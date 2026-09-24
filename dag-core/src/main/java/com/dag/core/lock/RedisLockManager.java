package com.dag.core.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Redis 分布式锁（Redisson RLock，watchdog 30s 自动续期）。
 * <p>锁 key：dag:node:{nodeId}。执行器持锁 = 存活标记；锁可抢 = 执行器已死。</p>
 */
public class RedisLockManager implements LockManager {

    private static final Logger log = LoggerFactory.getLogger(RedisLockManager.class);

    /** watchdog 续期租期说明：leaseTime=-1 时由 Redisson watchdog 自动续期（默认 30s 租期 / 10s 续期） */
    @SuppressWarnings("unused")
    private static final long LEASE_SECONDS = 30;

    private final RedissonClient redissonClient;

    public RedisLockManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public boolean tryLock(String key, long waitMs) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + key);
        try {
            // leaseTime=-1 → watchdog 自动续期（默认 30s 租期 / 10s 续期，执行器存活标记）
            return lock.tryLock(waitMs, -1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("tryLock interrupted, key={}", key);
            return false;
        }
    }

    @Override
    public void unlock(String key) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    public boolean isDistributed() {
        return true;
    }
}
