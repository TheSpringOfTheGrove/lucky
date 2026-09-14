package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueTransitionDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueTransitionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
        service = new LotteryMarketSyncService();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
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
    void refreshesAnUnconfiguredOwnerConnectionFiveMinutesAfterTheLastSuccessfulRefresh() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 15, 30);

        assertThat(LotteryMarketSyncService.ownerConnectionRefreshDue(null, now)).isTrue();
        assertThat(LotteryMarketSyncService.ownerConnectionRefreshDue(now.minusMinutes(4).minusSeconds(59), now)).isFalse();
        assertThat(LotteryMarketSyncService.ownerConnectionRefreshDue(now.minusMinutes(5), now)).isTrue();
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
        stale.setDrawConfirmations(1);
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
    void settlesTheImmediatePreviousDrawnIssueBehindAnUnopenedPreview() {
        IssueMapper issueMapper = mock(IssueMapper.class);
        IssueDO preview = openIssue(209L, "20260906209");
        IssueDO previous = openIssue(208L, "20260906208");
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
                LocalDateTime.of(2026, 8, 18, 20, 0, 16));

        verify(lotteryService).publishVerifiedDrawForUser(229L, "20260818240", "62845");
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
