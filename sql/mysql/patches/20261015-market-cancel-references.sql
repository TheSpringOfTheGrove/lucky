-- Keep every external bet ID for a compact accepted batch; safe to rerun.
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='lucky5_market_route_item'
     AND column_name='market_cancel_references_json')=0,
  'ALTER TABLE `lucky5_market_route_item` ADD COLUMN `market_cancel_references_json` mediumtext NULL AFTER `market_bet_id`',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;
