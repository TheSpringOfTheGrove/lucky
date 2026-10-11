-- 后台代理金币：只处理租户 1 的现有老板，归属当前 admin(ID=1)。
-- 执行前必须备份；旧老板每人只获一次 30 金币/30 天，重复执行不会续送。
SET NAMES utf8mb4;

SET @gold_account_type_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='system_users' AND column_name='account_type')=0,
  'ALTER TABLE `system_users` ADD COLUMN `account_type` varchar(16) NULL COMMENT ''Lucky5 ADMIN/AGENT/OWNER'' AFTER `expire_time`',
  'SELECT 1');
PREPARE gold_account_type_stmt FROM @gold_account_type_ddl;
EXECUTE gold_account_type_stmt;
DEALLOCATE PREPARE gold_account_type_stmt;

SET @gold_parent_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='system_users' AND column_name='parent_agent_user_id')=0,
  'ALTER TABLE `system_users` ADD COLUMN `parent_agent_user_id` bigint NULL COMMENT ''直属代理用户 ID'' AFTER `account_type`, ADD INDEX `idx_user_parent_agent` (`tenant_id`,`parent_agent_user_id`)',
  'SELECT 1');
PREPARE gold_parent_stmt FROM @gold_parent_ddl;
EXECUTE gold_parent_stmt;
DEALLOCATE PREPARE gold_parent_stmt;

SET @gold_balance_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='system_users' AND column_name='gold_balance')=0,
  'ALTER TABLE `system_users` ADD COLUMN `gold_balance` bigint NOT NULL DEFAULT 0 COMMENT ''代理尚未分配金币'' AFTER `parent_agent_user_id`',
  'SELECT 1');
PREPARE gold_balance_stmt FROM @gold_balance_ddl;
EXECUTE gold_balance_stmt;
DEALLOCATE PREPARE gold_balance_stmt;

CREATE TABLE IF NOT EXISTS `system_gold_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operation_key` varchar(100) NOT NULL,
  `operation_type` varchar(32) NOT NULL,
  `source_user_id` bigint NULL,
  `target_user_id` bigint NOT NULL,
  `amount` bigint NOT NULL,
  `agent_balance_before` bigint NULL,
  `agent_balance_after` bigint NULL,
  `target_expire_before` datetime NULL,
  `target_expire_after` datetime NULL,
  `operator_user_id` bigint NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_system_gold_operation` (`tenant_id`,`operation_key`),
  KEY `idx_system_gold_target` (`tenant_id`,`target_user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台代理金币不可变流水';

INSERT INTO `system_role` (`name`,`code`,`sort`,`data_scope`,`data_scope_dept_ids`,`status`,`type`,
                           `remark`,`creator`,`updater`,`tenant_id`)
SELECT '代理账号','lottery_agent',3,1,'',0,1,'仅管理自己及直属老板账号','1','1',t.id
FROM `system_tenant` t WHERE t.deleted=b'0'
AND NOT EXISTS (SELECT 1 FROM `system_role` r WHERE r.tenant_id=t.id AND r.code='lottery_agent' AND r.deleted=b'0');

INSERT INTO `system_role_menu` (`role_id`,`menu_id`,`creator`,`updater`,`tenant_id`)
SELECT r.id,m.id,'1','1',r.tenant_id FROM `system_role` r JOIN `system_menu` m ON m.id IN (1,100,1001,1002)
WHERE r.code='lottery_agent' AND r.deleted=b'0' AND m.deleted=b'0'
AND NOT EXISTS (SELECT 1 FROM `system_role_menu` rm WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.deleted=b'0');

SET @legacy_gold_cutover = NOW();
START TRANSACTION;
UPDATE `system_users` SET `account_type`='ADMIN' WHERE `tenant_id`=1 AND `id`=1 AND `deleted`=b'0';
INSERT IGNORE INTO `system_gold_ledger`
  (`operation_key`,`operation_type`,`target_user_id`,`amount`,`target_expire_before`,
   `target_expire_after`,`operator_user_id`,`creator`,`updater`,`tenant_id`)
SELECT CONCAT('legacy:30d:1:',u.id),'LEGACY_GRANT',u.id,30,u.expire_time,
       DATE_ADD(@legacy_gold_cutover, INTERVAL 30 DAY),1,'1','1',1
FROM `system_users` u JOIN `system_user_role` ur ON ur.user_id=u.id AND ur.deleted=b'0'
JOIN `system_role` r ON r.id=ur.role_id AND r.tenant_id=1 AND r.code IN ('crm_admin','tenant_admin') AND r.deleted=b'0'
WHERE u.tenant_id=1 AND u.id<>1 AND u.account_type IS NULL AND u.deleted=b'0'
  AND NOT EXISTS (SELECT 1 FROM `system_user_role` sur JOIN `system_role` sr ON sr.id=sur.role_id
                  WHERE sur.user_id=u.id AND sur.deleted=b'0' AND sr.code='super_admin' AND sr.deleted=b'0');
UPDATE `system_users` u JOIN `system_gold_ledger` l
  ON l.tenant_id=1 AND l.operation_key=CONCAT('legacy:30d:1:',u.id)
SET u.account_type='OWNER',u.parent_agent_user_id=1,u.gold_balance=0,
    u.expire_time=l.target_expire_after
WHERE u.tenant_id=1 AND u.account_type IS NULL AND u.deleted=b'0';
COMMIT;
