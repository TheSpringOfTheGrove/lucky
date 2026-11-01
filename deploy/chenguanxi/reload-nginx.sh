#!/bin/sh
set -eu

docker exec lucky5-chen-guan-xi-frontend-1 nginx -t
docker exec lucky5-chen-guan-xi-frontend-1 nginx -s reload
