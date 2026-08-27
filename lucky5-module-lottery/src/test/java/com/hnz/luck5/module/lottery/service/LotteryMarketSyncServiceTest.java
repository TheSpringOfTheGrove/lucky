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
    void refreshesOwnerConnectionThirtySecondsAfterTheLastSuccessfulRefresh() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 15, 30);

        assertThat(LotteryMarketSyncService.ownerConnectionRefreshDue(null, now)).isTrue();
        assertThat(LotteryMarketSyncService.ownerConnectionRefreshDue(now.minusSeconds(29), now)).isFalse();
        assertThat(LotteryMarketSyncService.ownerConnectionRefreshDue(now.minusSeconds(30), now)).isTrue();
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
        verify(issueMapper, never()).update(any(), any());
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
