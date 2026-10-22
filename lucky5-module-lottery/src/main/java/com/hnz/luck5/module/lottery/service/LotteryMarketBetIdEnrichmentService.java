package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Recovers complete external cancel references without delaying accepted bet receipts. */
@Service
public class LotteryMarketBetIdEnrichmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketBetIdEnrichmentService.class);
    private static final long CANCEL_LOOKUP_WAIT_SECONDS = 5;

    @Resource private LotteryMarketOrderStateService stateService;
    @Resource private Wa55MarketOrderClient marketClient;
    @Resource(name = "lotteryMarketDetailExecutor") private Executor detailExecutor;
    @Resource private LotteryMarketReferenceLockService referenceLockService;

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> inFlight = new ConcurrentHashMap<>();

    public void enqueue(Long tenantId, Long userId, String orderId) {
        retryInBackground(tenantId, userId, orderId, 0);
    }

    private void retryInBackground(Long tenantId, Long userId, String orderId, int retry) {
        schedule(tenantId, userId, orderId).thenAccept(resolved -> {
            if (resolved || retry >= 1) return;
            CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS)
                    .execute(() -> retryInBackground(tenantId, userId, orderId, retry + 1));
        });
    }

    /** A failed or incomplete lookup leaves the existing cancellation guard unchanged. */
    public boolean resolveNow(Long tenantId, Long userId, String orderId) {
        long startedAt = System.nanoTime();
        String outcome = "incomplete";
        CompletableFuture<Boolean> lookup = schedule(tenantId, userId, orderId);
        try {
            boolean resolved = lookup.get(CANCEL_LOOKUP_WAIT_SECONDS, TimeUnit.SECONDS);
            outcome = resolved ? "resolved" : "incomplete";
            return resolved;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            return false;
        } catch (TimeoutException ex) {
            LOGGER.info("盘口退码编号补查继续在后台执行 tenant={} user={} order={}", tenantId, userId, orderId);
            outcome = "timeout";
            return false;
        } catch (Exception ex) {
            LOGGER.warn("盘口退码编号补查失败 tenant={} user={} order={} cause={}",
                    tenantId, userId, orderId, ex.getClass().getSimpleName());
            outcome = "failed";
            return false;
        } finally {
            LOGGER.info("Lucky5 market cancel reference timing: user={} order={} lookupMs={} outcome={}",
                    userId, orderId, (System.nanoTime() - startedAt) / 1_000_000, outcome);
        }
    }

    private CompletableFuture<Boolean> schedule(Long tenantId, Long userId, String orderId) {
        String key = tenantId + ":" + userId + ":" + orderId;
        CompletableFuture<Boolean> created = new CompletableFuture<>();
        CompletableFuture<Boolean> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) return existing;
        try {
            detailExecutor.execute(() -> {
                try {
                    created.complete(TenantUtils.execute(tenantId, () -> lookup(userId, orderId)));
                } catch (RuntimeException ex) {
                    LOGGER.warn("盘口只读明细补查失败 tenant={} user={} order={} cause={}",
                            tenantId, userId, orderId, ex.getClass().getSimpleName());
                    created.complete(false);
                } finally {
                    inFlight.remove(key, created);
                }
            });
        } catch (RuntimeException ex) {
            inFlight.remove(key, created);
            LOGGER.warn("盘口退码编号补查调度失败 tenant={} user={} order={} cause={}",
                    tenantId, userId, orderId, ex.getClass().getSimpleName());
            created.complete(false);
        }
        return created;
    }

    private boolean lookup(Long userId, String orderId) {
        LotteryMarketOrderStateService.VerificationContext context =
                stateService.acceptedDetailContext(userId, orderId);
        if (context == null) {
            boolean complete = stateService.hasCompleteCancelReferences(userId, orderId);
            if (!complete) LOGGER.info("盘口退码编号补查跳过 user={} order={} reason=NO_ELIGIBLE_CONTEXT", userId, orderId);
            return complete;
        }
        Long tenantId = com.hnz.luck5.framework.tenant.core.context.TenantContextHolder.getRequiredTenantId();
        // This lock is separate from the write-account lock: a slow detail scan must not delay a new bet.
        return referenceLockService.execute(tenantId, userId, context.period(), () -> {
            if (stateService.hasCompleteCancelReferences(userId, orderId)) return true;
            return matchAndSave(userId, orderId, context);
        });
    }

    private boolean matchAndSave(Long userId, String orderId,
                                 LotteryMarketOrderStateService.VerificationContext context) {
        Wa55MarketOrderClient.VerificationBatch details = marketClient.verifyAccepted(
                context.credentials(), context.period(), context.requests(),
                stateService.claimedMarketBetIds(userId, context.period(), orderId));
        if (!details.unresolvedBatches().isEmpty() || details.confirmations().size() != context.requests().size()) {
            LOGGER.warn("盘口退码编号补查未匹配 user={} order={} matched={} expected={} diagnostics={}",
                    userId, orderId, details.confirmations().size(), context.requests().size(),
                    details.diagnostics());
            return false;
        }
        boolean saved = stateService.attachAcceptedCancelReferences(userId, orderId, details.confirmations());
        if (saved) {
            LOGGER.info("盘口退码编号已补齐 tenant={} user={} order={} count={}",
                    com.hnz.luck5.framework.tenant.core.context.TenantContextHolder.getRequiredTenantId(),
                    userId, orderId, details.confirmations().size());
        } else if (!stateService.hasCompleteCancelReferences(userId, orderId)) {
            LOGGER.warn("盘口退码编号补查已匹配但未保存 user={} order={} reason=STATE_CHANGED_OR_VALIDATION_FAILED",
                    userId, orderId);
        }
        return saved || stateService.hasCompleteCancelReferences(userId, orderId);
    }
}
