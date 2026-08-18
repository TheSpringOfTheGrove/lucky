package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketRouteItemMapper;
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
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lottery-order-cancel-route-test"),
                MarketRouteItemDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lottery-order-cancel-issue-test"),
                IssueDO.class);
    }

    private LotteryServiceImpl service;
    private OrderMapper orderMapper;
    private MemberMapper memberMapper;
    private MessageMapper messageMapper;
    private IssueMapper issueMapper;

    @BeforeEach
    void setUp() {
        service = new LotteryServiceImpl();
        orderMapper = mock(OrderMapper.class);
        memberMapper = mock(MemberMapper.class);
        messageMapper = mock(MessageMapper.class);
        issueMapper = mock(IssueMapper.class);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueFreshnessPolicy", new LotteryIssueFreshnessPolicy());
        ReflectionTestUtils.setField(service, "robotReplyTemplate", new LotteryRobotReplyTemplate());
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
        when(issueMapper.selectOne(any())).thenReturn(openIssue(order.getPeriod()));

        Map<String, Object> result = service.cancelOrder("O-1");

        assertThat(result)
                .containsEntry("status", "\u5df2\u9000\u7801")
                .containsEntry("refunded", new BigDecimal("100.00"));
        assertThat(member.getBalance()).isEqualByComparingTo("150.00");
        assertThat(member.getTotalBet()).isEqualByComparingTo("0.00");
    }

    @Test
    void ownerCanCancelPendingAutoProxyOrder() {
        OrderDO order = pendingOrder("AUTO_PROXY", "\u81ea\u52a8\u6258");
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setName("\u865a\u62df\u6258A");
        member.setUserId(142L);
        member.setBalance(BigDecimal.ZERO);
        member.setTotalBet(new BigDecimal("100"));
        member.setVersion(0);
        when(orderMapper.selectById("O-1")).thenReturn(order);
        when(memberMapper.selectById("M-1")).thenReturn(member);
        when(orderMapper.update(any(OrderDO.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(memberMapper.update(any(MemberDO.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(messageMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(issueMapper.selectOne(any())).thenReturn(openIssue(order.getPeriod()));

        Map<String, Object> result = service.cancelOrder("O-1");

        assertThat(result)
                .containsEntry("status", "\u5df2\u9000\u7801")
                .containsEntry("refunded", new BigDecimal("100.00"));
        assertThat(member.getBalance()).isEqualByComparingTo("100.00");
        assertThat(member.getTotalBet()).isEqualByComparingTo("0.00");
    }

    @Test
    void confirmedMarketOrderWithoutExternalBetIdsCannotRefundLocally() {
        OrderDO order = pendingOrder("PLAYER", "网页群");
        order.setDeliveryMode("MARKET_ADAPTER");
        order.setMarketStatus("CONFIRMED");
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setName("玩家A");
        member.setUserId(142L);
        member.setBalance(new BigDecimal("50"));
        member.setTotalBet(new BigDecimal("100"));
        member.setVersion(0);
        MarketRouteItemDO route = new MarketRouteItemDO();
        route.setOrderId("O-1");
        route.setUserId(142L);
        route.setMarketAmount(new BigDecimal("100"));
        route.setMarketBetId("");
        MarketRouteItemMapper routeMapper = mock(MarketRouteItemMapper.class);
        Wa55MarketOrderClient marketClient = mock(Wa55MarketOrderClient.class);
        ReflectionTestUtils.setField(service, "marketRouteItemMapper", routeMapper);
        ReflectionTestUtils.setField(service, "marketOrderClient", marketClient);
        when(orderMapper.selectById("O-1")).thenReturn(order);
        when(memberMapper.selectById("M-1")).thenReturn(member);
        when(marketClient.isRealWritesEnabled()).thenReturn(true);
        when(routeMapper.selectList(any())).thenReturn(java.util.List.of(route));
        when(issueMapper.selectOne(any())).thenReturn(openIssue(order.getPeriod()));

        assertThatThrownBy(() -> service.cancelOrder("O-1"))
                .hasMessageContaining("订单正在核对");

        assertThat(member.getBalance()).isEqualByComparingTo("50");
        assertThat(member.getTotalBet()).isEqualByComparingTo("100");
    }

    @Test
    void drawnOrderCancellationFailsWithoutReplacingOriginalReceipt() {
        OrderDO order = pendingOrder("PLAYER", "网页群");
        order.setPeriod("20260812132");
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setName("玩家A");
        member.setUserId(142L);
        member.setBalance(new BigDecimal("50"));
        member.setTotalBet(new BigDecimal("100"));
        member.setVersion(0);
        IssueDO issue = new IssueDO();
        issue.setPeriod(order.getPeriod());
        issue.setStatus("DRAWN");
        when(orderMapper.selectById("O-1")).thenReturn(order);
        when(memberMapper.selectById("M-1")).thenReturn(member);
        when(issueMapper.selectOne(any())).thenReturn(issue);

        assertThatThrownBy(() -> service.cancelOrder("O-1"))
                .hasMessageContaining("未开奖订单");

        verifyNoInteractions(messageMapper);
        assertThat(member.getBalance()).isEqualByComparingTo("50");
        assertThat(member.getTotalBet()).isEqualByComparingTo("100");
    }

    @Test
    void cancellationFailsAfterOfficialCutoffEvenWhenOrderIsNotSettled() {
        OrderDO order = pendingOrder("PLAYER", "网页群");
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setName("玩家A");
        member.setUserId(142L);
        member.setBalance(new BigDecimal("50"));
        member.setTotalBet(new BigDecimal("100"));
        IssueDO issue = openIssue(order.getPeriod());
        issue.setSourceObservedAt(LocalDateTime.now().minusSeconds(2));
        issue.setRemainingSeconds(1);
        when(orderMapper.selectById("O-1")).thenReturn(order);
        when(memberMapper.selectById("M-1")).thenReturn(member);
        when(issueMapper.selectOne(any())).thenReturn(issue);

        assertThatThrownBy(() -> service.cancelOrder("O-1"))
                .hasMessageContaining("未开奖订单");

        verifyNoInteractions(messageMapper);
        assertThat(member.getBalance()).isEqualByComparingTo("50");
        assertThat(member.getTotalBet()).isEqualByComparingTo("100");
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
        order.setPeriod("20260818240");
        order.setDeliveryMode("LOCAL_ONLY");
        order.setAmount(new BigDecimal("100"));
        order.setVersion(0);
        return order;
    }

    private IssueDO openIssue(String period) {
        IssueDO issue = new IssueDO();
        issue.setUserId(142L);
        issue.setPeriod(period);
        issue.setStatus("OPEN");
        issue.setServerTime(LocalDateTime.now());
        issue.setSourceObservedAt(LocalDateTime.now());
        issue.setRemainingSeconds(60);
        return issue;
    }
}
