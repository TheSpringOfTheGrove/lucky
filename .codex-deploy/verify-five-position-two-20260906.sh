#!/bin/sh
set -eu

cd /opt/lucky5

docker compose exec -T mysql sh -lc 'exec mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' <<'SQL'
SELECT COUNT(*), COUNT(DISTINCT user_id), MIN(rate), MAX(rate)
FROM lucky5_odd
WHERE code = 'regex5d2' AND deleted = 0;
SQL
