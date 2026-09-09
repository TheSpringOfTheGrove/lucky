package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryRoomMessagePolicyTest {

    private final LotteryRoomMessagePolicy policy = new LotteryRoomMessagePolicy();

    @Test
    void classifiesEveryUnrecognizedMessageAsBetSoItGetsARejectionReply() {
        assertThat(policy.classify("123456")).isEqualTo(LotteryRoomMessagePolicy.MessageType.BET);
        assertThat(policy.classify("老板晚上好")).isEqualTo(LotteryRoomMessagePolicy.MessageType.BET);
        assertThat(policy.classify("今天是9号")).isEqualTo(LotteryRoomMessagePolicy.MessageType.BET);
        assertThat(policy.looksLikeBetIntent("123456")).isTrue();
    }

    @Test
    void classifiesReadAndWriteCommandsAsOperations() {
        assertThat(policy.classify("查")).isEqualTo(LotteryRoomMessagePolicy.MessageType.BALANCE);
        assertThat(policy.classify("盈亏")).isEqualTo(LotteryRoomMessagePolicy.MessageType.PROFIT_LOSS);
        assertThat(policy.classify("yk")).isEqualTo(LotteryRoomMessagePolicy.MessageType.PROFIT_LOSS);
        assertThat(policy.classify("上分100")).isEqualTo(LotteryRoomMessagePolicy.MessageType.AMOUNT);
        assertThat(policy.classify("退码L5-100")).isEqualTo(LotteryRoomMessagePolicy.MessageType.CANCEL);
        assertThat(policy.classify("大100 单50")).isEqualTo(LotteryRoomMessagePolicy.MessageType.BET);
        assertThat(policy.classify("千12百34二定各10")).isEqualTo(LotteryRoomMessagePolicy.MessageType.BET);
    }

    @Test
    void classifiesMalformedBetIntentAsBetSoItGetsARejectionReply() {
        assertThat(policy.classify("兄弟2")).isEqualTo(LotteryRoomMessagePolicy.MessageType.BET);
        assertThat(policy.looksLikeBetIntent("兄弟2")).isTrue();
        assertThat(policy.classify("452现456个0.56")).isEqualTo(LotteryRoomMessagePolicy.MessageType.BET);
        assertThat(policy.looksLikeBetIntent("452现456个0.56")).isTrue();
    }
}
