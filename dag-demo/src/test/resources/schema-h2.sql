-- H2（MODE=MySQL）测试库 DDL：与 schema.sql 等价，去掉 MySQL 特有 ENGINE/COMMENT/CHARSET
CREATE TABLE IF NOT EXISTS flow_definition (
  id          VARCHAR(64) NOT NULL,
  biz_type    VARCHAR(64) NOT NULL,
  biz_id      VARCHAR(64) NOT NULL,
  graph_json  TEXT        NOT NULL,
  version     INT         NOT NULL DEFAULT 1,
  status      VARCHAR(16) NOT NULL DEFAULT 'ENABLE',
  created_at  DATETIME    NOT NULL,
  updated_at  DATETIME    NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_flow_biz UNIQUE (biz_type, biz_id)
);

CREATE TABLE IF NOT EXISTS task_instance (
  task_id     VARCHAR(64) NOT NULL,
  biz_type    VARCHAR(64) NOT NULL,
  biz_id      VARCHAR(64) NOT NULL,
  graph_json  TEXT        NOT NULL,
  state       VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  biz_args    TEXT,
  started_at  DATETIME,
  finished_at DATETIME,
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  PRIMARY KEY (task_id),
  CONSTRAINT uk_task_biz UNIQUE (biz_type, biz_id)
);
CREATE INDEX idx_task_state ON task_instance(state);

CREATE TABLE IF NOT EXISTS node_instance (
  id          VARCHAR(64) NOT NULL,
  task_id     VARCHAR(64) NOT NULL,
  biz_node_id VARCHAR(64) NOT NULL,
  handler     VARCHAR(128) NOT NULL,
  state       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  result      TEXT,
  biz_args    TEXT,
  time_out    TINYINT NOT NULL DEFAULT 0,
  async_type  TINYINT NOT NULL DEFAULT 0,
  started_at  DATETIME,
  finished_at DATETIME,
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_node UNIQUE (task_id, biz_node_id)
);
CREATE INDEX idx_node_task_state ON node_instance(task_id, state);
CREATE INDEX idx_node_state_started ON node_instance(state, started_at);

CREATE TABLE IF NOT EXISTS task_trace (
  id          VARCHAR(64) NOT NULL,
  task_id     VARCHAR(64) NOT NULL,
  node_id     VARCHAR(64),
  biz_node_id VARCHAR(64),
  event_type  VARCHAR(32) NOT NULL,
  task_state  VARCHAR(16) NOT NULL,
  node_state  VARCHAR(16),
  detail      TEXT,
  created_at  DATETIME NOT NULL,
  PRIMARY KEY (id)
);
CREATE INDEX idx_trace_task_time ON task_trace(task_id, created_at);
CREATE INDEX idx_trace_node_time ON task_trace(node_id, created_at);
