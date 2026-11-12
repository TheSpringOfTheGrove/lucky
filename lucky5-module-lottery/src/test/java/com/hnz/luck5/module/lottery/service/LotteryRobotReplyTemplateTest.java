package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.AmountRecordDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryRobotReplyTemplateTest {

    private final LotteryRobotReplyTemplate template = new LotteryRobotReplyTemplate();

    @Test
    void shouldShowOnlyTheRequestedMembersTransferHistory() {
        AmountRecordDO approved = new AmountRecordDO();
        approved.setCreateTime(LocalDateTime.of(2026, 11, 8, 10, 30));
        approved.setType("上分");
        approved.setAmount(new BigDecimal("100.00"));
        approved.setStatus("已通过");
        AmountRecordDO pending = new AmountRecordDO();
        pending.setCreateTime(LocalDateTime.of(2026, 11, 7, 22, 5));
        pending.setType("下分");
        pending.setAmount(new BigDecimal("20.50"));
        pending.setStatus("待审核");

        assertThat(template.amountHistory("阿杰", List.of(approved, pending)))
                .isEqualTo("@阿杰\n最近上下分：\n11-08 10:30 上分：100（已通过）"
                        + "\n11-07 22:05 下分：20.5（待审核）");
        assertThat(template.amountHistory("阿杰", List.of())).isEqualTo("@阿杰\n暂无上下分记录");
    }

    @Test
    void shouldFormatFlowAndFlowRebateLikeReferenceRoom() {
        assertThat(template.flow("阿杰", new BigDecimal("420"), new BigDecimal("400"),
                new BigDecimal("20")))
                .isEqualTo("@阿杰\n累计流水：420.00\n普通流水：400.00");
        assertThat(template.flowRebate("阿杰", new BigDecimal("100"), new BigDecimal("20"),
                new BigDecimal("1"), new BigDecimal("0.4"), new BigDecimal("11866.4")))
                .isEqualTo("@阿杰\n可返：120.00\n普通：100.00\n已回：1.40\n\n目前：11866.40");
        assertThat(template.flowRebate("广东二哥", new BigDecimal("20000"), BigDecimal.ZERO,
                new BigDecimal("200"), BigDecimal.ZERO, new BigDecimal("10116.8")))
                .isEqualTo("@广东二哥\n可返：20000.00\n普通：20000.00\n已回：200.00\n\n目前：10116.80");
    }

    @Test
    void shouldFormatBetReceiptLikeReferenceRobot() {
        assertThat(template.betReceipt("露露", "20260809194", "654倒二定各10", 3, 36,
                new BigDecimal("360.00"), new BigDecimal("26764.45")))
                .isEqualTo("@露露\n【入住时间】194\n654倒二定各10\n【户型审核成功】√√√"
                        + "\n【编号】3\n【室内】:36\n【室外】:360.00\n【面积】:26764.45"
                        + "\n\n点击退码");
    }

    @Test
    void shouldPutEachCombinedInstructionOnItsOwnLine() {
        assertThat(template.betReceipt("玩家2", "20260809194", "2501各2,5201各2,2015各2", 2, 3,
                new BigDecimal("6"), new BigDecimal("100")))
                .contains("【入住时间】194\n2501各2\n5201各2\n2015各2\n【户型审核成功】");
    }

    @Test
    void acceptedBatchUsesTheSameReceiptWithoutAnExtraStateLabel() {
        String expected = template.betReceipt("玩家", "20260928236", "1234各1", 1, 1,
                BigDecimal.ONE, new BigDecimal("99"));
        assertThat(template.betReceiptAcceptedWithoutDetails("玩家", "20260928236", "1234各1", 1, 1,
                BigDecimal.ONE, new BigDecimal("99"))).isEqualTo(expected).doesNotContain("已受理");
        assertThat(template.normalizeAcceptedReceipt(expected + "\n已受理")).isEqualTo(expected);
        assertThat(template.normalizeAcceptedReceipt(expected + "\n已受理\n已退码"))
                .isEqualTo(expected + "\n已退码");
        assertThat(template.normalizeAcceptedReceipt("玩家说已受理")).isEqualTo("玩家说已受理");
    }

    @Test
    void shouldShowAreaAndCancelActionInPublicGroupReceipt() {
        String privateReceipt = template.betReceipt("露露", "20260809194", "654倒二定各10", 3, 36,
                new BigDecimal("360"), new BigDecimal("26764.45"));

        assertThat(template.publicBetReceipt("露露", privateReceipt))
                .contains("@露露\n【入住时间】194\n654倒二定各10\n【户型审核成功】√√√",
                        "【编号】3", "【室内】:36", "【室外】:360.00", "【面积】:26764.45", "点击退码")
                .doesNotContain("共 36 注", "合计 360.00");
        assertThat(template.publicBetReceipt("露露", "下注成功，订单号 O1001"))
                .isEqualTo("@露露\n下注成功");
        assertThat(template.publicBetReceipt("露露", "下注成功，订单号 O1001\n点击退码"))
                .isEqualTo("@露露\n下注成功\n点击退码");
        assertThat(template.publicBetReceipt("露露", "")).isEmpty();

        String awaitingDetails = template.betReceiptAwaitingDetails("露露", "20260809194",
                "654倒二定各10", 3, 36, new BigDecimal("360"), new BigDecimal("26764.45"));
        assertThat(template.publicBetReceipt("露露", awaitingDetails))
                .contains("【面积】:26764.45")
                .doesNotContain("正在确认明细");
    }

    @Test
    void shouldReplaceCancelActionOnOriginalBetReceipt() {
        String receipt = template.betReceipt("玩家2", "20260812153", "三现全倒112各2", 4, 1,
                new BigDecimal("2"), new BigDecimal("43"));

        assertThat(template.cancelSucceeded(receipt))
                .isEqualTo("@玩家2\n【入住时间】153\n三现全倒112各2\n【户型审核成功】√√√"
                        + "\n【编号】4\n【室内】:1\n【室外】:2.00\n【面积】:43.00"
                        + "\n\n已退码")
                .doesNotContain("退码成功：");
        assertThat(template.cancelSucceeded(template.cancelSucceeded(receipt)))
                .isEqualTo(template.cancelSucceeded(receipt));
        assertThat(template.cancelSucceeded(receipt + "\n已退码"))
                .isEqualTo(template.cancelSucceeded(receipt));
    }

    @Test
    void shouldRenderRefundReceiptWithFullIssueAndBalanceAfterRefund() {
        String receipt = template.cancelSuccessReceipt("晓琳", "20261015268", 12,
                "5962033785962倒四定各0.1", 3000, new BigDecimal("300"),
                new BigDecimal("19451.6"));

        assertThat(receipt).isEqualTo("@晓琳\n【入住时间】20261015268\n【编号】： 12"
                + "\n5962033785962倒四定各0.1\n【室内】：3000"
                + "\n【退面积】：300.00\n【面积】：19451.60\ntui碼成功");
        assertThat(template.cancelSucceeded(receipt)).isEqualTo(receipt);
    }

    @Test
    void shouldOnlyExposeFinalBetOutcomeToPlayer() {
        assertThat(template.betReceiptAwaitingDetails("露露", "20260809194", "654倒二定各10", 3, 36,
                new BigDecimal("360.00"), new BigDecimal("26764.45")))
                .isEqualTo("@露露\n【入住时间】194\n654倒二定各10\n【户型审核成功】√√√"
                        + "\n【编号】3\n【室内】:36\n【室外】:360.00\n【面积】:26764.45"
                        + "\n\n正在确认明细")
                .doesNotContain("盘口", "外盘");
        assertThat(template.betFailed("露露", new BigDecimal("360.00")))
                .isEqualTo("@露露\n下注失败\n下注金额360已退回")
                .doesNotContain("盘口", "外盘", "核对");
        assertThat(template.cancelPending("O1001")).isEmpty();
        assertThat(template.cancelRejected("露露"))
                .isEqualTo("@露露\n退码失败，当前订单不能退码")
                .doesNotContain("盘口", "外盘");
        assertThat(template.cancelReview("露露"))
                .isEqualTo("@露露\n退码结果待确认，请联系管理员")
                .doesNotContain("盘口", "外盘");
        assertThat(template.balanceNotEnough("露露", "654倒二定各10", new BigDecimal("360"),
                new BigDecimal("131")))
                .isEqualTo("@露露\n【房源不足】\n654倒二定各10\n【此需】：360\n【您目前】：131")
                .doesNotContain("盘口", "外盘");
        assertThat(template.betRejected("露露", "当前玩法暂不可用"))
                .isEqualTo("@露露\n下注失败\n当前玩法暂不可用")
                .doesNotContain("盘口", "外盘");
        assertThat(template.betRejected("露露", "指令错误"))
                .isEqualTo("@露露\n指令错误");
    }

    @Test
    void shouldFormatClosedAndAmountReplies() {
        assertThat(template.roomClosed("旺旺杀米米")).isEqualTo("@旺旺杀米米\n机器人尚未启动，请联系老师");
        assertThat(template.periodClosed("旺旺杀米米")).isEqualTo("@旺旺杀米米\n已结束");
        assertThat(template.amountPending("旺旺杀米米")).isEqualTo("@旺旺杀米米\n请稍后");
        assertThat(template.amountAudited("旺旺杀米米", "上分", new BigDecimal("100.00"),
                "已通过", new BigDecimal("65393.87")))
                .isEqualTo("@旺旺杀米米\n加分：100\n目前：65393.87");
        assertThat(template.amountAudited("旺旺杀米米", "下分", new BigDecimal("50.00"),
                "已通过", new BigDecimal("65343.87")))
                .isEqualTo("@旺旺杀米米\n减分：50\n目前：65343.87");
        assertThat(template.amountAudited("旺旺杀米米", "下分", new BigDecimal("50.00"),
                "已拒绝", new BigDecimal("65393.87")))
                .isEqualTo("@旺旺杀米米\n下分审核不通过[下50]");
    }

    @Test
    void shouldFormatSettlementLikeReferenceRobot() {
        assertThat(template.settlement("波陆秀", List.of("57XX，套数10，房费960", "X75X，套数10，房费960"),
                new BigDecimal("1920.00"), new BigDecimal("31052.52")))
                .isEqualTo("【波陆秀】入住：\n57XX，套数10，房费960\nX75X，套数10，房费960"
                        + "\n合房费：1920\n【当前面积】：31052.52")
                .doesNotContain("中介费");
        assertThat(template.payoutSummary(new BigDecimal("6720.00")))
                .isEqualTo("【本次总派送】：6720");
        assertThat(template.payoutSummary(BigDecimal.ZERO))
                .isEqualTo("【本次总派送】：0");
        assertThat(template.payoutSummary(List.of(
                "【波陆秀】入住：\n57XX，套数10，房费960\n合房费：960\n【当前面积】：31052.52",
                "【露露】入住：\n654X，套数5，房费480\n合房费：480\n【当前面积】：4374.1"),
                new BigDecimal("1440.00")))
                .isEqualTo("【波陆秀】入住：\n57XX，套数10，房费960\n合房费：960\n【当前面积】：31052.52"
                        + "\n--------\n【露露】入住：\n654X，套数5，房费480\n合房费：480\n【当前面积】：4374.1"
                        + "\n--------\n【本次总派送】：1440");
    }

    @Test
    void shouldFormatBalanceProfitAndPeriodSummaryLikeReferenceRobot() {
        assertThat(template.balance("露露", List.of(
                new LotteryRobotReplyTemplate.CurrentOrder("20260809194", "654倒二定各10", true),
                new LotteryRobotReplyTemplate.CurrentOrder("20260809194", "千12百34二定各1", false)),
                new BigDecimal("4374.10")))
                .isEqualTo("@露露\n【今日房源】：\n【入住时间】194\n654倒二定各10"
                        + "\n【状态】：房源已提交成功√√√\n\n【入住时间】194\n千12百34二定各1"
                        + "\n【状态】：正在确认明细\n\n【您目前余】\n4374.1");
        assertThat(template.balance("露露", List.of(), new BigDecimal("20031")))
                .isEqualTo("@露露\n【今日房源】：\n\n【您目前余】\n20031")
                .doesNotContain("目前无房源");
        assertThat(template.profitLoss("广西一哥", new BigDecimal("-1857.08")))
                .isEqualTo("@广西一哥\n盈亏：-1857.08");
        assertThat(template.periodSummary(List.of("[露露]654倒二定各10", "[露露]654倒二定各10")))
                .isEqualTo("本期成功订单\n[露露]654倒二定各10\n[露露]654倒二定各10\n------------");
    }
}
