-- 示例固定流程定义：order 业务（createOrder → pay(异步) → notify）
INSERT IGNORE INTO flow_definition (id, biz_type, biz_id, graph_json, version, status, created_at, updated_at)
VALUES (
  'def_order_flow',
  'order',
  'order-flow',
  '{"nodes":[{"biz_node_id":"createOrder","handler":"echoHandler","async_type":0,"biz_args":{"action":"createOrder"}},{"biz_node_id":"pay","handler":"asyncHandler","async_type":1},{"biz_node_id":"notify","handler":"echoHandler","async_type":0,"biz_args":{"action":"notify"}}],"edges":[{"from":"createOrder","to":"pay"},{"from":"pay","to":"notify"}]}',
  1,
  'ENABLE',
  NOW(),
  NOW()
);
