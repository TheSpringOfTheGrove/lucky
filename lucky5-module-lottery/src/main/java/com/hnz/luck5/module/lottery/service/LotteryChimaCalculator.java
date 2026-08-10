package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LotteryChimaCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public List<PeriodChima> calculate(List<OrderDO> orders, List<MemberDO> members, LocalDateTime chimaClearedAt) {
        return calculate(orders, members, List.of(), chimaClearedAt);
    }

    public List<PeriodChima> calculate(List<OrderDO> orders, List<MemberDO> members,
                                       List<MarketRouteItemDO> routes, LocalDateTime chimaClearedAt) {
        Set<String> eatMembers = members.stream().filter(item -> Boolean.TRUE.equals(item.getEatEnabled()))
                .filter(item -> !"BOT".equalsIgnoreCase(item.getMemberType()) && !Boolean.TRUE.equals(item.getAutoProxy()))
                .map(MemberDO::getId).collect(Collectors.toSet());
        Map<String, LocalDateTime> memberClearedAt = new HashMap<>();
        members.forEach(member -> memberClearedAt.put(member.getId(), member.getFlowClearedAt()));
        Map<String, List<MarketRouteItemDO>> routesByOrder = routes.stream()
                .filter(item -> item.getOrderId() != null && !"REFUNDED".equals(item.getStatus()))
                .collect(Collectors.groupingBy(MarketRouteItemDO::getOrderId));
        Map<String, BigDecimal[]> values = new HashMap<>();
        for (OrderDO order : orders) {
            if (!eatMembers.contains(order.getMemberId()) || "已退码".equals(order.getStatus())
                    || "AUTO_PROXY".equals(order.getOrderType())
                    || before(order.getCreateTime(), chimaClearedAt)
                    || before(order.getCreateTime(), memberClearedAt.get(order.getMemberId()))) {
                continue;
            }
            BigDecimal[] totals = values.computeIfAbsent(order.getPeriod(), ignored -> new BigDecimal[]{ZERO, ZERO});
            List<MarketRouteItemDO> orderRoutes = routesByOrder.getOrDefault(order.getId(), List.of());
            totals[0] = totals[0].add(orderRoutes.isEmpty() ? value(order.getAmount()) : orderRoutes.stream()
                    .map(MarketRouteItemDO::getLocalAmount).map(this::value).reduce(ZERO, BigDecimal::add));
            totals[1] = totals[1].add(orderRoutes.isEmpty() ? value(order.getWin()) : orderRoutes.stream()
                    .map(MarketRouteItemDO::getLocalPayout).map(this::value).reduce(ZERO, BigDecimal::add));
        }
        return values.entrySet().stream().map(entry -> new PeriodChima(entry.getKey(), money(entry.getValue()[0]),
                        money(entry.getValue()[1]), money(entry.getValue()[0].subtract(entry.getValue()[1]))))
                .sorted(Comparator.comparing(PeriodChima::period).reversed()).toList();
    }

    private boolean before(LocalDateTime createdAt, LocalDateTime clearedAt) {
        return createdAt != null && clearedAt != null && createdAt.isBefore(clearedAt);
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record PeriodChima(String period, BigDecimal fakeAmount, BigDecimal totalWin, BigDecimal net) {
    }

}
