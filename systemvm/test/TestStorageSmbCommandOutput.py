#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Production SMB command stdout contract, including an opt-in private Samba SAM."""

import ast
import fcntl
import shutil
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "systemvm/debian/usr/local/bin/ablestack-storagectl"
LIB = ROOT / "systemvm/debian/usr/local/lib/ablestack-storage"
BODY = CLI.read_text().split("apply_smb_shares() {", 1)[1].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
TREE = ast.parse(BODY)
NAMES = {"smb_budget", "run", "run_optional", "ensure_local_smb_user", "smb_unhandled_error"}
FUNCTIONS = "\n\n".join(ast.get_source_segment(BODY, node) for node in TREE.body
                         if isinstance(node, ast.FunctionDef) and node.name in NAMES)
assert {node.name for node in TREE.body if isinstance(node, ast.FunctionDef) and node.name in NAMES} == NAMES


def adapter_types():
    # Execute the actual strict producer-consumer boundary without constructing
    # unrelated host-side credential/configfs observers.
    sys.path.insert(0, str(LIB))
    from rendered_generation import NativeRuntimeFailure, sanitized_runtime_diagnostic
    source = (LIB / "native_render_runtime.py").read_text()
    tree = ast.parse(source)
    cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "NativeRenderedRuntime")
    cls.body = [node for node in cls.body if isinstance(node, ast.FunctionDef) and node.name in {"command", "remaining"}]
    commands = ast.literal_eval(next(node.value for node in tree.body if isinstance(node, ast.Assign)
                                    and any(isinstance(item, ast.Name) and item.id == "RENDER_COMMANDS" for item in node.targets)))
    namespace = dict(RENDER_COMMANDS=commands, json=json, os=os, fcntl=fcntl, Path=Path,
                     subprocess=subprocess, signal=signal, time=time,
                     NativeRuntimeFailure=NativeRuntimeFailure,
                     sanitized_runtime_diagnostic=sanitized_runtime_diagnostic)
    exec(compile(ast.Module(body=[cls], type_ignores=[]), str(LIB / "native_render_runtime.py"), "exec"), namespace)
    return namespace["NativeRenderedRuntime"], NativeRuntimeFailure


class SmbCommandOutputTest(unittest.TestCase):
    def setUp(self):
        self.namespace = dict(subprocess=subprocess, time=time,
                              smb_operation_deadline=time.monotonic() + 60,
                              smb_apply_deadline=time.monotonic() + 60,
                              smb_rollback_active=False)
        selected = [node for node in TREE.body if isinstance(node, ast.FunctionDef) and node.name in {"smb_budget", "run", "run_optional"}]
        exec(compile(ast.Module(body=selected, type_ignores=[]), str(CLI), "exec"), self.namespace)

    def test_successful_tool_noise_is_not_inherited_by_default(self):
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as observed:
            self.namespace["run"](["smbpasswd", "-e", "private-user"])
        options = observed.call_args.kwargs
        self.assertEqual(subprocess.DEVNULL, options["stdout"])
        self.assertEqual(subprocess.DEVNULL, options["stderr"])
        self.assertTrue(options["check"])

    def test_explicit_observation_destinations_and_capture_output_remain_usable(self):
        for options in [{"stdout": subprocess.PIPE, "text": True},
                        {"stdout": subprocess.PIPE, "stderr": subprocess.PIPE, "text": True},
                        {"capture_output": True, "text": True}]:
            with self.subTest(options=options):
                result = self.namespace["run"]([sys.executable, "-c", "print('observed')"], **options)
                self.assertEqual("observed\n", result.stdout)

    def test_optional_command_noise_is_not_inherited_or_leaked_on_failure(self):
        with patch.object(subprocess, "run", side_effect=subprocess.CalledProcessError(1, ["secret-tool"], output=b"private-value")) as observed:
            self.assertIsNone(self.namespace["run_optional"](["secret-tool"]))
        self.assertEqual(subprocess.DEVNULL, observed.call_args.kwargs["stdout"])
        self.assertEqual(subprocess.DEVNULL, observed.call_args.kwargs["stderr"])

    def test_direct_subprocess_observers_in_smb_body_have_no_inherited_output(self):
        for node in ast.walk(TREE):
            if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and isinstance(node.func.value, ast.Name) and node.func.value.id == "subprocess"
                    and node.func.attr == "run"):
                if any(keyword.arg is None for keyword in node.keywords):
                    # The two bounded command wrappers are tested above.
                    continue
                options = {keyword.arg for keyword in node.keywords}
                self.assertTrue("capture_output" in options or {"stdout", "stderr"} <= options,
                                "Uncaptured subprocess at SMB body line " + str(node.lineno))


class SmbStructuredResultTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="smb-stdio-contract-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cli = self.root / "cli"
        self.cli.write_text("#!" + sys.executable + "\n"
                            "import atexit, grp, json, os, pwd, re, subprocess, sys, time\n"
                            "smb_operation_deadline=time.monotonic()+60\n"
                            "smb_apply_deadline=time.monotonic()+60\n"
                            "smb_rollback_active=False\n" + FUNCTIONS + "\n"
                            "sys.excepthook=smb_unhandled_error\n"
                            "value=json.loads(open(sys.argv[-1]).read())\n"
                            # The exit hook uses the real optional-command helper;
                            # failure cleanup cannot append child noise to stdout.
                            "atexit.register(lambda: run_optional([sys.executable,'-c',"
                            "'import sys;print(\"cleanup secret\");print(\"cleanup secret\",file=sys.stderr);sys.exit(1)']))\n"
                            "run([sys.executable,'-c',"
                            "'import sys;print(\"tool secret\");print(\"tool secret\",file=sys.stderr);sys.exit('+str(value['returncode'])+')'])\n"
                            "print(json.dumps({'success':True,'runtimeReady':True}))\n")
        self.cli.chmod(0o700)
        self.adapter, self.failure = adapter_types()

    def direct(self, code):
        descriptor = os.memfd_create("smb-contract-input", os.MFD_CLOEXEC)
        try:
            os.write(descriptor, json.dumps({"returncode": code}).encode())
            result = subprocess.run([str(self.cli), "smb", "share", "apply", "/proc/self/fd/" + str(descriptor)],
                                    capture_output=True, text=True, pass_fds=(descriptor,), timeout=20)
        finally:
            os.close(descriptor)
        return result

    def runtime(self):
        runtime = self.adapter()
        runtime.cli = str(self.cli)
        runtime.deadline = time.monotonic() + 60
        return runtime

    def test_success_is_one_json_for_direct_and_strict_parent(self):
        direct = self.direct(0)
        self.assertEqual(0, direct.returncode)
        self.assertEqual({"success": True, "runtimeReady": True}, json.loads(direct.stdout))
        self.assertEqual("", direct.stderr)
        self.assertEqual({"success": True, "runtimeReady": True},
                         self.runtime().command(("smb", "share", "apply"), {"returncode": 0}))

    def test_failure_is_nonzero_safe_json_for_direct_and_closed_parent_diagnostic(self):
        direct = self.direct(3)
        self.assertNotEqual(0, direct.returncode)
        result = json.loads(direct.stdout)
        self.assertEqual({"success": False, "errorCode": "SMB_APPLY_FAILED",
                          "runtimeDiagnostic": {"schemaVersion": 1, "protocol": "SMB",
                                                "phase": "APPLY", "code": "RUNTIME_APPLY_FAILED"}}, result)
        self.assertEqual("", direct.stderr)
        self.assertNotIn("secret", direct.stdout)
        with self.assertRaises(self.failure) as caught:
            self.runtime().command(("smb", "share", "apply"), {"returncode": 3})
        self.assertEqual("RUNTIME_APPLY_FAILED", caught.exception.runtime_diagnostic["code"])

    def test_strict_parent_still_rejects_polluted_json(self):
        self.cli.write_text("#!" + sys.executable + "\nprint('Added user private-user.')\nprint('{\"success\":true}')\n")
        with self.assertRaises(self.failure) as caught:
            self.runtime().command(("smb", "share", "apply"), {"returncode": 0})
        self.assertEqual("NATIVE_CHILD_OUTPUT_INVALID", caught.exception.runtime_diagnostic["code"])


@unittest.skipUnless(os.environ.get("ABLESTACK_STORAGE_TEST_PRIVATE_SAM") == "1",
                     "requires an isolated disposable container with Samba; never use the host SAM")
