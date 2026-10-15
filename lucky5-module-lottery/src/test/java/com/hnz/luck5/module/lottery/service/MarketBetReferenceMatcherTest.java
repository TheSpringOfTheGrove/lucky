package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MarketBetReferenceMatcherTest {

    @Test
    void excludesCompletePreviouslyClaimedBatchAndMatchesTheOtherIdenticalBatch() {
        var expected = List.of(expected("route-1", "1234"), expected("route-2", "5678"));
        var rows = List.of(candidate("old-1", "old", "1234"), candidate("old-2", "old", "5678"),
                candidate("new-1", "new", "1234"), candidate("new-2", "new", "5678"));

        var ambiguous = MarketBetReferenceMatcher.match(expected, rows, Set.of());
        assertThat(ambiguous.reason()).isEqualTo("AMBIGUOUS_MATCH");
        assertThat(ambiguous.confirmations()).isEmpty();

        var matched = MarketBetReferenceMatcher.match(expected, rows, Set.of("old-1", "old-2"));
        assertThat(matched.claimedGroups()).isEqualTo(1);
        assertThat(matched.confirmations()).extracting(MarketBetReferenceMatcher.Reference::betId)
                .containsExactly("new-1", "new-2");
    }

    @Test
    void neverClaimsAPartiallyKnownOrStillAmbiguousBatch() {
        var expected = List.of(expected("route-1", "1234"));
        var rows = List.of(candidate("old-1", "old", "1234"),
                candidate("new-1", "new", "1234"), candidate("new-2", "newer", "1234"));

        var result = MarketBetReferenceMatcher.match(expected, rows, Set.of("old-1"));
        assertThat(result.reason()).isEqualTo("AMBIGUOUS_MATCH");
        assertThat(result.confirmations()).isEmpty();
    }

    @Test
    void ignoresAnExplicitlyCancelledSerialGroup() {
        var expected = List.of(expected("route-1", "1234"));
        var rows = List.of(new MarketBetReferenceMatcher.Candidate("old-1", "old", "", 19, "1234",
                        BigDecimal.ONE, 1, BigDecimal.TEN, true),
                candidate("new-1", "new", "1234"));

        var result = MarketBetReferenceMatcher.match(expected, rows, Set.of());
        assertThat(result.cancelledGroups()).isEqualTo(1);
        assertThat(result.confirmations()).extracting(MarketBetReferenceMatcher.Reference::betId)
                .containsExactly("new-1");
    }

    @Test
    void usesEchoedRequestGuidToDistinguishTwoUnclaimedIdenticalBatches() {
        var expected = List.of(expected("route-1", "1234"));
        var rows = List.of(new MarketBetReferenceMatcher.Candidate("old-1", "old", "other-guid", 19,
                        "1234", BigDecimal.ONE, 1, BigDecimal.TEN, false),
                new MarketBetReferenceMatcher.Candidate("new-1", "new", "guid", 19,
                        "1234", BigDecimal.ONE, 1, BigDecimal.TEN, false));

        var result = MarketBetReferenceMatcher.match(expected, rows, Set.of());
        assertThat(result.confirmations()).extracting(MarketBetReferenceMatcher.Reference::betId)
                .containsExactly("new-1");
    }

    private static MarketBetReferenceMatcher.Expected expected(String routeId, String selection) {
        return new MarketBetReferenceMatcher.Expected(routeId, "guid", 19, selection, BigDecimal.ONE);
    }

    private static MarketBetReferenceMatcher.Candidate candidate(String betId, String serial, String selection) {
        return new MarketBetReferenceMatcher.Candidate(betId, serial, "", 19, selection, BigDecimal.ONE, 1,
                BigDecimal.TEN, false);
    }
}
