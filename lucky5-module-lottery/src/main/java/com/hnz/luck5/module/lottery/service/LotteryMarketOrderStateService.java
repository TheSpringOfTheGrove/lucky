package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.BetItemMapper;
import com.hnz.luck5.module.lottery.dal.mysql.LotteryConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketRouteItemMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.BET_STATE_CHANGED;

@Service
public class LotteryMarketOrderStateService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final Set<String> SUBMITTABLE = Set.of("PENDING", "RETRY");
    private static final Set<String> CONFIRMABLE = Set.of("SUBMITTING", "RETRY", "UNKNOWN", "VERIFYING");
    private static final String ACCEPTED_WITHOUT_IDS_NOTE =
            "盘口已明确受理，逐注明细编号未返回；后台继续补齐以便异步退码";
    private static final String ACCEPTED_WITHOUT_IDS_PREFIX = "盘口已明确受理，逐注明细编号未返回";

    @Resource private OrderMapper orderMapper;
    @Resource private MarketRouteItemMapper routeItemMapper;
    @Resource private LotteryConfigMapper lotteryConfigMapper;
    @Resource private IssueMapper issueMapper;
    @Resource private MemberMapper memberMapper;
    @Resource private MessageMapper messageMapper;
    @Resource private BetItemMapper betItemMapper;
    @Resource private MarketCredentialService credentialService;
    @Resource private LotteryRobotReplyTemplate robotReplyTemplate;
    @Resource private LotteryIssueFreshnessPolicy issueFreshnessPolicy;
    @Resource private LotteryMarketRoutingService marketRoutingService;

    public boolean isCancellationWindowOpen(Long userId, String period) {
        IssueDO issue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                        .eq(IssueDO::getPeriod, period).last("LIMIT 1")));
        return issue != null && "OPEN".equals(issue.getStatus())
                && !issueFreshnessPolicy.isStale(issue) && !issueFreshnessPolicy.isBettingClosed(issue);
    }

    public boolean needsMarketCancellation(Long userId, String orderId) {
        OrderDO order = order(userId, orderId);
        return order != null && "已退码".equals(order.getStatus())
                && "CANCEL_REQUESTED".equals(order.getMarketStatus());
    }
    @Transactional(rollbackFor = Exception.class)
    public DispatchContext claimSubmit(Long userId, String orderId) {
        OrderDO order = order(userId, orderId);
        if (order == null || !SUBMITTABLE.contains(order.getMarketStatus())) return null;
        List<MarketRouteItemDO> routes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0)
                .filter(item -> SUBMITTABLE.contains(item.getStatus()))
                .toList();
        if (routes.isEmpty()) {
            // No upstream request can be built. Never claim SUBMITTING and later mislabel this as an
            // interrupted network submission; retain it for an explicit, auditable reconciliation.
            markManualReview(userId, orderId, "外盘订单缺少提交明细，未发起盘口请求，请人工核对");
            return null;
        }
        int version = value(order.getVersion(), 0);
        int attempts = value(order.getMarketAttempts(), 0) + 1;
        int changed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getId, orderId).eq(OrderDO::getUserId, userId)
                .eq(OrderDO::getStatus, "未开奖").in(OrderDO::getMarketStatus, SUBMITTABLE)
                .eq(OrderDO::getVersion, version)
                .set(OrderDO::getMarketStatus, "SUBMITTING").set(OrderDO::getMarketAttempts, attempts)
                .set(OrderDO::getVersion, version + 1).set(OrderDO::getMarketError, ""));
        if (changed != 1) return null;
        LocalDateTime now = LocalDateTime.now();
        // This must remain one SQL statement. Updating 4,967 rows one-by-one used to hold the market dispatch for
        // 30–60 seconds before the BatchBet was even sent.
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .in(MarketRouteItemDO::getStatus, SUBMITTABLE)
                .set(MarketRouteItemDO::getStatus, "SUBMITTING")
                .set(MarketRouteItemDO::getAttempts, attempts)
                .set(MarketRouteItemDO::getSubmittedAt, now).set(MarketRouteItemDO::getLastError, ""));
        LotteryConfigDO config = config(userId);
        if (config == null) throw exception(BET_STATE_CHANGED);
        List<Wa55MarketOrderClient.BetRequest> requests = marketRoutingService.expandMarketRequests(routes);
        return new DispatchContext(order.getId(), order.getPeriod(), attempts, order.getMemberName(), order.getContent(),
                new Wa55MarketOrderClient.Credentials(config.getUpstreamUrl(), config.getUpstreamAccount(),
                        credentialService.decrypt(config.getMarketPasswordEncrypted())), requests);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean applyConfirmations(Long userId, String orderId,
                                      List<Wa55MarketOrderClient.BetConfirmation> confirmations) {
        if (confirmations == null || confirmations.isEmpty()) return false;
        List<MarketRouteItemDO> marketRoutes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
        if (marketRoutes.size() == 1 && marketRoutingService.isCompactRoute(marketRoutes.get(0))) {
            return applyCompactConfirmations(userId, orderId, marketRoutes.get(0), confirmations);
        }
        LocalDateTime now = LocalDateTime.now();
        for (Wa55MarketOrderClient.BetConfirmation confirmation : confirmations) {
            routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                    .eq(MarketRouteItemDO::getId, confirmation.routeItemId())
                    .eq(MarketRouteItemDO::getUserId, userId)
                    .in(MarketRouteItemDO::getStatus, "SUBMITTING", "RETRY", "UNKNOWN", "VERIFYING")
                    .set(MarketRouteItemDO::getStatus, "CONFIRMED")
                    .set(MarketRouteItemDO::getMarketGuid, confirmation.guid())
                    .set(MarketRouteItemDO::getMarketBetId, confirmation.marketBetId())
                    .set(MarketRouteItemDO::getMarketSerialNo, confirmation.serialNo())
                    .set(MarketRouteItemDO::getMarketBetCount, confirmation.betCount())
                    .set(MarketRouteItemDO::getMarketOdds,
                            confirmation.odds() == null ? ZERO : confirmation.odds())
                    .set(MarketRouteItemDO::getConfirmedAt, now)
                    .set(MarketRouteItemDO::getLastError, ""));
        }
        marketRoutes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
        if (!marketRoutes.isEmpty() && marketRoutes.stream().allMatch(item -> "CONFIRMED".equals(item.getStatus()))) {
            String serials = marketRoutes.stream().map(MarketRouteItemDO::getMarketSerialNo).filter(value -> !value.isBlank())
                    .distinct().collect(Collectors.joining(","));
            int confirmed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                    .eq(OrderDO::getUserId, userId).in(OrderDO::getMarketStatus, CONFIRMABLE)
                    .set(OrderDO::getMarketStatus, "CONFIRMED").set(OrderDO::getMarketOrderId, serials)
                    .set(OrderDO::getMarketError, ""));
            // A player may have cancelled locally while the external submit was in flight. Keep the
            // cancellation intent, but retain the returned serials for the subsequent external refund.
            orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                    .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "CANCEL_REQUESTED")
                    .set(OrderDO::getMarketOrderId, serials));
            if (confirmed == 1) updateConfirmedMessage(userId, orderId);
            OrderDO current = confirmed == 1 ? null : order(userId, orderId);
            return confirmed == 1 || current != null
                    && Set.of("CONFIRMED", "CANCEL_REQUESTED").contains(current.getMarketStatus());
        }
        return false;
    }

    /**
     * A compact route expands to synthetic request ids such as {@code routeId:42}.  Those ids are intentionally not
     * database rows, so collapse a complete detailed response back onto the one persisted route row.  A partial
     * detailed response stays in normal verification rather than being mistaken for a confirmed order.
     */
    private boolean applyCompactConfirmations(Long userId, String orderId, MarketRouteItemDO route,
                                              List<Wa55MarketOrderClient.BetConfirmation> confirmations) {
        int expected = marketRoutingService.expandMarketRequests(List.of(route)).size();
        BigDecimal acceptedAmount = money(confirmations.stream()
                .map(Wa55MarketOrderClient.BetConfirmation::acceptedAmount).filter(java.util.Objects::nonNull)
                .reduce(ZERO, BigDecimal::add));
        if (confirmations.size() != expected || acceptedAmount.compareTo(money(route.getMarketAmount())) != 0) {
            return false;
        }
        Wa55MarketOrderClient.BetConfirmation first = confirmations.get(0);
        String confirmedSerials = confirmations.stream().map(Wa55MarketOrderClient.BetConfirmation::serialNo)
                .distinct().collect(Collectors.joining(","));
        LocalDateTime now = LocalDateTime.now();
        int changed = routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getId, route.getId()).eq(MarketRouteItemDO::getUserId, userId)
                .in(MarketRouteItemDO::getStatus, "SUBMITTING", "RETRY", "UNKNOWN", "VERIFYING")
                .set(MarketRouteItemDO::getStatus, "CONFIRMED")
                .set(MarketRouteItemDO::getMarketGuid, first.guid())
                .set(MarketRouteItemDO::getMarketBetId, first.marketBetId())
                .set(MarketRouteItemDO::getMarketSerialNo, first.serialNo())
                .set(MarketRouteItemDO::getMarketBetCount, expected)
                .set(MarketRouteItemDO::getMarketOdds, first.odds() == null ? ZERO : first.odds())
                .set(MarketRouteItemDO::getConfirmedAt, now).set(MarketRouteItemDO::getLastError, ""));
        if (changed != 1) return false;
        int orderChanged = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).in(OrderDO::getMarketStatus, CONFIRMABLE)
                .set(OrderDO::getMarketStatus, "CONFIRMED").set(OrderDO::getMarketOrderId, confirmedSerials)
                .set(OrderDO::getMarketError, ""));
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "CANCEL_REQUESTED")
                .set(OrderDO::getMarketOrderId, confirmedSerials));
        if (orderChanged == 1) updateConfirmedMessage(userId, orderId);
        OrderDO current = orderChanged == 1 ? null : order(userId, orderId);
        return orderChanged == 1 || current != null
                && Set.of("CONFIRMED", "CANCEL_REQUESTED").contains(current.getMarketStatus());
    }

    /**
     * The market explicitly accepted these batches, but its detail list has not exposed the external identifiers yet.
     * Keep the order non-submittable and let the recovery worker perform read-only verification.
     */
    @Transactional(rollbackFor = Exception.class)
    public void markAcceptedBatchesVerifying(Long userId, String orderId,
                                             List<Wa55MarketOrderClient.AcceptedBatch> batches,
                                             LocalDateTime nextVerifyAt) {
        if (batches == null || batches.isEmpty()) return;
        OrderDO current = order(userId, orderId);
        java.util.LinkedHashSet<String> serials = new java.util.LinkedHashSet<>(acceptedSerialNos(current));
        batches.stream().map(Wa55MarketOrderClient.AcceptedBatch::serialNo)
                .filter(StrUtil::isNotBlank).forEach(serials::add);
        // A compact route represents the whole accepted batch, while historical orders still have one row per
        // selection. At this point every batch in the result was accepted, so one order-level update is correct for
        // both layouts and avoids looking up thousands of synthetic compact-route ids.
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .in(MarketRouteItemDO::getStatus, "SUBMITTING", "VERIFYING")
                .set(MarketRouteItemDO::getStatus, "VERIFYING")
                .set(MarketRouteItemDO::getNextRetryAt, nextVerifyAt)
                .set(MarketRouteItemDO::getLastError, "盘口已明确受理，等待下注明细标识"));
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).in(OrderDO::getMarketStatus, "SUBMITTING", "VERIFYING")
                .set(OrderDO::getMarketStatus, "VERIFYING")
                .set(!serials.isEmpty(), OrderDO::getMarketOrderId, String.join(",", serials))
                .set(OrderDO::getMarketError, "盘口已明确受理，等待下注明细标识"));
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "CANCEL_REQUESTED")
                .set(!serials.isEmpty(), OrderDO::getMarketOrderId, String.join(",", serials))
                .set(OrderDO::getMarketError, ACCEPTED_WITHOUT_IDS_NOTE));
    }

    public VerificationContext verificationContext(Long userId, String orderId) {
        OrderDO order = order(userId, orderId);
        if (order == null || !"VERIFYING".equals(order.getMarketStatus())) return null;
        List<MarketRouteItemDO> verifying = routes(userId, orderId).stream()
                .filter(item -> "VERIFYING".equals(item.getStatus()))
                .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
        if (verifying.isEmpty()) return null;
        LotteryConfigDO config = config(userId);
        if (config == null) throw exception(BET_STATE_CHANGED);
        List<Wa55MarketOrderClient.BetRequest> requests = marketRoutingService.expandMarketRequests(verifying);
        LocalDateTime submittedAt = verifying.stream().map(MarketRouteItemDO::getSubmittedAt)
                .filter(java.util.Objects::nonNull).min(LocalDateTime::compareTo).orElse(order.getCreateTime());
        return new VerificationContext(orderId, order.getPeriod(), submittedAt,
                new Wa55MarketOrderClient.Credentials(config.getUpstreamUrl(), config.getUpstreamAccount(),
                        credentialService.decrypt(config.getMarketPasswordEncrypted())), requests,
                acceptedSerialNos(order));
    }

    @Transactional(rollbackFor = Exception.class)
    public void scheduleVerificationRetry(Long userId, String orderId, String detail, LocalDateTime nextVerifyAt) {
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .eq(MarketRouteItemDO::getStatus, "VERIFYING")
                .set(MarketRouteItemDO::getNextRetryAt, nextVerifyAt)
                .set(MarketRouteItemDO::getLastError, safe(detail)));
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "VERIFYING")
                .set(OrderDO::getMarketError, safe(detail)));
    }

    /** Persist returned external identifiers even when the upstream result remains ambiguous. */
    @Transactional(rollbackFor = Exception.class)
    public void applyUncertainConfirmations(Long userId, String orderId,
                                            List<Wa55MarketOrderClient.BetConfirmation> confirmations,
                                            String error) {
        if (confirmations == null || confirmations.isEmpty()) return;
        for (Wa55MarketOrderClient.BetConfirmation confirmation : confirmations) {
            routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                    .eq(MarketRouteItemDO::getId, confirmation.routeItemId())
                    .eq(MarketRouteItemDO::getUserId, userId)
                    .in(MarketRouteItemDO::getStatus, "SUBMITTING", "RETRY", "UNKNOWN")
                    .set(MarketRouteItemDO::getStatus, "MANUAL_REVIEW")
                    .set(MarketRouteItemDO::getMarketGuid, confirmation.guid())
                    .set(MarketRouteItemDO::getMarketBetId, confirmation.marketBetId())
                    .set(MarketRouteItemDO::getMarketSerialNo, confirmation.serialNo())
                    .set(MarketRouteItemDO::getMarketBetCount, confirmation.betCount())
                    .set(MarketRouteItemDO::getMarketOdds,
                            confirmation.odds() == null ? ZERO : confirmation.odds())
                    .set(MarketRouteItemDO::getLastError, safe(error)));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRetry(Long userId, String orderId, String error, LocalDateTime nextRetryAt) {
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .in(MarketRouteItemDO::getStatus, "SUBMITTING", "UNKNOWN")
                .set(MarketRouteItemDO::getStatus, "RETRY").set(MarketRouteItemDO::getLastError, safe(error))
                .set(MarketRouteItemDO::getNextRetryAt, nextRetryAt));
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "SUBMITTING")
                .set(OrderDO::getMarketStatus, "RETRY").set(OrderDO::getMarketError, safe(error)));
    }

    @Transactional(rollbackFor = Exception.class)
    public CancelContext claimCancel(Long userId, String orderId) {
        OrderDO order = order(userId, orderId);
        if (order == null || !Set.of("CONFIRMED", "PARTIAL_CONFIRMED", "CANCEL_REQUESTED", "CANCEL_FAILED")
                .contains(order.getMarketStatus())) return null;
        List<MarketRouteItemDO> allMarketRoutes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
        if (allMarketRoutes.isEmpty() || allMarketRoutes.stream().anyMatch(item ->
                !Set.of("CONFIRMED", "CANCEL_FAILED", "CANCEL_PENDING", "FAILED").contains(item.getStatus()))) {
            return null; // Do not cancel a still-submitting or unverified market route.
        }
        List<MarketRouteItemDO> confirmed = allMarketRoutes.stream()
                .filter(item -> "CONFIRMED".equals(item.getStatus()) || "CANCEL_FAILED".equals(item.getStatus())
                        || "CANCEL_PENDING".equals(item.getStatus())).toList();
        List<Wa55MarketOrderClient.CancelRequest> cancelRequests = new ArrayList<>();
        for (MarketRouteItemDO route : confirmed) {
            List<Wa55MarketOrderClient.CancelRequest> references = cancelReferences(route);
            if (references.isEmpty()) return null; // Background ID enrichment will retry; player is already refunded.
            cancelRequests.addAll(references);
        }
        if (cancelRequests.isEmpty()) return null;
        int version = value(order.getVersion(), 0);
        int claimed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getStatus, "已退码")
                .eq(OrderDO::getMarketStatus, order.getMarketStatus()).eq(OrderDO::getVersion, version)
                .set(OrderDO::getMarketStatus, "CANCEL_PENDING").set(OrderDO::getVersion, version + 1)
                .set(OrderDO::getMarketError, ""));
        if (claimed != 1) return null;
        confirmed.forEach(item -> routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getId, item.getId()).eq(MarketRouteItemDO::getUserId, userId)
                .set(MarketRouteItemDO::getStatus, "CANCEL_PENDING")));
        LotteryConfigDO config = config(userId);
        if (config == null) throw exception(BET_STATE_CHANGED);
        return new CancelContext(orderId, order.getPeriod(), "PARTIAL_CONFIRMED".equals(order.getMarketStatus()),
                order.getMemberName(), order.getContent(),
                new Wa55MarketOrderClient.Credentials(
                config.getUpstreamUrl(), config.getUpstreamAccount(),
                        credentialService.decrypt(config.getMarketPasswordEncrypted())), cancelRequests);
    }

    @Transactional(rollbackFor = Exception.class)
    public void finalizeCancel(Long userId, String orderId, String actor, boolean rejectedSubmission) {
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getStatus, "已退码")
                .eq(OrderDO::getMarketStatus, "CANCEL_PENDING")
                .set(OrderDO::getMarketStatus, "CANCELLED").set(OrderDO::getMarketError, ""));
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .in(MarketRouteItemDO::getStatus, "CANCEL_PENDING", "CONFIRMED", "CANCEL_FAILED")
                .set(MarketRouteItemDO::getStatus, "CANCELLED")
                .set(MarketRouteItemDO::getCancelledAt, LocalDateTime.now()).set(MarketRouteItemDO::getLastError, ""));
    }

    /** External failure is the owner's exposure, not a player refund or a failed local order. */
    @Transactional(rollbackFor = Exception.class)
    public void markSubmissionFailed(Long userId, String orderId, String error) {
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "SUBMITTING")
                .set(OrderDO::getMarketStatus, "FAILED").set(OrderDO::getMarketError, safe(error)));
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .eq(MarketRouteItemDO::getStatus, "SUBMITTING")
                .set(MarketRouteItemDO::getStatus, "FAILED").set(MarketRouteItemDO::getLastError, safe(error)));
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getStatus, "已退码")
                .eq(OrderDO::getMarketStatus, "CANCEL_REQUESTED")
                .set(OrderDO::getMarketStatus, "CANCEL_FAILED")
                .set(OrderDO::getMarketError, "提交结果不确定，无法确认外盘退码：" + safe(error)));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markManualReview(Long userId, String orderId, String error) {
        int changed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId)
                .notIn(OrderDO::getMarketStatus, "CONFIRMED", "CANCEL_REQUESTED", "CANCEL_PENDING", "CANCELLED")
                .set(OrderDO::getMarketStatus, "MANUAL_REVIEW").set(OrderDO::getMarketError, safe(error)));
        if (changed != 1) return;
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .in(MarketRouteItemDO::getStatus, "SUBMITTING", "RETRY", "UNKNOWN", "VERIFYING", "CANCEL_PENDING")
                .set(MarketRouteItemDO::getStatus, "MANUAL_REVIEW").set(MarketRouteItemDO::getLastError, safe(error)));
    }

    /**
     * The market has already returned an exact accepted count and amount. A later detail timeout must keep the
     * locally accepted player receipt intact while preventing an accidental automatic resubmission.
     */
    @Transactional(rollbackFor = Exception.class)
    public void markAcceptedDetailsManualReview(Long userId, String orderId, String error) {
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "VERIFYING")
                .set(OrderDO::getMarketStatus, "MANUAL_REVIEW").set(OrderDO::getMarketError, safe(error)));
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .eq(MarketRouteItemDO::getStatus, "VERIFYING")
                .set(MarketRouteItemDO::getStatus, "MANUAL_REVIEW").set(MarketRouteItemDO::getLastError, safe(error)));
    }

    /**
     * An exact upstream accepted count and amount are conclusive that the order reached the market.  Some market
     * accounts never expose one identifier per row afterwards. This method only updates external bookkeeping;
     * it never submits, cancels, refunds, or changes the locally accepted player order.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmAcceptedDetailsWithoutIdentifiers(Long userId, String orderId) {
        OrderDO order = order(userId, orderId);
        if (order == null || !"未开奖".equals(order.getStatus())
                || !Set.of("VERIFYING", "MANUAL_REVIEW").contains(order.getMarketStatus())) return false;
        List<MarketRouteItemDO> marketRoutes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
        boolean acceptedWithoutIdentifiers = !marketRoutes.isEmpty()
                && marketRoutes.stream().allMatch(item -> Set.of("VERIFYING", "MANUAL_REVIEW").contains(item.getStatus())
                        && item.getMarketGuid() != null && !item.getMarketGuid().isBlank()
                        && (item.getMarketBetId() == null || item.getMarketBetId().isBlank()));
        if (!acceptedWithoutIdentifiers) return false;
        int version = value(order.getVersion(), 0);
        String note = ACCEPTED_WITHOUT_IDS_NOTE;
        int changed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getStatus, "未开奖")
                .in(OrderDO::getMarketStatus, "VERIFYING", "MANUAL_REVIEW").eq(OrderDO::getVersion, version)
                .set(OrderDO::getMarketStatus, "CONFIRMED").set(OrderDO::getMarketError, note)
                .set(OrderDO::getVersion, version + 1));
        if (changed != 1) return false;
        LocalDateTime now = LocalDateTime.now();
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .in(MarketRouteItemDO::getStatus, "VERIFYING", "MANUAL_REVIEW")
                .set(MarketRouteItemDO::getStatus, "CONFIRMED").set(MarketRouteItemDO::getConfirmedAt, now)
                .set(MarketRouteItemDO::getLastError, note));
        return true;
    }

    /** The accepted order stays CONFIRMED while a separate read-only worker recovers its cancel IDs. */
    public VerificationContext acceptedDetailContext(Long userId, String orderId) {
        OrderDO order = order(userId, orderId);
        if (order == null || !("未开奖".equals(order.getStatus()) || "已退码".equals(order.getStatus()))
                || !("CONFIRMED".equals(order.getMarketStatus()) || "CANCEL_REQUESTED".equals(order.getMarketStatus()))
                || !isAcceptedWithoutIds(order.getMarketError())) return null;
        List<MarketRouteItemDO> marketRoutes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
        if (marketRoutes.size() != 1 || !marketRoutingService.isCompactRoute(marketRoutes.get(0))
                || StrUtil.isNotBlank(marketRoutes.get(0).getMarketCancelReferencesJson())) return null;
        LotteryConfigDO config = config(userId);
        if (config == null) return null;
        return new VerificationContext(orderId, order.getPeriod(), marketRoutes.get(0).getSubmittedAt(),
                new Wa55MarketOrderClient.Credentials(config.getUpstreamUrl(), config.getUpstreamAccount(),
                        credentialService.decrypt(config.getMarketPasswordEncrypted())),
                marketRoutingService.expandMarketRequests(marketRoutes), acceptedSerialNos(order));
    }

    /** Known IDs from this owner's other orders in the same period, including already cancelled orders. */
    public Set<String> claimedMarketBetIds(Long userId, String period, String exceptOrderId) {
        List<MarketRouteItemDO> known = DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectList(
                new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, userId)
                        .eq(MarketRouteItemDO::getPeriod, period).ne(MarketRouteItemDO::getOrderId, exceptOrderId)
                        .and(wrapper -> wrapper.isNotNull(MarketRouteItemDO::getMarketBetId)
                                .ne(MarketRouteItemDO::getMarketBetId, "")
                                .or().isNotNull(MarketRouteItemDO::getMarketCancelReferencesJson)
                                .ne(MarketRouteItemDO::getMarketCancelReferencesJson, ""))
                        .select(MarketRouteItemDO::getMarketBetId,
                                MarketRouteItemDO::getMarketCancelReferencesJson,
                                MarketRouteItemDO::getMarketBetCount)));
        Set<String> ids = new java.util.HashSet<>();
        for (MarketRouteItemDO route : known) {
            if (StrUtil.isNotBlank(route.getMarketCancelReferencesJson())) {
                cancelReferences(route).forEach(reference -> ids.add(reference.marketBetId()));
            } else if (StrUtil.isNotBlank(route.getMarketBetId())) {
                ids.add(route.getMarketBetId());
            }
        }
        return ids;
    }

    /** Persist a complete matched set atomically; a partial detail page must never enable cancellation. */
    @Transactional(rollbackFor = Exception.class)
    public boolean attachAcceptedCancelReferences(Long userId, String orderId,
                                                  List<Wa55MarketOrderClient.BetConfirmation> confirmations) {
        OrderDO order = order(userId, orderId);
        if (order == null || !Set.of("未开奖", "已退码").contains(order.getStatus())
                || !Set.of("CONFIRMED", "CANCEL_REQUESTED").contains(order.getMarketStatus())
                || !isAcceptedWithoutIds(order.getMarketError())) return false;
        List<MarketRouteItemDO> marketRoutes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
        if (marketRoutes.size() != 1 || !marketRoutingService.isCompactRoute(marketRoutes.get(0))) return false;
        MarketRouteItemDO route = marketRoutes.get(0);
        int expected = marketRoutingService.expandMarketRequests(List.of(route)).size();
        if (confirmations == null || confirmations.size() != expected || expected == 0) return false;
        BigDecimal matchedAmount = confirmations.stream().map(Wa55MarketOrderClient.BetConfirmation::acceptedAmount)
                .filter(java.util.Objects::nonNull).reduce(ZERO, BigDecimal::add);
        if (matchedAmount.compareTo(money(route.getMarketAmount())) != 0) return false;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Wa55MarketOrderClient.BetConfirmation confirmation : confirmations) {
            if (StrUtil.isBlank(confirmation.marketBetId()) || StrUtil.isBlank(confirmation.serialNo())
                    || confirmation.betCount() <= 0) return false;
            counts.merge(confirmation.marketBetId(), confirmation.betCount(), Math::max);
        }
        if (counts.values().stream().mapToInt(Integer::intValue).sum() != expected) return false;
        if (!java.util.Collections.disjoint(counts.keySet(),
                claimedMarketBetIds(userId, order.getPeriod(), orderId))) return false;
        List<Map<String, Object>> references = counts.entrySet().stream()
                .map(item -> Map.<String, Object>of("betId", item.getKey(), "count", item.getValue())).toList();
        Wa55MarketOrderClient.BetConfirmation first = confirmations.get(0);
        String confirmedSerials = confirmations.stream().map(Wa55MarketOrderClient.BetConfirmation::serialNo)
                .distinct().collect(Collectors.joining(","));
        int changed = routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getId, route.getId()).eq(MarketRouteItemDO::getUserId, userId)
                .in(MarketRouteItemDO::getStatus, "CONFIRMED", "VERIFYING")
                .isNull(MarketRouteItemDO::getMarketCancelReferencesJson)
                .set(MarketRouteItemDO::getMarketCancelReferencesJson, JSONUtil.toJsonStr(references))
                .set(MarketRouteItemDO::getMarketBetId, first.marketBetId())
                .set(MarketRouteItemDO::getMarketSerialNo, first.serialNo())
                .set(MarketRouteItemDO::getMarketBetCount, expected)
                .set(MarketRouteItemDO::getStatus, "CONFIRMED")
                .set(MarketRouteItemDO::getLastError, ""));
        if (changed != 1) return false;
        int orderChanged = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).in(OrderDO::getStatus, "未开奖", "已退码")
                .in(OrderDO::getMarketStatus, "CONFIRMED", "CANCEL_REQUESTED")
                .likeRight(OrderDO::getMarketError, ACCEPTED_WITHOUT_IDS_PREFIX)
                .set(OrderDO::getMarketError, "").set(OrderDO::getMarketOrderId, confirmedSerials));
        if (orderChanged != 1) throw new IllegalStateException("订单状态已变化，退码编号未写入");
        return true;
    }

    public boolean hasCompleteCancelReferences(Long userId, String orderId) {
        List<MarketRouteItemDO> marketRoutes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
        return !marketRoutes.isEmpty() && marketRoutes.stream()
                .allMatch(item -> !cancelReferences(item).isEmpty());
    }

    private List<Wa55MarketOrderClient.CancelRequest> cancelReferences(MarketRouteItemDO route) {
        int expected = Math.max(1, value(route.getMarketBetCount(), 1));
        if (expected > 1 && StrUtil.isNotBlank(route.getMarketCancelReferencesJson())) {
            try {
                JSONArray values = JSONUtil.parseArray(route.getMarketCancelReferencesJson());
                List<Wa55MarketOrderClient.CancelRequest> result = new ArrayList<>(values.size());
                java.util.Set<String> seen = new java.util.HashSet<>();
                int count = 0;
                for (Object value : values) {
                    JSONObject item = JSONUtil.parseObj(value);
                    String betId = item.getStr("betId");
                    int betCount = value(item.getInt("count"), 0);
                    if (StrUtil.isBlank(betId) || betCount <= 0 || !seen.add(betId)) return List.of();
                    count += betCount;
                    result.add(new Wa55MarketOrderClient.CancelRequest(betId, betCount));
                }
                return count == expected ? result : List.of();
            } catch (RuntimeException ex) {
                return List.of();
            }
        }
        return StrUtil.isBlank(route.getMarketBetId()) ? List.of()
                : List.of(new Wa55MarketOrderClient.CancelRequest(route.getMarketBetId(), expected));
    }

    @Transactional(rollbackFor = Exception.class)
    public ManualReviewResult confirmManualReviewAccepted(Long userId, String orderId, String externalOrderId) {
        OrderDO order = order(userId, orderId);
        if (order == null || !"未开奖".equals(order.getStatus())
                || !"MANUAL_REVIEW".equals(order.getMarketStatus())) {
            throw exception(BET_STATE_CHANGED);
        }
        List<MarketRouteItemDO> marketRoutes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
        if (marketRoutes.isEmpty()) throw exception(BET_STATE_CHANGED);
        int version = value(order.getVersion(), 0);
        int changed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getId, orderId).eq(OrderDO::getUserId, userId)
                .eq(OrderDO::getStatus, "未开奖").eq(OrderDO::getMarketStatus, "MANUAL_REVIEW")
                .eq(OrderDO::getVersion, version)
                .set(OrderDO::getMarketStatus, "CONFIRMED").set(OrderDO::getMarketOrderId, externalOrderId)
                .set(OrderDO::getMarketError, "").set(OrderDO::getVersion, version + 1));
        if (changed != 1) throw exception(BET_STATE_CHANGED);
        LocalDateTime now = LocalDateTime.now();
        int routeCount = marketRoutes.size();
        int confirmed = routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .gt(MarketRouteItemDO::getMarketAmount, ZERO)
                .set(MarketRouteItemDO::getStatus, "CONFIRMED")
                .set(MarketRouteItemDO::getMarketBetId, externalOrderId)
                .set(MarketRouteItemDO::getMarketSerialNo, externalOrderId)
                .set(MarketRouteItemDO::getMarketBetCount, routeCount)
                .set(MarketRouteItemDO::getConfirmedAt, now).set(MarketRouteItemDO::getLastError, ""));
        if (confirmed != routeCount) throw exception(BET_STATE_CHANGED);
        updateConfirmedMessage(userId, orderId);
        BigDecimal amount = marketRoutes.stream().map(MarketRouteItemDO::getMarketAmount)
                .map(this::money).reduce(ZERO, BigDecimal::add);
        return new ManualReviewResult(routeCount, money(amount), "CONFIRMED");
    }

    @Transactional(rollbackFor = Exception.class)
    public ManualReviewResult confirmManualReviewNotAccepted(Long userId, String orderId,
                                                              String actor, String reason) {
        OrderDO order = order(userId, orderId);
        if (order == null || !"未开奖".equals(order.getStatus())
                || !"MANUAL_REVIEW".equals(order.getMarketStatus())) {
            throw exception(BET_STATE_CHANGED);
        }
        int version = value(order.getVersion(), 0);
        int claimed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getId, orderId).eq(OrderDO::getUserId, userId)
                .eq(OrderDO::getStatus, "未开奖").eq(OrderDO::getMarketStatus, "MANUAL_REVIEW")
                .eq(OrderDO::getVersion, version)
                .set(OrderDO::getMarketStatus, "FAILED").set(OrderDO::getMarketError, safe(reason))
                .set(OrderDO::getVersion, version + 1));
        if (claimed != 1) throw exception(BET_STATE_CHANGED);
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .eq(MarketRouteItemDO::getStatus, "MANUAL_REVIEW")
                .set(MarketRouteItemDO::getStatus, "FAILED").set(MarketRouteItemDO::getLastError, safe(reason)));
        return new ManualReviewResult(0, money(order.getAmount()), "FAILED");
    }

    @Transactional(rollbackFor = Exception.class)
    public void markPartialRejected(Long userId, String orderId, String error) {
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "SUBMITTING")
                .set(OrderDO::getMarketStatus, "PARTIAL_CONFIRMED").set(OrderDO::getMarketError, safe(error)));
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .in(MarketRouteItemDO::getStatus, "SUBMITTING", "RETRY", "UNKNOWN")
                .set(MarketRouteItemDO::getStatus, "FAILED").set(MarketRouteItemDO::getLastError, safe(error)));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markCancelFailed(Long userId, String orderId, String error) {
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "CANCEL_PENDING")
                .set(OrderDO::getMarketStatus, "CANCEL_FAILED").set(OrderDO::getMarketError, safe(error)));
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .eq(MarketRouteItemDO::getStatus, "CANCEL_PENDING")
                .set(MarketRouteItemDO::getStatus, "CANCEL_FAILED").set(MarketRouteItemDO::getLastError, safe(error)));
    }

    /** A definite external rejection is the owner's exposure; the player cancellation stays final. */
    @Transactional(rollbackFor = Exception.class)
    public void markCancelRejected(Long userId, String orderId, String error) {
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "CANCEL_PENDING")
                .set(OrderDO::getMarketStatus, "CANCEL_FAILED").set(OrderDO::getMarketError, safe(error)));
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .eq(MarketRouteItemDO::getStatus, "CANCEL_PENDING")
                .set(MarketRouteItemDO::getStatus, "CANCEL_FAILED").set(MarketRouteItemDO::getLastError, safe(error)));
    }

    public List<OrderDO> recoverableOrders() {
        LocalDateTime stale = LocalDateTime.now().minusMinutes(2);
        LocalDateTime now = LocalDateTime.now();
        // Old unresolved reviews must not fill a single LIMIT window and starve newly accepted bets.
        List<OrderDO> candidates = new ArrayList<>();
        candidates.addAll(DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getMarketStatus, "PENDING").orderByAsc(OrderDO::getCreateTime).last("LIMIT 200"))));
        candidates.addAll(DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getMarketStatus, "RETRY").orderByAsc(OrderDO::getCreateTime).last("LIMIT 100"))));
        candidates.addAll(DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getMarketStatus, "CANCEL_REQUESTED").orderByAsc(OrderDO::getCreateTime).last("LIMIT 100"))));
        candidates.addAll(DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .in(OrderDO::getMarketStatus, "SUBMITTING", "VERIFYING", "MANUAL_REVIEW",
                        "CANCEL_PENDING")
                .orderByAsc(OrderDO::getCreateTime).last("LIMIT 100"))));
        return candidates.stream().filter(order -> {
            if ("RETRY".equals(order.getMarketStatus())) {
                return DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectCount(
                        new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, order.getUserId())
                                .eq(MarketRouteItemDO::getOrderId, order.getId())
                                .eq(MarketRouteItemDO::getStatus, "RETRY")
                                .and(wrapper -> wrapper.isNull(MarketRouteItemDO::getNextRetryAt)
                                        .or().le(MarketRouteItemDO::getNextRetryAt, now)))) > 0;
            }
            if ("VERIFYING".equals(order.getMarketStatus())) {
                return DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectCount(
                        new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, order.getUserId())
                                .eq(MarketRouteItemDO::getOrderId, order.getId())
                                .eq(MarketRouteItemDO::getStatus, "VERIFYING")
                                .and(wrapper -> wrapper.isNull(MarketRouteItemDO::getNextRetryAt)
                                        .or().le(MarketRouteItemDO::getNextRetryAt, now)))) > 0;
            }
            if ("MANUAL_REVIEW".equals(order.getMarketStatus())) {
                List<MarketRouteItemDO> routes = routes(order.getUserId(), order.getId()).stream()
                        .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
                return !routes.isEmpty() && routes.stream().allMatch(item ->
                        "MANUAL_REVIEW".equals(item.getStatus()) && StrUtil.isNotBlank(item.getMarketGuid())
                                && StrUtil.isBlank(item.getMarketBetId()));
            }
            return order.getUpdateTime() == null || !order.getUpdateTime().isAfter(stale);
        }).toList();
    }

    private boolean isAcceptedWithoutIds(String marketError) {
        return marketError != null && marketError.startsWith(ACCEPTED_WITHOUT_IDS_PREFIX);
    }

    private OrderDO order(Long userId, String orderId) {
        return DataPermissionUtils.executeIgnore(() -> orderMapper.selectOne(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getId, orderId).eq(OrderDO::getUserId, userId).last("LIMIT 1")));
    }

    private LotteryConfigDO config(Long userId) {
        return DataPermissionUtils.executeIgnore(() -> lotteryConfigMapper.selectOne(new LambdaQueryWrapper<LotteryConfigDO>()
                .eq(LotteryConfigDO::getUserId, userId).last("LIMIT 1")));
    }

    private List<MarketRouteItemDO> routes(Long userId, String orderId) {
        return DataPermissionUtils.executeIgnore(() -> routeItemMapper.selectList(
                new LambdaQueryWrapper<MarketRouteItemDO>().eq(MarketRouteItemDO::getUserId, userId)
                        .eq(MarketRouteItemDO::getOrderId, orderId).orderByAsc(MarketRouteItemDO::getCreateTime)));
    }

    private void updateConfirmedMessage(Long userId, String orderId) {
        updateBetReceiptMessage(userId, orderId, true);
    }

    private void updateAcceptedWithoutDetailsMessage(Long userId, String orderId) {
        MessageDO existing = DataPermissionUtils.executeIgnore(() -> messageMapper.selectOne(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                        .eq(MessageDO::getOrderId, orderId).eq(MessageDO::getCommandType, "BET")
                        .last("LIMIT 1")));
        if (existing != null && StrUtil.isNotBlank(existing.getReply())) return;
        OrderDO order = order(userId, orderId);
        if (order == null) return;
        MemberDO member = DataPermissionUtils.executeIgnore(() -> memberMapper.selectOne(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getId, order.getMemberId())
                        .eq(MemberDO::getUserId, userId).last("LIMIT 1")));
        long itemCount = value(order.getItemCount(), 0);
        if (itemCount <= 0) {
            itemCount = DataPermissionUtils.executeIgnore(() -> betItemMapper.selectCount(
                    new LambdaQueryWrapper<BetItemDO>().eq(BetItemDO::getUserId, userId)
                            .eq(BetItemDO::getOrderId, orderId)));
        }
        String reply = robotReplyTemplate.betReceiptAcceptedWithoutDetails(order.getMemberName(), order.getPeriod(),
                order.getContent(), value(order.getPeriodSequence(), 0), Math.toIntExact(itemCount),
                money(order.getAmount()), member == null ? ZERO : money(member.getBalance()));
        messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getOrderId, orderId).set(MessageDO::getStatus, "已受理")
                .set(MessageDO::getReply, reply).set(MessageDO::getProcessedAt, LocalDateTime.now())
                .set(MessageDO::getError, ""));
    }

    private void updateBetReceiptMessage(Long userId, String orderId, boolean detailsConfirmed) {
        MessageDO existing = DataPermissionUtils.executeIgnore(() -> messageMapper.selectOne(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                        .eq(MessageDO::getOrderId, orderId).eq(MessageDO::getCommandType, "BET")
                        .last("LIMIT 1")));
        if (existing != null && StrUtil.isNotBlank(existing.getReply())) return;
        OrderDO order = order(userId, orderId);
        if (order == null) return;
        MemberDO member = DataPermissionUtils.executeIgnore(() -> memberMapper.selectOne(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getId, order.getMemberId())
                        .eq(MemberDO::getUserId, userId).last("LIMIT 1")));
        long itemCount = value(order.getItemCount(), 0);
        if (itemCount <= 0) {
            itemCount = DataPermissionUtils.executeIgnore(() -> betItemMapper.selectCount(
                    new LambdaQueryWrapper<BetItemDO>().eq(BetItemDO::getUserId, userId)
                            .eq(BetItemDO::getOrderId, orderId)));
        }
        String reply = detailsConfirmed
                ? robotReplyTemplate.betReceipt(order.getMemberName(), order.getPeriod(), order.getContent(),
                        value(order.getPeriodSequence(), 0), Math.toIntExact(itemCount), money(order.getAmount()),
                        member == null ? ZERO : money(member.getBalance()))
                : robotReplyTemplate.betReceiptAwaitingDetails(order.getMemberName(), order.getPeriod(),
                        order.getContent(), value(order.getPeriodSequence(), 0), Math.toIntExact(itemCount),
                        money(order.getAmount()), member == null ? ZERO : money(member.getBalance()));
        messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getOrderId, orderId)
                .set(MessageDO::getStatus, detailsConfirmed ? "已下单" : "明细确认中")
                .set(MessageDO::getReply, reply).set(MessageDO::getProcessedAt, LocalDateTime.now())
                .set(MessageDO::getError, ""));
    }

    private int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String safe(String value) {
        if (value == null) return "";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private List<String> acceptedSerialNos(OrderDO order) {
        if (order == null || StrUtil.isBlank(order.getMarketOrderId())) return List.of();
        return java.util.Arrays.stream(order.getMarketOrderId().split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    public record DispatchContext(String orderId, String period, int attempts, String memberName, String instruction,
                                  Wa55MarketOrderClient.Credentials credentials,
                                  List<Wa55MarketOrderClient.BetRequest> requests) {
        public DispatchContext(String orderId, String period, int attempts,
                               Wa55MarketOrderClient.Credentials credentials,
                               List<Wa55MarketOrderClient.BetRequest> requests) {
            this(orderId, period, attempts, "", "", credentials, requests);
        }
    }

    public record VerificationContext(String orderId, String period, LocalDateTime submittedAt,
                                      Wa55MarketOrderClient.Credentials credentials,
                                      List<Wa55MarketOrderClient.BetRequest> requests,
                                      List<String> acceptedSerialNos) {
        public VerificationContext(String orderId, String period, LocalDateTime submittedAt,
                                   Wa55MarketOrderClient.Credentials credentials,
                                   List<Wa55MarketOrderClient.BetRequest> requests) {
            this(orderId, period, submittedAt, credentials, requests, List.of());
        }
    }

    public record CancelContext(String orderId, String period, boolean rejectedSubmission,
                                String memberName, String instruction,
                                Wa55MarketOrderClient.Credentials credentials,
                                List<Wa55MarketOrderClient.CancelRequest> requests) {
        public CancelContext(String orderId, String period, boolean rejectedSubmission,
                             Wa55MarketOrderClient.Credentials credentials,
                             List<Wa55MarketOrderClient.CancelRequest> requests) {
            this(orderId, period, rejectedSubmission, "", "", credentials, requests);
        }
    }
    public record ManualReviewResult(int routeCount, BigDecimal amount, String marketStatus) {}
}
