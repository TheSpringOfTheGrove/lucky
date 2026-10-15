package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PlayerFollowDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PlayerFollowExecutionDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PlayerFollowExecutionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PlayerFollowMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryPlayerFollowServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "player-follow-test"),
                PlayerFollowDO.class);
    }

    @Mock private PlayerFollowMapper followMapper;
    @Mock private PlayerFollowExecutionMapper executionMapper;
    @Mock private OrderMapper orderMapper;
    @Mock private IssueMapper issueMapper;
    @InjectMocks private LotteryPlayerFollowService service;

    @Test
    void bindsTwoManualOrdersAsIndependentFrozenInstructionsWithoutRepeatingCurrentPeriod() {
        MemberDO member = new MemberDO();
        member.setId("member-1");
        member.setUserId(7L);
        member.setMemberType("REAL");
        OrderDO first = manualOrder("order-1", "20261013001", "123各1");
        OrderDO second = manualOrder("order-2", "20261013001", "456各1");
        IssueDO issue = new IssueDO();
        issue.setPeriod("20261013001");
        issue.setOpenedAt(LocalDateTime.now());
        when(orderMapper.selectOne(any())).thenReturn(first, second);
        when(issueMapper.selectOne(any())).thenReturn(issue);

        assertThat(service.bind(member, "网页群").alreadyPlaced()).isTrue();
        assertThat(service.bind(member, "网页群").alreadyPlaced()).isTrue();

        ArgumentCaptor<PlayerFollowDO> bindings = ArgumentCaptor.forClass(PlayerFollowDO.class);
        verify(followMapper, times(2)).insert(bindings.capture());
        assertThat(bindings.getAllValues()).extracting(PlayerFollowDO::getContent)
                .containsExactly("123各1", "456各1");
        assertThat(bindings.getAllValues()).extracting(PlayerFollowDO::getSourceOrderId)
                .containsExactly("order-1", "order-2");
        verify(executionMapper, times(0)).insert(any(PlayerFollowExecutionDO.class));
    }

    @Test
    void schedulesEveryBindingSeparatelyForTheNextIssue() {
        PlayerFollowDO first = follow("follow-1", "order-1", "123各1");
        PlayerFollowDO second = follow("follow-2", "order-2", "456各1");
        when(followMapper.selectList(any())).thenReturn(List.of(first, second));

        service.schedule(7L, "20261013002");

        ArgumentCaptor<PlayerFollowExecutionDO> tasks = ArgumentCaptor.forClass(PlayerFollowExecutionDO.class);
        verify(executionMapper, times(2)).insert(tasks.capture());
        assertThat(tasks.getAllValues()).extracting(PlayerFollowExecutionDO::getFollowId)
                .containsExactly("follow-1", "follow-2");
        assertThat(tasks.getAllValues()).extracting(PlayerFollowExecutionDO::getPeriod)
                .containsOnly("20261013002");
    }

    @Test
    void stopDisablesAllBindingsForThisMemberRegardlessOfRoom() {
        MemberDO member = new MemberDO();
        member.setId("member-1");
        member.setUserId(7L);
        when(followMapper.update(isNull(), any())).thenReturn(2);

        assertThat(service.stop(member)).isEqualTo(2);

        ArgumentCaptor<Wrapper> updates = ArgumentCaptor.forClass(Wrapper.class);
        verify(followMapper).update(isNull(), updates.capture());
        assertThat(updates.getValue().getSqlSegment()).contains("user_id", "member_id", "enabled")
                .doesNotContain("channel");
    }

    private PlayerFollowDO follow(String id, String sourceOrderId, String content) {
        PlayerFollowDO follow = new PlayerFollowDO();
        follow.setId(id);
        follow.setUserId(7L);
        follow.setMemberId("member-1");
        follow.setChannel("网页群");
        follow.setSourceOrderId(sourceOrderId);
        follow.setSourcePeriod("20261013001");
        follow.setContent(content);
        follow.setEnabled(true);
        return follow;
    }

    private OrderDO manualOrder(String id, String period, String content) {
        OrderDO order = new OrderDO();
        order.setId(id);
        order.setUserId(7L);
        order.setMemberId("member-1");
        order.setSource("网页群");
        order.setPeriod(period);
        order.setContent(content);
        order.setStatus("未开奖");
        order.setMarketStatus("NOT_REQUIRED");
        order.setOrderType("PLAYER");
        return order;
    }
}
