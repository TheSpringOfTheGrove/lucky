package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.OddDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryRoomMessagePolicyTest {

    private LotteryRoomMessagePolicy policy;
    private List<OddDO> odds;

    @BeforeEach
    void setUp() {
        policy = new LotteryRoomMessagePolicy(new LotteryBettingService());
        odds = List.of(
                odd("regex1d", "一定位", "9"), odd("regex2d", "二定位", "96"),
                odd("regex3d", "三定位", "960"), odd("regex4d", "四定位", "9600"),
                odd("regex2x", "二字现", "9"), odd("regex3x", "三字现", "45"),
                odd("regex4x", "四字现", "360"), odd("regex4d4", "四条", "7000"),
                odd("regexlh", "龙虎", "0"), odd("regexh", "和", "0"));
    }

    @Test
    void classifiesPlainTextAsChat() {
        assertThat(policy.classify("老板晚上好", odds)).isEqualTo(LotteryRoomMessagePolicy.MessageType.CHAT);
        assertThat(policy.classify("今天是9号", odds)).isEqualTo(LotteryRoomMessagePolicy.MessageType.CHAT);
    }

    @Test
    void classifiesReadAndWriteCommandsAsOperations() {
        assertThat(policy.classify("查", odds)).isEqualTo(LotteryRoomMessagePolicy.MessageType.BALANCE);
        assertThat(policy.classify("上分100", odds)).isEqualTo(LotteryRoomMessagePolicy.MessageType.AMOUNT);
        assertThat(policy.classify("退码L5-100", odds)).isEqualTo(LotteryRoomMessagePolicy.MessageType.CANCEL);
        assertThat(policy.classify("大100 单50", odds)).isEqualTo(LotteryRoomMessagePolicy.MessageType.BET);
        assertThat(policy.classify("千12百34二定各10", odds)).isEqualTo(LotteryRoomMessagePolicy.MessageType.BET);
    }

    private OddDO odd(String code, String play, String rate) {
        OddDO odd = new OddDO();
        odd.setCode(code);
        odd.setPlay(play);
        odd.setItem("");
        odd.setRate(new BigDecimal(rate));
        odd.setMinLimit(new BigDecimal("0.1"));
        odd.setMaxLimit(new BigDecimal("10000"));
        odd.setStatus("启用");
        return odd;
    }

}
