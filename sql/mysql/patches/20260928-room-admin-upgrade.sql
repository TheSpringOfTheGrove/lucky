-- Incremental upgrade from the pre-room-theme production version.
-- Do not replay owner configuration, balances, orders or the full business baseline.
SET NAMES utf8mb4;
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='lucky5_member' AND column_name='short_link_code')=0,
  'ALTER TABLE `lucky5_member` ADD COLUMN `short_link_code` varchar(20) NULL AFTER `open_id`', 'SELECT 1');
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='lucky5_member' AND column_name='avatar_path')=0,
  'ALTER TABLE `lucky5_member` ADD COLUMN `avatar_path` varchar(200) NOT NULL DEFAULT '''' AFTER `avatar`', 'SELECT 1');
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
   AND table_name='lucky5_member' AND index_name='uk_lucky5_member_short_link')=0,
  'ALTER TABLE `lucky5_member` ADD UNIQUE KEY `uk_lucky5_member_short_link` (`short_link_code`)', 'SELECT 1');
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='lucky5_message' AND column_name='draw_image')=0,
  'ALTER TABLE `lucky5_message` ADD COLUMN `draw_image` mediumtext NULL AFTER `reply`', 'SELECT 1');
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;

START TRANSACTION;
-- Stable external ids make replay safe. Only completed historical draws are backfilled;
-- the live application retains control of the payout/draw/next-open sequence.
INSERT INTO `lucky5_message`
  (`user_id`,`channel`,`member_id`,`member`,`period`,`content`,`status`,`external_id`,`error`,`command_type`,
   `message_type`,`reply`,`processed_at`,`creator`,`create_time`,`updater`,`update_time`,`deleted`,`tenant_id`)
SELECT d.`user_id`, '网页群', NULL, '', d.`period`, REPLACE(d.`result`, ',', ''), '已开奖',
       CONCAT('draw-result:', d.`period`), '', 'DRAW_RESULT', 'PLAYER',
       CONCAT('^^--| ', RIGHT(d.`period`, 3), '期开奖结果-',
         SUBSTRING(REPLACE(d.`result`, ',', ''), 1, 1), '|', SUBSTRING(REPLACE(d.`result`, ',', ''), 2, 1), '|',
         SUBSTRING(REPLACE(d.`result`, ',', ''), 3, 1), '|', SUBSTRING(REPLACE(d.`result`, ',', ''), 4, 1), '|',
         SUBSTRING(REPLACE(d.`result`, ',', ''), 5, 1), '|',
         CASE WHEN SUBSTRING(REPLACE(d.`result`, ',', ''), 1, 1) = SUBSTRING(REPLACE(d.`result`, ',', ''), 4, 1) THEN '和'
              WHEN SUBSTRING(REPLACE(d.`result`, ',', ''), 1, 1) > SUBSTRING(REPLACE(d.`result`, ',', ''), 4, 1) THEN '龙' ELSE '虎' END),
       d.`settled_at`, 'system', d.`settled_at`, 'system', d.`settled_at`, b'0', d.`tenant_id`
FROM `lucky5_draw` d
WHERE d.`deleted`=b'0' AND d.`settled_at` IS NOT NULL
  AND REPLACE(d.`result`, ',', '') REGEXP '^[0-9]{5}$' AND REPLACE(d.`result`, ',', '')<>'00000'
  AND NOT EXISTS (SELECT 1 FROM `lucky5_message` m WHERE m.`tenant_id`=d.`tenant_id`
    AND m.`user_id`=d.`user_id` AND m.`external_id`=CONCAT('draw-result:', d.`period`));

-- Change presentation only for existing business menus; keep their permissions and visibility.
UPDATE `system_menu` SET `icon`=CASE `id`
  WHEN 7000 THEN 'fa:dashboard' WHEN 7010 THEN 'fa:cog' WHEN 7020 THEN 'fa:cog'
  WHEN 7030 THEN 'fa:link' WHEN 7040 THEN 'fa:gear' WHEN 7050 THEN 'fa:clone'
  WHEN 7060 THEN 'fa:users' WHEN 7070 THEN 'fa:users' WHEN 7080 THEN 'fa:cny'
  WHEN 7090 THEN 'fa:shopping-cart' WHEN 7100 THEN 'fa:history' WHEN 7110 THEN 'fa:database'
  WHEN 7120 THEN 'fa:backward' WHEN 7130 THEN 'fa:cog' WHEN 7140 THEN 'fa:balance-scale'
  WHEN 7150 THEN 'fa:wrench' WHEN 7190 THEN 'fa:bolt' END
WHERE `id` IN (7000,7010,7020,7030,7040,7050,7060,7070,7080,7090,7100,7110,7120,7130,7140,7150,7190)
  AND `deleted`=b'0';
INSERT INTO `system_menu`
  (`id`,`name`,`permission`,`type`,`sort`,`parent_id`,`path`,`icon`,`component`,`component_name`,
   `status`,`visible`,`keep_alive`,`always_show`,`creator`,`updater`,`deleted`)
VALUES (7025,'飞鱼蓝鲸信息','lottery:dashboard:query',2,35,0,'/lucky5/fish-info','fa:dashboard',
        'lottery/fishInfo/index','LotteryFishInfo',0,b'1',b'1',b'1','1','1',b'0')
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`),`icon`=VALUES(`icon`),`component`=VALUES(`component`),
  `component_name`=VALUES(`component_name`),`path`=VALUES(`path`),`sort`=VALUES(`sort`);
-- This read-only page uses the same existing dashboard permission, not a broader role grant.
INSERT INTO `system_role_menu` (`role_id`,`menu_id`,`creator`,`updater`,`tenant_id`)
SELECT rm.`role_id`,7025,'1','1',rm.`tenant_id` FROM `system_role_menu` rm
WHERE rm.`menu_id`=7000 AND rm.`deleted`=b'0'
  AND NOT EXISTS (SELECT 1 FROM `system_role_menu` existing
    WHERE existing.`role_id`=rm.`role_id` AND existing.`menu_id`=7025 AND existing.`deleted`=b'0');
UPDATE `system_tenant_package`
SET `menu_ids`=JSON_ARRAY_APPEND(`menu_ids`,'$',7025)
WHERE `deleted`=b'0' AND JSON_VALID(`menu_ids`)
  AND JSON_CONTAINS(`menu_ids`,'7000') AND NOT JSON_CONTAINS(`menu_ids`,'7025');
COMMIT;
