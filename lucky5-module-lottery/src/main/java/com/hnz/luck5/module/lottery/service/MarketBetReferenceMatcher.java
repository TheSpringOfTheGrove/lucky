package com.hnz.luck5.module.lottery.service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure, reusable matching policy for accepted external batches. Never guesses between identical candidates. */
final class MarketBetReferenceMatcher {

    private MarketBetReferenceMatcher() {}

    static Result match(List<Expected> expected, List<Candidate> candidates, Set<String> alreadyClaimedIds) {
        Map<String, List<Candidate>> bySerial = new LinkedHashMap<>();
        candidates.forEach(row -> bySerial.computeIfAbsent(row.serialNo(), ignored -> new ArrayList<>()).add(row));
        int claimedGroups = 0;
        int cancelledGroups = 0;
        int sameSizeGroups = 0;
        List<List<Candidate>> matches = new ArrayList<>();
        List<List<Candidate>> guidMatches = new ArrayList<>();
        String expectedGuid = expected.isEmpty() ? "" : expected.get(0).guid();
        for (List<Candidate> group : bySerial.values()) {
            if (group.stream().anyMatch(Candidate::cancelled)) {
                cancelledGroups++;
                continue;
            }
            if (group.stream().anyMatch(row -> alreadyClaimedIds.contains(row.betId()))) {
                claimedGroups++;
                continue;
            }
            if (group.size() == expected.size()) sameSizeGroups++;
            if (!matches(group, expected)) continue;
            boolean conflictingGuid = group.stream().anyMatch(row -> !row.guid().isBlank()
                    && !row.guid().equalsIgnoreCase(expectedGuid));
            if (conflictingGuid) continue;
            matches.add(group);
            if (!expectedGuid.isBlank() && group.stream().anyMatch(row -> expectedGuid.equalsIgnoreCase(row.guid()))) {
                guidMatches.add(group);
            }
        }
        List<List<Candidate>> selected = guidMatches.isEmpty() ? matches : guidMatches;
        String reason = selected.size() > 1 ? "AMBIGUOUS_MATCH"
                : sameSizeGroups == 0 ? "NO_EQUAL_SIZE_GROUP" : "CONTENT_MISMATCH";
        if (selected.size() != 1) {
            return new Result(List.of(), reason, bySerial.size(), claimedGroups, cancelledGroups,
                    sameSizeGroups, selected.size());
        }
        Map<Key, ArrayDeque<Candidate>> available = new LinkedHashMap<>();
        selected.get(0).forEach(row -> available.computeIfAbsent(key(row.type(), row.selection(), row.amount()),
                ignored -> new ArrayDeque<>()).add(row));
        List<Reference> confirmations = new ArrayList<>(expected.size());
        for (Expected request : expected) {
            Candidate row = available.get(key(request.type(), request.selection(), request.amount())).remove();
            confirmations.add(new Reference(request.routeItemId(), request.guid(), row.betId(), row.serialNo(),
                    row.betCount(), request.amount(), row.odds()));
        }
        return new Result(confirmations, reason, bySerial.size(), claimedGroups, cancelledGroups,
                sameSizeGroups, selected.size());
    }

    private static boolean matches(List<Candidate> rows, List<Expected> expected) {
        if (rows.size() != expected.size()) return false;
        Map<Key, Integer> wanted = new LinkedHashMap<>();
        Map<Key, Integer> actual = new LinkedHashMap<>();
        expected.forEach(row -> wanted.merge(key(row.type(), row.selection(), row.amount()), 1, Integer::sum));
        rows.forEach(row -> actual.merge(key(row.type(), row.selection(), row.amount()), 1, Integer::sum));
        return wanted.equals(actual);
    }

    private static Key key(int type, String selection, BigDecimal amount) {
        return new Key(type, selection == null ? "" : selection.toUpperCase(Locale.ROOT), amount.stripTrailingZeros());
    }

    record Expected(String routeItemId, String guid, int type, String selection, BigDecimal amount) {}
    record Reference(String routeItemId, String guid, String betId, String serialNo, int betCount,
                     BigDecimal amount, BigDecimal odds) {}
    record Candidate(String betId, String serialNo, String guid, int type, String selection, BigDecimal amount,
                     int betCount, BigDecimal odds, boolean cancelled) {
        Candidate {
            guid = guid == null ? "" : guid;
        }
    }
    record Result(List<Reference> confirmations, String reason,
                  int serialGroups, int claimedGroups, int cancelledGroups, int sameSizeGroups, int exactGroups) {}
    private record Key(int type, String selection, BigDecimal amount) {}
}
