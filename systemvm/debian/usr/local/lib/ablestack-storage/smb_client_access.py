# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Share-scoped source policy; never changes accounts, DATA or endpoint topology."""
from pathlib import Path
import base64
import copy
import hashlib
import ipaddress
import json
import re
from shared_directory_policy import (PolicyError, SmbCreationPolicies, checked,
                                     inheritance_protection_version, smb_acl_policy_without_observation, smb_share_options)

VERSION = 1
KEY = "clientAccess"
PREFIX = "SMB_CLIENT_ACCESS_"


def protected():
    return inheritance_protection_version(minimum=10) == 1


def normalize(value):
    if not isinstance(value, list) or len(value) > 32:
        raise PolicyError(PREFIX + "INVALID")
    result = []
    for item in value:
        if not isinstance(item, str):
            raise PolicyError(PREFIX + "INVALID")
        text = item.strip()
        if ":" in text:
            raise PolicyError(PREFIX + "IPV6_UNSUPPORTED")
        if not re.fullmatch(r"(?:0|[1-9][0-9]{0,2})(?:\.(?:0|[1-9][0-9]{0,2})){3}(?:/(?:[1-9]|[12][0-9]|3[0-2]))?", text):
            raise PolicyError(PREFIX + "INVALID")
        try:
            network = ipaddress.IPv4Network(text, strict=False)
            address = ipaddress.IPv4Address(text.split("/")[0])
        except ValueError:
            raise PolicyError(PREFIX + "INVALID")
        if network.prefixlen == 0 or address.is_multicast or (address.is_unspecified and network.prefixlen == 32) or address == ipaddress.IPv4Address("255.255.255.255"):
            raise PolicyError(PREFIX + "INVALID")
        canonical = str(network)
        if canonical in result:
            raise PolicyError(PREFIX + "DUPLICATE")
        result.append(canonical)
    return sorted(result, key=lambda v: (int(ipaddress.IPv4Network(v).network_address), ipaddress.IPv4Network(v).prefixlen))


def policy(config):
    value = (config or {}).get(KEY)
    if value is None and KEY not in (config or {}):
        return []
    if not isinstance(value, dict) or set(value) != {"version", "cidrs"} or type(value.get("version")) is not int or value["version"] != VERSION:
        raise PolicyError(PREFIX + "INVALID")
    return normalize(value["cidrs"])


def parameters(cidrs):
    return {"hosts allow": " ".join(cidrs + ([] if "127.0.0.1/32" in cidrs else ["127.0.0.1/32"])), "hosts deny": "ALL"} if cidrs else {"hosts allow": "", "hosts deny": ""}


def validate_payload(payload):
    if payload.get("enabled") is False:
        return
    restricted = any(policy(row.get("config")) for row in payload.get("shares", []))
    if not restricted:
        return
    if not protected():
        raise PolicyError(PREFIX + "PROTECTION_REQUIRED")
    listeners = payload.get("listeners") or [{"listenIp": payload.get("listenIp") or "0.0.0.0"}]
    for row in listeners:
        try:
            value = ipaddress.ip_address(row.get("listenIp") or "0.0.0.0")
            if value.version != 4 or value.is_unspecified or value.is_multicast:
                raise ValueError()
        except ValueError:
            raise PolicyError(PREFIX + "LISTENER_FAMILY_UNSUPPORTED")


def render(config):
    cidrs = policy(config)
    return ["   " + k + " = " + v for k, v in parameters(cidrs).items()] if cidrs else []


def canonical(payload):
    value = copy.deepcopy(payload)
    for name in ("listenerOnly", "configurationOperationId", "releaseAddresses", "listenerBefore", "listenerOriginalNetwork"):
        value.pop(name, None)
    for row in value.get("listeners", []):
        if row.get("state") in ("Ready", "Updating", "Creating", "Allocated"):
            row["state"] = "Ready"
    identity = value.get("identityDomain") or {}
    identity.pop("healthState", None)
    (identity.get("config") or {}).pop("adJoin", None)
    rows = {}
    for row in value.get("shares", []):
        key = row.get("uuid")
        if not key or key in rows:
            raise PolicyError(PREFIX + "INVALID_INTENT")
        if row.get("state") in ("Ready", "Updating"):
            row["state"] = "Ready"
        # Match the established online-options baseline normalization without discarding ACL fields.
        row.setdefault("config", {}).update(smb_share_options(row.get("config") or {}))
        smb_acl_policy_without_observation(row)
        rows[key] = row
    value["shares"] = rows
    return value


