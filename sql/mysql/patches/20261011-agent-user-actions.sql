-- 代理可管理直属老板：修改、删除和重置密码；角色分配继续仅限系统管理员。
SET NAMES utf8mb4;

INSERT INTO `system_role_menu` (`role_id`, `menu_id`, `creator`, `updater`, `tenant_id`)
SELECT r.id, m.id, '1', '1', r.tenant_id
FROM `system_role` r JOIN `system_menu` m ON m.id IN (1003, 1004, 1007)
WHERE r.code = 'lottery_agent' AND r.deleted = b'0' AND m.deleted = b'0'
  AND NOT EXISTS (
    SELECT 1 FROM `system_role_menu` rm
    WHERE rm.role_id = r.id AND rm.menu_id = m.id AND rm.deleted = b'0'
  );

UPDATE `system_tenant_package` SET `menu_ids` = JSON_ARRAY_APPEND(`menu_ids`, '$', 1003)
WHERE `deleted` = b'0' AND JSON_VALID(`menu_ids`)
  AND JSON_CONTAINS(`menu_ids`, '1002') AND NOT JSON_CONTAINS(`menu_ids`, '1003');
UPDATE `system_tenant_package` SET `menu_ids` = JSON_ARRAY_APPEND(`menu_ids`, '$', 1004)
WHERE `deleted` = b'0' AND JSON_VALID(`menu_ids`)
  AND JSON_CONTAINS(`menu_ids`, '1002') AND NOT JSON_CONTAINS(`menu_ids`, '1004');
UPDATE `system_tenant_package` SET `menu_ids` = JSON_ARRAY_APPEND(`menu_ids`, '$', 1007)
WHERE `deleted` = b'0' AND JSON_VALID(`menu_ids`)
  AND JSON_CONTAINS(`menu_ids`, '1002') AND NOT JSON_CONTAINS(`menu_ids`, '1007');
