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

"""Authenticated target ACL inputs; plaintext exists only inside the current heap."""
import base64
import fcntl
import os
import stat
import hashlib
import json
import re
import uuid

CREDENTIAL_MAX_PLAIN = 8 * 1024 * 1024
CREDENTIAL_MAX_ENVELOPE = 12 * 1024 * 1024
CREDENTIAL_PROTOCOL_PATHS = {
    "SMB": "desired-state/smb-share-apply.json",
    "ISCSI": "iscsi-targets.json",
    "NVMEOF": "nvmeof-subsystems.json",
}
CREDENTIAL_CANONICAL_PATHS = {
    "sharedfs-network.json", "network-endpoints.json", "posix-directory-policies.json",
    "desired-state/nfs-export-apply.json", *CREDENTIAL_PROTOCOL_PATHS.values(),
}


def credential_sealed_input(path):
    if not isinstance(path,str) or not re.fullmatch(r"/proc/self/fd/[0-9]+",path):
        raise ValueError("Rendered credentials require protected sealed input")
    try:
        descriptor=int(path.rsplit("/",1)[1]);info=os.fstat(descriptor)
        seals=fcntl.fcntl(descriptor,fcntl.F_GET_SEALS)
        required=fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL
        if (not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600
                or seals&required!=required or not os.readlink(path).startswith("/memfd:")):
            raise ValueError("Rendered credentials require protected sealed input")
    except OSError as invalid:
        raise ValueError("Rendered credentials require protected sealed input") from invalid


