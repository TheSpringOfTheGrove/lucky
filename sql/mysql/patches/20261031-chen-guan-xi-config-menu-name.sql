-- Rename the current customer's read-only information menu without changing permissions or visibility.
UPDATE `system_menu`
SET `name` = '配置信息'
WHERE `id` = 7025 AND `name` = '飞鱼蓝鲸信息' AND `deleted` = b'0';
