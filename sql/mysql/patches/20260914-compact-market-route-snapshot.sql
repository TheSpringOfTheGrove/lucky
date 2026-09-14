-- One physical market-route row per original command. Existing per-selection rows remain readable.
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='lucky5_market_route_item' AND column_name='snapshot_json')=0,
  'ALTER TABLE `lucky5_market_route_item` ADD COLUMN `snapshot_json` mediumtext NULL AFTER `selection`',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;
