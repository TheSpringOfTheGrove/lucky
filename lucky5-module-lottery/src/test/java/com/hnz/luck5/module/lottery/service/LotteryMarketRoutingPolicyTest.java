package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.ChimaConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryMarketRoutingPolicyTest {

    private final LotteryMarketRoutingPolicy policy = new LotteryMarketRoutingPolicy();

    @Test
    void nonEatPlayerIsFullyRoutedToSimulation() {
        MemberDO member = new MemberDO();
        member.setEatEnabled(false);
        ChimaConfigDO config = config("100");

        List<LotteryMarketRoutingPolicy.Allocation> result = policy.allocate(member, config,
                List.of(item("B-1", "二字现", "80")), Map.of());

        assertThat(result).singleElement().satisfies(allocation -> {
            assertThat(allocation.localAmount()).isEqualByComparingTo("0.00");
            assertThat(allocation.simulatedAmount()).isEqualByComparingTo("80.00");
            assertThat(allocation.routeType()).isEqualTo("SIMULATED_MARKET");
        });
    }

    @Test
    void eatCapIsSharedByPlayWithinCurrentPeriod() {
        MemberDO member = new MemberDO();
        member.setEatEnabled(true);
        ChimaConfigDO config = config("100");

        List<LotteryMarketRoutingPolicy.Allocation> result = policy.allocate(member, config,
                List.of(item("B-1", "二字现", "60"), item("B-2", "二字现", "50")),
                Map.of("二字现", new BigDecimal("30")));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).localAmount()).isEqualByComparingTo("60.00");
        assertThat(result.get(0).simulatedAmount()).isEqualByComparingTo("0.00");
        assertThat(result.get(1).localAmount()).isEqualByComparingTo("10.00");
        assertThat(result.get(1).simulatedAmount()).isEqualByComparingTo("40.00");
        assertThat(result.get(1).routeType()).isEqualTo("MIXED");
    }

    @Test
    void zeroOrUnsupportedCapNeverSilentlyBecomesUnlimitedEat() {
        MemberDO member = new MemberDO();
        member.setEatEnabled(true);

        List<LotteryMarketRoutingPolicy.Allocation> result = policy.allocate(member, new ChimaConfigDO(),
                List.of(item("B-1", "龙虎", "50")), Map.of());

        assertThat(result.get(0).localAmount()).isEqualByComparingTo("0.00");
        assertThat(result.get(0).simulatedAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void simulatorHasNoRealMarketDependency() {
        assertThat(List.of(LotterySimulatedMarketService.class.getDeclaredFields()))
                .extracting(field -> field.getType().getSimpleName())
                .doesNotContain("LotteryMarketSyncService", "Wa55MarketClient");
    }

    private ChimaConfigDO config(String twoCurrent) {
        ChimaConfigDO config = new ChimaConfigDO();
        config.setErZiXian(new BigDecimal(twoCurrent));
        return config;
    }

    private BetItemDO item(String id, String play, String amount) {
        BetItemDO item = new BetItemDO();
        item.setId(id);
        item.setPlay(play);
        item.setSelection("12");
        item.setAmount(new BigDecimal(amount));
        item.setOdds(new BigDecimal("96"));
        return item;
    }
}
