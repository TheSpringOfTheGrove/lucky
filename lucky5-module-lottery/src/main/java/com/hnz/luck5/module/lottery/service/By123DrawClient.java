package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Read-only Lucky 5 result feed exposed by the first public history page. Never supplies a betting clock. */
@Service
public class By123DrawClient {

    static final URI HISTORY_URI = URI.create("https://w1.by123123b.com/History/Query?lotteryCode=614&size=5");
    static final URI RECOVERY_HISTORY_URI = URI.create("https://w1.by123123b.com/History/Query?lotteryCode=614&size=500");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource private ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public List<Wa55MarketClient.Draw> readDraws() {
        return readDraws(HISTORY_URI);
    }

    /** Wider history is used only when an old missing result blocks the live room. */
    public List<Wa55MarketClient.Draw> readRecoveryDraws() {
        return readDraws(RECOVERY_HISTORY_URI);
    }

    private List<Wa55MarketClient.Draw> readDraws(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("开奖源 HTTP " + response.statusCode());
            return parse(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("开奖源请求中断", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("备用开奖源读取失败", ex);
        }
    }

    List<Wa55MarketClient.Draw> parse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (root.path("Status").asInt() != 1 || !root.path("Data").isArray()) {
            throw new IllegalArgumentException("备用开奖源返回格式异常");
        }
        List<Wa55MarketClient.Draw> draws = new ArrayList<>();
        for (JsonNode item : root.path("Data")) {
            String period = item.path("PeriodNo").asText("");
            String result = item.path("DrawNo").asText("").replace(",", "").replace(" ", "");
            if (!period.matches("\\d{8,20}") || !result.matches("\\d{5}") || "00000".equals(result)) continue;
            try {
                LocalDateTime drawTime = LocalDateTime.parse(item.path("PerDrawTime").asText(), DATE_TIME);
                draws.add(new Wa55MarketClient.Draw(period, result, drawTime, drawTime, item.toString()));
            } catch (RuntimeException ignored) {
                // One incomplete history row must not hide another valid period from the same response.
            }
        }
        return draws;
    }
}
