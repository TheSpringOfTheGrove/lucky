package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Rejects an OPEN issue after its upstream countdown has expired without a fresh snapshot.
 *
 * <p>The grace period absorbs normal polling jitter. It must never be used to synthesize a new period or extend
 * betting after the upstream source stopped updating.</p>
 */
@Component
public class LotteryIssueFreshnessPolicy {

    public static final String STATUS_SOURCE_STALE = "SOURCE_STALE";

    @Value("${lottery.draw-source.stale-grace-seconds:90}")
    private long staleGraceSeconds = 90;

    public boolean isStale(IssueDO issue) {
        return isStale(issue, LocalDateTime.now());
    }

    boolean isStale(IssueDO issue, LocalDateTime now) {
        if (issue == null || !"OPEN".equals(issue.getStatus())) {
            return false;
        }
        long grace = Math.max(0, staleGraceSeconds);
        if (issue.getServerTime() != null) {
            long remaining = Math.max(0, issue.getRemainingSeconds() == null ? 0 : issue.getRemainingSeconds());
            return !now.isBefore(issue.getServerTime().plusSeconds(remaining + grace));
        }
        return issue.getUpdateTime() == null || !now.isBefore(issue.getUpdateTime().plusSeconds(grace));
    }

    public String effectiveStatus(IssueDO issue) {
        return isStale(issue) ? STATUS_SOURCE_STALE : issue == null ? "UNAVAILABLE" : issue.getStatus();
    }
}
