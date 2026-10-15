package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.AutoProxyExecutionDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PresetOrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.AutoProxyExecutionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PresetOrderMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class LotteryAutoProxyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryAutoProxyService.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final String SCHEDULED = "SCHEDULED";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String SKIPPED = "SKIPPED";

    private final PresetOrderMapper presetOrderMapper;
    private final MemberMapper memberMapper;
    private final MessageMapper messageMapper;
    private final AutoProxyExecutionMapper executionMapper;
    private final IssueMapper issueMapper;
    private final LotteryService lotteryService;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    /**
     * Creates durable per-member tasks. The unique database key makes repeated OPEN events harmless.
     */
    public RunResult run(Long userId, String period) {
        if (!previousOpenedIssueSettled(userId, period)) {
            return new RunResult(StrUtil.trim(period), 0, 0);
        }
        return schedule(userId, period, false);
    }

    /** Also handles an issue that was manually marked OPEN before the preceding payout committed. */
    public RunResult runAfterSettlement(Long userId, String settledPeriod) {
        IssueDO open = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                        .gt(IssueDO::getPeriod, settledPeriod).eq(IssueDO::getStatus, "OPEN")
                        .isNotNull(IssueDO::getOpenedAt).orderByAsc(IssueDO::getPeriod).last("LIMIT 1")));
        return open == null ? new RunResult(settledPeriod, 0, 0) : run(userId, open.getPeriod());
    }

    /** Starts this already-open period immediately when the owner turns the room on. */
    public RunResult runNow(Long userId, String period) {
        if (!previousOpenedIssueSettled(userId, period)) {
            return new RunResult(StrUtil.trim(period), 0, 0);
        }
        return schedule(userId, period, true);
    }

    private boolean previousOpenedIssueSettled(Long userId, String period) {
        String normalized = StrUtil.trim(period);
        if (normalized == null || !normalized.matches("\\d+")) return false;
        IssueDO previous = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                        .lt(IssueDO::getPeriod, normalized).isNotNull(IssueDO::getOpenedAt)
                        .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        // A brand-new owner has no preceding issue to settle; the first OPEN is the initial cycle.
        return previous == null || "SETTLED".equals(previous.getStatus());
    }

    private RunResult schedule(Long userId, String period, boolean immediate) {
        String normalizedPeriod = StrUtil.trim(period);
        if (!normalizedPeriod.matches("\\d+")) {
            return new RunResult(normalizedPeriod, 0, 0);
        }
        List<MemberDO> members = DataPermissionUtils.executeIgnore(() -> memberMapper.selectList(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getUserId, userId)
                        .and(query -> query.eq(MemberDO::getMemberType, "BOT").or().eq(MemberDO::getAutoProxy, true))
                        .eq(MemberDO::getAutoBetEnabled, true).orderByAsc(MemberDO::getId)));
        int scheduled = 0;
        String immediateTaskId = null;
        LocalDateTime nextScheduledAt = LocalDateTime.now();
        boolean firstTask = true;
        for (MemberDO member : members) {
            if (hasCompletedOrder(userId, member.getId(), normalizedPeriod)) {
                continue;
            }
            boolean firstSlot = firstTask;
            // The first room-start bet is immediate. Every subsequent bot gets its own cumulative
            // 5–30 second slot; independent random delays would bunch many bots together.
            if (!immediate || !firstTask) {
                nextScheduledAt = nextScheduledAt.plusSeconds(ThreadLocalRandom.current().nextLong(5, 31));
            }
            LocalDateTime scheduledAt = nextScheduledAt;
            firstTask = false;
            AutoProxyExecutionDO task = new AutoProxyExecutionDO();
            task.setId(IdUtil.fastSimpleUUID());
            task.setUserId(userId);
            task.setMemberId(member.getId());
            task.setMemberName(member.getName());
            task.setPeriod(normalizedPeriod);
            task.setStatus(SCHEDULED);
            task.setContent("");
            task.setRequiredAmount(ZERO);
            task.setTopUpAmount(ZERO);
            task.setError("");
            task.setAttemptCount(0);
            task.setVersion(0);
            task.setScheduledAt(scheduledAt);
            try {
                executionMapper.insert(task);
                scheduled++;
                if (immediate && firstSlot) immediateTaskId = task.getId();
            } catch (DuplicateKeyException ignored) {
                // A period OPEN event may have queued (or failed) this task while the room was still off.
                // Move only unfinished tasks forward; RUNNING/SUCCESS and completed orders stay untouched.
                if (immediate) {
                    int rescheduled = DataPermissionUtils.executeIgnore(() -> executionMapper.update(null,
                            new LambdaUpdateWrapper<AutoProxyExecutionDO>()
                                    .eq(AutoProxyExecutionDO::getUserId, userId)
                                    .eq(AutoProxyExecutionDO::getMemberId, member.getId())
                                    .eq(AutoProxyExecutionDO::getPeriod, normalizedPeriod)
                                    .in(AutoProxyExecutionDO::getStatus, SCHEDULED, FAILED, SKIPPED)
                                    .set(AutoProxyExecutionDO::getStatus, SCHEDULED)
                                    .set(AutoProxyExecutionDO::getScheduledAt, scheduledAt)
                                    .set(AutoProxyExecutionDO::getCompletedAt, null)
                                    .set(AutoProxyExecutionDO::getError, "")));
                    scheduled += rescheduled;
                    if (rescheduled > 0 && firstSlot) {
                        AutoProxyExecutionDO existing = DataPermissionUtils.executeIgnore(() ->
                                executionMapper.selectOne(new LambdaQueryWrapper<AutoProxyExecutionDO>()
                                        .eq(AutoProxyExecutionDO::getUserId, userId)
                                        .eq(AutoProxyExecutionDO::getMemberId, member.getId())
                                        .eq(AutoProxyExecutionDO::getPeriod, normalizedPeriod).last("LIMIT 1")));
                        if (existing != null) immediateTaskId = existing.getId();
                    }
                }
            }
        }
        // Run only the first bot now; the durable poller handles later slots without blocking room start.
        if (immediateTaskId != null) {
            try {
                execute(immediateTaskId, userId);
            } catch (RuntimeException ex) {
                LOGGER.error("当期自动托立即执行失败 user={} task={} period={}", userId, immediateTaskId,
                        normalizedPeriod, ex);
            }
        }
        return new RunResult(normalizedPeriod, members.size(), scheduled);
    }

    @Scheduled(initialDelayString = "${lottery.auto-proxy.initial-delay-ms:5000}",
            fixedDelayString = "${lottery.auto-proxy.poll-interval-ms:1000}")
    public void processDueExecutions() {
        if (!polling.compareAndSet(false, true)) return;
        try {
            recoverStaleExecutions();
            List<AutoProxyExecutionDO> due = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    () -> executionMapper.selectList(new LambdaQueryWrapper<AutoProxyExecutionDO>()
                            .eq(AutoProxyExecutionDO::getStatus, SCHEDULED)
                            .le(AutoProxyExecutionDO::getScheduledAt, LocalDateTime.now())
                            .orderByAsc(AutoProxyExecutionDO::getScheduledAt).last("LIMIT 100"))));
            for (AutoProxyExecutionDO task : due) {
                try {
                    TenantUtils.execute(task.getTenantId(), () -> execute(task.getId(), task.getUserId()));
                } catch (RuntimeException ex) {
                    LOGGER.error("自动托任务执行异常 tenant={} user={} member={} period={}", task.getTenantId(),
                            task.getUserId(), task.getMemberId(), task.getPeriod(), ex);
                }
            }
        } finally {
            polling.set(false);
        }
    }

    private void recoverStaleExecutions() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(2);
        TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(() -> executionMapper.update(null,
                new LambdaUpdateWrapper<AutoProxyExecutionDO>().eq(AutoProxyExecutionDO::getStatus, RUNNING)
                        .le(AutoProxyExecutionDO::getUpdateTime, staleBefore)
                        .set(AutoProxyExecutionDO::getStatus, SCHEDULED)
                        .set(AutoProxyExecutionDO::getScheduledAt, LocalDateTime.now())
                        .set(AutoProxyExecutionDO::getError, "服务恢复后重新执行"))));
    }

    void execute(String taskId, Long userId) {
        AutoProxyExecutionDO task = DataPermissionUtils.executeIgnore(() -> executionMapper.selectOne(
                new LambdaQueryWrapper<AutoProxyExecutionDO>().eq(AutoProxyExecutionDO::getId, taskId)
                        .eq(AutoProxyExecutionDO::getUserId, userId).last("LIMIT 1")));
        if (task == null || !SCHEDULED.equals(task.getStatus())) return;
        if (deferForMemberSpacing(task, userId)) return;
        int version = value(task.getVersion(), 0);
        int claimed = DataPermissionUtils.executeIgnore(() -> executionMapper.update(null,
                new LambdaUpdateWrapper<AutoProxyExecutionDO>().eq(AutoProxyExecutionDO::getId, taskId)
                        .eq(AutoProxyExecutionDO::getUserId, userId).eq(AutoProxyExecutionDO::getStatus, SCHEDULED)
                        .eq(AutoProxyExecutionDO::getVersion, version)
                        .set(AutoProxyExecutionDO::getStatus, RUNNING)
                        .set(AutoProxyExecutionDO::getStartedAt, LocalDateTime.now())
                        .set(AutoProxyExecutionDO::getAttemptCount, value(task.getAttemptCount(), 0) + 1)
                        .set(AutoProxyExecutionDO::getVersion, version + 1)));
        if (claimed != 1) return;

        MemberDO member = DataPermissionUtils.executeIgnore(() -> memberMapper.selectOne(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getUserId, userId)
                        .eq(MemberDO::getId, task.getMemberId()).last("LIMIT 1")));
        if (member == null || !isEnabledBot(member)) {
            finish(taskId, userId, SKIPPED, null, null, ZERO, ZERO, "自动托已停用");
            return;
        }
        if (hasCompletedOrder(userId, member.getId(), task.getPeriod())) {
            finish(taskId, userId, SUCCESS, null, null, ZERO, ZERO, "本期已有自动托订单");
            return;
        }
        List<PresetOrderDO> presets = DataPermissionUtils.executeIgnore(() -> presetOrderMapper.selectList(
                new LambdaQueryWrapper<PresetOrderDO>().eq(PresetOrderDO::getUserId, userId)
                        .orderByAsc(PresetOrderDO::getCreateTime)
                        .orderByAsc(PresetOrderDO::getId))).stream()
                .filter(item -> StrUtil.isNotBlank(item.getContent())).toList();
        if (presets.isEmpty()) {
            finish(taskId, userId, SKIPPED, null, null, ZERO, ZERO, "没有可用的预设订单");
            return;
        }

        List<PresetOrderDO> candidates = new ArrayList<>(presets);
        Collections.shuffle(candidates, ThreadLocalRandom.current());
        String lastError = "没有可用的预设订单";
        for (PresetOrderDO preset : candidates) {
            Map<String, Object> preparation;
            try {
                preparation = lotteryService.prepareAutoProxyBet(userId, member.getId(),
                        preset.getContent().trim());
            } catch (RuntimeException ex) {
                lastError = rootMessage(ex);
                LOGGER.warn("自动托忽略不可用预设 user={} member={} period={} preset={}: {}", userId,
                        member.getId(), task.getPeriod(), preset.getId(), lastError);
                continue;
            }

            BigDecimal required = money((BigDecimal) preparation.get("requiredAmount"));
            BigDecimal balance = money((BigDecimal) preparation.get("balance"));
            BigDecimal appliedTopUp = ZERO;
            try {
                if (balance.compareTo(required) < 0) {
                    BigDecimal configured = money(value(member.getAutoTopUpAmount(), new BigDecimal("1000")));
                    BigDecimal topUp = configured.max(required.subtract(balance));
                    Map<String, Object> topUpResult = lotteryService.autoTopUpProxy(userId, member.getId(),
                            task.getPeriod(), topUp);
                    if (!Boolean.TRUE.equals(topUpResult.get("duplicate"))) {
                        appliedTopUp = money(appliedTopUp.add(topUp));
                    }
                }
                LotteryReqVO.PlaceBet bet = new LotteryReqVO.PlaceBet();
                bet.setMemberId(member.getId());
                bet.setPeriod(task.getPeriod());
                bet.setContent(preset.getContent().trim());
                bet.setChannel("网页群");
                bet.setExternalId(externalId(member.getId(), task.getPeriod()));
                Map<String, Object> result = lotteryService.placeAutoBet(userId, bet, "自动托:" + member.getName());
                finish(taskId, userId, SUCCESS, preset, String.valueOf(result.get("orderId")), required,
                        appliedTopUp, "");
                return;
            } catch (RuntimeException ex) {
                lastError = rootMessage(ex);
                LOGGER.warn("自动托随机预设下注失败 user={} member={} period={} preset={}: {}", userId, member.getId(),
                        task.getPeriod(), preset.getId(), lastError);
                finish(taskId, userId, FAILED, preset, null, required, appliedTopUp, lastError);
                return;
            }
        }
        finish(taskId, userId, SKIPPED, null, null, ZERO, ZERO, lastError);
    }

    /** Keep the actual starts apart even when a poller was delayed or multiple due slots accumulated. */
    private boolean deferForMemberSpacing(AutoProxyExecutionDO task, Long userId) {
        if (task.getScheduledAt() == null) return false;
        List<AutoProxyExecutionDO> blocking = DataPermissionUtils.executeIgnore(() -> executionMapper.selectList(
                new LambdaQueryWrapper<AutoProxyExecutionDO>().eq(AutoProxyExecutionDO::getUserId, userId)
                        .eq(AutoProxyExecutionDO::getPeriod, task.getPeriod())
                        .ne(AutoProxyExecutionDO::getId, task.getId())
                        .and(query -> query.eq(AutoProxyExecutionDO::getStatus, RUNNING)
                                .or(nested -> nested.eq(AutoProxyExecutionDO::getStatus, SCHEDULED)
                                        .lt(AutoProxyExecutionDO::getScheduledAt, task.getScheduledAt())))
                        .last("LIMIT 1")));
        if (blocking != null && !blocking.isEmpty()) return true;
        List<AutoProxyExecutionDO> completed = DataPermissionUtils.executeIgnore(() -> executionMapper.selectList(
                new LambdaQueryWrapper<AutoProxyExecutionDO>().eq(AutoProxyExecutionDO::getUserId, userId)
                        .eq(AutoProxyExecutionDO::getPeriod, task.getPeriod())
                        .ne(AutoProxyExecutionDO::getId, task.getId())
                        .isNotNull(AutoProxyExecutionDO::getCompletedAt)
                        .orderByDesc(AutoProxyExecutionDO::getCompletedAt).last("LIMIT 1")));
        if (completed == null || completed.isEmpty()) return false;
        LocalDateTime lastCompletedAt = completed.get(0).getCompletedAt();
        if (lastCompletedAt == null || !LocalDateTime.now().isBefore(lastCompletedAt.plusSeconds(5))) return false;
        LocalDateTime nextSlot = lastCompletedAt.plusSeconds(ThreadLocalRandom.current().nextLong(5, 31));
        int version = value(task.getVersion(), 0);
        DataPermissionUtils.executeIgnore(() -> executionMapper.update(null,
                new LambdaUpdateWrapper<AutoProxyExecutionDO>().eq(AutoProxyExecutionDO::getId, task.getId())
                        .eq(AutoProxyExecutionDO::getUserId, userId)
                        .eq(AutoProxyExecutionDO::getStatus, SCHEDULED)
                        .eq(AutoProxyExecutionDO::getVersion, version)
                        .set(AutoProxyExecutionDO::getScheduledAt, nextSlot)
                        .set(AutoProxyExecutionDO::getVersion, version + 1)));
        return true;
    }

    private void finish(String taskId, Long userId, String status, PresetOrderDO preset, String orderId,
                        BigDecimal required, BigDecimal topUp, String error) {
        DataPermissionUtils.executeIgnore(() -> executionMapper.update(null,
                new LambdaUpdateWrapper<AutoProxyExecutionDO>().eq(AutoProxyExecutionDO::getId, taskId)
                        .eq(AutoProxyExecutionDO::getUserId, userId).eq(AutoProxyExecutionDO::getStatus, RUNNING)
                        .set(AutoProxyExecutionDO::getStatus, status)
                        .set(AutoProxyExecutionDO::getPresetOrderId, preset == null ? null : preset.getId())
                        .set(AutoProxyExecutionDO::getContent, preset == null ? "" : preset.getContent())
                        .set(AutoProxyExecutionDO::getRequiredAmount, money(required))
                        .set(AutoProxyExecutionDO::getTopUpAmount, money(topUp))
                        .set(AutoProxyExecutionDO::getOrderId, orderId)
                        .set(AutoProxyExecutionDO::getError, truncate(error))
                        .set(AutoProxyExecutionDO::getCompletedAt, LocalDateTime.now())));
    }

    private boolean hasCompletedOrder(Long userId, String memberId, String period) {
        String current = externalId(memberId, period);
        String legacy = "auto-proxy:" + memberId + ":" + period;
        MessageDO completed = DataPermissionUtils.executeIgnore(() -> messageMapper.selectOne(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, userId).isNotNull(MessageDO::getOrderId)
                        .and(query -> query.eq(MessageDO::getExternalId, current)
                                .or().eq(MessageDO::getExternalId, legacy)
                                .or().likeRight(MessageDO::getExternalId, legacy + ":"))
                        .last("LIMIT 1")));
        return completed != null;
    }

    private String externalId(String memberId, String period) {
        String memberKey = UUID.nameUUIDFromBytes(memberId.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return "auto-proxy:" + memberKey + ":" + period;
    }

    private boolean isEnabledBot(MemberDO member) {
        return ("BOT".equalsIgnoreCase(member.getMemberType()) || Boolean.TRUE.equals(member.getAutoProxy()))
                && Boolean.TRUE.equals(member.getAutoBetEnabled());
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String truncate(String value) {
        String normalized = StrUtil.blankToDefault(value, "");
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }

    private <T> T value(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    public record RunResult(String period, int attempted, int scheduled) {
    }

}
