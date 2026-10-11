package com.hnz.luck5.module.system.service.user;

import com.hnz.luck5.framework.common.enums.UserTypeEnum;
import com.hnz.luck5.framework.common.pojo.PageParam;
import com.hnz.luck5.framework.security.core.LoginUser;
import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.framework.test.core.ut.BaseDbUnitTest;
import com.hnz.luck5.module.system.dal.dataobject.permission.RoleDO;
import com.hnz.luck5.module.system.dal.dataobject.permission.UserRoleDO;
import com.hnz.luck5.module.system.dal.dataobject.user.AdminUserDO;
import com.hnz.luck5.module.system.dal.dataobject.user.GoldLedgerDO;
import com.hnz.luck5.module.system.dal.mysql.permission.RoleMapper;
import com.hnz.luck5.module.system.dal.mysql.permission.UserRoleMapper;
import com.hnz.luck5.module.system.dal.mysql.user.AdminUserMapper;
import com.hnz.luck5.module.system.dal.mysql.user.GoldLedgerMapper;
import com.hnz.luck5.module.system.service.permission.PermissionService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static com.hnz.luck5.framework.test.core.util.AssertUtils.assertServiceException;
import static com.hnz.luck5.module.system.enums.ErrorCodeConstants.USER_GOLD_ACCESS_DENIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@Import(AgentStatisticsService.class)
class AgentStatisticsServiceTest extends BaseDbUnitTest {

    private static final long TENANT_ID = 99001L;
    private static final long ADMIN_ID = 99001L;
    private static final long FIRST_AGENT_ID = 99002L;
    private static final long FIRST_OWNER_ID = 99003L;
    private static final long SECOND_AGENT_ID = 99004L;
    private static final long SECOND_OWNER_ID = 99005L;

    @Resource private AgentStatisticsService service;
    @Resource private AdminUserMapper userMapper;
    @Resource private GoldLedgerMapper ledgerMapper;
    @Resource private RoleMapper roleMapper;
    @Resource private UserRoleMapper userRoleMapper;
    @MockitoBean private PermissionService permissionService;

    @AfterEach
    void after() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminSeesTenantTotalsAndAgentSeesOnlyDirectConsumption() {
        TenantContextHolder.setTenantId(TENANT_ID);
        // 统计以有效角色为准：旧账号类型错标也不能让代理列表漏人。
        insertUser(FIRST_AGENT_ID, "OWNER", null, 7L);
        insertUser(FIRST_OWNER_ID, "OWNER", FIRST_AGENT_ID, 0L);
        insertUser(SECOND_AGENT_ID, "AGENT", null, 9L);
        insertUser(SECOND_OWNER_ID, "AGENT", SECOND_AGENT_ID, 0L);
        insertRole(99011L, FIRST_AGENT_ID, "lottery_agent");
        userRoleMapper.insert(new UserRoleDO().setUserId(SECOND_AGENT_ID).setRoleId(99011L));
        insertRole(99013L, FIRST_OWNER_ID, "crm_admin");
        insertRole(99014L, SECOND_OWNER_ID, "tenant_admin");
        insertLedger("ADMIN_GRANT", null, FIRST_AGENT_ID, 10L);
        insertLedger("OWNER_ALLOCATION", FIRST_AGENT_ID, FIRST_OWNER_ID, 3L);
        insertLedger("OWNER_ALLOCATION", SECOND_AGENT_ID, SECOND_OWNER_ID, 4L);
        insertLedger("OWNER_ALLOCATION", null, SECOND_OWNER_ID, 2L);
        when(permissionService.hasAnyRoles(ADMIN_ID, "super_admin")).thenReturn(true);
        when(permissionService.hasAnyRoles(FIRST_AGENT_ID, "lottery_agent")).thenReturn(true);

        login(ADMIN_ID);
        var admin = service.summary();
        assertEquals(2L, admin.agentCount());
        assertEquals(2L, admin.ownerCount());
        assertEquals(12L, admin.rechargedGold());
        assertEquals(9L, admin.consumedGold());
        assertEquals(2L, service.agents(new PageParam()).getTotal());
        assertEquals(2L, service.ledger(new PageParam(), "RECHARGE").getTotal());
        assertEquals("stats" + SECOND_OWNER_ID,
                service.ledger(new PageParam(), "CONSUMPTION").getList().get(0).targetAccount());

        login(FIRST_AGENT_ID);
        var agent = service.summary();
        assertEquals(7L, agent.availableGold());
        assertEquals(1L, agent.ownerCount());
        assertEquals(3L, agent.consumedGold());
        assertNull(agent.agentCount());
        assertNull(agent.rechargedGold());
        assertEquals(1L, service.ledger(new PageParam(), "CONSUMPTION").getTotal());
        assertServiceException(() -> service.ledger(new PageParam(), "RECHARGE"), USER_GOLD_ACCESS_DENIED);
        assertServiceException(() -> service.agents(new PageParam()), USER_GOLD_ACCESS_DENIED);
    }

    private void insertUser(Long id, String type, Long parent, Long balance) {
        AdminUserDO user = new AdminUserDO().setId(id).setUsername("stats" + id).setPassword("hash")
                .setNickname("stats" + id).setStatus(0).setExpireTime(LocalDateTime.of(2099, 12, 31, 23, 59))
                .setAccountType(type).setParentAgentUserId(parent).setGoldBalance(balance);
        user.setTenantId(TENANT_ID);
        userMapper.insert(user);
    }

    private void insertLedger(String type, Long source, Long target, Long amount) {
        GoldLedgerDO ledger = new GoldLedgerDO().setOperationKey("stats-" + type + "-" + source + "-" + target)
                .setOperationType(type).setSourceUserId(source).setTargetUserId(target)
                .setAmount(amount).setOperatorUserId(source == null ? ADMIN_ID : source);
        ledger.setTenantId(TENANT_ID);
        ledgerMapper.insert(ledger);
    }

    private void insertRole(Long roleId, Long userId, String code) {
        RoleDO role = new RoleDO().setId(roleId).setName(code + roleId).setCode(code)
                .setSort(1).setDataScope(1).setStatus(0).setType(1);
        role.setTenantId(TENANT_ID);
        roleMapper.insert(role);
        userRoleMapper.insert(new UserRoleDO().setUserId(userId).setRoleId(roleId));
    }

    private void login(Long userId) {
        LoginUser user = new LoginUser().setId(userId).setTenantId(TENANT_ID)
                .setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
