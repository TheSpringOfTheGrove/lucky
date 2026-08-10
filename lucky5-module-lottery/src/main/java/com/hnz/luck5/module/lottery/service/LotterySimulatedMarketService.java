package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.ChimaConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.SimulatedMarketAccountDO;
import com.hnz.luck5.module.lottery.dal.mysql.ChimaConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketRouteItemMapper;
import com.hnz.luck5.module.lottery.dal.mysql.SimulatedMarketAccountMapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.BET_STATE_CHANGED;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.SIMULATED_MARKET_BALANCE_NOT_ENOUGH;

/**
 * Fully local market simulator. This service has no dependency on a real market client by design.
 */
@Service
public class LotterySimulatedMarketService {

    public static final BigDecimal DEFAULT_BALANCE = new BigDecimal("100000.00");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Resource private SimulatedMarketAccountMapper accountMapper;
    @Resource private MarketRouteItemMapper routeItemMapper;
    @Resource private ChimaConfigMapper chimaConfigMapper;
    @Resource private LotteryMarketRoutingPolicy routingPolicy;

    @Transactional(rollbackFor = Exception.class)
    public Snapshot snapshot(Long userId) {
        SimulatedMarketAccountDO account = getOrCreate(userId);
        List<MarketRouteItemDO> recent = DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectList(
                new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, userId)
                        .orderByDesc(MarketRouteItemDO::getCreateTime).last("LIMIT 20")));
        return new Snapshot(account, recent);
    }

    @Transactional(rollbackFor = Exception.class)
    public RoutingResult reserve(Long userId, String orderId, String period, MemberDO member, List<BetItemDO> items) {
        SimulatedMarketAccountDO account = lockAccount(userId);
        ChimaConfigDO config = DataPermissionUtils.executeIgnore(() -> chimaConfigMapper.selectOne(
                new LambdaQueryWrapper<ChimaConfigDO>().eq(ChimaConfigDO::getUserId, userId).last("LIMIT 1")));
        List<MarketRouteItemDO> existing = DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectList(
                new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, userId)
                        .eq(MarketRouteItemDO::getPeriod, period).ne(MarketRouteItemDO::getStatus, "REFUNDED")));
        Map<String, BigDecimal> retained = new HashMap<>();
        existing.forEach(item -> retained.merge(item.getPlay(), money(item.getLocalAmount()), BigDecimal::add));

        List<LotteryMarketRoutingPolicy.Allocation> allocations = routingPolicy.allocate(member, config, items, retained);
        BigDecimal localTotal = money(allocations.stream().map(LotteryMarketRoutingPolicy.Allocation::localAmount)
                .reduce(ZERO, BigDecimal::add));
        BigDecimal simulatedTotal = money(allocations.stream().map(LotteryMarketRoutingPolicy.Allocation::simulatedAmount)
                .reduce(ZERO, BigDecimal::add));
        if (money(account.getBalance()).compareTo(simulatedTotal) < 0) {
            throw exception(SIMULATED_MARKET_BALANCE_NOT_ENOUGH);
        }
        if (simulatedTotal.signum() > 0) {
            updateAccount(account, money(money(account.getBalance()).subtract(simulatedTotal)),
                    money(money(account.getTotalStake()).add(simulatedTotal)), money(account.getTotalPayout()),
                    money(account.getTotalRefund()));
        }
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
            route.setSimulatedAmount(allocation.simulatedAmount());
            route.setOdds(allocation.item().getOdds());
            route.setLocalPayout(ZERO);
            route.setSimulatedPayout(ZERO);
            route.setStatus("CONFIRMED");
            route.setUserId(userId);
            routeItemMapper.insert(route);
        }
        String deliveryMode = localTotal.signum() == 0 ? "SIMULATED_MARKET"
                : simulatedTotal.signum() == 0 ? "LOCAL_EAT" : "MIXED_SIMULATED";
        return new RoutingResult(localTotal, simulatedTotal, deliveryMode,
                simulatedTotal.signum() == 0 ? "NOT_REQUIRED" : "SIMULATED_CONFIRMED");
    }

    @Transactional(rollbackFor = Exception.class)
    public SettlementResult settle(Long userId, String orderId, List<BetItemDO> items) {
        List<MarketRouteItemDO> routes = routes(userId, orderId, "CONFIRMED");
        if (routes.isEmpty()) {
            return new SettlementResult(false, ZERO, ZERO);
        }
        Map<String, BetItemDO> itemMap = items.stream().collect(Collectors.toMap(BetItemDO::getId,
                Function.identity()));
        SimulatedMarketAccountDO account = lockAccount(userId);
        BigDecimal localPayout = ZERO;
        BigDecimal simulatedPayout = ZERO;
        LocalDateTime now = LocalDateTime.now();
        for (MarketRouteItemDO route : routes) {
            BetItemDO item = itemMap.get(route.getBetItemId());
            if (item == null) {
                throw exception(BET_STATE_CHANGED);
            }
            BigDecimal routeLocalPayout = Boolean.TRUE.equals(item.getWon())
                    ? money(route.getLocalAmount().multiply(route.getOdds())) : ZERO;
            BigDecimal routeSimulatedPayout = Boolean.TRUE.equals(item.getWon())
                    ? money(route.getSimulatedAmount().multiply(route.getOdds())) : ZERO;
            route.setLocalPayout(routeLocalPayout);
            route.setSimulatedPayout(routeSimulatedPayout);
            route.setStatus("SETTLED");
            route.setSettledAt(now);
            int changed = routeItemMapper.update(route, new LambdaUpdateWrapper<MarketRouteItemDO>()
                    .eq(MarketRouteItemDO::getId, route.getId()).eq(MarketRouteItemDO::getUserId, userId)
                    .eq(MarketRouteItemDO::getStatus, "CONFIRMED"));
            if (changed != 1) {
                throw exception(BET_STATE_CHANGED);
            }
            localPayout = localPayout.add(routeLocalPayout);
            simulatedPayout = simulatedPayout.add(routeSimulatedPayout);
        }
        simulatedPayout = money(simulatedPayout);
        if (simulatedPayout.signum() > 0) {
            updateAccount(account, money(money(account.getBalance()).add(simulatedPayout)), money(account.getTotalStake()),
                    money(money(account.getTotalPayout()).add(simulatedPayout)), money(account.getTotalRefund()));
        }
        return new SettlementResult(true, money(localPayout), simulatedPayout);
    }

    @Transactional(rollbackFor = Exception.class)
    public BigDecimal refund(Long userId, String orderId) {
        List<MarketRouteItemDO> routes = routes(userId, orderId, "CONFIRMED");
        if (routes.isEmpty()) {
            return ZERO;
        }
        SimulatedMarketAccountDO account = lockAccount(userId);
        BigDecimal refund = money(routes.stream().map(MarketRouteItemDO::getSimulatedAmount)
                .reduce(ZERO, BigDecimal::add));
        LocalDateTime now = LocalDateTime.now();
        for (MarketRouteItemDO route : routes) {
            route.setStatus("REFUNDED");
            route.setCancelledAt(now);
            int changed = routeItemMapper.update(route, new LambdaUpdateWrapper<MarketRouteItemDO>()
                    .eq(MarketRouteItemDO::getId, route.getId()).eq(MarketRouteItemDO::getUserId, userId)
                    .eq(MarketRouteItemDO::getStatus, "CONFIRMED"));
            if (changed != 1) {
                throw exception(BET_STATE_CHANGED);
            }
        }
        if (refund.signum() > 0) {
            updateAccount(account, money(money(account.getBalance()).add(refund)), money(account.getTotalStake()),
                    money(account.getTotalPayout()), money(money(account.getTotalRefund()).add(refund)));
        }
        return refund;
    }

    public List<MarketRouteItemDO> listByOrderIds(List<String> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return routeItemMapper.selectList(new LambdaQueryWrapper<MarketRouteItemDO>()
                .in(MarketRouteItemDO::getOrderId, orderIds));
    }

    private List<MarketRouteItemDO> routes(Long userId, String orderId, String status) {
        return DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectList(
                new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, userId)
                        .eq(MarketRouteItemDO::getOrderId, orderId).eq(MarketRouteItemDO::getStatus, status)));
    }

    private SimulatedMarketAccountDO lockAccount(Long userId) {
        getOrCreate(userId);
        return DataPermissionUtils.executeIgnore(() -> accountMapper.selectOne(
                new LambdaQueryWrapper<SimulatedMarketAccountDO>().eq(SimulatedMarketAccountDO::getUserId, userId)
                        .last("LIMIT 1 FOR UPDATE")));
    }

    private SimulatedMarketAccountDO getOrCreate(Long userId) {
        SimulatedMarketAccountDO existing = DataPermissionUtils.executeIgnore(() -> accountMapper.selectOne(
                new LambdaQueryWrapper<SimulatedMarketAccountDO>().eq(SimulatedMarketAccountDO::getUserId, userId)
                        .last("LIMIT 1")));
        if (existing != null) {
            return existing;
        }
        SimulatedMarketAccountDO account = new SimulatedMarketAccountDO();
        account.setUserId(userId);
        account.setInitialBalance(DEFAULT_BALANCE);
        account.setBalance(DEFAULT_BALANCE);
        account.setTotalStake(ZERO);
        account.setTotalPayout(ZERO);
        account.setTotalRefund(ZERO);
        account.setVersion(0);
        try {
            accountMapper.insert(account);
            return account;
        } catch (DuplicateKeyException ignored) {
            return DataPermissionUtils.executeIgnore(() -> accountMapper.selectOne(
                    new LambdaQueryWrapper<SimulatedMarketAccountDO>().eq(SimulatedMarketAccountDO::getUserId, userId)
                            .last("LIMIT 1")));
        }
    }

    private void updateAccount(SimulatedMarketAccountDO account, BigDecimal balance, BigDecimal totalStake,
                               BigDecimal totalPayout, BigDecimal totalRefund) {
        int version = account.getVersion() == null ? 0 : account.getVersion();
        int changed = accountMapper.update(null, new LambdaUpdateWrapper<SimulatedMarketAccountDO>()
                .eq(SimulatedMarketAccountDO::getId, account.getId())
                .eq(SimulatedMarketAccountDO::getUserId, account.getUserId())
                .eq(SimulatedMarketAccountDO::getVersion, version)
                .set(SimulatedMarketAccountDO::getBalance, balance)
                .set(SimulatedMarketAccountDO::getTotalStake, totalStake)
                .set(SimulatedMarketAccountDO::getTotalPayout, totalPayout)
                .set(SimulatedMarketAccountDO::getTotalRefund, totalRefund)
                .set(SimulatedMarketAccountDO::getVersion, version + 1));
        if (changed != 1) {
            throw exception(BET_STATE_CHANGED);
        }
        account.setBalance(balance);
        account.setTotalStake(totalStake);
        account.setTotalPayout(totalPayout);
        account.setTotalRefund(totalRefund);
        account.setVersion(version + 1);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    public record Snapshot(SimulatedMarketAccountDO account, List<MarketRouteItemDO> recentRoutes) {
    }

    public record RoutingResult(BigDecimal localAmount, BigDecimal simulatedAmount, String deliveryMode,
                                String marketStatus) {
    }

    public record SettlementResult(boolean routed, BigDecimal localPayout, BigDecimal simulatedPayout) {
    }
}
