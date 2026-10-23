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

"""Run the remote file installer locally, substituting only the SSH transport."""

import base64
import contextlib
import fcntl
import io
import json
import os
from pathlib import Path
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import veeam_script_deploy as deploy
from test_veeam_host_config import config, FakeApi


RUN = subprocess.run


class VeeamScriptDeploymentTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.etc = self.root / "kvm config"
        self.etc.mkdir()
        self.server = self.root / "VBR scripts 'quoted' $(literal)" / "Job"
        self.args = SimpleNamespace(veeam_script_dir=str(self.server), veeam_host="veeam.test", veeam_ssh_user="administrator",
                                    veeam_ssh_key="", veeam_password="synthetic-password", job_name="Job with spaces",
                                    admin_apikey="synthetic-api-key", admin_secretkey="synthetic-api-secret")
        self.host = {"name": "host-test"}
        for hook in ("pre", "post"):
            (self.etc / ("Job_with_spaces-" + hook + "-notify.sh")).write_text(
                "#!/usr/bin/bash\nexec bash '/etc/ablestack/veeam/hooks/" + hook + "-notify.Job_with_spaces'\n")
        self.requests = []

    def transport(self, arguments, **options):
        self.assertEqual("ssh", arguments[0])
        self.assertEqual("veeam.test", arguments[-2])
        self.assertIn("StrictHostKeyChecking=accept-new", arguments)
        self.assertTrue(options["start_new_session"])
        self.assertEqual(120, options["timeout"])
        for value in (self.args.veeam_password, self.args.admin_apikey, self.args.admin_secretkey):
            self.assertNotIn(value, " ".join(arguments))
            self.assertNotIn(value, options["input"])
        payload = json.loads(options["input"])
        self.requests.append(payload)
        command = shlex.split(arguments[-1])
        self.assertEqual(["python3", "-c"], command[:2])
        return RUN([sys.executable, "-c", "import platform; platform.system=lambda:'Linux'; " + command[2]], input=options["input"], text=True,
                   capture_output=True, timeout=10)

    def upload(self):
        with patch.object(deploy.subprocess, "run", side_effect=self.transport):
            return deploy.deploy_hooks(self.args, self.etc, self.host)

    def test_creates_directory_and_copies_only_generated_wrappers(self):
        (self.etc / "mold-backup.env").write_text("private data")
        (self.etc / "Job_with_spaces.conf").write_text("private data")
        result = self.upload()
        self.assertEqual(2, len(result))
        self.assertTrue(all(entry["changed"] for entry in result))
        for entry in result:
            path = Path(entry["path"])
            self.assertEqual(self.server, path.parent)
            self.assertEqual((self.etc / path.name).read_bytes(), path.read_bytes())
            self.assertEqual(0o755, stat.S_IMODE(path.stat().st_mode))
        self.assertEqual(["Job_with_spaces-pre-notify.sh", "Job_with_spaces-post-notify.sh"],
                         [entry["name"] for entry in self.requests[0]["files"]])
        self.assertFalse((self.server / "mold-backup.env").exists())
        self.assertFalse((self.server / "Job_with_spaces.conf").exists())

    def test_rerun_reuses_files_and_backs_up_only_changed_content(self):
        self.upload()
        paths = sorted(self.server.glob("*.sh"))
        before = {path: (path.read_bytes(), path.stat().st_mtime_ns) for path in paths}
        result = self.upload()
        self.assertTrue(all(not entry["changed"] for entry in result))
        self.assertEqual(before, {path: (path.read_bytes(), path.stat().st_mtime_ns) for path in paths})
        self.assertFalse(list(self.server.glob("*.bak.*")))
        name = "Job_with_spaces-pre-notify.sh"
        (self.etc / name).write_text("#!/bin/bash\nprintf 'new version\\n'\n")
        result = self.upload()
        self.assertEqual([True, False], [entry["changed"] for entry in result])
        backups = list(self.server.glob("*.bak.*"))
        self.assertEqual(1, len(backups))
        self.assertEqual(before[self.server / name][0], backups[0].read_bytes())
        self.assertEqual((self.etc / name).read_bytes(), (self.server / name).read_bytes())

    def test_different_host_or_sanitized_job_collision_is_rejected(self):
        self.upload()
        before = {path: path.read_bytes() for path in self.server.glob("*.sh")}
        self.host["name"] = "other-host"
        with self.assertRaisesRegex(deploy.ScriptDeploymentError, "another Host or Job"):
            self.upload()
        self.host["name"] = "host-test"
        self.args.job_name = "Job/with/spaces"
        with self.assertRaisesRegex(deploy.ScriptDeploymentError, "another Host or Job"):
            self.upload()
        self.assertEqual(before, {path: path.read_bytes() for path in self.server.glob("*.sh")})

    def test_second_file_failure_keeps_first_file_and_retry_finishes(self):
        real_transport = self.transport

        def fail_second(arguments, **options):
            payload = json.loads(options["input"])
            payload["program"] = payload["program"].replace(
                "os.replace(temporary, str(path))", "if path.name.endswith('-post-notify.sh'): raise RuntimeError('injected failure')\n            os.replace(temporary, str(path))")
            options["input"] = json.dumps(payload)
            return real_transport(arguments, **options)

        with patch.object(deploy.subprocess, "run", side_effect=fail_second):
            with self.assertRaisesRegex(deploy.ScriptDeploymentError, "injected failure"):
                deploy.deploy_hooks(self.args, self.etc, self.host)
        self.assertTrue((self.server / "Job_with_spaces-pre-notify.sh").is_file())
        self.assertFalse((self.server / "Job_with_spaces-post-notify.sh").exists())
        self.host["name"] = "another-host"
        with self.assertRaisesRegex(deploy.ScriptDeploymentError, "another Host or Job"):
            self.upload()
        self.host["name"] = "host-test"
        self.assertEqual([False, True], [entry["changed"] for entry in self.upload()])
        self.assertFalse(list(self.server.glob("*.bak.*")))

    def test_directory_lock_blocks_concurrent_deployment(self):
        self.server.mkdir(parents=True)
        with (self.server / ".ablestack-veeam-deploy.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(deploy.ScriptDeploymentError):
                self.upload()
        self.assertFalse(list(self.server.glob("*.sh")))
        self.assertTrue(all(entry["changed"] for entry in self.upload()))

    def test_destination_symlink_is_rejected_without_overwriting_target(self):
        self.server.mkdir(parents=True)
        unrelated = self.root / "unrelated"
        unrelated.write_text("keep")
        (self.server / "Job_with_spaces-post-notify.sh").symlink_to(unrelated)
        with self.assertRaisesRegex(deploy.ScriptDeploymentError, "non-regular"):
            self.upload()
        self.assertEqual("keep", unrelated.read_text())
        self.assertFalse((self.server / "Job_with_spaces-pre-notify.sh").exists())

    def test_os_mismatch_fails_before_creating_the_destination(self):
        def different_os(arguments, **options):
            command = shlex.split(arguments[-1])[2]
            return RUN([sys.executable, "-c", "import platform; platform.system=lambda:'Windows'; " + command],
                       input=options["input"], text=True, capture_output=True, timeout=10)
        with patch.object(deploy.subprocess, "run", side_effect=different_os):
            with self.assertRaisesRegex(deploy.ScriptDeploymentError, "OS does not match"):
                deploy.deploy_hooks(self.args, self.etc, self.host)
        self.assertFalse(self.server.exists())

    def test_invalid_receipt_never_counts_as_success(self):
        def corrupt(arguments, **options):
            result = self.transport(arguments, **options)
            result.stdout = result.stdout.replace(self.requests[-1]["files"][0]["sha256"], "0" * 64)
            return result
        with patch.object(deploy.subprocess, "run", side_effect=corrupt):
            with self.assertRaisesRegex(deploy.ScriptDeploymentError, "Cannot verify"):
                deploy.deploy_hooks(self.args, self.etc, self.host)
        self.assertTrue(all(not entry["changed"] for entry in self.upload()))

    def test_timeout_cleans_askpass_and_remains_retryable(self):
        helpers = []
        def timeout(arguments, **options):
            helper = Path(options["env"]["SSH_ASKPASS"])
            self.assertTrue(helper.is_file())
            self.assertNotIn(self.args.veeam_password, helper.read_text())
            helpers.append(helper)
            raise subprocess.TimeoutExpired(arguments, 120)
        with patch.object(deploy.subprocess, "run", side_effect=timeout):
            with self.assertRaisesRegex(deploy.ScriptDeploymentError, "timed out"):
                deploy.deploy_hooks(self.args, self.etc, self.host)
        self.assertFalse(helpers[0].exists())
        self.assertTrue(all(entry["changed"] for entry in self.upload()))

    def test_ssh_failure_redacts_credentials_from_diagnostics(self):
        error = "\n".join((self.args.veeam_password, self.args.admin_apikey, self.args.admin_secretkey))
        with patch.object(deploy.subprocess, "run", return_value=subprocess.CompletedProcess([], 255, "", error)):
            with self.assertRaises(deploy.ScriptDeploymentError) as caught:
                deploy.deploy_hooks(self.args, self.etc, self.host)
        message = str(caught.exception)
        self.assertNotIn("\n", message)
        for value in (self.args.veeam_password, self.args.admin_apikey, self.args.admin_secretkey):
            self.assertNotIn(value, message)

    def test_key_auth_uses_the_selected_key_without_a_password_helper(self):
        key = self.root / "SSH private key"
        key.write_text("synthetic private key")
        self.args.veeam_ssh_key = str(key)
        with deploy.ssh_auth(self.args) as (arguments, environment):
            self.assertIn(str(key), arguments)
            self.assertIn("BatchMode=yes", arguments)
            self.assertIn("IdentitiesOnly=yes", arguments)
            self.assertNotIn("SSH_ASKPASS", environment)
            self.assertNotIn("ABLESTACK_VEEAM_DEPLOY_PASSWORD", environment)

    def test_windows_uses_a_short_command_and_transfers_paths_as_json(self):
        self.args.veeam_script_dir = "C:/ABLESTACK scripts/팀 'quoted'/Job"
        def windows(arguments, **options):
            self.assertLess(len(arguments[-1]), 2000)
            self.assertNotIn(self.args.veeam_script_dir, arguments[-1])
            encoded = arguments[-1].split()[-1]
            bootstrap = base64.b64decode(encoded).decode("utf-16-le")
            self.assertIn("[Console]::In.ReadToEnd()", bootstrap)
            self.assertIn("[scriptblock]::Create($p.program)", bootstrap)
            payload = json.loads(options["input"])
            self.assertEqual(deploy.WINDOWS_PROGRAM, payload["program"])
            files = [dict(name=entry["name"], sha256=entry["sha256"], changed=True) for entry in reversed(payload["files"])]
            return subprocess.CompletedProcess(arguments, 0, deploy.RESULT_PREFIX + json.dumps(dict(platform="windows", directory=payload["directory"], files=files)), "")
        with patch.object(deploy.subprocess, "run", side_effect=windows):
            result = deploy.deploy_hooks(self.args, self.etc, self.host)
        self.assertTrue(result[0]["path"].endswith("\\Job_with_spaces-pre-notify.sh"))
        self.assertTrue(result[1]["path"].endswith("\\Job_with_spaces-post-notify.sh"))

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell is not installed")
    def test_powershell_installer_round_trip(self):
        # PowerShell's filesystem APIs can also run on a developer/CI Linux or macOS host.
        def powershell(arguments, **options):
            payload = json.loads(options["input"])
            payload["program"] = deploy.WINDOWS_PROGRAM.replace("[Environment]::OSVersion.Platform", "[PlatformID]::Win32NT")
            bootstrap = "$ErrorActionPreference='Stop';try{$p=[Console]::In.ReadToEnd()|ConvertFrom-Json;& ([scriptblock]::Create($p.program))}catch{[Console]::Error.WriteLine($_.Exception.Message);exit 1}"
            return RUN([shutil.which("pwsh"), "-NoProfile", "-NonInteractive", "-EncodedCommand",
                        base64.b64encode(bootstrap.encode("utf-16-le")).decode()], input=json.dumps(payload), text=True, capture_output=True, timeout=15)
        with patch.object(deploy.subprocess, "run", side_effect=powershell):
            first = deploy.deploy_hooks(self.args, self.etc, self.host)
            second = deploy.deploy_hooks(self.args, self.etc, self.host)
        self.assertTrue(all(entry["changed"] for entry in first))
        self.assertTrue(all(not entry["changed"] for entry in second))


class VeeamDeploymentOptionsTest(unittest.TestCase):
    def test_accepts_absolute_local_windows_and_linux_directories(self):
        self.assertEqual(("windows", "C:\\ablestack\\veeam\\Job"), deploy.deployment_directory("C:/ablestack/veeam/Job"))
        self.assertEqual(("linux", "/var/lib/veeam/scripts/ablestack/Job"), deploy.deployment_directory("/var/lib/veeam/scripts/ablestack/Job"))

    def test_rejects_roots_relative_paths_traversal_and_unc_shares(self):
        for directory in ("", "relative/Job", "/", "C:/", "C:relative", "/var/../tmp/Job", "C:/foo/../bar",
                          "//server/share/Job", "\\\\server\\share\\Job", "C:/foo:stream", "C:/foo. ", "C:/foo\nbar"):
            with self.subTest(directory=directory), self.assertRaises(deploy.ScriptDeploymentError):
                deploy.deployment_directory(directory)

    def test_install_only_rejects_remote_copy(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            config.parse_args(["--install-only", "--veeam-script-dir", "C:/ablestack/veeam/Job"])


class VeeamDeploymentConfigurationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.locations = {"etc": self.root / "etc", "state": self.root / "state", "units": self.root / "units",
                          "ini": self.root / "veeam.ini", "logs": self.root / "logs"}
        self.locations["ini"].write_text("[scripts]\ntimeoutPrePost=60\n")
        self.api = FakeApi(self.root / "staging")
        self.server = self.root / "VBR" / "Job"
        self.options = ["--job-name", "veeam-ablecube1", "--kvm-hostname", "host-test", "--mold-url", self.api.url,
                        "--admin-apikey", "synthetic-api-key", "--admin-secretkey", "synthetic-api-secret",
                        "--veeam-host", "veeam.test", "--veeam-password", "synthetic-password", "--vm-schedule-backup", "true",
                        "--secret-key-file", str(self.root / "host.key"), "--veeam-script-dir", str(self.server)]

    def run_config(self, transfer=None, options=None):
        # API, systemd and AES provisioning are separate boundaries; keep real file/Hook installation.
        with patch.object(config, "paths", return_value=self.locations), patch.object(config, "require_root"), \
                patch.object(config, "require_cryptography"), patch.object(config.FileInstaller, "secret"), \
                patch.object(config, "MoldApi", return_value=self.api), patch.object(config.os.path, "ismount", return_value=True), \
                patch.object(config.sys.stdin, "isatty", return_value=False), patch.object(config.shutil, "which", return_value="/usr/bin/ssh"), \
                patch.object(config, "systemctl", return_value=True), contextlib.redirect_stderr(io.StringIO()) as output:
            if transfer is None:
                result = config.main(self.options if options is None else options)
            else:
                with patch.object(config, "deploy_hooks", side_effect=transfer):
                    result = config.main(self.options if options is None else options)
        return result, output.getvalue()

    def test_configuration_copies_generated_hooks_and_prints_remote_ui_paths(self):
        def transport(arguments, **options):
            return RUN([sys.executable, "-c", "import platform; platform.system=lambda:'Linux'; " + shlex.split(arguments[-1])[2]],
                       input=options["input"], text=True, capture_output=True, timeout=10)
        with patch.object(deploy.subprocess, "run", side_effect=transport):
            status, output = self.run_config()
        self.assertEqual(0, status, output)
        self.assertIn("Veeam UI Pre-job: " + str(self.server / "veeam-ablecube1-pre-notify.sh"), output)
        self.assertIn("Veeam UI Post-job: " + str(self.server / "veeam-ablecube1-post-notify.sh"), output)
        for hook in ("pre", "post"):
            wrapper = self.server / ("veeam-ablecube1-" + hook + "-notify.sh")
            self.assertEqual((self.locations["etc"] / wrapper.name).read_bytes(), wrapper.read_bytes())
            self.assertIn(str(self.locations["etc"] / "hooks"), wrapper.read_text())
        self.assertFalse((self.server / "veeam-ablecube1.conf").exists())
        self.assertFalse((self.locations["etc"] / "veeam-host-config.py").exists())

    def test_remote_failure_preserves_local_setup_and_retry_reuses_the_offering(self):
        status, output = self.run_config(transfer=deploy.ScriptDeploymentError("injected SSH failure"))
        self.assertEqual(1, status)
        self.assertIn("FAILED at deploy Veeam Pre/Post scripts", output)
        self.assertNotIn("DONE:", output)
        self.assertTrue((self.locations["etc"] / "veeam-ablecube1.conf").is_file())
        self.assertTrue((self.locations["etc"] / "veeam-ablecube1-pre-notify.sh").is_file())
        self.assertEqual(1, len(self.api.offers))
        def finish(*args):
            return [dict(path="C:\\veeam\\pre.sh", changed=False), dict(path="C:\\veeam\\post.sh", changed=True)]
        status, output = self.run_config(transfer=finish)
        self.assertEqual(0, status, output)
        self.assertIn("unchanged and verified", output)
        self.assertEqual(1, len(self.api.offers))

    def test_omitting_option_keeps_manual_copy_and_makes_no_ssh_request(self):
        options = self.options[:-2]
        status, output = self.run_config(transfer=AssertionError("unexpected deployment"), options=options)
        self.assertEqual(0, status, output)
        self.assertIn("--veeam-script-dir enables automatic copy", output)
        self.assertFalse(self.server.exists())

    def test_missing_ssh_key_stops_before_mold_updates_or_offering_creation(self):
        status, output = self.run_config(options=self.options + ["--veeam-ssh-key", str(self.root / "missing-key")])
        self.assertEqual(1, status)
        self.assertIn("--veeam-ssh-key", output)
        self.assertFalse(self.api.calls)
        self.assertFalse(self.locations["etc"].exists())


if __name__ == "__main__":
    unittest.main()
