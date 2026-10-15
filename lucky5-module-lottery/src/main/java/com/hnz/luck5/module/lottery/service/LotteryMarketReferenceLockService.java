package com.hnz.luck5.module.lottery.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/** Serializes reference assignment per owner and issue, independently from the market write lock. */
@Service
@RequiredArgsConstructor
public class LotteryMarketReferenceLockService {

    private final RedissonClient redissonClient;

    public <T> T execute(Long tenantId, Long userId, String period, Supplier<T> operation) {
        RLock lock = redissonClient.getLock("lucky5:market-reference:" + tenantId + ":" + userId + ":" + period);
        lock.lock();
        try {
            return operation.get();
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
