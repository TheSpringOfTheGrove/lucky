package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final AtomicInteger betCalls = new AtomicInteger();
    private final List<Map<String, String>> betForms = new ArrayList<>();
    private Map<String, String> cancelForm;
    private String memberPeriod = "20260811001";
    private String issuePeriod = "20260811001";
    private int openStatus = 0;
    private int mismatchAtCall = -1;
    private int missingIdAtCall = -1;
    private int malformedAtCall = -1;
    private int rejectAtCall = -1;

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
        assertThat(betForms.get(0)).containsEntry("betno", "X65X").containsEntry("isxian", "0")
                .containsEntry("guid", "guid-1").containsEntry("betmoney", "10");
        assertThat(betForms.get(1)).containsEntry("betno", "65").containsEntry("isxian", "1")
                .containsEntry("betmoney", "2.5");

        client.cancel(credentials(), issuePeriod, result.confirmations().stream()
                .map(item -> new Wa55MarketOrderClient.CancelRequest(item.marketBetId(), item.betCount())).toList());
        assertThat(cancelForm).containsEntry("period_no", issuePeriod)
                .containsEntry("ids", "BET-1|1,BET-2|1");
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
                    request("route-2", "X66X", "10", "guid-2", false)));
        } catch (Wa55MarketOrderClient.MarketProtocolException ex) {
            assertThat(ex.confirmations()).hasSize(2);
            assertThat(ex.confirmations().get(0).routeItemId()).isEqualTo("route-1");
            assertThat(ex.confirmations().get(1).routeItemId()).isEqualTo("route-2");
            assertThat(ex.retryable()).isFalse();
            assertThat(ex.submissionUncertain()).isTrue();
            return;
        }
        throw new AssertionError("expected market amount mismatch");
    }

    @Test
    void treatsSuccessWithoutMarketIdentifierAsUncertainAndNeverSafeToRefund() {
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
    void preservesEarlierConfirmationsWhenLaterItemIsExplicitlyRejected() {
        rejectAtCall = 2;
        Wa55MarketOrderClient client = client(true);

        assertThatThrownBy(() -> client.submit(credentials(), issuePeriod, List.of(
                request("route-1", "X65X", "10", "guid-1", false),
                request("route-2", "X66X", "10", "guid-2", false))))
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
        return new Wa55MarketOrderClient.BetRequest(routeId, issuePeriod, xian ? "二字现" : "二定位",
                selection, new BigDecimal(amount), guid, xian);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/" -> html(exchange, "<a href='/Member/Login?line=1'>login</a>");
            case "/Member/Login" -> html(exchange, "login");
            case "/Member/DoLogin" -> json(exchange, "{\"Status\":1,\"Data\":{}}");
            case "/Member/SysNotice", "/App/Index" -> html(exchange, "ok");
            case "/Member/GetMemberPrint" -> json(exchange, "{\"Status\":1,\"Data\":{"
                    + "\"period_no\":\"" + memberPeriod + "\",\"credit_balance\":9999.50}}");
            case "/drawno/GetCurrentPeriodStatus" -> json(exchange, "{\"Status\":1,"
                    + "\"PERIOD_NO\":\"" + issuePeriod + "\",\"OPEN_STATUS\":" + openStatus + "}");
            case "/Member/Bet" -> handleBet(exchange);
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
            json(exchange, "{\"Status\":1,\"Data\":{\"BetCount\":1,\"Money\":"
                    + form.get("betmoney") + ",\"Odds\":99.6}}");
            return;
        }
        String money = call == mismatchAtCall ? "0" : form.get("betmoney");
        json(exchange, "{\"Status\":1,\"Data\":{" +
                "\"BetId\":\"BET-" + call + "\",\"SerialNo\":\"SERIAL-" + call + "\"," +
                "\"BetCount\":1,\"Money\":" + money + ",\"Odds\":99.6}}");
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
