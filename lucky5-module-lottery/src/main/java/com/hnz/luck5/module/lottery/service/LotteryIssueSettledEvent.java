package com.hnz.luck5.module.lottery.service;

/** Signals that a period's payout transaction committed and the following open period may be scheduled. */
public record LotteryIssueSettledEvent(Long tenantId, Long userId, String period) {
}
