package com.hnz.luck5.module.lottery.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryDrawVerificationServiceTest {

    private final LotteryDrawVerificationService service = new LotteryDrawVerificationService();
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 9, 18, 0);

    @Test
    void blocksAllZeroResultWithoutMakingItSettleable() {
        LotteryDrawVerificationService.Decision decision = service.evaluate(
                "CLOSED", "", 0, null, "00000", now, Duration.ofSeconds(5));

        assertThat(decision.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.ABNORMAL);
        assertThat(decision.status()).isEqualTo(LotteryDrawVerificationService.STATUS_ABNORMAL);
        assertThat(decision.confirmations()).isZero();
        assertThat(decision.error()).contains("暂停自动结算");

        LotteryDrawVerificationService.Decision malformed = service.evaluate(
                "CLOSED", "", 0, null, "", now, Duration.ofSeconds(5));
        assertThat(malformed.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.ABNORMAL);
        assertThat(malformed.status()).isEqualTo(LotteryDrawVerificationService.STATUS_ABNORMAL);
    }

    @Test
    void requiresASecondObservationAfterTheSafetyDelay() {
        LotteryDrawVerificationService.Decision first = service.evaluate(
                "CLOSED", "", 0, null, "17254", now, Duration.ofSeconds(5));
        LotteryDrawVerificationService.Decision tooSoon = service.evaluate(
                first.status(), first.result(), first.confirmations(), first.firstSeenAt(),
                "17254", now.plusSeconds(2), Duration.ofSeconds(5));
        LotteryDrawVerificationService.Decision verified = service.evaluate(
                tooSoon.status(), tooSoon.result(), tooSoon.confirmations(), tooSoon.firstSeenAt(),
                "17254", now.plusSeconds(6), Duration.ofSeconds(5));

        assertThat(first.status()).isEqualTo(LotteryDrawVerificationService.STATUS_PENDING);
        assertThat(first.confirmations()).isEqualTo(1);
        assertThat(tooSoon.status()).isEqualTo(LotteryDrawVerificationService.STATUS_PENDING);
        assertThat(verified.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.VERIFIED);
        assertThat(verified.status()).isEqualTo(LotteryDrawVerificationService.STATUS_VERIFIED);
        assertThat(verified.confirmations()).isEqualTo(2);
    }

    @Test
    void changedApiResultRestartsConfirmation() {
        LotteryDrawVerificationService.Decision decision = service.evaluate(
                LotteryDrawVerificationService.STATUS_PENDING, "17254", 1, now,
                "60929", now.plusSeconds(30), Duration.ofSeconds(5));

        assertThat(decision.status()).isEqualTo(LotteryDrawVerificationService.STATUS_PENDING);
        assertThat(decision.result()).isEqualTo("60929");
        assertThat(decision.confirmations()).isEqualTo(1);
        assertThat(decision.firstSeenAt()).isEqualTo(now.plusSeconds(30));
    }

    @Test
    void settledResultIsNeverSilentlyOverwritten() {
        LotteryDrawVerificationService.Decision decision = service.evaluate(
                "SETTLED", "17254", 2, now, "60929", now.plusMinutes(1), Duration.ofSeconds(5));

        assertThat(decision.outcome()).isEqualTo(LotteryDrawVerificationService.Outcome.CONFLICT);
        assertThat(decision.status()).isEqualTo("SETTLED");
        assertThat(decision.result()).isEqualTo("17254");
        assertThat(decision.error()).contains("不一致");
    }
}
