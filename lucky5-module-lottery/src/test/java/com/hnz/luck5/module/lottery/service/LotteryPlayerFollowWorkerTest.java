package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.framework.common.exception.ServiceException;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PlayerFollowDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PlayerFollowExecutionDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.LotteryConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PlayerFollowExecutionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PlayerFollowMapper;
import com.hnz.luck5.module.lottery.dal.mysql.SystemStateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.MEMBER_BALANCE_NOT_ENOUGH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryPlayerFollowWorkerTest {

    @BeforeAll
    static void initTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "player-follow-test"),
                PlayerFollowDO.class);
    }

    @Mock private PlayerFollowExecutionMapper executionMapper;
    @Mock private PlayerFollowMapper followMapper;
    @Mock private MemberMapper memberMapper;
    @Mock private LotteryConfigMapper configMapper;
    @Mock private SystemStateMapper stateMapper;
    @Mock private IssueMapper issueMapper;
    @Mock private OrderMapper orderMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private LotteryService lotteryService;
    @Mock private LotteryRobotReplyTemplate robotReplyTemplate;
    @InjectMocks private LotteryPlayerFollowWorker worker;

    @Test
    void executesTheFrozenInstructionAsARealPlayerOrder() {
        PlayerFollowExecutionDO task = new PlayerFollowExecutionDO();
        task.setId("execution-1");
        task.setUserId(7L);
        task.setFollowId("follow-1");
        task.setMemberId("member-1");
        task.setPeriod("20261013002");
        task.setStatus("SCHEDULED");
        PlayerFollowDO follow = follow("follow-1");
        follow.setSourceOrderId("order-1");
        follow.setContent("123各1");
        MemberDO member = new MemberDO();
        member.setId("member-1");
        member.setUserId(7L);
        member.setName("玩家甲");
        member.setMemberType("REAL");
        LotteryConfigDO config = new LotteryConfigDO();
        config.setBossMode(true);
        OrderDO source = new OrderDO();
        source.setId("order-1");
        source.setMarketStatus("NOT_REQUIRED");
        IssueDO issue = new IssueDO();
        issue.setPeriod("20261013002");
        issue.setStatus("OPEN");
        issue.setOpenedAt(LocalDateTime.now());
        IssueDO previous = new IssueDO();
        previous.setPeriod("20261013001");
        previous.setStatus("SETTLED");
        previous.setOpenedAt(LocalDateTime.now().minusMinutes(5));
        when(executionMapper.selectOne(any())).thenReturn(task);
        when(followMapper.selectOne(any())).thenReturn(follow);
        when(configMapper.selectOne(any())).thenReturn(config);
        when(issueMapper.selectOne(any())).thenReturn(issue, previous);
        when(memberMapper.selectOne(any())).thenReturn(member);
        when(orderMapper.selectOne(any())).thenReturn(source);
        when(lotteryService.placePlayerFollowBet(7L, "member-1", "20261013002", "123各1", "网页群",
                "follow-1", "execution-1")).thenReturn(Map.of("orderId", "order-2"));

        worker.execute(task.getId(), 7L);

        assertThat(task.getStatus()).isEqualTo("SUCCESS");
        assertThat(task.getOrderId()).isEqualTo("order-2");
    }

    @Test
    void neverDebitsBeforeThePreviousFormallyOpenedIssueIsSettled() {
        PlayerFollowExecutionDO task = new PlayerFollowExecutionDO();
        task.setId("execution-2");
        task.setUserId(7L);
        task.setFollowId("follow-1");
        task.setMemberId("member-1");
        task.setPeriod("20261013004");
        task.setStatus("SCHEDULED");
        IssueDO current = new IssueDO();
        current.setStatus("OPEN");
        current.setOpenedAt(LocalDateTime.now());
        IssueDO previous = new IssueDO();
        previous.setStatus("DRAWN");
        previous.setOpenedAt(LocalDateTime.now().minusMinutes(10));
        when(executionMapper.selectOne(any())).thenReturn(task);
        when(followMapper.selectOne(any())).thenReturn(follow("follow-1"));
        when(issueMapper.selectOne(any())).thenReturn(current, previous);

        worker.execute(task.getId(), 7L);

        assertThat(task.getStatus()).isEqualTo("SCHEDULED");
        assertThat(task.getError()).isEqualTo("上一期尚未派奖");
        assertThat(task.getNextAttemptAt()).isAfter(LocalDateTime.now());
        verify(lotteryService, never()).placePlayerFollowBet(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void insufficientPlayerBalanceStopsAllRoomBindingsAndNotifiesOnce() {
        PlayerFollowExecutionDO task = new PlayerFollowExecutionDO();
        task.setId("execution-1");
        task.setUserId(7L);
        task.setFollowId("follow-1");
        task.setMemberId("member-1");
        task.setPeriod("20261013002");
        task.setStatus("SCHEDULED");
        PlayerFollowDO first = follow("follow-1");
        PlayerFollowDO second = follow("follow-2");
        MemberDO member = new MemberDO();
        member.setId("member-1");
        member.setUserId(7L);
        member.setName("玩家甲");
        when(executionMapper.selectOne(any())).thenReturn(task);
        when(followMapper.selectOne(any())).thenReturn(first);
        when(followMapper.selectList(any())).thenReturn(List.of(first, second));
        when(memberMapper.selectOne(any())).thenReturn(member);
        when(robotReplyTemplate.followBalanceStopped("玩家甲")).thenReturn("@玩家甲\n余额不足，跟单已停止");

        worker.fail(task.getId(), 7L, new ServiceException(MEMBER_BALANCE_NOT_ENOUGH));

        assertThat(task.getStatus()).isEqualTo("FAILED");
        verify(followMapper, times(2)).update(isNull(), any());
        ArgumentCaptor<MessageDO> notice = ArgumentCaptor.forClass(MessageDO.class);
        verify(messageMapper).insert(notice.capture());
        assertThat(notice.getValue().getReply()).contains("余额不足，跟单已停止");
        assertThat(notice.getValue().getMemberId()).isEqualTo("member-1");
    }

    private PlayerFollowDO follow(String id) {
        PlayerFollowDO follow = new PlayerFollowDO();
        follow.setId(id);
        follow.setUserId(7L);
        follow.setMemberId("member-1");
        follow.setChannel("网页群");
        follow.setEnabled(true);
        return follow;
    }
}
