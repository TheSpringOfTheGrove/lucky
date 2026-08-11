package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class LotteryMarketOrderDispatchListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketOrderDispatchListener.class);

    private final LotteryMarketOrderDispatchService dispatchService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(LotteryMarketOrderDispatchEvent event) {
        try {
            TenantUtils.execute(event.tenantId(), () -> {
                if (event.action() == LotteryMarketOrderDispatchEvent.Action.CANCEL) {
                    dispatchService.cancel(event.userId(), event.orderId());
                } else {
                    dispatchService.submit(event.userId(), event.orderId());
                }
            });
        } catch (RuntimeException ex) {
            LOGGER.error("真实盘口异步派发失败 tenant={} user={} order={} action={}", event.tenantId(),
                    event.userId(), event.orderId(), event.action(), ex);
        }
    }
}
