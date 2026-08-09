package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.hnz.luck5.framework.mybatis.core.dataobject.UserScopedDO;
import com.hnz.luck5.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Lucky5 租户内按后台用户隔离的基础数据对象。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class LotteryUserBaseDO extends TenantBaseDO implements UserScopedDO {

    @TableField(fill = FieldFill.INSERT)
    private Long userId;

}
