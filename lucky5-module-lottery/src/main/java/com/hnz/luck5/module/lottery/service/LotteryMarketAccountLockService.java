package com.hnz.luck5.module.lottery.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/** Serializes only write operations performed with one owner's market account. */
@Service
@RequiredArgsConstructor
public class LotteryMarketAccountLockService {

    private static final String KEY_PREFIX = "lucky5:market-account:";
    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryMarketAccountLockService.class);

    private final RedissonClient redissonClient;

    public <T> T execute(Long tenantId, Long userId, Supplier<T> operation) {
        RLock lock = lock(tenantId, userId);
        long waitingStartedAt = System.nanoTime();
        lock.lock();
        long waitingMs = (System.nanoTime() - waitingStartedAt) / 1_000_000;
        if (waitingMs >= 100) {
            LOGGER.info("盘口账户锁已获取 tenant={} user={} waitMs={}", tenantId, userId, waitingMs);
        }
        try {
            return operation.get();
        } finally {
            unlock(lock);
        }
    }

    public void execute(Long tenantId, Long userId, Runnable operation) {
        execute(tenantId, userId, () -> {
            operation.run();
            return null;
        });
    }

    public boolean tryExecute(Long tenantId, Long userId, Runnable operation) {
        RLock lock = lock(tenantId, userId);
        if (!lock.tryLock()) return false;
        try {
            operation.run();
            return true;
        } finally {
            unlock(lock);
        }
    }

    private RLock lock(Long tenantId, Long userId) {
        // A fair lock preserves cancelled/dead waiters for a grace period.  That is useful for a human queue but
        // disastrous for a time-sensitive market write: a live BatchBet may wait tens of seconds with no active
        // order.  A normal re-entrant lock still guarantees one write at a time without this stale-queue penalty.
        return redissonClient.getLock(KEY_PREFIX + tenantId + ":" + userId);
    }

    private void unlock(RLock lock) {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
