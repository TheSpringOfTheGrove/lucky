package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.mysql.ChimaConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketRouteItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LotteryMarketRoutingServiceTest {

    private final ChimaConfigMapper chimaConfigMapper = mock(ChimaConfigMapper.class);
    private final MarketRouteItemMapper routeItemMapper = mock(MarketRouteItemMapper.class);
    private final LotteryMarketRoutingService service = new LotteryMarketRoutingService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "chimaConfigMapper", chimaConfigMapper);
        ReflectionTestUtils.setField(service, "routeItemMapper", routeItemMapper);
        ReflectionTestUtils.setField(service, "routingPolicy", new LotteryMarketRoutingPolicy());
        when(chimaConfigMapper.selectOne(any())).thenReturn(null);
        when(routeItemMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void previewCalculatesExternalAmountWithoutPersistingRoutes() {
        MemberDO member = new MemberDO();
        member.setEatEnabled(false);
        BetItemDO item = new BetItemDO();
        item.setPlay("二定位");
        item.setSelection("12XX");
        item.setAmount(new BigDecimal("9.00"));
        item.setOdds(new BigDecimal("96"));

        assertThat(service.previewMarketAmount(142L, "20260811266", member, List.of(item)))
                .isEqualByComparingTo("9.00");
        verify(routeItemMapper, never()).insert(any(MarketRouteItemDO.class));
    }

    @Test
    void supportsRightAlignedNumericPositionSelections() {
        assertThat(service.supportsMarket(item("一定位", "XXX1"))).isTrue();
        assertThat(service.supportsMarket(item("二定位", "XX12"))).isTrue();
        assertThat(service.supportsMarket(item("三定位", "X123"))).isTrue();
        assertThat(service.supportsMarket(item("四定位", "5874"))).isTrue();
        assertThat(service.supportsMarket(item("四条", "8888"))).isTrue();
    }

    private BetItemDO item(String play, String selection) {
        BetItemDO item = new BetItemDO();
        item.setPlay(play);
        item.setSelection(selection);
        return item;
    }
}
