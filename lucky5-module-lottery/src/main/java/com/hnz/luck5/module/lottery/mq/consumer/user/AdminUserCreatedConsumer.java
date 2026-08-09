package com.hnz.luck5.module.lottery.mq.consumer.user;

import com.hnz.luck5.module.lottery.service.LotteryOwnerInitializationService;
import com.hnz.luck5.module.system.api.message.user.AdminUserCreatedMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 新老板账号初始化消费者。
 */
@Component
@RequiredArgsConstructor
public class AdminUserCreatedConsumer {

    private final LotteryOwnerInitializationService ownerInitializationService;

    @EventListener
    public void onMessage(AdminUserCreatedMessage message) {
        ownerInitializationService.initialize(message.getTenantId(), message.getUserId(), message.getUsername());
    }

}
