#!/bin/sh
# Run against the existing production compose directory. Payload is verified before use.
set -eu
release=${1:?release commit is required}
payload=${2:?absolute payload directory is required}
case "$release" in ''|*[!0-9a-f]*) echo 'Invalid release commit' >&2; exit 2;; esac
case "$payload" in /tmp/lucky5-release-*) ;; *) echo 'Invalid payload directory' >&2; exit 2;; esac
cd /opt/lucky5
test -f compose.yaml
test -f "$payload/SHA256SUMS"
(cd "$payload" && sha256sum -c SHA256SUMS)

server_image="lucky5-server:release-$release"
ui_image="lucky5-ui:release-$release"
server_backup="lucky5-server:backup-$release"
ui_backup="lucky5-ui:backup-$release"
server_container=lucky5-production-server-1
ui_container=lucky5-production-frontend-1
backup_dir="/opt/lucky5/backups/$release-$(date -u +%Y%m%dT%H%M%SZ)"
umask 077
mkdir -p "$backup_dir"

# Save the images actually running, which may differ from a stale production tag.
docker tag "$(docker inspect -f '{{.Image}}' "$server_container")" "$server_backup"
docker tag "$(docker inspect -f '{{.Image}}' "$ui_container")" "$ui_backup"
docker compose exec -T mysql sh -lc 'exec mysqldump --single-transaction --quick --hex-blob --no-tablespaces --set-gtid-purged=OFF -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' > "$backup_dir/database.sql"
test -s "$backup_dir/database.sql"
gzip "$backup_dir/database.sql"
gzip -t "$backup_dir/database.sql.gz"
docker load -i "$payload/images.tar"
docker image inspect "$server_image" "$ui_image" --format '{{.Id}}'

wait_healthy() {
  service_container=$1
  max_attempts=$2
  attempt=0
  while [ "$attempt" -lt "$max_attempts" ]; do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$service_container" 2>/dev/null || true)
    [ "$status" = healthy ] && return 0
    attempt=$((attempt + 1))
    sleep 3
  done
  return 1
}
changed=0
on_exit() {
  result=$1
  trap - EXIT HUP INT TERM
  if [ "$result" -ne 0 ] && [ "$changed" -eq 1 ]; then
    echo 'Release failed; restoring the previous application images.' >&2
    docker tag "$server_backup" lucky5-server:production
    docker tag "$ui_backup" lucky5-ui:production
    docker compose up -d --no-deps --force-recreate server
    wait_healthy "$server_container" 60 || true
    docker compose up -d --no-deps --force-recreate frontend
    wait_healthy "$ui_container" 30 || true
    # Additive schema and audit metadata are retained; never erase post-backup business data.
  fi
  exit "$result"
}
trap 'on_exit $?' EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM

# No full baseline replay: preserve all existing owner and market configuration.
for patch in 20260928-room-admin-upgrade.sql 20260928-admin-user-expiration.sql 20260928-admin-menu-visibility.sql; do
  docker compose exec -T mysql sh -lc 'exec mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < "$payload/$patch"
done
docker tag "$server_image" lucky5-server:production
docker tag "$ui_image" lucky5-ui:production
changed=1
docker compose up -d --no-deps --force-recreate server
wait_healthy "$server_container" 60
docker compose up -d --no-deps --force-recreate frontend
wait_healthy "$ui_container" 30

# Verify the actual running versions, not just tags, and the API body, not just HTTP 200.
test "$(docker inspect -f '{{.Image}}' "$server_container")" = "$(docker image inspect -f '{{.Id}}' "$server_image")"
test "$(docker inspect -f '{{.Image}}' "$ui_container")" = "$(docker image inspect -f '{{.Id}}' "$ui_image")"
docker compose exec -T server sh -lc 'curl --fail --silent http://127.0.0.1:48080/v3/api-docs | grep -q "\"openapi\""'
docker compose exec -T frontend wget --quiet --spider http://127.0.0.1/
docker compose ps
printf 'Release %s deployed; private database backup: %s\n' "$release" "$backup_dir"
