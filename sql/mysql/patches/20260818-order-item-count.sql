SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='lucky5_order' AND column_name='item_count')=0,
  'ALTER TABLE `lucky5_order` ADD COLUMN `item_count` int NOT NULL DEFAULT 0 AFTER `market_attempts`',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl;
EXECUTE lucky5_stmt;
DEALLOCATE PREPARE lucky5_stmt;

UPDATE `lucky5_order` o
JOIN (
  SELECT `tenant_id`, `user_id`, `order_id`, COUNT(*) AS `item_count`
  FROM `lucky5_bet_item`
  WHERE `deleted` = b'0'
  GROUP BY `tenant_id`, `user_id`, `order_id`
) counts ON counts.`tenant_id` = o.`tenant_id`
  AND counts.`user_id` = o.`user_id`
  AND counts.`order_id` = o.`id`
SET o.`item_count` = counts.`item_count`
WHERE o.`deleted` = b'0' AND o.`item_count` = 0;
