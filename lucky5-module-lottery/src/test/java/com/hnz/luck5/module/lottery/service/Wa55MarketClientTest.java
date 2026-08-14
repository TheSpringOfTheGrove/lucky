package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

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
    void usesMarketCloseAndDrawTimesAsAuthoritativeSchedule() throws Exception {
        Wa55MarketClient client = new Wa55MarketClient();

        Wa55MarketClient.Issue issue = client.issue(objectMapper.readTree("""
                {
                  "period_no":"20260814229",
                  "open_status":0,
                  "last_seconds":99,
                  "system_db_now":"2026-08-14 19:04:24",
                  "close_datetime":"2026-08-14 19:04:30",
                  "next_open_datetime":"2026-08-14 19:05:10",
                  "next_period_no":"20260814230"
                }
                """));

        assertThat(issue.period()).isEqualTo("20260814229");
        assertThat(issue.status()).isEqualTo("OPEN");
        assertThat(issue.remainingSeconds()).isEqualTo(6);
        assertThat(issue.serverTime()).isEqualTo(LocalDateTime.of(2026, 8, 14, 19, 4, 24));
        assertThat(issue.drawTime()).isEqualTo(LocalDateTime.of(2026, 8, 14, 19, 5, 10));
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
