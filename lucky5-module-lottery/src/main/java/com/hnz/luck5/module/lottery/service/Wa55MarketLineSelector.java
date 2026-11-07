package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Selects a WA55 line from the configured site's own public line list, before logging in. */
final class Wa55MarketLineSelector {

    private static final Logger LOGGER = LoggerFactory.getLogger(Wa55MarketLineSelector.class);
    private static final Pattern LINE_LABEL = Pattern.compile("f[1-9][0-9]?");
    private static final Pattern LINE_HOST = Pattern.compile("(?i)f[1-9][0-9]?\\.[a-z0-9.-]+");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final long CACHE_NANOS = Duration.ofMinutes(30).toNanos();
    private static final long FAILURE_CACHE_NANOS = Duration.ofMinutes(5).toNanos();
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    private final Map<String, Selection> selections = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    URI select(URI configured, String ownerSessionKey) {
        String suffix = lineSuffix(configured);
        if (suffix == null) return configured;
        Selection selection = selections.compute(ownerSessionKey, (ignored, old) -> old != null && !old.expired()
                ? old : measure(configured, suffix));
        return selection.line();
    }

    void failed(String ownerSessionKey, URI configured, URI selected) {
        if (!configured.equals(selected)) {
            selections.put(ownerSessionKey,
                    new Selection(configured, System.nanoTime() + FAILURE_CACHE_NANOS));
        }
    }

    private Selection measure(URI configured, String suffix) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest listRequest = probeRequest(configured.resolve("/Member/GetCompanyUrl?is_mobile=0&is_all=1"));
            HttpResponse<String> listResponse = client.send(listRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (listResponse.statusCode() != 200) return fallback(configured);
            JsonNode list = objectMapper.readTree(listResponse.body());
            if (list.path("Status").asInt() != 1 || !list.path("Data").isArray()) return fallback(configured);

            List<URI> candidates = new ArrayList<>();
            for (JsonNode item : list.path("Data")) {
                URI candidate = candidate(configured, suffix, item.asText());
                if (candidate != null && !candidates.contains(candidate)) candidates.add(candidate);
            }
            if (candidates.isEmpty() || candidates.size() > 16) return fallback(configured);

            List<CompletableFuture<Probe>> probes = new ArrayList<>();
            for (URI candidate : candidates) {
                for (int sample = 0; sample < 3; sample++) {
                    long started = System.nanoTime();
                    probes.add(client.sendAsync(probeRequest(candidate.resolve(
                                    "/Member/GetNetSpeed?jsonp=callback")),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                            .orTimeout(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                            .handle((response, failure) -> new Probe(candidate,
                                    failure == null && response.statusCode() == 200
                                            && response.body().contains("\"site\":\"net_test\"")
                                            && candidate.getHost().equalsIgnoreCase(response.uri().getHost())
                                            ? Duration.ofNanos(System.nanoTime() - started).toMillis() : -1)));
                }
            }
            try {
                CompletableFuture.allOf(probes.toArray(CompletableFuture[]::new)).get(4, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return fallback(configured);
            } catch (Exception ignored) {
                // Slow and timed-out lines are simply excluded below.
            }
            List<Score> scores = candidates.stream().map(candidate -> {
                List<Long> times = probes.stream().filter(future -> future.isDone())
                        .map(future -> future.getNow(null)).filter(probe -> probe != null
                                && probe.line().equals(candidate) && probe.millis() >= 0)
                        .map(Probe::millis).sorted().toList();
                return new Score(candidate, median(times));
            }).filter(score -> score.millis() != Long.MAX_VALUE)
                    .sorted(Comparator.comparingLong(Score::millis)).toList();
            if (scores.isEmpty()) return fallback(configured);
            Score best = scores.get(0);
            LOGGER.info("Lucky5 market line selected: configured={}, selected={}, medianMs={}, healthyLines={}",
                    configured.getHost(), best.line().getHost(), best.millis(), scores.size());
            return new Selection(best.line(), System.nanoTime() + CACHE_NANOS);
        } catch (Exception ex) {
            LOGGER.warn("Lucky5 market line probe failed: configured={}, errorType={}",
                    configured.getHost(), ex.getClass().getSimpleName());
            return fallback(configured);
        }
    }

    private HttpRequest probeRequest(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(PROBE_TIMEOUT).header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01").GET().build();
    }

    private Selection fallback(URI configured) {
        return new Selection(configured, System.nanoTime() + FAILURE_CACHE_NANOS);
    }

    static long median(List<Long> sortedTimes) {
        return sortedTimes.size() >= 2 ? sortedTimes.get(sortedTimes.size() / 2) : Long.MAX_VALUE;
    }

    static String lineSuffix(URI configured) {
        if (!"https".equalsIgnoreCase(configured.getScheme()) || configured.getPort() != -1) return null;
        String host = configured.getHost();
        if (host == null) return null;
        int dot = host.indexOf('.');
        if (dot < 0 || !LINE_LABEL.matcher(host.substring(0, dot)).matches()) return null;
        String suffix = host.substring(dot + 1);
        return suffix.indexOf('.') > 0 ? suffix : null;
    }

    static URI candidate(URI configured, String suffix, String host) {
        if (host == null || !LINE_HOST.matcher(host).matches()) return null;
        int dot = host.indexOf('.');
        if (dot < 0 || !LINE_LABEL.matcher(host.substring(0, dot)).matches()
                || !host.substring(dot + 1).equalsIgnoreCase(suffix)) return null;
        return URI.create(configured.getScheme() + "://" + host.toLowerCase() + "/");
    }

    private record Selection(URI line, long expiresAt) {
        private boolean expired() { return System.nanoTime() - expiresAt >= 0; }
    }

    private record Probe(URI line, long millis) {}
    private record Score(URI line, long millis) {}
}
