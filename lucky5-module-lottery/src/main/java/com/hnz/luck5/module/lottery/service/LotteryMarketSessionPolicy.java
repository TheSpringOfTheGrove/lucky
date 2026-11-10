package com.hnz.luck5.module.lottery.service;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** Lucky5 market trades through the 05:00 draw (060), then resumes with the 08:05 draw (097). */
final class LotteryMarketSessionPolicy {

    private LotteryMarketSessionPolicy() {
    }

    static boolean isTradablePeriod(String period) {
        if (period == null || !period.matches("\\d{11}")) return true;
        int sequence = Integer.parseInt(period.substring(8));
        return sequence < 61 || sequence > 96;
    }

    static boolean isLastDailyPeriod(String period) {
        return period != null && period.matches("\\d{8}060");
    }

    static boolean isOpenTime(String period, LocalDateTime now) {
        if (!isTradablePeriod(period) || now == null) return false;
        if (period == null || !period.matches("\\d{11}")) return true;
        int sequence = Integer.parseInt(period.substring(8));
        return sequence < 97 || !now.toLocalTime().isBefore(LocalTime.of(8, 0));
    }

    static String nextTradablePeriod(String period) {
        return isTradablePeriod(period) ? period : period.substring(0, 8) + "097";
    }
}
