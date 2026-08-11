package com.hnz.luck5.module.lottery.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralized text templates for member-facing robot replies.
 *
 * <p>The wording intentionally follows the established Lucky5 room protocol. Keep business values as parameters so
 * every message channel renders the same receipt instead of rebuilding similar strings independently.</p>
 */
@Component
public class LotteryRobotReplyTemplate {

    public String roomClosed(String memberName) {
        return "@" + memberName + "\n当前未开盘";
    }

    public String balance(String memberName, String currentOrders, BigDecimal balance) {
        return "@" + memberName + "\n【目前房源】：\n" + currentOrders
                + "\n\n【您目前】：\n" + number(balance);
    }

    public String amountPending(String memberName) {
        return "@" + memberName + "\n请稍后";
    }

    public String amountAudited(String memberName, String type, BigDecimal amount, String status,
                                BigDecimal balance) {
        String amountText = number(amount);
        if ("已通过".equals(status)) {
            return "上分".equals(type)
                    ? "@" + memberName + "\n审批通过[加油" + amountText + "]\n现在:" + number(balance)
                    : "@" + memberName + "\n考核成功下" + amountText + "\n现在:" + number(balance);
        }
        return "@" + memberName + "\n" + type + "审核不通过["
                + ("上分".equals(type) ? "上" : "下") + amountText + "]";
    }

    public String cancelSucceeded(String orderId, BigDecimal refunded) {
        return "退码成功：" + orderId + "，退回 " + number(refunded);
    }

    public String cancelPending(String orderId) {
        return "退码申请已提交盘口核对：" + orderId + "，确认成功后自动退回积分";
    }

    public String marketBetPending(String memberName, String period, String content) {
        return "@" + memberName + "\n[挂牌时间]" + periodSuffix(period) + "\n" + content
                + "\n【盘口提交中】请等待盘口确认，确认前不会显示下注成功";
    }

    public String betReceipt(String memberName, String period, String content, int sequence, int itemCount,
                             BigDecimal amount, BigDecimal balance) {
        return "@" + memberName + "\n[挂牌时间]" + periodSuffix(period) + "\n" + content
                + "\n【户型审核成功】✓✓\n【编号】：" + sequence
                + "\n【套内】：" + itemCount + "\n【套外】：" + number(amount)
                + "\n【面积】：" + number(balance) + "\n点击退码";
    }

    /**
     * Group rooms may show that another player was accepted, but must not expose their balance or cancellation entry.
     */
    public String publicBetReceipt(String memberName, String receipt) {
        if (receipt == null || receipt.isBlank() || receipt.contains("订单号")) {
            return "@" + memberName + "\n下注成功";
        }
        List<String> visible = new ArrayList<>();
        for (String line : receipt.split("\\R")) {
            String normalized = line.trim();
            if (normalized.startsWith("【面积】") || normalized.startsWith("面积：")
                    || normalized.equals("点击退码") || normalized.startsWith("可用积分")
                    || normalized.startsWith("余额")) {
                continue;
            }
            visible.add(line);
        }
        String result = String.join("\n", visible).trim();
        return result.isEmpty() ? "@" + memberName + "\n下注成功" : result;
    }

    public String drawSourceStale(String memberName) {
        return "@" + memberName + "\n开奖数据异常，当前暂停下注";
    }

    public String settlement(String memberName, List<String> winningLines, BigDecimal memberPayout,
                             BigDecimal currentBalance) {
        return "【" + memberName + "】入住：\n" + String.join("\n", winningLines)
                + "\n合房费：" + number(memberPayout)
                + "\n【当前面积】：" + number(currentBalance);
    }

    public String payoutSummary(BigDecimal periodPayout) {
        return "【本次总派送】：" + number(periodPayout);
    }

    String number(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String periodSuffix(String period) {
        if (period == null) {
            return "";
        }
        return period.length() <= 3 ? period : period.substring(period.length() - 3);
    }
}
