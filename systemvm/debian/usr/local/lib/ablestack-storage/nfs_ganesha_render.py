# Licensed under the Apache License, Version 2.0. See NOTICE.
# http://www.apache.org/licenses/LICENSE-2.0
"""Shared, side-effect-free Ganesha renderer. Preserve existing wire/config bytes."""
import os
import re
import zlib

GANESHA_EXPORT_ID_MIN = 1000
GANESHA_EXPORT_ID_MAX = 65535

def truth(config, key, default):
    value = config.get(key, default) if isinstance(config, dict) else default
    return bool(value)

def int_config(config, key):
    if not isinstance(config, dict) or config.get(key) in (None, ""):
        return None
    try:
        value = int(config.get(key))
    except Exception as exc:
        raise ValueError(f"invalid integer NFS option {key}: {config.get(key)}") from exc
    if value < 0 or value > 65535:
        raise ValueError(f"NFS option {key} must be between 0 and 65535")
    return value

def effective_nfs_export_config(config):
    result = dict(config or {})
    read_only = truth(result, "readOnly", False)
    root_squash = truth(result, "rootSquash", True)
    if read_only or not root_squash:
        return result
    if result.get("anonUid") in (None, ""):
        result["anonUid"] = 65534
    if result.get("anonGid") in (None, ""):
        result["anonGid"] = 65534
    if result.get("posixPolicy") == "PRESERVE_EXISTING_INITIAL":
        return result
    if result.get("ownerUid") in (None, ""):
        result["ownerUid"] = result.get("anonUid")
    if result.get("ownerGid") in (None, ""):
        result["ownerGid"] = result.get("anonGid")
    if result.get("mode") in (None, ""):
        result["mode"] = "0775"
    if result.get("recursivePermission") in (None, ""):
        result["recursivePermission"] = False
    if result.get("posixPolicy") in (None, ""):
        result["posixPolicy"] = "ANONYMOUS_WRITE"
    return result

def safe_export_root_name(value, path):
    value = str(value or "").strip().strip("/")
    if not value and path:
        value = os.path.basename(str(path).rstrip("/"))
    if not value or value in (".", ".."):
        raise ValueError("NFS export name is required")
    value = re.sub(r"[^A-Za-z0-9_.-]+", "-", value)
    value = value.strip(".-")
    if not value:
        raise ValueError("NFS export name is invalid")
    return value

def nfs_export_principal(value):
    value = str(value or "").strip()
    if value in ("0.0.0.0/0", "::/0"):
        return "*"
    if any(ch.isspace() for ch in value):
        raise ValueError(f"invalid NFS export principal: {value}")
    return value

def ganesha_clients(value):
    rendered = nfs_export_principal(value)
    return "*" if rendered == "*" else rendered

def ganesha_squash(all_squash, root_squash):
    if all_squash:
        return "All_Squash"
    return "Root_Squash" if root_squash else "No_Root_Squash"

def ganesha_access(permission, read_only):
    return "RW" if permission == "READ_WRITE" and not read_only else "RO"

def quote_ganesha(value):
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'

def ganesha_raw_literal(value, label):
    token = str(value or "").strip()
    if not token:
        raise ValueError(f"{label} is required")
    if re.search(r"\s", token) or any(ch in token for ch in ['"', "'", ";", "{", "}"]):
        raise ValueError(f"invalid {label}: {token}")
    return token

def ganesha_bind_addr(value):
    return ganesha_raw_literal(value or "0.0.0.0", "Ganesha bind address")

def ganesha_client_list(value):
    rendered = ganesha_clients(value)
    tokens = [item.strip() for item in str(rendered).split(",") if item.strip()]
    if not tokens:
        raise ValueError("Ganesha client list is required")
    return ", ".join(ganesha_raw_literal(item, "Ganesha client") for item in tokens)

def nfs_protocols_for_mode(protocol_mode):
    mode = str(protocol_mode or "V4_ONLY").strip().upper()
    return "3,4" if mode == "V3V4_DUAL" else "4"

def export_id(value):
    span = GANESHA_EXPORT_ID_MAX - GANESHA_EXPORT_ID_MIN + 1
    return GANESHA_EXPORT_ID_MIN + (zlib.crc32(str(value).encode("utf-8")) % span)

