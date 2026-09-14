package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.mysql.ChimaConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketRouteItemMapper;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LotteryMarketRoutingServiceTest {

    private final ChimaConfigMapper chimaConfigMapper = mock(ChimaConfigMapper.class);
    private final MarketRouteItemMapper routeItemMapper = mock(MarketRouteItemMapper.class);
    private final LotteryBatchInsertService batchInsertService = mock(LotteryBatchInsertService.class);
    private final LotteryMarketRoutingService service = new LotteryMarketRoutingService();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        ReflectionTestUtils.setField(service, "chimaConfigMapper", chimaConfigMapper);
        ReflectionTestUtils.setField(service, "routeItemMapper", routeItemMapper);
        ReflectionTestUtils.setField(service, "routingPolicy", new LotteryMarketRoutingPolicy());
        ReflectionTestUtils.setField(service, "batchInsertService", batchInsertService);
        when(chimaConfigMapper.selectOne(any())).thenReturn(null);
        when(routeItemMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
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

    @Test
    void expandsA4967SelectionCompactRouteWithinOneSecondWithoutDatabaseWork() {
        MarketRouteItemDO route = new MarketRouteItemDO();
        route.setId("route-1");
        route.setPeriod("20260914247");
        route.setSnapshotJson(compactRouteJson(4967));
        long startedAt = System.nanoTime();

        List<Wa55MarketOrderClient.BetRequest> requests = service.expandMarketRequests(List.of(route));

        assertThat(requests).hasSize(4967);
        assertThat((System.nanoTime() - startedAt) / 1_000_000L).isLessThan(1_000);
        verifyNoInteractions(routeItemMapper);
    }

    @Test
    void persistsOneCompactRouteForA4967SelectionCommandWithinOneSecond() {
        MemberDO member = new MemberDO();
        member.setEatEnabled(false);
        List<BetItemDO> items = new ArrayList<>(4_967);
        for (int index = 0; index < 4_967; index++) {
            BetItemDO item = item("四定位", String.format("%04d", index % 10_000));
            item.setId("bet-" + index);
            item.setAmount(new BigDecimal("0.30"));
            item.setOdds(new BigDecimal("96"));
            items.add(item);
        }
        long startedAt = System.nanoTime();

        LotteryMarketRoutingService.RoutingResult result = service.prepareCompact(142L, "order-1", "20260914247",
                member, items);

        assertThat(result.marketAmount()).isEqualByComparingTo("1490.10");
        assertThat((System.nanoTime() - startedAt) / 1_000_000L).isLessThan(1_000);
        @SuppressWarnings("unchecked")
        var routes = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(batchInsertService).insertMarketRoutes(eq(1L), routes.capture());
        assertThat((List<MarketRouteItemDO>) routes.getValue()).singleElement().satisfies(route -> {
            assertThat(route.getSnapshotJson()).isNotBlank();
            assertThat(route.getMarketAmount()).isEqualByComparingTo("1490.10");
        });
    }

    private String compactRouteJson(int count) {
        List<Map<String, Object>> routes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            routes.add(Map.of("play", "四定位", "selection", String.format("%04d", index % 10_000),
                    "localAmount", BigDecimal.ZERO, "marketAmount", new BigDecimal("0.30"),
                    "odds", new BigDecimal("96"), "guid", "guid-" + index));
        }
        return JSONUtil.toJsonStr(Map.of("version", 1, "routes", routes));
    }

    private BetItemDO item(String play, String selection) {
        BetItemDO item = new BetItemDO();
        item.setPlay(play);
        item.setSelection(selection);
        return item;
    }
}
