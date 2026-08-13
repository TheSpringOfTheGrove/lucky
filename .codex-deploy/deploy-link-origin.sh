#!/bin/sh
set -eu

cd /opt/lucky5

echo '2B7E102CBAEE6FFC63406F39E42E30CF880B1F28E1FDE0597E90ABA2DB724D63  /tmp/lucky5-server-link-origin.tar' | sha256sum -c -
echo '9BBC87C564A23B4EC5C55DB3078FFD00DE19C9FD64044F9B2EC78854B2659BC6  /tmp/lucky5-ui-link-origin.tar' | sha256sum -c -

stamp=20260811-link-origin
docker tag lucky5-server:production "lucky5-server:backup-$stamp"
docker tag lucky5-ui:production "lucky5-ui:backup-$stamp"

docker load -i /tmp/lucky5-server-link-origin.tar
docker load -i /tmp/lucky5-ui-link-origin.tar
docker tag lucky5-server:local lucky5-server:production
docker tag lucky5-ui:local lucky5-ui:production

docker compose up -d --no-deps --force-recreate server
server_status=starting
i=0
while [ "$i" -lt 36 ]; do
    server_status=$(docker inspect -f '{{.State.Health.Status}}' lucky5-production-server-1 2>/dev/null || true)
    [ "$server_status" = healthy ] && break
    i=$((i + 1))
    sleep 5
done
[ "$server_status" = healthy ]

docker compose up -d --no-deps --force-recreate frontend
frontend_status=starting
i=0
while [ "$i" -lt 24 ]; do
    frontend_status=$(docker inspect -f '{{.State.Health.Status}}' lucky5-production-frontend-1 2>/dev/null || true)
    [ "$frontend_status" = healthy ] && break
    i=$((i + 1))
    sleep 3
done
[ "$frontend_status" = healthy ]

docker compose ps server frontend caddy
