#!/bin/sh
set -eu

container=lucky5-chen-guan-xi-frontend-1
challenge_dir=/usr/share/nginx/html/.well-known/acme-challenge

case "$CERTBOT_TOKEN" in
    ''|*[!A-Za-z0-9_-]*) exit 1 ;;
esac

docker exec "$container" mkdir -p "$challenge_dir"
printf '%s' "$CERTBOT_VALIDATION" | docker exec -i "$container" sh -c 'cat > "$1/$2"' sh "$challenge_dir" "$CERTBOT_TOKEN"
