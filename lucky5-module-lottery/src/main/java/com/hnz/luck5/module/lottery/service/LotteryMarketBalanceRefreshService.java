package com.hnz.luck5.module.lottery.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Refreshes the exact external-market balance without delaying the player's order confirmation. */
@Service
@RequiredArgsConstructor
public class LotteryMarketBalanceRefreshService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketBalanceRefreshService.class);

    private final LotteryMarketSyncService marketSyncService;

    @Async
    public void refresh(Long tenantId, Long userId) {
        try {
            marketSyncService.refreshOwnerBalance(tenantId, userId);
        } catch (RuntimeException ex) {
            // The accepted order remains authoritative. Keep the estimated cached balance and let the normal
            // connection sync repair it later instead of turning a successful player reply into a failure.
            LOGGER.warn("外盘下注成功后的余额异步刷新失败 tenant={} user={}: {}", tenantId, userId,
                    ex.getMessage());
        }
    }
}
