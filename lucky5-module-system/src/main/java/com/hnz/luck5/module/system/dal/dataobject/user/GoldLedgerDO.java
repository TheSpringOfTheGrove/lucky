package com.hnz.luck5.module.system.dal.dataobject.user;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hnz.luck5.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 不可变的后台金币发行与分配流水；与玩家积分流水完全独立。 */
@TableName("system_gold_ledger")
@Data
@EqualsAndHashCode(callSuper = true)
public class GoldLedgerDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String operationKey;
    private String operationType;
    private Long sourceUserId;
    private Long targetUserId;
    private Long amount;
    private Long agentBalanceBefore;
    private Long agentBalanceAfter;
    private LocalDateTime targetExpireBefore;
    private LocalDateTime targetExpireAfter;
    private Long operatorUserId;
}
