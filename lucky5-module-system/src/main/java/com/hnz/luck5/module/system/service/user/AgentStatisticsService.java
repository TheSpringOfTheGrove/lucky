package com.hnz.luck5.module.system.service.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnz.luck5.framework.common.pojo.PageParam;
import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.framework.security.core.util.SecurityFrameworkUtils;
import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.module.system.dal.dataobject.user.AdminUserDO;
import com.hnz.luck5.module.system.dal.dataobject.user.GoldLedgerDO;
import com.hnz.luck5.module.system.dal.mysql.user.AdminUserMapper;
import com.hnz.luck5.module.system.dal.mysql.user.GoldLedgerMapper;
import com.hnz.luck5.module.system.service.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.system.enums.ErrorCodeConstants.USER_GOLD_ACCESS_DENIED;

/** 代理统计只读取当前租户的后台账号和不可变金币流水。 */
@Service
@RequiredArgsConstructor
public class AgentStatisticsService {

    private static final String RECHARGE = "ADMIN_GRANT";
    private static final String CONSUMPTION = "OWNER_ALLOCATION";
    private static final String AGENT_ROLE_EXISTS = "SELECT 1 FROM system_user_role ur " +
            "JOIN system_role r ON r.id = ur.role_id AND r.deleted = FALSE AND r.status = 0 " +
            "WHERE ur.user_id = system_users.id AND ur.deleted = FALSE " +
            "AND r.tenant_id = system_users.tenant_id AND r.code = 'lottery_agent'";
    private static final String OWNER_ROLE_EXISTS = "SELECT 1 FROM system_user_role ur " +
            "JOIN system_role r ON r.id = ur.role_id AND r.deleted = FALSE AND r.status = 0 " +
            "WHERE ur.user_id = system_users.id AND ur.deleted = FALSE " +
            "AND r.tenant_id = system_users.tenant_id AND r.code IN ('crm_admin', 'tenant_admin')";

    private final AdminUserMapper userMapper;
    private final GoldLedgerMapper ledgerMapper;
    private final PermissionService permissionService;

    public Summary summary() {
        Scope scope = scope();
        Long ownerCount = userMapper.selectCount(new LambdaQueryWrapper<AdminUserDO>()
                .exists(OWNER_ROLE_EXISTS)
                .eq(!scope.admin(), AdminUserDO::getParentAgentUserId, scope.userId()));
        Long consumed = ledgerMapper.sumAmount(TenantContextHolder.getRequiredTenantId(), CONSUMPTION,
                scope.admin() ? null : scope.userId());
        if (!scope.admin()) {
            AdminUserDO agent = userMapper.selectById(scope.userId());
            return new Summary(false, agent == null || agent.getGoldBalance() == null ? 0L : agent.getGoldBalance(),
                    null, ownerCount, null, consumed);
        }
        Long agentCount = userMapper.selectCount(new LambdaQueryWrapper<AdminUserDO>()
                .exists(AGENT_ROLE_EXISTS));
        Long recharged = ledgerMapper.sumAdminRecharge(TenantContextHolder.getRequiredTenantId());
        return new Summary(true, null, agentCount, ownerCount, recharged, consumed);
    }

    public PageResult<AgentRow> agents(PageParam page) {
        Scope scope = scope();
        if (!scope.admin()) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
        PageResult<AdminUserDO> result = userMapper.selectPage(page, new LambdaQueryWrapper<AdminUserDO>()
                .exists(AGENT_ROLE_EXISTS).orderByDesc(AdminUserDO::getId));
        return new PageResult<>(result.getList().stream().map(user ->
                new AgentRow(user.getId(), user.getUsername(), user.getNickname(),
                        Objects.requireNonNullElse(user.getGoldBalance(), 0L), user.getCreateTime()))
                .toList(), result.getTotal());
    }

    public PageResult<LedgerRow> ledger(PageParam page, String type) {
        Scope scope = scope();
        String operationType = switch (type) {
            case "RECHARGE" -> RECHARGE;
            case "CONSUMPTION" -> CONSUMPTION;
            default -> throw exception(USER_GOLD_ACCESS_DENIED);
        };
        if (!scope.admin() && RECHARGE.equals(operationType)) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
        LambdaQueryWrapper<GoldLedgerDO> query = new LambdaQueryWrapper<>();
        if (RECHARGE.equals(operationType)) {
            query.and(w -> w.eq(GoldLedgerDO::getOperationType, RECHARGE)
                    .or().eq(GoldLedgerDO::getOperationType, CONSUMPTION)
                    .isNull(GoldLedgerDO::getSourceUserId));
        } else {
            query.eq(GoldLedgerDO::getOperationType, operationType)
                    .eq(!scope.admin(), GoldLedgerDO::getSourceUserId, scope.userId());
        }
        PageResult<GoldLedgerDO> result = ledgerMapper.selectPage(page, query
                .orderByDesc(GoldLedgerDO::getCreateTime, GoldLedgerDO::getId));
        List<Long> userIds = result.getList().stream()
                .flatMap(item -> java.util.stream.Stream.of(item.getSourceUserId(), item.getTargetUserId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, AdminUserDO> users = userIds.isEmpty() ? Map.of() : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(AdminUserDO::getId, Function.identity()));
        return new PageResult<>(result.getList().stream().map(item -> new LedgerRow(
                item.getId(), item.getAmount(), account(users, item.getSourceUserId()),
                account(users, item.getTargetUserId()), item.getAgentBalanceAfter(),
                item.getTargetExpireAfter(), item.getCreateTime())).toList(), result.getTotal());
    }

    private String account(Map<Long, AdminUserDO> users, Long userId) {
        if (userId == null) {
            return "系统管理员";
        }
        AdminUserDO user = users.get(userId);
        return user == null ? "账号 #" + userId : user.getUsername();
    }

    private Scope scope() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
        boolean admin = permissionService.hasAnyRoles(userId, "super_admin");
        if (!admin && !permissionService.hasAnyRoles(userId, "lottery_agent")) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
        return new Scope(userId, admin);
    }

    private record Scope(Long userId, boolean admin) {}

    public record Summary(boolean admin, Long availableGold, Long agentCount, Long ownerCount,
                          Long rechargedGold, Long consumedGold) {}

    public record AgentRow(Long id, String username, String nickname, Long availableGold,
                           LocalDateTime createTime) {}

    public record LedgerRow(Long id, Long amount, String sourceAccount, String targetAccount,
                            Long agentBalanceAfter, LocalDateTime ownerExpireAfter,
                            LocalDateTime createTime) {}
}
