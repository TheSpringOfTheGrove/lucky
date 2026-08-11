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
    void routesEverythingToOwnerMarketWhenPlayerDoesNotEat() {
        MemberDO member = member(false);

        List<LotteryMarketRoutingPolicy.Allocation> result = policy.allocate(member, config("100"),
                List.of(item("二定位", "X65X", "20")), Map.of());

        assertThat(result).singleElement().satisfies(allocation -> {
            assertThat(allocation.localAmount()).isEqualByComparingTo("0.00");
            assertThat(allocation.marketAmount()).isEqualByComparingTo("20.00");
            assertThat(allocation.routeType()).isEqualTo("REAL_MARKET");
        });
    }

    @Test
    void sharesOwnerEatCapAcrossExistingAndCurrentItemsThenRoutesOverflowToMarket() {
        MemberDO member = member(true);
        List<BetItemDO> items = List.of(
                item("二定位", "X65X", "30"),
                item("二定位", "X66X", "40"));

        List<LotteryMarketRoutingPolicy.Allocation> result = policy.allocate(member, config("100"), items,
                Map.of("二定位", new BigDecimal("50")));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).localAmount()).isEqualByComparingTo("30.00");
        assertThat(result.get(0).marketAmount()).isEqualByComparingTo("0.00");
        assertThat(result.get(1).localAmount()).isEqualByComparingTo("20.00");
        assertThat(result.get(1).marketAmount()).isEqualByComparingTo("20.00");
        assertThat(result.get(1).routeType()).isEqualTo("MIXED_REAL");
    }

    @Test
    void zeroCapNeverMeansUnlimitedLocalEat() {
        MemberDO member = member(true);

        List<LotteryMarketRoutingPolicy.Allocation> result = policy.allocate(member, config("0"),
                List.of(item("二定位", "X65X", "20")), Map.of());

        assertThat(result).singleElement().satisfies(allocation -> {
            assertThat(allocation.localAmount()).isEqualByComparingTo("0.00");
            assertThat(allocation.marketAmount()).isEqualByComparingTo("20.00");
        });
    }

    private MemberDO member(boolean eatEnabled) {
        MemberDO member = new MemberDO();
        member.setEatEnabled(eatEnabled);
        return member;
    }

    private ChimaConfigDO config(String twoPositionCap) {
        ChimaConfigDO config = new ChimaConfigDO();
        config.setErDingWei(new BigDecimal(twoPositionCap));
        return config;
    }

    private BetItemDO item(String play, String selection, String amount) {
        BetItemDO item = new BetItemDO();
        item.setPlay(play);
        item.setSelection(selection);
        item.setAmount(new BigDecimal(amount));
        return item;
    }
}
