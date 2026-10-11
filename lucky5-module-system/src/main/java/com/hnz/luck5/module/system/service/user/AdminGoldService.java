package com.hnz.luck5.module.system.service.user;

import com.hnz.luck5.module.system.dal.dataobject.user.AdminUserDO;

/** 后台代理金币；1 金币购买老板账号连续 24 小时。 */
public interface AdminGoldService {

    void grantToAgent(Long agentUserId, long amount, String operationKey);

    void allocateToOwner(Long ownerUserId, long amount, String operationKey);

    Long displayBalance(AdminUserDO user);

    void assertVisible(Long userId);
}
