SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `lucky5_config` (
  `id` bigint NOT NULL AUTO_INCREMENT, `room_name` varchar(100) NOT NULL DEFAULT '幸运5',
  `close_time` varchar(20) NOT NULL DEFAULT '', `settle_delay` int NOT NULL DEFAULT 0,
  `min_deposit` decimal(18,2) NOT NULL DEFAULT 0, `max_deposit` decimal(18,2) NOT NULL DEFAULT 0,
  `announcement` varchar(1000) NOT NULL DEFAULT '', `service_url` varchar(500) NOT NULL DEFAULT '',
  `chat_url` varchar(500) NOT NULL DEFAULT '', `upstream_url` varchar(500) NOT NULL DEFAULT '',
  `upstream_account` varchar(100) NOT NULL DEFAULT '', `market_password_encrypted` varchar(1000) NOT NULL DEFAULT '',
  `alert_value` decimal(18,2) NOT NULL DEFAULT 0, `boss_mode` bit(1) NOT NULL DEFAULT b'0',
  `play_type` tinyint NOT NULL DEFAULT 2, `use_proxy` bit(1) NOT NULL DEFAULT b'1',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_config_tenant` (`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 配置';

CREATE TABLE IF NOT EXISTS `lucky5_system_state` (
  `id` bigint NOT NULL AUTO_INCREMENT, `operator_username` varchar(100) NOT NULL DEFAULT '',
  `expire_at` datetime NULL, `room_open` bit(1) NOT NULL DEFAULT b'0', `online` int NOT NULL DEFAULT 0,
  `chima_cleared_at` datetime NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_state_tenant` (`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 运行状态';

CREATE TABLE IF NOT EXISTS `lucky5_market_connection` (
  `id` bigint NOT NULL AUTO_INCREMENT, `status` varchar(30) NOT NULL DEFAULT '未配置',
  `line_url` varchar(500) NOT NULL DEFAULT '', `display_account` varchar(100) NOT NULL DEFAULT '',
  `balance` decimal(18,2) NULL, `error` varchar(1000) NOT NULL DEFAULT '',
  `last_login_at` datetime NULL, `last_sync_at` datetime NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_market_tenant` (`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 盘口连接';

CREATE TABLE IF NOT EXISTS `lucky5_link_config` (
  `id` bigint NOT NULL AUTO_INCREMENT, `device_id` varchar(100) NOT NULL DEFAULT '',
  `dealer_url` varchar(500) NOT NULL DEFAULT '', `room_url` varchar(500) NOT NULL DEFAULT '',
  `short_url` varchar(500) NOT NULL DEFAULT '', `qr_mode` varchar(50) NOT NULL DEFAULT '',
  `short_url_mode` tinyint NOT NULL DEFAULT 2,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_link_tenant` (`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 链接配置';

CREATE TABLE IF NOT EXISTS `lucky5_chima_config` (
  `id` bigint NOT NULL AUTO_INCREMENT, `si_zi_xian` decimal(18,2) NOT NULL DEFAULT 0,
  `san_zi_xian` decimal(18,2) NOT NULL DEFAULT 0, `er_zi_xian` decimal(18,2) NOT NULL DEFAULT 0,
  `dan_zi_xian` decimal(18,2) NOT NULL DEFAULT 0, `si_ding_wei` decimal(18,2) NOT NULL DEFAULT 0,
  `san_ding_wei` decimal(18,2) NOT NULL DEFAULT 0, `er_ding_wei` decimal(18,2) NOT NULL DEFAULT 0,
  `yi_ding_wei` decimal(18,2) NOT NULL DEFAULT 0, `yin_kui_max` decimal(18,2) NOT NULL DEFAULT 0,
  `yin_kui_min` decimal(18,2) NOT NULL DEFAULT 0,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_chima_config_tenant` (`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 吃码配置';

CREATE TABLE IF NOT EXISTS `lucky5_switch_setting` (
  `id` bigint NOT NULL AUTO_INCREMENT, `setting_key` varchar(80) NOT NULL, `label` varchar(100) NOT NULL,
  `enabled` bit(1) NOT NULL DEFAULT b'0',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_switch` (`tenant_id`,`setting_key`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 开关';

CREATE TABLE IF NOT EXISTS `lucky5_integration` (
  `id` bigint NOT NULL AUTO_INCREMENT, `integration_key` varchar(50) NOT NULL, `name` varchar(100) NOT NULL,
  `account` varchar(100) NOT NULL DEFAULT '', `group_name` varchar(100) NOT NULL DEFAULT '',
  `status` varchar(30) NOT NULL DEFAULT '未登录',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_integration` (`tenant_id`,`integration_key`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 第三方接入';

CREATE TABLE IF NOT EXISTS `lucky5_odd` (
  `id` bigint NOT NULL AUTO_INCREMENT, `code` varchar(64) NOT NULL, `play` varchar(100) NOT NULL,
  `item` varchar(100) NOT NULL, `rate` decimal(12,4) NOT NULL DEFAULT 0,
  `secondary_rate` decimal(12,4) NULL, `min_limit` decimal(18,2) NULL, `max_limit` decimal(18,2) NULL,
  `status` varchar(20) NOT NULL DEFAULT '启用',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_odd` (`tenant_id`,`code`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 赔率';

CREATE TABLE IF NOT EXISTS `lucky5_member` (
  `id` varchar(64) NOT NULL, `name` varchar(100) NOT NULL, `balance` decimal(18,2) NOT NULL DEFAULT 0,
  `status` varchar(20) NOT NULL DEFAULT '离线', `partner` varchar(100) NOT NULL DEFAULT '无',
  `normal_rate` decimal(8,4) NOT NULL DEFAULT 0, `lhh_rate` decimal(8,4) NOT NULL DEFAULT 0,
  `tag` varchar(50) NOT NULL DEFAULT '普通', `external_nickname` varchar(100) NOT NULL DEFAULT '',
  `total_bet` decimal(18,2) NOT NULL DEFAULT 0, `profit_loss` decimal(18,2) NOT NULL DEFAULT 0,
  `auto_proxy` bit(1) NOT NULL DEFAULT b'0', `eat_enabled` bit(1) NOT NULL DEFAULT b'0',
  `searchable` bit(1) NOT NULL DEFAULT b'1', `open_id` varchar(100) NULL, `fingerprint` varchar(200) NOT NULL DEFAULT '',
  `private_chat` bit(1) NOT NULL DEFAULT b'0', `web_only` bit(1) NOT NULL DEFAULT b'0',
  `blue_whale_password` varchar(200) NOT NULL DEFAULT '', `avatar` int NOT NULL DEFAULT 1,
  `flow_cleared_at` datetime NULL, `version` int NOT NULL DEFAULT 0,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_member_name` (`tenant_id`,`name`,`deleted`),
  UNIQUE KEY `uk_lucky5_member_open_id` (`tenant_id`,`open_id`), KEY `idx_lucky5_member_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 会员';

CREATE TABLE IF NOT EXISTS `lucky5_amount_record` (
  `id` varchar(64) NOT NULL, `member_id` varchar(64) NOT NULL, `member_name` varchar(100) NOT NULL,
  `type` varchar(20) NOT NULL, `amount` decimal(18,2) NOT NULL, `status` varchar(20) NOT NULL,
  `remark` varchar(500) NOT NULL DEFAULT '', `audited_at` datetime NULL, `audited_by` varchar(100) NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_lucky5_amount_status` (`tenant_id`,`status`,`create_time`),
  KEY `idx_lucky5_amount_member` (`tenant_id`,`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 上下分记录';

CREATE TABLE IF NOT EXISTS `lucky5_order` (
  `id` varchar(64) NOT NULL, `member_id` varchar(64) NOT NULL, `member_name` varchar(100) NOT NULL,
  `period` varchar(40) NOT NULL, `content` varchar(2000) NOT NULL, `amount` decimal(18,2) NOT NULL,
  `win` decimal(18,2) NOT NULL DEFAULT 0, `status` varchar(30) NOT NULL, `source` varchar(30) NOT NULL DEFAULT '网页群',
  `delivery_mode` varchar(30) NOT NULL DEFAULT 'LOCAL_ONLY', `market_status` varchar(30) NOT NULL DEFAULT 'NOT_REQUIRED',
  `market_order_id` varchar(100) NOT NULL DEFAULT '', `market_error` varchar(1000) NOT NULL DEFAULT '',
  `market_attempts` int NOT NULL DEFAULT 0, `period_sequence` int NOT NULL DEFAULT 0, `version` int NOT NULL DEFAULT 0,
  `settled_at` datetime NULL, `cancelled_at` datetime NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_order_sequence` (`tenant_id`,`period`,`period_sequence`),
  KEY `idx_lucky5_order_period` (`tenant_id`,`period`,`status`), KEY `idx_lucky5_order_member` (`tenant_id`,`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 订单';

CREATE TABLE IF NOT EXISTS `lucky5_bet_item` (
  `id` varchar(64) NOT NULL, `order_id` varchar(64) NOT NULL, `play` varchar(100) NOT NULL,
  `selection` varchar(100) NOT NULL, `amount` decimal(18,2) NOT NULL, `odds` decimal(12,4) NOT NULL,
  `won` bit(1) NULL, `payout` decimal(18,2) NOT NULL DEFAULT 0,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_lucky5_bet_order` (`tenant_id`,`order_id`),
  KEY `idx_lucky5_bet_selection` (`tenant_id`,`play`,`selection`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 拆分注项';

CREATE TABLE IF NOT EXISTS `lucky5_draw` (
  `id` bigint NOT NULL AUTO_INCREMENT, `period` varchar(40) NOT NULL, `result` varchar(100) NOT NULL,
  `big_small` varchar(20) NOT NULL DEFAULT '', `odd_even` varchar(20) NOT NULL DEFAULT '',
  `dragon_tiger` varchar(20) NOT NULL DEFAULT '', `status` varchar(30) NOT NULL DEFAULT '已开奖', `settled_at` datetime NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_draw_period` (`tenant_id`,`period`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 开奖记录';

CREATE TABLE IF NOT EXISTS `lucky5_issue` (
  `id` bigint NOT NULL AUTO_INCREMENT, `period` varchar(40) NOT NULL, `status` varchar(30) NOT NULL,
  `market_status` int NULL, `remaining_seconds` int NOT NULL DEFAULT 0, `server_time` datetime NULL,
  `next_period` varchar(40) NOT NULL DEFAULT '', `opened_at` datetime NULL, `closed_at` datetime NULL,
  `draw_time` datetime NULL, `draw_updated_at` datetime NULL, `result` varchar(100) NOT NULL DEFAULT '',
  `source` varchar(50) NOT NULL DEFAULT '盘口', `raw_snapshot` json NULL, `error` varchar(1000) NOT NULL DEFAULT '',
  `settlement_started_at` datetime NULL, `settled_at` datetime NULL, `order_sequence` int NOT NULL DEFAULT 0,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_issue_period` (`tenant_id`,`period`,`deleted`),
  KEY `idx_lucky5_issue_status` (`tenant_id`,`status`,`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 期号';

CREATE TABLE IF NOT EXISTS `lucky5_issue_transition` (
  `id` bigint NOT NULL AUTO_INCREMENT, `legacy_id` bigint NULL, `period` varchar(40) NOT NULL,
  `from_status` varchar(30) NOT NULL, `to_status` varchar(30) NOT NULL, `source` varchar(50) NOT NULL,
  `detail` json NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_issue_transition_legacy` (`tenant_id`,`legacy_id`),
  KEY `idx_lucky5_issue_transition` (`tenant_id`,`period`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 期号流转';

CREATE TABLE IF NOT EXISTS `lucky5_preset_order` (
  `id` varchar(64) NOT NULL, `member` varchar(100) NOT NULL DEFAULT '', `content` varchar(2000) NOT NULL,
  `enabled` bit(1) NOT NULL DEFAULT b'1',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_lucky5_preset_enabled` (`tenant_id`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 预设订单';

CREATE TABLE IF NOT EXISTS `lucky5_quick_command` (
  `id` varchar(64) NOT NULL, `label` varchar(100) NOT NULL, `content` varchar(1000) NOT NULL,
  `sort` int NOT NULL DEFAULT 0, `enabled` bit(1) NOT NULL DEFAULT b'1',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_lucky5_quick_sort` (`tenant_id`,`enabled`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 快捷指令';

CREATE TABLE IF NOT EXISTS `lucky5_follow_order` (
  `id` varchar(64) NOT NULL, `source` varchar(100) NOT NULL, `target` varchar(100) NOT NULL,
  `ratio` decimal(8,4) NOT NULL, `enabled` bit(1) NOT NULL DEFAULT b'1',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_lucky5_follow_enabled` (`tenant_id`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 跟单';

CREATE TABLE IF NOT EXISTS `lucky5_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT, `legacy_id` bigint NULL, `legacy_user_id` varchar(64) NULL,
  `operator` varchar(100) NOT NULL, `member` varchar(100) NOT NULL DEFAULT '', `action` varchar(1000) NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_operation_legacy` (`tenant_id`,`legacy_id`),
  KEY `idx_lucky5_operation_time` (`tenant_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 会员操作日志';

CREATE TABLE IF NOT EXISTS `lucky5_message` (
  `id` bigint NOT NULL AUTO_INCREMENT, `legacy_id` bigint NULL, `channel` varchar(50) NOT NULL,
  `member` varchar(100) NOT NULL DEFAULT '', `period` varchar(40) NOT NULL DEFAULT '', `content` varchar(2000) NOT NULL,
  `status` varchar(30) NOT NULL, `order_id` varchar(64) NULL, `external_id` varchar(100) NULL,
  `error` varchar(1000) NOT NULL DEFAULT '', `command_type` varchar(50) NOT NULL DEFAULT '', `reply` varchar(2000) NOT NULL DEFAULT '',
  `processed_at` datetime NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_message_legacy` (`tenant_id`,`legacy_id`),
  UNIQUE KEY `uk_lucky5_message_external` (`tenant_id`,`external_id`), KEY `idx_lucky5_message_time` (`tenant_id`,`channel`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 消息';

CREATE TABLE IF NOT EXISTS `lucky5_rebate_record` (
  `id` varchar(64) NOT NULL, `member_id` varchar(64) NOT NULL, `normal_bet` decimal(18,2) NOT NULL DEFAULT 0,
  `dragon_bet` decimal(18,2) NOT NULL DEFAULT 0, `normal_amount` decimal(18,2) NOT NULL DEFAULT 0,
  `dragon_amount` decimal(18,2) NOT NULL DEFAULT 0, `total_amount` decimal(18,2) NOT NULL DEFAULT 0,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_lucky5_rebate_member` (`tenant_id`,`member_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 返水记录';

CREATE TABLE IF NOT EXISTS `lucky5_chima_record` (
  `id` varchar(64) NOT NULL, `member_id` varchar(64) NOT NULL, `fake_amount` decimal(18,2) NOT NULL DEFAULT 0,
  `total_win` decimal(18,2) NOT NULL DEFAULT 0,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_lucky5_chima_member` (`tenant_id`,`member_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Lucky5 吃码记录';

INSERT IGNORE INTO `lucky5_config` (`tenant_id`,`creator`,`updater`) VALUES (1,'1','1');
INSERT IGNORE INTO `lucky5_system_state` (`tenant_id`,`operator_username`,`expire_at`,`creator`,`updater`) VALUES (1,'admin','2099-12-31 23:59:59','1','1');
INSERT IGNORE INTO `lucky5_market_connection` (`tenant_id`,`creator`,`updater`) VALUES (1,'1','1');
INSERT IGNORE INTO `lucky5_link_config` (`tenant_id`,`creator`,`updater`) VALUES (1,'1','1');
INSERT IGNORE INTO `lucky5_chima_config` (`tenant_id`,`creator`,`updater`) VALUES (1,'1','1');

INSERT IGNORE INTO `lucky5_switch_setting` (`tenant_id`,`setting_key`,`label`,`enabled`) VALUES
(1,'openCancel','开启退码',b'1'),(1,'groupImage','群发图',b'0'),
(1,'privateImage','私发图',b'0'),(1,'privateMode','开启私聊',b'0'),(1,'pullEnable','网页群',b'1'),
(1,'dailyClear','每天自动清理流水',b'0'),(1,'wangkaEnable','网咔模式',b'0'),(1,'delayOrder','延迟跟单',b'0'),
(1,'enableFingerCheck','校验指纹',b'0'),(1,'syncEnable','同步网盘',b'0'),
(1,'dragonTigerSeparateRebate','龙虎分开返水',b'0'),(1,'urlEncode','网址加密',b'0'),
(1,'delayOpen','延迟开',b'0'),(1,'linkToCode','拉发二维码',b'0'),(1,'prizeCard','刮刮卡',b'0'),
(1,'imageBold','图加粗',b'0'),(1,'autoDiscount','关盘后自动返水',b'0');

INSERT IGNORE INTO `lucky5_integration` (`tenant_id`,`integration_key`,`name`) VALUES
(1,'blueWhale','蓝鲸'),(1,'fish','飞鱼'),(1,'wechat','微信');

INSERT IGNORE INTO `lucky5_odd` (`tenant_id`,`code`,`play`,`item`,`rate`,`status`) VALUES
(1,'regex4x','四字现','',360,'启用'),(1,'regex3x','三字现','',45,'启用'),
(1,'regex2x','二字现','',9,'启用'),(1,'regex4d','四定位','',9600,'启用'),
(1,'regex4d4','四条','',7000,'启用'),(1,'regex3d','三定位','',960,'启用'),
(1,'regex2d','二定位','',96,'启用'),(1,'regex1d','一定位','',9,'启用'),
(1,'regexlh','龙虎','',0,'启用'),(1,'regexh','和','',0,'启用');

INSERT INTO `system_menu` (`id`,`name`,`permission`,`type`,`sort`,`parent_id`,`path`,`icon`,`component`,`component_name`,`status`,`visible`,`keep_alive`,`always_show`,`creator`,`updater`,`deleted`) VALUES
(7000,'首页','lottery:dashboard:query',2,10,0,'/lucky5/dashboard','ep:data-board','lottery/dashboard/index','LotteryDashboard',0,b'0',b'1',b'1','1','1',b'0'),
(7010,'配置管理','lottery:config:manage',2,20,0,'/lucky5/config','ep:setting','lottery/config/index','LotteryConfig',0,b'1',b'1',b'1','1','1',b'0'),
(7020,'赔率设置','lottery:odds:manage',2,30,0,'/lucky5/odds','ep:setting','lottery/odds/index','LotteryOdds',0,b'1',b'1',b'1','1','1',b'0'),
(7030,'链接配置','lottery:link:manage',2,40,0,'/lucky5/links','ep:link','lottery/linkConfig/index','LotteryLinkConfig',0,b'1',b'1',b'1','1','1',b'0'),
(7040,'预设订单管理','lottery:preset:manage',2,50,0,'/lucky5/presets','ep:document-add','lottery/presetOrder/index','LotteryPresetOrder',0,b'1',b'1',b'1','1','1',b'0'),
(7050,'跟单列表','lottery:follow:manage',2,60,0,'/lucky5/follows','ep:copy-document','lottery/followOrder/index','LotteryFollowOrder',0,b'1',b'1',b'1','1','1',b'0'),
(7060,'会员管理','lottery:member:manage',2,70,0,'/lucky5/members','ep:user-filled','lottery/member/index','LotteryMember',0,b'1',b'1',b'1','1','1',b'0'),
(7070,'会员操作管理','lottery:operator:query',2,80,0,'/lucky5/operators','ep:tickets','lottery/operator/index','LotteryOperator',0,b'1',b'1',b'1','1','1',b'0'),
(7080,'上下分审核','lottery:amount:manage',2,90,0,'/lucky5/amount-records','ep:wallet','lottery/amountRecord/index','LotteryAmountRecord',0,b'1',b'1',b'1','1','1',b'0'),
(7090,'订单查询','lottery:order:manage',2,100,0,'/lucky5/orders','ep:shopping-cart','lottery/orderInfo/index','LotteryOrderInfo',0,b'1',b'1',b'1','1','1',b'0'),
(7100,'历史记录','lottery:history:query',2,110,0,'/lucky5/history','ep:clock','lottery/orderHistory/index','LotteryOrderHistory',0,b'1',b'1',b'1','1','1',b'0'),
(7110,'开奖历史记录','lottery:draw:manage',2,120,0,'/lucky5/draws','ep:calendar','lottery/drawHistory/index','LotteryDrawHistory',0,b'1',b'1',b'1','1','1',b'0'),
(7120,'返水管理','lottery:rebate:manage',2,130,0,'/lucky5/rebates','ep:refresh-left','lottery/memberDiscount/index','LotteryMemberDiscount',0,b'1',b'1',b'1','1','1',b'0'),
(7130,'吃码额度设定','lottery:chima-config:manage',2,140,0,'/lucky5/chima-config','ep:setting','lottery/chimaConfig/index','LotteryChimaConfig',0,b'1',b'1',b'1','1','1',b'0'),
(7140,'吃码盈亏','lottery:chima-record:manage',2,150,0,'/lucky5/chima-records','ep:money','lottery/chimaRecord/index','LotteryChimaRecord',0,b'1',b'1',b'1','1','1',b'0'),
(7150,'消息记录','lottery:message:manage',2,160,0,'/lucky5/messages','ep:chat-dot-round','lottery/messages/index','LotteryMessages',0,b'1',b'1',b'1','1','1',b'0'),
(7190,'快捷指令','lottery:quick-command:manage',2,890,0,'/lucky5/quick-command','ep:promotion','lottery/quickCommand/index','LotteryQuickCommand',0,b'1',b'1',b'1','1','1',b'0')
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`),`permission`=VALUES(`permission`),`type`=VALUES(`type`),`sort`=VALUES(`sort`),
`parent_id`=VALUES(`parent_id`),`path`=VALUES(`path`),`icon`=VALUES(`icon`),`component`=VALUES(`component`),
`component_name`=VALUES(`component_name`),`status`=0,`visible`=VALUES(`visible`),`deleted`=b'0';

UPDATE `system_menu` SET `sort`=900 WHERE `id`=1;
UPDATE `system_menu` SET `sort`=910 WHERE `id`=2;

DROP TEMPORARY TABLE IF EXISTS `lucky5_retained_menu`;
CREATE TEMPORARY TABLE `lucky5_retained_menu` (`id` bigint NOT NULL PRIMARY KEY);
INSERT IGNORE INTO `lucky5_retained_menu`
WITH RECURSIVE `retained` (`id`) AS (
  SELECT `id` FROM `system_menu` WHERE `id` IN (1,2,7000,7010,7020,7030,7040,7050,7060,7070,7080,7090,7100,7110,7120,7130,7140,7150,7190)
  UNION DISTINCT
  SELECT m.`id` FROM `system_menu` m JOIN `retained` p ON m.`parent_id`=p.`id`
)
SELECT `id` FROM `retained`;

UPDATE `system_menu` SET `deleted`=b'1' WHERE `id` NOT IN (SELECT `id` FROM `lucky5_retained_menu`);
UPDATE `system_menu` SET `deleted`=b'0' WHERE `id` IN (SELECT `id` FROM `lucky5_retained_menu`);
UPDATE `system_role_menu` rm LEFT JOIN `system_menu` m ON m.id=rm.menu_id SET rm.deleted=b'1' WHERE m.id IS NULL OR m.deleted=b'1';

INSERT INTO `system_role_menu` (`role_id`,`menu_id`,`creator`,`updater`,`tenant_id`)
SELECT r.id,m.id,'1','1',r.tenant_id FROM system_role r JOIN system_menu m ON m.id BETWEEN 7000 AND 7190
WHERE r.deleted=b'0' AND r.code IN ('super_admin','tenant_admin')
AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.deleted=b'0');

SET SESSION group_concat_max_len=1000000;
UPDATE `system_tenant_package` SET `menu_ids`=(SELECT CONCAT('[',GROUP_CONCAT(id ORDER BY id),']') FROM system_menu WHERE deleted=b'0')
WHERE `id`=111 AND `deleted`=b'0';
UPDATE `system_tenant_package` SET `menu_ids`='[]' WHERE `id`=113 AND `deleted`=b'0';
DROP TEMPORARY TABLE IF EXISTS `lucky5_retained_menu`;
