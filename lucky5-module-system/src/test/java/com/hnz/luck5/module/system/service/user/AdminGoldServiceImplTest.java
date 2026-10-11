package com.hnz.luck5.module.system.service.user;

import com.hnz.luck5.framework.common.enums.UserTypeEnum;
import com.hnz.luck5.framework.security.core.LoginUser;
import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.framework.test.core.ut.BaseDbUnitTest;
import com.hnz.luck5.module.system.dal.dataobject.user.AdminUserDO;
import com.hnz.luck5.module.system.dal.mysql.user.AdminUserMapper;
import com.hnz.luck5.module.system.dal.mysql.user.GoldLedgerMapper;
import com.hnz.luck5.module.system.framework.datapermission.rule.AgentUserDataPermissionRule;
import com.hnz.luck5.module.system.service.permission.PermissionService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static com.hnz.luck5.framework.test.core.util.AssertUtils.assertServiceException;
import static com.hnz.luck5.module.system.enums.ErrorCodeConstants.USER_GOLD_INSUFFICIENT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@Import(AdminGoldServiceImpl.class)
class AdminGoldServiceImplTest extends BaseDbUnitTest {

    @Resource private AdminGoldService goldService;
    @Resource private AdminUserMapper userMapper;
    @Resource private GoldLedgerMapper ledgerMapper;
    @MockitoBean private PermissionService permissionService;

    @BeforeEach
    void before() {
        TenantContextHolder.setTenantId(1L);
        when(permissionService.hasAnyRoles(1L, "super_admin")).thenReturn(true);
        when(permissionService.hasAnyRoles(2L, "lottery_agent")).thenReturn(true);
        insertUser(2L, "AGENT", null, LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        insertUser(3L, "OWNER", 2L, LocalDateTime.now().minusSeconds(1));
    }

    @AfterEach
    void after() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void agentCannotAllocateWithoutGold() {
        login(2L);
        assertServiceException(() -> goldService.allocateToOwner(3L, 1, "zero-balance"),
                USER_GOLD_INSUFFICIENT);
        assertEquals(0, ledgerMapper.selectCount());
        assertEquals(0L, userMapper.selectById(2L).getGoldBalance());
    }

    @Test
    void grantThenAllocateIsAtomicAndIdempotent() {
        login(1L);
        goldService.grantToAgent(2L, 3, "grant-3");
        goldService.grantToAgent(2L, 3, "grant-3");
        assertEquals(3L, userMapper.selectById(2L).getGoldBalance());

        login(2L);
        goldService.allocateToOwner(3L, 2, "allocate-2");
        goldService.allocateToOwner(3L, 2, "allocate-2");
        assertEquals(1L, userMapper.selectById(2L).getGoldBalance());
        assertEquals(2, ledgerMapper.selectCount());
        LocalDateTime expiry = userMapper.selectById(3L).getExpireTime();
        assertTrue(expiry.isAfter(LocalDateTime.now().plusHours(47)));
        assertTrue(expiry.isBefore(LocalDateTime.now().plusHours(49)));
    }

    @Test
    void agentUserRuleRequiresOwnOrDirectChild() {
        login(2L);
        String condition = new AgentUserDataPermissionRule(permissionService)
                .getExpression("system_users", null).toString();
        assertTrue(condition.contains("id = 2"));
        assertTrue(condition.contains("parent_agent_user_id = 2"));
    }

    private void insertUser(Long id, String accountType, Long parentId, LocalDateTime expiry) {
        AdminUserDO user = new AdminUserDO().setId(id).setUsername("gold" + id).setPassword("hash")
                .setNickname("gold" + id).setStatus(0).setExpireTime(expiry)
                .setAccountType(accountType).setParentAgentUserId(parentId).setGoldBalance(0L);
        user.setTenantId(1L);
        userMapper.insert(user);
    }

    private void login(Long userId) {
        LoginUser user = new LoginUser().setId(userId).setTenantId(1L)
                .setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
