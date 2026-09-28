package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LotteryRoomBetReplyQueryTest {
    @Test
    void lightQueryIsBoundedAndRestrictedToOwnerAndMemberWithoutFinancialOrTimingFields() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MessageDO.class);
        MemberDO member = new MemberDO();
        member.setId("member-1");
        member.setUserId(7L);
        LambdaQueryWrapper<MessageDO> query = ReflectionTestUtils.invokeMethod(new LotteryServiceImpl(),
                "roomBetReplyQuery", member, List.of(11L, 12L));
        assertThat(query.getSqlSegment()).contains("user_id =", "member_id =", "command_type =", "id IN", "LIMIT 5");
        assertThat(query.getParamNameValuePairs().values()).contains(7L, "member-1", "BET", 11L, 12L);
        assertThat(query.getSqlSelect()).doesNotContain("content", "draw_image", "error", "elapsed", "balance");
    }

    @Test
    void rejectsAnEmptyOrUnboundedQueryInsteadOfReadingAllMessages() {
        LotteryServiceImpl service = new LotteryServiceImpl();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "roomBetReplyQuery", new MemberDO(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "roomBetReplyQuery", new MemberDO(),
                List.of(1L, 2L, 3L, 4L, 5L, 6L))).isInstanceOf(IllegalArgumentException.class);
    }
}
