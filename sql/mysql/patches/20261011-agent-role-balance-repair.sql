-- 修复历史上仅修改角色、未同步账号类型的代理：把老板剩余天数一次性换算为可分配金币。
-- 重复执行不会再次发币；原期限与换算余额保存在不可变金币流水中。
SET NAMES utf8mb4;
SET @agent_role_cutover = NOW();
START TRANSACTION;

INSERT INTO `system_gold_ledger`
  (`operation_key`, `operation_type`, `target_user_id`, `amount`,
   `agent_balance_before`, `agent_balance_after`, `target_expire_before`, `target_expire_after`,
   `operator_user_id`, `creator`, `updater`, `tenant_id`)
SELECT CONCAT('agent-role-conversion:', u.tenant_id, ':', u.id), 'AGENT_ROLE_MIGRATION', u.id,
       CEIL(GREATEST(TIMESTAMPDIFF(SECOND, @agent_role_cutover, u.expire_time), 0) / 86400),
       COALESCE(u.gold_balance, 0),
       COALESCE(u.gold_balance, 0) + CEIL(GREATEST(TIMESTAMPDIFF(SECOND, @agent_role_cutover, u.expire_time), 0) / 86400),
       u.expire_time, '2099-12-31 23:59:59', COALESCE(u.parent_agent_user_id, u.id),
       'migration', 'migration', u.tenant_id
FROM `system_users` u
WHERE u.deleted = b'0' AND u.account_type = 'OWNER'
  AND EXISTS (
    SELECT 1 FROM `system_user_role` ur JOIN `system_role` r ON r.id = ur.role_id
    WHERE ur.user_id = u.id AND ur.deleted = b'0' AND r.deleted = b'0'
      AND r.tenant_id = u.tenant_id AND r.code = 'lottery_agent'
  )
  AND NOT EXISTS (
    SELECT 1 FROM `system_gold_ledger` l
    WHERE l.tenant_id = u.tenant_id
      AND l.operation_key = CONCAT('agent-role-conversion:', u.tenant_id, ':', u.id)
  );

UPDATE `system_users` u JOIN `system_gold_ledger` l
  ON l.tenant_id = u.tenant_id
 AND l.operation_key = CONCAT('agent-role-conversion:', u.tenant_id, ':', u.id)
SET u.account_type = 'AGENT', u.gold_balance = l.agent_balance_after,
    u.parent_agent_user_id = NULL, u.expire_time = l.target_expire_after
WHERE u.deleted = b'0' AND u.account_type = 'OWNER' AND l.operation_type = 'AGENT_ROLE_MIGRATION';

COMMIT;