class SmbActualPrivateSamTest(unittest.TestCase):
    def setUp(self):
        # Opt-in tests are intentionally container-only. The source is read-only,
        # and Samba state/password material never touch operator account files.
        self.assertTrue(Path("/run/.containerenv").exists() or Path("/.dockerenv").exists())
        self.assertTrue(os.statvfs(ROOT).f_flag & os.ST_RDONLY)
        self.assertIsNotNone(shutil.which("smbpasswd"))
        self.temp = tempfile.TemporaryDirectory(prefix="issue909-private-sam-", dir="/tmp")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("private", "state", "lock", "cache"):
            (self.root / name).mkdir(mode=0o700)
        self.config = self.root / "smb.conf"
        self.config.write_text("[global]\nworkgroup = WORKGROUP\nnetbios name = CONTRACTPROBE\nsecurity = user\n"
                               "passdb backend = tdbsam:" + str(self.root / "private/passdb.tdb") + "\n" +
                               "".join(name + " directory = " + str(self.root / name) + "\n"
                                       for name in ("private", "state", "lock", "cache")) +
                               "log file = " + str(self.root / "samba.log") + "\nunix password sync = no\n")
        self.cli = self.root / "cli"
        self.cli.write_text("#!" + sys.executable + "\n"
                            "import grp,json,os,pwd,re,subprocess,sys,time\n"
                            "smb_operation_deadline=time.monotonic()+60\n"
                            "smb_apply_deadline=time.monotonic()+60\n"
                            "smb_rollback_active=False\n" + FUNCTIONS + "\n"
                            "sys.excepthook=smb_unhandled_error\n"
                            "value=json.loads(open(sys.argv[-1]).read())\n"
                            "real_run=subprocess.run\n"
                            "def private_run(command,**kwargs):\n"
                            "    assert command[0]=='smbpasswd'\n"
                            "    return real_run([command[0],'-c',value['privateConfig'],*command[1:]],**kwargs)\n"
                            "subprocess.run=private_run\n"
                            # nobody already exists: production creation branch
                            # must never be reached, even inside this container.
                            "ensure_local_smb_user('nobody',value['password'])\n"
                            "print(json.dumps({'success':True,'account':'nobody'}))\n")
        self.cli.chmod(0o700)
        self.adapter, self.failure = adapter_types()
        self.password = secrets.token_urlsafe(24)

    def runtime(self):
        runtime = self.adapter()
        runtime.cli = str(self.cli)
        runtime.deadline = time.monotonic() + 60
        return runtime

    def payload(self):
        return {"privateConfig": str(self.config), "password": self.password}

    def test_real_samba_add_enable_and_repeat_are_strict_json_success(self):
        for _ in range(2):
            self.assertEqual({"success": True, "account": "nobody"},
                             self.runtime().command(("smb", "share", "apply"), self.payload()))
        observed = subprocess.run(["pdbedit", "-s", str(self.config), "-L", "-u", "nobody"],
                                  capture_output=True, text=True, timeout=10)
        self.assertEqual(0, observed.returncode)
        self.assertTrue(observed.stdout.startswith("nobody:"))
        self.assertTrue((self.root / "private/passdb.tdb").exists())
        self.assertFalse(any(self.password.encode() in path.read_bytes()
                             for path in self.root.rglob("*") if path.is_file()))

    def test_real_samba_failure_is_structured_nonzero_without_tool_output(self):
        self.config.write_text(self.config.read_text().replace(
            str(self.root / "private/passdb.tdb"), "/proc/issue909-unavailable-private-sam/passdb.tdb"))
        with self.assertRaises(self.failure) as caught:
            self.runtime().command(("smb", "share", "apply"), self.payload())
        self.assertEqual("RUNTIME_APPLY_FAILED", caught.exception.runtime_diagnostic["code"])


SMB_SYSTEMCTL = r'''#!/usr/bin/python3
"""Only process lifecycle is adapted; Samba, sockets and credentials are real."""
import json,os,re,signal,subprocess,sys,time
from pathlib import Path
args=sys.argv[1:];verb=args[0] if args else ""
if any(item in args for item in ("nmbd","nmbd.service")):raise ValueError("rendered manifest does not own NetBIOS")
root=Path("/run/issue909-smb-systemctl");root.mkdir(exist_ok=True)
unit=next((item for item in args if item.startswith("ablestack-storage-smb@") and item.endswith(".service")),None)
def key():
    if not unit:raise ValueError("owned endpoint required")
    value=unit[len("ablestack-storage-smb@"):-len(".service")]
    if not re.fullmatch("[0-9a-f]{24}",value):raise ValueError("foreign endpoint")
    return value
def pid():
    if not unit:return 0
    path=root/(key()+".pid")
    if not path.exists():return 0
    value=int(path.read_text())
    try:return value if Path("/proc/"+str(value)+"/stat").read_text().rpartition(")")[2].split()[0]!="Z" else 0
    except FileNotFoundError:return 0
if verb in ("daemon-reload","disable","enable"):raise SystemExit(0)
if verb=="is-enabled":raise SystemExit(1)
if verb=="is-active":
    if any(item in args for item in ("nmbd","nmbd.service")):raise SystemExit(0)
    raise SystemExit(0 if pid() else 3)
if verb=="show":
    value=pid()
    if "--value" in args:print(value)
    else:
        group=Path("/proc/"+str(value)+"/cgroup").read_text().splitlines()[0].split(":",2)[-1] if value else ""
        print("MainPID="+str(value)+"\nControlGroup="+group)
    raise SystemExit(0)
if verb in ("start","restart","stop"):
    value=pid()
    if value:
        os.kill(value,signal.SIGTERM)
        for _ in range(100):
            if not pid():break
            time.sleep(.03)
        else:os.kill(value,signal.SIGKILL)
    path=root/(key()+".pid")
    if verb=="stop":path.write_text("0");raise SystemExit(0)
    process=subprocess.Popen(["/usr/local/bin/ablestack-storagectl","smb","endpoint-run",key()],
        stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True,close_fds=True)
    path.write_text(str(process.pid))
    raise SystemExit(0)
raise ValueError("unsupported lifecycle adapter verb")
'''


