package com.hnz.luck5.module.lottery.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Converts an upstream draw observation into a state that is safe for automatic settlement.
 */
@Service
public class LotteryDrawVerificationService {

    public static final String STATUS_ABNORMAL = "DRAW_ABNORMAL";
    public static final String STATUS_PENDING = "DRAW_PENDING";
    public static final String STATUS_VERIFIED = "DRAWN";
    public static final String ZERO_RESULT = "00000";

    private static final Set<String> IN_PROGRESS = Set.of("DRAWN", "SETTLING");

    public enum Outcome {
        ABNORMAL,
        CANDIDATE,
        VERIFIED,
        UNCHANGED,
        CONFLICT
    }

    public record Decision(Outcome outcome, String status, String result, int confirmations,
                           LocalDateTime firstSeenAt, String error) {
    }

    public Decision evaluate(String currentStatus, String currentResult, Integer currentConfirmations,
                             LocalDateTime currentFirstSeenAt, String apiResult, LocalDateTime now,
                             Duration confirmationDelay) {
        String normalized = apiResult == null ? "" : apiResult.trim();
        if (!normalized.matches("\\d{5}") || ZERO_RESULT.equals(normalized)) {
            if ("SETTLED".equals(currentStatus) || "SETTLING".equals(currentStatus)) {
                return new Decision(Outcome.CONFLICT, currentStatus, value(currentResult),
                        value(currentConfirmations), currentFirstSeenAt,
                        "开奖API返回异常号码 " + display(normalized) + "，已结算结果保持不变");
            }
            return new Decision(Outcome.ABNORMAL, STATUS_ABNORMAL, normalized, 0, null,
                    "开奖API返回异常号码 " + display(normalized) + "，已暂停自动结算");
        }

        if ("SETTLED".equals(currentStatus)) {
            if (normalized.equals(value(currentResult))) {
                return new Decision(Outcome.UNCHANGED, currentStatus, normalized,
                        Math.max(2, value(currentConfirmations)), currentFirstSeenAt, "");
            }
            return new Decision(Outcome.CONFLICT, currentStatus, value(currentResult),
                    value(currentConfirmations), currentFirstSeenAt,
                    "开奖API号码 " + normalized + " 与已结算号码 " + display(currentResult) + " 不一致");
        }

        if (IN_PROGRESS.contains(currentStatus) && normalized.equals(value(currentResult))) {
            return new Decision(Outcome.UNCHANGED, currentStatus, normalized,
                    Math.max(2, value(currentConfirmations)), currentFirstSeenAt, "");
        }
        if ("SETTLING".equals(currentStatus)) {
            return new Decision(Outcome.CONFLICT, currentStatus, value(currentResult),
                    value(currentConfirmations), currentFirstSeenAt,
                    "结算过程中开奖API号码发生变化，保持原号码并等待人工复核");
        }

        int confirmations = value(currentConfirmations);
        boolean sameCandidate = STATUS_PENDING.equals(currentStatus) && normalized.equals(value(currentResult))
                && confirmations >= 1 && currentFirstSeenAt != null;
        Duration delay = confirmationDelay == null || confirmationDelay.isNegative() ? Duration.ZERO : confirmationDelay;
        if (sameCandidate && !now.isBefore(currentFirstSeenAt.plus(delay))) {
            return new Decision(Outcome.VERIFIED, STATUS_VERIFIED, normalized, confirmations + 1,
                    currentFirstSeenAt, "");
        }
        if (sameCandidate) {
            return new Decision(Outcome.CANDIDATE, STATUS_PENDING, normalized, confirmations,
                    currentFirstSeenAt, "等待开奖API二次确认");
        }
        return new Decision(Outcome.CANDIDATE, STATUS_PENDING, normalized, 1, now,
                "等待开奖API二次确认");
    }

    public boolean isTrusted(String result) {
        return result != null && result.matches("\\d{5}") && !ZERO_RESULT.equals(result);
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    private String value(String text) {
        return text == null ? "" : text;
    }

    private String display(String result) {
        return result == null || result.isBlank() ? "<空>" : result;
    }
}
