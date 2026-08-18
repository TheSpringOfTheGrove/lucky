package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class LotteryMarketOrderDispatchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketOrderDispatchService.class);
    private static final int MAX_AUTOMATIC_ATTEMPTS = 3;
    private static final int VERIFICATION_TIMEOUT_SECONDS = 300;
    private static final int VERIFICATION_RETRY_SECONDS = 3;

    private final Wa55MarketOrderClient marketClient;
    private final LotteryMarketOrderStateService stateService;
    private final LotteryMarketSyncService marketSyncService;
    private final LotteryMarketBalanceRefreshService balanceRefreshService;
    private final LotteryMarketAccountLockService accountLockService;
    private final AtomicBoolean recovering = new AtomicBoolean();

    public void submit(Long userId, String orderId) {
        if (!marketClient.isRealWritesEnabled()) return;
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        accountLockService.execute(tenantId, userId, () -> submitLocked(userId, orderId));
    }

    private void submitLocked(Long userId, String orderId) {
        LotteryMarketOrderStateService.DispatchContext context = stateService.claimSubmit(userId, orderId);
        if (context == null || context.requests().isEmpty()) return;
        try {
            Wa55MarketOrderClient.SubmissionBatch result = marketClient.submit(context.credentials(), context.period(),
                    context.requests());
            if (!result.acceptedBatches().isEmpty()) {
                if (!result.confirmations().isEmpty()) {
                    stateService.applyConfirmations(userId, orderId, result.confirmations());
                }
                stateService.markAcceptedBatchesVerifying(userId, orderId, result.acceptedBatches(),
                        LocalDateTime.now().plusSeconds(VERIFICATION_RETRY_SECONDS));
                LOGGER.info("盘口批量已明确受理，转只读明细确认 user={} order={} batches={} routes={}",
                        userId, orderId, result.acceptedBatches().size(), result.acceptedBatches().stream()
                                .mapToInt(Wa55MarketOrderClient.AcceptedBatch::betCount).sum());
                BigDecimal acceptedAmount = result.confirmations().stream()
                        .map(Wa55MarketOrderClient.BetConfirmation::acceptedAmount)
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .add(result.acceptedBatches().stream()
                                .map(Wa55MarketOrderClient.AcceptedBatch::acceptedAmount)
                                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
                marketSyncService.recordSuccessfulSubmission(userId, result.balance(), acceptedAmount);
                balanceRefreshService.refresh(TenantContextHolder.getRequiredTenantId(), userId);
                return;
            }
            if (applySuccessfulConfirmations(userId, orderId, result.confirmations())) {
                BigDecimal acceptedAmount = result.confirmations().stream()
                        .map(Wa55MarketOrderClient.BetConfirmation::acceptedAmount)
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                marketSyncService.recordSuccessfulSubmission(userId, result.balance(), acceptedAmount);
                balanceRefreshService.refresh(TenantContextHolder.getRequiredTenantId(), userId);
            }
        } catch (Wa55MarketOrderClient.MarketProtocolException ex) {
            if (ex.submissionUncertain()) {
                stateService.applyUncertainConfirmations(userId, orderId, ex.confirmations(), ex.getMessage());
                stateService.markManualReview(userId, orderId,
                        "盘口受理结果不确定，禁止自动重投或退款，请人工核对：" + ex.getMessage());
            } else if (!ex.confirmations().isEmpty()) {
                stateService.applyConfirmations(userId, orderId, ex.confirmations());
                stateService.markPartialRejected(userId, orderId, ex.getMessage());
                cancel(userId, orderId);
            } else if (ex.retryable() && context.attempts() < MAX_AUTOMATIC_ATTEMPTS) {
                stateService.markRetry(userId, orderId, ex.getMessage(), LocalDateTime.now().plusSeconds(10));
            } else if (ex.retryable()) {
                stateService.failAndRefund(userId, orderId,
                        "盘口连续三次连接失败，订单未提交：" + ex.getMessage());
            } else {
                stateService.failAndRefund(userId, orderId, ex.getMessage());
            }
        } catch (RuntimeException ex) {
            stateService.markManualReview(userId, orderId,
                    "盘口派发后本地处理异常，禁止自动重投或退款，请人工核对：" + ex.getMessage());
        }
    }

    public void verify(Long userId, String orderId) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        accountLockService.execute(tenantId, userId, () -> verifyLocked(userId, orderId));
    }

    private void verifyLocked(Long userId, String orderId) {
        LotteryMarketOrderStateService.VerificationContext context =
                stateService.verificationContext(userId, orderId);
        if (context == null || context.requests().isEmpty()) return;
        if (verificationExpired(context)) {
            stateService.markAcceptedDetailsManualReview(userId, orderId,
                    "盘口已明确受理，但等待下注明细标识超过五分钟，请人工核对；禁止重投或退款");
            return;
        }
        try {
            Wa55MarketOrderClient.VerificationBatch result = marketClient.verifyAccepted(
                    context.credentials(), context.period(), context.requests());
            if (result.unresolvedBatches().isEmpty()) {
                LOGGER.info("盘口批量明细自动确认完成 user={} order={} routes={}", userId, orderId,
                        result.confirmations().size());
                applySuccessfulConfirmations(userId, orderId, result.confirmations());
                return;
            }
            if (!result.confirmations().isEmpty()) {
                stateService.applyConfirmations(userId, orderId, result.confirmations());
            }
            stateService.scheduleVerificationRetry(userId, orderId,
                    "盘口已明确受理，等待剩余下注明细标识", LocalDateTime.now()
                            .plusSeconds(VERIFICATION_RETRY_SECONDS));
            LOGGER.info("盘口批量明细尚未完全可见 user={} order={} confirmed={} unresolvedBatches={}",
                    userId, orderId, result.confirmations().size(), result.unresolvedBatches().size());
        } catch (RuntimeException ex) {
            if (verificationExpired(context)) {
                stateService.markAcceptedDetailsManualReview(userId, orderId,
                        "盘口已明确受理，但只读回查连续五分钟未完成，请人工核对；禁止重投或退款："
                                + ex.getMessage());
            } else {
                stateService.scheduleVerificationRetry(userId, orderId,
                        "盘口已明确受理，只读回查暂未完成：" + ex.getMessage(), LocalDateTime.now()
                                .plusSeconds(VERIFICATION_RETRY_SECONDS));
                LOGGER.warn("盘口批量只读明细确认暂未完成 user={} order={}: {}", userId, orderId,
                        ex.getMessage());
            }
        }
    }

    private boolean verificationExpired(LotteryMarketOrderStateService.VerificationContext context) {
        return context.submittedAt() != null && context.submittedAt()
                .isBefore(LocalDateTime.now().minusSeconds(VERIFICATION_TIMEOUT_SECONDS));
    }

    public void cancel(Long userId, String orderId) {
        if (!marketClient.isRealWritesEnabled()) return;
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        accountLockService.execute(tenantId, userId, () -> cancelLocked(userId, orderId));
    }

    private void cancelLocked(Long userId, String orderId) {
        LotteryMarketOrderStateService.CancelContext context = stateService.claimCancel(userId, orderId);
        if (context == null) return;
        if (!stateService.isCancellationWindowOpen(userId, context.period())) {
            String reason = "该期已封盘，不能退码";
            if (context.rejectedSubmission()) {
                stateService.markCancelFailed(userId, orderId, reason);
            } else {
                stateService.markCancelRejected(userId, orderId, reason);
            }
            return;
        }
        if (context.requests().isEmpty()) {
            stateService.finalizeCancel(userId, orderId, "system", context.rejectedSubmission());
            return;
        }
        try {
            marketClient.cancel(context.credentials(), context.period(), context.requests());
            stateService.finalizeCancel(userId, orderId, "system", context.rejectedSubmission());
        } catch (Wa55MarketOrderClient.MarketProtocolException ex) {
            if (ex.submissionUncertain()) {
                stateService.markCancelFailed(userId, orderId, ex.getMessage());
            } else {
                stateService.markCancelRejected(userId, orderId, ex.getMessage());
            }
        } catch (RuntimeException ex) {
            stateService.markCancelFailed(userId, orderId, ex.getMessage());
        }
    }

    /**
     * 外部已经明确受理后，本地确认必须优先落库。短暂数据库异常允许重试本地写入，绝不能重新向外部提交。
     */
    private boolean applySuccessfulConfirmations(Long userId, String orderId,
                                                 List<Wa55MarketOrderClient.BetConfirmation> confirmations) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_AUTOMATIC_ATTEMPTS; attempt++) {
            try {
                if (stateService.applyConfirmations(userId, orderId, confirmations)) return true;
            } catch (RuntimeException ex) {
                lastError = ex;
                LOGGER.warn("外部订单已成功，本地确认第 {} 次写入失败 user={} order={}", attempt, userId, orderId, ex);
            }
        }
        String detail = lastError == null ? "本地确认状态未完整更新" : lastError.getMessage();
        stateService.markManualReview(userId, orderId,
                "外部订单已成功，但本地确认连续三次写入失败，请仅修复本地状态，禁止重新提交：" + detail);
        return false;
    }

    @Scheduled(initialDelayString = "${lottery.market.order-recovery-initial-delay-ms:30000}",
            fixedDelayString = "${lottery.market.order-recovery-interval-ms:10000}")
    public void recoverPendingOrders() {
        if (!marketClient.isRealWritesEnabled() || !recovering.compareAndSet(false, true)) return;
        try {
            List<OrderDO> orders = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    stateService::recoverableOrders));
            for (OrderDO order : orders) {
                try {
                    TenantUtils.execute(order.getTenantId(), () -> {
                        if ("SUBMITTING".equals(order.getMarketStatus())) {
                            stateService.markManualReview(order.getUserId(), order.getId(),
                                    "服务重启时发现未完成的盘口提交，禁止自动重投，请人工核对");
                        } else if ("CANCEL_PENDING".equals(order.getMarketStatus())) {
                            stateService.markCancelFailed(order.getUserId(), order.getId(),
                                    "服务重启时退码结果不确定，请人工核对后重试");
                        } else if ("CANCEL_REQUESTED".equals(order.getMarketStatus())) {
                            cancel(order.getUserId(), order.getId());
                        } else if ("VERIFYING".equals(order.getMarketStatus())) {
                            verify(order.getUserId(), order.getId());
                        } else {
                            submit(order.getUserId(), order.getId());
                        }
                    });
                } catch (RuntimeException ex) {
                    LOGGER.error("恢复真实盘口订单失败 tenant={} user={} order={}", order.getTenantId(),
                            order.getUserId(), order.getId(), ex);
                }
            }
        } finally {
            recovering.set(false);
        }
    }
}
