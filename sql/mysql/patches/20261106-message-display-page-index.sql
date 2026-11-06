-- Match the message audit list's per-owner newest-first paging order.
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'lucky5_message'
     AND index_name = 'idx_lucky5_message_display_page') = 0,
  'ALTER TABLE `lucky5_message` ADD KEY `idx_lucky5_message_display_page` (`tenant_id`,`user_id`,`deleted`,`create_time`,`id`)',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;
