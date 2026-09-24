package com.dag.core.engine;

import com.dag.core.enums.TaskState;
import com.dag.core.repository.TaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务状态收敛（收敛态判定）。
 * <p>规则（01 §5.2）：全 SUCCESS → SUCCESS；任一 FAILED → FAILED（CAS 一次）；cancel → CANCELLED；
 * 其余状态（含存在 WAIT_CONFIRM 未确认）→ RUNNING。重试是唯一例外路径 FAILED → RUNNING。</p>
 */
public class TaskStateMachine {

    private static final Logger log = LoggerFactory.getLogger(TaskStateMachine.class);

    private final TaskMapper taskMapper;

    public TaskStateMachine(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 节点终态触发时调用：判断任务是否需要收敛，CAS 收敛并返回收敛终态；无需收敛返回 null。
     * <p>调用方必须处于事务中（节点终态 + 收敛 + 流水同事务原子提交）。</p>
     * <p>实现为两条单条原子 SQL（先判 FAILED 后判 SUCCESS）：子查询一致性读 + task 行 CAS 写，
     * 事务内锁顺序固定为 node(读)→task(写)，避免并发节点终态事务读-写交叉死锁；行数=1 才算收敛成功。</p>
     */
    public String convergeIfNeeded(String taskId) {
        // 任一 FAILED 优先收敛 FAILED
        if (taskMapper.convergeFailed(taskId) == 1) {
            log.info("task {} converge -> FAILED", taskId);
            return TaskState.FAILED.name();
        }
        // 全 SUCCESS 收敛 SUCCESS
        if (taskMapper.convergeSuccess(taskId) == 1) {
            log.info("task {} converge -> SUCCESS", taskId);
            return TaskState.SUCCESS.name();
        }
        // CAS 失败：任务已不是 RUNNING（被 cancel / 其他实例已收敛 / 尚未满足收敛条件），以任务当前状态为准
        return null;
    }
}
