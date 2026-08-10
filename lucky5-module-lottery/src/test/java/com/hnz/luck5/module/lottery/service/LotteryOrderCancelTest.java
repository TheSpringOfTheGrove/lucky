package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OperationLogMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LotteryOrderCancelTest {

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lottery-order-cancel-order-test"),
                OrderDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lottery-order-cancel-member-test"),
                MemberDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lottery-order-cancel-message-test"),
                MessageDO.class);
    }

    private LotteryServiceImpl service;
    private OrderMapper orderMapper;
    private MemberMapper memberMapper;
    private MessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        service = new LotteryServiceImpl();
        orderMapper = mock(OrderMapper.class);
        memberMapper = mock(MemberMapper.class);
        messageMapper = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "operationLogMapper", mock(OperationLogMapper.class));
        ReflectionTestUtils.setField(service, "balanceLedgerService", mock(LotteryBalanceLedgerService.class));
    }

    @Test
    void ownerCanCancelPendingPlayerOrderWithoutPlayerCancelSwitch() {
        OrderDO order = pendingOrder("PLAYER", "\u7f51\u9875\u7fa4");
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setName("\u73a9\u5bb6A");
        member.setUserId(142L);
        member.setBalance(new BigDecimal("50"));
        member.setTotalBet(new BigDecimal("100"));
        member.setVersion(0);
        when(orderMapper.selectById("O-1")).thenReturn(order);
        when(memberMapper.selectById("M-1")).thenReturn(member);
        when(orderMapper.update(any(OrderDO.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(memberMapper.update(any(MemberDO.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(messageMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        Map<String, Object> result = service.cancelOrder("O-1");

        assertThat(result)
                .containsEntry("status", "\u5df2\u9000\u7801")
                .containsEntry("refunded", new BigDecimal("100.00"));
        assertThat(member.getBalance()).isEqualByComparingTo("150.00");
        assertThat(member.getTotalBet()).isEqualByComparingTo("0.00");
    }

    @Test
    void ownerCannotCancelAutoProxyOrder() {
        when(orderMapper.selectById("O-1"))
                .thenReturn(pendingOrder("AUTO_PROXY", "\u81ea\u52a8\u6258"));

        assertThatThrownBy(() -> service.cancelOrder("O-1"));

        verify(memberMapper, never()).selectById(any());
    }

    private OrderDO pendingOrder(String orderType, String source) {
        OrderDO order = new OrderDO();
        order.setId("O-1");
        order.setUserId(142L);
        order.setMemberId("M-1");
        order.setMemberName("\u73a9\u5bb6A");
        order.setStatus("\u672a\u5f00\u5956");
        order.setOrderType(orderType);
        order.setSource(source);
        order.setDeliveryMode("LOCAL_ONLY");
        order.setAmount(new BigDecimal("100"));
        order.setVersion(0);
        return order;
    }
}
