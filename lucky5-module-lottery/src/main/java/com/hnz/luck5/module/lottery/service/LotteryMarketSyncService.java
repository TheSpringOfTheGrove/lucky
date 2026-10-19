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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls a market clock and independent read-only result sources, then distributes accepted results to every owner.
 * Owner market credentials remain isolated and are used only for that owner's connection/account information.
 */
@Service
public class LotteryMarketSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketSyncService.class);
    private static final Duration OWNER_CONNECTION_REFRESH_INTERVAL = Duration.ofSeconds(30);
    private final AtomicBoolean syncingAll = new AtomicBoolean();
    private final AtomicBoolean syncingConnections = new AtomicBoolean();
    private final AtomicBoolean settlingAll = new AtomicBoolean();
    private final Set<String> settlingOwners = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean repairingPeriodSummaries = new AtomicBoolean();
    private final AtomicBoolean repairingDrawMessages = new AtomicBoolean();
    private final AtomicBoolean repairingMissingDraws = new AtomicBoolean();
    private final AtomicBoolean syncingBackupDraws = new AtomicBoolean();
    private final AtomicInteger fallbackClockCursor = new AtomicInteger();
    private final Map<String, String> drawSourceClaims = new ConcurrentHashMap<>();
    private static final String MARKET_DRAW_SOURCE = "系统开奖";
    private static final String BY123_DRAW_SOURCE = "by123开奖";
    // Scheduler threads have no tenant context before a mapper call. Keep the polling cadence in memory and let
    // refreshOwnerBalance enter the tenant scope itself, instead of querying a tenant table merely to decide if a
    // refresh is due. The persisted lastSyncAt remains the audit/status timestamp.
    private final Map<String, LocalDateTime> ownerBalanceRefreshAt = new ConcurrentHashMap<>();
    private volatile long nextDrawSyncAtMillis;
    private volatile long activeDrawTimeMillis;
    private volatile long queuedDrawTimeMillis;
    private volatile String preferredClockOwnerKey;

    @Resource private LotteryConfigMapper lotteryConfigMapper;
    @Resource private MarketConnectionMapper marketConnectionMapper;
    @Resource private IssueMapper issueMapper;
    @Resource private IssueTransitionMapper issueTransitionMapper;
    @Resource private OrderMapper orderMapper;
    @Resource private ObjectMapper objectMapper;
    @Resource private MarketCredentialService credentialService;
    @Resource private Wa55MarketClient marketClient;
    @Resource private Wa55MarketOrderClient marketOrderClient;
    @Resource private By123DrawClient by123DrawClient;
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Lazy @Resource private LotteryMarketBalanceRefreshService balanceRefreshService;
    @Resource private LotteryDrawVerificationService drawVerificationService;
    @Resource private LotteryIssueFreshnessPolicy issueFreshnessPolicy;
    @Resource private LotteryPeriodSummaryService periodSummaryService;
    @Resource private TransactionTemplate transactionTemplate;
    @Resource(name = "lotterySettlementExecutor") private Executor settlementExecutor;
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

    private record SnapshotPersistence(boolean newlyOpened, List<String> closedPeriods,
                                       List<String> newlyDrawnPeriods) {
    }

    private record DrawDistribution(List<String> closedPeriods, List<String> newlyDrawnPeriods) {
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
            startBackupDrawSync(configs);
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
                    if (!configured(config)) {
                        TenantUtils.execute(config.getTenantId(), () -> syncOwnerConnection(config));
                    } else if (ownerConnectionRefreshDue(ownerBalanceRefreshAt.get(ownerRefreshKey(config)),
                            LocalDateTime.now())) {
                        // Reads use their own executor and never take the BatchBet write lock.  A completed read
                        // writes lastSyncAt, so the next scheduled read is exactly 30 seconds later.
                        balanceRefreshService.refresh(config.getTenantId(), config.getUserId());
                    }
                } catch (RuntimeException ex) {
                    LOGGER.warn("盘口账户同步失败 tenant={} user={}: {}", config.getTenantId(), config.getUserId(),
                            rootMessage(ex));
                }
            }
        } finally {
            syncingConnections.set(false);
        }
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
            pending.stream().map(issue -> issue.getTenantId() + ":" + issue.getUserId()).distinct()
                    .forEach(key -> {
                        String[] parts = key.split(":", 2);
                        dispatchSettlement(Long.valueOf(parts[0]), Long.valueOf(parts[1]));
                    });
        } finally {
            settlingAll.set(false);
        }
    }

    /**
     * A result can fall out of the upstream's short history before every owner receives it. A previously settled
     * owner is a durable, twice-confirmed reference for the same shared draw; replay it only to owners whose last
     * formally opened period is blocking their current preview. Normal draw verification and settlement still apply.
     */
    @Scheduled(initialDelayString = "${lottery.market.missing-draw-repair-initial-delay-ms:45000}",
            fixedDelayString = "${lottery.market.missing-draw-repair-interval-ms:15000}")
    public void repairLiveMissingDraws() {
        if (!repairingMissingDraws.compareAndSet(false, true)) return;
        try {
            List<IssueDO> waiting = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(() ->
                    issueMapper.selectList(new LambdaQueryWrapper<IssueDO>()
                            .eq(IssueDO::getStatus, "OPEN").isNull(IssueDO::getOpenedAt)
                            .orderByDesc(IssueDO::getPeriod).last("LIMIT 100"))));
            Set<String> visitedOwners = new LinkedHashSet<>();
            for (IssueDO preview : waiting) {
                String ownerKey = preview.getTenantId() + ":" + preview.getUserId();
                if (!visitedOwners.add(ownerKey)) continue;
                try {
                    TenantUtils.execute(preview.getTenantId(), () -> {
                        IssueDO blocker = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, preview.getUserId())
                                        .lt(IssueDO::getPeriod, preview.getPeriod())
                                        .isNotNull(IssueDO::getOpenedAt)
                                        .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
                        if (!isMissingDrawBlocker(blocker, LocalDateTime.now())) return null;
                        List<IssueDO> references = DataPermissionUtils.executeIgnore(() -> issueMapper.selectList(
                                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getPeriod, blocker.getPeriod())
                                        .eq(IssueDO::getStatus, "SETTLED")
                                        .ge(IssueDO::getDrawConfirmations, 2)));
                        IssueDO reference = trustedSharedReference(references,
                                blocker.getDrawFirstSeenAt() == null ? null : blocker.getSource());
                        if (reference == null || !blocker.getPeriod().equals(reference.getPeriod())) return null;
                        Wa55MarketClient.Draw draw = new Wa55MarketClient.Draw(reference.getPeriod(),
                                reference.getResult(), reference.getDrawTime(), reference.getDrawUpdatedAt(),
                                reference.getRawSnapshot());
                        if (blocker.getResult() != null && !blocker.getResult().isBlank()
                                && !blocker.getResult().equals(draw.result())) {
                            LOGGER.error("历史开奖补偿发现号码冲突 tenant={} user={} period={}",
                                    preview.getTenantId(), preview.getUserId(), blocker.getPeriod());
                            return null;
                        }
                        LinkedHashSet<String> newlyDrawn = new LinkedHashSet<>();
                        transactionTemplate.execute(status -> {
                            IssueDO current = findIssue(preview.getUserId(), blocker.getPeriod());
                            if (!isMissingDrawBlocker(current, LocalDateTime.now())) return null;
                            upsertDrawIssue(preview.getUserId(), draw, reference.getSource(),
                                    LocalDateTime.now(), newlyDrawn);
                            return null;
                        });
                        if (!newlyDrawn.isEmpty()) {
                            LOGGER.warn("历史开奖结果补偿成功 tenant={} user={} period={}",
                                    preview.getTenantId(), preview.getUserId(), blocker.getPeriod());
                            dispatchSettlement(preview.getTenantId(), preview.getUserId());
                        }
                        return null;
                    });
                } catch (RuntimeException ex) {
                    LOGGER.error("历史开奖结果补偿失败 tenant={} user={} period={}",
                            preview.getTenantId(), preview.getUserId(), preview.getPeriod(), ex);
                }
            }
        } finally {
            repairingMissingDraws.set(false);
        }
    }

    static boolean isMissingDrawBlocker(IssueDO issue, LocalDateTime now) {
        if (issue == null || issue.getOpenedAt() == null || issue.getSettledAt() != null
                || !Set.of("CLOSED", "DRAW_PENDING").contains(issue.getStatus())) return false;
        LocalDateTime expectedDraw = issue.getDrawTime() == null ? issue.getClosedAt() : issue.getDrawTime();
        return expectedDraw != null && expectedDraw.isBefore(now.minusSeconds(30));
    }

    private IssueDO trustedSharedReference(List<IssueDO> references, String requiredSource) {
        IssueDO trusted = null;
        IssueDO selected = null;
        for (IssueDO candidate : references) {
            if (!"SETTLED".equals(candidate.getStatus()) || candidate.getDrawConfirmations() == null
                    || candidate.getDrawConfirmations() < 2
                    || !(MARKET_DRAW_SOURCE.equals(candidate.getSource())
                            || BY123_DRAW_SOURCE.equals(candidate.getSource()))
                    || !drawVerificationService.isTrusted(candidate.getResult())) continue;
            if (trusted != null && (!Objects.equals(trusted.getPeriod(), candidate.getPeriod())
                    || !Objects.equals(trusted.getResult(), candidate.getResult()))) {
                LOGGER.error("同期开奖源记录不一致 period={}", candidate.getPeriod());
                return null;
            }
            trusted = candidate;
            if (selected == null && (requiredSource == null || requiredSource.equals(candidate.getSource()))) {
                selected = candidate;
            }
        }
        return selected;
    }

    private void dispatchSettlement(Long tenantId, Long userId) {
        String key = tenantId + ":" + userId;
        if (!settlingOwners.add(key)) return;
        long queuedAt = System.nanoTime();
        try {
            settlementExecutor.execute(() -> {
                try {
                    TenantUtils.execute(tenantId, () -> settlePending(userId, queuedAt));
                } catch (RuntimeException ex) {
                    LOGGER.error("待开奖自动结算失败 tenant={} user={}", tenantId, userId, ex);
                } finally {
                    settlingOwners.remove(key);
                }
            });
        } catch (RuntimeException ex) {
            settlingOwners.remove(key);
            LOGGER.error("待开奖自动结算任务提交失败 owner={}", key, ex);
        }
    }

    @Scheduled(initialDelayString = "${lottery.market.period-summary-repair-initial-delay-ms:30000}",
            fixedDelayString = "${lottery.market.period-summary-repair-interval-ms:60000}")
    public void repairRecentPeriodSummaries() {
        if (!repairingPeriodSummaries.compareAndSet(false, true)) return;
        try {
            if (shouldDeferMaintenance(System.currentTimeMillis())) return;
            List<LotteryConfigDO> configs = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    () -> lotteryConfigMapper.selectList(new LambdaQueryWrapper<LotteryConfigDO>()
                            .isNotNull(LotteryConfigDO::getUserId))));
            for (String key : configs.stream().map(config -> config.getTenantId() + ":" + config.getUserId())
                    .distinct().toList()) {
                if (shouldDeferMaintenance(System.currentTimeMillis())) break;
                String[] parts = key.split(":", 2);
                Long tenantId = Long.valueOf(parts[0]);
                Long userId = Long.valueOf(parts[1]);
                try {
                    TenantUtils.execute(tenantId, () -> {
                        for (String period : recentSummaryCandidatePeriods(userId)) {
                            if (shouldDeferMaintenance(System.currentTimeMillis())) break;
                            periodSummaryService.publish(userId, period);
                        }
                    });
                } catch (RuntimeException ex) {
                    LOGGER.error("近期成功订单汇总补偿失败 tenant={} user={}", tenantId, userId, ex);
                }
            }
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
                    syncOwnerConnection(config);
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
                syncOwnerConnection(config);
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
        try {
            TenantUtils.execute(tenantId, () -> {
                LotteryConfigDO config = DataPermissionUtils.executeIgnore(() -> lotteryConfigMapper.selectOne(
                        new LambdaQueryWrapper<LotteryConfigDO>().eq(LotteryConfigDO::getUserId, userId).last("LIMIT 1")));
                if (!configured(config)) return;
                try {
                    Wa55MarketOrderClient.AccountSnapshot snapshot = readOwnerBalance(config);
                    updateConnection(userId, "已连接", snapshot.lineUrl(), snapshot.displayAccount(),
                            snapshot.balance(), "", true);
                } catch (RuntimeException ex) {
                    // Record the attempt time as well: a bad account retries on the normal 30 second cadence instead
                    // of launching a new full login every five seconds.
                    updateConnection(userId, "连接失败", null, null, null, rootMessage(ex), true);
                    throw ex;
                }
            });
        } finally {
            // Both a successful read and a failed attempt wait for the normal cadence before retrying. This avoids
            // repeatedly logging in every five seconds when an external account is unavailable.
            ownerBalanceRefreshAt.put(ownerRefreshKey(tenantId, userId), LocalDateTime.now());
        }
    }

    private String ownerRefreshKey(LotteryConfigDO config) {
        return ownerRefreshKey(config.getTenantId(), config.getUserId());
    }

    private String ownerRefreshKey(Long tenantId, Long userId) {
        return tenantId + ":" + userId;
    }

    /** Returns the persisted connection snapshot and never contacts the external market. */
    public Map<String, Object> connectionSnapshot(Long tenantId, Long userId) {
        return TenantUtils.execute(tenantId, () -> connectionMap(findConnection(userId)));
    }

    private Wa55MarketClient.Snapshot syncGlobalDraws(List<LotteryConfigDO> configs) {
        List<LotteryConfigDO> sources = clockCandidates(configs);
        Wa55MarketClient.Snapshot snapshot = null;
        for (LotteryConfigDO source : sources) {
            try {
                Wa55MarketClient.Snapshot fetched = TenantUtils.execute(source.getTenantId(),
                        () -> readDrawSnapshot(source));
                TenantUtils.execute(source.getTenantId(), () -> updateConnection(source.getUserId(), "已连接",
                        fetched.lineUrl(), null, null, "", true));
                preferredClockOwnerKey = ownerRefreshKey(source);
                snapshot = fetched;
                break;
            } catch (RuntimeException ex) {
                LOGGER.warn("开奖时钟读取失败 tenant={} user={}: {}", source.getTenantId(), source.getUserId(),
                        rootMessage(ex));
            }
        }
        if (snapshot == null) {
            LOGGER.warn("没有可用的已登录会员网址，暂停更新下注期号；备用开奖历史仍可补充开奖结果");
            return null;
        }
        // Claim a valid period as soon as its source responds. A concurrent backup response can win another period.
        List<Wa55MarketClient.Draw> acceptedDraws = acceptedDraws(MARKET_DRAW_SOURCE, snapshot.draws());
        snapshot = new Wa55MarketClient.Snapshot(snapshot.lineUrl(), snapshot.account(), snapshot.issue(), acceptedDraws);
        Wa55MarketClient.Snapshot sharedSnapshot = snapshot;
        List<PeriodSummaryPublication> summaryPublications = new ArrayList<>();
        for (LotteryConfigDO target : configs) {
            try {
                TenantUtils.execute(target.getTenantId(), () -> {
                    SnapshotPersistence persistence = transactionTemplate.execute(status ->
                            persistSharedSnapshot(target.getUserId(), sharedSnapshot));
                    if (persistence != null) {
                        persistence.closedPeriods().stream()
                                .filter(period -> !persistence.newlyDrawnPeriods().contains(period))
                                .forEach(period -> summaryPublications.add(
                                        new PeriodSummaryPublication(target.getTenantId(), target.getUserId(), period)));
                        if (!persistence.newlyDrawnPeriods().isEmpty()) {
                            dispatchSettlement(target.getTenantId(), target.getUserId());
                        }
                    }
                    if (persistence != null && persistence.newlyOpened()) {
                        lotteryService.handleMarketIssueOpened(target.getUserId(), sharedSnapshot.issue().period());
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
        // A newly confirmed result starts settlement after its snapshot transaction commits. The scheduled sweep
        // remains a recovery path for failed or interrupted dispatches.
        return snapshot;
    }

    /** Repair recent settled periods that completed before the zero-order draw message fix. */
    @Scheduled(initialDelayString = "${lottery.market.draw-message-repair-initial-delay-ms:45000}",
            fixedDelayString = "${lottery.market.draw-message-repair-interval-ms:300000}")
    public void repairRecentDrawMessages() {
        if (!repairingDrawMessages.compareAndSet(false, true)) return;
        try {
            if (shouldDeferMaintenance(System.currentTimeMillis())) return;
            List<LotteryConfigDO> configs = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    () -> lotteryConfigMapper.selectList(new LambdaQueryWrapper<LotteryConfigDO>()
                            .isNotNull(LotteryConfigDO::getUserId))));
            for (String key : configs.stream().map(config -> config.getTenantId() + ":" + config.getUserId())
                    .distinct().toList()) {
                if (shouldDeferMaintenance(System.currentTimeMillis())) break;
                String[] parts = key.split(":", 2);
                Long tenantId = Long.valueOf(parts[0]);
                Long userId = Long.valueOf(parts[1]);
                try {
                    TenantUtils.execute(tenantId, () -> lotteryService.repairRecentSettledDrawMessagesForUser(userId));
                } catch (RuntimeException ex) {
                    LOGGER.error("近期房间开奖图补发失败 tenant={} user={}", tenantId, userId, ex);
                }
            }
        } finally {
            repairingDrawMessages.set(false);
        }
    }

    private List<LotteryConfigDO> clockCandidates(List<LotteryConfigDO> configs) {
        List<LotteryConfigDO> eligible = configs.stream().filter(this::configured)
                .sorted(Comparator.comparing(LotteryConfigDO::getTenantId)
                        .thenComparing(LotteryConfigDO::getUserId)).toList();
        LinkedHashMap<String, LotteryConfigDO> selected = new LinkedHashMap<>();
        eligible.stream().filter(this::isDrawSource).findFirst()
                .ifPresent(config -> selected.put(ownerRefreshKey(config), config));
        eligible.stream().filter(config -> ownerRefreshKey(config).equals(preferredClockOwnerKey)).findFirst()
                .ifPresent(config -> selected.put(ownerRefreshKey(config), config));
        List<LotteryConfigDO> fallback = eligible.stream().filter(config -> !isDrawSource(config)).toList();
        if (!fallback.isEmpty()) {
            LotteryConfigDO rotating = fallback.get(Math.floorMod(fallbackClockCursor.getAndIncrement(), fallback.size()));
            selected.put(ownerRefreshKey(rotating), rotating);
        }
        // At most three authenticated reads per tick, regardless of how many owner accounts exist.
        return new ArrayList<>(selected.values());
    }

    private void startBackupDrawSync(List<LotteryConfigDO> configs) {
        if (configs.isEmpty() || !syncingBackupDraws.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            try {
                List<Wa55MarketClient.Draw> draws = acceptedDraws(BY123_DRAW_SOURCE, by123DrawClient.readDraws());
                if (draws.isEmpty()) return;
                List<PeriodSummaryPublication> publications = new ArrayList<>();
                for (LotteryConfigDO target : configs) {
                    try {
                        TenantUtils.execute(target.getTenantId(), () -> {
                            DrawDistribution distribution = transactionTemplate.execute(status -> {
                                List<String> periods = new ArrayList<>();
                                LinkedHashSet<String> newlyDrawnPeriods = new LinkedHashSet<>();
                                for (Wa55MarketClient.Draw draw : draws) {
                                    if (upsertDrawIssue(target.getUserId(), draw, BY123_DRAW_SOURCE,
                                            LocalDateTime.now(), newlyDrawnPeriods)) {
                                        periods.add(draw.period());
                                    }
                                }
                                return new DrawDistribution(periods, List.copyOf(newlyDrawnPeriods));
                            });
                            if (distribution != null) {
                                distribution.closedPeriods().stream()
                                        .filter(period -> !distribution.newlyDrawnPeriods().contains(period))
                                        .forEach(period -> publications.add(new PeriodSummaryPublication(
                                                target.getTenantId(), target.getUserId(), period)));
                                if (!distribution.newlyDrawnPeriods().isEmpty()) {
                                    dispatchSettlement(target.getTenantId(), target.getUserId());
                                }
                            }
                        });
                    } catch (RuntimeException ex) {
                        LOGGER.error("备用开奖结果分发失败 tenant={} user={}", target.getTenantId(), target.getUserId(), ex);
                    }
                }
                for (PeriodSummaryPublication publication : publications) {
                    try {
                        TenantUtils.execute(publication.tenantId(), () ->
                                periodSummaryService.publish(publication.userId(), publication.period()));
                    } catch (RuntimeException ex) {
                        LOGGER.error("备用开奖期末汇总失败 tenant={} user={} period={}", publication.tenantId(),
                                publication.userId(), publication.period(), ex);
                    }
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("by123 备用开奖源本轮不可用: {}", rootMessage(ex));
            } finally {
                syncingBackupDraws.set(false);
            }
        });
    }

    private List<Wa55MarketClient.Draw> acceptedDraws(String source, List<Wa55MarketClient.Draw> draws) {
        List<Wa55MarketClient.Draw> accepted = new ArrayList<>();
        for (Wa55MarketClient.Draw draw : draws) {
            if (draw.period() == null || !draw.period().matches("\\d{8,20}")
                    || !drawVerificationService.isTrusted(draw.result())) continue;
            try {
                String winner = drawSourceClaims.computeIfAbsent(draw.period(), period -> {
                    String key = "lucky5:draw-source:" + period;
                    String existing = stringRedisTemplate.opsForValue().get(key);
                    if (existing != null) return existing;
                    // On upgrade/restart, an already persisted candidate must keep its original source. Otherwise
                    // the new Redis key could accidentally pin a different feed and strand DRAW_PENDING forever.
                    IssueDO persisted = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(() ->
                            issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                                    .eq(IssueDO::getPeriod, period).isNotNull(IssueDO::getDrawFirstSeenAt)
                                    .orderByAsc(IssueDO::getDrawFirstSeenAt).last("LIMIT 1"))));
                    String preferred = persisted != null && Set.of(MARKET_DRAW_SOURCE, BY123_DRAW_SOURCE)
                            .contains(persisted.getSource()) ? persisted.getSource() : source;
                    Boolean claimed = stringRedisTemplate.opsForValue().setIfAbsent(key, preferred, Duration.ofDays(3));
                    return Boolean.TRUE.equals(claimed) ? preferred : stringRedisTemplate.opsForValue().get(key);
                });
                if (source.equals(winner)) accepted.add(draw);
            } catch (RuntimeException ex) {
                // Failing closed is safer than letting two app instances settle from different sources.
                LOGGER.error("开奖源锁定失败 period={} source={}", draw.period(), source, ex);
            }
        }
        if (drawSourceClaims.size() > 512) {
            drawSourceClaims.keySet().stream().sorted().limit(drawSourceClaims.size() - 256)
                    .forEach(drawSourceClaims::remove);
        }
        return accepted;
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

    private boolean shouldDeferMaintenance(long nowMillis) {
        long before = Math.max(20, hotWindowBeforeSeconds) * 1000;
        // Keep the repair sweeps clear of both the normal payout and a retry after a DB lock timeout.
        long after = Math.max(120, hotWindowAfterSeconds) * 1000;
        long active = activeDrawTimeMillis;
        long queued = queuedDrawTimeMillis;
        return active > 0 && nowMillis >= active - before && nowMillis <= active + after
                || queued > 0 && nowMillis >= queued - before && nowMillis <= queued + after;
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
            Wa55MarketOrderClient.AccountSnapshot snapshot = readOwnerBalance(config);
            updateConnection(userId, "已连接", snapshot.lineUrl(), snapshot.displayAccount(),
                    snapshot.balance(), "", true);
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
        LinkedHashSet<String> newlyDrawnPeriods = new LinkedHashSet<>();
        for (Wa55MarketClient.Draw draw : snapshot.draws()) {
            if (upsertDrawIssue(userId, draw, MARKET_DRAW_SOURCE, now, newlyDrawnPeriods)) {
                closedPeriods.add(draw.period());
            }
        }
        return new SnapshotPersistence(persistence.newlyOpened(), List.copyOf(closedPeriods),
                List.copyOf(newlyDrawnPeriods));
    }

    private SnapshotPersistence upsertCurrentIssue(Long userId, Wa55MarketClient.Issue snapshot, LocalDateTime now) {
        if (snapshot.period() == null || !snapshot.period().matches("\\d{8,20}")) {
            return new SnapshotPersistence(false, List.of(), List.of());
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
            issue.setSource(MARKET_DRAW_SOURCE);
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
            if (issue.getDrawFirstSeenAt() == null) issue.setSource(MARKET_DRAW_SOURCE);
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
        return new SnapshotPersistence(newlyOpened, closedPeriods, List.of());
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
                        .lt(IssueDO::getPeriod, issue.getPeriod()).isNotNull(IssueDO::getOpenedAt)
                        .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        // A market preview may skip a period without ever opening it. It must not hide the last period that
        // actually accepted bets: that period's payout must commit before another period can open.
        // Only the latest formally opened period gates us, so an older abandoned row cannot block newer
        // periods once a later formally opened period has settled.
        if (previousIssue != null && !"SETTLED".equals(previousIssue.getStatus())) {
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

    private boolean upsertDrawIssue(Long userId, Wa55MarketClient.Draw draw, String source, LocalDateTime now) {
        return upsertDrawIssue(userId, draw, source, now, null);
    }

    private boolean upsertDrawIssue(Long userId, Wa55MarketClient.Draw draw, String source, LocalDateTime now,
                                    Set<String> newlyDrawnPeriods) {
        if (!draw.period().matches("\\d{8,20}")) return false;
        IssueDO issue = findIssue(userId, draw.period());
        if (issue != null && issue.getDrawFirstSeenAt() != null && !source.equals(issue.getSource())) return false;
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
            issue.setSource(source);
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
            issue.setSource(source);
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
        if (newlyDrawnPeriods != null && !"DRAWN".equals(oldStatus) && "DRAWN".equals(decision.status())) {
            newlyDrawnPeriods.add(draw.period());
        }
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

    private void settlePending(Long userId, long queuedAt) {
        long queueWaitMs = (System.nanoTime() - queuedAt) / 1_000_000;
        LocalDateTime staleSettlementBefore = staleSettlementBefore(LocalDateTime.now());
        IssueDO liveCandidate = findLiveSettlementCandidate(userId, staleSettlementBefore);
        List<IssueDO> candidates = liveCandidate == null ? List.of() : List.of(liveCandidate);
        for (IssueDO issue : candidates) {
            publishVerifiedDrawIfReady(userId, issue);
            long summaryStartedAt = System.nanoTime();
            try {
                periodSummaryService.publish(userId, issue.getPeriod());
            } catch (RuntimeException ex) {
                LOGGER.error("期号 {} 用户 {} 结算前成功订单汇总失败", issue.getPeriod(), userId, ex);
                continue;
            }
            long summaryMs = (System.nanoTime() - summaryStartedAt) / 1_000_000;
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
            long settleStartedAt = System.nanoTime();
            try {
                lotteryService.settlePeriodForUser(userId, issue.getPeriod(), issue.getResult(), "system");
                LOGGER.info("Lucky5 owner settlement committed tenant={} user={} period={} queueWaitMs={} "
                                + "summaryMs={} settlementCommitMs={} dispatchToCommitMs={} firstSeenToCommitMs={}",
                        issue.getTenantId(), userId, issue.getPeriod(), queueWaitMs, summaryMs,
                        (System.nanoTime() - settleStartedAt) / 1_000_000,
                        (System.nanoTime() - queuedAt) / 1_000_000,
                        issue.getDrawFirstSeenAt() == null ? -1
                                : Duration.between(issue.getDrawFirstSeenAt(), LocalDateTime.now()).toMillis());
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

    private Wa55MarketOrderClient.AccountSnapshot readOwnerBalance(LotteryConfigDO config) {
        String password = credentialService.decrypt(config.getMarketPasswordEncrypted());
        return marketOrderClient.readBalance(new Wa55MarketOrderClient.Credentials(
                config.getUpstreamUrl(), config.getUpstreamAccount(), password));
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
        if (latest.getOpenedAt() == null) {
            // The latest period may be an unopened preview several periods after the last period that
            // actually accepted bets. Recover that blocker even when it has no orders; otherwise the
            // next period can never acquire openedAt and every player bet is rejected as closed.
            IssueDO blocking = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                    new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                            .lt(IssueDO::getPeriod, latest.getPeriod()).isNotNull(IssueDO::getOpenedAt)
                            .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
            if (isReadyDrawnSettlement(blocking) || isRecoverableStaleSettlement(blocking, staleBefore)) {
                return blocking;
            }
        }
        IssueDO candidate = latest;
        if (!List.of("DRAWN", "SETTLING").contains(candidate.getStatus())) {
            // A newer answering period must not block recovery of a previously failed local-only settlement.
            // The recovery method remains deliberately scoped to local orders, so no external-market uncertainty is
            // retried while the live period is open.
            if (latest.getOpenedAt() != null) return findFailedHistoricalLocalCandidate(userId);
            candidate = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                    new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                            .lt(IssueDO::getPeriod, latest.getPeriod())
                            .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        }
        if (isReadyDrawnSettlement(candidate)) return candidate;
        if (isRecoverableStaleSettlement(candidate, staleBefore)) return candidate;
        IssueDO localRecovery = findFailedHistoricalLocalCandidate(userId);
        if (localRecovery != null) return localRecovery;
        return findAcceptedWithoutIdentifiersHistoricalCandidate(userId);
    }

    /**
     * A local-only order never waits for a third-party market reconciliation. If settlement failed only because of
     * a transient application or schema problem, it is safe to retry one oldest affected period after the live queue
     * has drained. External-market periods deliberately stay out of this recovery path.
     */
    private IssueDO findFailedHistoricalLocalCandidate(Long userId) {
        List<OrderDO> localOrders = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(
                new LambdaQueryWrapper<OrderDO>().select(OrderDO::getPeriod).eq(OrderDO::getUserId, userId)
                        .eq(OrderDO::getStatus, "未开奖").eq(OrderDO::getDeliveryMode, "LOCAL_ONLY")));
        Set<String> periods = localOrders.stream().map(OrderDO::getPeriod)
                .filter(period -> period != null && !period.isBlank()).collect(java.util.stream.Collectors.toSet());
        if (periods.isEmpty()) return null;
        List<IssueDO> candidates = DataPermissionUtils.executeIgnore(() -> issueMapper.selectList(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId).in(IssueDO::getPeriod, periods)
                        .eq(IssueDO::getStatus, "DRAWN").isNotNull(IssueDO::getError).ne(IssueDO::getError, "")
                        .isNotNull(IssueDO::getResult).ne(IssueDO::getResult, "")
                        .ne(IssueDO::getResult, LotteryDrawVerificationService.ZERO_RESULT)
                        .ge(IssueDO::getDrawConfirmations, 2).orderByAsc(IssueDO::getPeriod)));
        return candidates.stream().filter(issue -> allPeriodOrdersAreLocal(userId, issue.getPeriod())).findFirst()
                .orElse(null);
    }

    private boolean allPeriodOrdersAreLocal(Long userId, String period) {
        List<OrderDO> orders = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(
                new LambdaQueryWrapper<OrderDO>().select(OrderDO::getDeliveryMode).eq(OrderDO::getUserId, userId)
                        .eq(OrderDO::getPeriod, period)));
        return !orders.isEmpty() && orders.stream().allMatch(order -> "LOCAL_ONLY".equals(order.getDeliveryMode()));
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
