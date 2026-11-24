#!/usr/bin/env bash
# Use only generated rows in a disposable MySQL container.
set -Eeuo pipefail
cd "$(dirname "$0")/../../.."
scratch=$(mktemp -d)
test_name="lucky5-retention-test-$(openssl rand -hex 4)"
image=${RETENTION_TEST_IMAGE:-lucky5-bet-item-retention:local}
platform=${RETENTION_TEST_PLATFORM:-linux/amd64}
cleanup() {
  if docker container inspect "$test_name" >/dev/null 2>&1; then
    docker stop "$test_name" >/dev/null || true
    docker rm -v "$test_name" >/dev/null
  fi
  docker network rm "$test_name" >/dev/null 2>&1 || true
  rm -r -- "$scratch"
}
trap cleanup EXIT
docker network create --internal "$test_name" >/dev/null
docker run -d --platform "$platform" --name "$test_name" --network "$test_name" --network-alias mysql \
  -e MYSQL_ROOT_PASSWORD=synthetic-root-password -e MYSQL_DATABASE=retention_test mysql:8.4 >/dev/null
mysql_test() {
  docker exec -i -e MYSQL_PWD=synthetic-root-password "$test_name" \
    mysql --protocol=TCP -h 127.0.0.1 --default-character-set=utf8mb4 -uroot --batch --raw --skip-column-names retention_test "$@"
}
for attempt in $(seq 1 60); do
  if mysql_test -e 'SELECT 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
mysql_test -e 'SELECT 1' >/dev/null
python3 - <<'PY' | mysql_test
from pathlib import Path
import re
ddl = Path("sql/mysql/lucky5-business.sql").read_text()
for table in ("lucky5_bet_item", "lucky5_order", "lucky5_issue"):
    print(re.search(r"CREATE TABLE IF NOT EXISTS `" + table + r"` \(.*?;\n", ddl, re.S)[0])
PY
mysql_test <<'SQL'
SET @older=NOW()-INTERVAL 8 DAY, @recent=NOW()-INTERVAL 6 DAY;
INSERT INTO lucky5_issue (tenant_id,user_id,period,status,settled_at) VALUES
  (1,1,'old','SETTLED',@older), (1,1,'recent','SETTLED',@recent),
  (1,1,'unsettled','DRAW_PENDING',@older), (1,1,'late-issue','SETTLED',@recent),
  (2,2,'old','SETTLED',@older);
INSERT INTO lucky5_order (id,tenant_id,user_id,member_id,member_name,period,content,amount,status,
  order_type,delivery_mode,market_status,market_order_id,item_count,settled_at,period_sequence) VALUES
  ('old-auto',1,1,'member','测试','old','旧托',1,'未中奖','AUTO_PROXY','LOCAL_ONLY','NOT_REQUIRED','',2,@older,1),
  ('recent-auto',1,1,'member','测试','recent','近期托',1,'已中奖','AUTO_PROXY','LOCAL_ONLY','NOT_REQUIRED','',1,@recent,1),
  ('player',1,1,'member','测试','old','真实玩家',1,'已中奖','PLAYER','LOCAL_ONLY','NOT_REQUIRED','',1,@older,2),
  ('uncertain',1,1,'member','测试','old','外盘待核对',1,'已中奖','AUTO_PROXY','MARKET_ADAPTER','MANUAL_REVIEW','',1,@older,3),
  ('pending',1,1,'member','测试','unsettled','未结算期',1,'未中奖','AUTO_PROXY','LOCAL_ONLY','NOT_REQUIRED','',1,@older,1),
  ('legacy-count',1,1,'member','测试','old','注数缺失',1,'未中奖','AUTO_PROXY','LOCAL_ONLY','NOT_REQUIRED','',0,@older,4),
  ('late-issue-auto',1,1,'member','测试','late-issue','刚完成开奖',1,'未中奖',
    'AUTO_PROXY','LOCAL_ONLY','NOT_REQUIRED','',1,@older,1),
  ('tenant-two',2,2,'member','测试','old','其他租户',1,'未中奖','AUTO_PROXY','LOCAL_ONLY','NOT_REQUIRED','',1,@older,1);
