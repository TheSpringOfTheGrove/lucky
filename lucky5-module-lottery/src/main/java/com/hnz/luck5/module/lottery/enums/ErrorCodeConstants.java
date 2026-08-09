package com.hnz.luck5.module.lottery.enums;

import com.hnz.luck5.framework.common.exception.ErrorCode;

public interface ErrorCodeConstants {

    ErrorCode MEMBER_NOT_FOUND = new ErrorCode(2_001_000_001, "会员不存在");
    ErrorCode MEMBER_NAME_EXISTS = new ErrorCode(2_001_000_002, "会员昵称已存在");
    ErrorCode MEMBER_BALANCE_NOT_ENOUGH = new ErrorCode(2_001_000_003, "会员余额不足");
    ErrorCode RECORD_NOT_FOUND = new ErrorCode(2_001_000_004, "记录不存在");
    ErrorCode RECORD_ALREADY_PROCESSED = new ErrorCode(2_001_000_005, "记录已经处理");
    ErrorCode ISSUE_NOT_OPEN = new ErrorCode(2_001_000_006, "当前期号未开盘");
    ErrorCode ORDER_NOT_FOUND = new ErrorCode(2_001_000_007, "订单不存在");
    ErrorCode ORDER_CAN_NOT_CANCEL = new ErrorCode(2_001_000_008, "只有未开奖订单可以退码");
    ErrorCode PERIOD_ALREADY_SETTLED = new ErrorCode(2_001_000_009, "该期号已经结算");
    ErrorCode BET_CONTENT_INVALID = new ErrorCode(2_001_000_010, "下注内容无法识别");
    ErrorCode CONFIG_NOT_FOUND = new ErrorCode(2_001_000_011, "配置不存在");
    ErrorCode SWITCH_NOT_FOUND = new ErrorCode(2_001_000_012, "开关不存在");
    ErrorCode EXTERNAL_MESSAGE_EXISTS = new ErrorCode(2_001_000_013, "该外部消息已经处理");
    ErrorCode ROOM_CLOSED = new ErrorCode(2_001_000_014, "房间当前未开放");
    ErrorCode ROOM_CREDENTIAL_INVALID = new ErrorCode(2_001_000_015, "会员房间凭据无效");
    ErrorCode BET_LIMIT_INVALID = new ErrorCode(2_001_000_016, "下注金额不符合玩法限额");
    ErrorCode PASSWORD_INVALID = new ErrorCode(2_001_000_017, "当前登录密码不正确");
    ErrorCode MARKET_ORDER_UNAVAILABLE = new ErrorCode(2_001_000_018,
            "普通模式真实盘口下单接口尚未验证，系统已阻止扣款和生成未提交订单");
    ErrorCode PLAY_TYPE_DISABLED = new ErrorCode(2_001_000_019, "当前配置未开放该玩法");
    ErrorCode PRIVATE_BET_DISABLED = new ErrorCode(2_001_000_020, "该会员未开启私聊下注");
    ErrorCode BET_STATE_CHANGED = new ErrorCode(2_001_000_021, "下注状态已变化，请刷新后重试");
    ErrorCode DRAW_RESULT_INVALID = new ErrorCode(2_001_000_022, "开奖号码必须是完整的五位数字");
    ErrorCode DRAW_RESULT_ABNORMAL = new ErrorCode(2_001_000_023, "00000 属于异常开奖号码，禁止结算和派奖");
    ErrorCode DRAW_RESULT_NOT_VERIFIED = new ErrorCode(2_001_000_024, "开奖号码尚未通过开奖API二次确认");
    ErrorCode DRAW_REASON_REQUIRED = new ErrorCode(2_001_000_025, "人工开奖必须填写原因");
}
