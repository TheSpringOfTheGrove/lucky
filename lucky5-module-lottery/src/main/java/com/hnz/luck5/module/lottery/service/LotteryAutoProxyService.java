package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PresetOrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PresetOrderMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LotteryAutoProxyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryAutoProxyService.class);

    private final PresetOrderMapper presetOrderMapper;
    private final MemberMapper memberMapper;
    private final MessageMapper messageMapper;
    private final LotteryService lotteryService;

    public RunResult run(Long userId, String period) {
        String normalizedPeriod = StrUtil.trim(period);
        if (!normalizedPeriod.matches("\\d+")) {
            return new RunResult(normalizedPeriod, 0, 0);
        }
        List<PresetOrderDO> presets = DataPermissionUtils.executeIgnore(() -> presetOrderMapper.selectList(
                new LambdaQueryWrapper<PresetOrderDO>().eq(PresetOrderDO::getUserId, userId)
                        .eq(PresetOrderDO::getEnabled, true).orderByAsc(PresetOrderDO::getCreateTime)
                        .orderByAsc(PresetOrderDO::getId))).stream().filter(item -> StrUtil.isNotBlank(item.getContent())).toList();
        List<MemberDO> members = DataPermissionUtils.executeIgnore(() -> memberMapper.selectList(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getUserId, userId)
                        .eq(MemberDO::getAutoProxy, true).orderByAsc(MemberDO::getId)));
        if (presets.isEmpty() || members.isEmpty()) {
            return new RunResult(normalizedPeriod, members.size(), 0);
        }
        int placed = 0;
        for (MemberDO member : members) {
            String legacyExternalId = "auto-proxy:" + member.getId() + ":" + normalizedPeriod;
            String baseExternalId = "auto-proxy:" + memberKey(member.getId()) + ":" + normalizedPeriod;
            if (hasCompletedOrder(userId, baseExternalId, legacyExternalId)) {
                continue;
            }
            List<PresetOrderDO> candidates = rotate(presets, Math.floorMod(Objects.hash(member.getId(), normalizedPeriod),
                    presets.size()));
            for (PresetOrderDO preset : candidates) {
                LotteryReqVO.PlaceBet bet = new LotteryReqVO.PlaceBet();
                bet.setMemberId(member.getId());
                bet.setPeriod(normalizedPeriod);
                bet.setContent(preset.getContent().trim());
                bet.setChannel("网页群");
                bet.setExternalId(baseExternalId);
                try {
                    Map<String, Object> result = lotteryService.placeAutoBet(userId, bet, "自动托:" + member.getName());
                    if (!Boolean.TRUE.equals(result.get("duplicate"))) {
                        placed++;
                    }
                    break;
                } catch (RuntimeException ex) {
                    try {
                        saveFailure(userId, member, normalizedPeriod, preset, ex);
                    } catch (RuntimeException logException) {
                        LOGGER.error("自动托失败消息保存异常 user={} member={} period={} preset={}", userId,
                                member.getId(), normalizedPeriod, preset.getId(), logException);
                    }
                    LOGGER.warn("自动托模板失败 user={} member={} period={} preset={}: {}", userId, member.getId(),
                            normalizedPeriod, preset.getId(), rootMessage(ex));
                }
            }
        }
        return new RunResult(normalizedPeriod, members.size(), placed);
    }

    private boolean hasCompletedOrder(Long userId, String baseExternalId, String legacyExternalId) {
        MessageDO completed = DataPermissionUtils.executeIgnore(() -> messageMapper.selectOne(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, userId).isNotNull(MessageDO::getOrderId)
                        .and(query -> query.eq(MessageDO::getExternalId, baseExternalId)
                                .or().eq(MessageDO::getExternalId, legacyExternalId)
                                .or().likeRight(MessageDO::getExternalId, legacyExternalId + ":"))
                        .last("LIMIT 1")));
        return completed != null;
    }

    private String memberKey(String memberId) {
        return UUID.nameUUIDFromBytes(memberId.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private List<PresetOrderDO> rotate(List<PresetOrderDO> values, int start) {
        List<PresetOrderDO> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            result.add(values.get((start + index) % values.size()));
        }
        return result;
    }

    private void saveFailure(Long userId, MemberDO member, String period, PresetOrderDO preset, RuntimeException ex) {
        MessageDO message = new MessageDO();
        message.setChannel("网页群");
        message.setMember(member.getName());
        message.setPeriod(period);
        message.setContent(preset.getContent());
        message.setStatus("失败");
        message.setExternalId("auto-proxy-failure:" + period + ":" + IdUtil.fastSimpleUUID());
        String error = rootMessage(ex);
        message.setError(error.length() <= 1000 ? error : error.substring(0, 1000));
        message.setCommandType("AUTO_PROXY");
        message.setReply("自动托模板失败，已尝试下一条预设订单");
        message.setProcessedAt(LocalDateTime.now());
        message.setUserId(userId);
        DataPermissionUtils.executeIgnore(() -> messageMapper.insert(message));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }

    public record RunResult(String period, int attempted, int placed) {
    }

}
