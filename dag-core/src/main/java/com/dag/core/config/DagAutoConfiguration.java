package com.dag.core.config;

import com.dag.core.DagEngine;
import com.dag.core.DagEngineImpl;
import com.dag.core.engine.ClaimService;
import com.dag.core.engine.NodeExecutor;
import com.dag.core.engine.NodeInspector;
import com.dag.core.engine.NodeTransitionService;
import com.dag.core.engine.Scheduler;
import com.dag.core.engine.ScanRunner;
import com.dag.core.engine.TaskOperationService;
import com.dag.core.engine.TaskStateMachine;
import com.dag.core.graph.GraphParser;
import com.dag.core.graph.GraphValidator;
import com.dag.core.handler.HandlerRegistry;
import com.dag.core.lock.LocalLockManager;
import com.dag.core.lock.LockManager;
import com.dag.core.lock.RedisLockManager;
import com.dag.core.repository.FlowDefinitionMapper;
import com.dag.core.repository.NodeMapper;
import com.dag.core.repository.TaskMapper;
import com.dag.core.repository.TraceMapper;
import com.dag.core.trace.TraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * dag-core Starter 自动配置。
 * <p>业务方引入 dag-core 依赖 + 配置 DataSource 后自动装配引擎；dag.enabled=false 可整体关闭。</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DagProperties.class)
@MapperScan("com.dag.core.repository")
@ConditionalOnProperty(name = "dag.enabled", havingValue = "true", matchIfMissing = true)
public class DagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper dagObjectMapper() {
        return new ObjectMapper();
    }

    /** starter 开箱即用：强制开启下划线→驼峰映射（DB 字段 user_style ↔ 实体字段 userStyle） */
    @Bean
    public ConfigurationCustomizer dagMybatisCustomizer() {
        return configuration -> configuration.setMapUnderscoreToCamelCase(true);
    }

    @Bean
    public HandlerRegistry handlerRegistry(ApplicationContext applicationContext) {
        HandlerRegistry registry = new HandlerRegistry(applicationContext);
        registry.init();
        return registry;
    }

    @Bean
    public GraphParser graphParser(ObjectMapper objectMapper) {
        return new GraphParser(objectMapper);
    }

    @Bean
    public GraphValidator graphValidator(HandlerRegistry handlerRegistry) {
        return new GraphValidator(handlerRegistry);
    }

    @Bean
    @ConditionalOnClass(name = "org.redisson.api.RedissonClient")
    @ConditionalOnProperty(name = "dag.lock.redis-required", havingValue = "true")
    public LockManager redisLockManager(DagProperties properties) {
        Config config = new Config();
        String password = properties.getLock().getRedisPassword();
        String cluster = properties.getLock().getRedisClusterAddresses();
        if (cluster != null && !cluster.trim().isEmpty()) {
            org.redisson.config.ClusterServersConfig clusterConfig = config.useClusterServers()
                    .addNodeAddress(cluster.split(","));
            if (password != null && !password.isEmpty()) {
                clusterConfig.setPassword(password);
            }
        } else {
            org.redisson.config.SingleServerConfig singleConfig = config.useSingleServer()
                    .setAddress(properties.getLock().getRedisAddress());
            if (password != null && !password.isEmpty()) {
                singleConfig.setPassword(password);
            }
        }
        RedissonClient client = Redisson.create(config);
        return new RedisLockManager(client);
    }

    @Bean
    @ConditionalOnMissingBean(LockManager.class)
    public LockManager localLockManager() {
        return new LocalLockManager();
    }

    @Bean
    public TaskStateMachine taskStateMachine(TaskMapper taskMapper) {
        return new TaskStateMachine(taskMapper);
    }

    @Bean
    public TraceRecorder traceRecorder(TraceMapper traceMapper) {
        return new TraceRecorder(traceMapper);
    }

    @Bean
    public NodeTransitionService nodeTransitionService(NodeMapper nodeMapper, TaskMapper taskMapper,
                                                       TaskStateMachine taskStateMachine, TraceRecorder traceRecorder) {
        return new NodeTransitionService(nodeMapper, taskMapper, taskStateMachine, traceRecorder);
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor dagExecutor(DagProperties properties) {
        DagProperties.Executor executorProps = properties.getExecutor();
        return new ThreadPoolExecutor(
                executorProps.getThreads(), executorProps.getThreads(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(executorProps.getQueueSize()),
                new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "dag-executor-" + seq.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public NodeExecutor nodeExecutor(LockManager lockManager, HandlerRegistry handlerRegistry,
                                     NodeMapper nodeMapper, TraceRecorder traceRecorder,
                                     NodeTransitionService nodeTransitionService,
                                     @Lazy ClaimService claimService,
                                     ObjectMapper objectMapper, DagProperties properties) {
        return new NodeExecutor(lockManager, handlerRegistry, nodeMapper, traceRecorder,
                nodeTransitionService, claimService, objectMapper, properties);
    }

    @Bean
    public ClaimService claimService(@Autowired ThreadPoolExecutor dagExecutor, NodeExecutor nodeExecutor,
                                     NodeMapper nodeMapper) {
        return new ClaimService(dagExecutor, nodeExecutor, nodeMapper);
    }

    @Bean
    public Scheduler scheduler(TaskMapper taskMapper, NodeMapper nodeMapper, GraphParser graphParser,
                               ClaimService claimService, TaskStateMachine stateMachine, DagProperties properties) {
        return new Scheduler(taskMapper, nodeMapper, graphParser, claimService, stateMachine, properties);
    }

    @Bean
    public NodeInspector nodeInspector(LockManager lockManager, NodeMapper nodeMapper, TaskMapper taskMapper,
                                       NodeTransitionService nodeTransitionService, DagProperties properties) {
        return new NodeInspector(lockManager, nodeMapper, taskMapper, nodeTransitionService, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "dag.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    public ScanRunner scanRunner(Scheduler scheduler, NodeInspector inspector) {
        return new ScanRunner(scheduler, inspector);
    }

    @Bean
    public TaskOperationService taskOperationService(TaskMapper taskMapper, NodeMapper nodeMapper,
                                                     TraceRecorder traceRecorder) {
        return new TaskOperationService(taskMapper, nodeMapper, traceRecorder);
    }

    @Bean
    public DagEngine dagEngine(FlowDefinitionMapper flowDefinitionMapper, TaskMapper taskMapper,
                               NodeMapper nodeMapper, TraceMapper traceMapper, GraphParser graphParser,
                               GraphValidator graphValidator, TaskOperationService operationService,
                               NodeTransitionService transitionService, ClaimService claimService,
                               Scheduler scheduler, ObjectMapper objectMapper) {
        return new DagEngineImpl(flowDefinitionMapper, taskMapper, nodeMapper, traceMapper, graphParser,
                graphValidator, operationService, transitionService, claimService, scheduler, objectMapper);
    }
}
