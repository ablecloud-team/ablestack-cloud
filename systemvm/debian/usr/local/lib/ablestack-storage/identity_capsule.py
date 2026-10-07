# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http: #www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.


"""Protected local identity capsules for atomic rollback and same-VM ROOT upgrades."""
import base64
import hashlib
import json
import os
import re
import stat
import subprocess

MAX_CAPSULE_BYTES = 8 * 1024 * 1024
FILES = {
    "/var/lib/samba/private/passdb.tdb",
    "/var/lib/samba/private/secrets.tdb",
    "/etc/ablestack-storage/secrets/iscsi-acl-secrets.json",
    "/etc/ablestack-storage/smb-managed-identities.json",
    "/etc/ablestack-storage/smb-local-account-provenance.json",
}
ACCOUNT_FILES = {"/etc/passwd", "/etc/group", "/etc/shadow", "/etc/gshadow"}


def regular_file(path, maximum=MAX_CAPSULE_BYTES):
    info = os.stat(path, follow_symlinks=False)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_size > maximum:
        raise ValueError("Identity capsule source is not a protected regular file")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        observed = os.fstat(descriptor)
        if (observed.st_dev, observed.st_ino) != (info.st_dev, info.st_ino):
            raise ValueError("Identity capsule source changed")
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            data = handle.read(maximum + 1)
        if len(data) > maximum:
            raise ValueError("Identity capsule source exceeds limit")
        return data, info
    finally:
        os.close(descriptor)


def encrypt(payload, public_key_pem, scope):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding, rsa
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    public = serialization.load_pem_public_key(public_key_pem.encode())
    if not isinstance(public, rsa.RSAPublicKey) or public.key_size < 2048:
        raise ValueError("Identity capsule requires a supported wrapping public key")
    plain = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    if len(plain) > MAX_CAPSULE_BYTES:
        raise ValueError("Identity capsule exceeds limit")
    key, nonce = AESGCM.generate_key(bit_length=256), os.urandom(12)
    ciphertext = AESGCM(key).encrypt(nonce, plain, scope.encode())
    wrapped = public.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    return {"schemaVersion": 1, "scope": scope, "wrappedKey": base64.b64encode(wrapped).decode(),
            "nonce": base64.b64encode(nonce).decode(), "ciphertext": base64.b64encode(ciphertext).decode(),
            "sha256": hashlib.sha256(ciphertext).hexdigest()}


