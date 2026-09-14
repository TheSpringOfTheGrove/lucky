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

    @Async("lotteryMarketReadExecutor")
    public void refresh(Long tenantId, Long userId) {
        String accountKey = tenantId + ":" + userId;
        if (!refreshingAccounts.add(accountKey)) return;
        try {
            marketSyncService.refreshOwnerBalance(tenantId, userId);
        } catch (RuntimeException ex) {
            LOGGER.warn("外盘下注成功后的余额异步刷新失败 tenant={} user={}: {}", tenantId, userId,
                    ex.getMessage());
        } finally {
            refreshingAccounts.remove(accountKey);
        }
    }
}
