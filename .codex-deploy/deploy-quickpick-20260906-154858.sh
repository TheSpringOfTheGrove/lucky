#!/bin/sh
set -eu

cd /opt/lucky5

server_tar=/tmp/lucky5-server-quickpick-20260906-154858.tar
ui_tar=/tmp/lucky5-ui-quickpick-20260906-154858.tar
odds_patch=/tmp/20260906-five-position-two-odds.sql
server_version=lucky5-server:quickpick-20260906-154858
ui_version=lucky5-ui:quickpick-20260906-154858
server_backup=lucky5-server:backup-20260906-154858
ui_backup=lucky5-ui:backup-20260906-154858

echo '8A7C02C3E6796B4DC4F6B474541E8B21D92AC500A2795C42A5F7D10D0D9753DA  /tmp/lucky5-server-quickpick-20260906-154858.tar' | sha256sum -c -
echo '5A2CB034DC033578A0F94A6587314B04E0DCA82FF51AA05BD90B8F571F604813  /tmp/lucky5-ui-quickpick-20260906-154858.tar' | sha256sum -c -
echo '66C7122B2A8E880F75442A424FA478FFC52DF62C3E25AF423172222B5D6FD778  /tmp/20260906-five-position-two-odds.sql' | sha256sum -c -

docker tag lucky5-server:production "$server_backup"
docker tag lucky5-ui:production "$ui_backup"
docker load -i "$server_tar"
docker load -i "$ui_tar"

docker compose exec -T mysql sh -lc 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < "$odds_patch"

wait_healthy() {
  container="$1"
  attempts="$2"
  delay="$3"
  status=starting
  i=0
  while [ "$i" -lt "$attempts" ]; do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || true)
    [ "$status" = healthy ] && return 0
    i=$((i + 1))
    sleep "$delay"
  done
  return 1
}

rollback() {
  echo 'Deployment failed; restoring previous production images.' >&2
  docker tag "$server_backup" lucky5-server:production
  docker tag "$ui_backup" lucky5-ui:production
  docker compose up -d --no-deps --force-recreate server
  wait_healthy lucky5-production-server-1 36 5 || true
  docker compose up -d --no-deps --force-recreate frontend
  wait_healthy lucky5-production-frontend-1 24 3 || true
}

trap rollback HUP INT TERM

docker tag "$server_version" lucky5-server:production
docker tag "$ui_version" lucky5-ui:production

docker compose up -d --no-deps --force-recreate server
if ! wait_healthy lucky5-production-server-1 36 5; then
  rollback
  exit 1
fi

docker compose up -d --no-deps --force-recreate frontend
if ! wait_healthy lucky5-production-frontend-1 24 3; then
  rollback
  exit 1
fi

trap - HUP INT TERM
docker compose ps server frontend caddy
docker image inspect lucky5-server:production lucky5-ui:production --format '{{.RepoTags}} {{.Id}}'
