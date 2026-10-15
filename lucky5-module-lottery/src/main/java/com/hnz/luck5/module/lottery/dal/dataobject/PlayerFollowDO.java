package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("lucky5_player_follow")
@Data
@EqualsAndHashCode(callSuper = true)
public class PlayerFollowDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String memberId;
    private String channel;
    private String sourceOrderId;
    private String sourcePeriod;
    private String content;
    private Boolean enabled;
    private LocalDateTime stoppedAt;

}
