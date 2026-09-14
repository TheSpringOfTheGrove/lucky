package com.hnz.luck5.module.lottery.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Refreshes the exact external-market balance without delaying the player's order confirmation. */
@Service
@RequiredArgsConstructor
public class LotteryMarketBalanceRefreshService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketBalanceRefreshService.class);

    private final LotteryMarketSyncService marketSyncService;
    private final Set<String> refreshingAccounts = ConcurrentHashMap.newKeySet();
    private final Set<String> refreshAgainAfterAcceptance = ConcurrentHashMap.newKeySet();

    @Async("lotteryMarketReadExecutor")
    public void refresh(Long tenantId, Long userId) {
        refresh(tenantId, userId, false);
    }

    /**
     * A confirmed market write must refresh balance immediately.  When the 30-second reader is already in flight,
     * queue one more read after it rather than accepting a pre-bet balance as the new refresh baseline.
     */
    @Async("lotteryMarketReadExecutor")
    public void refreshAfterAcceptedSubmission(Long tenantId, Long userId) {
        refresh(tenantId, userId, true);
    }

    private void refresh(Long tenantId, Long userId, boolean afterAcceptedSubmission) {
        String accountKey = tenantId + ":" + userId;
        if (!refreshingAccounts.add(accountKey)) {
            if (afterAcceptedSubmission) refreshAgainAfterAcceptance.add(accountKey);
            return;
        }
        try {
            marketSyncService.refreshOwnerBalance(tenantId, userId);
        } catch (RuntimeException ex) {
            LOGGER.warn("外盘下注成功后的余额异步刷新失败 tenant={} user={}: {}", tenantId, userId,
                    ex.getMessage());
        } finally {
            refreshingAccounts.remove(accountKey);
            if (refreshAgainAfterAcceptance.remove(accountKey)) {
                // This call stays on the existing background reader thread.  It deliberately runs once, after the
                // in-flight pre-bet snapshot, and therefore establishes the correct new 30-second baseline.
                refresh(tenantId, userId, false);
            }
        }
    }
}
