package com.hnz.luck5.module.lottery.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private final LotteryBettingService bettingService = new LotteryBettingService();

    public record CurrentOrder(String period, String content, boolean confirmed) {
    }

    public String roomClosed(String memberName) {
        return "@" + memberName + "\n" + roomClosedReason();
    }

    public String roomClosedReason() {
        return "机器人尚未启动，请联系老师";
    }

    public String periodClosed(String memberName) {
        return "@" + memberName + "\n已结束";
    }

    public String drawResult(String period, String numbers) {
        LotteryBettingService.DrawResult draw = bettingService.deriveDraw(numbers);
        return "^^--| " + periodSuffix(period) + "期开奖结果-"
                + String.join("|", draw.digits().stream().map(String::valueOf).toList())
                + "|" + draw.dragonTiger();
    }

    public String balance(String memberName, List<CurrentOrder> currentOrders, BigDecimal balance) {
        String orders = currentOrders.isEmpty() ? "目前无房源" : currentOrders.stream()
                .map(order -> "[挂牌时间]" + periodSuffix(order.period()) + "\n" + displayCommands(order.content())
                        + "\n【状态】:" + (order.confirmed() ? "房源已录入成功✓✓" : "正在确认明细"))
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("目前无房源");
        return "@" + memberName + "\n【目前房源】：\n" + orders
                + "\n\n【您目前】：\n" + number(balance);
    }

    public String profitLoss(String memberName, BigDecimal profitLoss) {
        return "@" + memberName + "\nyk：" + number(profitLoss);
    }

    public String flow(String memberName, BigDecimal total, BigDecimal normal, BigDecimal dragon) {
        return "@" + memberName + "\n累计流水：" + fixed(total, 2)
                + "，普通：" + fixed(normal, 2) + "，龙虎：" + fixed(dragon, 2);
    }

    public String flowRebate(String memberName, BigDecimal pendingNormal, BigDecimal pendingDragon,
                             BigDecimal paidNormal, BigDecimal paidDragon, BigDecimal balance) {
        return "@" + memberName + "\n可返流水：" + fixed(pendingNormal.add(pendingDragon), 2)
                + "，普通：" + fixed(pendingNormal, 2) + "，龙虎：" + fixed(pendingDragon, 2)
                + "\n返还水：" + fixed(paidNormal.add(paidDragon), 3)
                + "，普通：" + fixed(paidNormal, 3) + "，龙虎：" + fixed(paidDragon, 3)
                + "\n目前：" + fixed(balance, 2);
    }

    public String flowUnavailable(String memberName) {
        return "@" + memberName + "\n当前不可查流水";
    }

    private String fixed(BigDecimal value, int scale) {
        return (value == null ? BigDecimal.ZERO : value).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    public String followResult(String memberName, String result) {
        return "@" + memberName + "\n" + result;
    }

    public String followBalanceStopped(String memberName) {
        return "@" + memberName + "\n余额不足，跟单已停止";
    }

    public String followSourceFailed(String memberName) {
        return "@" + memberName + "\n原下注未成功，这条跟单已停止";
    }

    public String noDrawResult(String memberName) {
        return "@" + memberName + "\n暂无开奖结果";
    }

    public String cancelCurrentNoRecord(String memberName) {
        return "@" + memberName + "\n当前期没有可取消的订单";
    }

    public String cancelCurrentResult(String memberName, int cancelled, int pending, int rejected) {
        if (cancelled == 0 && pending == 0) {
            return "@" + memberName + "\n当前期订单不能退码";
        }
        return "@" + memberName + "\n" + cancelCurrentSummary(cancelled, pending, rejected);
    }

    public String followStopResult(String memberName, int stopped, int matched,
                                   int cancelled, int pending, int rejected) {
        String stoppedText = stopped > 0 ? "所有跟单已停止" : "当前没有进行中的跟单";
        String cancellationText = matched == 0 ? "当前期没有可退的跟单订单"
                : cancelled == 0 && pending == 0 ? "当前期跟单订单不能退码"
                : cancelCurrentSummary(cancelled, pending, rejected);
        return "@" + memberName + "\n" + stoppedText + "\n" + cancellationText;
    }

    private String cancelCurrentSummary(int cancelled, int pending, int rejected) {
        List<String> results = new java.util.ArrayList<>();
        if (cancelled > 0) results.add("已退码" + cancelled + "笔");
        if (pending > 0) results.add("退码处理中" + pending + "笔");
        if (rejected > 0) results.add("未能退码" + rejected + "笔");
        return "当前期" + String.join("，", results);
    }

    public String periodSummary(List<String> memberCommands) {
        if (memberCommands == null || memberCommands.isEmpty()) {
            return "";
        }
        return "本期成功订单\n" + String.join("\n", memberCommands) + "\n------------";
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

    public String cancelSucceeded(String originalReceipt) {
        if (originalReceipt == null || originalReceipt.isBlank()) {
            return "已退码";
        }
        String receipt = originalReceipt.stripTrailing();
        if (receipt.endsWith("tui碼成功") || receipt.endsWith("退码成功")) return receipt;
        while (!receipt.isEmpty()) {
            int lastLineStart = receipt.lastIndexOf('\n') + 1;
            String lastLine = receipt.substring(lastLineStart).trim();
            if (!List.of("点击退码", "已退码", "正在确认明细").contains(lastLine)) {
                break;
            }
            receipt = receipt.substring(0, Math.max(0, lastLineStart - 1)).stripTrailing();
        }
        return receipt.isEmpty() ? "已退码" : receipt + "\n\n已退码";
    }

    public String cancelSuccessReceipt(String memberName, String period, int sequence, String content,
                                       int itemCount, BigDecimal refunded, BigDecimal balance) {
        return "@" + memberName + "\n[挂牌时间]" + period
                + "\n【编号】： " + sequence + "\n" + displayCommands(content)
                + "\n【套内】：" + itemCount
                + "\n【退面积】：" + receiptMoney(refunded)
                + "\n【面积】：" + receiptMoney(balance)
                + "\ntui碼成功";
    }

    public String cancelPending(String orderId) {
        return "";
    }

    public String cancelRejected(String memberName) {
        return "@" + memberName + "\n退码失败，当前订单不能退码";
    }

    public String cancelReview(String memberName) {
        return "@" + memberName + "\n退码结果待确认，请联系管理员";
    }

    public String marketBetPending(String memberName, String period, String content) {
        return "@" + memberName + "\n提交中";
    }

    public String betFailed(String memberName, BigDecimal refunded) {
        return "@" + memberName + "\n下注失败\n下注金额" + number(refunded) + "已退回";
    }

    public String balanceNotEnough(String memberName, String content, BigDecimal required, BigDecimal balance) {
        return "@" + memberName + "\n【房源不足】\n" + displayCommands(content)
                + "\n【此需】：" + number(required)
                + "\n【您目前】：" + number(balance);
    }

    public String betRejected(String memberName, String reason) {
        if ("指令错误".equals(reason)) {
            return "@" + memberName + "\n指令错误";
        }
        return "@" + memberName + "\n下注失败\n" + reason;
    }

    public String betReceipt(String memberName, String period, String content, int sequence, int itemCount,
                             BigDecimal amount, BigDecimal balance) {
        return betReceipt(memberName, period, content, sequence, itemCount, amount, balance, "点击退码");
    }

    public String betReceiptAwaitingDetails(String memberName, String period, String content, int sequence,
                                            int itemCount, BigDecimal amount, BigDecimal balance) {
        return betReceipt(memberName, period, content, sequence, itemCount, amount, balance, "正在确认明细");
    }

    /** The external market returned an exact accepted count and amount, but not every row identifier yet. */
    public String betReceiptAcceptedWithoutDetails(String memberName, String period, String content, int sequence,
                                                   int itemCount, BigDecimal amount, BigDecimal balance) {
        // The internal confirmation state must not add a second, non-reference label to the saved receipt.
        return betReceipt(memberName, period, content, sequence, itemCount, amount, balance);
    }

    public String normalizeAcceptedReceipt(String receipt) {
        if (receipt == null || !receipt.contains("【户型审核成功】")) return receipt;
        return String.join("\n", receipt.replace("\r\n", "\n").lines()
                .filter(line -> !"已受理".equals(line.trim())).toList()).stripTrailing();
    }

    private String betReceipt(String memberName, String period, String content, int sequence, int itemCount,
                              BigDecimal amount, BigDecimal balance, String action) {
        return "@" + memberName + "\n[挂牌时间]" + periodSuffix(period) + "\n" + displayCommands(content)
                + "\n【户型审核成功】√√\n【编号】" + sequence
                + "\n【套内】:" + itemCount + "\n【套外】:" + receiptMoney(amount)
                + "\n【面积】:" + receiptMoney(balance) + "\n\n" + action;
    }

    private String receiptMoney(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String displayCommands(String content) {
        return String.join("\n", bettingService.splitCommandsForDisplay(content));
    }

    /** Group receipts use the same visible fields as the member's receipt. */
    public String publicBetReceipt(String memberName, String receipt) {
        if (receipt == null || receipt.isBlank()) {
            return "";
        }
        if (receipt.contains("订单号")) {
            return "@" + memberName + "\n下注成功"
                    + (receipt.contains("点击退码") ? "\n点击退码" : "");
        }
        List<String> visible = new ArrayList<>();
        for (String line : receipt.split("\\R")) {
            String normalized = line.trim();
            if (normalized.equals("正在确认明细")
                    || normalized.startsWith("可用积分")
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

    public String payoutSummary(List<String> memberSettlements, BigDecimal periodPayout) {
        List<String> visible = memberSettlements == null ? List.of() : memberSettlements.stream()
                .filter(item -> item != null && !item.isBlank())
                .toList();
        if (visible.isEmpty()) {
            return payoutSummary(periodPayout);
        }
        return String.join("\n--------\n", visible)
                + "\n--------\n" + payoutSummary(periodPayout);
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
