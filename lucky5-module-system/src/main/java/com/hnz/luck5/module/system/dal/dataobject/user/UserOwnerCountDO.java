package com.hnz.luck5.module.system.dal.dataobject.user;

import lombok.Data;

/** 用户管理主列表中每个代理或管理员的直属老板数量。 */
@Data
public class UserOwnerCountDO {

    private Long parentUserId;
    private Long ownerCount;
}
