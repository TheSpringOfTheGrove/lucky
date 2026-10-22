-- Finalize verified historical draw snapshots which never opened and have no
-- orders of any status (including soft-deleted orders). This does not settle
-- ordinary periods, change balances, or touch market delivery records.
START TRANSACTION;

UPDATE lucky5_issue AS i
JOIN lucky5_draw AS d
  ON d.tenant_id = i.tenant_id
 AND d.user_id = i.user_id
 AND d.period = i.period
 AND d.deleted = b'0'
SET i.status = 'SETTLED',
    i.settled_at = COALESCE(i.settled_at, NOW()),
    i.error = ''
WHERE i.deleted = b'0'
  AND i.status = 'DRAWN'
  AND i.opened_at IS NULL
  AND i.draw_confirmations >= 2
  AND i.result REGEXP '^[0-9]{5}$'
  AND i.result <> '00000'
  AND d.status = '待结算'
  AND REPLACE(d.result, ',', '') = i.result
  AND NOT EXISTS (
    SELECT 1 FROM lucky5_order AS o
    WHERE o.tenant_id = i.tenant_id
      AND o.user_id = i.user_id
      AND o.period = i.period
  );

UPDATE lucky5_draw AS d
JOIN lucky5_issue AS i
  ON i.tenant_id = d.tenant_id
 AND i.user_id = d.user_id
 AND i.period = d.period
 AND i.deleted = b'0'
SET d.status = '已结算',
    d.settled_at = COALESCE(d.settled_at, i.settled_at, NOW())
WHERE d.deleted = b'0'
  AND d.status = '待结算'
  AND i.status = 'SETTLED'
  AND i.opened_at IS NULL
  AND i.draw_confirmations >= 2
  AND i.result REGEXP '^[0-9]{5}$'
  AND i.result <> '00000'
  AND REPLACE(d.result, ',', '') = i.result
  AND NOT EXISTS (
    SELECT 1 FROM lucky5_order AS o
    WHERE o.tenant_id = i.tenant_id
      AND o.user_id = i.user_id
      AND o.period = i.period
  );

COMMIT;
