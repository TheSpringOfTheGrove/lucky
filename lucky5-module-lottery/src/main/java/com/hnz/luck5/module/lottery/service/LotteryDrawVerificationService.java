package com.hnz.luck5.module.lottery.service;

import org.springframework.stereotype.Service;

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
                             LocalDateTime currentFirstSeenAt, String apiResult, LocalDateTime now) {
        String normalized = apiResult == null ? "" : apiResult.trim();
        if (!normalized.matches("\\d{5}") || ZERO_RESULT.equals(normalized)) {
            if (IN_PROGRESS.contains(currentStatus) || "SETTLED".equals(currentStatus)) {
                return new Decision(Outcome.CONFLICT, currentStatus, value(currentResult),
                        value(currentConfirmations), currentFirstSeenAt,
                        "开奖API返回异常号码 " + display(normalized) + "，首次有效结果保持不变");
            }
            return new Decision(Outcome.ABNORMAL, STATUS_ABNORMAL, normalized, 0, null,
                    "开奖API返回异常号码 " + display(normalized) + "，已暂停自动结算");
        }

        if ("SETTLED".equals(currentStatus)) {
            if (normalized.equals(value(currentResult))) {
                return new Decision(Outcome.UNCHANGED, currentStatus, normalized,
                        Math.max(1, value(currentConfirmations)), currentFirstSeenAt, "");
            }
            return new Decision(Outcome.CONFLICT, currentStatus, value(currentResult),
                    value(currentConfirmations), currentFirstSeenAt,
                    "开奖API号码 " + normalized + " 与已结算号码 " + display(currentResult) + " 不一致");
        }

        if (IN_PROGRESS.contains(currentStatus)) {
            if (normalized.equals(value(currentResult))) {
                return new Decision(Outcome.UNCHANGED, currentStatus, normalized,
                        Math.max(1, value(currentConfirmations)), currentFirstSeenAt, "");
            }
            return new Decision(Outcome.CONFLICT, currentStatus, value(currentResult),
                    value(currentConfirmations), currentFirstSeenAt,
                    "首次有效开奖号码发生变化，保持原号码并等待人工复核");
        }
        // Preserve a pre-upgrade candidate: the first valid result has already won that period.
        if (STATUS_PENDING.equals(currentStatus) && isTrusted(currentResult)
                && !normalized.equals(value(currentResult))) {
            return new Decision(Outcome.CONFLICT, currentStatus, value(currentResult),
                    value(currentConfirmations), currentFirstSeenAt,
                    "首次有效开奖号码发生变化，保持原号码并等待人工复核");
        }
        return new Decision(Outcome.VERIFIED, STATUS_VERIFIED, normalized,
                Math.max(1, value(currentConfirmations)),
                currentFirstSeenAt == null ? now : currentFirstSeenAt, "");
    }

    public boolean isTrusted(String result) {
        if (result == null) {
            return false;
        }
        String normalized = result.trim();
        if (normalized.matches("\\d,\\d,\\d,\\d,\\d")) {
            normalized = normalized.replace(",", "");
        }
        return normalized.matches("\\d{5}") && !ZERO_RESULT.equals(normalized);
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
