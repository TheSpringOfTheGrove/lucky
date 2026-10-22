package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Writes the cancellation as a new robot event without replacing the original bet receipt. */
final class LotteryCancelNoticePublisher {

    static final String COMMAND_TYPE = "CANCEL_SUCCESS";

    private LotteryCancelNoticePublisher() {
    }

    static void publish(MessageMapper messageMapper, LotteryRobotReplyTemplate template,
                        OrderDO order, MemberDO member, BigDecimal balance) {
        MessageDO original = DataPermissionUtils.executeIgnore(() -> messageMapper.selectOne(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, order.getUserId())
                        .eq(MessageDO::getOrderId, order.getId()).eq(MessageDO::getCommandType, "BET")
                        .orderByAsc(MessageDO::getId).last("LIMIT 1")));
        MessageDO notice = new MessageDO();
        notice.setUserId(order.getUserId());
        notice.setChannel(original != null ? original.getChannel()
                : "私聊".equals(order.getSource()) ? "网页私聊" : "网页群");
        notice.setMemberId(member.getId());
        notice.setMember(member.getName());
        notice.setPeriod(order.getPeriod());
        notice.setContent("");
        notice.setStatus("已处理");
        notice.setOrderId(order.getId());
        notice.setExternalId("cancel-success:" + order.getId());
        notice.setError("");
        notice.setCommandType(COMMAND_TYPE);
        notice.setMessageType(original != null && StrUtil.isNotBlank(original.getMessageType())
                ? original.getMessageType() : "AUTO_PROXY".equals(order.getOrderType()) ? "AUTO_PROXY" : "PLAYER");
        notice.setReply(template.cancelSuccessReceipt(member.getName(), order.getPeriod(),
                order.getPeriodSequence() == null ? 0 : order.getPeriodSequence(), order.getContent(),
                order.getItemCount() == null ? 0 : order.getItemCount(), order.getAmount(), balance));
        notice.setProcessedAt(LocalDateTime.now());
        // The unique (tenant, owner, external_id) key prevents a duplicate notice if a retry races.
        messageMapper.insert(notice);
    }
}
