package com.hnz.luck5.module.system.service.user;

import com.hnz.luck5.framework.security.core.util.SecurityFrameworkUtils;
import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.module.system.dal.dataobject.user.AdminUserDO;
import com.hnz.luck5.module.system.dal.dataobject.user.GoldLedgerDO;
import com.hnz.luck5.module.system.dal.mysql.user.AdminUserMapper;
import com.hnz.luck5.module.system.dal.mysql.user.GoldLedgerMapper;
import com.hnz.luck5.module.system.service.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.system.enums.ErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
public class AdminGoldServiceImpl implements AdminGoldService {

    private static final long MAX_DAYS_PER_OPERATION = 36500;
    private static final String ADMIN_GRANT = "ADMIN_GRANT";
    private static final String OWNER_ALLOCATION = "OWNER_ALLOCATION";

    private final AdminUserMapper userMapper;
    private final GoldLedgerMapper ledgerMapper;
    private final PermissionService permissionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grantToAgent(Long agentUserId, long amount, String operationKey) {
        Long operatorId = requireOperator();
        if (!isAdmin(operatorId)) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
        validateAmountAndKey(amount, operationKey);
        AdminUserDO agent = lockedUser(agentUserId);
        if (!"AGENT".equals(agent.getAccountType())) {
            throw exception(USER_GOLD_TARGET_INVALID);
        }
        if (alreadyApplied(operationKey, ADMIN_GRANT, null, agentUserId, amount)) {
            return;
        }
        long before = balance(agent);
        if (amount < 0 && before < -amount) {
            throw exception(USER_GOLD_INSUFFICIENT);
        }
        long after = Math.addExact(before, amount);
        userMapper.updateById(new AdminUserDO().setId(agentUserId).setGoldBalance(after));
        GoldLedgerDO ledger = new GoldLedgerDO();
        ledger.setTenantId(TenantContextHolder.getRequiredTenantId());
        ledgerMapper.insert(ledger.setOperationKey(operationKey).setOperationType(ADMIN_GRANT)
                .setTargetUserId(agentUserId).setAmount(amount).setAgentBalanceBefore(before)
                .setAgentBalanceAfter(after).setOperatorUserId(operatorId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void allocateToOwner(Long ownerUserId, long amount, String operationKey) {
        Long operatorId = requireOperator();
        boolean admin = isAdmin(operatorId);
        if (!admin && !isAgent(operatorId)) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
        validateAmountAndKey(amount, operationKey);
        // admin 代代理操作其直属老板时，金币仍从该代理余额出入。
        // 先读取不可通过资料修改的归属，再按来源、目标的顺序加锁，避免并发透支和死锁。
        Long agentId = admin ? findOwnerAgentId(ownerUserId) : operatorId;
        AdminUserDO agent = agentId == null ? null : lockedUser(agentId);
        AdminUserDO owner = lockedUser(ownerUserId);
        if (!"OWNER".equals(owner.getAccountType()) ||
                (!admin && !Objects.equals(owner.getParentAgentUserId(), operatorId))) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
        if (admin && (agent != null && (!Objects.equals(owner.getParentAgentUserId(), agentId) ||
                !"AGENT".equals(agent.getAccountType()) || !isAgent(agentId)) ||
                agent == null && owner.getParentAgentUserId() != null &&
                        !isAdmin(owner.getParentAgentUserId()))) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
        if (alreadyAppliedOwner(operationKey, admin, operatorId, agentId, ownerUserId, amount)) {
            return;
        }
        long before = agent == null ? 0 : balance(agent);
        if (amount > 0 && agent != null && before < amount) {
            throw exception(USER_GOLD_INSUFFICIENT);
        }
        LocalDateTime oldExpiry = owner.getExpireTime();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextExpiry;
        boolean refundLegacyAdminAllocation = false;
        if (amount > 0) {
            nextExpiry = (oldExpiry != null && oldExpiry.isAfter(now) ? oldExpiry : now).plusDays(amount);
        } else {
            long deduction = -amount;
            long remainingSeconds = oldExpiry == null ? 0 : Duration.between(now, oldExpiry).getSeconds();
            long remainingGold = remainingSeconds <= 0 ? 0 : (remainingSeconds + 86399) / 86400;
            long agentAllocated = agent == null ? 0 : ledgerMapper.sumOwnerAllocation(
                    TenantContextHolder.getRequiredTenantId(), agentId, ownerUserId);
            long adminAllocated = admin ? ledgerMapper.sumAdminOwnerAllocation(
                    TenantContextHolder.getRequiredTenantId(), ownerUserId) : 0;
            refundLegacyAdminAllocation = admin && agent != null && agentAllocated < deduction &&
                    adminAllocated >= deduction;
            if (remainingGold < deduction || (agent != null && agentAllocated < deduction &&
                    !refundLegacyAdminAllocation) || (agent == null && admin &&
                    !Objects.equals(owner.getParentAgentUserId(), operatorId) && adminAllocated < deduction)) {
                throw exception(USER_GOLD_OWNER_INSUFFICIENT);
            }
            nextExpiry = oldExpiry.minusDays(deduction);
            if (nextExpiry.isBefore(now)) {
                nextExpiry = now;
            }
        }
        if (agent != null && !refundLegacyAdminAllocation) {
            userMapper.updateById(new AdminUserDO().setId(agentId)
                    .setGoldBalance(Math.subtractExact(before, amount)));
        }
        userMapper.updateById(new AdminUserDO().setId(ownerUserId).setExpireTime(nextExpiry));
        GoldLedgerDO ledger = new GoldLedgerDO();
        ledger.setTenantId(TenantContextHolder.getRequiredTenantId());
        ledgerMapper.insert(ledger.setOperationKey(operationKey).setOperationType(OWNER_ALLOCATION)
                .setSourceUserId(agent == null || refundLegacyAdminAllocation ? null : agentId)
                .setTargetUserId(ownerUserId)
                .setAmount(amount).setAgentBalanceBefore(agent == null || refundLegacyAdminAllocation ? null : before)
                .setAgentBalanceAfter(agent == null || refundLegacyAdminAllocation ? null :
                        Math.subtractExact(before, amount))
                .setTargetExpireBefore(oldExpiry).setTargetExpireAfter(nextExpiry)
                .setOperatorUserId(operatorId));
    }

    @Override
    public Long displayBalance(AdminUserDO user) {
        if (user == null || isAdmin(user.getId())) {
            return null;
        }
        if ("AGENT".equals(user.getAccountType())) {
            return balance(user);
        }
        if (!"OWNER".equals(user.getAccountType()) || user.getExpireTime() == null) {
            return 0L;
        }
        long seconds = Duration.between(LocalDateTime.now(), user.getExpireTime()).getSeconds();
        return seconds <= 0 ? 0L : (seconds + 86399) / 86400;
    }

    @Override
    public void assertVisible(Long userId) {
        Long operatorId = requireOperator();
        if (isAdmin(operatorId) || Objects.equals(operatorId, userId)) {
            return;
        }
        AdminUserDO target = userMapper.selectById(userId);
        if (!isAgent(operatorId) || target == null ||
                !Objects.equals(target.getParentAgentUserId(), operatorId)) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
    }

    @Override
    public void assertManageableUser(Long userId) {
        Long operatorId = requireOperator();
        if (isAdmin(operatorId)) {
            return;
        }
        AdminUserDO owner = userMapper.selectById(userId);
        if (!isAgent(operatorId) || owner == null || !"OWNER".equals(owner.getAccountType()) ||
                !Objects.equals(owner.getParentAgentUserId(), operatorId)) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
    }

    private AdminUserDO lockedUser(Long userId) {
        AdminUserDO user = userMapper.selectForUpdate(TenantContextHolder.getRequiredTenantId(), userId);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        return user;
    }

    private Long findOwnerAgentId(Long ownerUserId) {
        AdminUserDO owner = userMapper.selectById(ownerUserId);
        if (owner == null) {
            throw exception(USER_NOT_EXISTS);
        }
        Long parentId = owner.getParentAgentUserId();
        if (parentId == null) {
            return null;
        }
        AdminUserDO parent = userMapper.selectById(parentId);
        if (parent == null) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
        return "AGENT".equals(parent.getAccountType()) ? parentId : null;
    }

    private boolean alreadyAppliedOwner(String key, boolean admin, Long operatorId, Long agentId,
                                        Long ownerUserId, long amount) {
        GoldLedgerDO previous = ledgerMapper.selectByOperationKey(key);
        if (previous == null) {
            return false;
        }
        // 旧版本 admin 为代理老板直充的流水没有来源；重试旧操作不能再次延长期限。
        if (!OWNER_ALLOCATION.equals(previous.getOperationType()) ||
                !Objects.equals(ownerUserId, previous.getTargetUserId()) ||
                !Objects.equals(amount, previous.getAmount()) ||
                !Objects.equals(operatorId, previous.getOperatorUserId()) ||
                !(Objects.equals(agentId, previous.getSourceUserId()) ||
                        admin && previous.getSourceUserId() == null)) {
            throw exception(USER_GOLD_OPERATION_CONFLICT);
        }
        return true;
    }

    private boolean alreadyApplied(String key, String type, Long sourceId, Long targetId, long amount) {
        GoldLedgerDO previous = ledgerMapper.selectByOperationKey(key);
        if (previous == null) {
            return false;
        }
        if (!type.equals(previous.getOperationType()) || !Objects.equals(sourceId, previous.getSourceUserId()) ||
                !Objects.equals(targetId, previous.getTargetUserId()) ||
                !Objects.equals(amount, previous.getAmount())) {
            throw exception(USER_GOLD_OPERATION_CONFLICT);
        }
        return true;
    }

    private void validateAmountAndKey(long amount, String key) {
        if (amount == 0 || amount < -MAX_DAYS_PER_OPERATION || amount > MAX_DAYS_PER_OPERATION) {
            throw exception(USER_GOLD_AMOUNT_INVALID);
        }
        if (key == null || key.isBlank() || key.length() > 100) {
            throw exception(USER_GOLD_OPERATION_CONFLICT);
        }
    }

    private Long requireOperator() {
        Long operatorId = SecurityFrameworkUtils.getLoginUserId();
        if (operatorId == null) {
            throw exception(USER_GOLD_ACCESS_DENIED);
        }
        return operatorId;
    }

    private boolean isAdmin(Long userId) {
        return permissionService.hasAnyRoles(userId, "super_admin");
    }

    private boolean isAgent(Long userId) {
        return permissionService.hasAnyRoles(userId, "lottery_agent");
    }

    private long balance(AdminUserDO user) {
        return user.getGoldBalance() == null ? 0L : user.getGoldBalance();
    }
}
