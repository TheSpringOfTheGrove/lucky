package com.hnz.luck5.module.lottery.service;

public record LotteryMarketOrderDispatchEvent(Long tenantId, Long userId, String orderId, Action action,
                                             long queuedAtNanos) {

    public LotteryMarketOrderDispatchEvent(Long tenantId, Long userId, String orderId, Action action) {
        this(tenantId, userId, orderId, action, System.nanoTime());
    }

    public enum Action {
        SUBMIT,
        CANCEL
    }
}
