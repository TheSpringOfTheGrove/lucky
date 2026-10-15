package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.PlayerFollowExecutionDO;
import com.hnz.luck5.module.lottery.dal.mysql.PlayerFollowExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded durable queue, not a per-player timer or full-room poll. */
@Component
@RequiredArgsConstructor
public class LotteryPlayerFollowScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryPlayerFollowScheduler.class);
    private final PlayerFollowExecutionMapper executionMapper;
    private final LotteryPlayerFollowWorker worker;
    private final LotteryPlayerFollowService followService;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    @Scheduled(initialDelayString = "${lottery.player-follow.initial-delay-ms:5000}",
            fixedDelayString = "${lottery.player-follow.poll-interval-ms:1000}")
    public void processDue() {
        if (!polling.compareAndSet(false, true)) return;
        try {
            List<PlayerFollowExecutionDO> tasks = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                    () -> executionMapper.selectList(new LambdaQueryWrapper<PlayerFollowExecutionDO>()
                            .eq(PlayerFollowExecutionDO::getStatus, "SCHEDULED")
                            .le(PlayerFollowExecutionDO::getNextAttemptAt, LocalDateTime.now())
                            .orderByAsc(PlayerFollowExecutionDO::getNextAttemptAt)
                            .orderByAsc(PlayerFollowExecutionDO::getCreateTime)
                            .orderByAsc(PlayerFollowExecutionDO::getId).last("LIMIT 20"))));
            for (PlayerFollowExecutionDO task : tasks) {
                try {
                    TenantUtils.execute(task.getTenantId(), () -> worker.execute(task.getId(), task.getUserId()));
                } catch (RuntimeException ex) {
                    LOGGER.warn("玩家跟单本期执行失败 tenant={} user={} follow={} period={}",
                            task.getTenantId(), task.getUserId(), task.getFollowId(), task.getPeriod());
                    try {
                        TenantUtils.execute(task.getTenantId(), () -> worker.fail(task.getId(), task.getUserId(), ex));
                    } catch (RuntimeException failure) {
                        LOGGER.error("玩家跟单失败状态保存异常 tenant={} user={} execution={}",
                                task.getTenantId(), task.getUserId(), task.getId(), failure);
                    }
                }
            }
        } finally {
            polling.set(false);
        }
    }

    /** Compensate an OPEN event lost to a process restart, without polling every member. */
    @Scheduled(initialDelayString = "${lottery.player-follow.reconcile-initial-delay-ms:15000}",
            fixedDelayString = "${lottery.player-follow.reconcile-interval-ms:30000}")
    public void reconcileOpenIssues() {
        List<Map<String, Object>> scopes = TenantUtils.executeIgnore(() -> DataPermissionUtils.executeIgnore(
                executionMapper::selectMissingOpenScopes));
        for (Map<String, Object> scope : scopes) {
            Long tenantId = ((Number) scope.get("tenantId")).longValue();
            Long userId = ((Number) scope.get("userId")).longValue();
            String period = (String) scope.get("period");
            try {
                TenantUtils.execute(tenantId, () -> followService.schedule(userId, period));
            } catch (RuntimeException ex) {
                LOGGER.error("玩家跟单开盘补偿失败 tenant={} user={} period={}", tenantId, userId, period, ex);
            }
        }
    }
}
