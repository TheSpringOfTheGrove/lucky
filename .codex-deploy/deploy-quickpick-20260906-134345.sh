#!/bin/sh
set -eu

cd /opt/lucky5

server_tar=/tmp/lucky5-server-quickpick-20260906-134345.tar
ui_tar=/tmp/lucky5-ui-quickpick-20260906-134345.tar
server_version=lucky5-server:quickpick-20260906-134345
ui_version=lucky5-ui:quickpick-20260906-134345
server_backup=lucky5-server:backup-20260906-134345
ui_backup=lucky5-ui:backup-20260906-134345

echo '12C67F40C3DA2A4FF5C7EBFDC106F36E8D57D0B94E785486B26D135810C61497  /tmp/lucky5-server-quickpick-20260906-134345.tar' | sha256sum -c -
echo '8B8104DF22A87075D3AAF23B88BBD25AD466E7B1AAE1EF547E090B78BE2D02A2  /tmp/lucky5-ui-quickpick-20260906-134345.tar' | sha256sum -c -

docker tag lucky5-server:production "$server_backup"
docker tag lucky5-ui:production "$ui_backup"
docker load -i "$server_tar"
docker load -i "$ui_tar"

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
