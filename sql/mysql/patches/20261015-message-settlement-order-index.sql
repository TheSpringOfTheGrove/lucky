-- Limit settlement receipt updates to the matching order rows instead of scanning all messages of an owner.
-- Safe to rerun on an existing installation.
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='lucky5_message'
     AND index_name='idx_lucky5_message_order')=0,
  'ALTER TABLE `lucky5_message` ADD KEY `idx_lucky5_message_order` (`tenant_id`,`user_id`,`order_id`,`deleted`)',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;
