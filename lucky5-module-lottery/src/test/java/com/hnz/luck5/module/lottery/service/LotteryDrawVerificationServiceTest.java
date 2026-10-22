package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryDrawVerificationServiceTest {

    private final LotteryDrawVerificationService service = new LotteryDrawVerificationService();
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 9, 18, 0);

    @Test
    void blocksAllZeroResultWithoutMakingItSettleable() {
        LotteryDrawVerificationService.Decision decision = service.evaluate(
                "CLOSED", "", 0, null, "00000", now);

        assertThat(decision.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.ABNORMAL);
        assertThat(decision.status()).isEqualTo(LotteryDrawVerificationService.STATUS_ABNORMAL);
        assertThat(decision.confirmations()).isZero();
        assertThat(decision.error()).contains("暂停自动结算");

        LotteryDrawVerificationService.Decision malformed = service.evaluate(
                "CLOSED", "", 0, null, "", now);
        assertThat(malformed.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.ABNORMAL);
        assertThat(malformed.status()).isEqualTo(LotteryDrawVerificationService.STATUS_ABNORMAL);
    }

    @Test
    void firstValidObservationImmediatelyBecomesSettleable() {
        LotteryDrawVerificationService.Decision first = service.evaluate(
                "CLOSED", "", 0, null, "17254", now);
        LotteryDrawVerificationService.Decision repeated = service.evaluate(
                first.status(), first.result(), first.confirmations(), first.firstSeenAt(),
                "17254", now.plusSeconds(2));

        assertThat(first.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.VERIFIED);
        assertThat(first.status()).isEqualTo(LotteryDrawVerificationService.STATUS_VERIFIED);
        assertThat(first.confirmations()).isEqualTo(1);
        assertThat(first.firstSeenAt()).isEqualTo(now);
        assertThat(repeated.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.UNCHANGED);
        assertThat(repeated.confirmations()).isEqualTo(1);
    }

    @Test
    void preUpgradeCandidateKeepsItsFirstValidResult() {
        LotteryDrawVerificationService.Decision decision = service.evaluate(
                LotteryDrawVerificationService.STATUS_PENDING, "17254", 1, now,
                "60929", now.plusSeconds(30));

        assertThat(decision.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.CONFLICT);
        assertThat(decision.status()).isEqualTo(LotteryDrawVerificationService.STATUS_PENDING);
        assertThat(decision.result()).isEqualTo("17254");
        assertThat(decision.confirmations()).isEqualTo(1);
        assertThat(decision.firstSeenAt()).isEqualTo(now);
    }

    @Test
    void settledResultIsNeverSilentlyOverwritten() {
        LotteryDrawVerificationService.Decision decision = service.evaluate(
                "SETTLED", "17254", 2, now, "60929", now.plusMinutes(1));

        assertThat(decision.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.CONFLICT);
        assertThat(decision.status()).isEqualTo("SETTLED");
        assertThat(decision.result()).isEqualTo("17254");
        assertThat(decision.error()).contains("不一致");
    }

    @Test
    void newlyVerifiedResultCannotChangeBeforeSettlement() {
        LotteryDrawVerificationService.Decision decision = service.evaluate(
                "DRAWN", "17254", 1, now, "60929", now.plusSeconds(1));

        assertThat(decision.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.CONFLICT);
        assertThat(decision.status()).isEqualTo("DRAWN");
        assertThat(decision.result()).isEqualTo("17254");
    }

    @Test
    void trustsCanonicalAndPersistedCommaSeparatedFiveDigitResults() {
        assertThat(service.isTrusted("17254")).isTrue();
        assertThat(service.isTrusted("1,7,2,5,4")).isTrue();
        assertThat(service.isTrusted("0,0,0,0,0")).isFalse();
        assertThat(service.isTrusted("5,6,8")).isFalse();
    }
}
