package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class Wa55MarketClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesLeadingZeroFromFiveMarketFields() throws Exception {
        Wa55MarketClient client = new Wa55MarketClient();
        assertThat(client.digitFields(objectMapper.readTree("""
                {"thousand_no":0,"hundred_no":3,"ten_no":3,"one_no":5,"ball5":6}
                """))).isEqualTo("03356");
        assertThat(client.digitFields(objectMapper.readTree("""
                {"thousand_no":10,"hundred_no":3,"ten_no":3,"one_no":5,"ball5":6}
                """))).isEmpty();
        assertThat(client.normalizeDirectResult("03356")).isEqualTo("03356");
        assertThat(client.normalizeDirectResult("0")).isEmpty();
        assertThat(client.normalizeDirectResult("123456")).isEmpty();
    }

    @Test
    void credentialCipherRoundTripsOriginalV1Format() throws Exception {
        MarketCredentialService service = new MarketCredentialService();
        Field secret = MarketCredentialService.class.getDeclaredField("secret");
        secret.setAccessible(true);
        secret.set(service, "lucky5-local-market-credential-key-change-me");

        String encrypted = service.encrypt("market-password");

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted.split(":")).hasSize(4);
        assertThat(service.decrypt(encrypted)).isEqualTo("market-password");
    }
}
