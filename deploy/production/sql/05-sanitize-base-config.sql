-- Production starts in boss mode with all room switches closed.
-- Market credentials are intentionally not copied from the local environment,
-- because the production credential key is generated independently.
UPDATE lucky5_config
SET upstream_url = '',
    upstream_account = '',
    market_password_encrypted = '',
    service_url = '',
    chat_url = '',
    boss_mode = b'1',
    updater = 'production-deploy',
    update_time = CURRENT_TIMESTAMP
WHERE user_id IN (1, 142, 144);

UPDATE lucky5_system_state
SET room_open = b'0',
    online = 0,
    updater = 'production-deploy',
    update_time = CURRENT_TIMESTAMP;

-- Keep the local account model: admin is the only super administrator;
-- test01 and the disabled compatibility account are independent owners.
DELETE ur
FROM system_user_role ur
JOIN system_users u ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id
WHERE u.tenant_id = 1
  AND u.id IN (142, 144);

INSERT INTO system_user_role
    (user_id, role_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT u.id,
       r.id,
       'production-deploy',
       CURRENT_TIMESTAMP,
       'production-deploy',
       CURRENT_TIMESTAMP,
       b'0',
       u.tenant_id
FROM system_users u
JOIN system_role r ON r.tenant_id = u.tenant_id
    AND r.code = 'crm_admin'
    AND r.deleted = b'0'
WHERE u.tenant_id = 1
  AND u.id IN (142, 144)
  AND u.deleted = b'0';
