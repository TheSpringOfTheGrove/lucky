package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryMarketOrderDispatchServiceTest {

    @Mock private Wa55MarketOrderClient marketClient;
    @Mock private LotteryMarketOrderStateService stateService;
    @Mock private LotteryMarketSyncService marketSyncService;
    @Mock private LotteryMarketBalanceRefreshService balanceRefreshService;
    @Mock private LotteryMarketAccountLockService accountLockService;

    private LotteryMarketOrderDispatchService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        service = new LotteryMarketOrderDispatchService(marketClient, stateService, marketSyncService,
                balanceRefreshService, accountLockService);
        doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return null;
        }).when(accountLockService).execute(anyLong(), anyLong(), any(Runnable.class));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void refreshesBalanceOnlyAfterLocalConfirmationHasSucceeded() {
        Wa55MarketOrderClient.Credentials credentials = new Wa55MarketOrderClient.Credentials(
                "https://market.example", "owner", "secret");
        Wa55MarketOrderClient.BetRequest request = new Wa55MarketOrderClient.BetRequest(
                "route-1", "20260811001", "二定位", "X12X", new BigDecimal("12.50"), "guid-1", false);
        LotteryMarketOrderStateService.DispatchContext context = new LotteryMarketOrderStateService.DispatchContext(
                "order-1", "20260811001", 1, credentials, List.of(request));
        Wa55MarketOrderClient.BetConfirmation confirmation = new Wa55MarketOrderClient.BetConfirmation(
                "route-1", "guid-1", "bet-1", "serial-1", 1,
                new BigDecimal("12.50"), new BigDecimal("96"));
        when(marketClient.isRealWritesEnabled()).thenReturn(true);
        when(stateService.claimSubmit(9L, "order-1")).thenReturn(context);
        when(marketClient.submit(credentials, "20260811001", List.of(request))).thenReturn(
                new Wa55MarketOrderClient.SubmissionBatch(List.of(confirmation), new BigDecimal("100.00")));
        when(stateService.applyConfirmations(9L, "order-1", List.of(confirmation))).thenReturn(true);

        service.submit(9L, "order-1");

        verify(marketSyncService).recordSuccessfulSubmission(9L, new BigDecimal("100.00"),
                new BigDecimal("12.50"));
        verify(balanceRefreshService).refresh(1L, 9L);
    }

    @Test
    void doesNotRefreshBalanceWhenLocalConfirmationCannotBeCompleted() {
        Wa55MarketOrderClient.Credentials credentials = new Wa55MarketOrderClient.Credentials(
                "https://market.example", "owner", "secret");
        Wa55MarketOrderClient.BetRequest request = new Wa55MarketOrderClient.BetRequest(
                "route-1", "20260811001", "二定位", "X12X", BigDecimal.ONE, "guid-1", false);
        LotteryMarketOrderStateService.DispatchContext context = new LotteryMarketOrderStateService.DispatchContext(
                "order-1", "20260811001", 1, credentials, List.of(request));
        Wa55MarketOrderClient.BetConfirmation confirmation = new Wa55MarketOrderClient.BetConfirmation(
                "route-1", "guid-1", "bet-1", "serial-1", 1, BigDecimal.ONE, new BigDecimal("96"));
        when(marketClient.isRealWritesEnabled()).thenReturn(true);
        when(stateService.claimSubmit(9L, "order-1")).thenReturn(context);
        when(marketClient.submit(credentials, "20260811001", List.of(request))).thenReturn(
                new Wa55MarketOrderClient.SubmissionBatch(List.of(confirmation), new BigDecimal("100.00")));
        when(stateService.applyConfirmations(9L, "order-1", List.of(confirmation))).thenReturn(false);

        service.submit(9L, "order-1");

        verify(stateService).markManualReview(9L, "order-1",
                "外部订单已成功，但本地确认连续三次写入失败，请仅修复本地状态，禁止重新提交：本地确认状态未完整更新");
        verify(marketSyncService, never()).recordSuccessfulSubmission(9L, new BigDecimal("100.00"), BigDecimal.ONE);
        verify(balanceRefreshService, never()).refresh(1L, 9L);
    }

    @Test
    void keepsExplicitlyAcceptedBatchForReadOnlyVerificationInsteadOfManualReview() {
        Wa55MarketOrderClient.Credentials credentials = new Wa55MarketOrderClient.Credentials(
                "https://market.example", "owner", "secret");
        Wa55MarketOrderClient.BetRequest request = new Wa55MarketOrderClient.BetRequest(
                "route-1", "20260811001", "四定位", "5874", BigDecimal.ONE, "guid-1", false);
        LotteryMarketOrderStateService.DispatchContext context = new LotteryMarketOrderStateService.DispatchContext(
                "order-1", "20260811001", 1, credentials, List.of(request));
        Wa55MarketOrderClient.AcceptedBatch accepted = new Wa55MarketOrderClient.AcceptedBatch(
                "batch-guid", List.of("route-1"), 1, BigDecimal.ONE);
        when(marketClient.isRealWritesEnabled()).thenReturn(true);
        when(stateService.claimSubmit(9L, "order-1")).thenReturn(context);
        when(marketClient.submit(credentials, "20260811001", List.of(request))).thenReturn(
                new Wa55MarketOrderClient.SubmissionBatch(List.of(), List.of(accepted),
                        new BigDecimal("100.00")));

        service.submit(9L, "order-1");

        verify(stateService).markAcceptedBatchesVerifying(anyLong(), any(), any(), any(LocalDateTime.class));
        verify(stateService, never()).markManualReview(anyLong(), any(), any());
        verify(marketSyncService).recordSuccessfulSubmission(9L, new BigDecimal("100.00"), BigDecimal.ONE);
        verify(balanceRefreshService).refresh(1L, 9L);
    }

    @Test
    void confirmsVerifyingBatchUsingReadOnlyDetailLookup() {
        Wa55MarketOrderClient.Credentials credentials = new Wa55MarketOrderClient.Credentials(
                "https://market.example", "owner", "secret");
        Wa55MarketOrderClient.BetRequest request = new Wa55MarketOrderClient.BetRequest(
                "route-1", "20260811001", "四定位", "5874", BigDecimal.ONE, "batch-guid", false);
        Wa55MarketOrderClient.BetConfirmation confirmation = new Wa55MarketOrderClient.BetConfirmation(
                "route-1", "batch-guid", "bet-1", "serial-1", 1, BigDecimal.ONE,
                new BigDecimal("9600"));
        LotteryMarketOrderStateService.VerificationContext context =
                new LotteryMarketOrderStateService.VerificationContext("order-1", "20260811001",
                        LocalDateTime.now(), credentials, List.of(request));
        when(stateService.verificationContext(9L, "order-1")).thenReturn(context);
        when(marketClient.verifyAccepted(credentials, "20260811001", List.of(request))).thenReturn(
                new Wa55MarketOrderClient.VerificationBatch(List.of(confirmation), List.of()));
        when(stateService.applyConfirmations(9L, "order-1", List.of(confirmation))).thenReturn(true);

        service.verify(9L, "order-1");

        verify(stateService).applyConfirmations(9L, "order-1", List.of(confirmation));
        verify(stateService, never()).markManualReview(anyLong(), any(), any());
        verify(marketClient, never()).submit(any(), any(), any());
    }

    @Test
    void keepsAcceptedReceiptWhenDetailVerificationEventuallyNeedsManualReview() {
        Wa55MarketOrderClient.Credentials credentials = new Wa55MarketOrderClient.Credentials(
                "https://market.example", "owner", "secret");
        Wa55MarketOrderClient.BetRequest request = new Wa55MarketOrderClient.BetRequest(
                "route-1", "20260811001", "四定位", "5874", BigDecimal.ONE, "batch-guid", false);
        LotteryMarketOrderStateService.VerificationContext context =
                new LotteryMarketOrderStateService.VerificationContext("order-1", "20260811001",
                        LocalDateTime.now().minusMinutes(6), credentials, List.of(request));
        when(stateService.verificationContext(9L, "order-1")).thenReturn(context);

        service.verify(9L, "order-1");

        verify(stateService).markAcceptedDetailsManualReview(9L, "order-1",
                "盘口已明确受理，但等待下注明细标识超过五分钟，请人工核对；禁止重投或退款");
        verify(marketClient, never()).verifyAccepted(any(), any(), any());
        verify(stateService, never()).markManualReview(anyLong(), any(), any());
    }

    @Test
    void restoresConfirmedOrderWhenCancellationIsExplicitlyRejected() {
        Wa55MarketOrderClient.Credentials credentials = new Wa55MarketOrderClient.Credentials(
                "https://market.example", "owner", "secret");
        List<Wa55MarketOrderClient.CancelRequest> requests = List.of(
                new Wa55MarketOrderClient.CancelRequest("bet-1", 1));
        LotteryMarketOrderStateService.CancelContext context = new LotteryMarketOrderStateService.CancelContext(
                "order-1", "20260811001", false, credentials, requests);
        when(marketClient.isRealWritesEnabled()).thenReturn(true);
        when(stateService.claimCancel(9L, "order-1")).thenReturn(context);
        when(stateService.isCancellationWindowOpen(9L, "20260811001")).thenReturn(true);
        doThrow(new Wa55MarketOrderClient.MarketProtocolException(
                "退码已截止", false, List.of())).when(marketClient)
                .cancel(credentials, "20260811001", requests);

        service.cancel(9L, "order-1");

        verify(stateService).markCancelRejected(9L, "order-1", "退码已截止");
        verify(stateService, never()).markCancelFailed(9L, "order-1", "退码已截止");
    }

    @Test
    void keepsCancellationForReviewWhenExternalResultIsUncertain() {
        Wa55MarketOrderClient.Credentials credentials = new Wa55MarketOrderClient.Credentials(
                "https://market.example", "owner", "secret");
        List<Wa55MarketOrderClient.CancelRequest> requests = List.of(
                new Wa55MarketOrderClient.CancelRequest("bet-1", 1));
        LotteryMarketOrderStateService.CancelContext context = new LotteryMarketOrderStateService.CancelContext(
                "order-1", "20260811001", false, credentials, requests);
        when(marketClient.isRealWritesEnabled()).thenReturn(true);
        when(stateService.claimCancel(9L, "order-1")).thenReturn(context);
        when(stateService.isCancellationWindowOpen(9L, "20260811001")).thenReturn(true);
        doThrow(new Wa55MarketOrderClient.MarketProtocolException(
                "请求超时", false, List.of(), true, null)).when(marketClient)
                .cancel(credentials, "20260811001", requests);

        service.cancel(9L, "order-1");

        verify(stateService).markCancelFailed(9L, "order-1", "请求超时");
        verify(stateService, never()).markCancelRejected(9L, "order-1", "请求超时");
    }

    @Test
    void doesNotCallMarketCancelAfterCutoff() {
        Wa55MarketOrderClient.Credentials credentials = new Wa55MarketOrderClient.Credentials(
                "https://market.example", "owner", "secret");
        List<Wa55MarketOrderClient.CancelRequest> requests = List.of(
                new Wa55MarketOrderClient.CancelRequest("bet-1", 1));
        LotteryMarketOrderStateService.CancelContext context = new LotteryMarketOrderStateService.CancelContext(
                "order-1", "20260811001", false, credentials, requests);
        when(marketClient.isRealWritesEnabled()).thenReturn(true);
        when(stateService.claimCancel(9L, "order-1")).thenReturn(context);
        when(stateService.isCancellationWindowOpen(9L, "20260811001")).thenReturn(false);

        service.cancel(9L, "order-1");

        verify(stateService).markCancelRejected(9L, "order-1", "该期已封盘，不能退码");
        verify(marketClient, never()).cancel(any(), any(), any());
    }

    @Test
    void keepsReturnedMarketIdentifiersForReviewWhenSubmissionAmountIsUncertain() {
        Wa55MarketOrderClient.Credentials credentials = new Wa55MarketOrderClient.Credentials(
                "https://market.example", "owner", "secret");
        Wa55MarketOrderClient.BetRequest request = new Wa55MarketOrderClient.BetRequest(
                "route-1", "20260811001", "四定位", "5874", BigDecimal.ONE, "guid-1", false);
        LotteryMarketOrderStateService.DispatchContext context = new LotteryMarketOrderStateService.DispatchContext(
                "order-1", "20260811001", 1, credentials, List.of(request));
        Wa55MarketOrderClient.BetConfirmation confirmation = new Wa55MarketOrderClient.BetConfirmation(
                "route-1", "batch-guid", "bet-1", "serial-1", 1, BigDecimal.ONE, new BigDecimal("9600"));
        when(marketClient.isRealWritesEnabled()).thenReturn(true);
        when(stateService.claimSubmit(9L, "order-1")).thenReturn(context);
        when(marketClient.submit(credentials, "20260811001", List.of(request))).thenThrow(
                new Wa55MarketOrderClient.MarketProtocolException(
                        "盘口实际受理注数或金额与提交批次不一致", false, List.of(confirmation), true, null));

        service.submit(9L, "order-1");

        verify(stateService).applyUncertainConfirmations(9L, "order-1", List.of(confirmation),
                "盘口实际受理注数或金额与提交批次不一致");
        verify(stateService, never()).applyConfirmations(9L, "order-1", List.of(confirmation));
        verify(stateService).markManualReview(9L, "order-1",
                "盘口受理结果不确定，禁止自动重投或退款，请人工核对：盘口实际受理注数或金额与提交批次不一致");
        verify(balanceRefreshService, never()).refresh(1L, 9L);
    }
}
