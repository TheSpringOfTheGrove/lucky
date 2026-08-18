package com.hnz.luck5.module.lottery.service;

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
import java.util.Set;
import java.util.stream.Collectors;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.BET_STATE_CHANGED;

@Service
public class LotteryMarketOrderStateService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final Set<String> SUBMITTABLE = Set.of("PENDING", "RETRY");
    private static final Set<String> CONFIRMABLE = Set.of("SUBMITTING", "RETRY", "UNKNOWN", "VERIFYING");

    @Resource private OrderMapper orderMapper;
    @Resource private MarketRouteItemMapper routeItemMapper;
    @Resource private LotteryConfigMapper lotteryConfigMapper;
    @Resource private IssueMapper issueMapper;
    @Resource private MemberMapper memberMapper;
    @Resource private MessageMapper messageMapper;
    @Resource private BetItemMapper betItemMapper;
    @Resource private MarketCredentialService credentialService;
    @Resource private LotteryBalanceLedgerService balanceLedgerService;
    @Resource private LotteryRobotReplyTemplate robotReplyTemplate;
    @Resource private LotteryIssueFreshnessPolicy issueFreshnessPolicy;

    public boolean isCancellationWindowOpen(Long userId, String period) {
        IssueDO issue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                        .eq(IssueDO::getPeriod, period).last("LIMIT 1")));
        return issue != null && "OPEN".equals(issue.getStatus())
                && !issueFreshnessPolicy.isStale(issue) && !issueFreshnessPolicy.isBettingClosed(issue);
    }

    @Transactional(rollbackFor = Exception.class)
    public DispatchContext claimSubmit(Long userId, String orderId) {
        OrderDO order = order(userId, orderId);
        if (order == null || !SUBMITTABLE.contains(order.getMarketStatus())) return null;
        int version = value(order.getVersion(), 0);
        int attempts = value(order.getMarketAttempts(), 0) + 1;
        int changed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getId, orderId).eq(OrderDO::getUserId, userId)
                .eq(OrderDO::getStatus, "未开奖").in(OrderDO::getMarketStatus, SUBMITTABLE)
                .eq(OrderDO::getVersion, version)
                .set(OrderDO::getMarketStatus, "SUBMITTING").set(OrderDO::getMarketAttempts, attempts)
                .set(OrderDO::getVersion, version + 1).set(OrderDO::getMarketError, ""));
        if (changed != 1) return null;
        List<MarketRouteItemDO> routes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0)
                .filter(item -> SUBMITTABLE.contains(item.getStatus()))
                .toList();
        LocalDateTime now = LocalDateTime.now();
        routes.forEach(route -> routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getId, route.getId()).eq(MarketRouteItemDO::getUserId, userId)
                .in(MarketRouteItemDO::getStatus, SUBMITTABLE)
                .set(MarketRouteItemDO::getStatus, "SUBMITTING")
                .set(MarketRouteItemDO::getAttempts, value(route.getAttempts(), 0) + 1)
                .set(MarketRouteItemDO::getSubmittedAt, now).set(MarketRouteItemDO::getLastError, "")));
        LotteryConfigDO config = config(userId);
        if (config == null) throw exception(BET_STATE_CHANGED);
        List<Wa55MarketOrderClient.BetRequest> requests = routes.stream().map(route ->
                new Wa55MarketOrderClient.BetRequest(route.getId(), order.getPeriod(), route.getPlay(),
                        route.getSelection(), route.getMarketAmount(), route.getMarketGuid(),
                        route.getPlay() != null && route.getPlay().endsWith("字现"))).toList();
        return new DispatchContext(order.getId(), order.getPeriod(), attempts,
                new Wa55MarketOrderClient.Credentials(config.getUpstreamUrl(), config.getUpstreamAccount(),
                        credentialService.decrypt(config.getMarketPasswordEncrypted())), requests);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean applyConfirmations(Long userId, String orderId,
                                      List<Wa55MarketOrderClient.BetConfirmation> confirmations) {
        if (confirmations == null || confirmations.isEmpty()) return false;
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
        List<MarketRouteItemDO> routes = routes(userId, orderId).stream()
                .filter(item -> money(item.getMarketAmount()).signum() > 0).toList();
        if (!routes.isEmpty() && routes.stream().allMatch(item -> "CONFIRMED".equals(item.getStatus()))) {
            String serials = routes.stream().map(MarketRouteItemDO::getMarketSerialNo).filter(value -> !value.isBlank())
                    .distinct().collect(Collectors.joining(","));
            int confirmed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                    .eq(OrderDO::getUserId, userId).in(OrderDO::getMarketStatus, CONFIRMABLE)
                    .set(OrderDO::getMarketStatus, "CONFIRMED").set(OrderDO::getMarketOrderId, serials)
                    .set(OrderDO::getMarketError, ""));
            if (confirmed == 1) updateConfirmedMessage(userId, orderId);
            OrderDO current = confirmed == 1 ? null : order(userId, orderId);
            return confirmed == 1 || current != null && "CONFIRMED".equals(current.getMarketStatus());
        }
        return false;
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
        for (Wa55MarketOrderClient.AcceptedBatch batch : batches) {
            routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                    .eq(MarketRouteItemDO::getUserId, userId).in(MarketRouteItemDO::getId, batch.routeItemIds())
                    .in(MarketRouteItemDO::getStatus, "SUBMITTING", "VERIFYING")
                    .set(MarketRouteItemDO::getStatus, "VERIFYING")
                    .set(MarketRouteItemDO::getMarketGuid, batch.guid())
                    .set(MarketRouteItemDO::getNextRetryAt, nextVerifyAt)
                    .set(MarketRouteItemDO::getLastError, "盘口已明确受理，等待下注明细标识"));
        }
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).in(OrderDO::getMarketStatus, "SUBMITTING", "VERIFYING")
                .set(OrderDO::getMarketStatus, "VERIFYING")
                .set(OrderDO::getMarketError, "盘口已明确受理，等待下注明细标识"));
        updateBetReceiptMessage(userId, orderId, false);
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
        List<Wa55MarketOrderClient.BetRequest> requests = verifying.stream().map(route ->
                new Wa55MarketOrderClient.BetRequest(route.getId(), order.getPeriod(), route.getPlay(),
                        route.getSelection(), route.getMarketAmount(), route.getMarketGuid(),
                        route.getPlay() != null && route.getPlay().endsWith("字现"))).toList();
        LocalDateTime submittedAt = verifying.stream().map(MarketRouteItemDO::getSubmittedAt)
                .filter(java.util.Objects::nonNull).min(LocalDateTime::compareTo).orElse(order.getCreateTime());
        return new VerificationContext(orderId, order.getPeriod(), submittedAt,
                new Wa55MarketOrderClient.Credentials(config.getUpstreamUrl(), config.getUpstreamAccount(),
                        credentialService.decrypt(config.getMarketPasswordEncrypted())), requests);
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

    /**
     * Persists every external identifier returned by an ambiguous response without declaring the order confirmed.
     * Operators can reconcile these identifiers manually, while automatic retry, refund and settlement stay blocked.
     */
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
        int version = value(order.getVersion(), 0);
        int claimed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getStatus, "未开奖")
                .eq(OrderDO::getMarketStatus, order.getMarketStatus()).eq(OrderDO::getVersion, version)
                .set(OrderDO::getMarketStatus, "CANCEL_PENDING").set(OrderDO::getVersion, version + 1)
                .set(OrderDO::getMarketError, ""));
        if (claimed != 1) return null;
        List<MarketRouteItemDO> confirmed = routes(userId, orderId).stream()
                .filter(item -> "CONFIRMED".equals(item.getStatus()) || "CANCEL_FAILED".equals(item.getStatus())
                        || "CANCEL_PENDING".equals(item.getStatus()))
                .filter(item -> item.getMarketBetId() != null && !item.getMarketBetId().isBlank()).toList();
        confirmed.forEach(item -> routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getId, item.getId()).eq(MarketRouteItemDO::getUserId, userId)
                .set(MarketRouteItemDO::getStatus, "CANCEL_PENDING")));
        LotteryConfigDO config = config(userId);
        if (config == null) throw exception(BET_STATE_CHANGED);
        return new CancelContext(orderId, order.getPeriod(), "PARTIAL_CONFIRMED".equals(order.getMarketStatus()),
                new Wa55MarketOrderClient.Credentials(
                config.getUpstreamUrl(), config.getUpstreamAccount(),
                        credentialService.decrypt(config.getMarketPasswordEncrypted())), confirmed.stream()
                .map(item -> new Wa55MarketOrderClient.CancelRequest(item.getMarketBetId(),
                        Math.max(1, value(item.getMarketBetCount(), 1)))).toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public void finalizeCancel(Long userId, String orderId, String actor, boolean rejectedSubmission) {
        refundAndClose(userId, orderId, "CANCELLED", "已退码", actor,
                rejectedSubmission ? "外部订单部分受理后已全部撤销" : "会员退码成功", rejectedSubmission);
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .in(MarketRouteItemDO::getStatus, "CANCEL_PENDING", "CONFIRMED", "CANCEL_FAILED")
                .set(MarketRouteItemDO::getStatus, "CANCELLED")
                .set(MarketRouteItemDO::getCancelledAt, LocalDateTime.now()).set(MarketRouteItemDO::getLastError, ""));
    }

    @Transactional(rollbackFor = Exception.class)
    public void failAndRefund(Long userId, String orderId, String error) {
        refundAndClose(userId, orderId, "FAILED", "已退码", "system", "外部订单提交失败：" + safe(error), true);
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .notIn(MarketRouteItemDO::getStatus, "CANCELLED", "LOCAL_CONFIRMED")
                .set(MarketRouteItemDO::getStatus, "FAILED").set(MarketRouteItemDO::getLastError, safe(error)));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markManualReview(Long userId, String orderId, String error) {
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).ne(OrderDO::getMarketStatus, "CONFIRMED")
                .set(OrderDO::getMarketStatus, "MANUAL_REVIEW").set(OrderDO::getMarketError, safe(error)));
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .in(MarketRouteItemDO::getStatus, "SUBMITTING", "RETRY", "UNKNOWN", "VERIFYING", "CANCEL_PENDING")
                .set(MarketRouteItemDO::getStatus, "MANUAL_REVIEW").set(MarketRouteItemDO::getLastError, safe(error)));
        messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getOrderId, orderId).set(MessageDO::getStatus, "处理中")
                .set(MessageDO::getReply, "").set(MessageDO::getError, safe(error)));
    }

    /**
     * The market has already returned an exact accepted count and amount. A later detail timeout must keep the
     * successful player receipt intact while blocking cancellation, settlement and any automatic resubmission.
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
        messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getOrderId, orderId).set(MessageDO::getStatus, "明细确认中")
                .set(MessageDO::getError, safe(error)));
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
                .set(OrderDO::getMarketStatus, "MANUAL_REFUNDING").set(OrderDO::getVersion, version + 1));
        if (claimed != 1) throw exception(BET_STATE_CHANGED);
        refundAndClose(userId, orderId, "FAILED", "已退码", actor,
                "人工核对确认盘口未受理：" + safe(reason), true);
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
        messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getOrderId, orderId).set(MessageDO::getStatus, "处理中")
                .set(MessageDO::getReply, "").set(MessageDO::getError, safe(error)));
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
        messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getOrderId, orderId).set(MessageDO::getStatus, "退码待确认")
                .set(MessageDO::getError, safe(error)));
    }

    /**
     * A definite rejection means the original external bet is still valid. Restore the confirmed state so the
     * period can settle normally, while clearly telling the player that this order cannot be cancelled.
     */
    @Transactional(rollbackFor = Exception.class)
    public void markCancelRejected(Long userId, String orderId, String error) {
        orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getMarketStatus, "CANCEL_PENDING")
                .set(OrderDO::getMarketStatus, "CONFIRMED").set(OrderDO::getMarketError, safe(error)));
        routeItemMapper.update(null, new LambdaUpdateWrapper<MarketRouteItemDO>()
                .eq(MarketRouteItemDO::getUserId, userId).eq(MarketRouteItemDO::getOrderId, orderId)
                .eq(MarketRouteItemDO::getStatus, "CANCEL_PENDING")
                .set(MarketRouteItemDO::getStatus, "CONFIRMED").set(MarketRouteItemDO::getLastError, safe(error)));
        messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getOrderId, orderId).set(MessageDO::getStatus, "退码失败")
                .set(MessageDO::getError, safe(error)));
    }

    public List<OrderDO> recoverableOrders() {
        LocalDateTime stale = LocalDateTime.now().minusMinutes(2);
        LocalDateTime now = LocalDateTime.now();
        List<OrderDO> candidates = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .in(OrderDO::getMarketStatus, "PENDING", "RETRY", "SUBMITTING", "VERIFYING",
                        "CANCEL_REQUESTED", "CANCEL_PENDING")
                .orderByAsc(OrderDO::getCreateTime).last("LIMIT 200")));
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
            return order.getUpdateTime() == null || !order.getUpdateTime().isAfter(stale);
        }).toList();
    }

    private void refundAndClose(Long userId, String orderId, String marketStatus, String orderStatus,
                                String actor, String reason, boolean failedSubmission) {
        OrderDO order = order(userId, orderId);
        if (order == null || !"未开奖".equals(order.getStatus())) return;
        MessageDO orderMessage = DataPermissionUtils.executeIgnore(() -> messageMapper.selectOne(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                        .eq(MessageDO::getOrderId, orderId).eq(MessageDO::getCommandType, "BET")
                        .orderByAsc(MessageDO::getCreateTime).last("LIMIT 1")));
        int version = value(order.getVersion(), 0);
        int changed = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, orderId)
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getStatus, "未开奖").eq(OrderDO::getVersion, version)
                .set(OrderDO::getStatus, orderStatus).set(OrderDO::getMarketStatus, marketStatus)
                .set(OrderDO::getMarketError, marketStatus.equals("FAILED") ? safe(reason) : "")
                .set(OrderDO::getCancelledAt, LocalDateTime.now()).set(OrderDO::getVersion, version + 1));
        if (changed != 1) return;
        MemberDO member = DataPermissionUtils.executeIgnore(() -> memberMapper.selectById(order.getMemberId()));
        if (member == null) throw exception(BET_STATE_CHANGED);
        int memberVersion = value(member.getVersion(), 0);
        BigDecimal before = money(member.getBalance());
        BigDecimal after = money(before.add(order.getAmount()));
        int refunded = memberMapper.update(null, new LambdaUpdateWrapper<MemberDO>()
                .eq(MemberDO::getId, member.getId()).eq(MemberDO::getUserId, userId)
                .eq(MemberDO::getVersion, memberVersion)
                .set(MemberDO::getBalance, after)
                .set(MemberDO::getTotalBet, money(money(member.getTotalBet()).subtract(order.getAmount()).max(ZERO)))
                .set(MemberDO::getVersion, memberVersion + 1));
        if (refunded != 1) throw exception(BET_STATE_CHANGED);
        member.setBalance(after);
        member.setVersion(memberVersion + 1);
        balanceLedgerService.recordAppliedChange(member, before, after, LotteryBalanceLedgerService.BET_REFUND,
                orderId, actor, reason);
        String playerReply = failedSubmission
                ? robotReplyTemplate.betFailed(member.getName(), order.getAmount())
                : robotReplyTemplate.cancelSucceeded(orderMessage == null ? "" : orderMessage.getReply());
        messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                .eq(MessageDO::getOrderId, orderId).set(MessageDO::getStatus, orderStatus)
                .set(MessageDO::getReply, playerReply)
                .set(MessageDO::getError, marketStatus.equals("FAILED") ? safe(reason) : ""));
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

    private void updateBetReceiptMessage(Long userId, String orderId, boolean detailsConfirmed) {
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

    public record DispatchContext(String orderId, String period, int attempts,
                                  Wa55MarketOrderClient.Credentials credentials,
                                  List<Wa55MarketOrderClient.BetRequest> requests) {}

    public record VerificationContext(String orderId, String period, LocalDateTime submittedAt,
                                      Wa55MarketOrderClient.Credentials credentials,
                                      List<Wa55MarketOrderClient.BetRequest> requests) {}

    public record CancelContext(String orderId, String period, boolean rejectedSubmission,
                                Wa55MarketOrderClient.Credentials credentials,
                                List<Wa55MarketOrderClient.CancelRequest> requests) {}

    public record ManualReviewResult(int routeCount, BigDecimal amount, String marketStatus) {}
}
