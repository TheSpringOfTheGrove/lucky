package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLHandshakeException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WA55 盘口写协议客户端。它与只读 {@link Wa55MarketClient} 严格分离，且受独立环境开关保护。
 * 所有自动化测试必须使用本地 Mock HTTP 服务，禁止使用真实盘口账号执行测试下注。
 */
@Service
public class Wa55MarketOrderClient {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";
    private static final Pattern LOGIN_LINK = Pattern.compile("href=[\"']([^\"']*/Member/Login\\?[^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NORMAL_SELECTION = Pattern.compile("(?i)[0-9X]{4}");
    private static final Pattern XIAN_SELECTION = Pattern.compile("[0-9]{2,4}");

    @Resource
    private ObjectMapper objectMapper;

    @Value("${lottery.market.real-writes-enabled:true}")
    private boolean realWritesEnabled;

    public boolean isRealWritesEnabled() {
        return realWritesEnabled;
    }

    public SubmissionBatch submit(Credentials credentials, String expectedPeriod, List<BetRequest> requests) {
        requireEnabled();
        if (requests == null || requests.isEmpty()) return new SubmissionBatch(List.of(), null);
        requests.forEach(request -> validateRequest(expectedPeriod, request));
        Session session = connect(credentials);
        MemberPrint member = memberPrint(session);
        assertExpectedOpenPeriod(session, expectedPeriod, member.period());
        List<BetConfirmation> confirmations = new ArrayList<>(requests.size());
        for (BetRequest request : requests) {
            JsonNode payload;
            try {
                payload = postForm(session, "/Member/Bet", Map.of(
                        "betno", request.selection().toUpperCase(Locale.ROOT),
                        "isxian", request.xian() ? "1" : "0",
                        "way", "101",
                        "isfulltransform", "0",
                        "guid", request.guid(),
                        "betmoney", money(request.amount()).stripTrailingZeros().toPlainString()));
            } catch (MarketProtocolException ex) {
                throw new MarketProtocolException("盘口提交结果不确定：" + ex.getMessage(), false, confirmations,
                        true, ex);
            }
            try {
                assertSuccess(payload, "盘口拒绝下注");
            } catch (MarketProtocolException ex) {
                if (confirmations.isEmpty()) throw ex;
                throw new MarketProtocolException("盘口只受理了部分注单：" + ex.getMessage(), false,
                        confirmations, false, ex);
            }
            JsonNode data = first(payload, "Data", "data");
            BigDecimal accepted = decimal(first(data, "Money", "money", "BetMoney", "bet_money"));
            String betId = text(first(data, "BetId", "bet_id", "Id", "id"));
            String serialNo = text(first(data, "SerialNo", "serial_no"));
            int betCount = number(first(data, "BetCount", "bet_count"), 1);
            if (betId.isBlank() || serialNo.isBlank() || betCount <= 0) {
                throw new MarketProtocolException("盘口返回成功但缺少注单标识", false, confirmations,
                        true, null);
            }
            BetConfirmation confirmation = new BetConfirmation(request.routeItemId(), request.guid(), betId, serialNo,
                    betCount, accepted, decimal(first(data, "Odds", "odds")));
            if (accepted == null || accepted.compareTo(money(request.amount())) != 0) {
                List<BetConfirmation> partial = new ArrayList<>(confirmations);
                partial.add(confirmation);
                throw new MarketProtocolException("盘口实际受理金额与提交金额不一致", false, partial,
                        true, null);
            }
            confirmations.add(confirmation);
        }
        return new SubmissionBatch(List.copyOf(confirmations), member.balance());
    }

    public CancelResult cancel(Credentials credentials, String expectedPeriod, List<CancelRequest> requests) {
        requireEnabled();
        if (requests == null || requests.isEmpty()) return new CancelResult(true, "无需退码");
        Session session = connect(credentials);
        MemberPrint member = memberPrint(session);
        assertExpectedOpenPeriod(session, expectedPeriod, member.period());
        String ids = requests.stream().map(item -> item.marketBetId() + "|" + item.betCount())
                .reduce((left, right) -> left + "," + right).orElse("");
        JsonNode payload;
        try {
            payload = postForm(session, "/Member/CancelMemberBet", Map.of(
                    "ids", ids, "period_no", expectedPeriod));
        } catch (MarketProtocolException ex) {
            throw new MarketProtocolException("盘口退码结果不确定：" + ex.getMessage(), false, List.of(),
                    true, ex);
        }
        assertSuccess(payload, "盘口拒绝退码");
        return new CancelResult(true, text(first(payload, "Data", "data")));
    }

    private void validateRequest(String expectedPeriod, BetRequest request) {
        if (request == null || !Objects.equals(expectedPeriod, request.period())) {
            throw new MarketProtocolException("盘口下注期号不一致", false, List.of());
        }
        String selection = request.selection() == null ? "" : request.selection().trim();
        boolean valid = request.xian() ? XIAN_SELECTION.matcher(selection).matches()
                : NORMAL_SELECTION.matcher(selection).matches();
        if (!valid) throw new MarketProtocolException("当前盘口不支持该下注选项：" + selection, false, List.of());
        if (request.guid() == null || request.guid().isBlank()) {
            throw new MarketProtocolException("盘口下注幂等标识不能为空", false, List.of());
        }
        if (money(request.amount()).signum() <= 0) {
            throw new MarketProtocolException("盘口下注金额必须大于零", false, List.of());
        }
    }

    private void assertExpectedOpenPeriod(Session session, String expectedPeriod, String memberPeriod) {
        JsonNode issue = getJson(session, "/drawno/GetCurrentPeriodStatus?_=" + System.currentTimeMillis());
        String issuePeriod = text(first(issue, "PERIOD_NO", "PeriodNo", "period_no", "period"));
        int openStatus = number(first(issue, "OPEN_STATUS", "OpenStatus", "open_status", "status"), 99);
        if (!Objects.equals(expectedPeriod, memberPeriod) || !Objects.equals(expectedPeriod, issuePeriod)) {
            throw new MarketProtocolException("老板盘口当前期号与系统期号不一致，已停止提交", false, List.of());
        }
        if (openStatus != 0) throw new MarketProtocolException("老板盘口当前已经封盘，已停止提交", false, List.of());
    }

    private MemberPrint memberPrint(Session session) {
        JsonNode payload = getJson(session, "/Member/GetMemberPrint?_=" + System.currentTimeMillis());
        assertAuthenticated(payload);
        String period = text(first(payload, "period_no", "PeriodNo", "PERIOD_NO", "period"));
        BigDecimal balance = decimal(first(payload, "credit_balance", "CreditBalance", "balance", "Balance"));
        return new MemberPrint(period, balance);
    }

    private Session connect(Credentials credentials) {
        URI configured = normalizeBase(credentials.url());
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15)).build();
        String landing = text(client, request(configured).GET().build());
        Matcher matcher = LOGIN_LINK.matcher(landing);
        URI login = configured.resolve(matcher.find() ? matcher.group(1).replace("&amp;", "&") : "/Member/Login");
        text(client, request(login).GET().build());
        URI base = URI.create(login.getScheme() + "://" + login.getAuthority());
        JsonNode loginResult = postForm(new Session(client, base.toString()), "/Member/DoLogin", Map.of(
                "Account", credentials.account(), "Password", credentials.password()));
        assertSuccess(loginResult, "盘口登录失败");
        text(client, request(base.resolve("/Member/SysNotice?_=" + System.currentTimeMillis())).GET().build());
        text(client, request(base.resolve("/App/Index?_=" + System.currentTimeMillis() + "#!kuaida")).GET().build());
        return new Session(client, base.toString());
    }

    private JsonNode getJson(Session session, String path) {
        return json(session.client(), request(URI.create(session.baseUrl()).resolve(path))
                .header("X-Requested-With", "XMLHttpRequest").GET().build());
    }

    private JsonNode postForm(Session session, String path, Map<String, String> values) {
        String body = values.entrySet().stream().map(item -> encode(item.getKey()) + "=" + encode(item.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        return json(session.client(), request(URI.create(session.baseUrl()).resolve(path + "?_="
                        + System.currentTimeMillis())).header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private void assertSuccess(JsonNode payload, String prefix) {
        assertAuthenticated(payload);
        if (number(first(payload, "Status", "status"), 0) != 1) {
            throw new MarketProtocolException(prefix + "：" + text(first(payload, "Data", "data", "message")),
                    false, List.of());
        }
    }

    private void assertAuthenticated(JsonNode payload) {
        int status = number(first(payload, "Status", "status"), Integer.MIN_VALUE);
        String data = text(first(payload, "Data", "data", "message"));
        if (status == 5 && (data.toLowerCase(Locale.ROOT).contains("login") || data.contains("登录"))) {
            throw new MarketProtocolException("盘口会话已经失效", true, List.of());
        }
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
    }

    private String text(HttpClient client, HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MarketProtocolException("盘口请求失败：HTTP " + response.statusCode(),
                        response.statusCode() >= 500, List.of());
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MarketProtocolException("盘口请求被中断", true, List.of(), ex);
        } catch (Exception ex) {
            if (ex instanceof MarketProtocolException protocol) throw protocol;
            if (isTlsValidationFailure(ex)) {
                throw new MarketProtocolException("盘口 HTTPS 证书或域名无效", false, List.of(), ex);
            }
            throw new MarketProtocolException("盘口请求失败：" + ex.getMessage(), true, List.of(), ex);
        }
    }

    private JsonNode json(HttpClient client, HttpRequest request) {
        String body = text(client, request);
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new MarketProtocolException("盘口返回了无法解析的数据", true, List.of(), ex);
        }
    }

    private JsonNode first(JsonNode input, String... keys) {
        if (input == null || input.isNull()) return null;
        if (input.isObject()) {
            for (String key : keys) if (input.has(key)) return input.get(key);
            Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
            while (fields.hasNext()) {
                JsonNode found = first(fields.next().getValue(), keys);
                if (found != null) return found;
            }
        } else if (input.isArray()) {
            for (JsonNode child : input) {
                JsonNode found = first(child, keys);
                if (found != null) return found;
            }
        }
        return null;
    }

    private BigDecimal decimal(JsonNode value) {
        String normalized = text(value).replace(",", "").replaceAll("[^\\d.-]", "");
        if (normalized.isEmpty()) return null;
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int number(JsonNode value, int fallback) {
        if (value == null || value.isNull()) return fallback;
        try {
            return value.isNumber() ? value.intValue() : Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String text(JsonNode value) {
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private URI normalizeBase(String value) {
        try {
            String normalized = value != null && value.matches("(?i)^https?://.*") ? value : "https://" + value;
            URI uri = URI.create(normalized);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw new IllegalArgumentException();
            return URI.create(uri.getScheme() + "://" + uri.getAuthority() + "/");
        } catch (Exception ex) {
            throw new MarketProtocolException("盘口网址格式不正确", false, List.of(), ex);
        }
    }

    private boolean isTlsValidationFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SSLHandshakeException) return true;
            current = current.getCause();
        }
        return false;
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private void requireEnabled() {
        if (!realWritesEnabled) {
            throw new MarketProtocolException("真实盘口写入开关未开启", false, List.of());
        }
    }

    void setObjectMapperForTest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void setRealWritesEnabledForTest(boolean enabled) {
        this.realWritesEnabled = enabled;
    }

    public record Credentials(String url, String account, String password) {}

    public record BetRequest(String routeItemId, String period, String play, String selection,
                             BigDecimal amount, String guid, boolean xian) {}

    public record BetConfirmation(String routeItemId, String guid, String marketBetId, String serialNo,
                                  int betCount, BigDecimal acceptedAmount, BigDecimal odds) {}

    public record SubmissionBatch(List<BetConfirmation> confirmations, BigDecimal balance) {}

    public record CancelRequest(String marketBetId, int betCount) {}

    public record CancelResult(boolean success, String message) {}

    private record MemberPrint(String period, BigDecimal balance) {}

    private record Session(HttpClient client, String baseUrl) {}

    public static class MarketProtocolException extends RuntimeException {

        private final boolean retryable;
        private final List<BetConfirmation> confirmations;
        private final boolean submissionUncertain;

        public MarketProtocolException(String message, boolean retryable, List<BetConfirmation> confirmations) {
            this(message, retryable, confirmations, false, null);
        }

        public MarketProtocolException(String message, boolean retryable, List<BetConfirmation> confirmations,
                                       Throwable cause) {
            this(message, retryable, confirmations, false, cause);
        }

        public MarketProtocolException(String message, boolean retryable, List<BetConfirmation> confirmations,
                                       boolean submissionUncertain, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
            this.confirmations = List.copyOf(confirmations);
            this.submissionUncertain = submissionUncertain;
        }

        public boolean retryable() {
            return retryable;
        }

        public List<BetConfirmation> confirmations() {
            return confirmations;
        }

        public boolean submissionUncertain() {
            return submissionUncertain;
        }
    }
}
