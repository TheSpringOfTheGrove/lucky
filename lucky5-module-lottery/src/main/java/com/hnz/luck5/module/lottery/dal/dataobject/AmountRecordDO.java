package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_amount_record")
@Data
@EqualsAndHashCode(callSuper = true)
public class AmountRecordDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String memberId;
    private String memberName;
    private String type;
    private BigDecimal amount;
    private String status;
    private String remark;
    private LocalDateTime auditedAt;
    private String auditedBy;

}
