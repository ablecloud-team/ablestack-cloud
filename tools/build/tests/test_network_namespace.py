#!/usr/bin/env python3
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

"""Proxy contract tests; no real SSH or network configuration is performed."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
PROXY = ROOT / "extensions/network-namespace/network-namespace.sh"


@unittest.skipUnless(os.name == "posix", "Requires bash")
class NetworkNamespaceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payload = self.root / "payload.json"
        self.trace = self.root / "ssh.log"
        ssh = self.root / "ssh"
        ssh.write_text('#!/bin/bash\nprintf "%s\\n" "$@" >> "$SSH_TRACE"\n'
                       'while [ "$#" -gt 1 ]; do\n'
                       '  if [ "$1" = -i ]; then cat "$2" > "$SSH_TRACE.key"; fi\n'
                       '  shift\ndone\nexit "${SSH_EXIT:-0}"\n')
        ssh.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.root) + ":" + os.environ["PATH"],
                        SSH_TRACE=str(self.trace))

    def run_proxy(self, details=None, network_id=42, command="ensure-network-device", timeout="60"):
        self.payload.write_text(json.dumps({
            "physical-network-extension-details": details or {"host": "192.0.2.1"},
            "network-extension-details": {}, "payload": {"network_id": network_id},
        }))
        return subprocess.run(["bash", str(PROXY), command, str(self.payload), timeout],
                              env=self.env, text=True, capture_output=True, timeout=15)

    def test_selects_reachable_host(self):
        result = self.run_proxy()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("cs-net-42", json.loads(result.stdout)["namespace"])
        self.assertEqual("192.0.2.1", json.loads(result.stdout)["host"])
        self.assertIn("StrictHostKeyChecking=yes", self.trace.read_text())
        self.assertNotIn("UserKnownHostsFile=/dev/null", self.trace.read_text())

    def test_unreachable_host_fails(self):
        self.env["SSH_EXIT"] = "255"
        self.assertNotEqual(0, self.run_proxy().returncode)

    def test_numeric_port(self):
        self.assertEqual(0, self.run_proxy({"host": "192.0.2.1", "port": 10022}).returncode)
        self.assertIn("10022", self.trace.read_text())

    def test_escaped_multiline_key_and_cleanup(self):
        key = 'test key line 1\nline 2 "quoted"\nline 3'
        self.assertEqual(0, self.run_proxy({"host": "192.0.2.1", "sshkey": key}).returncode)
        self.assertEqual(key + '\n', Path(str(self.trace) + '.key').read_text())
        args = self.trace.read_text().splitlines()
        self.assertFalse(Path(args[args.index('-i') + 1]).exists())

    def test_invalid_inputs_never_reach_ssh(self):
        cases = [{"network_id": "../bad"}, {"command": "bad';touch /tmp/bad"},
                 {"timeout": "1';id"}, {"details": {"port": "65536"}},
                 {"details": {"username": "-oProxyCommand=id"}}]
        for case in cases:
            with self.subTest(case=case):
                self.assertNotEqual(0, self.run_proxy(**case).returncode)
                self.assertFalse(self.trace.exists())

    def test_malformed_json_fails(self):
        self.payload.write_text("not-json")
        result = subprocess.run(["bash", str(PROXY), "ensure-network-device", str(self.payload), "60"],
                                env=self.env, capture_output=True, timeout=15)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.trace.exists())

    def test_shell_syntax(self):
        for path in PROXY.parent.glob("*.sh"):
            subprocess.run(["bash", "-n", str(path)], check=True)


if __name__ == "__main__":
    unittest.main()
