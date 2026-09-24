package com.dag.core.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时任务统一入口（调度/巡检两路）。
 * <p>定时线程 try/catch 保活：单轮异常不影响后续轮次（EX-C07）。</p>
 */
public class ScanRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanRunner.class);

    private final Scheduler scheduler;
    private final NodeInspector inspector;

    public ScanRunner(Scheduler scheduler, NodeInspector inspector) {
        this.scheduler = scheduler;
        this.inspector = inspector;
    }

    @Scheduled(fixedDelayString = "${dag.scheduler.interval-ms:1000}")
    public void run() {
        try {
            scheduler.scanAndSchedule();
        } catch (Throwable t) {
            log.error("scanAndSchedule round failed", t);
        }
        try {
            inspector.inspect();
        } catch (Throwable t) {
            log.error("inspect round failed", t);
        }
    }
}
