package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.common.exception.ServiceException;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.SystemStateDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PlayerFollowDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PlayerFollowExecutionDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.LotteryConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.SystemStateMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PlayerFollowExecutionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PlayerFollowMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.MEMBER_BALANCE_NOT_ENOUGH;

@Service
@RequiredArgsConstructor
public class LotteryPlayerFollowWorker {

    private final PlayerFollowExecutionMapper executionMapper;
    private final PlayerFollowMapper followMapper;
    private final MemberMapper memberMapper;
    private final LotteryConfigMapper configMapper;
    private final SystemStateMapper stateMapper;
    private final IssueMapper issueMapper;
    private final OrderMapper orderMapper;
    private final MessageMapper messageMapper;
    private final LotteryService lotteryService;
    private final LotteryRobotReplyTemplate robotReplyTemplate;

    /** The task row lock and bet debit commit together. A crash rolls both back to SCHEDULED. */
    @Transactional(rollbackFor = Exception.class)
    public void execute(String taskId, Long userId) {
        PlayerFollowExecutionDO task = DataPermissionUtils.executeIgnore(() -> executionMapper.selectOne(
                new LambdaQueryWrapper<PlayerFollowExecutionDO>().eq(PlayerFollowExecutionDO::getId, taskId)
                        .eq(PlayerFollowExecutionDO::getUserId, userId).last("LIMIT 1 FOR UPDATE")));
        if (task == null || !"SCHEDULED".equals(task.getStatus())) return;
        PlayerFollowDO follow = DataPermissionUtils.executeIgnore(() -> followMapper.selectOne(
                new LambdaQueryWrapper<PlayerFollowDO>().eq(PlayerFollowDO::getId, task.getFollowId())
                        .eq(PlayerFollowDO::getUserId, userId).last("LIMIT 1 FOR UPDATE")));
        if (follow == null || !Boolean.TRUE.equals(follow.getEnabled())
                || !task.getMemberId().equals(follow.getMemberId())) {
            complete(task, "SKIPPED", null, "跟单已停止");
            return;
        }
        LotteryConfigDO config = DataPermissionUtils.executeIgnore(() -> configMapper.selectOne(
                new LambdaQueryWrapper<LotteryConfigDO>().eq(LotteryConfigDO::getUserId, userId).last("LIMIT 1")));
        if (config != null && !Boolean.TRUE.equals(config.getBossMode())) {
            // Match the normal real-market order's owner-state -> issue -> member lock order.
            DataPermissionUtils.executeIgnore(() -> stateMapper.selectOne(
                    new LambdaQueryWrapper<SystemStateDO>().eq(SystemStateDO::getUserId, userId)
                            .last("LIMIT 1 FOR UPDATE")));
        }
        IssueDO issue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                        .eq(IssueDO::getPeriod, task.getPeriod()).last("LIMIT 1 FOR UPDATE")));
        if (issue == null || !"OPEN".equals(issue.getStatus()) || issue.getOpenedAt() == null) {
            complete(task, "SKIPPED", null, "本期未正式开放");
            return;
        }
        IssueDO previousIssue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                        .lt(IssueDO::getPeriod, task.getPeriod()).isNotNull(IssueDO::getOpenedAt)
                        .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        if (previousIssue != null && !"SETTLED".equals(previousIssue.getStatus())) {
            // An already-open historical issue may predate the stricter opening gate. Never debit the player
            // before the previous formally opened issue has completed payout. Retry at a bounded cadence while
            // this issue remains open, so a late payout can still trigger the current issue's follow order.
            task.setError("上一期尚未派奖");
            task.setNextAttemptAt(LocalDateTime.now().plusSeconds(5));
            executionMapper.updateById(task);
            return;
        }
        MemberDO member = DataPermissionUtils.executeIgnore(() -> memberMapper.selectOne(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getId, task.getMemberId())
                        .eq(MemberDO::getUserId, userId).last("LIMIT 1 FOR UPDATE")));
        if (member == null || Boolean.TRUE.equals(member.getAutoProxy()) || "BOT".equals(member.getMemberType())) {
            complete(task, "SKIPPED", null, "玩家已不可用");
            return;
        }
        OrderDO source = DataPermissionUtils.executeIgnore(() -> orderMapper.selectOne(
                new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getId, follow.getSourceOrderId())
                        .eq(OrderDO::getUserId, userId).last("LIMIT 1")));
        if (source == null || "FAILED".equals(source.getMarketStatus())) {
            follow.setEnabled(false);
            follow.setStoppedAt(LocalDateTime.now());
            followMapper.updateById(follow);
            complete(task, "SKIPPED", null, "绑定的原下注未成功");
            addNotice(member, follow.getChannel(), task.getPeriod(),
                    "player-follow-source-failed:" + task.getId(),
                    robotReplyTemplate.followSourceFailed(member.getName()));
            return;
        }
        if ("PENDING".equals(source.getMarketStatus()) || "SUBMITTING".equals(source.getMarketStatus())
                || "RETRY".equals(source.getMarketStatus()) || "MANUAL_REVIEW".equals(source.getMarketStatus())) {
            complete(task, "SKIPPED", null, "原下注仍在确认");
            return;
        }
        Map<String, Object> result = lotteryService.placePlayerFollowBet(userId, member.getId(), task.getPeriod(),
                follow.getContent(), follow.getChannel(), follow.getId(), task.getId());
        complete(task, "SUCCESS", (String) result.get("orderId"), "");
    }

    /** Called after a failed placement transaction rolls back. One balance failure stops every active binding. */
    @Transactional(rollbackFor = Exception.class)
    public void fail(String taskId, Long userId, RuntimeException cause) {
        PlayerFollowExecutionDO task = DataPermissionUtils.executeIgnore(() -> executionMapper.selectOne(
                new LambdaQueryWrapper<PlayerFollowExecutionDO>().eq(PlayerFollowExecutionDO::getId, taskId)
                        .eq(PlayerFollowExecutionDO::getUserId, userId).last("LIMIT 1 FOR UPDATE")));
        if (task == null || !"SCHEDULED".equals(task.getStatus())) return;
        boolean balanceFailure = cause instanceof ServiceException ex
                && MEMBER_BALANCE_NOT_ENOUGH.getCode().equals(ex.getCode());
        complete(task, "FAILED", null, balanceFailure ? "余额不足" : "本期下注未完成");
        if (!balanceFailure) return;

        PlayerFollowDO current = DataPermissionUtils.executeIgnore(() -> followMapper.selectOne(
                new LambdaQueryWrapper<PlayerFollowDO>().eq(PlayerFollowDO::getId, task.getFollowId())
                        .eq(PlayerFollowDO::getUserId, userId).last("LIMIT 1")));
        if (current == null || !Boolean.TRUE.equals(current.getEnabled())) return;
        List<PlayerFollowDO> active = DataPermissionUtils.executeIgnore(() -> followMapper.selectList(
                new LambdaQueryWrapper<PlayerFollowDO>().eq(PlayerFollowDO::getUserId, userId)
                        .eq(PlayerFollowDO::getMemberId, task.getMemberId())
                        .eq(PlayerFollowDO::getChannel, current.getChannel())
                        .eq(PlayerFollowDO::getEnabled, true).orderByAsc(PlayerFollowDO::getId)));
        if (active.isEmpty()) return;
        for (PlayerFollowDO follow : active) {
            DataPermissionUtils.executeIgnore(() -> followMapper.update(null,
                    new LambdaUpdateWrapper<PlayerFollowDO>().eq(PlayerFollowDO::getId, follow.getId())
                            .eq(PlayerFollowDO::getUserId, userId).eq(PlayerFollowDO::getEnabled, true)
                            .set(PlayerFollowDO::getEnabled, false)
                            .set(PlayerFollowDO::getStoppedAt, LocalDateTime.now())));
        }
        MemberDO member = DataPermissionUtils.executeIgnore(() -> memberMapper.selectOne(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getId, task.getMemberId())
                        .eq(MemberDO::getUserId, userId).last("LIMIT 1")));
        if (member == null) return;
        addNotice(member, current.getChannel(), task.getPeriod(),
                "player-follow-stopped:" + task.getId(),
                robotReplyTemplate.followBalanceStopped(member.getName()));
    }

    private void addNotice(MemberDO member, String channel, String period, String externalId, String reply) {
        MessageDO notice = new MessageDO();
        notice.setUserId(member.getUserId());
        notice.setMemberId(member.getId());
        notice.setMember(member.getName());
        notice.setChannel(channel);
        notice.setPeriod(period);
        notice.setContent("");
        notice.setStatus("已处理");
        notice.setExternalId(externalId);
        notice.setError("");
        notice.setCommandType("FOLLOW_STOPPED");
        notice.setMessageType("PLAYER");
        notice.setReply(reply);
        notice.setProcessedAt(LocalDateTime.now());
        messageMapper.insert(notice);
    }

    private void complete(PlayerFollowExecutionDO task, String status, String orderId, String error) {
        task.setStatus(status);
        task.setOrderId(orderId);
        task.setError(StrUtil.sub(error, 0, 1000));
        task.setCompletedAt(LocalDateTime.now());
        executionMapper.updateById(task);
    }
}
