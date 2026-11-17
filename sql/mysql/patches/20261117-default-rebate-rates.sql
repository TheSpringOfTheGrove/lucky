-- Persist the owner's one-click rebate rates so existing and future real members stay in sync.
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='lucky5_config' AND column_name='default_normal_rebate_rate')=0,
  'ALTER TABLE `lucky5_config` ADD COLUMN `default_normal_rebate_rate` decimal(8,4) NOT NULL DEFAULT 0 AFTER `use_proxy`',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;

SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='lucky5_config' AND column_name='default_lhh_rebate_rate')=0,
  'ALTER TABLE `lucky5_config` ADD COLUMN `default_lhh_rebate_rate` decimal(8,4) NOT NULL DEFAULT 0 AFTER `default_normal_rebate_rate`',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;
