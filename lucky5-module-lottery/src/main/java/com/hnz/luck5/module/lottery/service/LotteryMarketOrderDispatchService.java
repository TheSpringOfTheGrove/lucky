package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class LotteryMarketOrderDispatchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketOrderDispatchService.class);
    private static final int MAX_AUTOMATIC_ATTEMPTS = 3;

    private final Wa55MarketOrderClient marketClient;
    private final LotteryMarketOrderStateService stateService;
    private final AtomicBoolean recovering = new AtomicBoolean();

    public void submit(Long userId, String orderId) {
        if (!marketClient.isRealWritesEnabled()) return;
        LotteryMarketOrderStateService.DispatchContext context = stateService.claimSubmit(userId, orderId);
        if (context == null || context.requests().isEmpty()) return;
        try {
            Wa55MarketOrderClient.SubmissionBatch result = marketClient.submit(context.credentials(), context.period(),
                    context.requests());
            applySuccessfulConfirmations(userId, orderId, result.confirmations());
        } catch (Wa55MarketOrderClient.MarketProtocolException ex) {
            stateService.applyConfirmations(userId, orderId, ex.confirmations());
            if (ex.submissionUncertain()) {
                stateService.markManualReview(userId, orderId,
                        "盘口受理结果不确定，禁止自动重投或退款，请人工核对：" + ex.getMessage());
            } else if (!ex.confirmations().isEmpty()) {
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

    public void cancel(Long userId, String orderId) {
        if (!marketClient.isRealWritesEnabled()) return;
        LotteryMarketOrderStateService.CancelContext context = stateService.claimCancel(userId, orderId);
        if (context == null) return;
        if (context.requests().isEmpty()) {
            stateService.finalizeCancel(userId, orderId, "system", context.rejectedSubmission());
            return;
        }
        try {
            marketClient.cancel(context.credentials(), context.period(), context.requests());
            stateService.finalizeCancel(userId, orderId, "system", context.rejectedSubmission());
        } catch (RuntimeException ex) {
            stateService.markCancelFailed(userId, orderId, ex.getMessage());
        }
    }

    /**
     * 外部已经明确受理后，本地确认必须优先落库。短暂数据库异常允许重试本地写入，绝不能重新向外部提交。
     */
    private void applySuccessfulConfirmations(Long userId, String orderId,
                                              List<Wa55MarketOrderClient.BetConfirmation> confirmations) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_AUTOMATIC_ATTEMPTS; attempt++) {
            try {
                if (stateService.applyConfirmations(userId, orderId, confirmations)) return;
            } catch (RuntimeException ex) {
                lastError = ex;
                LOGGER.warn("外部订单已成功，本地确认第 {} 次写入失败 user={} order={}", attempt, userId, orderId, ex);
            }
        }
        String detail = lastError == null ? "本地确认状态未完整更新" : lastError.getMessage();
        stateService.markManualReview(userId, orderId,
                "外部订单已成功，但本地确认连续三次写入失败，请仅修复本地状态，禁止重新提交：" + detail);
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
