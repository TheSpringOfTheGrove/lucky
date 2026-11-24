#!/usr/bin/env python3
"""Archive finalized local auto-proxy bet items and remove verified copies."""
import argparse
from contextlib import contextmanager
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


TABLE = "lucky5_bet_item"
COLUMNS = (
    "id", "user_id", "order_id", "play", "selection", "snapshot_json", "amount", "odds",
    "won", "payout", "creator", "create_time", "updater", "update_time", "deleted", "tenant_id",
)
# Keep money and datetime exact. MySQL JSON_ARRAY converts BIT values only after an explicit cast.
TEXT_VALUES = {"amount", "odds", "payout", "create_time", "update_time"}
BIT_VALUES = {"won", "deleted"}
VALUES = [
    f"CAST(b.`{name}` AS CHAR CHARACTER SET utf8mb4)" if name in TEXT_VALUES
    else f"CAST(b.`{name}` AS UNSIGNED)" if name in BIT_VALUES
    else f"b.`{name}`"
    for name in COLUMNS
]
DOCUMENT = f"CAST(JSON_ARRAY({', '.join(VALUES)}) AS CHAR CHARACTER SET utf8mb4)"
DIGEST = f"SHA2({DOCUMENT}, 256)"
COLUMN_LIST = ", ".join(f"`{name}`" for name in COLUMNS)
JOIN = f"""FROM {TABLE} b
STRAIGHT_JOIN lucky5_order o ON o.id=b.order_id AND o.tenant_id=b.tenant_id AND o.user_id=b.user_id
STRAIGHT_JOIN lucky5_issue i ON i.tenant_id=o.tenant_id AND i.user_id=o.user_id AND i.period=o.period"""
ELIGIBLE = """b.deleted=b'0' AND b.won IS NOT NULL AND o.deleted=b'0' AND i.deleted=b'0'
    AND o.order_type='AUTO_PROXY' AND o.delivery_mode='LOCAL_ONLY' AND o.market_status='NOT_REQUIRED'
    AND o.status IN ('已中奖','未中奖') AND o.item_count>0 AND o.settled_at<CAST(@cutoff AS DATETIME)
    AND i.status='SETTLED' AND i.settled_at<CAST(@cutoff AS DATETIME)"""


def literal(value):
    if value is None:
        return "NULL"
    if type(value) is int:
        return str(value)
    if not isinstance(value, str):
        raise ValueError("Unexpected archive column value")
    return f"CONVERT(X'{value.encode('utf-8').hex()}' USING utf8mb4) COLLATE utf8mb4_unicode_ci"


class Mysql:
    def __init__(self, defaults_file, database):
        self.command = [
            "mysql", f"--defaults-file={defaults_file}", "--no-login-paths", f"--database={database}",
            "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names",
            "--quick", "--skip-reconnect", "--connect-timeout=5", "--max-allowed-packet=128M",
        ]

    @contextmanager
    def stream(self, statement):
        with tempfile.TemporaryFile() as sql, tempfile.TemporaryFile() as errors:
            sql.write(("SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;\n" + statement).encode("utf-8"))
            sql.seek(0)
            with subprocess.Popen(self.command, stdin=sql, stdout=subprocess.PIPE, stderr=errors) as proc:
                try:
                    yield (line.rstrip(b"\r\n") for line in proc.stdout)
                    if proc.wait():
                        errors.seek(0)
                        error = re.search(rb"ERROR \d+(?: \([A-Z0-9]+\))?", errors.read(8192))
                        code = error.group().decode() if error else str(proc.returncode)
                        raise RuntimeError(f"MySQL command failed ({code})")
                except BaseException:
                    if proc.poll() is None:
                        proc.kill()
                    raise

    def rows(self, statement):
        with self.stream(statement) as lines:
            return list(lines)


def sql_prefix(cutoff):
    return f"SET SESSION MAX_EXECUTION_TIME=30000; SET @cutoff=CAST({literal(cutoff)} AS DATETIME);\n"


def candidate_ids(db, cutoff, after, count):
    cursor = "" if after is None else f"\n  AND b.id>{literal(after)}"
    query = f"{sql_prefix(cutoff)}SELECT HEX(b.id) {JOIN} WHERE {ELIGIBLE}{cursor}\nORDER BY b.id LIMIT {count};"
    return [bytes.fromhex(row.decode()).decode("utf-8") for row in db.rows(query)]


def archive_digest(path):
    with gzip.open(path, "rb") as archive:
        return hashlib.file_digest(archive, "sha256").hexdigest()


