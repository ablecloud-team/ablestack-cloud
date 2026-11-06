# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Host-local, transactional index of retired backup execution records."""
import argparse
from contextlib import closing, ExitStack
import fcntl
import json
import os
from pathlib import Path
import re
import sqlite3
import tempfile
import time


def database(root):
    path = Path(root) / "job-history.sqlite"
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise RuntimeError("Job history is not a regular local database")
    return path


def read(root, job_id):
    path = database(root)
    if not path.exists():
        return None
    # Read-only connections never create an empty index after a path/mount error.
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=30)) as connection:
        row = connection.execute("SELECT record FROM retired_jobs WHERE job_id = ?", (job_id,)).fetchone()
        return json.loads(row[0]) if row else None


def compact(root, job_id):
    root = Path(root).resolve(strict=True)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", job_id) or job_id in (".", ".."):
        raise RuntimeError("Invalid retired job ID")
    start = root / "backup-start" / (job_id + ".json")
    if start.is_file():
        # Same POSIX record lock as Java FileLock and the Python worker's claim.
        # Publish the permanent index fence before unlinking this old lock inode.
        with start.with_suffix(".lock").open("a") as lock:
            fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return compact_locked(root, job_id)
    return compact_locked(root, job_id)


def compact_locked(root, job_id):
    # Restore receipts belong to a separately acknowledged deletion, not backup GC.
    if any((root / directory / (job_id + ".json")).exists()
           for directory in ("restore-start", "restore-outcomes")):
        return None
    start = root / "backup-start" / (job_id + ".json")
    closed = root / "staging-admission-closed" / (job_id + ".closed")
    if (root / "jobs" / job_id).exists() or (root / "jobs" / (job_id + ".properties")).exists():
        raise RuntimeError("Execution records must be cleaned before compaction")
    existing = read(root, job_id)
    if not closed.is_file() and existing is None:
        return None  # NAS/legacy workflows without admission receipts need no tombstone.
    receipt = json.loads(start.read_text()) if start.is_file() else None
    if receipt is not None and (receipt.get("plan", {}).get("jobId") != job_id
                                or receipt.get("state") not in ("STARTED", "START_FAILED", "DISPATCHED")):
        raise RuntimeError("Retired backup start identity is unconfirmed")
    closure = closed.read_text() if closed.is_file() else None
    record = existing or {"version": 1, "jobId": job_id, "startReceipt": receipt,
                          "closedReason": closure, "retiredAt": int(time.time() * 1000)}
    if (record.get("version") != 1 or record.get("jobId") != job_id or not record.get("closedReason")
            or (receipt is not None and record.get("startReceipt") != receipt)
            or (closure is not None and record.get("closedReason") != closure)):
        raise RuntimeError("Retired job differs from its durable history")
    path = database(root)
    # Python sqlite3 is also used by readers; no Java JDBC driver is required.
    initialize(root, path)
    with closing(sqlite3.connect(path, timeout=30)) as connection:
        with connection:
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute("INSERT OR IGNORE INTO retired_jobs VALUES (?, ?)", (job_id, json.dumps(record)))
            actual = json.loads(connection.execute("SELECT record FROM retired_jobs WHERE job_id = ?", (job_id,)).fetchone()[0])
            if actual != record:
                raise RuntimeError("Conflicting retired job history")
    # Commit the tombstone before removing individual start/admission files.
    sync_directory(root)
    for file in (start, start.with_suffix(".lock"), closed):
        file.unlink(missing_ok=True)
    for directory in (start.parent, closed.parent):
        if directory.exists():
            sync_directory(directory)
    return record


