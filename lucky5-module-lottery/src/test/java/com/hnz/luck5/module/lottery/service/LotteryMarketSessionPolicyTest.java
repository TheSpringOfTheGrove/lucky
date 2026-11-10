package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryMarketSessionPolicyTest {

    @Test
    void closesAfterThe060DrawAndResumesWith097() {
        assertThat(LotteryMarketSessionPolicy.isTradablePeriod("20261110059")).isTrue();
        assertThat(LotteryMarketSessionPolicy.isTradablePeriod("20261110060")).isTrue();
        for (int sequence = 61; sequence <= 96; sequence++) {
            String period = "20261110" + String.format("%03d", sequence);
            assertThat(LotteryMarketSessionPolicy.isTradablePeriod(period)).isFalse();
            assertThat(LotteryMarketSessionPolicy.nextTradablePeriod(period)).isEqualTo("20261110097");
        }
        assertThat(LotteryMarketSessionPolicy.isTradablePeriod("20261110097")).isTrue();
        assertThat(LotteryMarketSessionPolicy.isOpenTime("20261110097",
                LocalDateTime.of(2026, 11, 10, 7, 59, 59))).isFalse();
        assertThat(LotteryMarketSessionPolicy.isOpenTime("20261110097",
                LocalDateTime.of(2026, 11, 10, 8, 0))).isTrue();
        assertThat(LotteryMarketSessionPolicy.isLastDailyPeriod("20261110060")).isTrue();
        assertThat(LotteryMarketSessionPolicy.isLastDailyPeriod("20261110059")).isFalse();
        assertThat(LotteryMarketSessionPolicy.nextTradablePeriod("20261110098")).isEqualTo("20261110098");
    }
}
