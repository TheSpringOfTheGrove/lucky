package com.hnz.luck5.module.lottery.service;

public record LotteryMarketOrderDispatchEvent(Long tenantId, Long userId, String orderId, Action action) {

    public enum Action {
        SUBMIT,
        CANCEL
    }
}
