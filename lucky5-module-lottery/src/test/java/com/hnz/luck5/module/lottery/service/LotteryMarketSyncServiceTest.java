package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryMarketSyncServiceTest {

    private LotteryMarketSyncService service;

    @BeforeEach
    void setUp() {
        service = new LotteryMarketSyncService();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
    }

    @Test
    void wrapsPlainSettlementErrorsAsValidJson() {
        assertThat(service.normalizeTransitionDetail("订单正在核对，暂不能结算"))
                .isEqualTo("{\"message\":\"订单正在核对，暂不能结算\"}");
    }

    @Test
    void preservesJsonAndNormalizesBlankDetails() {
        assertThat(service.normalizeTransitionDetail("{\"confirmations\":2}"))
                .isEqualTo("{\"confirmations\":2}");
        assertThat(service.normalizeTransitionDetail(" ")).isEqualTo("{}");
    }
}
