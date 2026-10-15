-- Existing databases: install the real-player follow tables without changing historical orders.
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lucky5_order' AND column_name='follow_id')=0,
  'ALTER TABLE `lucky5_order` ADD COLUMN `follow_id` varchar(64) NULL AFTER `order_type`', 'SELECT 1');
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lucky5_order' AND index_name='idx_lucky5_order_follow')=0,
  'ALTER TABLE `lucky5_order` ADD KEY `idx_lucky5_order_follow` (`tenant_id`,`user_id`,`follow_id`,`period`)', 'SELECT 1');
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;

CREATE TABLE IF NOT EXISTS `lucky5_player_follow` (
  `id` varchar(64) NOT NULL, `user_id` bigint NOT NULL, `member_id` varchar(64) NOT NULL,
  `channel` varchar(50) NOT NULL, `source_order_id` varchar(64) NOT NULL,
  `source_period` varchar(40) NOT NULL, `content` varchar(2000) NOT NULL,
  `enabled` bit(1) NOT NULL DEFAULT b'1', `stopped_at` datetime NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_lucky5_player_follow_active` (`tenant_id`,`user_id`,`enabled`,`member_id`,`channel`),
  KEY `idx_lucky5_player_follow_source` (`tenant_id`,`user_id`,`member_id`,`channel`,`source_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 玩家固定指令跟单';

CREATE TABLE IF NOT EXISTS `lucky5_player_follow_execution` (
  `id` varchar(64) NOT NULL, `user_id` bigint NOT NULL, `follow_id` varchar(64) NOT NULL,
  `member_id` varchar(64) NOT NULL, `period` varchar(40) NOT NULL,
  `status` varchar(30) NOT NULL, `order_id` varchar(64) NULL,
  `error` varchar(1000) NOT NULL DEFAULT '', `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` datetime NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lucky5_player_follow_period` (`tenant_id`,`user_id`,`follow_id`,`period`,`deleted`),
  KEY `idx_lucky5_player_follow_retry` (`status`,`next_attempt_at`,`create_time`),
  KEY `idx_lucky5_player_follow_owner` (`tenant_id`,`user_id`,`member_id`,`period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 玩家跟单每期执行';

SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lucky5_player_follow_execution' AND column_name='next_attempt_at')=0,
  'ALTER TABLE `lucky5_player_follow_execution` ADD COLUMN `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER `error`', 'SELECT 1');
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;
SET @lucky5_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lucky5_player_follow_execution' AND index_name='idx_lucky5_player_follow_retry')=0,
  'ALTER TABLE `lucky5_player_follow_execution` ADD KEY `idx_lucky5_player_follow_retry` (`status`,`next_attempt_at`,`create_time`)', 'SELECT 1');
PREPARE lucky5_stmt FROM @lucky5_ddl; EXECUTE lucky5_stmt; DEALLOCATE PREPARE lucky5_stmt;
