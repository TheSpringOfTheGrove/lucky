package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("lucky5_player_follow_execution")
@Data
@EqualsAndHashCode(callSuper = true)
public class PlayerFollowExecutionDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String followId;
    private String memberId;
    private String period;
    private String status;
    private String orderId;
    private String error;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime completedAt;

}
