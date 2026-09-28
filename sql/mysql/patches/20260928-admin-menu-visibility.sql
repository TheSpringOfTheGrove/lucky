-- 已有数据库的幂等升级；与 lucky5-business.sql 的导航可见性规则保持一致。
-- 系统管理保留用户管理、角色管理；隐藏其余系统导航及整个基础设施导航。
-- 只修改 visible，不修改 status/deleted/permission、按钮、角色授权或租户套餐。
SET NAMES utf8mb4;

DROP TEMPORARY TABLE IF EXISTS `lucky5_admin_navigation`;
CREATE TEMPORARY TABLE `lucky5_admin_navigation` (`id` bigint NOT NULL PRIMARY KEY);
INSERT IGNORE INTO `lucky5_admin_navigation`
WITH RECURSIVE `navigation` (`id`) AS (
  SELECT `id` FROM `system_menu` WHERE `id` IN (1,2) AND `deleted`=b'0'
  UNION DISTINCT
  SELECT m.`id` FROM `system_menu` m JOIN `navigation` p ON m.`parent_id`=p.`id`
  WHERE m.`deleted`=b'0'
)
SELECT `id` FROM `navigation`;

START TRANSACTION;
UPDATE `system_menu` m JOIN `lucky5_admin_navigation` n ON n.`id`=m.`id`
SET m.`visible`=IF(m.`id` IN (1,100,101),b'1',b'0')
WHERE m.`type` IN (1,2)
  AND m.`visible`<>IF(m.`id` IN (1,100,101),b'1',b'0');
COMMIT;

DROP TEMPORARY TABLE IF EXISTS `lucky5_admin_navigation`;
