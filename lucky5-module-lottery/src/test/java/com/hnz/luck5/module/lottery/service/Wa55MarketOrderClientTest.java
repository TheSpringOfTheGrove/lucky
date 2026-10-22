package com.hnz.luck5.module.lottery.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Wa55MarketOrderClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger loginCalls = new AtomicInteger();
    private final AtomicInteger memberPrintCalls = new AtomicInteger();
    private final AtomicInteger betCalls = new AtomicInteger();
    private final AtomicInteger detailCalls = new AtomicInteger();
    private final AtomicInteger serialLookupCalls = new AtomicInteger();
    private final List<Map<String, String>> betForms = new ArrayList<>();
    private final List<Map<String, Object>> marketRows = new ArrayList<>();
    private Map<String, String> cancelForm;
    private String memberPeriod = "20260811001";
    private String issuePeriod = "20260811001";
    private int openStatus = 0;
    private int mismatchAtCall = -1;
    private int missingIdAtCall = -1;
    private int malformedAtCall = -1;
    private int rejectAtCall = -1;
    private boolean batchResponseWithIds;
    private boolean batchResponseWithSecret;
    private boolean batchResponseWithSerialOnly;
    private int serialVisibleAfterCall;
    private boolean serialLookupWrongSelection;
    private boolean serialLookupWrongBetCount;
    private boolean memberPrintSessionExpiredOnce;
    private boolean batchSessionExpired;
    private boolean includeSummaryRow;
    private int detailVisibleAfterCall;
    private int detailPageSize = Integer.MAX_VALUE;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void submitsWithOwnerSessionAndCancelsConfirmedMarketIds() {
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                request("route-1", "X65X", "10", "guid-1", false),
                request("route-2", "65", "2.5", "guid-2", true));

        Wa55MarketOrderClient.SubmissionBatch result = client.submit(credentials(), issuePeriod, requests);

        assertThat(result.confirmations()).hasSize(2);
        assertThat(result.confirmations().get(0).marketBetId()).isEqualTo("BET-1");
        assertThat(result.confirmations().get(1).serialNo()).isEqualTo("SERIAL-2");
        assertThat(betForms.get(0)).containsEntry("betno", "X65X").containsEntry("is_xian", "0")
                .containsEntry("isxian", "0")
                .containsEntry("guid", "guid-1").containsEntry("betmoney", "10");
        assertThat(betForms.get(1)).containsEntry("betno", "65").containsEntry("is_xian", "1")
                .containsEntry("isxian", "1")
                .containsEntry("betmoney", "2.5");

        client.cancel(credentials(), issuePeriod, result.confirmations().stream()
                .map(item -> new Wa55MarketOrderClient.CancelRequest(item.marketBetId(), item.betCount())).toList());
        assertThat(cancelForm).containsEntry("period_no", issuePeriod)
                .containsEntry("ids", "BET-1|1,BET-2|1");
    }

    @Test
    void combinesCompatibleFourPositionRoutesIntoOneMarketRequest() {
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false),
                request("route-3", "四定位", "7694", "1", "guid-3", false));

        Wa55MarketOrderClient.SubmissionBatch submitted = client.submit(credentials(), issuePeriod, requests);

        assertThat(betCalls).hasValue(1);
        assertThat(betForms).singleElement().satisfies(form -> assertThat(form)
                .containsEntry("way", "108")
                .containsEntry("period_no", issuePeriod));
        assertThat(batchBets(betForms.get(0))).containsExactly(
                Map.of("dict_no_type_id", 11, "bet_no", "5874", "bet_money", "1"),
                Map.of("dict_no_type_id", 11, "bet_no", "8888", "bet_money", "1"),
                Map.of("dict_no_type_id", 11, "bet_no", "7694", "bet_money", "1"));
        assertThat(submitted.confirmations()).isEmpty();
        assertThat(submitted.acceptedBatches()).hasSize(1);
        assertThat(detailCalls).hasValue(0);

        Wa55MarketOrderClient.VerificationBatch verified = client.verifyAccepted(
                credentials(), issuePeriod, requests);
        assertThat(verified.confirmations()).hasSize(3)
                .allSatisfy(item -> {
                    assertThat(item.marketBetId()).startsWith("BET-1-");
                    assertThat(item.betCount()).isEqualTo(1);
                    assertThat(item.acceptedAmount()).isEqualByComparingTo("1");
                });
        assertThat(verified.confirmations()).extracting(Wa55MarketOrderClient.BetConfirmation::guid)
                .containsOnly(verified.confirmations().get(0).guid());

        client.cancel(credentials(), issuePeriod, verified.confirmations().stream()
                .map(item -> new Wa55MarketOrderClient.CancelRequest(item.marketBetId(), item.betCount())).toList());
        assertThat(cancelForm).containsEntry("ids", "BET-1-1|1,BET-1-2|1,BET-1-3|1");
    }

    @Test
    void acceptsExactBatchIdentifiersDirectlyWithoutWaitingForDetailRows() {
        batchResponseWithIds = true;
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false));

        Wa55MarketOrderClient.SubmissionBatch result = client.submit(credentials(), issuePeriod, requests);

        assertThat(betCalls).hasValue(1);
        assertThat(detailCalls).hasValue(0);
        assertThat(result.confirmations()).hasSize(2).allSatisfy(item -> {
            assertThat(item.marketBetId()).isEqualTo("BATCH-BET-1");
            assertThat(item.serialNo()).isEqualTo("BATCH-SERIAL-1");
            assertThat(item.betCount()).isEqualTo(2);
        });
    }

    @Test
    void logsCompleteBatchReceiptWhileRedactingAuthenticationSecrets() {
        batchResponseWithIds = true;
        batchResponseWithSecret = true;
        Logger logger = (Logger) LoggerFactory.getLogger(Wa55MarketOrderClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            client(true).submit(credentials(), issuePeriod, List.of(
                    request("route-1", "四定位", "5874", "1", "guid-1", false),
                    request("route-2", "四定位", "8888", "1", "guid-2", false)));
            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Lucky5 market batch receipt:"))
                    .toList();
            assertThat(messages).singleElement().satisfies(message -> assertThat(message)
                    .contains("itemCount=2", "\"FinalBetCount\":2", "\"FinalMoney\":2",
                            "\"BetId\":\"BATCH-BET-1\"", "\"SerialNo\":\"BATCH-SERIAL-1\"",
                            "\"AccessToken\":\"[REDACTED]\"", "\"Password\":\"[REDACTED]\"")
                    .doesNotContain("sample-token", "sample-password"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void confirmsFromSerialLookupOnFirstCompleteResponseWithoutPeriodScan() {
        batchResponseWithSerialOnly = true;
        Logger logger = (Logger) LoggerFactory.getLogger(Wa55MarketOrderClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Wa55MarketOrderClient client = client(true);
            client.setSerialLookupLogForTest(true);
            List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                    request("route-1", "四定位", "5874", "1", "guid-1", false),
                    request("route-2", "四定位", "8888", "1", "guid-2", false));
            Wa55MarketOrderClient.SubmissionBatch result = client.submit(credentials(), issuePeriod, requests);
            assertThat(result.confirmations()).isEmpty();
            assertThat(result.acceptedBatches()).hasSize(1);
            assertThat(result.acceptedBatches().get(0).serialNo()).isEqualTo("BATCH-SERIAL-1");
            Wa55MarketOrderClient.VerificationBatch verified = client.verifyAcceptedPreferSerial(
                    credentials(), issuePeriod, requests, List.of("BATCH-SERIAL-1"), java.util.Set.of());
            assertThat(verified.confirmations()).hasSize(2);
            assertThat(verified.unresolvedBatches()).isEmpty();
            assertThat(verified.lookupStrategy()).isEqualTo("SERIAL_NO");
            assertThat(verified.serialAttempts()).isEqualTo(1);
            assertThat(detailCalls).hasValue(0);
            assertThat(serialLookupCalls).hasValue(1);
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Lucky5 market serial lookup response:"))).hasSize(1)
                    .allSatisfy(message -> assertThat(message)
                            .contains("serialNo=BATCH-SERIAL-1", "\"bet_id\":\"BET-1-1\"")
                            .contains("\"AccessToken\":\"[REDACTED]\"")
                            .doesNotContain("sample-token"));
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Lucky5 market reference match success:")))
                    .singleElement().asString().contains("strategy=SERIAL_NO", "serialAttempts=1", "matched=2");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void retriesOnlyUntilSerialRowsBecomeComplete() {
        batchResponseWithSerialOnly = true;
        serialVisibleAfterCall = 1;
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false));
        client.submit(credentials(), issuePeriod, requests);

        var verified = client.verifyAcceptedPreferSerial(credentials(), issuePeriod, requests,
                List.of("BATCH-SERIAL-1"), java.util.Set.of());

        assertThat(verified.confirmations()).hasSize(2);
        assertThat(verified.lookupStrategy()).isEqualTo("SERIAL_NO");
        assertThat(verified.serialAttempts()).isEqualTo(2);
        assertThat(serialLookupCalls).hasValue(2);
        assertThat(detailCalls).hasValue(0);
    }

    @Test
    void fallsBackToExistingPeriodScanAfterThreeIncompleteSerialLookups() {
        batchResponseWithSerialOnly = true;
        serialVisibleAfterCall = Integer.MAX_VALUE;
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false));
        client.submit(credentials(), issuePeriod, requests);

        var verified = client.verifyAcceptedPreferSerial(credentials(), issuePeriod, requests,
                List.of("BATCH-SERIAL-1"), java.util.Set.of());

        assertThat(verified.confirmations()).hasSize(2);
        assertThat(verified.lookupStrategy()).isEqualTo("PERIOD_SCAN_FALLBACK");
        assertThat(verified.serialAttempts()).isEqualTo(3);
        assertThat(serialLookupCalls).hasValue(3);
        assertThat(detailCalls).hasValue(1);
        assertThat(betCalls).hasValue(1);
    }

    @Test
    void rejectsSerialRowsWithWrongSelectionBeforeFallingBack() {
        batchResponseWithSerialOnly = true;
        serialLookupWrongSelection = true;
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false));
        client.submit(credentials(), issuePeriod, requests);

        var verified = client.verifyAcceptedPreferSerial(credentials(), issuePeriod, requests,
                List.of("BATCH-SERIAL-1"), java.util.Set.of());

        assertThat(verified.confirmations()).hasSize(2);
        assertThat(serialLookupCalls).hasValue(3);
        assertThat(detailCalls).hasValue(1);
    }

    @Test
    void rejectsSerialRowsWithWrongBetCountBeforeFallingBack() {
        batchResponseWithSerialOnly = true;
        serialLookupWrongBetCount = true;
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false));
        client.submit(credentials(), issuePeriod, requests);

        var verified = client.verifyAcceptedPreferSerial(credentials(), issuePeriod, requests,
                List.of("BATCH-SERIAL-1"), java.util.Set.of());

        assertThat(verified.confirmations()).hasSize(2);
        assertThat(verified.lookupStrategy()).isEqualTo("PERIOD_SCAN_FALLBACK");
        assertThat(serialLookupCalls).hasValue(3);
        assertThat(detailCalls).hasValue(1);
    }

    @Test
    void submitsDirectlyWithoutPreflightAccountRoundTrip() {
        memberPrintSessionExpiredOnce = true;
        batchResponseWithIds = true;
        Wa55MarketOrderClient client = client(true);

        Wa55MarketOrderClient.SubmissionBatch result = client.submit(credentials(), issuePeriod, List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false)));

        assertThat(loginCalls).hasValue(1);
        assertThat(memberPrintCalls).hasValue(0);
        assertThat(betCalls).hasValue(1);
        assertThat(detailCalls).hasValue(0);
        assertThat(result.confirmations()).hasSize(2);
    }

    @Test
    void reusesBalanceRefreshSessionForTheNextBatchBet() {
        batchResponseWithIds = true;
        Wa55MarketOrderClient client = client(true);

        Wa55MarketOrderClient.AccountSnapshot balance = client.readBalance(credentials());
        client.submit(credentials(), issuePeriod, List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false)));

        assertThat(balance.balance()).isEqualByComparingTo("9999.50");
        assertThat(loginCalls).hasValue(1);
        assertThat(memberPrintCalls).hasValue(1);
        assertThat(betCalls).hasValue(1);
    }

    @Test
    void neverRetriesWhenBatchWriteResponseSaysSessionExpired() {
        batchSessionExpired = true;
        Wa55MarketOrderClient client = client(true);

        assertThatThrownBy(() -> client.submit(credentials(), issuePeriod, List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false))))
                .isInstanceOfSatisfying(Wa55MarketOrderClient.MarketProtocolException.class, ex -> {
                    assertThat(ex.submissionUncertain()).isTrue();
                    assertThat(ex.confirmations()).isEmpty();
                });
        assertThat(loginCalls).hasValue(1);
        assertThat(betCalls).hasValue(1);
        assertThat(detailCalls).hasValue(0);
    }

    @Test
    void returnsExactAcceptedBatchWithoutWaitingForDetailRows() {
        detailVisibleAfterCall = 2;
        Wa55MarketOrderClient client = client(true);

        Wa55MarketOrderClient.SubmissionBatch result = client.submit(credentials(), issuePeriod, List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false)));

        assertThat(result.confirmations()).isEmpty();
        assertThat(result.acceptedBatches()).singleElement().satisfies(batch -> {
            assertThat(batch.routeItemIds()).containsExactly("route-1", "route-2");
            assertThat(batch.betCount()).isEqualTo(2);
            assertThat(batch.acceptedAmount()).isEqualByComparingTo("2");
        });
        assertThat(betCalls).hasValue(1);
        assertThat(detailCalls).hasValue(0);
    }

    @Test
    void ignoresMemberBetListSummaryRowDuringBatchConfirmation() {
        includeSummaryRow = true;
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false));

        Wa55MarketOrderClient.SubmissionBatch submitted = client.submit(credentials(), issuePeriod, requests);
        Wa55MarketOrderClient.VerificationBatch verified = client.verifyAccepted(
                credentials(), issuePeriod, requests);

        assertThat(submitted.acceptedBatches()).hasSize(1);
        assertThat(verified.confirmations()).hasSize(2);
        assertThat(verified.unresolvedBatches()).isEmpty();
        assertThat(betCalls).hasValue(1);
        assertThat(detailCalls).hasValue(1);
    }

    @Test
    void verifiesBatchAcrossMoreThanTwentyDetailPages() {
        detailPageSize = 1;
        Wa55MarketOrderClient client = client(true);
        java.util.concurrent.ExecutorService pageExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
        client.setDetailPageExecutorForTest(pageExecutor);
        List<Wa55MarketOrderClient.BetRequest> requests = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            requests.add(request("route-" + index, "四定位", String.format("%04d", index),
                    "1", "guid-" + index, false));
        }

        try {
            Wa55MarketOrderClient.SubmissionBatch submitted = client.submit(credentials(), issuePeriod, requests);
            Wa55MarketOrderClient.VerificationBatch verified = client.verifyAccepted(
                    credentials(), issuePeriod, requests);

            assertThat(submitted.acceptedBatches()).hasSize(1);
            assertThat(verified.unresolvedBatches()).isEmpty();
            assertThat(verified.confirmations()).hasSize(21);
            assertThat(detailCalls).hasValue(21);
        } finally {
            pageExecutor.shutdownNow();
        }
    }

    @Test
    void verifiesAcceptedBatchUsingAllReportedPagesBeyondOneThousand() {
        detailPageSize = 1;
        Wa55MarketOrderClient client = client(true);
        java.util.concurrent.ExecutorService pageExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
        client.setDetailPageExecutorForTest(pageExecutor);
        List<Wa55MarketOrderClient.BetRequest> requests = new ArrayList<>();
        for (int index = 0; index < 1005; index++) {
            requests.add(request("route-" + index, "四定位", String.format("%04d", index),
                    "1", "guid-" + index, false));
        }

        try {
            assertThat(client.submit(credentials(), issuePeriod, requests).acceptedBatches()).hasSize(1);
            Wa55MarketOrderClient.VerificationBatch verified = client.verifyAccepted(
                    credentials(), issuePeriod, requests);

            assertThat(verified.unresolvedBatches()).isEmpty();
            assertThat(verified.confirmations()).hasSize(1005);
            assertThat(detailCalls).hasValue(1005);
        } finally {
            pageExecutor.shutdownNow();
        }
    }

    @Test
    void excludesIdsAlreadyBoundToAnEarlierIdenticalBatch() {
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> first = List.of(
                request("old-1", "四定位", "5874", "1", "guid-old-1", false),
                request("old-2", "四定位", "8888", "1", "guid-old-2", false));
        List<Wa55MarketOrderClient.BetRequest> second = List.of(
                request("new-1", "四定位", "5874", "1", "guid-new-1", false),
                request("new-2", "四定位", "8888", "1", "guid-new-2", false));
        client.submit(credentials(), issuePeriod, first);
        var firstIds = client.verifyAccepted(credentials(), issuePeriod, first).confirmations().stream()
                .map(Wa55MarketOrderClient.BetConfirmation::marketBetId).collect(java.util.stream.Collectors.toSet());
        client.submit(credentials(), issuePeriod, second);

        assertThat(client.verifyAccepted(credentials(), issuePeriod, second).diagnostics())
                .singleElement().satisfies(item -> assertThat(item.reason()).isEqualTo("AMBIGUOUS_MATCH"));
        var resolved = client.verifyAccepted(credentials(), issuePeriod, second, firstIds);
        assertThat(resolved.unresolvedBatches()).isEmpty();
        assertThat(resolved.confirmations()).hasSize(2)
                .allSatisfy(item -> assertThat(item.marketBetId()).startsWith("BET-2-"));
    }

    @Test
    void ignoresOnlyExplicitlyCancelledDetailRows() {
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> first = List.of(
                request("old-1", "四定位", "5874", "1", "guid-old-1", false),
                request("old-2", "四定位", "8888", "1", "guid-old-2", false));
        List<Wa55MarketOrderClient.BetRequest> second = List.of(
                request("new-1", "四定位", "5874", "1", "guid-new-1", false),
                request("new-2", "四定位", "8888", "1", "guid-new-2", false));
        client.submit(credentials(), issuePeriod, first);
        client.submit(credentials(), issuePeriod, second);
        for (int index = 0; index < 2; index++) {
            Map<String, Object> cancelled = new LinkedHashMap<>(marketRows.get(index));
            cancelled.put("is_cancel", 1);
            marketRows.set(index, cancelled);
        }

        var result = client.verifyAccepted(credentials(), issuePeriod, second);
        assertThat(result.unresolvedBatches()).isEmpty();
        assertThat(result.confirmations()).hasSize(2)
                .allSatisfy(item -> assertThat(item.marketBetId()).startsWith("BET-2-"));
    }

    @Test
    void usesEchoedGuidWhenBothIdenticalBatchesAreUnclaimed() {
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> first = List.of(
                request("old-1", "四定位", "5874", "1", "guid-old-1", false),
                request("old-2", "四定位", "8888", "1", "guid-old-2", false));
        List<Wa55MarketOrderClient.BetRequest> second = List.of(
                request("new-1", "四定位", "5874", "1", "guid-new-1", false),
                request("new-2", "四定位", "8888", "1", "guid-new-2", false));
        String firstGuid = client.submit(credentials(), issuePeriod, first).acceptedBatches().get(0).guid();
        String secondGuid = client.submit(credentials(), issuePeriod, second).acceptedBatches().get(0).guid();
        for (int index = 0; index < marketRows.size(); index++) {
            Map<String, Object> withGuid = new LinkedHashMap<>(marketRows.get(index));
            withGuid.put("guid", index < 2 ? firstGuid : secondGuid);
            marketRows.set(index, withGuid);
        }

        var result = client.verifyAccepted(credentials(), issuePeriod, second);
        assertThat(result.unresolvedBatches()).isEmpty();
        assertThat(result.confirmations()).hasSize(2)
                .allSatisfy(item -> assertThat(item.marketBetId()).startsWith("BET-2-"));
    }

    @Test
    void keepsAcceptedBatchForReadOnlyVerificationAndNeverResubmits() {
        detailVisibleAfterCall = 1;
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false));

        Wa55MarketOrderClient.SubmissionBatch submitted = client.submit(credentials(), issuePeriod, requests);

        assertThat(submitted.confirmations()).isEmpty();
        assertThat(submitted.acceptedBatches()).singleElement().satisfies(batch -> {
            assertThat(batch.routeItemIds()).containsExactly("route-1", "route-2");
            assertThat(batch.betCount()).isEqualTo(2);
            assertThat(batch.acceptedAmount()).isEqualByComparingTo("2");
        });
        Wa55MarketOrderClient.VerificationBatch unresolved = client.verifyAccepted(
                credentials(), issuePeriod, requests);
        assertThat(unresolved.confirmations()).isEmpty();
        assertThat(unresolved.unresolvedBatches()).hasSize(1);
        assertThat(unresolved.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.reason()).isEqualTo("NO_VALID_ROWS");
            assertThat(diagnostic.expectedRows()).isEqualTo(2);
            assertThat(diagnostic.rawRows()).isZero();
        });
        Wa55MarketOrderClient.VerificationBatch verified = client.verifyAccepted(
                credentials(), issuePeriod, requests);
        assertThat(verified.unresolvedBatches()).isEmpty();
        assertThat(verified.diagnostics()).isEmpty();
        assertThat(verified.confirmations()).hasSize(2);
        assertThat(betCalls).hasValue(1);
        assertThat(detailCalls).hasValue(2);
    }

    @Test
    void explainsValidDetailRowsThatDoNotMatchAcceptedSelectionsWithoutLoggingThem() {
        Wa55MarketOrderClient client = client(true);
        List<Wa55MarketOrderClient.BetRequest> requests = List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false));
        client.submit(credentials(), issuePeriod, requests);
        Map<String, Object> changed = new LinkedHashMap<>(marketRows.get(0));
        changed.put("bet_no", "1234");
        marketRows.set(0, changed);

        Wa55MarketOrderClient.VerificationBatch result = client.verifyAccepted(
                credentials(), issuePeriod, requests);

        assertThat(result.confirmations()).isEmpty();
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.reason()).isEqualTo("CONTENT_MISMATCH");
            assertThat(diagnostic.rawRows()).isEqualTo(2);
            assertThat(diagnostic.validRows()).isEqualTo(2);
            assertThat(diagnostic.sameSizeGroups()).isEqualTo(1);
            assertThat(diagnostic.exactGroups()).isZero();
            assertThat(diagnostic.toString()).doesNotContain("1234", "5874", "8888", "BET-");
        });
    }

    @Test
    void cancelsUsingOriginalOrderPeriodAfterMarketHasAdvanced() {
        Wa55MarketOrderClient client = client(true);
        String originalPeriod = issuePeriod;
        memberPeriod = "20260811002";
        issuePeriod = "20260811002";

        client.cancel(credentials(), originalPeriod,
                List.of(new Wa55MarketOrderClient.CancelRequest("BET-OLD", 2)));

        assertThat(cancelForm).containsEntry("period_no", originalPeriod)
                .containsEntry("ids", "BET-OLD|2");
    }

    @Test
    void refusesAnyNetworkWriteWhenSafetySwitchIsOff() {
        Wa55MarketOrderClient client = client(false);

        assertThatThrownBy(() -> client.submit(credentials(), issuePeriod,
                List.of(request("route-1", "X65X", "10", "guid-1", false))))
                .isInstanceOf(Wa55MarketOrderClient.MarketProtocolException.class)
                .hasMessageContaining("写入开关未开启");
        assertThat(betCalls).hasValue(0);
    }

    @Test
    void submitsDirectlyWhenSharedIssueWasAlreadyValidatedLocally() {
        memberPeriod = "20260811002";
        Wa55MarketOrderClient client = client(true);

        client.submit(credentials(), issuePeriod,
                List.of(request("route-1", "X65X", "10", "guid-1", false)));
        assertThat(betCalls).hasValue(1);
    }

    @Test
    void preservesEarlierConfirmationsWhenLaterResponseAmountIsUnexpected() {
        mismatchAtCall = 2;
        Wa55MarketOrderClient client = client(true);

        try {
            client.submit(credentials(), issuePeriod, List.of(
                    request("route-1", "X65X", "10", "guid-1", false),
                    request("route-2", "X66X", "11", "guid-2", false)));
        } catch (Wa55MarketOrderClient.MarketProtocolException ex) {
            assertThat(ex.confirmations()).singleElement()
                    .satisfies(item -> assertThat(item.routeItemId()).isEqualTo("route-1"));
            assertThat(ex.retryable()).isFalse();
            assertThat(ex.submissionUncertain()).isTrue();
            return;
        }
        throw new AssertionError("expected market amount mismatch");
    }

    @Test
    void treatsSingleSuccessWithoutMarketIdentifierAsUncertainAndNeverSafeToRefund() {
        missingIdAtCall = 1;
        Wa55MarketOrderClient client = client(true);

        assertThatThrownBy(() -> client.submit(credentials(), issuePeriod,
                List.of(request("route-1", "X65X", "10", "guid-1", false))))
                .isInstanceOfSatisfying(Wa55MarketOrderClient.MarketProtocolException.class, ex -> {
                    assertThat(ex.submissionUncertain()).isTrue();
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex.confirmations()).isEmpty();
                });
        assertThat(betCalls).hasValue(1);
    }

    @Test
    void treatsUnreadableBetResponseAsUncertainAndNeverRetriesBlindly() {
        malformedAtCall = 1;
        Wa55MarketOrderClient client = client(true);

        assertThatThrownBy(() -> client.submit(credentials(), issuePeriod,
                List.of(request("route-1", "X65X", "10", "guid-1", false))))
                .isInstanceOfSatisfying(Wa55MarketOrderClient.MarketProtocolException.class, ex -> {
                    assertThat(ex.submissionUncertain()).isTrue();
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex.confirmations()).isEmpty();
                });
        assertThat(betCalls).hasValue(1);
    }

    @Test
    void neverRefundsWhenAnIdlessAcceptedBatchPrecedesALaterRejectedGroup() {
        rejectAtCall = 2;
        Wa55MarketOrderClient client = client(true);

        assertThatThrownBy(() -> client.submit(credentials(), issuePeriod, List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false),
                request("route-3", "二定位", "X12X", "1", "guid-3", false))))
                .isInstanceOfSatisfying(Wa55MarketOrderClient.MarketProtocolException.class, ex -> {
                    assertThat(ex.submissionUncertain()).isTrue();
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex.confirmations()).isEmpty();
                });
        assertThat(betCalls).hasValue(2);
    }

    @Test
    void preservesEarlierConfirmationsWhenLaterItemIsExplicitlyRejected() {
        rejectAtCall = 2;
        Wa55MarketOrderClient client = client(true);

        assertThatThrownBy(() -> client.submit(credentials(), issuePeriod, List.of(
                request("route-1", "X65X", "10", "guid-1", false),
                request("route-2", "X66X", "11", "guid-2", false))))
                .isInstanceOfSatisfying(Wa55MarketOrderClient.MarketProtocolException.class, ex -> {
                    assertThat(ex.confirmations()).singleElement()
                            .satisfies(item -> assertThat(item.routeItemId()).isEqualTo("route-1"));
                    assertThat(ex.retryable()).isFalse();
                });
        assertThat(betCalls).hasValue(2);
    }

    private Wa55MarketOrderClient client(boolean enabled) {
        Wa55MarketOrderClient client = new Wa55MarketOrderClient();
        client.setObjectMapperForTest(new ObjectMapper());
        client.setRealWritesEnabledForTest(enabled);
        return client;
    }

    private Wa55MarketOrderClient.Credentials credentials() {
        return new Wa55MarketOrderClient.Credentials(baseUrl, "owner-account", "owner-password");
    }

    private Wa55MarketOrderClient.BetRequest request(String routeId, String selection, String amount,
                                                     String guid, boolean xian) {
        return request(routeId, xian ? "二字现" : "二定位", selection, amount, guid, xian);
    }

    private Wa55MarketOrderClient.BetRequest request(String routeId, String play, String selection, String amount,
                                                     String guid, boolean xian) {
        return new Wa55MarketOrderClient.BetRequest(routeId, issuePeriod, play,
                selection, new BigDecimal(amount), guid, xian);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/" -> html(exchange, "<a href='/Member/Login?line=1'>login</a>");
            case "/Member/Login" -> html(exchange, "login");
            case "/Member/DoLogin" -> {
                loginCalls.incrementAndGet();
                json(exchange, "{\"Status\":1,\"Data\":{}}");
            }
            case "/Member/SysNotice", "/App/Index" -> html(exchange, "ok");
            case "/Member/GetMemberPrint" -> handleMemberPrint(exchange);
            case "/drawno/GetCurrentPeriodStatus" -> json(exchange, "{\"Status\":1,"
                    + "\"PERIOD_NO\":\"" + issuePeriod + "\",\"OPEN_STATUS\":" + openStatus + "}");
            case "/Member/GetMemberBetList" -> handleMemberBetList(exchange);
            case "/Member/GetBetsBySerialNo" -> handleSerialLookup(exchange);
            case "/Member/Bet" -> handleBet(exchange);
            case "/Member/BatchBet" -> handleBatchBet(exchange);
            case "/Member/CancelMemberBet" -> {
                cancelForm = form(exchange);
                json(exchange, "{\"Status\":1,\"Data\":\"退码成功\"}");
            }
            default -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        }
    }

    private void handleMemberPrint(HttpExchange exchange) throws IOException {
        int call = memberPrintCalls.incrementAndGet();
        if (memberPrintSessionExpiredOnce && call == 1) {
            json(exchange, "{\"Status\":0,\"Data\":\"您的帐号已在别处登录。\"}");
            return;
        }
        json(exchange, "{\"Status\":1,\"Data\":{" +
                "\"period_no\":\"" + memberPeriod + "\",\"credit_balance\":9999.50}}");
    }

    private void handleBet(HttpExchange exchange) throws IOException {
        int call = betCalls.incrementAndGet();
        Map<String, String> form = form(exchange);
        betForms.add(form);
        if (call == rejectAtCall) {
            json(exchange, "{\"Status\":0,\"Data\":\"余额不足\"}");
            return;
        }
        if (call == malformedAtCall) {
            json(exchange, "not-json");
            return;
        }
        if (call == missingIdAtCall) {
            int count = form.get("betno").split(",").length;
            BigDecimal accepted = new BigDecimal(form.get("betmoney")).multiply(BigDecimal.valueOf(count));
            json(exchange, "{\"Status\":1,\"Data\":{\"BetCount\":" + count + ",\"Money\":"
                    + accepted + ",\"Odds\":99.6}}");
            return;
        }
        int count = form.get("betno").split(",").length;
        BigDecimal accepted = new BigDecimal(form.get("betmoney")).multiply(BigDecimal.valueOf(count));
        String money = call == mismatchAtCall ? "0" : accepted.toPlainString();
        json(exchange, "{\"Status\":1,\"Data\":{" +
                "\"BetId\":\"BET-" + call + "\",\"SerialNo\":\"SERIAL-" + call + "\"," +
                "\"BetCount\":" + count + ",\"Money\":" + money + ",\"Odds\":99.6}}");
    }

    private void handleBatchBet(HttpExchange exchange) throws IOException {
        int call = betCalls.incrementAndGet();
        Map<String, String> form = form(exchange);
        betForms.add(form);
        if (batchSessionExpired) {
            json(exchange, "{\"Status\":0,\"Data\":\"您的帐号已在别处登录。\"}");
            return;
        }
        List<Map<String, Object>> bets = batchBets(form);
        if (call == rejectAtCall) {
            json(exchange, "{\"Status\":0,\"Data\":\"余额不足\"}");
            return;
        }
        if (call == malformedAtCall) {
            json(exchange, "not-json");
            return;
        }
        BigDecimal accepted = bets.stream().map(item -> new BigDecimal(String.valueOf(item.get("bet_money"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (int i = 0; i < bets.size(); i++) {
            Map<String, Object> bet = bets.get(i);
            marketRows.add(Map.of(
                    "bet_id", "BET-" + call + "-" + (i + 1),
                    "serial_no", (batchResponseWithSerialOnly ? "BATCH-SERIAL-" : "SERIAL-") + call,
                    "dict_no_type_id", bet.get("dict_no_type_id"),
                    "bet_no", bet.get("bet_no"),
                    "bet_money", bet.get("bet_money"),
                    "BetCount", 1,
                    "odds", 99.6));
        }
        String money = call == mismatchAtCall ? "0" : accepted.toPlainString();
        if (call == missingIdAtCall) {
            json(exchange, "{\"Status\":1,\"Data\":{\"FinalBetCount\":" + bets.size()
                    + ",\"FinalMoney\":" + money + ",\"Odds\":99.6}}");
            return;
        }
        if (batchResponseWithIds) {
            json(exchange, "{\"Status\":1,\"Data\":{" +
                    "\"FinalBetCount\":" + bets.size() + ",\"FinalMoney\":" + money + "," +
                    "\"BetId\":\"BATCH-BET-" + call + "\",\"SerialNo\":\"BATCH-SERIAL-" + call
                    + "\",\"Odds\":99.6" + (batchResponseWithSecret
                    ? ",\"Meta\":{\"AccessToken\":\"sample-token\",\"Password\":\"sample-password\"}" : "")
                    + "}}");
            return;
        }
        if (batchResponseWithSerialOnly) {
            json(exchange, "{\"Status\":1,\"Data\":{\"FinalBetCount\":" + bets.size()
                    + ",\"FinalMoney\":" + money + ",\"SerialNo\":\"BATCH-SERIAL-" + call + "\"}}");
            return;
        }
        json(exchange, "{\"Status\":1,\"Data\":{" +
                "\"FinalBetCount\":" + bets.size() + ",\"FinalMoney\":" + money + ",\"Odds\":99.6}}");
    }

    private void handleSerialLookup(HttpExchange exchange) throws IOException {
        int call = serialLookupCalls.incrementAndGet();
        String serial = "";
        for (String parameter : exchange.getRequestURI().getRawQuery().split("&")) {
            if (parameter.startsWith("serial_no=")) {
                serial = URLDecoder.decode(parameter.substring(10), StandardCharsets.UTF_8);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (call > serialVisibleAfterCall) {
            for (Map<String, Object> marketRow : marketRows) {
                if (!serial.equals(marketRow.get("serial_no"))) continue;
                rows.add(Map.of("bet_id", marketRow.get("bet_id"),
                        "bet_no", serialLookupWrongSelection ? "9999" : marketRow.get("bet_no"),
                        "bet_money", marketRow.get("bet_money"),
                        "dict_no_type_id", marketRow.get("dict_no_type_id"),
                        "BetCount", serialLookupWrongBetCount ? 2 : 1,
                        "AccessToken", "sample-token"));
            }
        }
        json(exchange, new ObjectMapper().writeValueAsString(Map.of("Status", 1, "Data", rows)));
    }

    private void handleMemberBetList(HttpExchange exchange) throws IOException {
        int call = detailCalls.incrementAndGet();
        List<Map<String, Object>> visibleRows = new ArrayList<>();
        if (call > detailVisibleAfterCall) visibleRows.addAll(marketRows);
        if (includeSummaryRow) {
            visibleRows.add(Map.of(
                    "bet_id", "SUMMARY", "serial_no", "SERIAL-1", "bet_no", -1,
                    "bet_money", "999", "BetCount", 1, "odds", 0));
        }
        int pages = visibleRows.isEmpty() ? 1 : 1 + (visibleRows.size() - 1) / detailPageSize;
        int page = 1;
        for (String parameter : exchange.getRequestURI().getRawQuery().split("&")) {
            if (parameter.startsWith("pageindex=")) page = Integer.parseInt(parameter.substring(10));
        }
        int start = (page - 1) * detailPageSize;
        List<Map<String, Object>> current = start >= visibleRows.size() ? List.of()
                : visibleRows.subList(start, Math.min(visibleRows.size(), start + detailPageSize));
        String rows = new ObjectMapper().writeValueAsString(current);
        json(exchange, "{\"Status\":1,\"Data\":{\"PageCount\":" + pages + ",\"Rows\":" + rows + "}}");
    }

    private List<Map<String, Object>> batchBets(Map<String, String> form) {
        try {
            return new ObjectMapper().readValue(form.get("bets"), new TypeReference<>() {});
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private Map<String, String> form(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    private void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void html(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
