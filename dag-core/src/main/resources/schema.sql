-- dag-engine 四表 DDL（MySQL 5.6.x / utf8mb4）
-- 说明：所有 id 为 VARCHAR(64)；TEXT 字段不支持默认值；索引见注释

CREATE TABLE IF NOT EXISTS flow_definition (
  id          VARCHAR(64)  NOT NULL COMMENT '定义id',
  biz_type    VARCHAR(64)  NOT NULL COMMENT '业务类型',
  biz_id      VARCHAR(64)  NOT NULL COMMENT '业务主键',
  graph_json  TEXT         NOT NULL COMMENT 'DAG图（nodes+edges）',
  version     INT          NOT NULL DEFAULT 1 COMMENT '版本',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ENABLE' COMMENT 'ENABLE/DISABLE',
  created_at  DATETIME     NOT NULL COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程定义';

CREATE TABLE IF NOT EXISTS task_instance (
  task_id     VARCHAR(64) NOT NULL COMMENT '任务id（引擎生成）',
  biz_type    VARCHAR(64) NOT NULL COMMENT '业务类型',
  biz_id      VARCHAR(64) NOT NULL COMMENT '业务主键',
  graph_json  TEXT        NOT NULL COMMENT '本次运行图快照（nodes+edges）',
  state       VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED/CANCELLED',
  biz_args    TEXT        NULL COMMENT '全局业务参数JSON（submit 接口传入）',
  started_at  DATETIME    NULL COMMENT '开始时间',
  finished_at DATETIME    NULL COMMENT '结束时间',
  created_at  DATETIME    NOT NULL COMMENT '创建时间',
  updated_at  DATETIME    NOT NULL COMMENT '更新时间',
  PRIMARY KEY (task_id),
  UNIQUE KEY uk_biz (biz_type, biz_id),
  KEY idx_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务表';

CREATE TABLE IF NOT EXISTS node_instance (
  id          VARCHAR(64)  NOT NULL COMMENT '节点实例id（引擎生成）',
  task_id     VARCHAR(64)  NOT NULL COMMENT '所属任务',
  biz_node_id VARCHAR(64)  NOT NULL COMMENT '用户定义节点唯一id',
  handler     VARCHAR(128) NOT NULL COMMENT '执行器bean名（@Component名）',
  state       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/WAIT_CONFIRM/SUCCESS/FAILED/CANCELLED',
  result      TEXT         NULL COMMENT '执行结果（handler回写；超时/异常由引擎填）',
  biz_args    TEXT         NULL COMMENT '节点业务参数（来自 graph_json nodes[].biz_args）',
  time_out    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否超时（引擎填写）',
  async_type  TINYINT      NOT NULL DEFAULT 0 COMMENT '0同步/1异步（来自graph_json节点定义）',
  started_at  DATETIME     NULL COMMENT '开始时间',
  finished_at DATETIME     NULL COMMENT '结束时间',
  created_at  DATETIME     NOT NULL COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_node (task_id, biz_node_id),
  KEY idx_task_state (task_id, state),
  KEY idx_state_started (state, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点表';

CREATE TABLE IF NOT EXISTS task_trace (
  id           VARCHAR(64) NOT NULL COMMENT '流水id',
  task_id      VARCHAR(64) NOT NULL COMMENT '所属任务',
  node_id      VARCHAR(64) NULL COMMENT '节点实例id（节点级事件；任务级为空）',
  biz_node_id  VARCHAR(64) NULL COMMENT '节点业务id（可读性）',
  event_type   VARCHAR(32) NOT NULL COMMENT '事件类型',
  task_state   VARCHAR(16) NOT NULL COMMENT '事件后任务状态（快照）',
  node_state   VARCHAR(16) NULL COMMENT '事件后节点状态（节点级事件）',
  detail       TEXT        NULL COMMENT '附加信息（来源/模式/结果/操作）',
  created_at   DATETIME    NOT NULL COMMENT '事件时间',
  PRIMARY KEY (id),
  KEY idx_task_time (task_id, created_at),
  KEY idx_node_time (node_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务流水';