def credential_json(value):
    def unique(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise ValueError("Credential envelope has duplicate JSON fields")
            result[key] = item
        return result
    def invalid_constant(value):
        raise ValueError("Credential envelope has an invalid JSON number")
    try:
        return json.loads(value.decode("utf-8") if isinstance(value, bytes) else value, object_pairs_hook=unique, parse_constant=invalid_constant)
    except (UnicodeError, TypeError, json.JSONDecodeError) as invalid:
        raise ValueError("Credential envelope is not strict UTF-8 JSON") from invalid


def credential_uuid(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError("Credential reference UUID is not canonical")
    return value


def credential_sha256(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Credential reference checksum is invalid")
    return value


def credential_scope(value):
    if not isinstance(value, dict) or set(value) != {"instanceUuid", "operationUuid", "revision"}:
        raise ValueError("Credential capsule scope is not exact")
    credential_uuid(value["instanceUuid"]);credential_uuid(value["operationUuid"])
    if type(value["revision"]) is not int or value["revision"] < 1:
        raise ValueError("Credential capsule revision is invalid")
    return value


def credential_configuration_sha256(value):
    if not isinstance(value, dict) or set(value) != CREDENTIAL_CANONICAL_PATHS or any(
            item is not None and not isinstance(item, dict) for item in value.values()):
        raise ValueError("Credential capsule requires all seven canonical files")
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def credential_version(value, scope, smb=False):
    keys = {"kind", "artifactUuid", "artifactSha256", "operationUuid"}
    if not isinstance(value, dict) or set(value) != (keys | ({"aclUuids"} if smb else set())):
        raise ValueError("Target credential version fields are not exact")
    if value["kind"] != "TARGET_CREDENTIAL_CAPSULE" or value["operationUuid"] != scope["operationUuid"]:
        raise ValueError("Target credential version belongs to another operation")
    credential_uuid(value["artifactUuid"]);credential_sha256(value["artifactSha256"])
    if smb:
        ids = value["aclUuids"]
        if not isinstance(ids, list) or not ids or len(set(ids)) != len(ids):
            raise ValueError("SMB credential version ACL membership is ambiguous")
        for item in ids:credential_uuid(item)
    return {key: value[key] for key in keys}


def credential_bindings(desired, refs, scope, source_sha):
    credential_scope(scope);credential_configuration_sha256(desired);credential_sha256(source_sha)
    if not isinstance(refs, dict) or not set(refs) <= set(CREDENTIAL_PROTOCOL_PATHS):
        raise ValueError("Credential references contain an unknown protocol")
    bindings = {};versions = [];smb_membership = {}
    inactive = {"Disabled", "Destroyed", "Error"}
    for domain, path in CREDENTIAL_PROTOCOL_PATHS.items():
        payload = desired[path] or {}
        collection = "shares" if domain == "SMB" else "targets" if domain == "ISCSI" else "subsystems"
        rules = "hosts" if domain == "NVMEOF" else "acls"
        provided = refs.get(domain) or {}
        if not isinstance(provided, dict):
            raise ValueError("Credential resource references are not structured")
        declared = set();active_keys = set();active_ids = {};seen_ids = set();share_ids = set()
        for resource in payload.get(collection) or []:
            if not isinstance(resource, dict):
                raise ValueError("Credential resource declaration is invalid")
            resource_id = credential_uuid(resource.get("uuid"))
            if domain == "SMB":
                if resource_id in share_ids:raise ValueError("SMB credential share identity is ambiguous")
                share_ids.add(resource_id);declared.add(resource_id)
            resource_active = payload.get("enabled") is not False and resource.get("state", "Ready") not in inactive
            for acl in resource.get(rules) or []:
                if not isinstance(acl, dict):raise ValueError("Credential ACL declaration is invalid")
                acl_id = credential_uuid(acl.get("uuid"))
                if acl_id in seen_ids:raise ValueError("Credential ACL identity belongs to multiple resources")
                seen_ids.add(acl_id)
                principal = acl.get("principal")
                if not isinstance(principal, str) or not principal:
                    raise ValueError("Credential ACL principal is invalid")
                ref_key = resource_id if domain == "SMB" else str(resource.get("targetName") or "") + "|" + principal
                declared.add(ref_key)
                active = resource_active and acl.get("state", "Ready") not in inactive
                if active:
                    active_keys.add(ref_key)
                    active_ids[acl_id] = {"resourceUuid": resource_id, "refKey": ref_key,
                                          "principalType": acl.get("principalType"), "config": acl.get("config") or {}}
            if domain == "SMB" and resource_active:active_keys.add(resource_id)
        if not set(provided) <= declared:
            raise ValueError("Credential reference belongs to another resource")
        for key in active_keys:
            requires = domain == "SMB" or any(
                row["refKey"] == key and any(row["config"].get(flag) is True for flag in
                    ("chapEnabled", "mutualChapEnabled", "dhChapEnabled", "dhChapCtrlEnabled"))
                for row in active_ids.values())
            if requires and not provided.get(key):
                raise ValueError("Active resource lacks its scoped identity checkpoint reference")
        for key, ref in provided.items():
            fields = {"kind", "operationUuid", "sourceConfigurationSha256"}
            if not isinstance(ref, dict) or set(ref) - {"targetCredentialVersion"} != fields:
                raise ValueError("Source credential checkpoint fields are not exact")
            if ref["kind"] != "IDENTITY_CHECKPOINT" or ref["operationUuid"] != scope["operationUuid"] or ref["sourceConfigurationSha256"] != source_sha:
                raise ValueError("Source credential checkpoint differs from its staged source")
            if "targetCredentialVersion" in ref:
                version = ref["targetCredentialVersion"]
                versions.append(credential_version(version, scope, domain == "SMB"))
                if domain == "SMB":
                    allowed = {acl for acl, row in active_ids.items() if row["refKey"] == key}
                    if not set(version["aclUuids"]) <= allowed:
                        raise ValueError("SMB credential version contains a foreign or inactive share ACL")
                    smb_membership[key] = set(version["aclUuids"])
        for row in active_ids.values():row["reference"] = provided.get(row["refKey"])
        bindings[domain] = active_ids
    return bindings, versions, smb_membership


def credential_envelope(value, expected_scope):
    keys = {"schemaVersion", "scope", "wrappedKey", "nonce", "ciphertext", "sha256"}
    if not isinstance(value, dict) or set(value) != keys or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1 or value["scope"] != expected_scope:
        raise ValueError("Credential encrypted envelope fields or AAD scope differ")
    decoded = {}
    for field in ("wrappedKey", "nonce", "ciphertext"):
        if not isinstance(value[field], str):raise ValueError("Credential encrypted field is invalid")
        try:decoded[field] = base64.b64decode(value[field], validate=True)
        except (ValueError, TypeError) as invalid:raise ValueError("Credential encrypted field is not strict base64") from invalid
    if len(decoded["nonce"]) != 12 or not 16 <= len(decoded["ciphertext"]) <= CREDENTIAL_MAX_PLAIN + 16:
        raise ValueError("Credential encrypted envelope size is invalid")
    if hashlib.sha256(decoded["ciphertext"]).hexdigest() != credential_sha256(value["sha256"]):
        raise ValueError("Credential encrypted ciphertext checksum differs")
    return decoded


def credential_recovery_key(saved, private_pem, scope, source_sha, checkpoint_ref):
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization
    if (not isinstance(saved, dict) or set(saved) != {"schemaVersion", "scope", "sourceConfigurationSha256", "publicKey", "capsule"}
            or type(saved["schemaVersion"]) is not int or saved["schemaVersion"] != 1 or saved["scope"] != scope
            or saved["sourceConfigurationSha256"] != source_sha):
        raise ValueError("Durable identity checkpoint differs from its exact source scope")
    credential_scope(saved["scope"])
    if not isinstance(saved["capsule"], dict):raise ValueError("Identity checkpoint ciphertext is invalid")
    if (not isinstance(checkpoint_ref, dict) or set(checkpoint_ref) != {"operationUuid", "sha256"}
            or checkpoint_ref["operationUuid"] != scope["operationUuid"] or checkpoint_ref["sha256"] != saved["capsule"].get("sha256")):
        raise ValueError("Identity checkpoint reference differs from its pinned ciphertext")
    aad=scope["instanceUuid"]+":"+scope["operationUuid"]
    source_envelope=credential_envelope(saved["capsule"],aad)
    if not isinstance(private_pem, str) or len(private_pem) > 16384 or not isinstance(saved["publicKey"], str) or len(saved["publicKey"]) > 16384:
        raise ValueError("Protected recovery key transport is invalid")
    try:
        private = serialization.load_pem_private_key(private_pem.encode(), password=None)
        public = serialization.load_pem_public_key(saved["publicKey"].encode())
    except (TypeError, ValueError) as invalid:raise ValueError("Protected recovery key transport is invalid") from invalid
    if not isinstance(private, rsa.RSAPrivateKey) or not isinstance(public, rsa.RSAPublicKey) or min(private.key_size, public.key_size) < 2048:
        raise ValueError("Protected recovery key algorithm is unsupported")
    if private.public_key().public_numbers() != public.public_numbers():
        raise ValueError("Protected recovery key differs from its pinned public key")
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    try:
        source_key=private.decrypt(source_envelope["wrappedKey"],padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))
        if len(source_key)!=32:raise ValueError("Invalid source encryption key")
        plain=AESGCM(source_key).decrypt(source_envelope["nonce"],source_envelope["ciphertext"],aad.encode())
        source_payload=credential_json(plain)
        if not isinstance(source_payload,dict) or type(source_payload.get("schemaVersion")) is not int or source_payload["schemaVersion"]!=1:
            raise ValueError("Invalid source identity schema")
    except Exception as invalid:
        raise ValueError("Source identity checkpoint authentication failed before activation") from invalid
    return private


def credential_target_inputs(request, desired, refs, saved, source_sha, source_only=False):
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    scope = credential_scope({key: request[key] for key in ("instanceUuid", "operationUuid", "revision")})
    bindings, versions, smb_membership = credential_bindings(desired, refs, scope, source_sha)
    private = credential_recovery_key(saved, request.get("checkpointPrivateKey"), scope, source_sha, request.get("identityCheckpointRef"))
    transient = request.get("transientCredentials")
    if transient is not None and (not isinstance(transient, dict) or not set(transient) <= {"target", "previous"} or any(value != {} for value in transient.values())):
        raise ValueError("Rendered credentials require one authenticated artifact and no dual-source plaintext")
    artifact = request.get("targetCredentialArtifact")
    if source_only:
        if artifact is not None:raise ValueError("Source-only rollback cannot carry target credential artifacts")
        return {}
    if artifact is None:
        if versions:raise ValueError("Target credential references have no encrypted artifact")
        return {}
    if not isinstance(artifact, dict) or set(artifact) != {"artifactUuid", "artifactSha256", "encodedCapsule"}:
        raise ValueError("Target credential artifact transport fields are not exact")
    artifact_id = credential_uuid(artifact["artifactUuid"]);raw_sha = credential_sha256(artifact["artifactSha256"])
    encoded = artifact["encodedCapsule"]
    if not isinstance(encoded, str) or len(encoded) > (CREDENTIAL_MAX_ENVELOPE + 2) // 3 * 4:
        raise ValueError("Target credential artifact exceeds its protected size limit")
    try:raw = base64.b64decode(encoded, validate=True)
    except (TypeError, ValueError) as invalid:raise ValueError("Target credential artifact is not strict base64") from invalid
    if len(raw) > CREDENTIAL_MAX_ENVELOPE or hashlib.sha256(raw).hexdigest() != raw_sha:
        raise ValueError("Target credential artifact raw-byte checksum differs")
    expected_ref = {"kind": "TARGET_CREDENTIAL_CAPSULE", "artifactUuid": artifact_id, "artifactSha256": raw_sha, "operationUuid": scope["operationUuid"]}
    if not versions or any(version != expected_ref for version in versions):
        raise ValueError("Target credential artifact differs from its staged version reference")
    aad = scope["instanceUuid"] + ":" + scope["operationUuid"]
    envelope = credential_envelope(credential_json(raw), aad)
    try:
        key = private.decrypt(envelope["wrappedKey"], padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        if len(key) != 32:raise ValueError("Target credential encryption key has an invalid length")
        plain = AESGCM(key).decrypt(envelope["nonce"], envelope["ciphertext"], aad.encode())
    except Exception as invalid:
        raise ValueError("Target credential artifact authentication failed") from invalid
    if len(plain) > CREDENTIAL_MAX_PLAIN:raise ValueError("Target credential plaintext exceeds its protected size limit")
    payload = credential_json(plain)
    if (not isinstance(payload, dict) or set(payload) != {"schemaVersion", "kind", "scope", "configurationDesiredState", "credentials"}
            or type(payload["schemaVersion"]) is not int or payload["schemaVersion"] != 1 or payload["kind"] != "RENDERED_TARGET_CREDENTIALS"
            or credential_scope(payload["scope"]) != scope
            or credential_configuration_sha256(payload["configurationDesiredState"]) != credential_configuration_sha256(desired)):
        raise ValueError("Target credential capsule differs from its exact target declaration")
    inputs = payload["credentials"]
    if not isinstance(inputs, dict) or not inputs or not set(inputs) <= set(CREDENTIAL_PROTOCOL_PATHS):
        raise ValueError("Target credential protocol inputs are invalid")
    used_smb = {}
    for domain, values in inputs.items():
        if not isinstance(values, dict) or not values:
            raise ValueError("Target credential ACL inputs are invalid")
        for acl_id, secret in values.items():
            credential_uuid(acl_id);row = bindings[domain].get(acl_id)
            if row is None:raise ValueError("Target credential ACL is foreign or inactive")
            version = (row["reference"] or {}).get("targetCredentialVersion")
            if version is None or credential_version(version, scope, domain == "SMB") != expected_ref:
                raise ValueError("Target credential ACL lacks its exact encrypted version reference")
            if domain == "SMB":
                allowed = {"password"} if row["principalType"] == "LOCAL_USER" else set()
                used_smb.setdefault(row["refKey"], set()).add(acl_id)
            elif domain == "ISCSI":
                allowed = {key for flag, key in (("chapEnabled", "chapSecret"), ("mutualChapEnabled", "mutualChapSecret")) if row["config"].get(flag) is True}
            else:
                allowed = {key for flag, key in (("dhChapEnabled", "dhChapKey"), ("dhChapCtrlEnabled", "dhChapCtrlKey")) if row["config"].get(flag) is True}
            if (not isinstance(secret, dict) or not secret or not set(secret) <= allowed
                    or any(not isinstance(value, str) or not value or len(value) > 4096 or any(char in value for char in ("\0", "\r", "\n")) for value in secret.values())):
                raise ValueError("Target credential input differs from its exact active ACL authentication policy")
    if used_smb != smb_membership:
        raise ValueError("Target credential SMB share ACL set differs from its staged encrypted membership")
    return inputs
