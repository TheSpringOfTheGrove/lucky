package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.ChimaConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.mysql.ChimaConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketRouteItemMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.MARKET_PLAY_UNSUPPORTED;

@Service
public class LotteryMarketRoutingService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final String COMPACT_ROUTE_PLAY = "JSON快照";
    private static final int COMPACT_ROUTE_VERSION = 1;
    @Resource private ChimaConfigMapper chimaConfigMapper;
    @Resource private MarketRouteItemMapper routeItemMapper;
    @Resource private LotteryMarketRoutingPolicy routingPolicy;
    @Resource private LotteryBatchInsertService batchInsertService;

    /**
     * Calculates the amount that would actually be sent to the external market without persisting an order route.
     * The caller holds the owner finance row lock, so the retained-cap snapshot used here remains stable until the
     * matching {@link #prepare(Long, String, String, MemberDO, List)} call completes.
     */
    public BigDecimal previewMarketAmount(Long userId, String period, MemberDO member, List<BetItemDO> items) {
        return allocationPlan(userId, period, member, items).marketTotal();
    }

    public RoutingResult prepare(Long userId, String orderId, String period, MemberDO member, List<BetItemDO> items) {
        AllocationPlan plan = allocationPlan(userId, period, member, items);
        List<LotteryMarketRoutingPolicy.Allocation> allocations = plan.allocations();
        BigDecimal localTotal = plan.localTotal();
        BigDecimal marketTotal = plan.marketTotal();
        List<MarketRouteItemDO> routes = new ArrayList<>(allocations.size());
        for (LotteryMarketRoutingPolicy.Allocation allocation : allocations) {
            MarketRouteItemDO route = new MarketRouteItemDO();
            route.setId(detailId());
            route.setOrderId(orderId);
            route.setBetItemId(allocation.item().getId());
            route.setPeriod(period);
            route.setPlay(allocation.item().getPlay());
            route.setSelection(allocation.item().getSelection());
            route.setRouteType(allocation.routeType());
            route.setLocalAmount(allocation.localAmount());
            route.setMarketAmount(allocation.marketAmount());
            route.setOdds(allocation.item().getOdds());
            route.setLocalPayout(ZERO);
            route.setMarketGuid(IdUtil.fastSimpleUUID());
            route.setMarketBetId("");
            route.setMarketSerialNo("");
            route.setMarketBetCount(0);
            route.setMarketOdds(ZERO);
            route.setStatus(allocation.marketAmount().signum() == 0 ? "LOCAL_CONFIRMED" : "PENDING");
            route.setAttempts(0);
            route.setLastError("");
            route.setUserId(userId);
            routes.add(route);
        }
        insertRoutes(routes);
        String deliveryMode = marketTotal.signum() == 0 ? "LOCAL_EAT"
                : localTotal.signum() == 0 ? "MARKET_ADAPTER" : "MIXED_MARKET";
        return new RoutingResult(localTotal, marketTotal, deliveryMode,
                marketTotal.signum() == 0 ? "NOT_REQUIRED" : "PENDING");
    }

    /**
     * Persists one route record for a whole original command. The expanded route data is immutable JSON, so a large
     * command can start its single BatchBet without first inserting or state-updating thousands of physical rows.
     * Historical per-selection route rows continue to be handled by {@link #prepare}.
     */
    public RoutingResult prepareCompact(Long userId, String orderId, String period, MemberDO member,
                                        List<BetItemDO> expandedItems) {
        AllocationPlan plan = allocationPlan(userId, period, member, expandedItems);
        List<LotteryMarketRoutingPolicy.Allocation> allocations = plan.allocations();
        MarketRouteItemDO route = new MarketRouteItemDO();
        route.setId(detailId());
        route.setOrderId(orderId);
        route.setBetItemId("");
        route.setPeriod(period);
        route.setPlay(compactPlay(allocations));
        route.setSelection("已快照 " + allocations.size() + " 注");
        route.setSnapshotJson(compactRouteSnapshot(allocations));
        route.setRouteType(plan.marketTotal().signum() == 0 ? "LOCAL_EAT" : plan.localTotal().signum() == 0
                ? "REAL_MARKET" : "MIXED_MARKET");
        route.setLocalAmount(plan.localTotal());
        route.setMarketAmount(plan.marketTotal());
        route.setOdds(compactOdds(allocations));
        route.setLocalPayout(ZERO);
        route.setMarketGuid(IdUtil.fastSimpleUUID());
        route.setMarketBetId("");
        route.setMarketSerialNo("");
        route.setMarketBetCount(allocations.size());
        route.setMarketOdds(ZERO);
        route.setStatus(plan.marketTotal().signum() == 0 ? "LOCAL_CONFIRMED" : "PENDING");
        route.setAttempts(0);
        route.setLastError("");
        route.setUserId(userId);
        insertRoutes(List.of(route));
        String deliveryMode = plan.marketTotal().signum() == 0 ? "LOCAL_EAT"
                : plan.localTotal().signum() == 0 ? "MARKET_ADAPTER" : "MIXED_MARKET";
        return new RoutingResult(plan.localTotal(), plan.marketTotal(), deliveryMode,
                plan.marketTotal().signum() == 0 ? "NOT_REQUIRED" : "PENDING");
    }

    public boolean isCompactRoute(MarketRouteItemDO route) {
        return route != null && StrUtil.isNotBlank(route.getSnapshotJson());
    }

    public List<Wa55MarketOrderClient.BetRequest> expandMarketRequests(List<MarketRouteItemDO> routes) {
        List<Wa55MarketOrderClient.BetRequest> result = new ArrayList<>();
        for (MarketRouteItemDO route : routes) {
            if (!isCompactRoute(route)) {
                result.add(new Wa55MarketOrderClient.BetRequest(route.getId(), route.getPeriod(), route.getPlay(),
                        route.getSelection(), route.getMarketAmount(), route.getMarketGuid(),
                        route.getPlay() != null && route.getPlay().endsWith("字现")));
                continue;
            }
            JSONArray values = JSONUtil.parseObj(route.getSnapshotJson()).getJSONArray("routes");
            if (values == null) throw new IllegalStateException("盘口快照缺少线路数据");
            int index = 0;
            for (Object value : values) {
                JSONObject item = JSONUtil.parseObj(value);
                BigDecimal marketAmount = money(item.getBigDecimal("marketAmount"));
                if (marketAmount.signum() > 0) {
                    String play = item.getStr("play");
                    result.add(new Wa55MarketOrderClient.BetRequest(route.getId() + ":" + index, route.getPeriod(), play,
                            item.getStr("selection"), marketAmount, item.getStr("guid"),
                            play != null && play.endsWith("字现")));
                }
                index++;
            }
        }
        return result;
    }

    /**
     * Returns the expected market payout from an already-settled compact route.  The per-selection outcome remains
     * inside the immutable route snapshot because the physical route table deliberately contains only one row.
     */
    public BigDecimal compactMarketPayout(MarketRouteItemDO route) {
        if (!isCompactRoute(route)) return ZERO;
        JSONArray values = JSONUtil.parseObj(route.getSnapshotJson()).getJSONArray("routes");
        if (values == null) return ZERO;
        BigDecimal payout = ZERO;
        for (Object value : values) {
            JSONObject item = JSONUtil.parseObj(value);
            if (!Boolean.TRUE.equals(item.getBool("won"))) continue;
            payout = payout.add(money(item.getBigDecimal("marketAmount")).multiply(
                    money(item.getBigDecimal("odds"))).setScale(2, RoundingMode.HALF_UP));
        }
        return money(payout);
    }

    private void insertRoutes(List<MarketRouteItemDO> routes) {
        batchInsertService.insertMarketRoutes(TenantContextHolder.getRequiredTenantId(), routes);
    }

    private String detailId() {
        String timestamp = Long.toHexString(System.currentTimeMillis());
        return "0".repeat(Math.max(0, 12 - timestamp.length())) + timestamp + IdUtil.fastSimpleUUID();
    }

    private String compactRouteSnapshot(List<LotteryMarketRoutingPolicy.Allocation> allocations) {
        List<Map<String, Object>> routes = allocations.stream().map(allocation -> Map.<String, Object>of(
                "play", allocation.item().getPlay(), "selection", allocation.item().getSelection(),
                "localAmount", allocation.localAmount(), "marketAmount", allocation.marketAmount(),
                "odds", allocation.item().getOdds(), "guid", IdUtil.fastSimpleUUID())).toList();
        return JSONUtil.toJsonStr(Map.of("version", COMPACT_ROUTE_VERSION, "routes", routes));
    }

    private String compactPlay(List<LotteryMarketRoutingPolicy.Allocation> allocations) {
        return allocations.stream().map(allocation -> allocation.item().getPlay()).filter(StrUtil::isNotBlank)
                .distinct().limit(2).count() == 1 ? allocations.get(0).item().getPlay() : COMPACT_ROUTE_PLAY;
    }

    private BigDecimal compactOdds(List<LotteryMarketRoutingPolicy.Allocation> allocations) {
        return allocations.stream().map(allocation -> money(allocation.item().getOdds())).distinct().limit(2).count() == 1
                ? money(allocations.get(0).item().getOdds()) : ZERO;
    }

    private AllocationPlan allocationPlan(Long userId, String period, MemberDO member, List<BetItemDO> items) {
        ChimaConfigDO config = DataPermissionUtils.executeIgnore(() -> chimaConfigMapper.selectOne(
                new LambdaQueryWrapper<ChimaConfigDO>().eq(ChimaConfigDO::getUserId, userId).last("LIMIT 1")));
        List<MarketRouteItemDO> existing = DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectList(
                new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, userId)
                        .eq(MarketRouteItemDO::getPeriod, period)
                        .notIn(MarketRouteItemDO::getStatus, "CANCELLED", "FAILED", "REFUNDED")));
        Map<String, BigDecimal> retained = new HashMap<>();
        existing.forEach(item -> {
            if (!isCompactRoute(item)) {
                retained.merge(item.getPlay(), money(item.getLocalAmount()), BigDecimal::add);
                return;
            }
            JSONArray routes = JSONUtil.parseObj(item.getSnapshotJson()).getJSONArray("routes");
            if (routes == null) return;
            for (Object value : routes) {
                JSONObject route = JSONUtil.parseObj(value);
                retained.merge(route.getStr("play"), money(route.getBigDecimal("localAmount")), BigDecimal::add);
            }
        });

        List<LotteryMarketRoutingPolicy.Allocation> allocations = routingPolicy.allocate(member, config, items, retained);
        for (LotteryMarketRoutingPolicy.Allocation allocation : allocations) {
            if (allocation.marketAmount().signum() > 0 && !supportsMarket(allocation.item())) {
                throw exception(MARKET_PLAY_UNSUPPORTED, allocation.item().getPlay(), allocation.item().getSelection());
            }
        }
        BigDecimal localTotal = money(allocations.stream().map(LotteryMarketRoutingPolicy.Allocation::localAmount)
                .reduce(ZERO, BigDecimal::add));
        BigDecimal marketTotal = money(allocations.stream().map(LotteryMarketRoutingPolicy.Allocation::marketAmount)
                .reduce(ZERO, BigDecimal::add));
        return new AllocationPlan(allocations, localTotal, marketTotal);
    }

    boolean supportsMarket(BetItemDO item) {
        String selection = item.getSelection() == null ? "" : item.getSelection().trim();
        if (item.getPlay() != null && item.getPlay().endsWith("字现")) return selection.matches("\\d{2,4}");
        return selection.matches("(?i)[0-9X]{4}");
    }

    public void settle(Long userId, String orderId, List<BetItemDO> items) {
        Map<String, BetItemDO> itemById = items.stream().collect(Collectors.toMap(BetItemDO::getId,
                Function.identity()));
        Map<String, Deque<CompactOutcome>> compactOutcomes = compactOutcomes(items);
        List<MarketRouteItemDO> routes = DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectList(
                new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, userId)
                        .eq(MarketRouteItemDO::getOrderId, orderId)));
        for (MarketRouteItemDO route : routes) {
            if (isCompactRoute(route)) {
                settleCompactRoute(userId, route, compactOutcomes);
                continue;
            }
            BetItemDO item = itemById.get(route.getBetItemId());
            if (item == null) continue;
            BigDecimal localPayout = Boolean.TRUE.equals(item.getWon())
                    ? money(route.getLocalAmount()).multiply(route.getOdds()).setScale(2, RoundingMode.HALF_UP) : ZERO;
            routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                    .eq(MarketRouteItemDO::getId, route.getId()).eq(MarketRouteItemDO::getUserId, userId)
                    .in(MarketRouteItemDO::getStatus, "LOCAL_CONFIRMED", "CONFIRMED")
                    .set(MarketRouteItemDO::getLocalPayout, localPayout)
                    .set(MarketRouteItemDO::getStatus, "SETTLED")
                    .set(MarketRouteItemDO::getSettledAt, java.time.LocalDateTime.now()));
        }
    }

    private void settleCompactRoute(Long userId, MarketRouteItemDO route,
                                    Map<String, Deque<CompactOutcome>> compactOutcomes) {
        JSONObject snapshot = JSONUtil.parseObj(route.getSnapshotJson());
        JSONArray values = snapshot.getJSONArray("routes");
        if (values == null) return;
        BigDecimal localPayout = ZERO;
        for (int index = 0; index < values.size(); index++) {
            // Mutate the JSONObject held by the JSONArray itself. Parsing a detached copy here would lose the
            // settled outcome when the route snapshot is written back below.
            JSONObject item = values.getJSONObject(index);
            CompactOutcome outcome = compactOutcomes.getOrDefault(compactOutcomeKey(item.getStr("play"),
                    item.getStr("selection")), new ArrayDeque<>()).pollFirst();
            boolean won = outcome != null && outcome.won();
            BigDecimal payout = won ? money(item.getBigDecimal("localAmount")).multiply(
                    money(item.getBigDecimal("odds"))).setScale(2, RoundingMode.HALF_UP) : ZERO;
            item.set("won", won).set("localPayout", payout);
            localPayout = localPayout.add(payout);
        }
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getId, route.getId()).eq(MarketRouteItemDO::getUserId, userId)
                .in(MarketRouteItemDO::getStatus, "LOCAL_CONFIRMED", "CONFIRMED")
                .set(MarketRouteItemDO::getSnapshotJson, snapshot.toString())
                .set(MarketRouteItemDO::getLocalPayout, money(localPayout))
                .set(MarketRouteItemDO::getStatus, "SETTLED")
                .set(MarketRouteItemDO::getSettledAt, java.time.LocalDateTime.now()));
    }

    private Map<String, Deque<CompactOutcome>> compactOutcomes(List<BetItemDO> items) {
        Map<String, Deque<CompactOutcome>> result = new HashMap<>();
        for (BetItemDO item : items) {
            if (StrUtil.isBlank(item.getSnapshotJson())) continue;
            JSONArray values = JSONUtil.parseObj(item.getSnapshotJson()).getJSONArray("items");
            if (values == null) continue;
            for (Object value : values) {
                JSONObject outcome = JSONUtil.parseObj(value);
                result.computeIfAbsent(compactOutcomeKey(outcome.getStr("play"), outcome.getStr("selection")),
                        ignored -> new ArrayDeque<>()).addLast(new CompactOutcome(Boolean.TRUE.equals(outcome.getBool("won"))));
            }
        }
        return result;
    }

    private String compactOutcomeKey(String play, String selection) {
        return String.valueOf(play) + '\u0000' + String.valueOf(selection);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    public record RoutingResult(BigDecimal localAmount, BigDecimal marketAmount, String deliveryMode,
                                String marketStatus) {}

    private record AllocationPlan(List<LotteryMarketRoutingPolicy.Allocation> allocations,
                                  BigDecimal localTotal, BigDecimal marketTotal) {}

    private record CompactOutcome(boolean won) {}
}