@unittest.skipUnless(os.environ.get("ABLESTACK_STORAGE_TEST_SMB_CLI") == "1",
                     "requires an isolated disposable container with real smbd and SMB client")
class SmbActualCliLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import hashlib
        if (os.geteuid() != 0 or not Path("/run/issue909-smb-cli-isolated").is_file()
                or not (Path("/run/.containerenv").exists() or Path("/.dockerenv").exists())
                or not os.statvfs(ROOT).f_flag & os.ST_RDONLY):
            raise RuntimeError("full SMB CLI test requires the owned disposable container")
        cls.cli = Path("/usr/local/bin/ablestack-storagectl")
        cls.pin = hashlib.sha256(CLI.read_bytes()).hexdigest()
        if hashlib.sha256(cls.cli.read_bytes()).hexdigest() != cls.pin:
            raise RuntimeError("full SMB CLI differs from read-only source")
        cls.proof = Path(os.environ["ABLESTACK_SMB_CLI_PROOF"])
        cls.proof.mkdir(parents=True, exist_ok=True)
        shim=Path("/usr/local/bin/systemctl")
        shim.write_text(SMB_SYSTEMCTL)
        shim.chmod(0o755)
        for name in ("/etc/ablestack-storage", "/etc/systemd/system", "/run/ablestack-storage"):
            Path(name).mkdir(parents=True, exist_ok=True, mode=0o700)
        # A different initial machine name exercises the normal first reconcile
        # path while keeping both source and generated files container-private.
        Path("/etc/samba/smb.conf").write_text("[global]\nnetbios name = INITIALSAM\nsecurity = user\npassdb backend = tdbsam\n")
        cls.adapter, cls.failure = adapter_types()

    def test_real_complete_cli_manual_and_strict_parent_apply_authenticate_and_keep_data(self):
        import copy
        import hashlib
        import uuid
        from native_renderers import render_smb_candidate
        store=Path("/var/lib/ablestack-storage/rendered-generations")
        (store/"generations").mkdir(parents=True, mode=0o700)
        store.chmod(0o700)
        instance=str(uuid.uuid4());operation=str(uuid.uuid4());share=str(uuid.uuid4())
        scope={"instanceUuid":instance,"operationUuid":operation,"revision":2}
        target=store/"generations"/operation
        target.mkdir(mode=0o700)
        backing=Path("/srv/ablestack-storage/volumes")/str(uuid.uuid4())/"share"
        backing.mkdir(parents=True)
        retained=b"existing bytes retained through real SMB reconcile\n"
        (backing/"retained.txt").write_bytes(retained)
        password=secrets.token_urlsafe(24)
        payload={"instanceUuid":instance,"enabled":True,"netbiosName":"CLICONTRACT",
                 "listeners":[{"listenIp":"127.0.0.1","port":1445}],
                 "shares":[{"uuid":share,"name":"contract","path":str(backing),"state":"Ready",
                            "config":{"createDirectory":False,"directoryMode":"0770"},
                            "acls":[{"principalType":"LOCAL_USER","principal":"smb909test",
                                     "password":password,"permission":"READ_WRITE"}]}]}
        files=render_smb_candidate(payload,self.cli)
        manifest={"scope":scope,"files":{name:hashlib.sha256(raw.encode()).hexdigest() for name,raw in files.items()},
                  "previousRenderedSha256":None}
        digest=hashlib.sha256(json.dumps(manifest,sort_keys=True,separators=(",",":")).encode()).hexdigest()
        manifest["manifestSha256"]=digest
        for name,raw in {**files,"generation.json":json.dumps(manifest)}.items():
            path=target/name;path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
            path.write_text(raw);path.chmod(0o600)
        (store/"activation.json").write_text(json.dumps({"scope":scope,"phase":"ACTIVATING","targetSha256":digest}))
        (store/"activation.json").chmod(0o600)
        (store/"current").symlink_to("generations/"+operation)
        env={**os.environ,"ABLESTACK_STORAGE_RENDERED_REPLAY":str(target),
             "ABLESTACK_STORAGE_RENDERED_FROM":str(target)}
        descriptor=os.memfd_create("smb-cli-payload",os.MFD_CLOEXEC)
        try:
            os.write(descriptor,json.dumps(payload).encode())
            direct=subprocess.run([str(self.cli),"smb","share","apply","/proc/self/fd/"+str(descriptor)],
                                  capture_output=True,text=True,env=env,pass_fds=(descriptor,),timeout=90)
        finally:os.close(descriptor)
        self.assertEqual(0,direct.returncode,(direct.stdout,direct.stderr))
        result=json.loads(direct.stdout)
        self.assertTrue(result["success"]);self.assertTrue(result["runtimeReady"])
        self.assertEqual("",direct.stderr)
        runtime=self.adapter();runtime.cli=str(self.cli);runtime.deadline=time.monotonic()+180
        repeated=runtime.command(("smb","share","apply"),payload,replay=target,replay_from=target)
        self.assertTrue(repeated["success"])
        self.assertTrue(all(row["listenerOwned"] and row["tcpReady"] for row in repeated["runtimeEndpoints"]))
        client_file=Path("/tmp/issue909-client-read.txt")
        upload=Path("/tmp/issue909-client-created.txt");upload.write_text("written through actual Samba client\n")
        credentials=os.memfd_create("smb-client-authentication",os.MFD_CLOEXEC)
        try:
            os.write(credentials,("username=smb909test\npassword="+password+"\ndomain=CLICONTRACT\n").encode())
            client=subprocess.run(["smbclient","//127.0.0.1/contract","-p","1445","-A","/proc/self/fd/"+str(credentials),
                                   "-c","get retained.txt "+str(client_file)+"; put "+str(upload)+" client-created.txt"],
                                  capture_output=True,text=True,pass_fds=(credentials,),timeout=30)
        finally:os.close(credentials)
        self.assertEqual(0,client.returncode,(client.stdout,client.stderr))
        self.assertEqual(retained,client_file.read_bytes())
        self.assertEqual(upload.read_bytes(),(backing/"client-created.txt").read_bytes())
        self.assertEqual(retained,(backing/"retained.txt").read_bytes())
        # A real producer validation failure remains one fixed nonzero JSON
        # document at the same parent boundary; raw principal data is absent.
        invalid=copy.deepcopy(payload)
        invalid["shares"][0]["acls"][0]["principal"]="invalid principal "+password
        with self.assertRaises(self.failure) as caught:
            runtime.command(("smb","share","apply"),invalid,replay=target,replay_from=target)
        self.assertEqual("RUNTIME_APPLY_FAILED",caught.exception.runtime_diagnostic["code"])
        receipt={"success":True,"cliSha256":self.pin,"actualCompleteCli":True,
                 "actualSambaVersion":subprocess.run(["smbd","--version"],capture_output=True,text=True).stdout.strip(),
                 "actualSambaSamAndPosixAcl":True,"manualWholeStdoutJson":True,
                 "strictParentWholeStdoutJson":True,"secondReconcileSuccess":True,
                 "actualSmbClientAuthenticatedReadWrite":True,"existingDataPreserved":True,
                 "safeStructuredFailure":True,"systemctlOnlyLifecycleAdapter":True,"noUnplannedLegacyNetbiosDaemon":True,
                 "operatorHostAccountsModified":False,"fixturePasswordStoredInFiles":False}
        (self.proof/"smb-cli-lifecycle-public.json").write_text(json.dumps(receipt,indent=2))
        key=next(Path("/etc/ablestack-storage/smb-endpoint-listeners").glob("*.json")).stem
        stopped=subprocess.run(["systemctl","stop","ablestack-storage-smb@"+key+".service"],capture_output=True,text=True)
        self.assertEqual(0,stopped.returncode)


if __name__ == "__main__":
    unittest.main()
