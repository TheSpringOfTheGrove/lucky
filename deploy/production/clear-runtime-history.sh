#!/bin/sh
# One-time, explicitly approved maintenance; never run by normal deployment.
set -eu
test "${1:-}" = ALL_TENANTS_KEEP_MEMBERS_BALANCES_CONFIG_AND_LEDGER
cd /opt/lucky5
test "$(docker inspect -f '{{.State.Running}}' lucky5-production-server-1)" = false
mysql_query() {
  docker compose exec -T mysql sh -lc 'exec mysql --default-character-set=utf8mb4 --batch --skip-column-names -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
}
# Never discard a real player's unsettled order or uncertain external transaction.
unsafe=$(printf '%s\n' "SELECT COUNT(*) FROM lucky5_order WHERE deleted=0 AND ((COALESCE(order_type,'PLAYER')<>'AUTO_PROXY' AND status NOT IN ('已中奖','未中奖','已退码')) OR market_status IN ('PENDING','SUBMITTING','RETRY','VERIFYING','MANUAL_REVIEW','CANCEL_PENDING','CANCEL_SUBMITTING','CANCEL_RETRY','CANCEL_MANUAL_REVIEW'));" | mysql_query)
test "$unsafe" = 0 || { echo 'Unsafe active real/market orders: cleanup refused.' >&2; exit 1; }
running=$(printf '%s\n' "SELECT COUNT(*) FROM lucky5_auto_proxy_execution WHERE status='RUNNING';" | mysql_query)
test "$running" = 0 || { echo 'An auto-proxy task is running: cleanup refused.' >&2; exit 1; }

preserved='CHECKSUM TABLE lucky5_member,lucky5_balance_ledger,lucky5_config,lucky5_system_state,lucky5_switch_setting,lucky5_odd,lucky5_chima_config,lucky5_link_config,lucky5_integration,lucky5_market_connection,lucky5_preset_order,lucky5_quick_command,lucky5_owner_initialization,lucky5_issue,lucky5_auto_proxy_execution,lucky5_amount_record,lucky5_rebate_record,lucky5_chima_record,system_users,system_role,system_user_role,system_role_menu,system_tenant_package;'
before=$(printf '%s\n' "$preserved" | mysql_query)
printf '%s\n' 'START TRANSACTION;
DELETE FROM lucky5_market_route_item;
DELETE FROM lucky5_bet_item;
DELETE FROM lucky5_follow_order;
DELETE FROM lucky5_order;
DELETE FROM lucky5_draw;
DELETE FROM lucky5_message;
DELETE FROM lucky5_issue_transition;
DELETE FROM lucky5_operation_log;
COMMIT;
SELECT "order",COUNT(*) FROM lucky5_order
UNION ALL SELECT "bet_item",COUNT(*) FROM lucky5_bet_item
UNION ALL SELECT "market_route_item",COUNT(*) FROM lucky5_market_route_item
UNION ALL SELECT "follow_order",COUNT(*) FROM lucky5_follow_order
UNION ALL SELECT "draw",COUNT(*) FROM lucky5_draw
UNION ALL SELECT "message",COUNT(*) FROM lucky5_message
UNION ALL SELECT "issue_transition",COUNT(*) FROM lucky5_issue_transition
UNION ALL SELECT "operation_log",COUNT(*) FROM lucky5_operation_log;' | mysql_query
after=$(printf '%s\n' "$preserved" | mysql_query)
test "$before" = "$after" || { echo 'Preserved data checksum mismatch: keep application stopped for review.' >&2; exit 1; }
echo 'Approved runtime history cleared; all preserved table checksums match.'
