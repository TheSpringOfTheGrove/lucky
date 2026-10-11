package com.hnz.luck5.module.system.service.user;

import com.hnz.luck5.module.system.dal.dataobject.user.AdminUserDO;

/** 后台代理金币；1 金币购买老板账号连续 24 小时。 */
public interface AdminGoldService {

    /** 正数增加代理金币，负数扣减；扣减不得使余额为负。 */
    void grantToAgent(Long agentUserId, long amount, String operationKey);

    /** 正数给老板增加使用天数，负数缩短期限并将代理实际分配的金币返还。 */
    void allocateToOwner(Long ownerUserId, long amount, String operationKey);

    Long displayBalance(AdminUserDO user);

    void assertVisible(Long userId);

    /** 超管可管理任何用户；代理只能修改自己的直属老板，不能修改自身账号。 */
    void assertManageableUser(Long userId);
}
