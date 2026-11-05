#!/usr/bin/env python3
"""Inspect or refresh avatars only in the Lucky_chenguanxi database.

Run on Lucky_chenguanxi as root after the 60-asset release is present:
  python3 scripts/migrate-chenguanxi-avatar-assignments.py --inspect
  python3 scripts/migrate-chenguanxi-avatar-assignments.py --apply
"""

from __future__ import annotations

import argparse
import json
import random
import subprocess
from datetime import datetime, timezone
from pathlib import Path


CONTAINER = "lucky5-chen-guan-xi-mysql-1"
ROOT = Path("/opt/lucky5")
BACKUP_DIR = ROOT / "backups"
ASSET_DIR = ROOT / "lucky5-ui/src/assets/lottery/player-avatars-chenguanxi"
ACCOUNT_ASSET_DIR = ROOT / "lucky5-ui/public/chenguanxi/account-avatars"


def query(sql: str) -> list[list[str]]:
    result = subprocess.run(
        [
            "docker", "exec", "-i", CONTAINER, "sh", "-c",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$MYSQL_DATABASE" -N -B -s',
        ],
        input=sql,
        text=True,
        capture_output=True,
        check=True,
    )
    return [line.split("\t") for line in result.stdout.splitlines() if line]


def sql_string(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def assigned_numbers(count: int) -> list[int]:
    rng = random.SystemRandom()
    result: list[int] = []
    while len(result) < count:
        group = list(range(1, 61))
        rng.shuffle(group)
        result.extend(group[: count - len(result)])
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--inspect", action="store_true")
    action.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    if not Path("/etc/lucky5-chenguanxi.env").is_file() or not ROOT.is_dir():
        raise SystemExit("This is not the Lucky_chenguanxi host; no changes made")
    members = query(
        "SELECT id,user_id,avatar,avatar_path FROM lucky5_member "
        "WHERE tenant_id=1 AND deleted=0 ORDER BY id"
    )
    accounts = query(
        "SELECT id,username,avatar FROM system_users "
        "WHERE tenant_id=1 AND deleted=0 ORDER BY id"
    )
    non_admin_accounts = [row for row in accounts if row[1] != "admin"]
    if len(accounts) - len(non_admin_accounts) != 1:
        raise SystemExit("Expected exactly one admin account; no changes made")
    print(
        f"Current customer: members={len(members)}, "
        f"backend_accounts={len(accounts)}, non_admin_accounts={len(non_admin_accounts)}"
    )
    if args.inspect:
        print(f"Current distinct member avatars={len({row[2] for row in members})}")
        print(f"Current distinct non-admin account avatars={len({row[2] for row in non_admin_accounts})}")
        print(f"Admin portrait={next(row[2] for row in accounts if row[1] == 'admin')}")
        return

    expected = [f"avatar_{number:02d}.jpg" for number in range(1, 61)]
    for directory in (ASSET_DIR, ACCOUNT_ASSET_DIR):
        missing = [name for name in expected if not (directory / name).is_file()]
        if missing:
            raise SystemExit(f"Missing {len(missing)} deployed images in {directory}; no changes made")

    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    backup_path = BACKUP_DIR / f"chenguanxi-avatars-before-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}.json"
    backup_path.write_text(
        json.dumps({"members": members, "accounts": accounts}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    backup_path.chmod(0o600)

    statements = ["START TRANSACTION;"]
    for row, number in zip(members, assigned_numbers(len(members))):
        filename = f"avatar_{number:02d}.jpg"
        statements.append(
            "UPDATE lucky5_member SET "
            f"avatar={number}, avatar_path='player-avatars-chenguanxi/{filename}', "
            "updater='avatar-refresh', update_time=NOW(6) "
            f"WHERE id={sql_string(row[0])} AND tenant_id=1 AND deleted=0;"
        )
    for row, number in zip(non_admin_accounts, assigned_numbers(len(non_admin_accounts))):
        filename = f"avatar_{number:02d}.jpg"
        statements.append(
            "UPDATE system_users SET "
            f"avatar='/chenguanxi/account-avatars/{filename}', "
            "updater='avatar-refresh', update_time=NOW(6) "
            f"WHERE id={sql_string(row[0])} AND tenant_id=1 AND username<>'admin' AND deleted=0;"
        )
    statements.append("COMMIT;")
    query("\n".join(statements))
    verify = query(
        "SELECT COUNT(*),COUNT(DISTINCT avatar),MIN(avatar),MAX(avatar) "
        "FROM lucky5_member WHERE tenant_id=1 AND deleted=0;"
    )[0]
    print(
        f"Updated members={verify[0]}, distinct_avatars={verify[1]}, "
        f"avatar_range={verify[2]}..{verify[3]}"
    )
    print(f"Updated non-admin backend accounts={len(non_admin_accounts)}")
    print(f"Rollback mapping: {backup_path}")


if __name__ == "__main__":
    main()
