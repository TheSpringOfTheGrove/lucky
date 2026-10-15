package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class By123DrawClientTest {

    @Test
    void parsesOnlyValidFiveBallResults() throws Exception {
        By123DrawClient client = new By123DrawClient();
        ReflectionTestUtils.setField(client, "objectMapper", new ObjectMapper());

        var draws = client.parse("""
                {"Status":1,"Data":[
                  {"PeriodNo":20261014172,"DrawNo":"8,2,1,4,3","PerDrawTime":"2026-10-14 14:20:12"},
                  {"PeriodNo":20261014173,"DrawNo":"0,0,0,0,0","PerDrawTime":"2026-10-14 14:25:12"},
                  {"PeriodNo":"bad","DrawNo":"1,2,3,4,5","PerDrawTime":"2026-10-14 14:30:12"}
                ]}
                """);

        assertThat(draws).hasSize(1);
        assertThat(draws.get(0).period()).isEqualTo("20261014172");
        assertThat(draws.get(0).result()).isEqualTo("82143");
    }
}
