-- Reconcile the display row only when the owner-scoped issue is already
-- settled with a matching, twice-confirmed result and has no orders at all.
-- Orders, balances, ledgers and issue settlement state are not modified here.
START TRANSACTION;

UPDATE lucky5_draw AS d
JOIN lucky5_issue AS i
  ON i.tenant_id = d.tenant_id
 AND i.user_id = d.user_id
 AND i.period = d.period
 AND i.deleted = b'0'
SET d.status = '已结算',
    d.settled_at = i.settled_at
WHERE d.deleted = b'0'
  AND d.status = '待结算'
  AND i.status = 'SETTLED'
  AND i.settled_at IS NOT NULL
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
