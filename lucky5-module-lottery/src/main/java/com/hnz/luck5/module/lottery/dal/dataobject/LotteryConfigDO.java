package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hnz.luck5.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@TableName("lucky5_config")
@Data
@EqualsAndHashCode(callSuper = true)
public class LotteryConfigDO extends TenantBaseDO {

    private Long id;
    private String roomName;
    private String closeTime;
    private Integer settleDelay;
    private BigDecimal minDeposit;
    private BigDecimal maxDeposit;
    private String announcement;
    private String serviceUrl;
    private String chatUrl;
    private String upstreamUrl;
    private String upstreamAccount;
    private String marketPasswordEncrypted;
    private BigDecimal alertValue;
    private Boolean bossMode;
    private Integer playType;
    private Boolean useProxy;

}
