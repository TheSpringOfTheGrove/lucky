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
        if (exists(userId, "period-summary:g:" + period)) {
            return;
        }
        List<OrderDO> orders = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(
                new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getUserId, userId)
                        .eq(OrderDO::getPeriod, period).ne(OrderDO::getStatus, "已退码")
                        .orderByAsc(OrderDO::getCreateTime).orderByAsc(OrderDO::getId)));
        if (orders.isEmpty()) {
            return;
        }

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

        insertIfAbsent(userId, period, "网页群", null, "", "period-summary:g:" + period,
                robotReplyTemplate.periodSummary(groupLines));
        memberSummaries.forEach((memberId, summary) -> insertIfAbsent(userId, period, "网页私聊", memberId,
                summary.memberName(), privateExternalId(period, memberId),
                robotReplyTemplate.periodSummary(summary.lines())));
    }

    private void insertIfAbsent(Long userId, String period, String channel, String memberId, String memberName,
                                String externalId, String reply) {
        if (exists(userId, externalId)) {
            return;
        }
        MessageDO message = new MessageDO();
        message.setChannel(channel);
        message.setMemberId(memberId);
        message.setMember(memberName);
        message.setPeriod(period);
        message.setContent("");
        message.setStatus("已封盘");
        message.setExternalId(externalId);
        message.setError("");
        message.setCommandType(COMMAND_PERIOD_SUMMARY);
        message.setMessageType("PLAYER");
        message.setReply(reply);
        message.setProcessedAt(LocalDateTime.now());
        message.setUserId(userId);
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException ignored) {
            LOGGER.debug("期末成功订单汇总已存在 user={} period={} externalId={}", userId, period, externalId);
        }
    }

    private boolean exists(Long userId, String externalId) {
        Long existing = DataPermissionUtils.executeIgnore(() -> messageMapper.selectCount(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                        .eq(MessageDO::getExternalId, externalId)));
        return existing != null && existing > 0;
    }

    private String privateExternalId(String period, String memberId) {
        String stableMember = UUID.nameUUIDFromBytes(memberId.getBytes(StandardCharsets.UTF_8)).toString();
        return "period-summary:p:" + period + ":" + stableMember;
    }

    private record MemberSummary(String memberName, List<String> lines) {
    }
}
