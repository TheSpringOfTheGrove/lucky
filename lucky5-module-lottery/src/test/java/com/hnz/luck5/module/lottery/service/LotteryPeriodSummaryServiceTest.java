package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryPeriodSummaryServiceTest {

    @Mock private OrderMapper orderMapper;
    @Mock private MessageMapper messageMapper;

    private LotteryPeriodSummaryService service;

    @BeforeEach
    void setUp() {
        service = new LotteryPeriodSummaryService();
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "bettingService", new LotteryBettingService());
        ReflectionTestUtils.setField(service, "robotReplyTemplate", new LotteryRobotReplyTemplate());
    }

    @Test
    void shouldPublishOwnerWideGroupSummaryAndMemberOnlyPrivateSummaries() {
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order("O1", "M1", "露露", "0759三定各1,0759三定各1"),
                order("O2", "M2", "旺旺", "5874各2")));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.insert(any(MessageDO.class))).thenReturn(1);

        service.publish(7L, "20260809194");

        ArgumentCaptor<MessageDO> captor = ArgumentCaptor.forClass(MessageDO.class);
        verify(messageMapper, times(3)).insert(captor.capture());
        List<MessageDO> messages = captor.getAllValues();
        MessageDO group = messages.stream().filter(message -> "网页群".equals(message.getChannel())).findFirst()
                .orElseThrow();
        assertThat(group.getReply()).isEqualTo("本期成功订单\n[露露]0759三定各1\n[露露]0759三定各1"
                + "\n[旺旺]5874各2\n------------");
        assertThat(messages.stream().filter(message -> "网页私聊".equals(message.getChannel())))
                .extracting(MessageDO::getMemberId, MessageDO::getReply)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("M1", "本期成功订单\n[露露]0759三定各1"
                                + "\n[露露]0759三定各1\n------------"),
                        org.assertj.core.groups.Tuple.tuple("M2", "本期成功订单\n[旺旺]5874各2\n------------"));
    }

    @Test
    void shouldClearExistingSummaryWhenNoOrderWasActuallyAccepted() {
        MessageDO stale = new MessageDO();
        stale.setExternalId("period-summary:g:20260809194");
        stale.setReply("本期成功订单\n[露露]0759三定各1\n------------");
        when(messageMapper.selectList(any())).thenReturn(List.of(stale));
        when(orderMapper.selectList(any())).thenReturn(List.of());

        service.publish(7L, "20260809194");

        verify(messageMapper).updateById(stale);
        assertThat(stale.getReply()).isEqualTo("本期成功订单\n------------");
    }

    private OrderDO order(String id, String memberId, String memberName, String content) {
        OrderDO order = new OrderDO();
        order.setId(id);
        order.setMemberId(memberId);
        order.setMemberName(memberName);
        order.setContent(content);
        order.setStatus("未开奖");
        return order;
    }
}