def compact_restore(root, job_id):
    """Forget a deleted backup's terminal restore details, retaining a small replay fence."""
    root = Path(root).resolve(strict=True)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", job_id) or job_id in (".", ".."):
        raise RuntimeError("Invalid retired restore ID")
    start = root / "restore-start" / (job_id + ".json")
    outcome = root / "restore-outcomes" / (job_id + ".json")
    closed = root / "staging-admission-closed" / (job_id + ".closed")
    with ExitStack() as stack:
        for file in (start, outcome):
            if file.parent.exists():
                if file.is_symlink() or file.with_suffix(".lock").is_symlink():
                    raise RuntimeError("Restore history cannot be a symbolic link")
                lock = stack.enter_context(file.with_suffix(".lock").open("a"))
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if (root / "jobs" / job_id).exists() or (root / "jobs" / (job_id + ".properties")).exists():
            raise RuntimeError("Restore execution records must be cleaned before retirement")
        existing = read(root, job_id)
        if existing is not None and existing.get("kind") == "RESTORE":
            record = existing
            for file, is_start in ((start, True), (outcome, False)):
                if file.is_file():
                    saved = json.loads(file.read_text())
                    identity = (saved.get("plan") or {}) if is_start else saved
                    backup = (identity.get("manifest") or {}).get("backupUuid") if is_start else identity.get("sourceBackupUuid")
                    if (identity.get("jobId") != job_id or backup != record.get("sourceBackupUuid")
                            or identity.get("targetVmName") != record.get("targetVmName")):
                        raise RuntimeError("Restore files conflict with their retired identity")
        else:
            receipt = json.loads(start.read_text()) if start.is_file() else None
            plan = (receipt or {}).get("plan") or {}
            manifest = plan.get("manifest") or {}
            if (receipt is None or receipt.get("version") != 1 or plan.get("jobId") != job_id
                    or not manifest.get("backupUuid") or not plan.get("targetVmName")
                    or receipt.get("state") not in ("INITIALIZED", "START_FAILED")):
                raise RuntimeError("Restore start ownership or termination is unconfirmed")
            if Path(plan["destination"]).exists() or (Path(plan["stageRoot"]) / ".volume-reservations" / (job_id + ".json")).exists():
                raise RuntimeError("Restore staging or reservation cleanup is incomplete")
            if receipt["state"] == "INITIALIZED":
                result = json.loads(outcome.read_text()) if outcome.is_file() else None
                if (result is None or result.get("version") != 1 or result.get("jobId") != job_id
                        or result.get("sourceBackupUuid") != manifest["backupUuid"]
                        or result.get("targetVmName") != plan["targetVmName"]
                        or result.get("outcome") not in ("COMMITTED", "ROLLED_BACK", "FAILED")
                        or (result.get("transactionId") and (result.get("outcome") == "FAILED"
                            or result.get("primaryCleanupState") != "COMPLETED"))
                        or (not result.get("transactionId") and result.get("outcome") != "FAILED")
                        or (not closed.is_file() and not (existing or {}).get("closedReason"))):
                    raise RuntimeError("Restore outcome or admission closure is unconfirmed")
            elif outcome.exists():
                raise RuntimeError("Unstarted restore conflicts with a VM outcome")
            if existing is not None and (existing.get("startReceipt") is not None or existing.get("kind") is not None):
                raise RuntimeError("Restore conflicts with existing job history")
            record = {"version": 1, "kind": "RESTORE", "jobId": job_id,
                      "sourceBackupUuid": manifest["backupUuid"], "targetVmName": plan["targetVmName"],
                      "hostId": plan.get("hostId"), "provider": manifest.get("provider"),
                      "closedReason": "Restore records removed after backup deletion",
                      "retiredAt": int(time.time() * 1000)}
            path = database(root)
            initialize(root, path)
            with closing(sqlite3.connect(path, timeout=30)) as connection:
                with connection:
                    connection.execute("PRAGMA synchronous=FULL")
                    actual = connection.execute("SELECT record FROM retired_jobs WHERE job_id = ?", (job_id,)).fetchone()
                    if (json.loads(actual[0]) if actual else None) != existing:
                        raise RuntimeError("Restore history changed during retirement")
                    connection.execute("INSERT OR REPLACE INTO retired_jobs VALUES (?, ?)", (job_id, json.dumps(record)))
            sync_directory(root)
        # Every reader/initializer checks the committed fence after taking its file lock.
        # This order also makes a lost deletion response safe to retry.
        for file in (start, start.with_suffix(".lock"), outcome, outcome.with_suffix(".lock"), closed):
            file.unlink(missing_ok=True)
        for directory in (start.parent, outcome.parent, closed.parent):
            if directory.exists():
                sync_directory(directory)
        return record


def initialize(root, path):
    if path.exists():
        return
    descriptor, name = tempfile.mkstemp(prefix=".job-history-", suffix=".tmp", dir=root)
    os.close(descriptor)
    temporary = Path(name)
    try:
        with closing(sqlite3.connect(temporary, timeout=30)) as connection:
            with connection:
                connection.execute("PRAGMA synchronous=FULL")
                connection.execute("CREATE TABLE retired_jobs (job_id TEXT PRIMARY KEY, record TEXT NOT NULL)")
        # Publish a valid database atomically without replacing another writer's index.
        try:
            os.link(temporary, path)
        except FileExistsError:
            pass
        sync_directory(root)
    finally:
        temporary.unlink(missing_ok=True)


def sync_directory(directory):
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def compact_retired(root, limit=100):
    """Fold older, already removed jobs into the same index in bounded batches."""
    root = Path(root)
    count = 0
    for fence in (root / "staging-admission-closed").glob("*.closed"):
        job_id = fence.name[:-len(".closed")]
        if (root / "jobs" / job_id).exists() or (root / "jobs" / (job_id + ".properties")).exists():
            continue
        try:
            if compact(root, job_id) is not None:
                count += 1
        except (OSError, ValueError, RuntimeError):
            # Unconfirmed legacy records stay in place; another job can still finish.
            continue
        if count >= limit:
            break


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("read", "compact", "compact-restore"))
    parser.add_argument("--root", required=True)
    parser.add_argument("--job-id", required=True)
    args = parser.parse_args()
    root = Path(args.root).absolute()
    result = {"read": read, "compact": compact, "compact-restore": compact_restore}[args.action](root, args.job_id)
    if args.action == "compact":
        compact_retired(root)
    print(json.dumps(result))
