#!/usr/bin/env python3
"""Remove old, settled, local auto-proxy bet items in bounded batches."""
import argparse
from pathlib import Path
import re
import subprocess
import time


ITEMS = "lucky5_bet_item"
ISSUE_SCOPE = "i.tenant_id=o.tenant_id AND i.user_id=o.user_id AND i.period=o.period"
SETTLED_ORDERS = """o.deleted=b'0' AND i.deleted=b'0'
  AND o.order_type='AUTO_PROXY' AND o.delivery_mode='LOCAL_ONLY' AND o.market_status='NOT_REQUIRED'
  AND o.status IN ('已中奖','未中奖') AND o.item_count>0 AND o.settled_at<@cutoff
  AND i.status='SETTLED' AND i.settled_at<@cutoff"""
SETTLED_ITEMS = "b.deleted=b'0' AND b.won IS NOT NULL"


def literal(value):
    return f"CONVERT(X'{value.encode('utf-8').hex()}' USING utf8mb4) COLLATE utf8mb4_unicode_ci"


class Mysql:
    def __init__(self, defaults_file, database):
        self.command = [
            "mysql", f"--defaults-file={defaults_file}", "--no-login-paths", f"--database={database}",
            "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names",
            "--skip-reconnect", "--connect-timeout=5",
        ]

    def rows(self, statement):
        result = subprocess.run(
            self.command, input=statement.encode("utf-8"), capture_output=True, check=False,
        )
        if result.returncode:
            error = re.search(rb"ERROR \d+(?: \([A-Z0-9]+\))?", result.stderr)
            code = error.group().decode() if error else str(result.returncode)
            raise RuntimeError(f"MySQL command failed ({code})")
        return result.stdout.decode("utf-8").splitlines()


def query_prefix(cutoff):
    return f"""SET SESSION MAX_EXECUTION_TIME=15000;
SET @cutoff=CAST({literal(cutoff)} AS DATETIME);
"""


def candidate_orders(db, cutoff, after):
    cursor = "" if after is None else f"AND o.id>{literal(after)}"
    # The scoped order index also makes checking drained orders inexpensive.
    query = f"""{query_prefix(cutoff)}
SELECT HEX(o.id), o.tenant_id, o.user_id
FROM lucky5_order o FORCE INDEX (PRIMARY)
STRAIGHT_JOIN lucky5_issue i ON {ISSUE_SCOPE}
WHERE {SETTLED_ORDERS}
  {cursor}
  AND EXISTS (
    SELECT 1 FROM {ITEMS} b FORCE INDEX (idx_lucky5_bet_order)
    WHERE b.tenant_id=o.tenant_id AND b.user_id=o.user_id AND b.order_id=o.id AND {SETTLED_ITEMS}
  )
ORDER BY o.id LIMIT 32;"""
    for row in db.rows(query):
        hex_id, tenant_id, user_id = row.split("\t")
        yield bytes.fromhex(hex_id).decode("utf-8"), int(tenant_id), int(user_id)


def candidate_items(db, orders, limit):
    scopes = ", ".join(f"({tenant}, {owner}, {literal(order_id)})" for order_id, tenant, owner in orders)
    query = f"""SET SESSION MAX_EXECUTION_TIME=15000;
SELECT HEX(b.id) FROM {ITEMS} b FORCE INDEX (idx_lucky5_bet_order)
WHERE (b.tenant_id, b.user_id, b.order_id) IN ({scopes}) AND {SETTLED_ITEMS}
LIMIT {limit};"""
    return [bytes.fromhex(row).decode("utf-8") for row in db.rows(query)]


def delete_items(db, cutoff, ids):
    item_ids = ", ".join(map(literal, ids))
    # The delete starts with primary keys and checks the same parent conditions
    # after candidate selection, so a concurrent change can only defer cleanup.
    query = f"""{query_prefix(cutoff)}
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
SET SESSION innodb_lock_wait_timeout=3;
DELETE b FROM {ITEMS} b
STRAIGHT_JOIN lucky5_order o ON o.id=b.order_id AND o.tenant_id=b.tenant_id AND o.user_id=b.user_id
STRAIGHT_JOIN lucky5_issue i ON {ISSUE_SCOPE}
WHERE b.id IN ({item_ids}) AND {SETTLED_ITEMS} AND {SETTLED_ORDERS};
SELECT ROW_COUNT();"""
    [count] = db.rows(query)
    return int(count)


def run(db, batch_size, max_rows, apply):
    [cutoff] = db.rows("SELECT DATE_FORMAT(NOW()-INTERVAL 7 DAY,'%Y-%m-%d %H:%i:%s');")
    print(f"Cutoff {cutoff}, mode {'delete' if apply else 'dry run'}", flush=True)
    scanned = deleted = 0
    after = None
    while scanned < max_rows:
        orders = list(candidate_orders(db, cutoff, after))
        if not orders:
            break
        after = orders[-1][0]
        while scanned < max_rows:
            limit = min(batch_size, max_rows - scanned) if apply else max_rows - scanned
            ids = candidate_items(db, orders, limit)
            if not ids:
                break
            scanned += len(ids)
            if not apply:
                break
            removed = delete_items(db, cutoff, ids)
            deleted += removed
            time.sleep(0.1)
            if len(ids) < limit or removed < len(ids):
                break
    print(f"Eligible in scan {scanned} (limit {max_rows}), deleted {deleted}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--defaults-file", type=Path, required=True, help="MySQL client option file")
    parser.add_argument("--database", required=True)
    parser.add_argument("--apply", action="store_true", help="Delete eligible auto-proxy items")
    parser.add_argument("--batch-size", type=int, default=2000, help="Max rows per SQL DELETE")
    parser.add_argument("--max-rows", type=int, default=20_000, help="Max rows per invocation")
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 2000 or args.max_rows < 1:
        parser.error("batch size must be 1 to 2000 and max rows must be positive")
    if not args.defaults_file.is_file():
        parser.error("MySQL option file does not exist")
    db = Mysql(args.defaults_file.resolve(), args.database)
    try:
        run(db, args.batch_size, args.max_rows, args.apply)
    except (OSError, RuntimeError, ValueError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