def assign_ganesha_export_ids(exports):
    span = GANESHA_EXPORT_ID_MAX - GANESHA_EXPORT_ID_MIN + 1
    used = set()
    assigned = []
    for index, export in enumerate(exports or []):
        rendered = dict(export)
        source = rendered.get("uuid") or rendered.get("name") or rendered.get("path") or index
        candidate = export_id(source)
        for _ in range(span):
            if candidate not in used:
                used.add(candidate)
                rendered["id"] = candidate
                assigned.append(rendered)
                break
            candidate += 1
            if candidate > GANESHA_EXPORT_ID_MAX:
                candidate = GANESHA_EXPORT_ID_MIN
        else:
            raise RuntimeError("No available Ganesha Export_Id values for Storage Service NFS endpoint")
    return assigned

def endpoint_key(ip, port):
    safe_ip = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(ip or "0.0.0.0"))
    return f"{safe_ip}_{int(port)}"

def nfs_export_listener_ports(export_config, default_port, protocol_mode):
    if str(protocol_mode or "V4_ONLY").strip().upper() == "V3V4_DUAL":
        return [2049]
    raw_ports = None
    for key in ("listenerGroupPorts", "listenergroupports", "listenerPorts", "listenerports"):
        if key in export_config:
            raw_ports = export_config.get(key)
            break
    if isinstance(raw_ports, list):
        values = raw_ports
    elif raw_ports is None or str(raw_ports).strip() == "":
        values = []
    else:
        values = str(raw_ports).split(",")
    ports = []
    seen = set()
    for value in values:
        try:
            port = int(str(value).strip())
        except Exception:
            continue
        if 1 <= port <= 65535 and port not in seen:
            seen.add(port)
            ports.append(port)
    if not ports:
        try:
            fallback = int(default_port or 2049)
        except Exception:
            fallback = 2049
        ports.append(fallback)
    return ports

def render_ganesha_export(rendered):
    protocol_mode = str(rendered.get('protocolMode') or 'V4_ONLY').strip().upper()
    anonymous_uid = rendered.get("anonUid")
    anonymous_gid = rendered.get("anonGid")
    lines = [
        "EXPORT {",
        f"    Export_Id = {rendered['id']};",
        f"    Path = {quote_ganesha(rendered['path'])};",
        f"    Pseudo = {quote_ganesha(rendered['pseudo'])};",
        f"    Protocols = {nfs_protocols_for_mode(protocol_mode)};",
        "    Transports = TCP;",
        "    Access_Type = None;",
        "    Squash = Root_Squash;",
        "    SecType = sys;",
        "    FSAL {",
        "        Name = VFS;",
        "    }",
    ]
    if anonymous_uid is not None:
        lines.append(f"    Anonymous_uid = {int(anonymous_uid)};")
    if anonymous_gid is not None:
        lines.append(f"    Anonymous_gid = {int(anonymous_gid)};")
    for client in rendered["clients"]:
        lines.extend([
            "    CLIENT {",
            f"        Clients = {ganesha_client_list(client['clients'])};",
            f"        Access_Type = {client['access']};",
            f"        Squash = {client['squash']};",
        ])
        if client.get("anonUid") is not None:
            lines.append(f"        Anonymous_uid = {int(client['anonUid'])};")
        if client.get("anonGid") is not None:
            lines.append(f"        Anonymous_gid = {int(client['anonGid'])};")
        lines.append("    }")
    lines.append("}")
    return "\n".join(lines)

