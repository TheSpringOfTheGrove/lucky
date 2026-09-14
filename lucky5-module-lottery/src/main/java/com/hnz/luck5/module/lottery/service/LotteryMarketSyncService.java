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
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueTransitionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.LotteryConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketConnectionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls one system-level read-only draw source and distributes the same issue/result snapshot to every owner.
 * Owner market credentials remain isolated and are used only for that owner's connection/account information.
 */
@Service
public class LotteryMarketSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketSyncService.class);
    private static final Duration OWNER_CONNECTION_REFRESH_INTERVAL = Duration.ofMinutes(5);
    private final AtomicBoolean syncingAll = new AtomicBoolean();
    private final AtomicBoolean syncingConnections = new AtomicBoolean();
    private final AtomicBoolean settlingAll = new AtomicBoolean();
    private final AtomicBoolean repairingPeriodSummaries = new AtomicBoolean();
    private volatile long nextDrawSyncAtMillis;
    private volatile long activeDrawTimeMillis;
    private volatile long queuedDrawTimeMillis;

    @Resource private LotteryConfigMapper lotteryConfigMapper;
    @Resource private MarketConnectionMapper marketConnectionMapper;
    @Resource private IssueMapper issueMapper;
    @Resource private IssueTransitionMapper issueTransitionMapper;
    @Resource private OrderMapper orderMapper;
    @Resource private ObjectMapper objectMapper;
    @Resource private MarketCredentialService credentialService;
    @Resource private Wa55MarketClient marketClient;
    @Resource private LotteryMarketAccountLockService accountLockService;
    @Resource private LotteryDrawVerificationService drawVerificationService;
    @Resource private LotteryIssueFreshnessPolicy issueFreshnessPolicy;
    @Resource private LotteryPeriodSummaryService periodSummaryService;
    @Resource private TransactionTemplate transactionTemplate;
    @Lazy @Resource private LotteryService lotteryService;

    @Value("${lottery.market.draw-confirmation-delay-ms:5000}")
    private long drawConfirmationDelayMs;

    @Value("${lottery.market.settlement-recovery-seconds:120}")
    private long settlementRecoverySeconds;

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

    private record SnapshotPersistence(boolean newlyOpened, List<String> closedPeriods) {
    }

    private record PeriodSummaryPublication(Long tenantId, Long userId, String period) {
    }

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
                // A configured owner is explicitly verified when credentials are saved.  Periodic full read-only
                // logins create a second market session and can invalidate the session used by actual BatchBet
                // requests.  The global draw source is synchronized by syncAllConfigured(); leave owner account
                // verification on the explicit admin action instead of interrupting live player submissions.
                if (configured(config)) continue;
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
            LocalDateTime staleSettlementBefore = staleSettlementBefore(LocalDateTime.now());
            List<IssueDO> pending = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    () -> issueMapper.selectList(new LambdaQueryWrapper<IssueDO>()
                            .select(IssueDO::getTenantId, IssueDO::getUserId)
                            .and(status -> status.eq(IssueDO::getStatus, "DRAWN")
                                    .or(stale -> stale.eq(IssueDO::getStatus, "SETTLING")
                                            .and(started -> started.isNull(IssueDO::getSettlementStartedAt)
                                                    .or().le(IssueDO::getSettlementStartedAt, staleSettlementBefore))))
                            .isNotNull(IssueDO::getResult).ne(IssueDO::getResult, "")
                            .ne(IssueDO::getResult, LotteryDrawVerificationService.ZERO_RESULT)
                            .ge(IssueDO::getDrawConfirmations, 2)
                            // Select every affected owner once. A LIMIT on issue rows lets a pile of old or
                            // externally-blocked rows starve newer owners forever.
                            .groupBy(IssueDO::getTenantId, IssueDO::getUserId))));
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

    @Scheduled(initialDelayString = "${lottery.market.period-summary-repair-initial-delay-ms:30000}",
            fixedDelayString = "${lottery.market.period-summary-repair-interval-ms:60000}")
    public void repairRecentPeriodSummaries() {
        if (!repairingPeriodSummaries.compareAndSet(false, true)) return;
        try {
            List<LotteryConfigDO> configs = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    () -> lotteryConfigMapper.selectList(new LambdaQueryWrapper<LotteryConfigDO>()
                            .isNotNull(LotteryConfigDO::getUserId))));
            configs.stream().map(config -> config.getTenantId() + ":" + config.getUserId()).distinct().forEach(key -> {
                String[] parts = key.split(":", 2);
                Long tenantId = Long.valueOf(parts[0]);
                Long userId = Long.valueOf(parts[1]);
                try {
                    TenantUtils.execute(tenantId, () -> recentSummaryCandidatePeriods(userId).forEach(period ->
                            periodSummaryService.publish(userId, period)));
                } catch (RuntimeException ex) {
                    LOGGER.error("近期成功订单汇总补偿失败 tenant={} user={}", tenantId, userId, ex);
                }
            });
        } finally {
            repairingPeriodSummaries.set(false);
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
        // The write response is authoritative for a player's order.  A balance refresh used to perform another
        // full login immediately after it, which invalidated the write session before the next player could bet.
        // Keep the verified connection snapshot; the administrator's explicit connection check refreshes balance.
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
        List<PeriodSummaryPublication> summaryPublications = new ArrayList<>();
        for (LotteryConfigDO target : configs) {
            try {
                TenantUtils.execute(target.getTenantId(), () -> {
                    SnapshotPersistence persistence = transactionTemplate.execute(status ->
                            persistSharedSnapshot(target.getUserId(), snapshot));
                    if (persistence != null) {
                        persistence.closedPeriods().forEach(period -> summaryPublications.add(
                                new PeriodSummaryPublication(target.getTenantId(), target.getUserId(), period)));
                    }
                    if (persistence != null && persistence.newlyOpened()) {
                        lotteryService.handleMarketIssueOpened(target.getUserId(), snapshot.issue().period());
                    }
                });
            } catch (RuntimeException ex) {
                LOGGER.error("共享开奖分发失败 tenant={} user={}", target.getTenantId(), target.getUserId(), ex);
            }
        }
        // Publish summaries only after the shared issue/result snapshot has reached every owner. Summary formatting
        // and order reads must never delay another owner's draw display path.
        for (PeriodSummaryPublication publication : summaryPublications) {
            try {
                TenantUtils.execute(publication.tenantId(), () ->
                        periodSummaryService.publish(publication.userId(), publication.period()));
            } catch (RuntimeException ex) {
                LOGGER.error("期末成功订单汇总失败 tenant={} user={} period={}", publication.tenantId(),
                        publication.userId(), publication.period(), ex);
            }
        }
        // Settlement is intentionally left to settleAllPending() on another scheduler thread. Trusted numbers are
        // still published immediately, while the next answering period is released only after payout commits.
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

    private SnapshotPersistence persistSharedSnapshot(Long userId, Wa55MarketClient.Snapshot snapshot) {
        LocalDateTime now = LocalDateTime.now();
        SnapshotPersistence persistence = upsertCurrentIssue(userId, snapshot.issue(), now);
        LinkedHashSet<String> closedPeriods = new LinkedHashSet<>(persistence.closedPeriods());
        for (Wa55MarketClient.Draw draw : snapshot.draws()) {
            if (upsertDrawIssue(userId, draw, now)) {
                closedPeriods.add(draw.period());
            }
        }
        return new SnapshotPersistence(persistence.newlyOpened(), List.copyOf(closedPeriods));
    }

    private SnapshotPersistence upsertCurrentIssue(Long userId, Wa55MarketClient.Issue snapshot, LocalDateTime now) {
        if (snapshot.period() == null || !snapshot.period().matches("\\d{8,20}")) {
            return new SnapshotPersistence(false, List.of());
        }
        List<String> closedPeriods = new ArrayList<>();
        IssueDO issue = findIssue(userId, snapshot.period());
        String oldStatus = issue == null ? "NEW" : issue.getStatus();
        boolean terminal = issue != null && List.of("DRAW_PENDING", "DRAW_ABNORMAL", "DRAWN", "SETTLING", "SETTLED")
                .contains(issue.getStatus());
        String nextStatus = terminal ? issue.getStatus() : snapshot.status();
        List<IssueDO> previous = DataPermissionUtils.executeIgnore(() -> issueMapper.selectList(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId).eq(IssueDO::getStatus, "OPEN")
                        .ne(IssueDO::getPeriod, snapshot.period())));
        for (IssueDO old : previous) {
            if (!isLaterPeriod(snapshot.period(), old.getPeriod())) continue;
            boolean formallyOpened = old.getOpenedAt() != null;
            old.setStatus("CLOSED");
            old.setClosedAt(now);
            issueMapper.updateById(old);
            if (formallyOpened) {
                transition(userId, old.getPeriod(), "OPEN", "CLOSED", "系统新期开奖", "");
                closedPeriods.add(old.getPeriod());
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
            issue.setOpenedAt(null);
            issue.setClosedAt("CLOSED".equals(nextStatus) ? now : null);
            fillIssueSnapshot(issue, snapshot);
            issueMapper.insert(issue);
        } else {
            if ("CLOSED".equals(nextStatus) && issue.getClosedAt() == null) issue.setClosedAt(now);
            boolean clearStaleClosedAt = "OPEN".equals(nextStatus) && issue.getClosedAt() != null;
            if ("OPEN".equals(nextStatus)) issue.setClosedAt(null);
            issue.setStatus(nextStatus);
            issue.setSource("系统开奖");
            if (!terminal) issue.setError("");
            fillIssueSnapshot(issue, snapshot);
            issueMapper.updateById(issue);
            // MyBatis-Plus ignores null values in updateById by default. A period first arrives as a CLOSED preview,
            // so explicitly clear that preview timestamp when the same period becomes market-visible OPEN.
            if (clearStaleClosedAt) {
                issueMapper.update(null, new LambdaUpdateWrapper<IssueDO>()
                        .eq(IssueDO::getId, issue.getId()).eq(IssueDO::getUserId, userId)
                        .eq(IssueDO::getStatus, "OPEN").set(IssueDO::getClosedAt, null));
            }
        }
        if (!Objects.equals(oldStatus, nextStatus) && !"OPEN".equals(nextStatus)) {
            boolean formallyOpened = issue.getOpenedAt() != null;
            if (!"CLOSED".equals(nextStatus) || formallyOpened) {
                transition(userId, snapshot.period(), oldStatus, nextStatus, "系统期号同步",
                        "{\"marketStatus\":" + snapshot.marketStatus() + "}");
            }
            if ("CLOSED".equals(nextStatus) && formallyOpened) {
                closedPeriods.add(snapshot.period());
            }
        }
        boolean newlyOpened = "OPEN".equals(nextStatus)
                && tryOpenForAnswering(userId, issue, "NEW".equals(oldStatus) ? "NEW" : "WAITING_SETTLEMENT", now);
        return new SnapshotPersistence(newlyOpened, closedPeriods);
    }

    /**
     * Marks a market-visible OPEN issue as formally available for answering. The previous formally opened issue must
     * already be settled; otherwise this issue remains a harmless preview with {@code openedAt == null}.
     */
    private boolean tryOpenForAnswering(Long userId, IssueDO issue, String fromStatus, LocalDateTime now) {
        if (issue == null || !"OPEN".equals(issue.getStatus()) || issue.getOpenedAt() != null
                || issueFreshnessPolicy.isStale(issue) || issueFreshnessPolicy.isBettingClosed(issue)) {
            return false;
        }
        IssueDO previousIssue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                        .lt(IssueDO::getPeriod, issue.getPeriod())
                        .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        // Only the immediately preceding period can gate the next one. Searching every historical opened period
        // lets an old abandoned CLOSED row block all future periods even after many newer periods have settled.
        if (previousIssue != null && previousIssue.getOpenedAt() != null
                && !"SETTLED".equals(previousIssue.getStatus())) {
            return false;
        }
        int opened = issueMapper.update(null, new LambdaUpdateWrapper<IssueDO>()
                .eq(IssueDO::getId, issue.getId()).eq(IssueDO::getUserId, userId)
                .eq(IssueDO::getStatus, "OPEN").isNull(IssueDO::getOpenedAt)
                .set(IssueDO::getOpenedAt, now));
        if (opened != 1) return false;
        issue.setOpenedAt(now);
        transition(userId, issue.getPeriod(), fromStatus, "OPEN", "结算完成后开始答题", "");
        return true;
    }

    private String releaseWaitingIssue(Long userId, LocalDateTime now) {
        IssueDO waiting = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId).eq(IssueDO::getStatus, "OPEN")
                        .isNull(IssueDO::getOpenedAt).orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        return tryOpenForAnswering(userId, waiting, "WAITING_SETTLEMENT", now) ? waiting.getPeriod() : "";
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

    private boolean upsertDrawIssue(Long userId, Wa55MarketClient.Draw draw, LocalDateTime now) {
        if (!draw.period().matches("\\d{8,20}")) return false;
        IssueDO issue = findIssue(userId, draw.period());
        if (issue != null
                && List.of("DRAWN", "SETTLING", "SETTLED").contains(issue.getStatus())
                && Objects.equals(issue.getResult(), draw.result())
                && (issue.getDrawTime() != null || draw.drawTime() == null)) {
            publishVerifiedDrawIfReady(userId, issue);
            return false;
        }
        String oldStatus = issue == null ? "NEW" : issue.getStatus();
        boolean formallyOpened = issue != null && issue.getOpenedAt() != null;
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
        return formallyOpened && "OPEN".equals(oldStatus) && !"OPEN".equals(decision.status());
    }

    private List<String> recentSummaryCandidatePeriods(Long userId) {
        return DataPermissionUtils.executeIgnore(() -> issueMapper.selectList(new LambdaQueryWrapper<IssueDO>()
                        .eq(IssueDO::getUserId, userId)
                        .in(IssueDO::getStatus, "CLOSED", "DRAW_ABNORMAL", "DRAW_PENDING", "DRAWN", "SETTLING", "SETTLED")
                        .orderByDesc(IssueDO::getPeriod).last("LIMIT 20")))
                .stream().map(IssueDO::getPeriod).filter(Objects::nonNull).distinct().toList();
    }

    static boolean isLaterPeriod(String candidate, String current) {
        if (candidate == null || current == null || !candidate.matches("\\d{8,20}")
                || !current.matches("\\d{8,20}")) {
            return false;
        }
        if (candidate.length() != current.length()) {
            return candidate.length() > current.length();
        }
        return candidate.compareTo(current) > 0;
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
        LocalDateTime staleSettlementBefore = staleSettlementBefore(LocalDateTime.now());
        IssueDO liveCandidate = findLiveSettlementCandidate(userId, staleSettlementBefore);
        List<IssueDO> candidates = liveCandidate == null ? List.of() : List.of(liveCandidate);
        for (IssueDO issue : candidates) {
            publishVerifiedDrawIfReady(userId, issue);
            try {
                periodSummaryService.publish(userId, issue.getPeriod());
            } catch (RuntimeException ex) {
                LOGGER.error("期号 {} 用户 {} 结算前成功订单汇总失败", issue.getPeriod(), userId, ex);
                continue;
            }
            boolean recoveringStaleSettlement = "SETTLING".equals(issue.getStatus());
            if (!recoveringStaleSettlement) {
                int claimed = issueMapper.update(null, new LambdaUpdateWrapper<IssueDO>()
                        .eq(IssueDO::getId, issue.getId()).eq(IssueDO::getUserId, userId)
                        .eq(IssueDO::getStatus, "DRAWN")
                        .eq(IssueDO::getResult, issue.getResult()).ge(IssueDO::getDrawConfirmations, 2)
                        .set(IssueDO::getStatus, "SETTLING").set(IssueDO::getSettlementStartedAt, LocalDateTime.now())
                        .set(IssueDO::getError, ""));
                if (claimed != 1) continue;
                transition(userId, issue.getPeriod(), "DRAWN", "SETTLING", "自动结算", "");
            } else {
                int claimed = issueMapper.update(null, new LambdaUpdateWrapper<IssueDO>()
                        .eq(IssueDO::getId, issue.getId()).eq(IssueDO::getUserId, userId)
                        .eq(IssueDO::getStatus, "SETTLING")
                        .and(started -> started.isNull(IssueDO::getSettlementStartedAt)
                                .or().le(IssueDO::getSettlementStartedAt, staleSettlementBefore))
                        .set(IssueDO::getSettlementStartedAt, LocalDateTime.now()));
                if (claimed != 1) continue;
                LOGGER.warn("检测到超时结算并自动接管 user={} period={} startedAt={}", userId,
                        issue.getPeriod(), issue.getSettlementStartedAt());
            }
            try {
                lotteryService.settlePeriodForUser(userId, issue.getPeriod(), issue.getResult(), "system");
            } catch (RuntimeException ex) {
                // A freshly claimed failure is returned to DRAWN for the normal retry loop. A stale SETTLING row
                // may still be owned by another slow instance, so leave it claimed and retry idempotently later.
                if (!recoveringStaleSettlement) {
                    issueMapper.update(null, new LambdaUpdateWrapper<IssueDO>()
                            .eq(IssueDO::getId, issue.getId()).eq(IssueDO::getUserId, userId)
                            .eq(IssueDO::getStatus, "SETTLING")
                            .set(IssueDO::getStatus, "DRAWN").set(IssueDO::getError, rootMessage(ex)));
                    transition(userId, issue.getPeriod(), "SETTLING", "DRAWN", "结算失败", rootMessage(ex));
                }
                LOGGER.error("期号 {} 用户 {} 自动结算失败", issue.getPeriod(), userId, ex);
                continue;
            }
            try {
                String openedPeriod = transactionTemplate.execute(status ->
                        releaseWaitingIssue(userId, LocalDateTime.now()));
                if (openedPeriod != null && !openedPeriod.isBlank()) {
                    lotteryService.handleMarketIssueOpened(userId, openedPeriod);
                }
            } catch (RuntimeException ex) {
                // Payout has committed at this point. Never roll it back or mislabel it as failed merely because the
                // next answering period could not be released; the normal snapshot poll will retry the release.
                LOGGER.error("期号 {} 用户 {} 结算完成，但下一期答题开放失败", issue.getPeriod(), userId, ex);
            }
        }
    }

    /**
     * Recovers only a settlement that can still block the live flow. Historical SETTLING rows may require manual
     * reconciliation, but retrying all of them every five seconds can starve current DRAWN periods and flood logs.
     */
    private IssueDO findLiveSettlementCandidate(Long userId, LocalDateTime staleBefore) {
        IssueDO latest = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                        .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        if (latest == null) return null;
        IssueDO candidate = latest;
        if (!List.of("DRAWN", "SETTLING").contains(candidate.getStatus())) {
            if (latest.getOpenedAt() != null) return null;
            candidate = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                    new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                            .lt(IssueDO::getPeriod, latest.getPeriod())
                            .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        }
        if (isReadyDrawnSettlement(candidate)) return candidate;
        if (isRecoverableStaleSettlement(candidate, staleBefore)) return candidate;
        return findAcceptedWithoutIdentifiersHistoricalCandidate(userId);
    }

    /**
     * Old DRAWN issues normally stay untouched so they cannot crowd out the live settlement queue. The sole
     * exception is a market order whose batch was conclusively accepted (same count and amount) and was later
     * promoted from MANUAL_REVIEW because the market never exposed per-row ids. These orders are safe to settle,
     * but they were previously unable to re-enter the live-only queue.
     */
    private IssueDO findAcceptedWithoutIdentifiersHistoricalCandidate(Long userId) {
        List<OrderDO> accepted = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(
                new LambdaQueryWrapper<OrderDO>().select(OrderDO::getPeriod).eq(OrderDO::getUserId, userId)
                        .eq(OrderDO::getStatus, "未开奖").eq(OrderDO::getMarketStatus, "CONFIRMED")
                        .likeRight(OrderDO::getMarketError, "盘口已明确受理，逐注明细编号未返回")));
        Set<String> periods = accepted.stream().map(OrderDO::getPeriod)
                .filter(period -> period != null && !period.isBlank()).collect(java.util.stream.Collectors.toSet());
        if (periods.isEmpty()) return null;
        List<IssueDO> candidates = DataPermissionUtils.executeIgnore(() -> issueMapper.selectList(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId).in(IssueDO::getPeriod, periods)
                        .eq(IssueDO::getStatus, "DRAWN").isNotNull(IssueDO::getResult).ne(IssueDO::getResult, "")
                        .ne(IssueDO::getResult, LotteryDrawVerificationService.ZERO_RESULT)
                        .ge(IssueDO::getDrawConfirmations, 2).orderByAsc(IssueDO::getPeriod).last("LIMIT 1")));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    static boolean isReadyDrawnSettlement(IssueDO issue) {
        return issue != null && "DRAWN".equals(issue.getStatus())
                && issue.getResult() != null && !issue.getResult().isBlank()
                && !LotteryDrawVerificationService.ZERO_RESULT.equals(issue.getResult())
                && (issue.getDrawConfirmations() == null ? 0 : issue.getDrawConfirmations()) >= 2;
    }

    static boolean isRecoverableStaleSettlement(IssueDO issue, LocalDateTime staleBefore) {
        return issue != null && "SETTLING".equals(issue.getStatus())
                && issue.getResult() != null && !issue.getResult().isBlank()
                && !LotteryDrawVerificationService.ZERO_RESULT.equals(issue.getResult())
                && (issue.getDrawConfirmations() == null ? 0 : issue.getDrawConfirmations()) >= 2
                && (issue.getSettlementStartedAt() == null
                || !issue.getSettlementStartedAt().isAfter(staleBefore));
    }

    private LocalDateTime staleSettlementBefore(LocalDateTime now) {
        return now.minusSeconds(Math.max(30, settlementRecoverySeconds));
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
