package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.BetItemMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketRouteItemMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LotteryMarketManualReviewTest {

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "manual-review-order"), OrderDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "manual-review-route"), MarketRouteItemDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "manual-review-member"), MemberDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "manual-review-message"), MessageDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "manual-review-bet-item"), BetItemDO.class);
    }

    private LotteryMarketOrderStateService service;
    private OrderMapper orderMapper;
    private MarketRouteItemMapper routeMapper;
    private MemberMapper memberMapper;
    private MessageMapper messageMapper;
    private BetItemMapper betItemMapper;
    private LotteryBalanceLedgerService ledgerService;

    @BeforeEach
    void setUp() {
        service = new LotteryMarketOrderStateService();
        orderMapper = mock(OrderMapper.class);
        routeMapper = mock(MarketRouteItemMapper.class);
        memberMapper = mock(MemberMapper.class);
        messageMapper = mock(MessageMapper.class);
        betItemMapper = mock(BetItemMapper.class);
        ledgerService = mock(LotteryBalanceLedgerService.class);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "routeItemMapper", routeMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "betItemMapper", betItemMapper);
        ReflectionTestUtils.setField(service, "balanceLedgerService", ledgerService);
        ReflectionTestUtils.setField(service, "robotReplyTemplate", new LotteryRobotReplyTemplate());
    }

    @Test
    void acceptedDecisionConfirmsEveryRouteWithoutResubmitting() {
        OrderDO order = manualReviewOrder();
        MarketRouteItemDO first = route("R-1", "0.10");
        MarketRouteItemDO second = route("R-2", "0.10");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(routeMapper.selectList(any())).thenReturn(List.of(first, second));
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(routeMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(2);
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(betItemMapper.selectList(any())).thenReturn(List.of(new BetItemDO(), new BetItemDO()));
        when(messageMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        LotteryMarketOrderStateService.ManualReviewResult result =
                service.confirmManualReviewAccepted(229L, "O-1", "external-bet-1");

        assertThat(result.routeCount()).isEqualTo(2);
        assertThat(result.amount()).isEqualByComparingTo("0.20");
        assertThat(result.marketStatus()).isEqualTo("CONFIRMED");
        verify(orderMapper).update(any(), any(LambdaUpdateWrapper.class));
        verify(routeMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void notAcceptedDecisionRefundsOnlyAfterManualReviewClaim() {
        OrderDO order = manualReviewOrder();
        MemberDO member = member();
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(memberMapper.selectById("M-1")).thenReturn(member);
        when(memberMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(routeMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        LotteryMarketOrderStateService.ManualReviewResult result =
                service.confirmManualReviewNotAccepted(229L, "O-1", "owner", "盘口明细确认无此订单");

        assertThat(result.marketStatus()).isEqualTo("FAILED");
        assertThat(result.amount()).isEqualByComparingTo("0.20");
        assertThat(member.getBalance()).isEqualByComparingTo("10.20");
        verify(ledgerService).recordAppliedChange(any(), any(), any(), any(), any(), any(), any());
    }

    private OrderDO manualReviewOrder() {
        OrderDO order = new OrderDO();
        order.setId("O-1");
        order.setUserId(229L);
        order.setMemberId("M-1");
        order.setMemberName("玩家1");
        order.setPeriod("20260818240");
        order.setContent("百0123456789各0.1");
        order.setStatus("未开奖");
        order.setMarketStatus("MANUAL_REVIEW");
        order.setAmount(new BigDecimal("0.20"));
        order.setPeriodSequence(1);
        order.setVersion(0);
        return order;
    }

    private MarketRouteItemDO route(String id, String amount) {
        MarketRouteItemDO route = new MarketRouteItemDO();
        route.setId(id);
        route.setUserId(229L);
        route.setOrderId("O-1");
        route.setMarketAmount(new BigDecimal(amount));
        route.setStatus("MANUAL_REVIEW");
        return route;
    }

    private MemberDO member() {
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setUserId(229L);
        member.setName("玩家1");
        member.setBalance(new BigDecimal("10.00"));
        member.setTotalBet(new BigDecimal("0.20"));
        member.setVersion(0);
        return member;
    }
}
