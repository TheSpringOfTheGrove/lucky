package com.hnz.luck5.module.system.framework.datapermission.rule;

import com.hnz.luck5.framework.common.enums.UserTypeEnum;
import com.hnz.luck5.framework.datapermission.core.rule.DataPermissionRule;
import com.hnz.luck5.framework.mybatis.core.util.MyBatisUtils;
import com.hnz.luck5.framework.security.core.LoginUser;
import com.hnz.luck5.framework.security.core.util.SecurityFrameworkUtils;
import com.hnz.luck5.module.system.service.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;

import java.util.Objects;
import java.util.Set;

/** 代理只能查询自己和直属老板；不能借部门/角色的“全部数据”权限越界。 */
@RequiredArgsConstructor
public class AgentUserDataPermissionRule implements DataPermissionRule {

    private final PermissionService permissionService;

    @Override
    public Set<String> getTableNames() {
        return Set.of("system_users");
    }

    @Override
    public Expression getExpression(String tableName, Alias tableAlias) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null || !Objects.equals(loginUser.getUserType(), UserTypeEnum.ADMIN.getValue()) ||
                permissionService.hasAnyRoles(loginUser.getId(), "super_admin") ||
                !permissionService.hasAnyRoles(loginUser.getId(), "lottery_agent")) {
            return null;
        }
        Expression own = new EqualsTo(MyBatisUtils.buildColumn(tableName, tableAlias, "id"),
                new LongValue(loginUser.getId()));
        Expression children = new EqualsTo(MyBatisUtils.buildColumn(tableName, tableAlias, "parent_agent_user_id"),
                new LongValue(loginUser.getId()));
        return new ParenthesedExpressionList(new OrExpression(own, children));
    }
}
