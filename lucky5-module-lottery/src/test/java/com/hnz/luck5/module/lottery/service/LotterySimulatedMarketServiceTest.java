package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.SimulatedMarketAccountDO;
import com.hnz.luck5.module.lottery.dal.mysql.ChimaConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketRouteItemMapper;
import com.hnz.luck5.module.lottery.dal.mysql.SimulatedMarketAccountMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LotterySimulatedMarketServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "sim-market-account-test"),
                SimulatedMarketAccountDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "sim-market-route-test"),
                MarketRouteItemDO.class);
    }

    private LotterySimulatedMarketService service;
    private SimulatedMarketAccountMapper accountMapper;
    private MarketRouteItemMapper routeMapper;
    private ChimaConfigMapper chimaConfigMapper;

    @BeforeEach
    void setUp() {
        service = new LotterySimulatedMarketService();
        accountMapper = mock(SimulatedMarketAccountMapper.class);
        routeMapper = mock(MarketRouteItemMapper.class);
        chimaConfigMapper = mock(ChimaConfigMapper.class);
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "routeItemMapper", routeMapper);
        ReflectionTestUtils.setField(service, "chimaConfigMapper", chimaConfigMapper);
        ReflectionTestUtils.setField(service, "routingPolicy", new LotteryMarketRoutingPolicy());
    }

    @Test
    void reserveDebitsSimulatedBalanceBeforeSettlement() {
        SimulatedMarketAccountDO account = account("1000.00");
        MemberDO member = new MemberDO();
        member.setEatEnabled(false);
        BetItemDO bet = new BetItemDO();
        bet.setId("B-1");
        bet.setPlay("龙虎");
        bet.setSelection("龙");
        bet.setAmount(new BigDecimal("100"));
        bet.setOdds(new BigDecimal("2"));

        when(accountMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(account);
        when(chimaConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(routeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(accountMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        LotterySimulatedMarketService.RoutingResult result = service.reserve(7L, "O-1", "20260810207",
                member, List.of(bet));

        assertThat(result.simulatedAmount()).isEqualByComparingTo("100.00");
        assertThat(result.deliveryMode()).isEqualTo("SIMULATED_MARKET");
        assertThat(account.getBalance()).isEqualByComparingTo("900.00");
        assertThat(account.getTotalStake()).isEqualByComparingTo("100.00");
    }

    @Test
    void winningSettlementCreditsOnlyTheSimulatedMarketShare() {
        SimulatedMarketAccountDO account = account("100.00");
        MarketRouteItemDO route = new MarketRouteItemDO();
        route.setId("R-1");
        route.setUserId(7L);
        route.setOrderId("O-1");
        route.setBetItemId("B-1");
        route.setLocalAmount(new BigDecimal("4"));
        route.setSimulatedAmount(new BigDecimal("6"));
        route.setOdds(new BigDecimal("2"));
        route.setStatus("CONFIRMED");
        BetItemDO bet = new BetItemDO();
        bet.setId("B-1");
        bet.setWon(true);

        when(routeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(route));
        when(accountMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(account);
        when(routeMapper.update(any(MarketRouteItemDO.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(accountMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        LotterySimulatedMarketService.SettlementResult result = service.settle(7L, "O-1", List.of(bet));

        assertThat(result.routed()).isTrue();
        assertThat(result.localPayout()).isEqualByComparingTo("8.00");
        assertThat(result.simulatedPayout()).isEqualByComparingTo("12.00");
        assertThat(account.getBalance()).isEqualByComparingTo("112.00");
        assertThat(account.getTotalPayout()).isEqualByComparingTo("12.00");
        assertThat(route.getStatus()).isEqualTo("SETTLED");
    }

    private SimulatedMarketAccountDO account(String balance) {
        SimulatedMarketAccountDO account = new SimulatedMarketAccountDO();
        account.setId(1L);
        account.setUserId(7L);
        account.setInitialBalance(new BigDecimal("100"));
        account.setBalance(new BigDecimal(balance));
        account.setTotalStake(BigDecimal.ZERO);
        account.setTotalPayout(BigDecimal.ZERO);
        account.setTotalRefund(BigDecimal.ZERO);
        account.setVersion(0);
        return account;
    }
}
