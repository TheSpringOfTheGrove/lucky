package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Applies the upstream betting cutoff and rejects an OPEN issue after the source becomes stale.
 *
 * <p>The grace period only classifies a source as stale. It must never extend betting past the upstream
 * {@code serverTime + remainingSeconds} cutoff.</p>
 */
@Component
public class LotteryIssueFreshnessPolicy {

    public static final String STATUS_SOURCE_STALE = "SOURCE_STALE";

    @Value("${lottery.draw-source.stale-grace-seconds:90}")
    private long staleGraceSeconds = 90;

    public boolean isStale(IssueDO issue) {
        return isStale(issue, LocalDateTime.now());
    }

    public boolean isBettingClosed(IssueDO issue) {
        return isBettingClosed(issue, LocalDateTime.now());
    }

    boolean isBettingClosed(IssueDO issue, LocalDateTime now) {
        if (issue == null || !"OPEN".equals(issue.getStatus())) {
            return true;
        }
        LocalDateTime cutoff = localCutoff(issue);
        if (cutoff == null) {
            // An OPEN snapshot without an authoritative source clock is unsafe for real-money betting.
            return true;
        }
        return !now.isBefore(cutoff);
    }

    boolean isStale(IssueDO issue, LocalDateTime now) {
        if (issue == null || !"OPEN".equals(issue.getStatus())) {
            return false;
        }
        long grace = Math.max(0, staleGraceSeconds);
        LocalDateTime cutoff = localCutoff(issue);
        if (cutoff != null) {
            return !now.isBefore(cutoff.plusSeconds(grace));
        }
        return issue.getUpdateTime() == null || !now.isBefore(issue.getUpdateTime().plusSeconds(grace));
    }

    public String effectiveStatus(IssueDO issue) {
        return effectiveStatus(issue, LocalDateTime.now());
    }

    String effectiveStatus(IssueDO issue, LocalDateTime now) {
        if (issue == null) {
            return "UNAVAILABLE";
        }
        if (!"OPEN".equals(issue.getStatus())) {
            return issue.getStatus();
        }
        if (isStale(issue, now)) {
            return STATUS_SOURCE_STALE;
        }
        if (isBettingClosed(issue, now)) {
            return "CLOSED";
        }
        return issue.getStatus();
    }

    public int effectiveRemainingSeconds(IssueDO issue, LocalDateTime now) {
        LocalDateTime cutoff = localCutoff(issue);
        if (cutoff == null || !now.isBefore(cutoff)) return 0;
        long remainingMillis = Duration.between(now, cutoff).toMillis();
        return (int) Math.min(Integer.MAX_VALUE, (remainingMillis + 999) / 1000);
    }

    public LocalDateTime authoritativeSourceTime(IssueDO issue, LocalDateTime now) {
        if (issue == null || issue.getServerTime() == null || issue.getSourceObservedAt() == null) return now;
        return issue.getServerTime().plus(Duration.between(issue.getSourceObservedAt(), now));
    }

    private LocalDateTime localCutoff(IssueDO issue) {
        if (issue == null) return null;
        long remaining = Math.max(0, issue.getRemainingSeconds() == null ? 0 : issue.getRemainingSeconds());
        if (issue.getSourceObservedAt() != null) return issue.getSourceObservedAt().plusSeconds(remaining);
        return issue.getServerTime() == null ? null : issue.getServerTime().plusSeconds(remaining);
    }
}
