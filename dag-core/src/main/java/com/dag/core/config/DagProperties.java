package com.dag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * dag.* 配置项（02 §6）。
 * <pre>
 * dag.enabled=true                        # 引擎总开关
 * dag.scheduler.enabled=true              # 定时调度开关（调度+巡检）
 * dag.scheduler.interval-ms=1000          # 扫描间隔
 * dag.scheduler.batch-size=100            # 每轮扫描上限
 * dag.executor.threads=8                  # 节点执行线程池
 * dag.node.timeout-seconds=60             # 节点超时（平台统一，节点不自定义）
 * dag.lock.wait-ms=100                    # 领取锁等待
 * dag.lock.redis-required=false           # 多实例部署建议 true（缺失则降级本地锁+关异常巡检）
 * dag.lock.redis-address=redis://localhost:6379
 * dag.lock.redis-password=                 # Redis 密码（单点/集群共用，可为空）
 * dag.lock.redis-cluster-addresses=       # 逗号分隔，配置后走 Redis Cluster
 * </pre>
 */
@ConfigurationProperties(prefix = "dag")
public class DagProperties {

    private boolean enabled = true;
    private final Scheduler scheduler = new Scheduler();
    private final Executor executor = new Executor();
    private final Node node = new Node();
    private final Lock lock = new Lock();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Scheduler getScheduler() { return scheduler; }
    public Executor getExecutor() { return executor; }
    public Node getNode() { return node; }
    public Lock getLock() { return lock; }

    public static class Scheduler {
        private boolean enabled = true;
        private long intervalMs = 1000;
        private int batchSize = 100;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    public static class Executor {
        private int threads = 8;
        private int queueSize = 1000;
        public int getThreads() { return threads; }
        public void setThreads(int threads) { this.threads = threads; }
        public int getQueueSize() { return queueSize; }
        public void setQueueSize(int queueSize) { this.queueSize = queueSize; }
    }

    public static class Node {
        private int timeoutSeconds = 60;
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Lock {
        private long waitMs = 100;
        private boolean redisRequired = false;
        private String redisAddress = "redis://localhost:6379";
        private String redisPassword = "";
        private String redisClusterAddresses = "";
        public long getWaitMs() { return waitMs; }
        public void setWaitMs(long waitMs) { this.waitMs = waitMs; }
        public boolean isRedisRequired() { return redisRequired; }
        public void setRedisRequired(boolean redisRequired) { this.redisRequired = redisRequired; }
        public String getRedisAddress() { return redisAddress; }
        public void setRedisAddress(String redisAddress) { this.redisAddress = redisAddress; }
        public String getRedisPassword() { return redisPassword; }
        public void setRedisPassword(String redisPassword) { this.redisPassword = redisPassword; }
        public String getRedisClusterAddresses() { return redisClusterAddresses; }
        public void setRedisClusterAddresses(String redisClusterAddresses) { this.redisClusterAddresses = redisClusterAddresses; }
    }
}
