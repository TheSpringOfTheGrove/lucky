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
    void closesBettingAtMarketCutoffWithoutUsingStaleGraceAsExtraBettingTime() {
        LocalDateTime serverTime = LocalDateTime.of(2026, 8, 14, 19, 4, 24);
        IssueDO issue = issue("OPEN", serverTime, 6);

        assertThat(policy.isBettingClosed(issue, serverTime.plusSeconds(5))).isFalse();
        assertThat(policy.isBettingClosed(issue, serverTime.plusSeconds(6))).isTrue();
        assertThat(policy.isStale(issue, serverTime.plusSeconds(6))).isFalse();
        assertThat(policy.effectiveStatus(issue, serverTime.plusSeconds(6))).isEqualTo("CLOSED");
        assertThat(policy.effectiveStatus(issue, serverTime.plusSeconds(96))).isEqualTo("SOURCE_STALE");
    }

    @Test
    void neverMarksTerminalIssueAsSourceStale() {
        LocalDateTime serverTime = LocalDateTime.of(2026, 8, 10, 10, 15, 13);
        IssueDO issue = issue("SETTLED", serverTime, 0);

        assertThat(policy.isStale(issue, serverTime.plusHours(1))).isFalse();
        assertThat(policy.effectiveStatus(issue, serverTime.plusHours(1))).isEqualTo("SETTLED");
    }

    @Test
    void usesLocalObservationToCorrectSourceClockSkew() {
        LocalDateTime sourceTime = LocalDateTime.of(2026, 8, 14, 20, 49, 59);
        LocalDateTime observedAt = sourceTime.plusSeconds(5);
        IssueDO issue = issue("OPEN", sourceTime, 6);
        issue.setSourceObservedAt(observedAt);

        assertThat(policy.isBettingClosed(issue, observedAt.plusSeconds(5))).isFalse();
        assertThat(policy.effectiveRemainingSeconds(issue, observedAt.plusSeconds(5))).isEqualTo(1);
        assertThat(policy.isBettingClosed(issue, observedAt.plusSeconds(6))).isTrue();
        assertThat(policy.authoritativeSourceTime(issue, observedAt.plusSeconds(3)))
                .isEqualTo(sourceTime.plusSeconds(3));
        assertThat(policy.authoritativeBettingCutoffTime(issue)).isEqualTo(sourceTime.plusSeconds(6));
    }

    @Test
    void missingServerTimeFallsBackToDatabaseUpdateTime() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 10, 10, 15, 13);
        IssueDO issue = issue("OPEN", null, 0);
        issue.setUpdateTime(updatedAt);

        assertThat(policy.isStale(issue, updatedAt.plusSeconds(89))).isFalse();
        assertThat(policy.isStale(issue, updatedAt.plusSeconds(90))).isTrue();
        assertThat(policy.isBettingClosed(issue, updatedAt)).isTrue();
    }

    private IssueDO issue(String status, LocalDateTime serverTime, int remainingSeconds) {
        IssueDO issue = new IssueDO();
        issue.setStatus(status);
        issue.setServerTime(serverTime);
        issue.setRemainingSeconds(remainingSeconds);
        return issue;
    }
}
