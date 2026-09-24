package com.dag.core.lock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JVM 本地锁（Redis 缺失/未启用时的降级实现）。
 * <p>注意：仅单进程内互斥；此时异常巡检的"锁判定"自动关闭（多实例防重仍由 CAS 保证，崩溃节点走超时 FAILED + 人工 retry）。</p>
 */
public class LocalLockManager implements LockManager {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String key, long waitMs) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        try {
            return lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void unlock(String key) {
        ReentrantLock lock = locks.get(key);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    public boolean isDistributed() {
        return false;
    }
}
