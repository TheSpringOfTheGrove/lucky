package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Wa55MarketSessionReuseTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger loginCalls = new AtomicInteger();
    private final AtomicInteger accountCalls = new AtomicInteger();
    private volatile String validSession = "";
    private volatile boolean expireNextAccountRead;
    private volatile boolean malformedNextAccountRead;

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
    void reusesAuthenticatedCookieForRepeatedBalanceReads() {
        Wa55MarketClient client = client();

        client.read(credentials(), false);
        client.read(credentials(), false);

        assertThat(loginCalls).hasValue(1);
        assertThat(accountCalls).hasValue(2);
    }

    @Test
    void logsInOnceAfterTheCachedSessionIsExplicitlyExpired() {
        Wa55MarketClient client = client();
        client.read(credentials(), false);
        expireNextAccountRead = true;

        Wa55MarketClient.Snapshot refreshed = client.read(credentials(), false);

        assertThat(refreshed.account().balance()).isEqualByComparingTo("8888.50");
        assertThat(loginCalls).hasValue(2);
        assertThat(accountCalls).hasValue(3);
    }

    @Test
    void doesNotReloginForAnUnreadableNonLoginResponse() {
        Wa55MarketClient client = client();
        client.read(credentials(), false);
        malformedNextAccountRead = true;

        assertThatThrownBy(() -> client.read(credentials(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法解析");
        assertThat(loginCalls).hasValue(1);
    }

    private Wa55MarketClient client() {
        Wa55MarketClient client = new Wa55MarketClient();
        client.setObjectMapperForTest(new ObjectMapper());
        return client;
    }

    private Wa55MarketClient.Credentials credentials() {
        return new Wa55MarketClient.Credentials(baseUrl, "owner-account", "owner-password");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/" -> html(exchange, "<a href='/Member/Login?line=1'>login</a>");
            case "/Member/Login" -> html(exchange, "login");
            case "/Member/DoLogin" -> login(exchange);
            case "/Member/SysNotice", "/App/Index" -> html(exchange, "ok");
            case "/Member/GetMemberPrint" -> account(exchange);
            case "/drawno/GetCurrentPeriodStatus" -> authenticatedJson(exchange,
                    "{\"Status\":1,\"PERIOD_NO\":\"20260817001\",\"OPEN_STATUS\":0}");
            default -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        validSession = "session-" + loginCalls.incrementAndGet();
        exchange.getResponseHeaders().add("Set-Cookie", "MARKET_SESSION=" + validSession + "; Path=/");
        json(exchange, "{\"Status\":1,\"Data\":{}}");
    }

    private void account(HttpExchange exchange) throws IOException {
        accountCalls.incrementAndGet();
        if (expireNextAccountRead) {
            expireNextAccountRead = false;
            json(exchange, "{\"Status\":5,\"Data\":\"请登录\"}");
            return;
        }
        if (malformedNextAccountRead) {
            malformedNextAccountRead = false;
            html(exchange, "temporary upstream response");
            return;
        }
        authenticatedJson(exchange, "{\"Status\":1,\"member_account\":\"owner\"," +
                "\"credit_balance\":8888.50}");
    }

    private void authenticatedJson(HttpExchange exchange, String body) throws IOException {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || !cookie.contains("MARKET_SESSION=" + validSession)) {
            json(exchange, "{\"Status\":5,\"Data\":\"请登录\"}");
            return;
        }
        json(exchange, body);
    }

    private void json(HttpExchange exchange, String body) throws IOException {
        respond(exchange, "application/json; charset=UTF-8", body);
    }

    private void html(HttpExchange exchange, String body) throws IOException {
        respond(exchange, "text/html; charset=UTF-8", body);
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
