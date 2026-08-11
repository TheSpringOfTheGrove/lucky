SET NAMES utf8mb4;

-- 老板基础配置使用版本号增量修复；只处理旧版本且明显缺失的字段。
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='lucky5_owner_initialization' AND column_name='schema_version')=0,
  'ALTER TABLE `lucky5_owner_initialization` ADD COLUMN `schema_version` int NOT NULL DEFAULT 1 AFTER `initialization_count`',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;

SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='lucky5_member' AND column_name='last_seen_at')=0,
  'ALTER TABLE `lucky5_member` ADD COLUMN `last_seen_at` datetime(6) NULL AFTER `avatar`',
  'SELECT 1'
);
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;

UPDATE `lucky5_config` config
JOIN `lucky5_owner_initialization` marker
  ON marker.`tenant_id`=config.`tenant_id` AND marker.`user_id`=config.`user_id` AND marker.`deleted`=b'0'
LEFT JOIN `system_users` account
  ON account.`tenant_id`=config.`tenant_id` AND account.`id`=config.`user_id` AND account.`deleted`=b'0'
SET config.`room_name`=IF(config.`room_name`='' OR config.`room_name` LIKE '%??%',
      CONCAT(COALESCE(NULLIF(account.`username`,''),CONVERT(0xE88081E69DBF USING utf8mb4)),
        CONVERT(0x20E5B9B8E8BF9035E688BFE997B4 USING utf8mb4)), config.`room_name`),
    config.`close_time`=IF(config.`close_time`='', '23:55', config.`close_time`),
    config.`settle_delay`=IF(config.`settle_delay`<=0, 8, config.`settle_delay`),
    config.`min_deposit`=IF(config.`min_deposit`<=0, 100, config.`min_deposit`),
    config.`max_deposit`=IF(config.`max_deposit`<=0, 50000, config.`max_deposit`)
WHERE marker.`schema_version`<2;

UPDATE `lucky5_link_config` links
JOIN `lucky5_owner_initialization` marker
  ON marker.`tenant_id`=links.`tenant_id` AND marker.`user_id`=links.`user_id` AND marker.`deleted`=b'0'
SET links.`group_link_enabled`=COALESCE(links.`group_link_enabled`, b'1'),
    links.`private_link_enabled`=COALESCE(links.`private_link_enabled`, b'1'),
    links.`default_room_mode`=IF(links.`default_room_mode` IN ('GROUP','PRIVATE'),
      links.`default_room_mode`, 'GROUP')
WHERE marker.`schema_version`<2;

UPDATE `lucky5_owner_initialization`
SET `schema_version`=2, `last_source`='REPAIR', `last_initialized_at`=NOW(6), `update_time`=NOW(6)
WHERE `schema_version`<2 AND `deleted`=b'0';

-- 保留存量号码便于审计，但明确标记异常，禁止作为订单开奖结果或房间最近开奖使用。
UPDATE `lucky5_draw`
SET `status`=CONVERT(0xE5BC82E5B8B8 USING utf8mb4), `update_time`=NOW(6)
WHERE (REPLACE(`result`,',','') NOT REGEXP '^[0-9]{5}$' OR REPLACE(`result`,',','')='00000')
  AND `deleted`=b'0';
