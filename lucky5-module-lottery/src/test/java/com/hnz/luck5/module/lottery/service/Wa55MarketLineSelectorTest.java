package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Wa55MarketLineSelectorTest {

    @Test
    void acceptsOnlyOfficialLinesUnderConfiguredDomain() {
        URI configured = URI.create("https://f1.wy550a22.xyz/");
        String suffix = Wa55MarketLineSelector.lineSuffix(configured);

        assertThat(suffix).isEqualTo("wy550a22.xyz");
        assertThat(Wa55MarketLineSelector.candidate(configured, suffix, "f2.wy550a22.xyz"))
                .isEqualTo(URI.create("https://f2.wy550a22.xyz/"));
        assertThat(Wa55MarketLineSelector.candidate(configured, suffix, "f2.other.xyz")).isNull();
        assertThat(Wa55MarketLineSelector.candidate(configured, suffix, "127.0.0.1")).isNull();
        assertThat(Wa55MarketLineSelector.candidate(configured, suffix, "f2.wy550a22.xyz:8080")).isNull();
        assertThat(Wa55MarketLineSelector.candidate(configured, suffix, "f2.wy550a22.xyz/path")).isNull();
        assertThat(Wa55MarketLineSelector.lineSuffix(URI.create("http://127.0.0.1:8080/"))).isNull();
    }

    @Test
    void requiresTwoSuccessfulMeasurementsBeforeRankingALine() {
        assertThat(Wa55MarketLineSelector.median(List.of())).isEqualTo(Long.MAX_VALUE);
        assertThat(Wa55MarketLineSelector.median(List.of(10L))).isEqualTo(Long.MAX_VALUE);
        assertThat(Wa55MarketLineSelector.median(List.of(20L, 30L, 50L))).isEqualTo(30L);
    }
}