def difference(before, candidate):
    left, right = canonical(before), canonical(candidate)
    if set(left["shares"]) != set(right["shares"]):
        raise PolicyError(PREFIX + "INVALID_INTENT")
    changed = []
    for key in left["shares"]:
        a, b = left["shares"][key], right["shares"][key]
        old, new = policy(a.get("config")), policy(b.get("config"))
        for row in (a, b):
            row.setdefault("config", {}).pop(KEY, None)
        if old != new:
            if a.get("state") != "Ready" or b.get("state") != "Ready":
                raise PolicyError(PREFIX + "INVALID_INTENT")
            changed.append({"uuid": key, "name": a["name"], "before": old, "after": new,
                            "row": next(r for r in before["shares"] if r["uuid"] == key)})
    if left != right or len(changed) > 1:
        raise PolicyError(PREFIX + "INVALID_INTENT")
    return changed[0] if changed else None


class SmbClientAccessPolicies(SmbCreationPolicies):
    @staticmethod
    def _tokens(value):
        return [v for v in re.split(r"[\s,]+", value.strip()) if v]

    def settings(self, name, profiles=None):
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", str(name)):
            raise PolicyError(PREFIX + "INVALID")
        text = self.read_file(self.base)[0].decode()
        if re.search(r"(?im)^\s*(include|copy)\s*=", text):
            raise PolicyError(PREFIX + "SECTION_AMBIGUOUS")
        headers = list(re.finditer(r"(?m)^\s*\[([^]\r\n]+)\]\s*$", text))
        matches = [(i, h) for i, h in enumerate(headers) if h.group(1).casefold() == name.casefold()]
        if len(matches) > 1:
            raise PolicyError(PREFIX + "SECTION_AMBIGUOUS")
        values = []
        for entry in profiles or self.profiles():
            for key in ("hosts allow", "hosts deny"):
                global_value = checked(["testparm", "-s", "--section-name=global", "--parameter-name=" + key, entry["config"]]).decode().strip()
                if global_value:
                    raise PolicyError(PREFIX + "GLOBAL_CONFLICT")
            observed = dict(entry, published=bool(matches))
            for key in ("hosts allow", "hosts deny"):
                observed[key] = self._tokens(checked(["testparm", "-s", "--section-name=" + name, "--parameter-name=" + key, entry["config"]]).decode()) if matches else []
            values.append(observed)
        return values

    def require_family(self, profiles):
        for row in profiles:
            config = row["config"]
            interfaces = self._tokens(checked(["testparm", "-s", "--parameter-name=interfaces", config]).decode())
            bound = checked(["testparm", "-s", "--parameter-name=bind interfaces only", config]).decode().strip().lower()
            if bound not in ("yes", "true") or not interfaces:
                raise PolicyError(PREFIX + "LISTENER_FAMILY_UNSUPPORTED")
            for item in interfaces:
                try:
                    ip = ipaddress.ip_address(item.split("/")[0])
                    if ip.version != 4 or ip.is_unspecified:
                        raise ValueError()
                except ValueError:
                    raise PolicyError(PREFIX + "LISTENER_FAMILY_UNSUPPORTED")
            # Restriction must not have an unverified IPv6 ingress path.
            sockets = checked(["ss", "-Hlnpt6"]).decode()
            if re.search(r"pid=" + str(row["pid"]) + r"\b", sockets):
                raise PolicyError(PREFIX + "LISTENER_FAMILY_UNSUPPORTED")

    def observation(self, row, profiles=None):
        cidrs = policy(row.get("config"))
        profiles = profiles or self.profiles()
        if cidrs:
            if not protected():
                raise PolicyError(PREFIX + "PROTECTION_REQUIRED")
            self.require_family(profiles)
        rows = self.settings(row["name"], profiles)
        required = row.get("publicationRequired")
        if required is None:
            required = ((row.get("config") or {}).get("guestOk") is True or
                        any(a.get("principal") and a.get("state") not in ("Disabled", "Destroyed", "Error") for a in row.get("acls", [])))
        if row.get("state") in ("Disabled", "Destroyed", "Error"):
            required = False
        for item in rows:
            if item["published"] != bool(required):
                raise PolicyError(PREFIX + "PUBLICATION_DRIFT")
            expected = parameters(cidrs)
            if item["published"] and any(item[k] != self._tokens(v) for k, v in expected.items()):
                raise PolicyError(PREFIX + "DRIFT")
        return {"mode": "RESTRICTED" if cidrs else "ANY_SOURCE", "cidrs": cidrs,
                "status": "CONFIG_VERIFIED" if required else "WAITING_FOR_ACL",
                "applyscope": "NEW_CONNECTIONS",
                "profiles": [{"port": r["ports"], "status": "CONFIG_VERIFIED" if required else "WAITING_FOR_ACL"} for r in rows]}

    def preview(self, row, changes):
        if not protected():
            raise PolicyError(PREFIX + "PROTECTION_REQUIRED")
        if not isinstance(changes, dict) or set(changes) - {"cidrs", "confirmAnySource"} or "cidrs" not in changes:
            raise PolicyError(PREFIX + "INVALID")
        old, new = policy(row.get("config")), normalize(changes["cidrs"])
        if old and not new and changes.get("confirmAnySource") is not True:
            raise PolicyError(PREFIX + "CONFIRM_ANY_REQUIRED")
        profiles = self.profiles()
        if new:
            self.require_family(profiles)
        if row.get("verifyCurrent", True):
            current = self.observation(row, profiles)
        else:
            current = {"status": "NOT_CREATED", "mode": "ANY_SOURCE", "cidrs": []}
        raw = self.read_file(self.base)[0]
        return {"before": old, "after": new, "current": current, "profiles": profiles,
                "configurationhash": hashlib.sha256(raw).hexdigest(), "applyscope": "NEW_CONNECTIONS",
                "existingobjectschange": False}

    def snapshot(self, payload):
        before = payload.get("before", {}).get("smb share apply")
        candidate = payload.get("candidate", {}).get("smb share apply")
        if set(payload.get("candidate", {})) != {"smb share apply"} or not isinstance(before, dict):
            raise PolicyError(PREFIX + "INVALID_INTENT")
        validate_payload(candidate)
        change = difference(before, candidate)
        raw, meta = self.read_file(self.base)
        profiles = self.profiles()
        if change is None:
            return {"kind": "SMB_OPTIONS_NOOP", "base": {"content": base64.b64encode(raw).decode(), **meta}, "profiles": profiles}
        saved, desired_meta = self.read_file(self.desired)
        if canonical(json.loads(saved)) != canonical(before):
            raise PolicyError(PREFIX + "BASELINE_CHANGED")
        confirmation = (payload.get("preflight") or {}).get("smbclientaccess", {}).get("confirmAnySource") is True
        preview = self.preview(change["row"], {"cidrs": change["after"], "confirmAnySource": confirmation})
        text = raw.decode()
        headers = list(re.finditer(r"(?m)^\s*\[([^]\r\n]+)\]\s*$", text))
        matches = [(i, h) for i, h in enumerate(headers) if h.group(1).casefold() == change["name"].casefold()]
        if len(matches) > 1:
            raise PolicyError(PREFIX + "SECTION_AMBIGUOUS")
        if matches:
            index, header = matches[0]
            start, end = header.end(), headers[index + 1].start() if index + 1 < len(headers) else len(text)
            section = text[start:end]
            if re.search(r"(?im)^\s*(include|copy)\s*=", section):
                raise PolicyError(PREFIX + "SECTION_AMBIGUOUS")
            for key, value in parameters(change["after"]).items():
                pattern = r"(?im)^[ \t]*" + re.escape(key) + r"[ \t]*=.*$"
                if len(re.findall(pattern, section)) > 1:
                    raise PolicyError(PREFIX + "SECTION_AMBIGUOUS")
                section = re.sub(pattern, "", section)
                if value:
                    section = section.rstrip() + "\n   " + key + " = " + value + "\n"
            text = text[:start] + section + text[end:]
        return {"kind": "SMB_CLIENT_ACCESS", "target": change["name"], "before": change["before"], "after": change["after"],
                "row": change["row"], "base": {"content": base64.b64encode(raw).decode(), **meta},
                "desired": {"content": base64.b64encode(saved).decode(), **desired_meta},
                "candidate": text, "candidateDesired": json.dumps(candidate, sort_keys=True, indent=2) + "\n",
                "profiles": profiles, "preview": preview}

    def verify(self, snapshot, restored=False):
        self.require_profiles(snapshot)
        raw = self.read_file(self.base)[0]
        if snapshot["kind"] == "SMB_OPTIONS_NOOP":
            if raw != base64.b64decode(snapshot["base"]["content"]):
                raise PolicyError(PREFIX + "CONFIG_MISMATCH")
            return True
        expected_bytes = base64.b64decode(snapshot["base"]["content"]) if restored else snapshot["candidate"].encode()
        desired = self.read_file(self.desired)[0]
        desired_expected = base64.b64decode(snapshot["desired"]["content"]) if restored else snapshot["candidateDesired"].encode()
        if raw != expected_bytes or desired != desired_expected:
            raise PolicyError(PREFIX + "CONFIG_MISMATCH")
        row = copy.deepcopy(snapshot["row"])
        row.setdefault("config", {})[KEY] = {"version": VERSION, "cidrs": snapshot["before" if restored else "after"]}
        self.observation(row, snapshot["profiles"])
        return True

    def observations(self, payload):
        result = {}
        for row in payload.get("shares", []):
            try:
                target = dict(row)
                if payload.get("enabled") is False: target["publicationRequired"] = False
                result[row["uuid"]] = self.observation(target)
            except Exception as error:
                result[row.get("uuid", "")] = {"status": "UNKNOWN", "reason": getattr(error, "code", PREFIX + "OBSERVATION_UNAVAILABLE")}
        return result

def verify_payload(payload):
    if payload.get("enabled") is False:
        return
    engine = SmbClientAccessPolicies()
    for row in payload.get("shares", []):
        if KEY in (row.get("config") or {}):
            engine.observation(row)
