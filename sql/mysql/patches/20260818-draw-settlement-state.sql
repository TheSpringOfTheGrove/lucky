SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='lucky5_draw'
     AND column_name='settled_at' AND is_nullable='NO')>0,
  'ALTER TABLE `lucky5_draw` MODIFY COLUMN `settled_at` datetime NULL',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;
