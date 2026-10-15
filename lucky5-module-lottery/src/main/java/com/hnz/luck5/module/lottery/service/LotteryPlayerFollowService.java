package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PlayerFollowDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PlayerFollowExecutionDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PlayerFollowExecutionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PlayerFollowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Fixed real-player instructions. This is deliberately unrelated to admin follow orders and virtual bots. */
@Service
@RequiredArgsConstructor
public class LotteryPlayerFollowService {

    private static final String WEB_GROUP = "网页群";
    private static final String WEB_PRIVATE = "网页私聊";

    private final PlayerFollowMapper followMapper;
    private final PlayerFollowExecutionMapper executionMapper;
    private final OrderMapper orderMapper;
    private final IssueMapper issueMapper;

    public BindResult bind(MemberDO member, String channel) {
        if (!Set.of(WEB_GROUP, WEB_PRIVATE).contains(channel) || Boolean.TRUE.equals(member.getAutoProxy())
                || "BOT".equals(member.getMemberType())) {
            return new BindResult(false, false, "仅真实房间玩家可以使用跟单");
        }
        String orderSource = WEB_PRIVATE.equals(channel) ? "私聊" : channel;
        OrderDO source = DataPermissionUtils.executeIgnore(() -> orderMapper.selectOne(
                new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getUserId, member.getUserId())
                        .eq(OrderDO::getMemberId, member.getId()).eq(OrderDO::getSource, orderSource)
                        .isNull(OrderDO::getFollowId)
                        .in(OrderDO::getStatus, "未开奖", "已中奖", "未中奖")
                        .and(query -> query.isNull(OrderDO::getOrderType)
                                .or().ne(OrderDO::getOrderType, "AUTO_PROXY"))
                        .orderByDesc(OrderDO::getCreateTime).orderByDesc(OrderDO::getId).last("LIMIT 1 FOR UPDATE")));
        if (source == null) return new BindResult(false, false, "当前房间没有可跟的成功下注");
        // A locally accepted real-market order can still be awaiting the external confirmation when the
        // player immediately sends 跟. The worker checks a later definitive failure before using this source.
        PlayerFollowDO existing = DataPermissionUtils.executeIgnore(() -> followMapper.selectOne(
                new LambdaQueryWrapper<PlayerFollowDO>().eq(PlayerFollowDO::getUserId, member.getUserId())
                        .eq(PlayerFollowDO::getMemberId, member.getId()).eq(PlayerFollowDO::getChannel, channel)
                        .eq(PlayerFollowDO::getSourceOrderId, source.getId()).eq(PlayerFollowDO::getEnabled, true)
                        .last("LIMIT 1")));
        if (existing != null) return new BindResult(true, true, "这笔指令已经开启跟单");

        PlayerFollowDO follow = new PlayerFollowDO();
        follow.setId(IdUtil.fastSimpleUUID());
        follow.setUserId(member.getUserId());
        follow.setMemberId(member.getId());
        follow.setChannel(channel);
        follow.setSourceOrderId(source.getId());
        follow.setSourcePeriod(source.getPeriod());
        follow.setContent(source.getContent());
        follow.setEnabled(true);
        followMapper.insert(follow);

        IssueDO current = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, member.getUserId())
                        .eq(IssueDO::getStatus, "OPEN").isNotNull(IssueDO::getOpenedAt)
                        .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        boolean currentAlreadyPlaced = current != null && Objects.equals(current.getPeriod(), source.getPeriod());
        if (current != null && laterThan(current.getPeriod(), source.getPeriod())) {
            scheduleOne(follow, current.getPeriod());
        }
        return new BindResult(true, currentAlreadyPlaced,
                currentAlreadyPlaced ? "当前期号已下注，本期不自动跟单，下期自动跟" : "跟单已开启");
    }

    public int stop(MemberDO member) {
        return DataPermissionUtils.executeIgnore(() -> followMapper.update(null,
                new LambdaUpdateWrapper<PlayerFollowDO>().eq(PlayerFollowDO::getUserId, member.getUserId())
                        .eq(PlayerFollowDO::getMemberId, member.getId())
                        .eq(PlayerFollowDO::getEnabled, true).set(PlayerFollowDO::getEnabled, false)
                        .set(PlayerFollowDO::getStoppedAt, LocalDateTime.now())));
    }

    public void schedule(Long userId, String period) {
        if (!numericPeriod(period)) return;
        List<PlayerFollowDO> active = DataPermissionUtils.executeIgnore(() -> followMapper.selectList(
                new LambdaQueryWrapper<PlayerFollowDO>().eq(PlayerFollowDO::getUserId, userId)
                        .eq(PlayerFollowDO::getEnabled, true).orderByAsc(PlayerFollowDO::getId)));
        for (PlayerFollowDO follow : active) {
            if (laterThan(period, follow.getSourcePeriod())) scheduleOne(follow, period);
        }
    }

    private void scheduleOne(PlayerFollowDO follow, String period) {
        PlayerFollowExecutionDO task = new PlayerFollowExecutionDO();
        task.setId(IdUtil.fastSimpleUUID());
        task.setUserId(follow.getUserId());
        task.setFollowId(follow.getId());
        task.setMemberId(follow.getMemberId());
        task.setPeriod(period);
        task.setStatus("SCHEDULED");
        task.setError("");
        task.setNextAttemptAt(LocalDateTime.now());
        try {
            executionMapper.insert(task);
        } catch (DuplicateKeyException ignored) {
            // Replayed OPEN event or simultaneous bind/OPEN: exactly one execution per fixed instruction and period.
        }
    }

    private boolean laterThan(String period, String sourcePeriod) {
        return numericPeriod(period) && numericPeriod(sourcePeriod)
                && new BigInteger(period).compareTo(new BigInteger(sourcePeriod)) > 0;
    }

    private boolean numericPeriod(String period) {
        return StrUtil.isNotBlank(period) && period.matches("\\d+");
    }

    public record BindResult(boolean bound, boolean alreadyPlaced, String text) {
    }
}
