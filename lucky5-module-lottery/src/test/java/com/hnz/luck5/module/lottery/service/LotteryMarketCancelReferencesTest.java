package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LotteryMarketCancelReferencesTest {

    private LotteryMarketOrderStateService service;
    private LotteryMarketRoutingService routingService;

    @BeforeEach
    void setUp() {
        service = new LotteryMarketOrderStateService();
        routingService = mock(LotteryMarketRoutingService.class);
        ReflectionTestUtils.setField(service, "marketRoutingService", routingService);
    }

    @Test
    void completeCompactReferencesProduceOneCancellationPerExternalBetId() {
        MarketRouteItemDO route = route(2, "[{\"betId\":\"bet-a\",\"count\":1},"
                + "{\"betId\":\"bet-b\",\"count\":1}]");

        List<Wa55MarketOrderClient.CancelRequest> requests = cancelReferences(route);

        assertEquals(List.of(new Wa55MarketOrderClient.CancelRequest("bet-a", 1),
                new Wa55MarketOrderClient.CancelRequest("bet-b", 1)), requests);
    }

    @Test
    void incompleteOrDuplicateCompactReferencesCannotEnableCancellation() {
        assertTrue(cancelReferences(route(2, "[{\"betId\":\"bet-a\",\"count\":1}]")).isEmpty());
        assertTrue(cancelReferences(route(2, "[{\"betId\":\"bet-a\",\"count\":1},"
                + "{\"betId\":\"bet-a\",\"count\":1}]")).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private List<Wa55MarketOrderClient.CancelRequest> cancelReferences(MarketRouteItemDO route) {
        return (List<Wa55MarketOrderClient.CancelRequest>) ReflectionTestUtils.invokeMethod(
                service, "cancelReferences", route);
    }

    private MarketRouteItemDO route(int expectedCount, String json) {
        MarketRouteItemDO route = new MarketRouteItemDO();
        route.setMarketBetCount(expectedCount);
        route.setMarketCancelReferencesJson(json);
        route.setMarketBetId("bet-a");
        when(routingService.isCompactRoute(route)).thenReturn(true);
        return route;
    }
}
