package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
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

    @Resource private ChimaConfigMapper chimaConfigMapper;
    @Resource private MarketRouteItemMapper routeItemMapper;
    @Resource private LotteryMarketRoutingPolicy routingPolicy;

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
        for (LotteryMarketRoutingPolicy.Allocation allocation : allocations) {
            MarketRouteItemDO route = new MarketRouteItemDO();
            route.setId(IdUtil.fastSimpleUUID());
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
            routeItemMapper.insert(route);
        }
        String deliveryMode = marketTotal.signum() == 0 ? "LOCAL_EAT"
                : localTotal.signum() == 0 ? "MARKET_ADAPTER" : "MIXED_MARKET";
        return new RoutingResult(localTotal, marketTotal, deliveryMode,
                marketTotal.signum() == 0 ? "NOT_REQUIRED" : "PENDING");
    }

    private AllocationPlan allocationPlan(Long userId, String period, MemberDO member, List<BetItemDO> items) {
        ChimaConfigDO config = DataPermissionUtils.executeIgnore(() -> chimaConfigMapper.selectOne(
                new LambdaQueryWrapper<ChimaConfigDO>().eq(ChimaConfigDO::getUserId, userId).last("LIMIT 1")));
        List<MarketRouteItemDO> existing = DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectList(
                new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, userId)
                        .eq(MarketRouteItemDO::getPeriod, period)
                        .notIn(MarketRouteItemDO::getStatus, "CANCELLED", "FAILED", "REFUNDED")));
        Map<String, BigDecimal> retained = new HashMap<>();
        existing.forEach(item -> retained.merge(item.getPlay(), money(item.getLocalAmount()), BigDecimal::add));

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
        List<MarketRouteItemDO> routes = DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectList(
                new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, userId)
                        .eq(MarketRouteItemDO::getOrderId, orderId)));
        for (MarketRouteItemDO route : routes) {
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

    private BigDecimal money(BigDecimal value) {
        return (value == null ? ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    public record RoutingResult(BigDecimal localAmount, BigDecimal marketAmount, String deliveryMode,
                                String marketStatus) {}

    private record AllocationPlan(List<LotteryMarketRoutingPolicy.Allocation> allocations,
                                  BigDecimal localTotal, BigDecimal marketTotal) {}
}