def decrypt(capsule, private_key_pem, expected_scope):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    if capsule.get("schemaVersion") != 1 or capsule.get("scope") != expected_scope:
        raise ValueError("Identity capsule scope mismatch")
    ciphertext = base64.b64decode(capsule["ciphertext"], validate=True)
    if len(ciphertext) > MAX_CAPSULE_BYTES + 16 or hashlib.sha256(ciphertext).hexdigest() != capsule["sha256"]:
        raise ValueError("Identity capsule integrity mismatch")
    private = serialization.load_pem_private_key(private_key_pem.encode(), password=None)
    key = private.decrypt(base64.b64decode(capsule["wrappedKey"], validate=True),
                          padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    nonce = base64.b64decode(capsule["nonce"], validate=True)
    if len(nonce) != 12:
        raise ValueError("Identity capsule nonce is invalid")
    plain = AESGCM(key).decrypt(nonce, ciphertext, expected_scope.encode())
    return json.loads(plain)


def validate_host_nqn(value):
    if not isinstance(value, str) or not value.startswith("nqn.") or len(value) > 223 or not re.fullmatch(r"[A-Za-z0-9_.:-]+", value):
        raise ValueError("Invalid scoped NVMe host NQN")
    return value


def collect_nvme_hosts(hosts):
    if not isinstance(hosts, list) or len(hosts) > 512 or len(set(hosts)) != len(hosts):
        raise ValueError("Invalid scoped NVMe host collection")
    result = {}
    for host in hosts:
        validate_host_nqn(host)
        root = "/sys/kernel/config/nvmet/hosts/" + host
        if not os.path.isdir(root) or os.path.islink(root):
            raise ValueError("Scoped NVMe authentication host is unavailable")
        entry = {}
        for field in ("dhchap_key", "dhchap_ctrl_key"):
            path = root + "/" + field
            if not os.path.exists(path):
                entry[field] = None
                continue
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            try:
                data = os.read(descriptor, 4097)
            finally:
                os.close(descriptor)
            if len(data) > 4096:
                raise ValueError("NVMe authentication state exceeds its bound")
            entry[field] = data.decode().strip()
        result[host] = entry
    return result


def merge_nvme_identity_payload(desired, protected_hosts):
    """Only existing desired ACLs receive heap-held credentials; no access is added."""
    if not isinstance(desired, dict) or not isinstance(desired.get("subsystems"), list):
        raise ValueError("Protected NVMe replay requires a structured desired payload")
    result = json.loads(json.dumps(desired))
    for subsystem in result["subsystems"]:
        if not isinstance(subsystem, dict) or not isinstance(subsystem.get("hosts", []), list):
            raise ValueError("Protected NVMe replay has invalid host bindings")
        for host in subsystem.get("hosts", []):
            principal = validate_host_nqn(host.get("principal"))
            config = host.get("config") or {}
            credentials = {}
            for flag, field, parameter in (("dhChapEnabled", "dhchap_key", "dhChapKey"),
                                            ("dhChapCtrlEnabled", "dhchap_ctrl_key", "dhChapCtrlKey")):
                if config.get(flag):
                    value = protected_hosts.get(principal, {}).get(field)
                    if not value:
                        raise ValueError("Required protected NVMe authentication state is missing")
                    credentials[parameter] = value
            if credentials:
                host["secrets"] = credentials
            else:
                host.pop("secrets", None)
    return result


def replay_nvme_identity_payload(desired, protected_hosts):
    merged = merge_nvme_identity_payload(desired, protected_hosts)
    result = subprocess.run(["/usr/local/bin/ablestack-storagectl", "nvmeof", "subsystem", "apply", "/dev/stdin"],
                            input=json.dumps(merged), text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=300)
    if result.returncode != 0:
        raise ValueError("Protected NVMe protocol replay failed")
    try:
        observed = json.loads(result.stdout)
    except (ValueError, TypeError):
        raise ValueError("Protected NVMe protocol replay did not return a verified result")
    if not isinstance(observed, dict) or observed.get("success") is not True:
        raise ValueError("Protected NVMe protocol replay was not successful")
    return {"success": True, "nvmeRestored": True}


def collect(names, nvme_hosts=None):
    names = set(names)
    if any(not isinstance(name, str) or name in {"root", "nobody", "daemon", "cloud", "debian"} or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}", name) for name in names):
        raise ValueError("Invalid managed account name")
    files = {}
    for path in sorted(FILES):
        if not os.path.exists(path):
            files[path] = {"absent": True}
            continue
        data, info = regular_file(path)
        if path.endswith(".tdb"):
            # tdbbackup validates and makes a coherent copy while Samba is running.
            suffix = ".epic-capsule-" + os.urandom(8).hex()
            backup = path + suffix
            try:
                subprocess.run(["tdbbackup", "-s", suffix, path], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
                data, _ = regular_file(backup)
            finally:
                try:
                    os.unlink(backup)
                except FileNotFoundError:
                    pass
        files[path] = {"data": base64.b64encode(data).decode(), "mode": stat.S_IMODE(info.st_mode), "uid": 0, "gid": info.st_gid}
    accounts = {}
    for path in sorted(ACCOUNT_FILES):
        data, _ = regular_file(path)
        accounts[path] = [line for line in data.decode().splitlines() if line.split(":", 1)[0] in names]
    for path, records in accounts.items():
        account_merge("", records, os.path.basename(path))
    return {"schemaVersion": 1, "files": files, "accounts": accounts, "nvmeHosts": collect_nvme_hosts(nvme_hosts or [])}


def validate_payload(payload):
    if not isinstance(payload, dict) or set(payload) - {"schemaVersion", "files", "accounts", "nvmeHosts"}:
        raise ValueError("Identity capsule payload shape is invalid")
    if not isinstance(payload.get("files"), dict) or not isinstance(payload.get("accounts"), dict):
        raise ValueError("Identity capsule collections are invalid")
    if payload.get("schemaVersion") != 1 or set(payload.get("files", {})) - FILES or set(payload.get("accounts", {})) - ACCOUNT_FILES:
        raise ValueError("Identity capsule path allow-list mismatch")
    for path, item in payload.get("files", {}).items():
        if item.get("absent") is True:
            if set(item) != {"absent"}:
                raise ValueError("Identity capsule absent-file marker is invalid")
            continue
        if set(item) != {"data", "mode", "uid", "gid"} or type(item.get("mode")) is not int or type(item.get("gid")) is not int or not 0 <= item["gid"] <= 2147483647:
            raise ValueError("Identity capsule file metadata is invalid")
        data = base64.b64decode(item["data"], validate=True)
        mode = int(item.get("mode", 0))
        secret_path = path != "/etc/ablestack-storage/smb-managed-identities.json"
        if len(data) > MAX_CAPSULE_BYTES or item.get("uid") != 0 or not 0 <= mode <= 0o777 or mode & 0o022 or mode & 0o111 or (secret_path and mode & 0o007):
            raise ValueError("Identity capsule file protection is invalid")
    for path, lines in payload.get("accounts", {}).items():
        if not isinstance(lines, list) or len(lines) > 512 or any(not isinstance(line, str) or "\n" in line for line in lines):
            raise ValueError("Identity capsule account records are invalid")
    hosts = payload.get("nvmeHosts", {})
    if not isinstance(hosts, dict) or len(hosts) > 512:
        raise ValueError("Identity capsule NVMe host scope is invalid")
    for host, fields in hosts.items():
        validate_host_nqn(host)
        if not isinstance(fields, dict) or set(fields) != {"dhchap_key", "dhchap_ctrl_key"}:
            raise ValueError("Identity capsule NVMe authentication fields are invalid")
        for key in fields.values():
            if key is not None and (not isinstance(key, str) or len(key) > 4096 or "\n" in key or "\r" in key
                                    or key and not key.startswith("DHHC-1:")):
                raise ValueError("Identity capsule NVMe authentication value is invalid")
    accounts = payload.get("accounts", {})
    users = {line.split(":", 1)[0] for line in accounts.get("/etc/passwd", [])}
    groups = {line.split(":", 1)[0] for line in accounts.get("/etc/group", [])}
    if any(line.split(":", 1)[0] not in users for line in accounts.get("/etc/shadow", [])):
        raise ValueError("Identity capsule shadow record has no scoped account")
    if any(line.split(":", 1)[0] not in groups for line in accounts.get("/etc/gshadow", [])):
        raise ValueError("Identity capsule group secret has no scoped group")
    for path, lines in accounts.items():
        account_merge("", lines, os.path.basename(path))
    return payload


def account_merge(current, records, kind):
    """Keep unrelated OS identities and reject UID/GID/name conflicts before any write."""
    fields = {"passwd": 7, "group": 4, "shadow": 9, "gshadow": 4}
    if kind not in fields:
        raise ValueError("Unsupported account database")
    current_lines = current.splitlines()
    current_by_name = {line.split(":", 1)[0]: line for line in current_lines if line and not line.startswith("#")}
    incoming = {}
    for line in records:
        values = line.split(":")
        name = values[0]
        if len(values) != fields[kind] or name in {"root", "nobody", "daemon", "cloud", "debian"} or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}", name) or name in incoming:
            raise ValueError("Protected or malformed account record")
        if kind == "passwd":
            uid, gid = int(values[2]), int(values[3])
            if not 1000 <= uid <= 2147483647 or uid == 65534 or not 1000 <= gid <= 2147483647 or gid == 65534 or values[5] not in {"/nonexistent", "/home/" + name} or values[6] not in {"/usr/sbin/nologin", "/sbin/nologin"}:
                raise ValueError("Identity capsule account is not a managed non-login identity")
            for other, existing in current_by_name.items():
                parts = existing.split(":")
                if other != name and len(parts) == 7 and int(parts[2]) == uid:
                    raise ValueError("Identity capsule UID is occupied by another account")
            if name in current_by_name:
                old = current_by_name[name].split(":")
                if (old[2], old[3], old[5], old[6]) != (values[2], values[3], values[5], values[6]):
                    raise ValueError("Identity capsule account ownership changed")
        elif kind == "group":
            gid = int(values[2])
            if not 1000 <= gid <= 2147483647 or gid == 65534:
                raise ValueError("Identity capsule group is protected")
            for other, existing in current_by_name.items():
                parts = existing.split(":")
                if other != name and len(parts) == 4 and int(parts[2]) == gid:
                    raise ValueError("Identity capsule GID is occupied by another group")
            if name in current_by_name and current_by_name[name].split(":")[2] != values[2]:
                raise ValueError("Identity capsule group ownership changed")
        incoming[name] = line
    result = [incoming.pop(line.split(":", 1)[0], line) for line in current_lines]
    result.extend(incoming.values())
    return "\n".join(result) + "\n"


def owned_account_records(files):
    """Read only non-secret provenance written when this controller created an identity."""
    records = {path: {} for path in ACCOUNT_FILES}
    local = files.get("/etc/ablestack-storage/smb-local-account-provenance.json", {})
    if local and not local.get("absent"):
        content = json.loads(base64.b64decode(local["data"], validate=True))
        if content.get("schemaVersion") != 1:
            raise ValueError("Local account provenance schema is invalid")
        for path, lines in content.get("accounts", {}).items():
            if path not in {"/etc/passwd", "/etc/group"} or not isinstance(lines, list):
                raise ValueError("Local account provenance path is invalid")
            account_merge("", lines, os.path.basename(path))
            for line in lines:
                records[path][line.split(":", 1)[0]] = line
    managed = files.get("/etc/ablestack-storage/smb-managed-identities.json", {})
    if managed and not managed.get("absent"):
        content = json.loads(base64.b64decode(managed["data"], validate=True))
        for uuid, identity in content.items():
            suffix = str(uuid).replace("-", "")[:20]
            user, group = identity.get("managedUser"), identity.get("managedGroup")
            if user != "sf_u_" + suffix or group != "sf_g_" + suffix:
                raise ValueError("Managed fixed identity provenance is invalid")
            uid, gid = int(identity["ownerUid"]), int(identity["ownerGid"])
            if identity.get("createdByControllerUser") is True:
                line = f"{user}:x:{uid}:{gid}::/nonexistent:/usr/sbin/nologin"
                account_merge("", [line], "passwd");records["/etc/passwd"][user] = line
            if identity.get("createdByControllerGroup") is True:
                line = f"{group}:x:{gid}:"
                account_merge("", [line], "group");records["/etc/group"][group] = line
    return records


def rollback_account_merge(current, records, kind, owned):
    """Remove post-snapshot identities only when their exact provenance still matches."""
    incoming = {line.split(":", 1)[0] for line in records}
    filtered = []
    for line in current.splitlines():
        name = line.split(":", 1)[0]
        if name not in incoming and name in owned:
            if line != owned[name]:
                raise ValueError("Post-snapshot managed identity changed ownership")
            continue
        filtered.append(line)
    return account_merge("\n".join(filtered), records, kind)


def restore_protected(payload, nvme_desired=None):
    validate_payload(payload)
    hosts = payload.get("nvmeHosts", {})
    if hosts and nvme_desired is None:
        raise ValueError("Protected NVMe desired bindings are required before identity writes")
    if nvme_desired is not None:
        merge_nvme_identity_payload(nvme_desired, hosts)  # Prevalidate credentials before account writes.
    file_payload = dict(payload)
    file_payload.pop("nvmeHosts", None)
    restored = restore(file_payload)
    if nvme_desired is not None:
        replay_nvme_identity_payload(nvme_desired, hosts)
        restored["nvmeRestored"] = True
    return restored


def restore(payload):
    validate_payload(payload)
    if payload.get("nvmeHosts"):
        raise ValueError("NVMe authentication capsule requires the protected protocol replay path")
    staged = {}
    current_files = {}
    for provenance_path in ("/etc/ablestack-storage/smb-local-account-provenance.json",
                            "/etc/ablestack-storage/smb-managed-identities.json"):
        if os.path.lexists(provenance_path):
            data, _ = regular_file(provenance_path)
            current_files[provenance_path] = {"data": base64.b64encode(data).decode()}
    owned = owned_account_records(current_files)
    # Hash databases contain no stored provenance. Their names are authorized only
    # after the public passwd/group records are checked exactly against provenance.
    for identity_path, secret_path in (("/etc/passwd", "/etc/shadow"), ("/etc/group", "/etc/gshadow")):
        current, _ = regular_file(identity_path)
        observed = {line.split(":", 1)[0]: line for line in current.decode().splitlines()}
        old_names = {line.split(":", 1)[0] for line in payload.get("accounts", {}).get(identity_path, [])}
        created_names = set()
        for name, record in owned[identity_path].items():
            if name not in old_names and name in observed:
                if observed[name] != record:
                    raise ValueError("Post-snapshot identity provenance changed")
                created_names.add(name)
        secrets, _ = regular_file(secret_path)
        owned[secret_path] = {line.split(":", 1)[0]: line for line in secrets.decode().splitlines()
                              if line.split(":", 1)[0] in created_names}
    for path, records in payload.get("accounts", {}).items():
        current, info = regular_file(path)
        merged = rollback_account_merge(current.decode(), records, os.path.basename(path), owned[path])
        staged[path] = (merged.encode(), stat.S_IMODE(info.st_mode), info.st_gid)
    absent = []
    for path, item in payload.get("files", {}).items():
        if os.path.lexists(path):
            regular_file(path)
        if item.get("absent") is True:
            absent.append(path)
            continue
        staged[path] = (base64.b64decode(item["data"], validate=True), int(item["mode"]), int(item["gid"]))
    previous = {}
    temporary = {}
    try:
        for path, (data, mode, gid) in staged.items():
            if os.path.exists(path):
                old, info = regular_file(path)
                previous[path] = (old, stat.S_IMODE(info.st_mode), info.st_gid)
            else:
                previous[path] = None
            parent = os.path.dirname(path)
            if os.path.realpath(parent) != parent:
                raise ValueError("Identity capsule target directory changed")
            os.makedirs(parent, mode=0o700, exist_ok=True)
            import tempfile
            fd, tmp = tempfile.mkstemp(prefix=".identity-", dir=parent)
            temporary[path] = tmp
            os.fchmod(fd, mode)
            os.fchown(fd, 0, gid)
            with os.fdopen(fd, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
        for path in absent:
            if os.path.exists(path):
                old, info = regular_file(path)
                previous[path] = (old, stat.S_IMODE(info.st_mode), info.st_gid)
                os.unlink(path)
        for path, tmp in temporary.items():
            os.replace(tmp, path)
        return {"success": True, "restoredFiles": len(staged)}
    except Exception:
        for path, old in previous.items():
            if old is None:
                try:
                    os.unlink(path)
                except FileNotFoundError:
                    pass
            else:
                data, mode, gid = old
                import tempfile
                fd, tmp = tempfile.mkstemp(prefix=".identity-rollback-", dir=os.path.dirname(path))
                os.fchmod(fd, mode)
                os.fchown(fd, 0, gid)
                with os.fdopen(fd, "wb") as handle:
                    handle.write(data)
                    handle.flush()
                    os.fsync(handle.fileno())
                os.replace(tmp, path)
        raise
    finally:
        for tmp in temporary.values():
            try:
                os.unlink(tmp)
            except FileNotFoundError:
                pass
