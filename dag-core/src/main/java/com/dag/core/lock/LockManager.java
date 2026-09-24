package com.dag.core.lock;

/**
 * 分布式锁抽象（多实例防重核心）。
 * <p>实现：RedisLockManager（Redisson RLock + watchdog 自动续期）/ LocalLockManager（JVM 降级）。</p>
 */
public interface LockManager {

    /** 锁 key 前缀：dag:node:{nodeId} */
    String KEY_PREFIX = "dag:node:";

    /**
     * 尝试加锁。
     * @param key   锁 key（不含前缀）
     * @param waitMs 等待毫秒
     * @return true=获取成功
     */
    boolean tryLock(String key, long waitMs);

    /** 释放锁 */
    void unlock(String key);

    /** 是否支持跨进程互斥（true=Redis，false=本地降级） */
    default boolean isDistributed() {
        return false;
    }
}
