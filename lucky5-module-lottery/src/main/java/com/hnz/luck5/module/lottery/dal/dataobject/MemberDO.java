package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_member")
@Data
@EqualsAndHashCode(callSuper = true)
public class MemberDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private BigDecimal balance;
    private String status;
    private String partner;
    private BigDecimal normalRate;
    private BigDecimal lhhRate;
    private BigDecimal partnerNormalRate;
    private BigDecimal partnerLhhRate;
    private String tag;
    private String externalNickname;
    private BigDecimal totalBet;
    private BigDecimal profitLoss;
    private String memberType;
    private Boolean autoProxy;
    private Boolean autoBetEnabled;
    private BigDecimal autoTopUpAmount;
    private Boolean eatEnabled;
    private Boolean searchable;
    private String openId;
    private String fingerprint;
    private Boolean privateChat;
    private Boolean webOnly;
    private String blueWhalePassword;
    private Integer avatar;
    private LocalDateTime flowClearedAt;
    private Integer version;

}
