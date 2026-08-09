package com.hnz.luck5.module.lottery.service;

public record LotteryIssueOpenedEvent(Long tenantId, Long userId, String period) {
}
