package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_link_config")
@Data
@EqualsAndHashCode(callSuper = true)
public class LinkConfigDO extends LotteryUserBaseDO {

    private Long id;
    private String deviceId;
    private String dealerUrl;
    private String roomUrl;
    private String shortUrl;
    private String qrMode;
    private Integer shortUrlMode;

}