def archive_batch(db, cutoff, ids, directory):
    selected_ids = ", ".join(map(literal, ids))
    query = f"""{sql_prefix(cutoff)}SELECT {DIGEST}, {DOCUMENT}
{JOIN} WHERE b.id IN ({selected_ids}) AND {ELIGIBLE} ORDER BY b.id;"""
    expected = set(ids)
    archived = {}
    content = hashlib.sha256()
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(dir=directory, prefix=".bet-item-", suffix=".tmp", delete=False) as tmp:
            temp_path = Path(tmp.name)
            with gzip.GzipFile(filename="", fileobj=tmp, mode="wb", mtime=0) as archive:
                header = b"SET NAMES utf8mb4;\nSTART TRANSACTION;\n"
                archive.write(header)
                content.update(header)
                with db.stream(query) as lines:
                    for line in lines:
                        checksum, document = line.split(b"\t", 1)
                        if hashlib.sha256(document).hexdigest().encode() != checksum:
                            raise RuntimeError("MySQL row checksum mismatch")
                        row = json.loads(document)
                        if len(row) != len(COLUMNS) or row[0] not in expected or row[0] in archived:
                            raise RuntimeError("Unexpected row in archive export")
                        values = ", ".join(map(literal, row))
                        encoded = (
                            f"INSERT INTO `{TABLE}` ({COLUMN_LIST}) VALUES ({values}) "
                            "ON DUPLICATE KEY UPDATE `id`=`id`;\n"
                        ).encode()
                        archive.write(encoded)
                        content.update(encoded)
                        archived[row[0]] = checksum.decode()
                archive.write(b"COMMIT;\n")
                content.update(b"COMMIT;\n")
            tmp.flush()
            os.fsync(tmp.fileno())
        if not archived:
            return {}, None
        digest = content.hexdigest()
        destination = directory / f"lucky5-bet-item-v1-{digest}.sql.gz"
        if archive_digest(temp_path) != digest:
            raise RuntimeError("Archive readback checksum mismatch")
        if destination.exists():
            if archive_digest(destination) != digest:
                raise RuntimeError("Existing archive checksum mismatch")
        else:
            os.rename(temp_path, destination)
        fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        return archived, destination
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


def remove_batch(db, cutoff, archived):
    ids = ", ".join(map(literal, archived))
    rows = ", ".join(f"({literal(row_id)}, '{checksum}')" for row_id, checksum in archived.items())
    # Recheck the parent state and the full archived row in the same statement as the delete.
    query = f"""{sql_prefix(cutoff)}SET SESSION innodb_lock_wait_timeout=5;
START TRANSACTION;
DELETE b {JOIN} WHERE b.id IN ({ids}) AND {ELIGIBLE} AND (b.id, {DIGEST}) IN ({rows});
SELECT ROW_COUNT();
COMMIT;"""
    [result] = db.rows(query)
    return int(result)


def run(db, batch_size, max_rows, directory):
    columns = {line.split(b"\t", 1)[0].decode() for line in db.rows(f"SHOW COLUMNS FROM {TABLE};")}
    if columns != set(COLUMNS):
        raise RuntimeError("Unexpected bet item schema. Review all columns before archiving.")
    [cutoff] = db.rows("SELECT DATE_FORMAT(NOW()-INTERVAL 7 DAY,'%Y-%m-%d %H:%i:%s');")
    cutoff = cutoff.decode()
    print(f"Cutoff {cutoff}, mode {'archive and delete' if directory else 'dry run'}", flush=True)
    scanned = archived_count = removed = 0
    after = None
    while scanned < max_rows:
        ids = candidate_ids(db, cutoff, after, min(batch_size, max_rows - scanned))
        if not ids:
            break
        after = ids[-1]
        scanned += len(ids)
        if directory:
            archived, destination = archive_batch(db, cutoff, ids, directory)
            if archived:
                deleted = remove_batch(db, cutoff, archived)
                archived_count += len(archived)
                removed += deleted
                print(f"{destination.name}: archived {len(archived)}, deleted {deleted}", flush=True)
            time.sleep(0.1)
    print(f"Eligible in scan {scanned} (limit {max_rows}), archived {archived_count}, deleted {removed}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--defaults-file", type=Path, required=True, help="MySQL client option file")
    parser.add_argument("--database", required=True)
    parser.add_argument("--archive-dir", type=Path)
    parser.add_argument("--apply", action="store_true", help="Archive then remove eligible rows")
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--max-rows", type=int, default=20_000, help="Max candidates per invocation")
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 500 or args.max_rows < 1:
        parser.error("batch size must be 1 to 500 and max rows must be positive")
    if not args.defaults_file.is_file():
        parser.error("MySQL option file does not exist")
    if args.apply and (args.archive_dir is None or not args.archive_dir.is_dir()):
        parser.error("--apply requires an existing archive directory")
    db = Mysql(args.defaults_file.resolve(), args.database)
    try:
        if args.apply:
            with (args.archive_dir / ".lucky5-bet-item-retention.lock").open("a") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                run(db, args.batch_size, args.max_rows, args.archive_dir)
        else:
            run(db, args.batch_size, args.max_rows, None)
    except BlockingIOError:
        parser.exit(1, "Another bet item archive run is using this directory.\n")
    except (OSError, RuntimeError, ValueError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
