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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    private static final int MAX_BATCH_DETAIL_PAGES = 20;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${lottery.market.real-writes-enabled:true}")
    private boolean realWritesEnabled;

    public boolean isRealWritesEnabled() {
        return realWritesEnabled;
    }

    public SubmissionBatch submit(Credentials credentials, String expectedPeriod, List<BetRequest> requests) {
        requireEnabled();
        if (requests == null || requests.isEmpty()) return new SubmissionBatch(List.of(), List.of(), null);
        requests.forEach(request -> validateRequest(expectedPeriod, request));
        Preflight preflight = preflight(credentials, expectedPeriod);
        Session session = preflight.session();
        MemberPrint member = preflight.member();
        List<BetConfirmation> confirmations = new ArrayList<>(requests.size());
        List<AcceptedBatch> acceptedBatches = new ArrayList<>();
        List<BetGroup> groups = groupRequests(expectedPeriod, requests);
        for (BetGroup group : groups) {
            JsonNode payload;
            try {
                payload = submitGroup(session, expectedPeriod, group);
            } catch (MarketProtocolException ex) {
                throw new MarketProtocolException("盘口提交结果不确定（批次 " + group.guid() + "）："
                        + ex.getMessage(), false, confirmations, true, ex);
            }
            try {
                assertSuccess(payload, "盘口拒绝下注");
            } catch (MarketSessionExpiredException ex) {
                throw new MarketProtocolException("盘口提交结果不确定（批次 " + group.guid() + "）："
                        + ex.getMessage(), false, confirmations, true, ex);
            } catch (MarketProtocolException ex) {
                if (confirmations.isEmpty() && acceptedBatches.isEmpty()) throw ex;
                if (!acceptedBatches.isEmpty()) {
                    throw new MarketProtocolException("盘口已有批次明确受理，后续批次结果异常："
                            + ex.getMessage(), false, confirmations, true, ex);
                }
                throw new MarketProtocolException("盘口只受理了部分注单：" + ex.getMessage(), false,
                        confirmations, false, ex);
            }
            JsonNode data = first(payload, "Data", "data");
            BigDecimal accepted = decimal(first(data, "FinalMoney", "final_money", "Money", "money",
                    "BetMoney", "bet_money"));
            int betCount = number(first(data, "FinalBetCount", "final_bet_count", "BetCount", "bet_count"), -1);
            if (betCount != group.requests().size() || accepted == null
                    || accepted.compareTo(group.totalAmount()) != 0) {
                throw new MarketProtocolException("盘口实际受理注数或金额与提交批次不一致", false, confirmations,
                        true, null);
            }
            if (group.requests().size() == 1) {
                String betId = text(first(data, "BetId", "bet_id", "Id", "id"));
                String serialNo = text(first(data, "SerialNo", "serial_no"));
                if (betId.isBlank() || serialNo.isBlank()) {
                    throw new MarketProtocolException("盘口返回成功但缺少注单标识", false, confirmations,
                            true, null);
                }
                BigDecimal odds = decimal(first(data, "Odds", "odds"));
                BetRequest request = group.requests().get(0);
                confirmations.add(new BetConfirmation(request.routeItemId(), group.guid(), betId, serialNo,
                        betCount, money(request.amount()), odds));
                continue;
            }
            List<BetConfirmation> directConfirmations = directBatchConfirmations(group, data, betCount);
            if (!directConfirmations.isEmpty()) {
                confirmations.addAll(directConfirmations);
            } else {
                // Exact count and amount make the write result definite. External identifiers are recovered later
                // through the read-only detail endpoint so the player response is not blocked by paged polling.
                acceptedBatches.add(acceptedBatch(group));
            }
        }
        return new SubmissionBatch(List.copyOf(confirmations), List.copyOf(acceptedBatches), member.balance());
    }

    /**
     * Reads only the external detail list for batches that already returned an exact successful count and amount.
     * This method never calls a market write endpoint and therefore is safe to repeat until the identifiers appear.
     */
    public VerificationBatch verifyAccepted(Credentials credentials, String period, List<BetRequest> requests) {
        if (requests == null || requests.isEmpty()) return new VerificationBatch(List.of(), List.of());
        requests.forEach(request -> validateRequest(period, request));
        List<ExternalBetRow> rows = memberBetRows(connect(credentials), period);
        List<BetConfirmation> confirmations = new ArrayList<>(requests.size());
        List<AcceptedBatch> unresolved = new ArrayList<>();
        for (BetGroup group : groupRequests(period, requests)) {
            List<BetConfirmation> matched = matchBatchConfirmations(rows, group, "", "");
            if (matched.isEmpty()) unresolved.add(acceptedBatch(group));
            else confirmations.addAll(matched);
        }
        return new VerificationBatch(List.copyOf(confirmations), List.copyOf(unresolved));
    }

    private Preflight preflight(Credentials credentials, String expectedPeriod) {
        MarketSessionExpiredException firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Session session = connect(credentials);
                MemberPrint member = memberPrint(session);
                assertExpectedOpenPeriod(session, expectedPeriod, member.period());
                return new Preflight(session, member);
            } catch (MarketSessionExpiredException ex) {
                if (attempt > 0) throw ex;
                firstFailure = ex;
            }
        }
        throw firstFailure;
    }

    private List<BetConfirmation> directBatchConfirmations(BetGroup group, JsonNode data, int betCount) {
        String betId = text(direct(data, "BetId", "bet_id", "Id", "id"));
        String serialNo = text(direct(data, "SerialNo", "serial_no"));
        if (betId.isBlank() || serialNo.isBlank()) return List.of();
        BigDecimal odds = decimal(direct(data, "Odds", "odds"));
        return group.requests().stream().map(request -> new BetConfirmation(request.routeItemId(), group.guid(),
                betId, serialNo, betCount, money(request.amount()), odds)).toList();
    }

    private List<BetConfirmation> matchBatchConfirmations(List<ExternalBetRow> rows, BetGroup group,
                                                           String directBetId, String directSerialNo) {
        Map<String, List<ExternalBetRow>> rowsBySerial = new LinkedHashMap<>();
        for (ExternalBetRow row : rows) {
            if (!directBetId.isBlank() && !directBetId.equals(row.betId())) continue;
            if (!directSerialNo.isBlank() && !directSerialNo.equals(row.serialNo())) continue;
            rowsBySerial.computeIfAbsent(row.serialNo(), ignored -> new ArrayList<>()).add(row);
        }
        List<List<ExternalBetRow>> matches = rowsBySerial.values().stream()
                .filter(candidate -> matchesGroup(candidate, group.requests())).toList();
        return matches.size() == 1 ? confirmations(group, matches.get(0)) : List.of();
    }

    private boolean matchesGroup(List<ExternalBetRow> rows, List<BetRequest> requests) {
        if (rows.size() != requests.size()) return false;
        Map<ExternalBetKey, Integer> expected = new LinkedHashMap<>();
        Map<ExternalBetKey, Integer> actual = new LinkedHashMap<>();
        requests.forEach(request -> expected.merge(new ExternalBetKey(dictNoTypeId(request), request.selection(),
                money(request.amount())), 1, Integer::sum));
        rows.forEach(row -> actual.merge(new ExternalBetKey(row.dictNoTypeId(), row.selection(),
                money(row.amount())), 1, Integer::sum));
        return expected.equals(actual);
    }

    private List<BetConfirmation> confirmations(BetGroup group, List<ExternalBetRow> rows) {
        Map<ExternalBetKey, ArrayDeque<ExternalBetRow>> available = new LinkedHashMap<>();
        rows.forEach(row -> available.computeIfAbsent(new ExternalBetKey(row.dictNoTypeId(), row.selection(),
                money(row.amount())), ignored -> new ArrayDeque<>()).add(row));
        List<BetConfirmation> result = new ArrayList<>(group.requests().size());
        for (BetRequest request : group.requests()) {
            ExternalBetRow row = available.get(new ExternalBetKey(dictNoTypeId(request), request.selection(),
                    money(request.amount()))).remove();
            result.add(new BetConfirmation(request.routeItemId(), group.guid(), row.betId(), row.serialNo(),
                    row.betCount(), money(request.amount()), row.odds()));
        }
        return result;
    }

    private List<ExternalBetRow> memberBetRows(Session session, String period) {
        List<ExternalBetRow> result = new ArrayList<>();
        int pageCount = 1;
        for (int page = 1; page <= pageCount && page <= MAX_BATCH_DETAIL_PAGES; page++) {
            JsonNode payload = getJson(session, "/Member/GetMemberBetList?period_number=" + encode(period)
                    + "&pageindex=" + page + "&_=" + System.currentTimeMillis());
            assertSuccess(payload, "盘口下注明细读取失败");
            JsonNode data = first(payload, "Data", "data");
            pageCount = Math.max(1, number(first(data, "PageCount", "page_count"), 1));
            JsonNode rows = first(data, "Rows", "rows", "List", "list");
            if (rows == null || !rows.isArray()) continue;
            for (JsonNode row : rows) {
                String betId = text(first(row, "bet_id", "BetId", "id"));
                String serialNo = text(first(row, "serial_no", "SerialNo"));
                int dictNoTypeId = number(first(row, "dict_no_type_id", "DictNoTypeId"), -1);
                String selection = text(first(row, "bet_no", "BetNo", "selection")).toUpperCase(Locale.ROOT);
                BigDecimal amount = decimal(first(row, "bet_money", "BetMoney", "money", "Money"));
                int betCount = number(first(row, "BetCount", "bet_count"), 1);
                if (betId.isBlank() || serialNo.isBlank() || dictNoTypeId <= 0 || selection.isBlank() || amount == null
                        || betCount <= 0) continue;
                boolean validSelection = NORMAL_SELECTION.matcher(selection).matches()
                        || XIAN_SELECTION.matcher(selection).matches();
                if (!validSelection) continue;
                result.add(new ExternalBetRow(betId, serialNo, dictNoTypeId, selection, amount, betCount,
                        decimal(first(row, "odds", "Odds"))));
            }
        }
        return result;
    }

    private JsonNode submitGroup(Session session, String expectedPeriod, BetGroup group) {
        if (group.requests().size() == 1) {
            return postForm(session, "/Member/Bet", Map.of(
                    "betno", group.selections(),
                    "is_xian", group.xian() ? "1" : "0",
                    "isxian", group.xian() ? "1" : "0",
                    "way", "101",
                    "isfulltransform", "0",
                    "guid", group.guid(),
                    "betmoney", group.amount().stripTrailingZeros().toPlainString()));
        }
        try {
            List<Map<String, Object>> bets = group.requests().stream().map(request -> Map.<String, Object>of(
                    "dict_no_type_id", dictNoTypeId(request),
                    "bet_no", request.selection().toUpperCase(Locale.ROOT),
                    "bet_money", request.amount().stripTrailingZeros().toPlainString())).toList();
            return postForm(session, "/Member/BatchBet", Map.of(
                    "bets", objectMapper.writeValueAsString(bets),
                    "way", "108",
                    "period_no", expectedPeriod,
                    "bet_log", group.requests().get(0).play(),
                    "guid", group.guid()));
        } catch (MarketProtocolException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MarketProtocolException("盘口批量下注参数生成失败", false, List.of(), ex);
        }
    }

    private int dictNoTypeId(BetRequest request) {
        String selection = request.selection().toUpperCase(Locale.ROOT);
        if (request.xian()) {
            return switch (selection.length()) {
                case 2 -> 12;
                case 3 -> 13;
                case 4 -> 14;
                default -> throw new MarketProtocolException("盘口字现类型无法识别", false, List.of());
            };
        }
        String pattern = selection.replaceAll("\\d", "口");
        return switch (pattern) {
            case "口XXX" -> 19;
            case "X口XX" -> 20;
            case "XX口X" -> 21;
            case "XXX口" -> 22;
            case "口口XX" -> 1;
            case "口X口X" -> 2;
            case "口XX口" -> 3;
            case "X口X口" -> 4;
            case "X口口X" -> 5;
            case "XX口口" -> 6;
            case "口口口X" -> 7;
            case "口口X口" -> 8;
            case "口X口口" -> 9;
            case "X口口口" -> 10;
            case "口口口口" -> 11;
            default -> throw new MarketProtocolException("盘口定位类型无法识别：" + request.play(), false,
                    List.of());
        };
    }

    public CancelResult cancel(Credentials credentials, String expectedPeriod, List<CancelRequest> requests) {
        requireEnabled();
        if (requests == null || requests.isEmpty()) return new CancelResult(true, "无需退码");
        if (expectedPeriod == null || expectedPeriod.isBlank()) {
            throw new MarketProtocolException("退码期号不能为空", false, List.of());
        }
        List<CancelRequest> distinctRequests = distinctCancelRequests(requests);
        distinctRequests.forEach(this::validateCancelRequest);
        Session session = connect(credentials);
        String ids = distinctRequests.stream().map(item -> item.marketBetId() + "|" + item.betCount())
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

    private void validateCancelRequest(CancelRequest request) {
        if (request == null || request.marketBetId() == null || request.marketBetId().isBlank()) {
            throw new MarketProtocolException("退码注单标识不能为空", false, List.of());
        }
        if (request.betCount() <= 0) {
            throw new MarketProtocolException("退码注数必须大于零", false, List.of());
        }
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

    private List<BetGroup> groupRequests(String expectedPeriod, List<BetRequest> requests) {
        Map<BetGroupKey, List<BetRequest>> groups = new LinkedHashMap<>();
        for (BetRequest request : requests) {
            BetGroupKey key = new BetGroupKey(request.play() == null ? "" : request.play().trim(), request.xian(),
                    money(request.amount()));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(request);
        }
        return groups.entrySet().stream().map(entry -> {
            List<BetRequest> items = List.copyOf(entry.getValue());
            String selections = items.stream().map(item -> item.selection().toUpperCase(Locale.ROOT))
                    .reduce((left, right) -> left + "," + right).orElse("");
            BigDecimal total = entry.getKey().amount().multiply(BigDecimal.valueOf(items.size()));
            return new BetGroup(items, selections, entry.getKey().xian(), entry.getKey().amount(), total,
                    batchGuid(expectedPeriod, entry.getKey(), items));
        }).toList();
    }

    private AcceptedBatch acceptedBatch(BetGroup group) {
        return new AcceptedBatch(group.guid(), group.requests().stream().map(BetRequest::routeItemId).toList(),
                group.requests().size(), money(group.totalAmount()));
    }

    private List<CancelRequest> distinctCancelRequests(List<CancelRequest> requests) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CancelRequest request : requests) {
            validateCancelRequest(request);
            counts.merge(request.marketBetId(), request.betCount(), Math::max);
        }
        return counts.entrySet().stream().map(entry -> new CancelRequest(entry.getKey(), entry.getValue())).toList();
    }

    private String batchGuid(String period, BetGroupKey key, List<BetRequest> requests) {
        if (requests.size() == 1) return requests.get(0).guid();
        StringBuilder source = new StringBuilder(period).append('|').append(key.play()).append('|')
                .append(key.xian()).append('|').append(key.amount());
        requests.stream().map(BetRequest::routeItemId).sorted().forEach(id -> source.append('|').append(id));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法生成盘口批次标识", ex);
        }
    }

    private void assertExpectedOpenPeriod(Session session, String expectedPeriod, String memberPeriod) {
        JsonNode issue = getJson(session, "/drawno/GetCurrentPeriodStatus?_=" + System.currentTimeMillis());
        assertAuthenticated(issue);
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
        String normalized = data.toLowerCase(Locale.ROOT);
        boolean explicitSessionFailure = data.contains("别处登录") || data.contains("別處登錄")
                || data.contains("重新登录") || data.contains("重新登錄")
                || data.contains("会话失效") || data.contains("會話失效")
                || data.contains("登录超时") || data.contains("登錄超時")
                || data.contains("未登录") || data.contains("未登錄");
        if (explicitSessionFailure || status == 5 && (normalized.contains("login") || data.contains("登录")
                || data.contains("登錄"))) {
            throw new MarketSessionExpiredException("盘口会话已经失效");
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

    private JsonNode direct(JsonNode input, String... keys) {
        if (input == null || !input.isObject()) return null;
        for (String key : keys) if (input.has(key)) return input.get(key);
        Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            for (String key : keys) {
                if (field.getKey().equalsIgnoreCase(key)) return field.getValue();
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

    public record AcceptedBatch(String guid, List<String> routeItemIds, int betCount, BigDecimal acceptedAmount) {}

    public record SubmissionBatch(List<BetConfirmation> confirmations, List<AcceptedBatch> acceptedBatches,
                                  BigDecimal balance) {
        public SubmissionBatch(List<BetConfirmation> confirmations, BigDecimal balance) {
            this(confirmations, List.of(), balance);
        }
    }

    public record VerificationBatch(List<BetConfirmation> confirmations, List<AcceptedBatch> unresolvedBatches) {}

    public record CancelRequest(String marketBetId, int betCount) {}

    public record CancelResult(boolean success, String message) {}

    private record BetGroupKey(String play, boolean xian, BigDecimal amount) {}

    private record BetGroup(List<BetRequest> requests, String selections, boolean xian, BigDecimal amount,
                            BigDecimal totalAmount, String guid) {}

    private record ExternalBetKey(int dictNoTypeId, String selection, BigDecimal amount) {
        private ExternalBetKey {
            selection = selection == null ? "" : selection.toUpperCase(Locale.ROOT);
        }
    }

    private record ExternalBetRow(String betId, String serialNo, int dictNoTypeId, String selection, BigDecimal amount,
                                  int betCount, BigDecimal odds) {}

    private record MemberPrint(String period, BigDecimal balance) {}

    private record Preflight(Session session, MemberPrint member) {}

    private record Session(HttpClient client, String baseUrl) {}

    private static final class MarketSessionExpiredException extends MarketProtocolException {

        private MarketSessionExpiredException(String message) {
            super(message, true, List.of());
        }
    }

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
