package com.hnz.luck5.module.lottery.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/** Serializes every login and remote operation performed with one owner's market account. */
@Service
@RequiredArgsConstructor
public class LotteryMarketAccountLockService {

    private static final String KEY_PREFIX = "lucky5:market-account:";

    private final RedissonClient redissonClient;

    public <T> T execute(Long tenantId, Long userId, Supplier<T> operation) {
        RLock lock = lock(tenantId, userId);
        lock.lock();
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
        return redissonClient.getFairLock(KEY_PREFIX + tenantId + ":" + userId);
    }

    private void unlock(RLock lock) {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
