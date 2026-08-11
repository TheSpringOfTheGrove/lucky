package com.hnz.luck5.module.lottery.framework.datapermission;

import cn.hutool.core.util.ObjectUtil;
import com.hnz.luck5.framework.common.enums.UserTypeEnum;
import com.hnz.luck5.framework.datapermission.core.rule.DataPermissionRule;
import com.hnz.luck5.framework.mybatis.core.util.MyBatisUtils;
import com.hnz.luck5.framework.security.core.LoginUser;
import com.hnz.luck5.framework.security.core.util.SecurityFrameworkUtils;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;

import java.util.Set;

/**
 * Lucky5 业务数据的后台用户隔离规则。
 *
 * 所有后台用户（包括超级管理员）都只能访问自己的业务数据。
 * 无后台登录上下文的会员端和后台任务不应用该规则。
 */
public class LotteryUserDataPermissionRule implements DataPermissionRule {

    private static final String USER_COLUMN = "user_id";

    private static final Set<String> TABLE_NAMES = Set.of(
            "lucky5_config", "lucky5_system_state", "lucky5_market_connection", "lucky5_link_config",
            "lucky5_chima_config", "lucky5_switch_setting", "lucky5_integration", "lucky5_odd",
            "lucky5_member", "lucky5_amount_record", "lucky5_balance_ledger", "lucky5_order", "lucky5_bet_item", "lucky5_draw",
            "lucky5_issue", "lucky5_issue_transition", "lucky5_preset_order", "lucky5_quick_command",
            "lucky5_follow_order", "lucky5_operation_log", "lucky5_message", "lucky5_rebate_record",
            "lucky5_chima_record", "lucky5_auto_proxy_execution");

    @Override
    public Set<String> getTableNames() {
        return TABLE_NAMES;
    }

    @Override
    public Expression getExpression(String tableName, Alias tableAlias) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null || ObjectUtil.notEqual(loginUser.getUserType(), UserTypeEnum.ADMIN.getValue())) {
            return null;
        }
        return new EqualsTo(MyBatisUtils.buildColumn(tableName, tableAlias, USER_COLUMN),
                new LongValue(loginUser.getId()));
    }

}
