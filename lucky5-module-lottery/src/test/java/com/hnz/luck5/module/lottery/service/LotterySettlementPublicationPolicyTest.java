package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LotterySettlementPublicationPolicyTest {

    @Test
    void systemHistoricalBackfillDoesNotPublishRoomArtifacts() {
        assertThat(LotteryServiceImpl.shouldPublishSettlementArtifacts("system", false, null)).isFalse();
    }

    @Test
    void liveEmptyPeriodStillPublishesZeroPayoutSummary() {
        assertThat(LotteryServiceImpl.shouldPublishSettlementArtifacts(
                "system", false, LocalDateTime.now())).isTrue();
    }

    @Test
    void periodsWithOrdersAndManualSettlementsStillPublish() {
        assertThat(LotteryServiceImpl.shouldPublishSettlementArtifacts("system", true, null)).isTrue();
        assertThat(LotteryServiceImpl.shouldPublishSettlementArtifacts("admin", false, null)).isTrue();
    }
}
