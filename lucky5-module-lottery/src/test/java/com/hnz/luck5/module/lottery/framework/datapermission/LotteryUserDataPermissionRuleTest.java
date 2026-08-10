package com.hnz.luck5.module.lottery.framework.datapermission;

import com.hnz.luck5.framework.common.enums.UserTypeEnum;
import com.hnz.luck5.framework.security.core.LoginUser;
import net.sf.jsqlparser.expression.Expression;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryUserDataPermissionRuleTest {

    private final LotteryUserDataPermissionRule rule = new LotteryUserDataPermissionRule();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void superAdminMustStillBeUserScoped() {
        login(1L, UserTypeEnum.ADMIN.getValue());

        Expression expression = rule.getExpression("lucky5_amount_record", null);

        assertThat(expression.toString()).isEqualTo("lucky5_amount_record.user_id = 1");
    }

    @Test
    void ownerMustBeUserScoped() {
        login(142L, UserTypeEnum.ADMIN.getValue());

        Expression expression = rule.getExpression("lucky5_order", null);

        assertThat(expression.toString()).isEqualTo("lucky5_order.user_id = 142");
    }

    @Test
    void publicRoomWithoutAdminLoginIsNotFiltered() {
        assertThat(rule.getExpression("lucky5_amount_record", null)).isNull();
    }

    private void login(Long userId, Integer userType) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        loginUser.setUserType(userType);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));
    }
}
