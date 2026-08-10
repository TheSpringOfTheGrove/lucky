package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryRobotReplyTemplateTest {

    private final LotteryRobotReplyTemplate template = new LotteryRobotReplyTemplate();

    @Test
    void shouldFormatBetReceiptLikeReferenceRobot() {
        assertThat(template.betReceipt("露露", "20260809194", "654倒二定各10", 3, 36,
                new BigDecimal("360.00"), new BigDecimal("26764.45")))
                .isEqualTo("@露露\n[挂牌时间]194\n654倒二定各10\n【户型审核成功】√√"
                        + "\n【编号】：3\n【套内】：36\n【套外】：360\n【面积】：26764.45\n点击退码");
    }

    @Test
    void shouldRemoveBalanceAndCancelActionFromPublicGroupReceipt() {
        String privateReceipt = template.betReceipt("露露", "20260809194", "654倒二定各10", 3, 36,
                new BigDecimal("360"), new BigDecimal("26764.45"));

        assertThat(template.publicBetReceipt("露露", privateReceipt))
                .isEqualTo("@露露\n[挂牌时间]194\n654倒二定各10\n【户型审核成功】√√"
                        + "\n【编号】：3\n【套内】：36\n【套外】：360")
                .doesNotContain("26764.45", "点击退码");
        assertThat(template.publicBetReceipt("露露", "下注成功，订单号 O1001"))
                .isEqualTo("@露露\n下注成功");
    }

    @Test
    void shouldFormatClosedAndAmountReplies() {
        assertThat(template.roomClosed("旺旺杀米米")).isEqualTo("@旺旺杀米米\n当前未开盘");
        assertThat(template.amountPending("旺旺杀米米")).isEqualTo("@旺旺杀米米\n请稍后");
        assertThat(template.amountAudited("旺旺杀米米", "上分", new BigDecimal("100.00"),
                "已通过", new BigDecimal("65393.87")))
                .isEqualTo("@旺旺杀米米\n审批通过[加油100]\n现在:65393.87");
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
    }
}
