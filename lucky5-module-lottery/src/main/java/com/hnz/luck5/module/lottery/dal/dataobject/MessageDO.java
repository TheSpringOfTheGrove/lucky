package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hnz.luck5.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_message")
@Data
@EqualsAndHashCode(callSuper = true)
public class MessageDO extends TenantBaseDO {

    private Long id;
    private Long legacyId;
    private String channel;
    private String member;
    private String period;
    private String content;
    private String status;
    private String orderId;
    private String externalId;
    private String error;
    private String commandType;
    private String reply;
    private LocalDateTime processedAt;

}

