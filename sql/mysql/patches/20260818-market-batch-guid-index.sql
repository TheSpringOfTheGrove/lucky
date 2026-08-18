SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='lucky5_market_route_item'
     AND index_name='uk_lucky5_market_route_guid')>0,
  'ALTER TABLE `lucky5_market_route_item` DROP INDEX `uk_lucky5_market_route_guid`',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;

SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='lucky5_market_route_item'
     AND index_name='idx_lucky5_market_route_guid')=0,
  'ALTER TABLE `lucky5_market_route_item` ADD KEY `idx_lucky5_market_route_guid` (`tenant_id`,`user_id`,`market_guid`)',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;
