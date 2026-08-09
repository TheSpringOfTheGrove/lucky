DROP TABLE IF EXISTS `crm_clue`;
CREATE TABLE `crm_clue` (
                            `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号，主键自增',
                            `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '线索名称',

                            `owner_user_id` bigint NOT NULL COMMENT '负责人的用户编号',

                            `follow_up_status` bit(1) DEFAULT b'0' COMMENT '跟进状态',
                            `contact_last_time` datetime DEFAULT NULL COMMENT '最后跟进时间',
                            `contact_last_content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后跟进内容',
                            `contact_next_time` datetime DEFAULT NULL COMMENT '下次联系时间',

                            `transform_status` bit(1) DEFAULT b'0' COMMENT '转化状态',
                            `customer_id` bigint DEFAULT NULL COMMENT '客户编号',

                            `mobile` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
                            `telephone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '电话',
                            `qq` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'QQ',
                            `wechat` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '微信',
                            `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
                            `area_id` bigint DEFAULT NULL COMMENT '地区编号',
                            `detail_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '详细地址',
                            `industry_id` int DEFAULT NULL COMMENT '所属行业',
                            `level` int DEFAULT NULL COMMENT '客户等级',
                            `source` int DEFAULT NULL COMMENT '客户来源',
                            `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
                            `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                            `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                            `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                            `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                            `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                            PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 线索表';

DROP TABLE IF EXISTS `crm_customer`;
CREATE TABLE `crm_customer` (
                                `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号，主键自增',
                                `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户名称',

                                `owner_user_id` bigint DEFAULT NULL COMMENT '负责人的用户编号',
                                `owner_time` datetime NOT NULL COMMENT '成为负责人的时间',
                                `lock_status` bit(1) NOT NULL DEFAULT b'0' COMMENT '锁定状态',
                                `deal_status` bit(1) NOT NULL DEFAULT b'0' COMMENT '成交状态',

                                `follow_up_status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '跟进状态',
                                `contact_last_time` datetime DEFAULT NULL COMMENT '最后跟进时间',
                                `contact_last_content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后跟进内容',
                                `contact_next_time` datetime DEFAULT NULL COMMENT '下次联系时间',

                                `mobile` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机',
                                `telephone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '电话',
                                `qq` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'QQ',
                                `wechat` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '微信',
                                `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
                                `area_id` bigint DEFAULT NULL COMMENT '地区编号',
                                `detail_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '详细地址',
                                `industry_id` int DEFAULT NULL COMMENT '所属行业',
                                `level` int DEFAULT NULL COMMENT '客户等级',
                                `source` int DEFAULT NULL COMMENT '客户来源',
                                `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
                                `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                PRIMARY KEY (`id`) USING BTREE,
                                KEY `owner_user_id` (`owner_user_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 客户表';

DROP TABLE IF EXISTS `crm_customer_limit_config`;
CREATE TABLE `crm_customer_limit_config` (
                                             `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',

                                             `type` int NOT NULL COMMENT '规则类型 1: 拥有客户数限制，2:锁定客户数限制',

                                             `user_ids` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '规则适用人群',
                                             `dept_ids` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '规则适用部门',

                                             `max_count` int NOT NULL COMMENT '数量上限',
                                             `deal_count_enabled` tinyint DEFAULT NULL COMMENT '成交客户是否占有拥有客户数(当 type = 1 时)',
                                             `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                             `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                             `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                             `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                             `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                             `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                             PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 客户限制配置表';

DROP TABLE IF EXISTS `crm_customer_pool_config`;
CREATE TABLE `crm_customer_pool_config` (
                                            `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',

                                            `enabled` tinyint(1) NOT NULL COMMENT '是否启用客户公海',
                                            `contact_expire_days` int DEFAULT NULL COMMENT '未跟进放入公海天数',
                                            `deal_expire_days` int DEFAULT NULL COMMENT '未成交放入公海天数',

                                            `notify_enabled` tinyint(1) DEFAULT NULL COMMENT '是否开启提前提醒',
                                            `notify_days` int DEFAULT NULL COMMENT '提前提醒天数',
                                            `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                            `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                            `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                            `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                            `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                            `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                            PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 客户公海配置表';

DROP TABLE IF EXISTS `crm_contact`;
CREATE TABLE `crm_contact` (
                               `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                               `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系人名称',
                               `customer_id` bigint DEFAULT NULL COMMENT '客户编号',

                               `owner_user_id` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '负责人用户编号',

                               `contact_last_time` datetime DEFAULT NULL COMMENT '最后跟进时间',
                               `contact_last_content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后跟进内容',
                               `contact_next_time` datetime DEFAULT NULL COMMENT '下次联系时间',

                               `mobile` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
                               `telephone` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '电话',
                               `email` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '电子邮箱',
                               `qq` int DEFAULT NULL,
                               `wechat` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
                               `area_id` bigint DEFAULT NULL COMMENT '地区',
                               `detail_address` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '地址',

                               `sex` int DEFAULT NULL COMMENT '性别',
                               `master` bit(1) DEFAULT NULL COMMENT '是否关键决策人',
                               `parent_id` bigint DEFAULT NULL COMMENT '直系上属',
                               `post` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '职务',
                               `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                               `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                               `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                               `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                               `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                               PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 联系人';

DROP TABLE IF EXISTS `crm_business_status_type`;
CREATE TABLE `crm_business_status_type` (
                                            `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                            `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态组名',

                                            `dept_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '使用的部门编号',
                                            `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                            `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                            `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                            `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                            `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                            `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                            PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 商机状态组表';

DROP TABLE IF EXISTS `crm_business_status`;
CREATE TABLE `crm_business_status` (
                                       `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                       `type_id` bigint NOT NULL COMMENT '状态类型编号',
                                       `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态类型名',
                                       `percent` decimal(24,6) NOT NULL COMMENT '赢单率',
                                       `sort` int NOT NULL DEFAULT '1' COMMENT '排序',
                                       `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                       `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                       `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                       `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                       `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                       `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                       PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 商机状态表';

DROP TABLE IF EXISTS `crm_business`;
CREATE TABLE `crm_business` (
                                `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
                                `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商机名称',
                                `customer_id` bigint NOT NULL COMMENT '客户编号',

                                `owner_user_id` bigint DEFAULT NULL COMMENT '负责人的用户编号',

                                `follow_up_status` bit(1) DEFAULT b'0' COMMENT '跟进状态',
                                `contact_last_time` datetime DEFAULT NULL COMMENT '最后跟进时间',
                                `contact_next_time` datetime DEFAULT NULL COMMENT '下次联系时间',

                                `status_type_id` bigint DEFAULT NULL COMMENT '商机状态类型编号',
                                `status_id` bigint DEFAULT NULL COMMENT '商机状态编号',
                                `end_status` tinyint DEFAULT NULL COMMENT '结束状态：1-赢单 2-输单3-无效',

                                `deal_time` datetime DEFAULT NULL COMMENT '预计成交日期',
                                `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '备注',

                                `total_product_price` decimal(24,6) DEFAULT NULL COMMENT '产品总金额，单位：元',
                                `discount_percent` decimal(24,6) DEFAULT NULL COMMENT '整单折扣，百分比',
                                `total_price` decimal(24,6) DEFAULT NULL COMMENT '商机总金额，单位：元',
                                `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM 商机表';

DROP TABLE IF EXISTS `crm_business_product`;
CREATE TABLE `crm_business_product` (
                                        `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',

                                        `business_id` bigint NOT NULL COMMENT '商机编号',
                                        `product_id` bigint NOT NULL COMMENT '产品编号',

                                        `product_price` decimal(24,6) NOT NULL COMMENT '产品单价',
                                        `business_price` decimal(24,6) NOT NULL COMMENT '商机价格',
                                        `count` decimal(24,6) NOT NULL COMMENT '数量',
                                        `total_price` decimal(24,6) NOT NULL COMMENT '总计价格',
                                        `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                        `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                        `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                        `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                        `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                        `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                        PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='CRM 商机产品关联表';

DROP TABLE IF EXISTS `crm_contact_business`;
CREATE TABLE `crm_contact_business` (
                                        `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
                                        `contact_id` int DEFAULT NULL COMMENT '联系人id',
                                        `business_id` int DEFAULT NULL COMMENT '商机id',
                                        `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                        `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                        `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                        `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                        `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                        `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                        PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=34 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 联系人商机关联表';

DROP TABLE IF EXISTS `crm_contract`;
CREATE TABLE `crm_contract` (
                                `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号，主键自增',
                                `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '合同名称',
                                `no` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '合同编号',

                                `customer_id` bigint NOT NULL COMMENT '客户编号',
                                `business_id` bigint DEFAULT NULL COMMENT '商机编号',

                                `contact_last_time` datetime DEFAULT NULL COMMENT '最后跟进时间',

                                `owner_user_id` bigint DEFAULT NULL COMMENT '负责人的用户编号',

                                `process_instance_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工作流编号',
                                `audit_status` tinyint NOT NULL DEFAULT '0' COMMENT '审批状态',

                                `total_product_price` decimal(24,6) DEFAULT NULL COMMENT '产品总金额',
                                `discount_percent` decimal(24,6) DEFAULT NULL COMMENT '整单折扣',
                                `total_price` decimal(10,2) DEFAULT NULL COMMENT '合同总金额',

                                `order_date` datetime DEFAULT NULL COMMENT '下单日期',
                                `start_time` datetime DEFAULT NULL COMMENT '开始时间',
                                `end_time` datetime DEFAULT NULL COMMENT '结束时间',
                                `sign_contact_id` bigint DEFAULT NULL COMMENT '联系人编号',
                                `sign_user_id` bigint DEFAULT NULL COMMENT '公司签约人',
                                `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 合同表';

DROP TABLE IF EXISTS `crm_contract_product`;
CREATE TABLE `crm_contract_product` (
                                        `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',

                                        `contract_id` bigint NOT NULL COMMENT '合同编号',
                                        `product_id` bigint NOT NULL COMMENT '产品编号',

                                        `product_price` decimal(24,6) NOT NULL COMMENT '产品单价',
                                        `contract_price` decimal(24,6) NOT NULL COMMENT '合同价格',
                                        `count` decimal(24,6) NOT NULL COMMENT '数量',
                                        `total_price` decimal(24,6) NOT NULL COMMENT '总计价格',
                                        `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                        `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                        `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                        `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                        `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                        `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                        PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='CRM 合同产品关联表';

DROP TABLE IF EXISTS `crm_contract_config`;
CREATE TABLE `crm_contract_config` (
                                       `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
                                       `notify_enabled` tinyint(1) DEFAULT NULL COMMENT '是否开启提前提醒',
                                       `notify_days` int DEFAULT NULL COMMENT '提前提醒天数',
                                       `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                       `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                       `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                       `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                       `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                       `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                       PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 合同配置表';


DROP TABLE IF EXISTS `crm_receivable`;
CREATE TABLE `crm_receivable` (
                                  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
                                  `no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '回款编号',

                                  `customer_id` bigint NOT NULL COMMENT '客户ID',
                                  `contract_id` bigint NOT NULL COMMENT '合同ID',

                                  `plan_id` bigint DEFAULT NULL COMMENT '回款计划ID',

                                  `owner_user_id` bigint DEFAULT NULL COMMENT '负责人的用户编号',

                                  `audit_status` tinyint NOT NULL COMMENT '审批状态',
                                  `process_instance_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工作流编号',

                                  `price` decimal(24,6) NOT NULL COMMENT '回款金额',

                                  `return_time` datetime DEFAULT NULL COMMENT '回款日期',
                                  `return_type` int DEFAULT NULL COMMENT '回款方式',
                                  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
                                  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=29 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 回款管理';

DROP TABLE IF EXISTS `crm_receivable_plan`;
CREATE TABLE `crm_receivable_plan` (
                                       `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',

                                       `period` bigint NOT NULL COMMENT '期数',

                                       `customer_id` bigint NOT NULL COMMENT '客户编号',
                                       `contract_id` bigint NOT NULL COMMENT '合同编号',

                                       `owner_user_id` bigint DEFAULT NULL COMMENT '负责人编号',

                                       `receivable_id` bigint DEFAULT NULL COMMENT '回款编号',

                                       `return_time` datetime DEFAULT NULL COMMENT '计划回款日期',
                                       `return_type` tinyint DEFAULT NULL COMMENT '计划还款方式',
                                       `price` decimal(24,6) NOT NULL COMMENT '计划回款金额',
                                       `remind_days` bigint DEFAULT NULL COMMENT '提前几天提醒',
                                       `remind_time` datetime DEFAULT NULL COMMENT '提醒日期',
                                       `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
                                       `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                       `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                       `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                       `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                       `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                       `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                       PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 回款计划';

DROP TABLE IF EXISTS `crm_product_category`;
CREATE TABLE `crm_product_category` (
                                        `id` bigint NOT NULL AUTO_INCREMENT COMMENT '分类编号',
                                        `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分类名称',

                                        `parent_id` bigint NOT NULL COMMENT '父级编号',
                                        `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                        `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                        `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                        `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                        `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                        `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                        PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 产品分类表';

DROP TABLE IF EXISTS `crm_product`;
CREATE TABLE `crm_product` (
                               `id` bigint NOT NULL AUTO_INCREMENT COMMENT '产品编号',
                               `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '产品名称',

                               `category_id` bigint NOT NULL COMMENT '产品分类编号',
                               `unit` tinyint DEFAULT NULL COMMENT '单位',
                               `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态',

                               `owner_user_id` bigint NOT NULL COMMENT '负责人的用户编号',
                               `no` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '产品编码',
                               `price` decimal(24,6) DEFAULT '0.000000' COMMENT '价格，单位：元',
                               `description` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '产品描述',
                               `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                               `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                               `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                               `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                               `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                               PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 产品表';

DROP TABLE IF EXISTS `crm_permission`;
CREATE TABLE `crm_permission` (
                                  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',

                                  `user_id` bigint NOT NULL DEFAULT '0' COMMENT '用户编号',

                                  `biz_type` tinyint NOT NULL DEFAULT '100' COMMENT '数据类型',
                                  `biz_id` bigint NOT NULL DEFAULT '0' COMMENT '数据编号',

                                  `level` int NOT NULL DEFAULT '0' COMMENT '会员等级',
                                  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=86 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM 数据权限表';

DROP TABLE IF EXISTS `crm_follow_up_record`;
CREATE TABLE `crm_follow_up_record` (
                                        `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',

                                        `biz_type` int DEFAULT NULL COMMENT '数据类型',
                                        `biz_id` bigint DEFAULT NULL COMMENT '数据编号',

                                        `next_time` datetime DEFAULT NULL COMMENT '下次联系时间',
                                        `type` int DEFAULT NULL COMMENT '跟进类型',
                                        `content` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '跟进内容',
                                        `pic_urls` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '图片',
                                        `file_urls` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '附件',

                                        `business_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '关联的商机编号数组',
                                        `contact_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '关联的联系人编号数组',
                                        `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
                                        `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                        `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
                                        `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                        `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
                                        `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
                                        PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='CRM 跟进记录';




