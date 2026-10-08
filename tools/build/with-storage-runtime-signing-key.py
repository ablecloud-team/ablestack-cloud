#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Run a release builder with an Ed25519 private key held only in a memfd."""
import argparse
import os
import re
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument("--key-id", required=True)
p.add_argument("--trusted-key-dir", type=Path, required=True)
p.add_argument("--require-stable", choices=("true", "false"), default="false")
p.add_argument("command", nargs=argparse.REMAINDER)
a = p.parse_args()
if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", a.key_id):
    p.error("Signing key ID is invalid")
if not a.command:
    p.error("A release build command is required")
command = a.command[1:] if a.command[0] == "--" else a.command
private = os.environ.pop("STORAGE_RUNTIME_SIGNING_PRIVATE_KEY", "").encode()
if not private:
    if a.require_stable == "true":
        raise SystemExit("Published runtime bundles require the stable storage_runtime_signing_private_key secret")
    private = subprocess.check_output(["openssl", "genpkey", "-algorithm", "ED25519"], stderr=subprocess.DEVNULL)
    print("Using an ephemeral signing key for this test build", flush=True)
fd = os.memfd_create("storage-runtime-signing-key", os.MFD_CLOEXEC)
try:
    os.fchmod(fd, 0o600)
    os.write(fd, private)
    private = None
    os.lseek(fd, 0, os.SEEK_SET)
    key = f"/proc/self/fd/{fd}"
    public = subprocess.check_output(["openssl", "pkey", "-in", key, "-pubout"],
                                     pass_fds=(fd,), stderr=subprocess.DEVNULL)
    a.trusted_key_dir.mkdir(parents=True, exist_ok=True)
    (a.trusted_key_dir / (a.key_id + ".pem")).write_bytes(public)
    env = dict(os.environ, STORAGE_RUNTIME_SIGNING_PRIVATE_KEY_FILE=key)
    result = subprocess.run(command, env=env, pass_fds=(fd,))
    raise SystemExit(result.returncode)
finally:
    os.close(fd)
