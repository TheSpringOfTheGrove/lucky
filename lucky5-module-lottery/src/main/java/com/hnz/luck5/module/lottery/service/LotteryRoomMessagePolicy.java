package com.hnz.luck5.module.lottery.service;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Distinguishes room chat from commands that can change or expose business state.
 */
@Service
public class LotteryRoomMessagePolicy {

    private static final Pattern AMOUNT_COMMAND = Pattern.compile("^(上|下)(?:分)?(\\d+(?:\\.\\d+)?)$");
    private static final Pattern CANCEL_COMMAND = Pattern.compile("^退(?:码)?\\s*([A-Za-z0-9_-]+)$");
    public enum MessageType {
        CHAT,
        BALANCE,
        PROFIT_LOSS,
        AMOUNT,
        CANCEL,
        BET
    }

    /**
     * Identifies the small set of non-betting room commands without parsing betting expressions.
     *
     * <p>A room message that is not one of these commands is always a betting attempt: valid betting
     * expressions are parsed exactly once by the transactional placement path, while invalid text is
     * persisted there as {@code BET_REJECTED}. Parsing here used to expand a large command once merely
     * to classify it and then expand it a second time to place it. A 69,141-item legacy command therefore
     * paid the full CPU and allocation cost twice before any database work began.</p>
     */
    public MessageType classify(String rawContent) {
        String content = rawContent == null ? "" : rawContent.trim();
        if (Set.of("查", "余额").contains(content)) {
            return MessageType.BALANCE;
        }
        if (Set.of("盈亏", "yk", "YK").contains(content)) {
            return MessageType.PROFIT_LOSS;
        }
        if (AMOUNT_COMMAND.matcher(content).matches()) {
            return MessageType.AMOUNT;
        }
        if (CANCEL_COMMAND.matcher(content).matches()) {
            return MessageType.CANCEL;
        }
        // The room input is command-only. Both valid and invalid remaining messages go through the same
        // transactional placement/rejection path so the original text and a clear robot response are retained.
        return MessageType.BET;
    }

    /**
     * The room input is command-only, so every non-blank message is an operation intent.
     */
    public boolean looksLikeBetIntent(String rawContent) {
        String content = rawContent == null ? "" : rawContent.trim();
        return !content.isEmpty();
    }

}
