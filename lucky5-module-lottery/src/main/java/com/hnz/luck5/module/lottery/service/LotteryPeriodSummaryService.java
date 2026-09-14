package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes one owner-wide period order summary for group rooms and one member-only summary for private rooms.
 */
@Service
public class LotteryPeriodSummaryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryPeriodSummaryService.class);
    private static final String COMMAND_PERIOD_SUMMARY = "PERIOD_SUMMARY";

    @Resource private OrderMapper orderMapper;
    @Resource private MessageMapper messageMapper;
    @Resource private LotteryBettingService bettingService;
    @Resource private LotteryRobotReplyTemplate robotReplyTemplate;

    @Transactional(rollbackFor = Exception.class)
    public void publish(Long userId, String period) {
        if (userId == null || period == null || period.isBlank()) {
            return;
        }
        List<MessageDO> existingSummaries = DataPermissionUtils.executeIgnore(() -> messageMapper.selectList(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                        .eq(MessageDO::getPeriod, period).eq(MessageDO::getCommandType, COMMAND_PERIOD_SUMMARY)));
        List<OrderDO> orders = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(
                new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getUserId, userId)
                        .eq(OrderDO::getPeriod, period).eq(OrderDO::getStatus, "未开奖")
                        .orderByAsc(OrderDO::getCreateTime).orderByAsc(OrderDO::getId))).stream()
                .filter(this::isAcceptedForSummary).toList();

        List<String> groupLines = new ArrayList<>();
        Map<String, MemberSummary> memberSummaries = new LinkedHashMap<>();
        for (OrderDO order : orders) {
            List<String> commands = bettingService.splitCommandsForDisplay(order.getContent());
            for (String command : commands) {
                String line = "[" + order.getMemberName() + "]" + command;
                groupLines.add(line);
                if (order.getMemberId() != null && !order.getMemberId().isBlank()) {
                    memberSummaries.computeIfAbsent(order.getMemberId(), ignored ->
                            new MemberSummary(order.getMemberName(), new ArrayList<>())).lines().add(line);
                }
            }
        }

        Map<String, SummaryMessage> expected = new LinkedHashMap<>();
        if (!orders.isEmpty()) {
            expected.put("period-summary:g:" + period, new SummaryMessage("网页群", null, "",
                    robotReplyTemplate.periodSummary(groupLines)));
            memberSummaries.forEach((memberId, summary) -> expected.put(privateExternalId(period, memberId),
                    new SummaryMessage("网页私聊", memberId, summary.memberName(),
                            robotReplyTemplate.periodSummary(summary.lines()))));
        }
        Map<String, MessageDO> existingByExternalId = new LinkedHashMap<>();
        existingSummaries.forEach(message -> existingByExternalId.put(message.getExternalId(), message));
        expected.forEach((externalId, summary) -> upsert(userId, period, externalId, summary,
                existingByExternalId.remove(externalId)));

        // A summary can be created while an external order is still pending. If that order is later rejected and
        // refunded, preserve the chat row but clear its stale command list on the next reconciliation.
        existingByExternalId.values().forEach(this::clearObsoleteSummary);
    }

    private boolean isAcceptedForSummary(OrderDO order) {
        String deliveryMode = order.getDeliveryMode();
        if (!"MARKET_ADAPTER".equals(deliveryMode) && !"MIXED_MARKET".equals(deliveryMode)) {
            return true; // 本地单、历史本地单和本地吃码单在创建时即已受理
        }
        return "CONFIRMED".equals(order.getMarketStatus());
    }

    private void upsert(Long userId, String period, String externalId, SummaryMessage summary, MessageDO existing) {
        if (existing != null) {
            existing.setChannel(summary.channel());
            existing.setMemberId(summary.memberId());
            existing.setMember(summary.memberName());
            existing.setReply(summary.reply());
            existing.setStatus("已封盘");
            existing.setProcessedAt(LocalDateTime.now());
            messageMapper.updateById(existing);
            return;
        }
        MessageDO message = new MessageDO();
        message.setChannel(summary.channel());
        message.setMemberId(summary.memberId());
        message.setMember(summary.memberName());
        message.setPeriod(period);
        message.setContent("");
        message.setStatus("已封盘");
        message.setExternalId(externalId);
        message.setError("");
        message.setCommandType(COMMAND_PERIOD_SUMMARY);
        message.setMessageType("PLAYER");
        message.setReply(summary.reply());
        message.setProcessedAt(LocalDateTime.now());
        message.setUserId(userId);
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException ignored) {
            LOGGER.debug("期末成功订单汇总已存在 user={} period={} externalId={}", userId, period, externalId);
        }
    }

    private void clearObsoleteSummary(MessageDO message) {
        message.setReply(robotReplyTemplate.periodSummary(List.of()));
        message.setProcessedAt(LocalDateTime.now());
        messageMapper.updateById(message);
    }

    private String privateExternalId(String period, String memberId) {
        String stableMember = UUID.nameUUIDFromBytes(memberId.getBytes(StandardCharsets.UTF_8)).toString();
        return "period-summary:p:" + period + ":" + stableMember;
    }

    private record MemberSummary(String memberName, List<String> lines) {
    }

    private record SummaryMessage(String channel, String memberId, String memberName, String reply) {
    }
}
