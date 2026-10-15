package com.hnz.luck5.module.lottery.service;

public record LotteryRoomStartedEvent(Long tenantId, Long userId, String period) {
}
