package com.hnz.luck5.module.lottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LotteryMarketSyncServiceTest {

    private LotteryMarketSyncService service;

    @BeforeEach
    void setUp() {
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
}
