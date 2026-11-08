package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.AmountRecordDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryRoomInternalFundingTest {
    private LotteryServiceImpl service;
    private MemberDO viewer;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MessageDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AmountRecordDO.class);
        service = new LotteryServiceImpl();
        viewer = new MemberDO();
        viewer.setId("M-1");
        viewer.setUserId(7L);
        viewer.setMemberType("PLAYER");
    }

    @Test
    void groupAndPrivateMemberQueriesKeepAutomaticProxyTransfers() {
        for (String channel : new String[]{"网页群", "网页私聊"}) {
            LambdaQueryWrapper<MessageDO> query = ReflectionTestUtils.invokeMethod(service,
                    "roomMemberMessageQuery", viewer, channel);
            assertThat(query.getSqlSegment()).contains("user_id =", "channel =")
                    .doesNotContain("message_type", "command_type", "external_id");
            assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder(7L, channel);
        }
    }

    @Test
    void groupHistoryAlsoLoadsLegacySystemChannelAutomaticProxyTransfers() {
        LambdaQueryWrapper<MessageDO> query = ReflectionTestUtils.invokeMethod(service,
                "legacyAutoProxyTransferQuery", 7L, 123L);
        assertThat(query.getSqlSegment()).contains("user_id =", "channel =", "message_type =",
                "command_type IN", "id <", "LIMIT 100");
        assertThat(query.getParamNameValuePairs().values()).contains(7L, "系统", "AUTO_PROXY",
                "DEPOSIT_REQUEST", "WITHDRAW_REQUEST", 123L);
    }

    @Test
    void amountQueryIsOwnMemberScopedAndExcludesBothStableAndLegacyInternalSources() {
        LambdaQueryWrapper<AmountRecordDO> query = ReflectionTestUtils.invokeMethod(service,
                "roomAmountRecordQuery", viewer);
        assertThat(query.getSqlSegment()).contains("user_id =", "member_id =", "record_source IS NULL",
                "record_source <>", "remark IS NULL", "remark NOT LIKE", "LIMIT 20");
        assertThat(query.getParamNameValuePairs().values()).contains(7L, "M-1", "AUTO_PROXY",
                "%自动托虚拟积分不足%", "%自动托上下分自动审核%");
    }
}
