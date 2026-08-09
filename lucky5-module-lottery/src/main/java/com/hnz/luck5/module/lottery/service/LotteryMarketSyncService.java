package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueTransitionDO;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketConnectionDO;
import com.hnz.luck5.module.lottery.dal.dataobject.SystemStateDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueTransitionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.LotteryConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketConnectionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.SystemStateMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the read-only market API for every tenant/user configuration and drives issue settlement.
 */
@Service
public class LotteryMarketSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketSyncService.class);
    private final AtomicBoolean syncingAll = new AtomicBoolean();
    private final AtomicBoolean settlingAll = new AtomicBoolean();

    @Resource private LotteryConfigMapper lotteryConfigMapper;
    @Resource private MarketConnectionMapper marketConnectionMapper;
    @Resource private IssueMapper issueMapper;
    @Resource private IssueTransitionMapper issueTransitionMapper;
    @Resource private SystemStateMapper systemStateMapper;
    @Resource private MarketCredentialService credentialService;
    @Resource private Wa55MarketClient marketClient;
    @Resource private LotteryDrawVerificationService drawVerificationService;
    @Resource private TransactionTemplate transactionTemplate;
    @Lazy @Resource private LotteryService lotteryService;

    @Value("${lottery.market.draw-confirmation-delay-ms:5000}")
    private long drawConfirmationDelayMs;

    @Scheduled(initialDelayString = "${lottery.market.initial-delay-ms:15000}",
            fixedDelayString = "${lottery.market.sync-interval-ms:30000}")
    public void syncAllConfigured() {
        if (!syncingAll.compareAndSet(false, true)) return;
        try {
            List<LotteryConfigDO> configs = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    () -> lotteryConfigMapper.selectList(new LambdaQueryWrapper<LotteryConfigDO>()
                            .isNotNull(LotteryConfigDO::getUserId))));
            for (LotteryConfigDO config : configs) {
                if (!configured(config)) continue;
                try {
                    TenantUtils.execute(config.getTenantId(), () -> syncConfigured(config));
                } catch (RuntimeException ex) {
                    LOGGER.warn("盘口同步失败 tenant={} user={}: {}", config.getTenantId(), config.getUserId(),
                            rootMessage(ex));
                }
            }
        } finally {
            syncingAll.set(false);
        }
    }

    @Scheduled(initialDelayString = "${lottery.market.settlement-initial-delay-ms:20000}",
            fixedDelayString = "${lottery.market.settlement-interval-ms:5000}")
    public void settleAllPending() {
        if (!settlingAll.compareAndSet(false, true)) return;
        try {
            List<IssueDO> pending = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    () -> issueMapper.selectList(new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getStatus, "DRAWN")
                            .isNotNull(IssueDO::getResult).ne(IssueDO::getResult, "")
                            .ne(IssueDO::getResult, LotteryDrawVerificationService.ZERO_RESULT)
                            .ge(IssueDO::getDrawConfirmations, 2)
                            .orderByAsc(IssueDO::getPeriod).last("LIMIT 200"))));
            pending.stream().map(issue -> issue.getTenantId() + ":" + issue.getUserId()).distinct().forEach(key -> {
                String[] parts = key.split(":", 2);
                Long tenantId = Long.valueOf(parts[0]);
                Long userId = Long.valueOf(parts[1]);
                try {
                    TenantUtils.execute(tenantId, () -> settlePending(userId));
                } catch (RuntimeException ex) {
                    LOGGER.error("待开奖自动结算失败 tenant={} user={}", tenantId, userId, ex);
                }
            });
        } finally {
            settlingAll.set(false);
        }
    }

    public Map<String, Object> test(LotteryReqVO.Config input, LotteryConfigDO current) {
        String url = trim(input.getUrl());
        String account = trim(input.getAccount());
        String password = usablePassword(input.getPassword()) ? input.getPassword().trim()
                : current == null ? "" : credentialService.decrypt(current.getMarketPasswordEncrypted());
        requireComplete(url, account, password);
        Wa55MarketClient.Snapshot snapshot = marketClient.read(new Wa55MarketClient.Credentials(url, account, password), false);
        return map("configured", true, "connected", true, "status", "已连接", "lineUrl", snapshot.lineUrl(),
                "account", map("displayAccount", snapshot.account().displayAccount(), "balance", snapshot.account().balance()),
                "issue", issueMap(snapshot.issue()));
    }

    public Map<String, Object> syncCurrent(Long tenantId, Long userId) {
        return TenantUtils.execute(tenantId, () -> {
            LotteryConfigDO config = DataPermissionUtils.executeIgnore(() -> lotteryConfigMapper.selectOne(
                    new LambdaQueryWrapper<LotteryConfigDO>().eq(LotteryConfigDO::getUserId, userId).last("LIMIT 1")));
            if (config == null || !configured(config)) {
                updateConnection(userId, config != null && Boolean.TRUE.equals(config.getBossMode())
                        ? "老板模式（未配置盘口）" : "未配置", "", "", null, "", false);
                return connectionMap(findConnection(userId));
            }
            syncConfigured(config);
            return connectionMap(findConnection(userId));
        });
    }

    private void syncConfigured(LotteryConfigDO config) {
        Long userId = config.getUserId();
        updateConnection(userId, "连接中", null, null, null, "", false);
        try {
            String password = credentialService.decrypt(config.getMarketPasswordEncrypted());
            Wa55MarketClient.Snapshot snapshot = marketClient.read(new Wa55MarketClient.Credentials(
                    config.getUpstreamUrl(), config.getUpstreamAccount(), password), true);
            boolean newlyOpened = Boolean.TRUE.equals(transactionTemplate.execute(status -> persistSnapshot(config, snapshot)));
            if (newlyOpened) lotteryService.handleMarketIssueOpened(userId, snapshot.issue().period());
            settlePending(config.getUserId());
        } catch (RuntimeException ex) {
            updateConnection(userId, "连接失败", null, null, BigDecimal.ZERO, rootMessage(ex), false);
            throw ex;
        }
    }

    private boolean persistSnapshot(LotteryConfigDO config, Wa55MarketClient.Snapshot snapshot) {
        Long userId = config.getUserId();
        LocalDateTime now = LocalDateTime.now();
        updateConnection(userId, "已连接", snapshot.lineUrl(), snapshot.account().displayAccount(),
                snapshot.account().balance(), "", true);
        boolean newlyOpened = upsertCurrentIssue(userId, snapshot.issue(), now);
        for (Wa55MarketClient.Draw draw : snapshot.draws()) upsertDrawIssue(userId, draw, now);
        updateRoomState(userId, "OPEN".equals(snapshot.issue().status()));
        return newlyOpened;
    }

    private boolean upsertCurrentIssue(Long userId, Wa55MarketClient.Issue snapshot, LocalDateTime now) {
        if (snapshot.period() == null || !snapshot.period().matches("\\d{8,20}")) return false;
        IssueDO issue = findIssue(userId, snapshot.period());
        String oldStatus = issue == null ? "NEW" : issue.getStatus();
        boolean terminal = issue != null && List.of("DRAW_PENDING", "DRAW_ABNORMAL", "DRAWN", "SETTLING", "SETTLED")
                .contains(issue.getStatus());
        String nextStatus = terminal ? issue.getStatus() : snapshot.status();
        if ("OPEN".equals(nextStatus)) {
            List<IssueDO> previous = DataPermissionUtils.executeIgnore(() -> issueMapper.selectList(
                    new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId).eq(IssueDO::getStatus, "OPEN")
                            .ne(IssueDO::getPeriod, snapshot.period())));
            for (IssueDO old : previous) {
                old.setStatus("CLOSED");
                old.setClosedAt(now);
                issueMapper.updateById(old);
                transition(userId, old.getPeriod(), "OPEN", "CLOSED", "盘口新期开盘", "");
            }
        }
        if (issue == null) {
            issue = new IssueDO();
            issue.setUserId(userId);
            issue.setPeriod(snapshot.period());
            issue.setStatus(nextStatus);
            issue.setResult("");
            issue.setSource("盘口");
            issue.setError("");
            issue.setOrderSequence(0);
            issue.setDrawConfirmations(0);
            issue.setOpenedAt("OPEN".equals(nextStatus) ? now : null);
            issue.setClosedAt("CLOSED".equals(nextStatus) ? now : null);
            fillIssueSnapshot(issue, snapshot);
            issueMapper.insert(issue);
        } else {
            if ("OPEN".equals(nextStatus) && issue.getOpenedAt() == null) issue.setOpenedAt(now);
            if ("CLOSED".equals(nextStatus) && issue.getClosedAt() == null) issue.setClosedAt(now);
            issue.setStatus(nextStatus);
            issue.setSource("盘口");
            if (!terminal) issue.setError("");
            fillIssueSnapshot(issue, snapshot);
            issueMapper.updateById(issue);
        }
        if (!Objects.equals(oldStatus, nextStatus)) {
            transition(userId, snapshot.period(), oldStatus, nextStatus, "盘口期号同步",
                    "{\"marketStatus\":" + snapshot.marketStatus() + "}");
        }
        return "OPEN".equals(nextStatus) && !"OPEN".equals(oldStatus);
    }

    private void fillIssueSnapshot(IssueDO issue, Wa55MarketClient.Issue snapshot) {
        issue.setMarketStatus(snapshot.marketStatus());
        issue.setRemainingSeconds(snapshot.remainingSeconds());
        issue.setServerTime(snapshot.serverTime());
        issue.setNextPeriod(snapshot.nextPeriod());
        issue.setRawSnapshot(snapshot.raw());
    }

    private void upsertDrawIssue(Long userId, Wa55MarketClient.Draw draw, LocalDateTime now) {
        if (!draw.period().matches("\\d{8,20}")) return;
        IssueDO issue = findIssue(userId, draw.period());
        String oldStatus = issue == null ? "NEW" : issue.getStatus();
        LotteryDrawVerificationService.Decision decision = drawVerificationService.evaluate(oldStatus,
                issue == null ? "" : issue.getResult(), issue == null ? 0 : issue.getDrawConfirmations(),
                issue == null ? null : issue.getDrawFirstSeenAt(), draw.result(), now,
                Duration.ofMillis(Math.max(0, drawConfirmationDelayMs)));
        if (issue == null) {
            issue = new IssueDO();
            issue.setUserId(userId);
            issue.setPeriod(draw.period());
            issue.setStatus(decision.status());
            issue.setRemainingSeconds(0);
            issue.setNextPeriod("");
            issue.setResult(decision.result());
            issue.setDrawConfirmations(decision.confirmations());
            issue.setDrawFirstSeenAt(decision.firstSeenAt());
            issue.setSource("盘口");
            issue.setRawSnapshot(draw.raw());
            issue.setError(decision.error());
            issue.setOrderSequence(0);
            issue.setDrawTime(draw.drawTime());
            issue.setDrawUpdatedAt(draw.updatedAt());
            issueMapper.insert(issue);
        } else {
            issue.setStatus(decision.status());
            issue.setResult(decision.result());
            issue.setDrawConfirmations(decision.confirmations());
            issue.setDrawFirstSeenAt(decision.firstSeenAt());
            issue.setSource("盘口");
            issue.setRawSnapshot(draw.raw());
            issue.setError(decision.error());
            issue.setDrawTime(draw.drawTime());
            issue.setDrawUpdatedAt(draw.updatedAt());
            issueMapper.updateById(issue);
        }
        if (!Objects.equals(oldStatus, decision.status()) || decision.outcome() == LotteryDrawVerificationService.Outcome.CONFLICT) {
            transition(userId, draw.period(), oldStatus, decision.status(), transitionSource(decision.outcome()),
                    "{\"apiResult\":\"" + jsonValue(draw.result()) + "\",\"confirmations\":"
                            + decision.confirmations() + "}");
        }
    }

    private void settlePending(Long userId) {
        List<IssueDO> candidates = DataPermissionUtils.executeIgnore(() -> issueMapper.selectList(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId).eq(IssueDO::getStatus, "DRAWN")
                        .isNotNull(IssueDO::getResult).ne(IssueDO::getResult, "")
                        .ne(IssueDO::getResult, LotteryDrawVerificationService.ZERO_RESULT)
                        .ge(IssueDO::getDrawConfirmations, 2)
                        .orderByAsc(IssueDO::getPeriod).last("LIMIT 10")));
        for (IssueDO issue : candidates) {
            int claimed = issueMapper.update(null, new LambdaUpdateWrapper<IssueDO>()
                    .eq(IssueDO::getId, issue.getId()).eq(IssueDO::getUserId, userId).eq(IssueDO::getStatus, "DRAWN")
                    .eq(IssueDO::getResult, issue.getResult()).ge(IssueDO::getDrawConfirmations, 2)
                    .set(IssueDO::getStatus, "SETTLING").set(IssueDO::getSettlementStartedAt, LocalDateTime.now())
                    .set(IssueDO::getError, ""));
            if (claimed != 1) continue;
            transition(userId, issue.getPeriod(), "DRAWN", "SETTLING", "自动结算", "");
            try {
                lotteryService.settlePeriodForUser(userId, issue.getPeriod(), issue.getResult(), "system");
            } catch (RuntimeException ex) {
                issueMapper.update(null, new LambdaUpdateWrapper<IssueDO>()
                        .eq(IssueDO::getId, issue.getId()).eq(IssueDO::getUserId, userId)
                        .eq(IssueDO::getStatus, "SETTLING")
                        .set(IssueDO::getStatus, "DRAWN").set(IssueDO::getError, rootMessage(ex)));
                transition(userId, issue.getPeriod(), "SETTLING", "DRAWN", "结算失败", rootMessage(ex));
                LOGGER.error("期号 {} 用户 {} 自动结算失败", issue.getPeriod(), userId, ex);
            }
        }
    }

    private void updateConnection(Long userId, String status, String lineUrl, String account, BigDecimal balance,
                                  String error, boolean synced) {
        DataPermissionUtils.executeIgnore(() -> {
            MarketConnectionDO connection = findConnection(userId);
            boolean create = connection == null;
            if (create) {
                connection = new MarketConnectionDO();
                connection.setUserId(userId);
            }
            connection.setStatus(status);
            if (lineUrl != null) connection.setLineUrl(lineUrl);
            if (account != null) connection.setDisplayAccount(account);
            if (balance != null) connection.setBalance(balance);
            connection.setError(error == null ? "" : error);
            if (synced) {
                connection.setLastLoginAt(LocalDateTime.now());
                connection.setLastSyncAt(LocalDateTime.now());
            }
            if (create) marketConnectionMapper.insert(connection); else marketConnectionMapper.updateById(connection);
        });
    }

    private void updateRoomState(Long userId, boolean open) {
        SystemStateDO state = DataPermissionUtils.executeIgnore(() -> systemStateMapper.selectOne(
                new LambdaQueryWrapper<SystemStateDO>().eq(SystemStateDO::getUserId, userId).last("LIMIT 1")));
        if (state == null) {
            state = new SystemStateDO();
            state.setUserId(userId);
            state.setOperatorUsername(String.valueOf(userId));
            state.setExpireAt(LocalDateTime.of(2099, 12, 31, 23, 59, 59));
            state.setOnline(0);
            state.setRoomOpen(open);
            systemStateMapper.insert(state);
        } else if (!Objects.equals(state.getRoomOpen(), open)) {
            state.setRoomOpen(open);
            systemStateMapper.updateById(state);
        }
    }

    private IssueDO findIssue(Long userId, String period) {
        return DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                .eq(IssueDO::getUserId, userId).eq(IssueDO::getPeriod, period).last("LIMIT 1")));
    }

    private MarketConnectionDO findConnection(Long userId) {
        return DataPermissionUtils.executeIgnore(() -> marketConnectionMapper.selectOne(
                new LambdaQueryWrapper<MarketConnectionDO>().eq(MarketConnectionDO::getUserId, userId).last("LIMIT 1")));
    }

    private void transition(Long userId, String period, String from, String to, String source, String detail) {
        IssueTransitionDO transition = new IssueTransitionDO();
        transition.setUserId(userId);
        transition.setPeriod(period);
        transition.setFromStatus(from == null ? "" : from);
        transition.setToStatus(to);
        transition.setSource(source);
        transition.setDetail(detail == null || detail.isBlank() ? "{}" : detail);
        issueTransitionMapper.insert(transition);
    }

    private boolean configured(LotteryConfigDO config) {
        return config != null && !trim(config.getUpstreamUrl()).isEmpty() && !trim(config.getUpstreamAccount()).isEmpty()
                && !trim(config.getMarketPasswordEncrypted()).isEmpty();
    }

    private void requireComplete(String url, String account, String password) {
        if (url.isEmpty() || account.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException("请填写完整的盘口网址、账号和密码");
        }
    }

    private boolean usablePassword(String value) {
        return value != null && !value.isBlank() && !"********".equals(value);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String transitionSource(LotteryDrawVerificationService.Outcome outcome) {
        return switch (outcome) {
            case ABNORMAL -> "盘口开奖异常";
            case CANDIDATE -> "盘口开奖待确认";
            case VERIFIED -> "盘口开奖确认";
            case CONFLICT -> "盘口开奖冲突";
            case UNCHANGED -> "盘口开奖同步";
        };
    }

    private String jsonValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Map<String, Object> issueMap(Wa55MarketClient.Issue issue) {
        return map("period", issue.period(), "status", issue.status(), "marketStatus", issue.marketStatus(),
                "remainingSeconds", issue.remainingSeconds(), "serverTime", issue.serverTime(), "nextPeriod", issue.nextPeriod());
    }

    private Map<String, Object> connectionMap(MarketConnectionDO item) {
        return map("status", item == null ? "未配置" : item.getStatus(), "lineUrl", item == null ? "" : item.getLineUrl(),
                "displayAccount", item == null ? "" : item.getDisplayAccount(), "balance", item == null ? null : item.getBalance(),
                "error", item == null ? "" : item.getError(), "lastLoginAt", item == null ? null : item.getLastLoginAt(),
                "lastSyncAt", item == null ? null : item.getLastSyncAt());
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
