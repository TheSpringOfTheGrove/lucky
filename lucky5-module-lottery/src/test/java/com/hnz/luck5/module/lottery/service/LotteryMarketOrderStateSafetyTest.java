package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.MarketRouteItemMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryMarketOrderStateSafetyTest {

    @Mock private OrderMapper orderMapper;
    @Mock private MarketRouteItemMapper routeItemMapper;
    @InjectMocks private LotteryMarketOrderStateService stateService;

    @Test
    void neverConfirmsManualReviewFromOnlyALocalGuid() {
        OrderDO order = new OrderDO();
        order.setStatus("未开奖");
        order.setMarketStatus("MANUAL_REVIEW");
        order.setMarketError("盘口提交结果不确定：帐号已在别处登录");
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThat(stateService.confirmAcceptedDetailsWithoutIdentifiers(1L, "order-1")).isFalse();
        verifyNoInteractions(routeItemMapper);
    }

    @Test
    void verifyingWithoutExactAcceptanceEvidenceIsNotConfirmed() {
        OrderDO order = new OrderDO();
        order.setStatus("未开奖");
        order.setMarketStatus("VERIFYING");
        order.setMarketError("盘口提交结果不确定");
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThat(stateService.confirmAcceptedDetailsWithoutIdentifiers(1L, "order-1")).isFalse();
        verifyNoInteractions(routeItemMapper);
    }
}
