-- Speed up the per-owner closing summary without changing existing rows.
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='lucky5_order'
     AND index_name='idx_lucky5_order_period_summary')=0,
  'ALTER TABLE `lucky5_order` ADD KEY `idx_lucky5_order_period_summary` (`tenant_id`,`user_id`,`period`,`deleted`,`create_time`,`id`)',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;

SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='lucky5_message'
     AND index_name='idx_lucky5_message_period_command')=0,
  'ALTER TABLE `lucky5_message` ADD KEY `idx_lucky5_message_period_command` (`tenant_id`,`user_id`,`period`,`command_type`,`deleted`)',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;
