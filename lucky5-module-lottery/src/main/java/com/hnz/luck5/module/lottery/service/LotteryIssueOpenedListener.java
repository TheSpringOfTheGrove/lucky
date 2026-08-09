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
public class LotteryIssueOpenedListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryIssueOpenedListener.class);

    private final LotteryAutoProxyService autoProxyService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(LotteryIssueOpenedEvent event) {
        try {
            TenantUtils.execute(event.tenantId(), () -> autoProxyService.run(event.userId(), event.period()));
        } catch (RuntimeException ex) {
            LOGGER.error("自动托执行失败 tenant={} user={} period={}", event.tenantId(), event.userId(), event.period(), ex);
        }
    }

}
