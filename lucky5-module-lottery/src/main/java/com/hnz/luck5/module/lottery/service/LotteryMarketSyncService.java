package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueTransitionDO;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketConnectionDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueTransitionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.LotteryConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketConnectionMapper;
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
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls one system-level read-only draw source and distributes the same issue/result snapshot to every owner.
 * Owner market credentials remain isolated and are used only for that owner's connection/account information.
 */
@Service
public class LotteryMarketSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketSyncService.class);
    private static final Duration OWNER_CONNECTION_REFRESH_INTERVAL = Duration.ofSeconds(30);
    private final AtomicBoolean syncingAll = new AtomicBoolean();
    private final AtomicBoolean syncingConnections = new AtomicBoolean();
    private final AtomicBoolean settlingAll = new AtomicBoolean();
    private volatile long nextDrawSyncAtMillis;
    private volatile long activeDrawTimeMillis;
    private volatile long queuedDrawTimeMillis;

    @Resource private LotteryConfigMapper lotteryConfigMapper;
    @Resource private MarketConnectionMapper marketConnectionMapper;
    @Resource private IssueMapper issueMapper;
    @Resource private IssueTransitionMapper issueTransitionMapper;
    @Resource private ObjectMapper objectMapper;
    @Resource private MarketCredentialService credentialService;
    @Resource private Wa55MarketClient marketClient;
    @Resource private LotteryMarketAccountLockService accountLockService;
    @Resource private LotteryDrawVerificationService drawVerificationService;
    @Resource private TransactionTemplate transactionTemplate;
    @Lazy @Resource private LotteryService lotteryService;

    @Value("${lottery.market.draw-confirmation-delay-ms:5000}")
    private long drawConfirmationDelayMs;

    @Value("${lottery.market.sync-interval-ms:2000}")
    private long normalDrawSyncIntervalMs;

    @Value("${lottery.market.hot-sync-interval-ms:250}")
    private long hotDrawSyncIntervalMs;

    @Value("${lottery.market.hot-window-before-seconds:8}")
    private long hotWindowBeforeSeconds;

    @Value("${lottery.market.hot-window-after-seconds:45}")
    private long hotWindowAfterSeconds;

    @Value("${lottery.draw-source.tenant-id:1}")
    private Long drawSourceTenantId;

    @Value("${lottery.draw-source.user-id:1}")
    private Long drawSourceUserId;

    @Scheduled(initialDelayString = "${lottery.market.initial-delay-ms:5000}",
            fixedDelayString = "${lottery.market.sync-tick-ms:50}")
    public void syncAllConfigured() {
        long startedAt = System.currentTimeMillis();
        if (startedAt < nextDrawSyncAtMillis) return;
        if (!syncingAll.compareAndSet(false, true)) return;
        try {
            List<LotteryConfigDO> configs = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    () -> lotteryConfigMapper.selectList(new LambdaQueryWrapper<LotteryConfigDO>()
                            .isNotNull(LotteryConfigDO::getUserId))));
            Wa55MarketClient.Snapshot snapshot = syncGlobalDraws(configs);
            if (snapshot != null) observeDrawTime(snapshot.issue().drawTime(), System.currentTimeMillis());
        } finally {
            long completedAt = System.currentTimeMillis();
            nextDrawSyncAtMillis = Math.max(completedAt, startedAt + drawSyncInterval(completedAt));
            syncingAll.set(false);
        }
    }

    @Scheduled(initialDelayString = "${lottery.market.connection-initial-delay-ms:15000}",
            fixedDelayString = "${lottery.market.connection-sync-tick-ms:5000}")
    public void syncAllOwnerConnections() {
        if (!syncingConnections.compareAndSet(false, true)) return;
        try {
            List<LotteryConfigDO> configs = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    () -> lotteryConfigMapper.selectList(new LambdaQueryWrapper<LotteryConfigDO>()
                            .isNotNull(LotteryConfigDO::getUserId))));
            for (LotteryConfigDO config : configs) {
                try {
                    TenantUtils.execute(config.getTenantId(), () ->
                            accountLockService.tryExecute(config.getTenantId(), config.getUserId(),
                                    () -> {
                                        if (ownerConnectionRefreshDue(config.getUserId(), LocalDateTime.now())) {
                                            syncOwnerConnection(config);
                                        }
                                    }));
                } catch (RuntimeException ex) {
                    LOGGER.warn("盘口账户同步失败 tenant={} user={}: {}", config.getTenantId(), config.getUserId(),
                            rootMessage(ex));
                }
            }
        } finally {
            syncingConnections.set(false);
        }
    }

    private boolean ownerConnectionRefreshDue(Long userId, LocalDateTime now) {
        MarketConnectionDO connection = findConnection(userId);
        LocalDateTime lastSyncAt = connection == null ? null : connection.getLastSyncAt();
        return ownerConnectionRefreshDue(lastSyncAt, now);
    }

    static boolean ownerConnectionRefreshDue(LocalDateTime lastSyncAt, LocalDateTime now) {
        return lastSyncAt == null || !lastSyncAt.plus(OWNER_CONNECTION_REFRESH_INTERVAL).isAfter(now);
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
        Wa55MarketClient.Snapshot snapshot = current == null
                ? marketClient.read(new Wa55MarketClient.Credentials(url, account, password), false)
                : accountLockService.execute(current.getTenantId(), current.getUserId(),
                        () -> marketClient.read(new Wa55MarketClient.Credentials(url, account, password), false));
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
                        ? "老板模式（使用系统开奖源）" : "未配置", "", "", null, "", false);
                return connectionMap(findConnection(userId));
            }
            if (isDrawSource(config)) {
                List<LotteryConfigDO> configs = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                        () -> lotteryConfigMapper.selectList(new LambdaQueryWrapper<LotteryConfigDO>()
                                .isNotNull(LotteryConfigDO::getUserId))));
                syncGlobalDraws(configs);
            } else {
                try {
                    accountLockService.execute(tenantId, userId, () -> syncOwnerConnection(config));
                } catch (RuntimeException ex) {
                    LOGGER.warn("盘口账户立即验证失败 tenant={} user={}: {}", tenantId, userId, rootMessage(ex));
                }
            }
            return connectionMap(findConnection(userId));
        });
    }

    /**
     * Verifies only the current owner's freshly persisted credentials. Unlike {@link #syncCurrent(Long, Long)},
     * this method does not distribute draw snapshots or trigger settlement when the owner is the shared draw source.
     */
    public Map<String, Object> verifyCurrentConnection(Long tenantId, Long userId) {
        return TenantUtils.execute(tenantId, () -> {
            LotteryConfigDO config = DataPermissionUtils.executeIgnore(() -> lotteryConfigMapper.selectOne(
                    new LambdaQueryWrapper<LotteryConfigDO>().eq(LotteryConfigDO::getUserId, userId).last("LIMIT 1")));
            if (config == null || !configured(config)) {
                updateConnection(userId, config != null && Boolean.TRUE.equals(config.getBossMode())
                        ? "老板模式（使用系统开奖源）" : "未配置", "", "", null, "", false);
                return connectionMap(findConnection(userId));
            }
            try {
                accountLockService.execute(tenantId, userId, () -> syncOwnerConnection(config));
            } catch (RuntimeException ex) {
                LOGGER.warn("盘口账户保存后验证失败 tenant={} user={}: {}", tenantId, userId, rootMessage(ex));
            }
            return connectionMap(findConnection(userId));
        });
    }

    /**
     * Updates the cached balance immediately from the pre-submit market snapshot. An asynchronous read follows and
     * replaces this estimate with the exact external balance, so this method never performs network I/O.
     */
    public void recordSuccessfulSubmission(Long userId, BigDecimal balanceBefore, BigDecimal acceptedAmount) {
        if (balanceBefore == null || acceptedAmount == null) return;
        BigDecimal estimated = balanceBefore.subtract(acceptedAmount).max(BigDecimal.ZERO);
        updateConnection(userId, "已连接", null, null, estimated, "", true);
    }

    /** Reads only the owner's account snapshot after a successful external order. */
    public void refreshOwnerBalance(Long tenantId, Long userId) {
        TenantUtils.execute(tenantId, () -> {
            LotteryConfigDO config = DataPermissionUtils.executeIgnore(() -> lotteryConfigMapper.selectOne(
                    new LambdaQueryWrapper<LotteryConfigDO>().eq(LotteryConfigDO::getUserId, userId).last("LIMIT 1")));
            if (!configured(config)) return;
            accountLockService.tryExecute(tenantId, userId, () -> {
                Wa55MarketClient.Snapshot snapshot = readSnapshot(config, false);
                updateConnection(userId, "已连接", snapshot.lineUrl(), snapshot.account().displayAccount(),
                        snapshot.account().balance(), "", true);
            });
        });
    }

    /** Returns the persisted connection snapshot and never contacts the external market. */
    public Map<String, Object> connectionSnapshot(Long tenantId, Long userId) {
        return TenantUtils.execute(tenantId, () -> connectionMap(findConnection(userId)));
    }

    private Wa55MarketClient.Snapshot syncGlobalDraws(List<LotteryConfigDO> configs) {
        LotteryConfigDO source = configs.stream().filter(this::isDrawSource).findFirst().orElse(null);
        if (!configured(source)) {
            LOGGER.warn("系统开奖源未配置 tenant={} user={}，本轮不更新期号和开奖结果",
                    drawSourceTenantId, drawSourceUserId);
            return null;
        }
        Wa55MarketClient.Snapshot snapshot;
        try {
            snapshot = TenantUtils.execute(source.getTenantId(), () -> readDrawSnapshot(source));
            TenantUtils.execute(source.getTenantId(), () -> updateConnection(source.getUserId(), "已连接",
                    snapshot.lineUrl(), null, null, "", true));
        } catch (RuntimeException ex) {
            TenantUtils.execute(source.getTenantId(), () -> updateConnection(source.getUserId(), "连接失败",
                    null, null, BigDecimal.ZERO, rootMessage(ex), false));
            LOGGER.warn("系统开奖源同步失败 tenant={} user={}: {}", source.getTenantId(), source.getUserId(),
                    rootMessage(ex));
            return null;
        }
        for (LotteryConfigDO target : configs) {
            try {
                TenantUtils.execute(target.getTenantId(), () -> {
                    boolean newlyOpened = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                            persistSharedSnapshot(target.getUserId(), snapshot)));
                    if (newlyOpened) {
                        lotteryService.handleMarketIssueOpened(target.getUserId(), snapshot.issue().period());
                    }
                });
            } catch (RuntimeException ex) {
                LOGGER.error("共享开奖分发失败 tenant={} user={}", target.getTenantId(), target.getUserId(), ex);
            }
        }
        // Settlement is intentionally left to settleAllPending() on another scheduler thread. Publishing the next
        // period and trusted result must never wait for payout, rebate or downstream order processing.
        return snapshot;
    }

    private synchronized void observeDrawTime(LocalDateTime drawTime, long nowMillis) {
        if (drawTime == null) return;
        long observedDrawTime = drawTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        promoteQueuedDrawTime(nowMillis);
        if (activeDrawTimeMillis == 0 || observedDrawTime < activeDrawTimeMillis) {
            activeDrawTimeMillis = observedDrawTime;
        } else if (observedDrawTime > activeDrawTimeMillis) {
            queuedDrawTimeMillis = Math.max(queuedDrawTimeMillis, observedDrawTime);
        }
    }

    private synchronized long drawSyncInterval(long nowMillis) {
        promoteQueuedDrawTime(nowMillis);
        long before = Math.max(0, hotWindowBeforeSeconds) * 1000;
        long after = Math.max(0, hotWindowAfterSeconds) * 1000;
        boolean hot = activeDrawTimeMillis > 0
                && nowMillis >= activeDrawTimeMillis - before
                && nowMillis <= activeDrawTimeMillis + after;
        return Math.max(100, hot ? hotDrawSyncIntervalMs : normalDrawSyncIntervalMs);
    }

    private void promoteQueuedDrawTime(long nowMillis) {
        long after = Math.max(0, hotWindowAfterSeconds) * 1000;
        if (activeDrawTimeMillis > 0 && nowMillis > activeDrawTimeMillis + after) {
            activeDrawTimeMillis = queuedDrawTimeMillis > activeDrawTimeMillis ? queuedDrawTimeMillis : 0;
            queuedDrawTimeMillis = 0;
        }
    }

    private void syncOwnerConnection(LotteryConfigDO config) {
        Long userId = config.getUserId();
        if (!configured(config)) {
            updateConnection(userId, Boolean.TRUE.equals(config.getBossMode())
                    ? "老板模式（使用系统开奖源）" : "未配置", "", "", null, "", false);
            return;
        }
        updateConnection(userId, "连接中", null, null, null, "", false);
        try {
            Wa55MarketClient.Snapshot snapshot = readSnapshot(config, false);
            updateConnection(userId, "已连接", snapshot.lineUrl(), snapshot.account().displayAccount(),
                    snapshot.account().balance(), "", true);
        } catch (RuntimeException ex) {
            updateConnection(userId, "连接失败", null, null, BigDecimal.ZERO, rootMessage(ex), false);
            throw ex;
        }
    }

    private Wa55MarketClient.Snapshot readSnapshot(LotteryConfigDO config, boolean includeDraws) {
        String password = credentialService.decrypt(config.getMarketPasswordEncrypted());
        return marketClient.read(new Wa55MarketClient.Credentials(
                config.getUpstreamUrl(), config.getUpstreamAccount(), password), includeDraws);
    }

    private Wa55MarketClient.Snapshot readDrawSnapshot(LotteryConfigDO config) {
        String password = credentialService.decrypt(config.getMarketPasswordEncrypted());
        return marketClient.readDrawSnapshot(new Wa55MarketClient.Credentials(
                config.getUpstreamUrl(), config.getUpstreamAccount(), password));
    }

    private boolean persistSharedSnapshot(Long userId, Wa55MarketClient.Snapshot snapshot) {
        LocalDateTime now = LocalDateTime.now();
        boolean newlyOpened = upsertCurrentIssue(userId, snapshot.issue(), now);
        for (Wa55MarketClient.Draw draw : snapshot.draws()) upsertDrawIssue(userId, draw, now);
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
                transition(userId, old.getPeriod(), "OPEN", "CLOSED", "系统新期开奖", "");
            }
        }
        if (issue == null) {
            issue = new IssueDO();
            issue.setUserId(userId);
            issue.setPeriod(snapshot.period());
            issue.setStatus(nextStatus);
            issue.setResult("");
            issue.setSource("系统开奖");
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
            issue.setSource("系统开奖");
            if (!terminal) issue.setError("");
            fillIssueSnapshot(issue, snapshot);
            issueMapper.updateById(issue);
        }
        if (!Objects.equals(oldStatus, nextStatus)) {
            transition(userId, snapshot.period(), oldStatus, nextStatus, "系统期号同步",
                    "{\"marketStatus\":" + snapshot.marketStatus() + "}");
        }
        return "OPEN".equals(nextStatus) && !"OPEN".equals(oldStatus);
    }

    private void fillIssueSnapshot(IssueDO issue, Wa55MarketClient.Issue snapshot) {
        issue.setMarketStatus(snapshot.marketStatus());
        issue.setRemainingSeconds(snapshot.remainingSeconds());
        issue.setServerTime(snapshot.serverTime());
        issue.setSourceObservedAt(snapshot.observedAt());
        issue.setNextPeriod(snapshot.nextPeriod());
        if (snapshot.drawTime() != null) issue.setDrawTime(snapshot.drawTime());
        issue.setRawSnapshot(snapshot.raw());
    }

    private void upsertDrawIssue(Long userId, Wa55MarketClient.Draw draw, LocalDateTime now) {
        if (!draw.period().matches("\\d{8,20}")) return;
        IssueDO issue = findIssue(userId, draw.period());
        if (issue != null
                && List.of("DRAWN", "SETTLING", "SETTLED").contains(issue.getStatus())
                && Objects.equals(issue.getResult(), draw.result())
                && (issue.getDrawTime() != null || draw.drawTime() == null)) {
            publishVerifiedDrawIfReady(userId, issue);
            return;
        }
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
            issue.setSource("系统开奖");
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
            issue.setSource("系统开奖");
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
        publishVerifiedDrawIfReady(userId, issue);
    }

    private void publishVerifiedDrawIfReady(Long userId, IssueDO issue) {
        if (issue == null || (issue.getDrawConfirmations() == null ? 0 : issue.getDrawConfirmations()) < 2
                || !List.of("DRAWN", "SETTLING", "SETTLED").contains(issue.getStatus())
                || !drawVerificationService.isTrusted(issue.getResult())) {
            return;
        }
        lotteryService.publishVerifiedDrawForUser(userId, issue.getPeriod(), issue.getResult());
    }

    private void settlePending(Long userId) {
        List<IssueDO> candidates = DataPermissionUtils.executeIgnore(() -> issueMapper.selectList(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId).eq(IssueDO::getStatus, "DRAWN")
                        .isNotNull(IssueDO::getResult).ne(IssueDO::getResult, "")
                        .ne(IssueDO::getResult, LotteryDrawVerificationService.ZERO_RESULT)
                        .ge(IssueDO::getDrawConfirmations, 2)
                        .orderByAsc(IssueDO::getPeriod).last("LIMIT 10")));
        for (IssueDO issue : candidates) {
            publishVerifiedDrawIfReady(userId, issue);
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
        transition.setDetail(normalizeTransitionDetail(detail));
        issueTransitionMapper.insert(transition);
    }

    String normalizeTransitionDetail(String detail) {
        if (detail == null || detail.isBlank()) return "{}";
        try {
            objectMapper.readTree(detail);
            return detail;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode().put("message", detail).toString();
        }
    }

    private boolean configured(LotteryConfigDO config) {
        return config != null && !trim(config.getUpstreamUrl()).isEmpty() && !trim(config.getUpstreamAccount()).isEmpty()
                && !trim(config.getMarketPasswordEncrypted()).isEmpty();
    }

    private boolean isDrawSource(LotteryConfigDO config) {
        return config != null && Objects.equals(config.getTenantId(), drawSourceTenantId)
                && Objects.equals(config.getUserId(), drawSourceUserId);
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
        String tlsMessage = "盘口HTTPS证书无效或域名已失效，请更新有效的网盘会员网址";
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(tlsMessage)) {
                return tlsMessage;
            }
            current = current.getCause();
        }
        current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String transitionSource(LotteryDrawVerificationService.Outcome outcome) {
        return switch (outcome) {
            case ABNORMAL -> "系统开奖异常";
            case CANDIDATE -> "系统开奖待确认";
            case VERIFIED -> "系统开奖确认";
            case CONFLICT -> "系统开奖冲突";
            case UNCHANGED -> "系统开奖同步";
        };
    }

    private String jsonValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Map<String, Object> issueMap(Wa55MarketClient.Issue issue) {
        return map("period", issue.period(), "status", issue.status(), "marketStatus", issue.marketStatus(),
                "remainingSeconds", issue.remainingSeconds(), "serverTime", issue.serverTime(),
                "nextPeriod", issue.nextPeriod(), "drawTime", issue.drawTime());
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
