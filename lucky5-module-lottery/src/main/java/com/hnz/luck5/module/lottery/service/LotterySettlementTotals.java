package com.hnz.luck5.module.lottery.service;

import java.math.BigDecimal;

/**
 * Keeps room display totals separate from real-player business totals.
 */
final class LotterySettlementTotals {

    private BigDecimal realBet = BigDecimal.ZERO;
    private BigDecimal realPayout = BigDecimal.ZERO;
    private BigDecimal roomPayout = BigDecimal.ZERO;

    void add(BigDecimal bet, BigDecimal payout, boolean autoProxy) {
        BigDecimal safeBet = bet == null ? BigDecimal.ZERO : bet;
        BigDecimal safePayout = payout == null ? BigDecimal.ZERO : payout;
        roomPayout = roomPayout.add(safePayout);
        if (!autoProxy) {
            realBet = realBet.add(safeBet);
            realPayout = realPayout.add(safePayout);
        }
    }

    BigDecimal realBet() {
        return realBet;
    }

    BigDecimal realPayout() {
        return realPayout;
    }

    BigDecimal roomPayout() {
        return roomPayout;
    }
}
