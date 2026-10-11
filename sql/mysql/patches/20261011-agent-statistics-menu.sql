-- 现有数据库幂等新增独立的代理统计导航，仅管理员和代理授权。
SET NAMES utf8mb4;

SET @agent_stats_user_index_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
   AND table_name='system_users' AND index_name='idx_user_agent_stats')=0,
  'ALTER TABLE `system_users` ADD INDEX `idx_user_agent_stats` (`tenant_id`,`account_type`,`parent_agent_user_id`)',
  'SELECT 1');
PREPARE agent_stats_user_index_stmt FROM @agent_stats_user_index_ddl;
EXECUTE agent_stats_user_index_stmt;
DEALLOCATE PREPARE agent_stats_user_index_stmt;

SET @agent_stats_ledger_index_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
   AND table_name='system_gold_ledger' AND index_name='idx_system_gold_stats')=0,
  'ALTER TABLE `system_gold_ledger` ADD INDEX `idx_system_gold_stats` (`tenant_id`,`operation_type`,`source_user_id`,`create_time`,`id`)',
  'SELECT 1');
PREPARE agent_stats_ledger_index_stmt FROM @agent_stats_ledger_index_ddl;
EXECUTE agent_stats_ledger_index_stmt;
DEALLOCATE PREPARE agent_stats_ledger_index_stmt;

INSERT INTO `system_menu`
  (`id`,`name`,`permission`,`type`,`sort`,`parent_id`,`path`,`icon`,`component`,`component_name`,
   `status`,`visible`,`keep_alive`,`always_show`,`creator`,`updater`,`deleted`)
VALUES
  (7180,'代理统计','system:agent-statistics:query',2,880,0,'/system/agent-statistics','fa:bar-chart',
   'system/agentStatistics/index','SystemAgentStatistics',0,b'1',b'1',b'1','1','1',b'0')
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`),`permission`=VALUES(`permission`),
  `type`=VALUES(`type`),`sort`=VALUES(`sort`),`parent_id`=VALUES(`parent_id`),
  `path`=VALUES(`path`),`icon`=VALUES(`icon`),`component`=VALUES(`component`),
  `component_name`=VALUES(`component_name`),`status`=0,`visible`=b'1',`deleted`=b'0';

INSERT INTO `system_role_menu` (`role_id`,`menu_id`,`creator`,`updater`,`tenant_id`)
SELECT r.id,7180,'1','1',r.tenant_id FROM `system_role` r
WHERE r.code IN ('super_admin','lottery_agent') AND r.deleted=b'0'
AND NOT EXISTS (SELECT 1 FROM `system_role_menu` rm
                WHERE rm.role_id=r.id AND rm.menu_id=7180 AND rm.deleted=b'0');

UPDATE `system_tenant_package` SET `menu_ids`=JSON_ARRAY_APPEND(`menu_ids`,'$',7180)
WHERE `deleted`=b'0' AND JSON_VALID(`menu_ids`)
  AND JSON_CONTAINS(`menu_ids`,'100') AND NOT JSON_CONTAINS(`menu_ids`,'7180');
