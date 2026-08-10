package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLHandshakeException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only client for the market protocol implemented by the original Lucky5 project. */
@Service
public class Wa55MarketClient {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern LOGIN_LINK = Pattern.compile("href=[\"']([^\"']*/Member/Login\\?[^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE);

    @Resource
    private ObjectMapper objectMapper;

    public record Credentials(String url, String account, String password) {}
    public record Account(String displayAccount, BigDecimal balance) {}
    public record Issue(String period, String status, int marketStatus, int remainingSeconds,
                        LocalDateTime serverTime, String nextPeriod, String raw) {}
    public record Draw(String period, String result, LocalDateTime drawTime, LocalDateTime updatedAt, String raw) {}
    public record Snapshot(String lineUrl, Account account, Issue issue, List<Draw> draws) {}

    public Snapshot read(Credentials credentials, boolean includeDraws) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Session session = connect(credentials);
                Account account = account(session);
                Issue issue = issue(session);
                List<Draw> draws = includeDraws ? draws(session) : List.of();
                return new Snapshot(session.baseUrl, account, issue, draws);
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt == 0) {
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("盘口连接被中断", interrupted);
                    }
                }
            }
        }
        throw last == null ? new IllegalStateException("盘口连接失败") : last;
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
        String form = "Account=" + encode(credentials.account()) + "&Password=" + encode(credentials.password());
        JsonNode loginResult = json(client, request(base.resolve("/Member/DoLogin?_=" + System.currentTimeMillis()))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        if (number(first(loginResult, "Status", "status"), 0) != 1) {
            throw new IllegalStateException("盘口登录失败：" + text(first(loginResult, "Data", "message")));
        }
        text(client, request(base.resolve("/Member/SysNotice?_=" + System.currentTimeMillis())).GET().build());
        text(client, request(base.resolve("/App/Index?_=" + System.currentTimeMillis() + "#!kuaida")).GET().build());
        return new Session(client, base.toString());
    }

    private Account account(Session session) {
        JsonNode raw = jsonRequest(session, "/Member/GetMemberPrint?_=" + System.currentTimeMillis());
        assertAuthenticated(raw);
        JsonNode balance = first(raw, "credit_balance", "CreditBalance", "balance", "Balance", "member_credit",
                "MemberCredit", "usable_credit", "UsableCredit", "credit", "Credit", "money", "Money");
        return new Account(text(first(raw, "member_account", "MemberAccount", "account")), decimal(balance));
    }

    private Issue issue(Session session) {
        JsonNode raw = jsonRequest(session, "/drawno/GetCurrentPeriodStatus?_=" + System.currentTimeMillis());
        assertAuthenticated(raw);
        int marketStatus = number(first(raw, "OPEN_STATUS", "OpenStatus", "open_status", "status"), 99);
        String status = switch (marketStatus) {
            case 0 -> "OPEN";
            case 1 -> "CLOSED";
            case 4 -> "PENDING";
            default -> "UNAVAILABLE";
        };
        return new Issue(text(first(raw, "PERIOD_NO", "PeriodNo", "period_no", "period")), status, marketStatus,
                Math.max(0, number(first(raw, "LAST_TIME", "LastTime", "last_time", "last_seconds"), 0)),
                date(first(raw, "SERVER_TIME", "ServerTime", "server_time", "system_db_now", "system_now")),
                text(first(raw, "NEXT_PERIOD_NO", "NextPeriodNo", "next_period_no", "next_period")), raw.toString());
    }

    private List<Draw> draws(Session session) {
        JsonNode firstPage = jsonRequest(session, "/DrawNo/GetDrawNoTable?pageindex=1&_=" + System.currentTimeMillis());
        assertAuthenticated(firstPage);
        int pageCount = Math.max(1, number(first(firstPage, "PageCount", "pageCount"), 1));
        List<JsonNode> payloads = new ArrayList<>();
        payloads.add(firstPage);
        for (int page = 2; page <= pageCount; page++) {
            JsonNode payload = jsonRequest(session, "/DrawNo/GetDrawNoTable?pageindex=" + page + "&_="
                    + System.currentTimeMillis());
            assertAuthenticated(payload);
            payloads.add(payload);
        }
        List<Draw> result = new ArrayList<>();
        for (JsonNode payload : payloads) {
            JsonNode rows = array(payload, "Rows", "rows", "List", "list", "draw_info");
            if (rows == null) continue;
            for (JsonNode row : rows) {
                String period = text(first(row, "period_no", "PeriodNo", "period"));
                String drawResult = digitFields(row);
                if (drawResult.isEmpty()) {
                    drawResult = normalizeDirectResult(text(first(row, "result", "draw_no", "DrawNo")));
                }
                if (!period.matches("\\d+")) continue;
                result.add(new Draw(period, drawResult,
                        date(first(row, "draw_datetime", "DrawDatetime", "draw_time", "DrawTime", "open_time")),
                        date(first(row, "period_datetime", "PeriodDatetime", "update_time", "UpdateTime", "updated_at")),
                        row.toString()));
            }
        }
        return result;
    }

    String digitFields(JsonNode row) {
        StringBuilder result = new StringBuilder();
        for (String key : List.of("thousand_no", "hundred_no", "ten_no", "one_no", "ball5")) {
            JsonNode value = first(row, key, pascal(key));
            String digit = text(value).replaceAll("\\D", "");
            if (digit.length() != 1) return "";
            result.append(digit);
        }
        return result.toString();
    }

    String normalizeDirectResult(String raw) {
        String result = raw == null ? "" : raw.replaceAll("\\D", "");
        return result.matches("\\d{5}") ? result : "";
    }

    private JsonNode jsonRequest(Session session, String path) {
        return json(session.client, request(URI.create(session.baseUrl).resolve(path))
                .header("X-Requested-With", "XMLHttpRequest").GET().build());
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
                throw new IllegalStateException("盘口请求失败：HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("盘口请求被中断", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException state) throw state;
            if (isTlsValidationFailure(ex)) {
                throw new IllegalStateException("盘口HTTPS证书无效或域名已失效，请更新有效的网盘会员网址", ex);
            }
            throw new IllegalStateException("盘口请求失败：" + ex.getMessage(), ex);
        }
    }

    private boolean isTlsValidationFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SSLHandshakeException) return true;
            String message = current.getMessage();
            if (message != null && (message.contains("PKIX path building failed")
                    || message.contains("unable to find valid certification path"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private JsonNode json(HttpClient client, HttpRequest request) {
        String body = text(client, request);
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("盘口返回了无法解析的数据", ex);
        }
    }

    private void assertAuthenticated(JsonNode payload) {
        int status = number(first(payload, "Status", "status"), Integer.MIN_VALUE);
        String data = text(first(payload, "Data", "message"));
        if (status == 5 || data.toLowerCase(Locale.ROOT).contains("login") || data.contains("请登录")) {
            throw new IllegalStateException("盘口会话已失效");
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

    private JsonNode array(JsonNode input, String... keys) {
        if (input != null && input.isArray()) return input;
        JsonNode value = first(input, keys);
        return value != null && value.isArray() ? value : null;
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

    private LocalDateTime date(JsonNode value) {
        String raw = text(value).trim();
        if (raw.isEmpty()) return null;
        try {
            long numeric = Long.parseLong(raw);
            if (numeric > 1_000_000_000L) {
                if (numeric < 100_000_000_000L) numeric *= 1000;
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(numeric), ZONE);
            }
        } catch (NumberFormatException ignored) {
            // Parse formatted dates below.
        }
        try {
            return LocalDateTime.parse(raw, DATE_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(raw), ZONE);
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private URI normalizeBase(String value) {
        try {
            String normalized = value != null && value.matches("(?i)^https?://.*") ? value : "https://" + value;
            URI uri = URI.create(normalized);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw new IllegalArgumentException();
            return URI.create(uri.getScheme() + "://" + uri.getAuthority() + "/");
        } catch (Exception ex) {
            throw new IllegalStateException("盘口网址格式不正确", ex);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String pascal(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = true;
        for (char character : value.toCharArray()) {
            if (character == '_') {
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(character) : character);
                upper = false;
            }
        }
        return result.toString();
    }

    private record Session(HttpClient client, String baseUrl) {}
}
