package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueTransitionDO;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueTransitionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class LotteryMarketSyncServiceTest {

    private LotteryMarketSyncService service;

    @BeforeEach
    void setUp() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "market-sync-issue-test"),
                IssueDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "market-sync-order-test"),
                OrderDO.class);
        service = new LotteryMarketSyncService();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        OrderMapper orderMapper = mock(OrderMapper.class);
        when(orderMapper.selectList(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
    }

    @Test
    void wrapsPlainSettlementErrorsAsValidJson() {
        assertThat(service.normalizeTransitionDetail("订单正在核对，暂不能结算"))
                .isEqualTo("{\"message\":\"订单正在核对，暂不能结算\"}");
    }

    @Test
    void preservesJsonAndNormalizesBlankDetails() {
        assertThat(service.normalizeTransitionDetail("{\"confirmations\":2}"))
                .isEqualTo("{\"confirmations\":2}");
        assertThat(service.normalizeTransitionDetail(" ")).isEqualTo("{}");
    }

    @Test
    void refreshesOwnerBalanceThirtySecondsAfterTheLastSuccessfulRefresh() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 15, 30);

        assertThat(LotteryMarketSyncService.ownerConnectionRefreshDue(null, now)).isTrue();
        assertThat(LotteryMarketSyncService.ownerConnectionRefreshDue(now.minusSeconds(29), now)).isFalse();
        assertThat(LotteryMarketSyncService.ownerConnectionRefreshDue(now.minusSeconds(30), now)).isTrue();
    }

    @Test
    void retriesASettlementAfterTheConfiguredRecoveryTimeoutWithASafeMinimum() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 6, 16, 30);
        ReflectionTestUtils.setField(service, "settlementRecoverySeconds", 120L);

        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "staleSettlementBefore", now))
                .isEqualTo(now.minusSeconds(120));

        ReflectionTestUtils.setField(service, "settlementRecoverySeconds", 5L);
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "staleSettlementBefore", now))
                .isEqualTo(now.minusSeconds(30));
    }

    @Test
    void onlyTreatsTimedOutVerifiedSettlementsAsRecoverable() {
        LocalDateTime staleBefore = LocalDateTime.of(2026, 9, 6, 16, 30);
        IssueDO stale = new IssueDO();
        stale.setStatus("SETTLING");
        stale.setResult("32092");
        stale.setDrawConfirmations(2);
        stale.setSettlementStartedAt(staleBefore.minusSeconds(1));

        assertThat(LotteryMarketSyncService.isRecoverableStaleSettlement(stale, staleBefore)).isTrue();

        stale.setSettlementStartedAt(staleBefore.plusSeconds(1));
        assertThat(LotteryMarketSyncService.isRecoverableStaleSettlement(stale, staleBefore)).isFalse();

        stale.setSettlementStartedAt(staleBefore.minusSeconds(1));
        stale.setDrawConfirmations(0);
        assertThat(LotteryMarketSyncService.isRecoverableStaleSettlement(stale, staleBefore)).isFalse();

        stale.setDrawConfirmations(2);
        stale.setResult(LotteryDrawVerificationService.ZERO_RESULT);
        assertThat(LotteryMarketSyncService.isRecoverableStaleSettlement(stale, staleBefore)).isFalse();
    }

    @Test
    void ignoresHistoricalDrawnRowsWhenTheLatestIssueIsAlreadyOpen() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueDO latest = openIssue(208L, "20260906208");
        latest.setOpenedAt(LocalDateTime.of(2026, 9, 6, 17, 15));
        when(issueMapper.selectOne(any())).thenReturn(latest);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);

        IssueDO candidate = ReflectionTestUtils.invokeMethod(service, "findLiveSettlementCandidate", 229L,
                LocalDateTime.of(2026, 9, 6, 17, 14));

        assertThat(candidate).isNull();
        verify(issueMapper, times(1)).selectOne(any());
    }

    @Test
    void recoversVerifiedHistoricalLocalDrawAfterLiveIssueIsOpen() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueDO latest = openIssue(208L, "20261022208");
        latest.setTenantId(1L);
        latest.setOpenedAt(LocalDateTime.of(2026, 10, 22, 17, 15));
        IssueDO historical = openIssue(160L, "20261013160");
        historical.setStatus("DRAWN");
        historical.setOpenedAt(LocalDateTime.of(2026, 10, 13, 13, 15));
        historical.setResult("12345");
        historical.setDrawConfirmations(2);
        when(issueMapper.selectOne(any())).thenReturn(latest);
        when(issueMapper.selectRecoverableHistoricalDraw(1L, 229L)).thenReturn(historical);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);

        IssueDO candidate = ReflectionTestUtils.invokeMethod(service, "findLiveSettlementCandidate", 229L,
                LocalDateTime.of(2026, 10, 22, 17, 14));

        assertThat(candidate).isSameAs(historical);
        verify(issueMapper).selectRecoverableHistoricalDraw(1L, 229L);
    }

    @Test
    void settlesTheImmediatePreviousDrawnIssueBehindAnUnopenedPreview() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueDO preview = openIssue(209L, "20260906209");
        IssueDO previous = openIssue(208L, "20260906208");
        previous.setOpenedAt(LocalDateTime.of(2026, 9, 6, 17, 10));
        previous.setStatus("DRAWN");
        previous.setResult("50309");
        previous.setDrawConfirmations(2);
        when(issueMapper.selectOne(any())).thenReturn(preview, previous);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);

        IssueDO candidate = ReflectionTestUtils.invokeMethod(service, "findLiveSettlementCandidate", 229L,
                LocalDateTime.of(2026, 9, 6, 17, 14));

        assertThat(candidate).isSameAs(previous);
        assertThat(LotteryMarketSyncService.isReadyDrawnSettlement(candidate)).isTrue();
        verify(issueMapper, times(2)).selectOne(any());
    }

    @Test
    void dispatchesDifferentOwnersIndependentlyWithoutQueuingTheSameOwnerTwice() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueDO first = new IssueDO();
        first.setTenantId(1L);
        first.setUserId(229L);
        IssueDO duplicate = new IssueDO();
        duplicate.setTenantId(1L);
        duplicate.setUserId(229L);
        IssueDO second = new IssueDO();
        second.setTenantId(1L);
        second.setUserId(230L);
        when(issueMapper.selectList(any())).thenReturn(List.of(first, duplicate, second));
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        List<Runnable> queued = new ArrayList<>();
        ReflectionTestUtils.setField(service, "settlementExecutor", (Executor) queued::add);

        service.settleAllPending();
        service.settleAllPending();

        assertThat(queued).hasSize(2);
        @SuppressWarnings("unchecked")
        java.util.Set<String> activeOwners = (java.util.Set<String>) ReflectionTestUtils.getField(service,
                "settlingOwners");
        assertThat(activeOwners).containsExactlyInAnyOrder("1:229", "1:230");
    }

    @Test
    void publishesDifferentOwnersSummariesConcurrently() {
        LotteryPeriodSummaryService summaries = mock(LotteryPeriodSummaryService.class);
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger concurrentPublishes = new AtomicInteger();
        doAnswer(invocation -> {
            bothStarted.countDown();
            if (bothStarted.await(2, TimeUnit.SECONDS)) concurrentPublishes.incrementAndGet();
            return null;
        }).when(summaries).publish(any(), any());
        ReflectionTestUtils.setField(service, "periodSummaryService", summaries);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ReflectionTestUtils.setField(service, "periodSummaryExecutor", workers);
        try {
            service.publishSummaries(List.of(
                    new LotteryMarketSyncService.PeriodSummaryPublication(1L, 229L, "20261022250"),
                    new LotteryMarketSyncService.PeriodSummaryPublication(1L, 244L, "20261022250")), "test");
            assertThat(concurrentPublishes.get()).isEqualTo(2);
            verify(summaries, times(2)).publish(any(), any());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void defersBulkRepairAroundTheDrawButNotOutsideTheWindow() {
        long drawAt = 1_000_000L;
        ReflectionTestUtils.setField(service, "activeDrawTimeMillis", drawAt);
        ReflectionTestUtils.setField(service, "hotWindowBeforeSeconds", 8L);
        ReflectionTestUtils.setField(service, "hotWindowAfterSeconds", 45L);

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldDeferMaintenance", drawAt - 19_000L))
                .isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldDeferMaintenance", drawAt + 119_000L))
                .isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldDeferMaintenance", drawAt - 21_000L))
                .isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldDeferMaintenance", drawAt + 121_000L))
                .isFalse();
    }

    @Test
    void recoversAnOlderUnsettledOpenedIssueWithoutOrdersBehindManyUnopenedPreviews() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueDO current = openIssue(165L, "20261014165");
        IssueDO blocker = openIssue(60L, "20261014060");
        blocker.setOpenedAt(LocalDateTime.of(2026, 10, 14, 4, 55));
        blocker.setStatus("DRAWN");
        blocker.setResult("19231");
        blocker.setDrawConfirmations(2);
        // The order mapper returns no orders. A formally opened zero-order issue still needs settlement.
        when(issueMapper.selectOne(any())).thenReturn(current, blocker);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);

        IssueDO candidate = ReflectionTestUtils.invokeMethod(service, "findLiveSettlementCandidate", 229L,
                LocalDateTime.of(2026, 10, 14, 13, 42));

        assertThat(candidate).isSameAs(blocker);
        ArgumentCaptor<Wrapper<IssueDO>> queries = ArgumentCaptor.forClass(Wrapper.class);
        verify(issueMapper, times(2)).selectOne(queries.capture());
        assertThat(queries.getAllValues().get(1).getSqlSegment()).contains("openedAt IS NOT NULL");
    }

    @Test
    void closesTheOpenPeriodWhenTheMarketAdvancesToAClosedPreview() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        IssueDO open = new IssueDO();
        open.setUserId(229L);
        open.setPeriod("20260827134");
        open.setStatus("OPEN");
        open.setOpenedAt(nowMinusMinutes(5));
        when(issueMapper.selectOne(any())).thenReturn(null);
        when(issueMapper.selectList(any())).thenReturn(java.util.List.of(open));
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 27, 11, 9, 31);

        Object persistence = ReflectionTestUtils.invokeMethod(service, "upsertCurrentIssue", 229L,
                new Wa55MarketClient.Issue("20260827135", "CLOSED", 0, 0, now, now,
                        "20260827136", now.plusSeconds(39), "{}"), now);

        assertThat(open.getStatus()).isEqualTo("CLOSED");
        assertThat(open.getClosedAt()).isEqualTo(now);
        List<?> closedPeriods = ReflectionTestUtils.invokeMethod(persistence, "closedPeriods");
        assertThat(closedPeriods.stream().anyMatch("20260827134"::equals)).isTrue();
        verify(issueMapper).updateById(open);
    }

    @Test
    void doesNotPublishClosingMessagesForAnIssueThatNeverStartedAnswering() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        IssueDO preview = new IssueDO();
        preview.setUserId(229L);
        preview.setPeriod("20260827134");
        preview.setStatus("OPEN");
        when(issueMapper.selectOne(any())).thenReturn(null);
        when(issueMapper.selectList(any())).thenReturn(List.of(preview));
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 27, 11, 9, 31);

        Object persistence = ReflectionTestUtils.invokeMethod(service, "upsertCurrentIssue", 229L,
                new Wa55MarketClient.Issue("20260827135", "CLOSED", 0, 0, now, now,
                        "20260827136", now.plusSeconds(39), "{}"), now);

        List<?> closedPeriods = ReflectionTestUtils.invokeMethod(persistence, "closedPeriods");
        assertThat(preview.getStatus()).isEqualTo("CLOSED");
        assertThat(closedPeriods.stream().map(String::valueOf).toList()).doesNotContain("20260827134");
        verify(transitionMapper, never()).insert(any(IssueTransitionDO.class));
    }

    @Test
    void keepsNextIssueWaitingUntilThePreviousAnsweringIssueIsSettled() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        LotteryIssueFreshnessPolicy freshnessPolicy = mock(LotteryIssueFreshnessPolicy.class);
        IssueDO next = openIssue(136L, "20260827136");
        next.setClosedAt(nowMinusMinutes(1));
        IssueDO previous = openIssue(135L, "20260827135");
        previous.setOpenedAt(nowMinusMinutes(5));
        previous.setStatus("SETTLING");
        when(issueMapper.selectOne(any())).thenReturn(next, previous);
        when(issueMapper.selectList(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        ReflectionTestUtils.setField(service, "issueFreshnessPolicy", freshnessPolicy);
        LocalDateTime now = LocalDateTime.of(2026, 8, 27, 11, 10);

        Object persistence = ReflectionTestUtils.invokeMethod(service, "upsertCurrentIssue", 229L,
                new Wa55MarketClient.Issue(next.getPeriod(), "OPEN", 1, 90, now, now,
                        "20260827137", now.plusMinutes(2), "{}"), now);

        Boolean newlyOpened = ReflectionTestUtils.invokeMethod(persistence, "newlyOpened");
        assertThat(newlyOpened).isFalse();
        assertThat(next.getOpenedAt()).isNull();
        // The first update only clears the stale CLOSED-preview timestamp; openedAt must remain untouched.
        verify(issueMapper, times(1)).update(any(), any());
    }

    @Test
    void skippedUnopenedPreviewCannotHideAnUnpaidFormallyOpenedIssue() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        LotteryIssueFreshnessPolicy freshnessPolicy = mock(LotteryIssueFreshnessPolicy.class);
        IssueDO next = openIssue(244L, "20261013244");
        IssueDO unpaid = openIssue(242L, "20261013242");
        unpaid.setOpenedAt(nowMinusMinutes(10));
        unpaid.setStatus("DRAWN");
        // Period 243 exists only as an unopened preview. The query must skip it and find period 242.
        when(issueMapper.selectOne(any())).thenReturn(next, unpaid);
        when(issueMapper.selectList(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        ReflectionTestUtils.setField(service, "issueFreshnessPolicy", freshnessPolicy);
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 20, 16);

        Object persistence = ReflectionTestUtils.invokeMethod(service, "upsertCurrentIssue", 228L,
                new Wa55MarketClient.Issue(next.getPeriod(), "OPEN", 1, 90, now, now,
                        "20261013245", now.plusMinutes(2), "{}"), now);

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(persistence, "newlyOpened")).isFalse();
        assertThat(next.getOpenedAt()).isNull();
        ArgumentCaptor<Wrapper> queries = ArgumentCaptor.forClass(Wrapper.class);
        verify(issueMapper, times(2)).selectOne(queries.capture());
        assertThat(queries.getAllValues().get(1).getSqlSegment()).contains("openedAt IS NOT NULL");
    }

    @Test
    void opensNextIssueAfterThePreviousAnsweringIssueSettles() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        LotteryIssueFreshnessPolicy freshnessPolicy = mock(LotteryIssueFreshnessPolicy.class);
        IssueDO next = openIssue(136L, "20260827136");
        IssueDO previous = openIssue(135L, "20260827135");
        previous.setOpenedAt(nowMinusMinutes(5));
        previous.setStatus("SETTLED");
        when(issueMapper.selectOne(any())).thenReturn(next, previous);
        when(issueMapper.selectList(any())).thenReturn(List.of());
        when(issueMapper.update(any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        ReflectionTestUtils.setField(service, "issueFreshnessPolicy", freshnessPolicy);
        LocalDateTime now = LocalDateTime.of(2026, 8, 27, 11, 10);

        Object persistence = ReflectionTestUtils.invokeMethod(service, "upsertCurrentIssue", 229L,
                new Wa55MarketClient.Issue(next.getPeriod(), "OPEN", 1, 90, now, now,
                        "20260827137", now.plusMinutes(2), "{}"), now);

        Boolean newlyOpened = ReflectionTestUtils.invokeMethod(persistence, "newlyOpened");
        assertThat(newlyOpened).isTrue();
        assertThat(next.getOpenedAt()).isEqualTo(now);
        assertThat(next.getClosedAt()).isNull();
        verify(transitionMapper).insert(any(IssueTransitionDO.class));
    }

    @Test
    void opensCurrentIssueWhenTheImmediatePreviousIssueSettledAfterAnOlderAbandonedPeriod() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        LotteryIssueFreshnessPolicy freshnessPolicy = mock(LotteryIssueFreshnessPolicy.class);
        IssueDO current = openIssue(196L, "20260906196");
        current.setClosedAt(nowMinusMinutes(1));
        IssueDO immediatelyPrevious = openIssue(195L, "20260906195");
        immediatelyPrevious.setStatus("SETTLED");
        // A much older opened-but-abandoned row exists in production. It is deliberately not returned by the
        // latest-period query and therefore cannot permanently block newer settled periods.
        IssueDO abandoned = openIssue(29L, "20260829029");
        abandoned.setOpenedAt(nowMinusMinutes(10));
        abandoned.setStatus("CLOSED");
        when(issueMapper.selectOne(any())).thenReturn(current, immediatelyPrevious);
        when(issueMapper.selectList(any())).thenReturn(List.of());
        when(issueMapper.update(any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        ReflectionTestUtils.setField(service, "issueFreshnessPolicy", freshnessPolicy);
        LocalDateTime now = LocalDateTime.of(2026, 9, 6, 16, 18);

        Object persistence = ReflectionTestUtils.invokeMethod(service, "upsertCurrentIssue", 1L,
                new Wa55MarketClient.Issue(current.getPeriod(), "OPEN", 0, 74, now, now,
                        "20260906197", now.plusSeconds(112), "{}"), now);

        Boolean newlyOpened = ReflectionTestUtils.invokeMethod(persistence, "newlyOpened");
        assertThat(newlyOpened).isTrue();
        assertThat(current.getOpenedAt()).isEqualTo(now);
        assertThat(current.getClosedAt()).isNull();
        assertThat(abandoned.getStatus()).isEqualTo("CLOSED");
        verify(issueMapper, times(2)).update(any(), any());
        verify(transitionMapper).insert(any(IssueTransitionDO.class));
    }

    @Test
    void comparesNumericPeriodsWithoutAcceptingOlderOrInvalidValues() {
        assertThat(LotteryMarketSyncService.isLaterPeriod("20260827135", "20260827134")).isTrue();
        assertThat(LotteryMarketSyncService.isLaterPeriod("20260827134", "20260827135")).isFalse();
        assertThat(LotteryMarketSyncService.isLaterPeriod("invalid", "20260827135")).isFalse();
    }

    @Test
    void republishesVerifiedDrawEvenWhenSettlementIsBlocked() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        LotteryDrawVerificationService verificationService = mock(LotteryDrawVerificationService.class);
        LotteryService lotteryService = mock(LotteryService.class);
        IssueDO issue = new IssueDO();
        issue.setUserId(229L);
        issue.setPeriod("20260818240");
        issue.setStatus("DRAWN");
        issue.setResult("62845");
        issue.setDrawConfirmations(2);
        issue.setDrawTime(LocalDateTime.of(2026, 8, 18, 20, 0, 10));
        when(issueMapper.selectOne(any())).thenReturn(issue);
        when(verificationService.isTrusted("62845")).thenReturn(true);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "drawVerificationService", verificationService);
        ReflectionTestUtils.setField(service, "lotteryService", lotteryService);

        ReflectionTestUtils.invokeMethod(service, "upsertDrawIssue", 229L,
                new Wa55MarketClient.Draw("20260818240", "62845", issue.getDrawTime(),
                        LocalDateTime.of(2026, 8, 18, 20, 0, 15), "{}"),
                "系统开奖",
                LocalDateTime.of(2026, 8, 18, 20, 0, 16));

        verify(lotteryService).publishVerifiedDrawForUser(229L, "20260818240", "62845");
    }

    @Test
    void legacyPendingResultRequestsSettlementOnlyOnce() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        LotteryService lotteryService = mock(LotteryService.class);
        LocalDateTime firstSeen = LocalDateTime.of(2026, 10, 15, 10, 0);
        IssueDO issue = new IssueDO();
        issue.setUserId(229L);
        issue.setPeriod("20261015120");
        issue.setStatus("DRAW_PENDING");
        issue.setSource("系统开奖");
        issue.setResult("62845");
        issue.setDrawConfirmations(1);
        issue.setDrawFirstSeenAt(firstSeen);
        when(issueMapper.selectOne(any())).thenReturn(issue);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        ReflectionTestUtils.setField(service, "drawVerificationService", new LotteryDrawVerificationService());
        ReflectionTestUtils.setField(service, "lotteryService", lotteryService);
        Wa55MarketClient.Draw draw = new Wa55MarketClient.Draw(issue.getPeriod(), "62845",
                firstSeen, firstSeen.plusSeconds(6), "{}");
        Set<String> newlyDrawn = new LinkedHashSet<>();

        ReflectionTestUtils.invokeMethod(service, "upsertDrawIssue", 229L, draw, "系统开奖",
                firstSeen.plusSeconds(6), newlyDrawn);
        assertThat(newlyDrawn).containsExactly("20261015120");

        newlyDrawn.clear();
        ReflectionTestUtils.invokeMethod(service, "upsertDrawIssue", 229L, draw, "系统开奖",
                firstSeen.plusSeconds(7), newlyDrawn);
        assertThat(newlyDrawn).isEmpty();
        verify(lotteryService, times(2)).publishVerifiedDrawForUser(229L, "20261015120", "62845");
    }

    @Test
    void pinsTheFirstValidResultSourceForAPeriod() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> operations = mock(ValueOperations.class);
        LotteryDrawVerificationService verification = new LotteryDrawVerificationService();
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.setIfAbsent(any(), any(), any(java.time.Duration.class))).thenReturn(true);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "drawVerificationService", verification);
        ReflectionTestUtils.setField(service, "issueMapper", mock(IssueMapper.class));
        Wa55MarketClient.Draw draw = new Wa55MarketClient.Draw("20261014172", "82143",
                LocalDateTime.of(2026, 10, 14, 14, 20, 12), LocalDateTime.now(), "{}");

        assertThat((List<?>) ReflectionTestUtils.invokeMethod(service, "acceptedDraws", "by123开奖", List.of(draw)))
                .hasSize(1);
        assertThat((List<?>) ReflectionTestUtils.invokeMethod(service, "acceptedDraws", "系统开奖", List.of(draw)))
                .isEmpty();
        Wa55MarketClient.Draw correctedByWinner = new Wa55MarketClient.Draw(draw.period(), "82144",
                draw.drawTime(), draw.updatedAt(), "{}");
        assertThat((List<?>) ReflectionTestUtils.invokeMethod(service, "acceptedDraws", "by123开奖",
                List.of(correctedByWinner))).isEmpty();
    }

    @Test
    void firstValidResultImmediatelyQueuesSettlementForTheOwner() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        LotteryService lotteryService = mock(LotteryService.class);
        IssueDO issue = new IssueDO();
        issue.setUserId(229L);
        issue.setPeriod("20261023250");
        issue.setStatus("CLOSED");
        when(issueMapper.selectOne(any())).thenReturn(issue);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        ReflectionTestUtils.setField(service, "drawVerificationService", new LotteryDrawVerificationService());
        ReflectionTestUtils.setField(service, "lotteryService", lotteryService);
        LocalDateTime seenAt = LocalDateTime.of(2026, 10, 23, 20, 0, 10);
        Wa55MarketClient.Draw draw = new Wa55MarketClient.Draw(issue.getPeriod(), "62845",
                seenAt, seenAt, "{}");
        Set<String> newlyDrawn = new LinkedHashSet<>();

        ReflectionTestUtils.invokeMethod(service, "upsertDrawIssue", 229L, draw, "系统开奖", seenAt, newlyDrawn);

        assertThat(issue.getStatus()).isEqualTo("DRAWN");
        assertThat(issue.getDrawConfirmations()).isEqualTo(1);
        assertThat(issue.getDrawFirstSeenAt()).isEqualTo(seenAt);
        assertThat(newlyDrawn).containsExactly(issue.getPeriod());
        verify(lotteryService).publishVerifiedDrawForUser(229L, issue.getPeriod(), "62845");
    }

    @Test
    void drawArrivingBeforeClockClosureStillRecordsStopBeforeSettlement() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        LotteryService lotteryService = mock(LotteryService.class);
        IssueDO issue = openIssue(250L, "20261023250");
        issue.setOpenedAt(LocalDateTime.of(2026, 10, 23, 19, 55));
        when(issueMapper.selectOne(any())).thenReturn(issue);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        ReflectionTestUtils.setField(service, "drawVerificationService", new LotteryDrawVerificationService());
        ReflectionTestUtils.setField(service, "lotteryService", lotteryService);
        LocalDateTime seenAt = LocalDateTime.of(2026, 10, 23, 20, 0, 10);
        Wa55MarketClient.Draw draw = new Wa55MarketClient.Draw(issue.getPeriod(), "62845",
                seenAt, seenAt, "{}");

        ReflectionTestUtils.invokeMethod(service, "upsertDrawIssue", 229L, draw, "系统开奖",
                seenAt, new LinkedHashSet<String>());

        assertThat(issue.getClosedAt()).isEqualTo(seenAt);
        ArgumentCaptor<IssueTransitionDO> transitions = ArgumentCaptor.forClass(IssueTransitionDO.class);
        verify(transitionMapper, times(2)).insert(transitions.capture());
        assertThat(transitions.getAllValues()).extracting(IssueTransitionDO::getToStatus)
                .containsExactly("CLOSED", "DRAWN");
    }

    @Test
    void laterDifferentResultDoesNotOverwriteTheFirstAcceptedIssue() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        IssueDO issue = new IssueDO();
        issue.setUserId(229L);
        issue.setPeriod("20261023250");
        issue.setStatus("DRAWN");
        issue.setSource("系统开奖");
        issue.setResult("62845");
        issue.setDrawConfirmations(1);
        issue.setDrawFirstSeenAt(LocalDateTime.of(2026, 10, 23, 20, 0, 10));
        when(issueMapper.selectOne(any())).thenReturn(issue);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        ReflectionTestUtils.setField(service, "drawVerificationService", new LotteryDrawVerificationService());

        ReflectionTestUtils.invokeMethod(service, "upsertDrawIssue", 229L,
                new Wa55MarketClient.Draw(issue.getPeriod(), "62846", issue.getDrawFirstSeenAt(),
                        issue.getDrawFirstSeenAt().plusSeconds(1), "{}"),
                "系统开奖", issue.getDrawFirstSeenAt().plusSeconds(1));

        assertThat(issue.getStatus()).isEqualTo("DRAWN");
        assertThat(issue.getResult()).isEqualTo("62845");
        verify(issueMapper, never()).updateById(any(IssueDO.class));
        verify(transitionMapper).insert(any(IssueTransitionDO.class));
    }

    @Test
    void doesNotReplaceAnExistingCandidateFromAnotherSource() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        ReflectionTestUtils.setField(service, "drawVerificationService", new LotteryDrawVerificationService());
        IssueDO issue = new IssueDO();
        issue.setPeriod("20261014172");
        issue.setStatus("DRAW_PENDING");
        issue.setResult("82143");
        issue.setSource("by123开奖");
        issue.setDrawFirstSeenAt(LocalDateTime.of(2026, 10, 14, 14, 20, 12));
        when(issueMapper.selectOne(any())).thenReturn(issue);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        Wa55MarketClient.Draw draw = new Wa55MarketClient.Draw("20261014172", "82143",
                issue.getDrawFirstSeenAt(), issue.getDrawFirstSeenAt(), "{}");

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "upsertDrawIssue", 229L, draw,
                "系统开奖", LocalDateTime.of(2026, 10, 14, 14, 20, 18))).isFalse();
        verify(issueMapper, never()).updateById(any(IssueDO.class));
    }

    @Test
    void replacesAnEmptyHistoricalCandidateWithATrustedSharedResult() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueTransitionMapper transitionMapper = mock(IssueTransitionMapper.class);
        LotteryService lotteryService = mock(LotteryService.class);
        IssueDO issue = new IssueDO();
        issue.setUserId(234L);
        issue.setPeriod("20261015167");
        issue.setStatus("CLOSED");
        issue.setResult("");
        issue.setSource("系统开奖");
        issue.setDrawConfirmations(0);
        issue.setDrawFirstSeenAt(LocalDateTime.of(2026, 10, 15, 13, 56, 23));
        when(issueMapper.selectOne(any())).thenReturn(issue);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", transitionMapper);
        ReflectionTestUtils.setField(service, "drawVerificationService", new LotteryDrawVerificationService());
        ReflectionTestUtils.setField(service, "lotteryService", lotteryService);

        ReflectionTestUtils.invokeMethod(service, "upsertDrawIssue", 234L,
                new Wa55MarketClient.Draw(issue.getPeriod(), "71018", issue.getDrawFirstSeenAt(),
                        issue.getDrawFirstSeenAt(), "{}"), "by123开奖", LocalDateTime.now());

        assertThat(issue.getStatus()).isEqualTo("DRAWN");
        assertThat(issue.getResult()).isEqualTo("71018");
        assertThat(issue.getSource()).isEqualTo("by123开奖");
        verify(issueMapper).updateById(issue);
    }

    @Test
    void limitsClockLoginsAndRotatesFallbackOwners() {
        ReflectionTestUtils.setField(service, "drawSourceTenantId", 1L);
        ReflectionTestUtils.setField(service, "drawSourceUserId", 1L);
        List<LotteryConfigDO> configs = List.of(clockConfig(1L), clockConfig(2L),
                clockConfig(3L), clockConfig(4L), clockConfig(5L));

        List<LotteryConfigDO> first = ReflectionTestUtils.invokeMethod(service, "clockCandidates", configs);
        List<LotteryConfigDO> second = ReflectionTestUtils.invokeMethod(service, "clockCandidates", configs);

        assertThat(first).hasSize(2).extracting(LotteryConfigDO::getUserId).contains(1L, 2L);
        assertThat(second).hasSize(2).extracting(LotteryConfigDO::getUserId).contains(1L, 3L);
    }

    @Test
    void onlyRepairsAnOldDrawThatStillBlocksTheNextPeriod() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 19, 12, 40);
        IssueDO blocker = new IssueDO();
        blocker.setStatus("CLOSED");
        blocker.setOpenedAt(now.minusMinutes(10));
        blocker.setDrawTime(now.minusSeconds(31));

        assertThat(LotteryMarketSyncService.isMissingDrawBlocker(blocker, now)).isTrue();
        blocker.setDrawTime(now.minusSeconds(29));
        assertThat(LotteryMarketSyncService.isMissingDrawBlocker(blocker, now)).isFalse();
        blocker.setDrawTime(now.minusSeconds(31));
        blocker.setStatus("DRAW_PENDING");
        assertThat(LotteryMarketSyncService.isMissingDrawBlocker(blocker, now)).isTrue();
        blocker.setStatus("SETTLED");
        assertThat(LotteryMarketSyncService.isMissingDrawBlocker(blocker, now)).isFalse();
    }

    @Test
    void backfillRequiresAConsistentVerifiedResultAndPreservesTheCandidateSource() {
        ReflectionTestUtils.setField(service, "drawVerificationService", new LotteryDrawVerificationService());
        IssueDO by123 = verifiedIssue("by123开奖", "31146");
        IssueDO market = verifiedIssue("系统开奖", "31146");

        assertThat((IssueDO) ReflectionTestUtils.invokeMethod(service, "trustedSharedReference",
                List.of(market, by123), "by123开奖")).isSameAs(by123);
        assertThat((IssueDO) ReflectionTestUtils.invokeMethod(service, "trustedSharedReference",
                List.of(market, by123), "unknown")).isNull();
        market.setResult("31147");
        assertThat((IssueDO) ReflectionTestUtils.invokeMethod(service, "trustedSharedReference",
                List.of(market, by123), null)).isNull();
        market.setResult("31146");
        market.setDrawConfirmations(0);
        assertThat((IssueDO) ReflectionTestUtils.invokeMethod(service, "trustedSharedReference",
                List.of(market, by123), null)).isSameAs(by123);
    }

    @Test
    void recoveryHistoryOnlyUsesTheExactPeriodAndRejectsConflictingResults() {
        ReflectionTestUtils.setField(service, "drawVerificationService", new LotteryDrawVerificationService());
        Wa55MarketClient.Draw target = new Wa55MarketClient.Draw("20261024017", "58771", null, null, "{}");
        Wa55MarketClient.Draw another = new Wa55MarketClient.Draw("20261024117", "12345", null, null, "{}");

        assertThat((Wa55MarketClient.Draw) ReflectionTestUtils.invokeMethod(service,
                "matchingRecoveryDraw", List.of(another, target), "20261024017")).isSameAs(target);
        assertThat((Wa55MarketClient.Draw) ReflectionTestUtils.invokeMethod(service,
                "matchingRecoveryDraw", List.of(another), "20261024017")).isNull();
        assertThat((Wa55MarketClient.Draw) ReflectionTestUtils.invokeMethod(service,
                "matchingRecoveryDraw", List.of(target,
                        new Wa55MarketClient.Draw("20261024017", "58772", null, null, "{}")),
                "20261024017")).isNull();
    }

    private static IssueDO verifiedIssue(String source, String result) {
        IssueDO issue = new IssueDO();
        issue.setPeriod("20261015267");
        issue.setStatus("SETTLED");
        issue.setResult(result);
        issue.setSource(source);
        issue.setDrawConfirmations(2);
        return issue;
    }

    private static LotteryConfigDO clockConfig(Long userId) {
        LotteryConfigDO config = new LotteryConfigDO();
        config.setTenantId(1L);
        config.setUserId(userId);
        config.setUpstreamUrl("https://example.invalid/");
        config.setUpstreamAccount("account");
        config.setMarketPasswordEncrypted("encrypted");
        return config;
    }

    private static IssueDO openIssue(Long id, String period) {
        IssueDO issue = new IssueDO();
        issue.setId(id);
        issue.setUserId(229L);
        issue.setPeriod(period);
        issue.setStatus("OPEN");
        return issue;
    }

    private static LocalDateTime nowMinusMinutes(long minutes) {
        return LocalDateTime.of(2026, 8, 27, 11, 10).minusMinutes(minutes);
    }
}
