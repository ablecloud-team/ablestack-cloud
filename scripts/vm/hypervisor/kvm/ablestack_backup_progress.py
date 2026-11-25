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

"""Internal progress policy for backup data workers; queue/cleanup ownership stays with their coordinators."""
import argparse
import contextlib
import math
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import time

IDLE_TIMEOUT_SECONDS = 30 * 60


class NoProgressError(RuntimeError):
    pass


class ProgressDeadline:
    def __init__(self, label, timeout=IDLE_TIMEOUT_SECONDS, clock=None, warning=None):
        self.label = label
        self.timeout = timeout
        self.clock = clock or time.monotonic
        self.last_progress = self.clock()
        self.value = None
        self.warned = False
        self.warning = warning or (lambda message: print(message, file=sys.stderr, flush=True))

    def observe(self, value):
        if value is None or not math.isfinite(value) or value < 0:
            return
        if self.value is None or value > self.value:
            self.value = value
            self.last_progress = self.clock()
            self.warned = False

    def check(self):
        if self.clock() - self.last_progress < self.timeout:
            return
        if self.value is not None:
            raise NoProgressError("BACKUP_DATA_NO_PROGRESS: %s made no measurable progress for %s seconds" %
                                  (self.label, self.timeout))
        if not self.warned:
            self.warning("BACKUP_PROGRESS_UNAVAILABLE: %s has no reliable progress metric; execution timeout remains in effect" % self.label)
            self.warned = True


def percent(kind, text):
    pattern = r"([0-9]+(?:\.[0-9]+)?)/100%" if kind == "qemu" else r"([0-9]+(?:\.[0-9]+)?)% complete"
    values = [float(value) for value in re.findall(pattern, text)]
    return max((value for value in values if 0 <= value <= 100), default=None)


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)


def warning_file(job, message):
    path = job / "data-progress-warning.properties"
    if message is None:
        path.unlink(missing_ok=True)
    else:
        temporary = path.with_suffix(".tmp")
        temporary.write_text("details=" + message.replace("\n", " ") + "\n")
        os.replace(temporary, path)


def stop_worker(process, grace=30):
    # Stop only this launch's process group. Never release reservations or remove source evidence here.
    if process.poll() is not None:
        return
    with contextlib.suppress(ProcessLookupError):
        os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        with contextlib.suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError("Worker termination is unconfirmed; source protection and reservations must be retained") from exc


def run_command(command, kind, idle=IDLE_TIMEOUT_SECONDS, poll=1):
    """Read native progress independently of a blocked data command, without using sparse file size."""
    process = subprocess.Popen(command, stderr=subprocess.PIPE, start_new_session=True)
    guard = ProgressDeadline(kind + " data command", idle)
    tail = ""
    failure = None
    finalizing = False
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stderr, selectors.EVENT_READ)
            while process.poll() is None:
                for key, _ in selector.select(poll):
                    chunk = os.read(key.fd, 65536)
                    if chunk:
                        sys.stderr.write(chunk.decode(errors="replace"))
                        sys.stderr.flush()
                        tail = (tail + chunk.decode(errors="replace"))[-4096:]
                        completed = percent(kind, tail)
                        # qemu may report 0% throughout lengthy target preallocation.
                        if completed is not None and completed >= 100 and not finalizing:
                            guard = ProgressDeadline(kind + " data finalization", idle)
                            finalizing = True
                        elif completed is not None and completed > 0 and not finalizing:
                            guard.observe(completed)
                    else:
                        selector.unregister(key.fd)
                guard.check()
                if not selector.get_map():
                    time.sleep(poll)
            # Preserve diagnostics from commands that exit before the first polling iteration.
            os.set_blocking(process.stderr.fileno(), False)
            with contextlib.suppress(BlockingIOError):
                remainder = process.stderr.read()
                if remainder:
                    sys.stderr.write(remainder.decode(errors="replace"))
                    sys.stderr.flush()
            result = process.returncode
    except NoProgressError as exc:
        failure = str(exc)
        result = 125  # Keep GNU timeout's 124 distinct from the internal no-progress policy.
    finally:
        stop_worker(process)
        process.stderr.close()
    if failure:
        print(failure, file=sys.stderr, flush=True)
    return result


def supervise(command, job, idle=IDLE_TIMEOUT_SECONDS, poll=1, grace=30):
    job = Path(job)
    process = subprocess.Popen(command, start_new_session=True)
    guard = None
    phase = None
    result = None
    failure = None
    try:
        while process.poll() is None:
            try:
                state = properties(job / "volume-progress.properties")
            except (OSError, ValueError):
                state = None
            if state is not None:
                try:
                    measured = float(state["processedBytes"])
                    if not math.isfinite(measured) or measured < 0:
                        measured = None
                except (KeyError, ValueError):
                    measured = None
                key = (state.get("step"), state.get("volumeIndex"), measured is not None)
                if key != phase:
                    phase = key
                    warning_file(job, None)
                    guard = ProgressDeadline("%s volume %s" % key[:2], idle,
                                             warning=lambda message: (warning_file(job, message),
                                                                      print(message, file=sys.stderr, flush=True)))
                # Admission has its own queue deadline. A source/cleanup phase never inherits a copy timer.
                if key[0] not in {"WAITING", "CLEANUP_SOURCE", "FINALIZING"}:
                    if measured is not None:
                        before = guard.last_progress
                        guard.observe(measured)
                        if guard.last_progress != before:
                            warning_file(job, None)
                    guard.check()
            time.sleep(poll)
        result = process.returncode
    except NoProgressError as exc:
        warning_file(job, str(exc))
        failure = str(exc)
        result = 124
    except InterruptedError:
        result = 143
    finally:
        stop_worker(process, grace)
    if failure:
        print(failure, file=sys.stderr, flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--job-dir")
    mode.add_argument("--kind", choices=("rbd", "qemu"))
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("worker command is required")
    def interrupted(signum, frame):
        raise InterruptedError("Backup worker supervision interrupted")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    return run_command(command, args.kind) if args.kind else supervise(command, args.job_dir)


if __name__ == "__main__":
    sys.exit(main())
