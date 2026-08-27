package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LotterySettlementTotalsTest {

    @Test
    void shouldIncludeAutoProxyPayoutOnlyInRoomSummaryTotal() {
        LotterySettlementTotals totals = new LotterySettlementTotals();

        totals.add(new BigDecimal("10"), new BigDecimal("20"), false);
        totals.add(new BigDecimal("5"), new BigDecimal("50"), true);

        assertThat(totals.realBet()).isEqualByComparingTo("10");
        assertThat(totals.realPayout()).isEqualByComparingTo("20");
        assertThat(totals.roomPayout()).isEqualByComparingTo("70");
    }
}
