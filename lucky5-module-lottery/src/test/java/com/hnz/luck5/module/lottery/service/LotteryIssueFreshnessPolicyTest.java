package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryIssueFreshnessPolicyTest {

    private final LotteryIssueFreshnessPolicy policy = new LotteryIssueFreshnessPolicy();

    @Test
    void keepsOpenSnapshotFreshUntilCountdownAndGraceExpire() {
        LocalDateTime serverTime = LocalDateTime.of(2026, 8, 10, 10, 15, 13);
        IssueDO issue = issue("OPEN", serverTime, 257);

        assertThat(policy.isStale(issue, serverTime.plusSeconds(346))).isFalse();
        assertThat(policy.isStale(issue, serverTime.plusSeconds(347))).isTrue();
    }

    @Test
    void neverMarksTerminalIssueAsSourceStale() {
        LocalDateTime serverTime = LocalDateTime.of(2026, 8, 10, 10, 15, 13);
        IssueDO issue = issue("CLOSED", serverTime, 0);

        assertThat(policy.isStale(issue, serverTime.plusHours(1))).isFalse();
    }

    @Test
    void missingServerTimeFallsBackToDatabaseUpdateTime() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 10, 10, 15, 13);
        IssueDO issue = issue("OPEN", null, 0);
        issue.setUpdateTime(updatedAt);

        assertThat(policy.isStale(issue, updatedAt.plusSeconds(89))).isFalse();
        assertThat(policy.isStale(issue, updatedAt.plusSeconds(90))).isTrue();
    }

    private IssueDO issue(String status, LocalDateTime serverTime, int remainingSeconds) {
        IssueDO issue = new IssueDO();
        issue.setStatus(status);
        issue.setServerTime(serverTime);
        issue.setRemainingSeconds(remainingSeconds);
        return issue;
    }
}
