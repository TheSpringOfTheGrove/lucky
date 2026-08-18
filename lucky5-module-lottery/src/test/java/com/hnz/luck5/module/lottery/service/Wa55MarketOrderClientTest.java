package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private boolean memberPrintSessionExpiredOnce;
    private boolean batchSessionExpired;
    private boolean includeSummaryRow;
    private int detailVisibleAfterCall;

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
    void reconnectsOnceWhenSessionExpiresBeforeAnyMarketWrite() {
        memberPrintSessionExpiredOnce = true;
        batchResponseWithIds = true;
        Wa55MarketOrderClient client = client(true);

        Wa55MarketOrderClient.SubmissionBatch result = client.submit(credentials(), issuePeriod, List.of(
                request("route-1", "四定位", "5874", "1", "guid-1", false),
                request("route-2", "四定位", "8888", "1", "guid-2", false)));

        assertThat(loginCalls).hasValue(2);
        assertThat(memberPrintCalls).hasValue(2);
        assertThat(betCalls).hasValue(1);
        assertThat(detailCalls).hasValue(0);
        assertThat(result.confirmations()).hasSize(2);
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
        Wa55MarketOrderClient.VerificationBatch verified = client.verifyAccepted(
                credentials(), issuePeriod, requests);
        assertThat(verified.unresolvedBatches()).isEmpty();
        assertThat(verified.confirmations()).hasSize(2);
        assertThat(betCalls).hasValue(1);
        assertThat(detailCalls).hasValue(2);
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
    void stopsBeforeBetWhenOwnerMarketPeriodDiffersFromSharedIssue() {
        memberPeriod = "20260811002";
        Wa55MarketOrderClient client = client(true);

        assertThatThrownBy(() -> client.submit(credentials(), issuePeriod,
                List.of(request("route-1", "X65X", "10", "guid-1", false))))
                .isInstanceOf(Wa55MarketOrderClient.MarketProtocolException.class)
                .hasMessageContaining("期号与系统期号不一致");
        assertThat(betCalls).hasValue(0);
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
                    "serial_no", "SERIAL-" + call,
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
                    + "\",\"Odds\":99.6}}");
            return;
        }
        json(exchange, "{\"Status\":1,\"Data\":{" +
                "\"FinalBetCount\":" + bets.size() + ",\"FinalMoney\":" + money + ",\"Odds\":99.6}}");
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
        String rows = new ObjectMapper().writeValueAsString(visibleRows);
        json(exchange, "{\"Status\":1,\"Data\":{\"PageCount\":1,\"Rows\":" + rows + "}}");
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
