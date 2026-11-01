#!/bin/sh
set -eu

container=lucky5-chen-guan-xi-frontend-1
challenge_dir=/usr/share/nginx/html/.well-known/acme-challenge

case "$CERTBOT_TOKEN" in
    ''|*[!A-Za-z0-9_-]*) exit 1 ;;
esac

docker exec "$container" rm -f "$challenge_dir/$CERTBOT_TOKEN"