INSERT INTO lucky5_bet_item (id,tenant_id,user_id,order_id,play,selection,snapshot_json,amount,odds,won,creator) VALUES
  ('a-old-compact',1,1,'old-auto','复式','甲',JSON_OBJECT('payload',CONCAT('千百十个',CHAR(10,9,34,92),'😀')),1.20,1.9500,b'0','测试'),
  ('b-old-legacy',1,1,'old-auto','一定位','XXX1',NULL,2.70,4.1200,b'0',NULL),
  ('c-other-tenant',2,2,'tenant-two','一定位','XXX1',NULL,0.13,9.0000,b'0','测试'),
  ('recent',1,1,'recent-auto','一定位','XXX1',NULL,1,9,b'1','测试'),
  ('player',1,1,'player','一定位','XXX1',NULL,1,9,b'1','测试'),
  ('uncertain',1,1,'uncertain','一定位','XXX1',NULL,1,9,b'1','测试'),
  ('pending',1,1,'pending','一定位','XXX1',NULL,1,9,b'0','测试'),
  ('missing-count',1,1,'legacy-count','一定位','XXX1',NULL,1,9,b'0','测试'),
  ('unfinished-item',1,1,'old-auto','一定位','XXX1',NULL,1,9,NULL,'测试'),
  ('wrong-tenant',2,1,'old-auto','一定位','XXX1',NULL,1,9,b'0','测试'),
  ('late-issue-item',1,1,'late-issue-auto','一定位','XXX1',NULL,1,9,b'0','测试'),
  ('wrong-owner',1,2,'old-auto','一定位','XXX1',NULL,1,9,b'0','测试');
CREATE TABLE lucky5_config (setting varchar(30));
INSERT INTO lucky5_config VALUES ('keep');
CREATE USER 'retention'@'%' IDENTIFIED BY 'synthetic-worker-password';
GRANT SELECT,DELETE ON retention_test.lucky5_bet_item TO 'retention'@'%';
GRANT SELECT ON retention_test.lucky5_order TO 'retention'@'%';
GRANT SELECT ON retention_test.lucky5_issue TO 'retention'@'%';
SQL
cat > "$scratch/client.cnf" <<'CNF'
[client]
host=mysql
user=retention
password=synthetic-worker-password
CNF
chmod 0755 "$scratch"
chmod 0644 "$scratch/client.cnf"
worker() {
  docker run --rm --platform "$platform" --network "$test_name" --read-only --tmpfs /tmp:mode=1777 \
    --mount "type=bind,src=$scratch/client.cnf,dst=/run/mysql.cnf,readonly" \
    "$image" --defaults-file /run/mysql.cnf --database retention_test "$@"
}
dump_items() {
  mysql_test -e 'SELECT id,tenant_id,user_id,order_id,play,selection,HEX(snapshot_json),amount,odds,
    CAST(won AS UNSIGNED),payout,creator,create_time,updater,update_time,CAST(deleted AS UNSIGNED)
    FROM lucky5_bet_item ORDER BY id'
}
dump_items > "$scratch/before"
worker --batch-size 1 | tee "$scratch/dry"
grep -q 'Eligible in scan 3 .*deleted 0' "$scratch/dry"
dump_items | cmp -s - "$scratch/before"

worker --apply --batch-size 1 --max-rows 1 | tee "$scratch/first"
grep -q 'Eligible in scan 1 .*deleted 1' "$scratch/first"
worker --apply --batch-size 1 | tee "$scratch/apply"
grep -q 'Eligible in scan 2 .*deleted 2' "$scratch/apply"
awk -F '\t' '$1 != "a-old-compact" && $1 != "b-old-legacy" && $1 != "c-other-tenant"' "$scratch/before" > "$scratch/expected"
dump_items | cmp -s - "$scratch/expected"
test "$(mysql_test -e 'SELECT setting FROM lucky5_config')" = keep
test "$(mysql_test -e 'SELECT COUNT(*) FROM lucky5_order')" = 8
test "$(mysql_test -e 'SELECT COUNT(*) FROM lucky5_issue')" = 5

worker --apply | grep -q 'Eligible in scan 0 .*deleted 0'

if docker exec -e MYSQL_PWD=synthetic-worker-password "$test_name" \
  mysql -h127.0.0.1 -uretention retention_test -e 'DELETE FROM lucky5_config' > "$scratch/privilege" 2>&1; then
  echo 'Configuration DELETE must be denied' >&2
  exit 1
fi
grep -q 'denied' "$scratch/privilege"
echo 'Isolated retention checks passed.'