def render_clients(export, export_config):
    exposed_path = export.get("path")
    rendered_acl_keys = set()
    clients = []
    for acl in export.get("acls", []):
        acl_state = acl.get("state", "Ready")
        if acl_state in ("Disabled", "Destroyed", "Error"):
            continue
        principal = acl.get("principal")
        if not principal:
            continue
        rendered_principal = nfs_export_principal(principal)
        permission = acl.get("permission", "READ_ONLY")
        acl_config = acl.get("config") or {}
        read_write = "rw" if permission == "READ_WRITE" and not truth(export_config, "readOnly", False) else "ro"
        all_squash = truth(acl_config, "allSquash", truth(export_config, "allSquash", False))
        root_squash = "all_squash" if all_squash else ("root_squash" if truth(acl_config, "rootSquash", truth(export_config, "rootSquash", True)) else "no_root_squash")
        sync = "sync" if truth(acl_config, "sync", truth(export_config, "sync", True)) else "async"
        secure = "secure" if truth(acl_config, "secure", truth(export_config, "secure", True)) else "insecure"
        options = [read_write, sync, root_squash, secure, "no_subtree_check"]
        anon_uid = int_config(acl_config, "anonUid")
        anon_gid = int_config(acl_config, "anonGid")
        if anon_uid is None:
            anon_uid = int_config(export_config, "anonUid")
        if anon_gid is None:
            anon_gid = int_config(export_config, "anonGid")
        if anon_uid is not None:
            options.append(f"anonuid={anon_uid}")
        if anon_gid is not None:
            options.append(f"anongid={anon_gid}")
        options = ",".join(options)
        rendered_key = f"{exposed_path} {rendered_principal}"
        if rendered_key in rendered_acl_keys:
            continue
        rendered_acl_keys.add(rendered_key)
        clients.append({
            "clients": ganesha_clients(principal),
            "access": ganesha_access(permission, truth(export_config, "readOnly", False)),
            "squash": ganesha_squash(all_squash, truth(acl_config, "rootSquash", truth(export_config, "rootSquash", True))),
            "anonUid": anon_uid,
            "anonGid": anon_gid,
        })
    if not clients:
        anon_uid = int_config(export_config, "anonUid")
        anon_gid = int_config(export_config, "anonGid")
        clients.append({
            "clients": "*",
            "access": ganesha_access("READ_WRITE", truth(export_config, "readOnly", False)),
            "squash": ganesha_squash(truth(export_config, "allSquash", False), truth(export_config, "rootSquash", True)),
            "anonUid": anon_uid,
            "anonGid": anon_gid,
            "implicit": True,
        })
    return clients


def render_configurations(endpoint_exports, identity_lines):
    result = {}
    for (ip, endpoint_port), exports in sorted(endpoint_exports.items()):
        if not exports: continue
        endpoint_mode = "V3V4_DUAL" if any(str(e.get("protocolMode") or "V4_ONLY").strip().upper() == "V3V4_DUAL" for e in exports) else "V4_ONLY"
        content = [
            "NFS_Core_Param {",
            f"    NFS_Port = {int(endpoint_port)};",
            f"    Bind_addr = {ganesha_bind_addr(ip)};",
            f"    Protocols = {nfs_protocols_for_mode(endpoint_mode)};",
        ]
        if endpoint_mode == "V3V4_DUAL":
            content.append("    mount_path_pseudo = true;")
        else:
            content.extend([
                "    Transports = TCP;",
                "    Enable_NLM = false;",
                "    Enable_RQUOTA = false;",
            ])
        content.extend([
            "}",
            "NFSV4 {",
            "    Grace_Period = 5;",
            *identity_lines,
            "}",
            "EXPORT_DEFAULTS {",
            "    Access_Type = None;",
            f"    Protocols = {nfs_protocols_for_mode(endpoint_mode)};",
            "    Transports = TCP;",
            "    SecType = sys;",
            "}",
        ])
        rendered_exports = assign_ganesha_export_ids(exports)
        content.extend(render_ganesha_export(export) for export in rendered_exports)
        result[(ip, endpoint_port)] = "\n\n".join(content) + "\n"
    return result

def payload_exports(value):
    """Render only; callers separately prove mounted aliases and physical identity."""
    result = {}
    if not value.get("enabled", True):
        return result
    mode = str(value.get("protocolMode") or value.get("protocolmode") or "V4_ONLY").strip().upper()
    if mode not in ("V4_ONLY", "V3V4_DUAL"):
        mode = "V4_ONLY"
    for row in value.get("exports", []):
        if row.get("state", "Ready") in ("Disabled", "Destroyed", "Error") or not row.get("path"):
            continue
        config = effective_nfs_export_config(row.get("config") or {})
        name = safe_export_root_name(row.get("exportPath") or row.get("name"), row["path"])
        if row["path"] != "/export/" + name:
            raise ValueError("NFS_EXPORT_ALIAS_MISMATCH")
        rendered = {"uuid": row["uuid"], "name": row.get("name"), "path": row["path"],
                    "pseudo": "/" + name, "protocolMode": mode,
                    "anonUid": int_config(config, "anonUid"), "anonGid": int_config(config, "anonGid"),
                    "clients": render_clients(row, config)}
        for port in nfs_export_listener_ports(config, value.get("port") or 2049, mode):
            result.setdefault(("0.0.0.0", port), []).append(dict(rendered))
    return result
