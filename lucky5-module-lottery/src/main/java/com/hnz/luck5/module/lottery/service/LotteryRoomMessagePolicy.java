package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.common.exception.ServiceException;
import com.hnz.luck5.module.lottery.dal.dataobject.OddDO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.BET_CONTENT_INVALID;

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
        AMOUNT,
        CANCEL,
        BET
    }

    private final LotteryBettingService bettingService;

    public LotteryRoomMessagePolicy(LotteryBettingService bettingService) {
        this.bettingService = bettingService;
    }

    public MessageType classify(String rawContent, List<OddDO> odds) {
        String content = rawContent == null ? "" : rawContent.trim();
        if (Set.of("查", "余额").contains(content)) {
            return MessageType.BALANCE;
        }
        if (AMOUNT_COMMAND.matcher(content).matches()) {
            return MessageType.AMOUNT;
        }
        if (CANCEL_COMMAND.matcher(content).matches()) {
            return MessageType.CANCEL;
        }
        try {
            bettingService.parse(content, odds);
            return MessageType.BET;
        } catch (ServiceException ex) {
            if (BET_CONTENT_INVALID.getCode().equals(ex.getCode())) {
                return MessageType.CHAT;
            }
            // The parser recognized a bet but rejected a limit or configuration. It is still an operation.
            return MessageType.BET;
        }
    }

}
