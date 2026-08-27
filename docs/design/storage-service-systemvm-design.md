# Storage Service System VM Design

## Purpose

This document captures the design direction for extending the existing
`ablestack-diplo` shared file system capability into a System VM based file and
block storage service platform.

The current SharedFS implementation provides a Storage VM that exposes one NFS
export. The target design expands this into a managed storage service that can
provide:

- NFS exports
- SMB shares, including Active Directory domain join
- iSCSI targets
- NVMe-oF subsystems and namespaces

The design keeps the upstream Apache ABLESTACK SharedFS API path as compatible
as possible and adds independent ABLESTACK APIs for the expanded service model.

## Current Baseline

The current SharedFS feature is built around these components:

- API and model:
  `api/src/main/java/org/apache/cloudstack/storage/sharedfs`
- API commands:
  `api/src/main/java/org/apache/cloudstack/api/command/user/storage/sharedfs`
- Server orchestration:
  `server/src/main/java/org/apache/cloudstack/storage/sharedfs/SharedFSServiceImpl.java`
- Storage VM provider:
  `plugins/storage/sharedfs/storagevm`
- Database:
  `cloud.shared_filesystem`
  `cloud.shared_filesystem_view`
- UI:
  `ui/src/config/section/storage.js`
  `ui/src/views/storage/*SharedFS*.vue`
- System VM setup:
  `systemvm/debian/opt/cloud/bin/setup/sharedfsvm.sh`
  `plugins/storage/sharedfs/storagevm/src/main/resources/conf/fsvm-init.yml`

Current behavior:

1. `createSharedFileSystem` validates the zone, network, disk offering, service
   offering, filesystem type, and account ownership.
2. A `shared_filesystem` row is created in `Allocated` state.
3. A System VM template is deployed as a `sharedfsvm` user VM type.
4. Cloud-init config formats the attached data disk, mounts it at `/export`,
   and exports it through NFS.
5. The resulting backing volume ID and VM ID are recorded in
   `shared_filesystem`, and state transitions to `Ready`.

## Design Principles

- Preserve the existing ABLESTACK SharedFS API as a compatibility surface.
- Build the expanded service as an independent ABLESTACK Storage Service API.
- Use System VM based service instances for protocol daemons and data paths.
- Use QEMU Guest Agent (QGA) as the standard command channel from the
  management server to the service VM.
- Make System VM template contents part of the product: all required runtime
  packages and storage control scripts must be preinstalled.
- Support both newly created backing volumes and operator-selected existing
  ABLESTACK volumes. Existing volumes must be attached, inspected, mounted, and
  exposed without destructive formatting unless the caller explicitly requests
  a force format mode.
- Treat capacity expansion as a first-class workflow: resize the ABLESTACK
  backing volume first, then rescan and grow the filesystem or block namespace
  from inside the Storage Service System VM through QGA.
- Treat file-share backing volume create/import as one logical lifecycle. A new
  backing volume must be created on an explicit primary storage, attached to the
  Storage Service System VM, inspected/formatted/mounted through QGA, and only
  then exposed as a Ready export/share. If any step fails, the partially created
  export/share row must be removed from the normal management surface. A volume
  created specifically by the current operation may be detached and expunged as
  compensation; an operator-selected existing volume must never be deleted by
  rollback.
- Treat ABLESTACK volume creation as asynchronous. When the UI creates a new
  backing volume for a file share, it must wait for `createVolume` job
  completion and confirm that the volume has reached an attachable state
  (`Allocated`, `Ready`, or `Uploaded`) before calling the Storage Service
  export/share API. The engine must also re-check the volume state before
  attaching it to the Storage Service System VM so direct API calls do not race
  the volume lifecycle.
- Treat attached backing-volume device identification as safety critical. The
  System VM must never infer a target device by selecting an arbitrary
  unmounted partition with an existing filesystem. `volume attach inspect` must
  identify the candidate from the ABLESTACK volume UUID/name/size and guest
  block inventory, exclude the root disk and all root-disk children, exclude
  already mounted devices unless they match the requested volume, and reject
  ambiguous candidates. In `FORMAT_EMPTY` mode, only an unmounted block device
  without an existing filesystem may be formatted; swap, root, boot, ISO/ROM,
  and previously mounted data devices are never valid format targets.
- Avoid storing passwords, API secrets, SSH passwords, AD join passwords, CHAP
  secrets, or similar credentials in plaintext files or logs.
- Prefer low-risk incremental implementation: first rebuild NFS on the new
  engine, then add SMB, then block protocols.

## Capacity Unit Policy

Mold follows the ABLESTACK storage API convention for volume and disk offering
sizes:

- Existing API fields such as `size`, `disksize`, and `rootdisksize` are
  documented in many places as `GB`.
- The implementation converts these values with `value * 1024 * 1024 * 1024`.
- `createDiskOffering` explicitly documents `1GB = 1,073,741,824 bytes`.

Therefore, for compatibility with existing Mold and ABLESTACK API behavior,
Storage Service must treat API `size` values for backing ABLESTACK volumes as
integer GiB values even when the legacy API description says `GB`.

Unit rules:

- Existing API compatibility:
  - Keep `createSharedFileSystem.size` and `resizeStorageFileShare.size` as
    integer GiB-compatible values passed through existing `size` semantics.
  - Do not introduce decimal `GB` interpretation for those fields.
- UI wording:
  - Label backing disk size as `Data disk size (GiB)` in English and
    `?곗씠???붿뒪???ш린(GiB)` in Korean for the expanded Storage Service UI.
  - When the UI must remain aligned with existing ABLESTACK wording, the help
    text should explain that Mold API `GB` storage fields are calculated as GiB
    using 1024-based units.
- New quota and protocol capacity fields:
  - Keep API and database storage in bytes: `quota_bytes`, `quotaBytes`,
    `lunSizeBytes`, and `namespaceSizeBytes`.
  - The UI must not ask operators to enter raw bytes by default.
  - The UI should provide a numeric input plus unit selector using IEC units:
    `B`, `MiB`, `GiB`, and `TiB`.
  - The UI converts the selected value to bytes before submitting the API
    request and shows the converted byte value in review text.
- Avoid mixed decimal/binary labels:
  - Do not label 1024-based values as plain `MB`, `GB`, or `TB` in new Storage
    Service UI fields.
  - Use `MiB`, `GiB`, and `TiB` for operator-entered capacity values.

## High-Level Architecture

The expanded platform should introduce a new service model:

- `StorageServiceInstance`
  - A System VM that hosts one or more file/block protocol services.
  - Owns lifecycle, network attachment, account/domain/zone, service offering,
    and VM state.
- `StorageServiceProtocol`
  - Protocol configuration for `NFS`, `SMB`, `ISCSI`, or `NVME_OF`.
  - Tracks enabled state, listen address, port, and protocol-specific options.
- `StorageFileShare`
  - A file share exposed through NFS or SMB.
  - Owns backing volume, filesystem, export/share name, path, quota/size, and
    state.
- `StorageBlockTarget`
  - A block target exposed through iSCSI or NVMe-oF.
  - Owns backing volume, target identifier, LUN/namespace mapping, and state.
- `StorageAccessRule`
  - Access policy for file shares or block targets.
  - Covers NFS CIDR ACLs, SMB local/AD users and groups, iSCSI initiator IQNs
    and CHAP, and NVMe-oF host NQNs.
- `StorageIdentityDomain`
  - Identity integration state, mainly for SMB Active Directory domain join.
  - Tracks domain name, OU, DNS configuration, join state, and health.

The existing SharedFS API can later be implemented as a compatibility facade
over a `StorageServiceInstance` with one NFS `StorageFileShare`.

## API Direction

### Existing API To Preserve

The following upstream SharedFS APIs should remain in their current package and
keep their behavior as much as possible:

- `createSharedFileSystem`
- `listSharedFileSystems`
- `updateSharedFileSystem`
- `startSharedFileSystem`
- `stopSharedFileSystem`
- `restartSharedFileSystem`
- `changeSharedFileSystemDiskOffering`
- `changeSharedFileSystemServiceOffering`
- `destroySharedFileSystem`
- `recoverSharedFileSystem`
- `expungeSharedFileSystem`

These should remain under:

```text
org.apache.cloudstack.api.command.user.storage.sharedfs
```

`changeSharedFileSystemDiskOffering` is retained for API compatibility only.
The current Storage Service UI must not expose it as a service-wide operation,
because a Storage Service can own multiple independently managed backing
volumes.

### New Independent API

New ABLESTACK APIs should be placed under an independent package, for example:

```text
org.apache.cloudstack.api.command.user.storage.dataservice
```

Candidate APIs:

- Instance lifecycle
  - `createStorageServiceInstance`
  - `listStorageServiceInstances`
  - `updateStorageServiceInstance`
  - `startStorageServiceInstance`
  - `stopStorageServiceInstance`
  - `restartStorageServiceInstance`
  - `destroyStorageServiceInstance`
- Protocol management
  - `enableStorageServiceProtocol`
  - `disableStorageServiceProtocol`
  - `updateStorageServiceProtocol`
- Operations
  - `listStorageServiceHealth`
  - `listStorageServiceInventory`
  - `listStorageServiceSessions`
  - `disconnectStorageServiceSession`
  - `listStorageServiceProtocolSummary`
- NFS
  - `createStorageNfsExport`
    - Accepts an optional cleanup marker for UI-created backing volumes. When
      enabled, create/apply failure removes the failed export record and attempts
      to detach and destroy only that newly created volume.
  - `listStorageNfsExports`
  - `updateStorageNfsExport`
  - `deleteStorageNfsExport`
  - `attachStorageVolumeToFileShare`
  - `detachStorageVolumeFromFileShare`
  - `resizeStorageFileShare`
  - `createStorageNfsAcl`
  - `updateStorageNfsAcl`
  - `deleteStorageNfsAcl`
- SMB
  - `createStorageSmbShare`
  - `listStorageSmbShares`
  - `updateStorageSmbShare`
  - `deleteStorageSmbShare`
  - `attachStorageVolumeToFileShare`
  - `detachStorageVolumeFromFileShare`
  - `resizeStorageFileShare`
  - `createStorageSmbAcl`
  - `updateStorageSmbAcl`
  - `deleteStorageSmbAcl`
  - `joinStorageServiceToAdDomain`
  - `leaveStorageServiceFromAdDomain`
  - `listStorageServiceDomainStatus`
- iSCSI
  - `createStorageIscsiTarget`
  - `listStorageIscsiTargets`
  - `updateStorageIscsiTarget`
  - `deleteStorageIscsiTarget`
  - `createStorageIscsiAcl`
  - `updateStorageIscsiAcl`
  - `deleteStorageIscsiAcl`
  - `listStorageIscsiAcls`
- NVMe-oF
  - `createStorageNvmeOfSubsystem`
  - `listStorageNvmeOfSubsystems`
  - `updateStorageNvmeOfSubsystem`
  - `deleteStorageNvmeOfSubsystem`
  - `prepareStorageServiceNvmeOfVm`
  - `createStorageNvmeOfNamespace`
  - `resizeStorageNvmeOfNamespace`
  - `deleteStorageNvmeOfNamespace`
  - `createStorageNvmeOfHostAcl`
  - `deleteStorageNvmeOfHostAcl`
  - `listStorageNvmeOfHostAcls`

## Database Model

Initial tables:

### `storage_service_instance`

Tracks the System VM service instance.

Important columns:

- `id`
- `uuid`
- `name`
- `description`
- `account_id`
- `domain_id`
- `data_center_id`
- `vm_id`
- `service_offering_id`
- `provider`
- `state`
- `created`
- `updated`
- `removed`

### `storage_service_protocol`

Tracks enabled protocol services per instance.

Important columns:

- `id`
- `uuid`
- `instance_id`
- `protocol`
- `enabled`
- `listen_ip`
- `port`
- `state`
- `config_json`

All Storage Service `config_json` columns must be `mediumtext`, not the
default JPA string width. These JSON documents carry endpoint binding, backing
volume mount metadata, authentication settings, runtime capability snapshots,
and service-specific desired state. A 255-byte truncation is a data corruption
defect because it can silently drop fields such as NFS `endpointMode` and
`listenIps`, causing the engine and UI to misrepresent a selected endpoint as
all endpoints. Upgrade scripts and live deployment checks must verify these
columns on:

- `storage_service_protocol.config_json`
- `storage_file_share.config_json`
- `storage_block_target.config_json`
- `storage_access_rule.config_json`
- `storage_identity_domain.config_json`

The API layer must not silently treat an invalid JSON configuration as a normal
default state. List/detail responses should expose configuration validity, log
the invalid row, and recover only protocol-critical fields that can be parsed
unambiguously from the stored raw value. Operators should recreate or update
affected rows after the DB type is corrected, because a truncated JSON payload
may have already lost fields that cannot be reconstructed.

### `storage_file_share`

Tracks NFS exports and SMB shares.

Important columns:

- `id`
- `uuid`
- `instance_id`
- `protocol`
- `name`
- `path`
- `volume_id`
- `filesystem`
- `quota_bytes`
- `state`
- `config_json`

`config_json` should also carry the volume attachment/import policy:

- `volumeMode`
  - `NEW_VOLUME`: ABLESTACK creates the backing volume for this share.
  - `EXISTING_VOLUME`: an existing ABLESTACK volume is attached to the
    Storage Service System VM and exposed.
- `importMode`
  - `MOUNT_EXISTING`: inspect and mount an existing filesystem. This is the
    default for existing volumes.
  - `FORMAT_EMPTY`: format only when no filesystem signature is detected.
    This is used by the UI when it creates a new data volume for a share.
  - `INSPECT_ONLY`: inspect the attached volume and persist runtime metadata
    without mounting it.
  - Destructive force-format is intentionally out of scope for the first
    implementation.
- `mountOptions`
- `partition`
- `filesystemUuid` (canonical); legacy `fsUuid` is accepted only as a read
  alias and is normalized on the next successful explicit inspect/apply.
- `projectQuotaId` for XFS project quota based capacity enforcement.

### `storage_block_target`

Tracks iSCSI targets and NVMe-oF subsystems/namespaces.

Important columns:

- `id`
- `uuid`
- `instance_id`
- `protocol`
- `target_name`
- `lun_or_namespace`
- `volume_id`
- `state`
- `config_json`

For NVMe-oF, `config_json` should distinguish:

- `type`: `subsystem` or `namespace`
- `engine`: `KERNEL_NVMET` or `SPDK`
- `engineState`: `SUPPORTED`, `PLANNED`, or `PREPARATION_REQUIRED`
- `allowAnyHost`
- `backingPath`
- `transport`: initially `tcp`
- `namespaceSizeBytes`

### `storage_access_rule`

Tracks ACLs and protocol-specific access rules.

Important columns:

- `id`
- `uuid`
- `resource_type`
- `resource_id`
- `principal_type`
- `principal`
- `permission`
- `secret_ref`
- `state`
- `config_json`

### `storage_identity_domain`

Tracks SMB identity integration.

Important columns:

- `id`
- `uuid`
- `instance_id`
- `domain_name`
- `organizational_unit`
- `dns_servers`
- `join_state`
- `health_state`
- `config_json`

## QGA Command Channel

All post-boot storage operations should be applied through QGA. Cloud-init
should be limited to bootstrap.

The Mold UI must only submit asynchronous Cloud API requests and poll the async
job result. It must not execute storage commands directly. The actual command
execution path is:

```text
Mold UI
  -> Management Server API
  -> Management Server async job
  -> Mold Host Agent on the host running the Storage Service System VM
  -> QGA guest command/file operation
  -> ablestack-storagectl inside the Storage Service System VM
```

The management server is responsible for validation, desired-state persistence,
job orchestration, and state reconciliation. The host-side Mold Agent is
responsible for interacting with the hypervisor/QGA channel for the specific
Storage Service System VM running on that host.

Command flow:

1. API request is validated.
2. Desired state is stored in the database.
3. An async job starts.
4. The manager locates the host currently running the Storage Service System VM.
5. The manager sends a storage service command to that Mold Host Agent.
6. The Mold Host Agent writes a JSON payload into the service VM through QGA.
7. The Mold Host Agent executes `ablestack-storagectl apply <payload>` through
   QGA `guest-exec`.
8. The Mold Host Agent polls QGA for command completion and reads the result
   JSON.
9. The manager receives the structured result from the Mold Host Agent.
10. Database state, operation status, and event details are updated.

Required management-side and agent-side abstractions:

```text
StorageServiceGuestCommandDispatcher
StorageServiceGuestCommand
StorageServiceGuestCommandResult
StorageServiceHostCommand
StorageServiceHostCommandWrapper
StorageServiceQgaClient
```

The QGA client lives on the Mold Host Agent side and should support:

- guest file write
- guest command execution
- timeout and retry
- masked logging for sensitive payload fields
- structured JSON result parsing

## System VM Runtime

The System VM template must include a storage control tool:

```text
/usr/local/bin/ablestack-storagectl
```

Recommended command model:

```text
ablestack-storagectl apply <payload.json>
ablestack-storagectl health
ablestack-storagectl inventory
ablestack-storagectl nfs export apply <payload.json>
ablestack-storagectl smb share apply <payload.json>
ablestack-storagectl smb domain join <payload.json>
ablestack-storagectl smb domain leave <payload.json>
ablestack-storagectl iscsi target apply <payload.json>
ablestack-storagectl volume attach inspect <payload.json>
ablestack-storagectl filesystem resize <payload.json>
ablestack-storagectl nvmeof prepare <payload.json>
ablestack-storagectl nvmeof subsystem apply <payload.json>
```

The tool should be idempotent:

- Reapplying the same payload should be safe.
- Partial failures should return structured error details.
- Existing OS-level config should be reconciled to desired state.
- Existing-volume import and filesystem resize commands must be non-destructive
  unless the payload explicitly requests a destructive mode.
- All generated files should be under predictable directories, for example:
  - `/etc/exports.d/ablestack-*.exports`
  - `/etc/samba/ablestack-shares.d/*.conf`
  - `/etc/target/ablestack-*.json`
  - `/etc/nvmet/ablestack-*.json`

Storage Service monitoring cache:

- The System VM template must include and enable a lightweight monitoring
  service for Storage Service runtime state. The service should run inside the
  Storage Service System VM and periodically collect protocol health,
  inventory, capacity, and session information into local JSON files.
- The monitoring service avoids expensive on-demand QGA command execution for
  every UI refresh. The Management Server still reaches the System VM through
  the Mold Host Agent and QGA, but status APIs should prefer reading the latest
  cached JSON files instead of running full protocol discovery commands every
  time.
- Recommended service name:
  `ablestack-storage-monitor.service`
- Recommended executable:
  `/usr/local/bin/ablestack-storage-monitor`
- Recommended cache directory:
  `/run/ablestack-storage/monitor`
- Recommended cache files:
  - `health.json`: service, daemon, QGA-visible timestamp, and monitor status
  - `inventory.json`: NFS exports, SMB shares, iSCSI targets, NVMe-oF
    subsystems/namespaces, backing volumes, and protocol object counts
  - `sessions.json`: active client/session view by protocol, best effort for
    NFS
  - `capacity.json`: mounted filesystem usage, quota state, backing volume
    mapping, and block target capacity
  - `errors.json`: last collection errors by collector and protocol
- Cache files must be written atomically by writing to a temporary file and
  renaming it into place. Readers must never see partial JSON.
- Runtime collectors must normalize command output before writing JSON. In
  particular, rendered NFS `exportfs` option strings such as `anonuid\=65534`
  must be normalized to `anonuid=65534` before they are retuned through
  `resultjson`; the management API and UI must never receive JSON text with
  invalid escape sequences.
- Management-server runtime status APIs must also normalize and re-serialize
  `resultjson` with HTML escaping disabled. This prevents otherwise valid
  runtime values such as NFS option strings containing `=` from being emitted
  through the API as invalid backslash escape sequences after double
  serialization.
- Each JSON file must include:
  - `schemaVersion`
  - `generatedAt`
  - `collector`
  - `status`
  - `staleAfterSeconds`
  - `errors`
- The monitoring interval should be configurable. A default interval around
  5-15 seconds is acceptable for UI responsiveness while avoiding excessive
  load inside the System VM.
- The monitor must collect data with low-cost commands where possible, for
  example `systemctl is-active`, `exportfs -v`, `showmount -e localhost`,
  `findmnt`, `df`, `lsblk`, Samba status commands, `targetcli`/LIO state,
  `nvmetcli` or configfs reads, and lightweight session commands.
- `health.json` and `inventory.json` must include the guest-visible IPv4
  addresses and interface/prefix evidence collected inside the System VM.
  The UI must merge this runtime endpoint evidence with ABLESTACK VM/NIC
  metadata and protocol/export desired-state IPs, because Cloud NIC metadata
  can lag or omit ConfigDrive/L2 runtime addresses even when the guest is
  serving storage traffic correctly.
- Expensive protocol discovery should be rate-limited or split into separate
  collectors so a slow SMB, iSCSI, or NVMe-oF command does not block all status
  files.
- The status APIs must check monitor health first:
  - if the monitor service is active and cache freshness is within
    `staleAfterSeconds`, return cached data;
  - if the monitor service is inactive but cache exists, return stale data with
    a clear stale waning;
  - if no cache exists, fall back to targeted QGA command execution only for the
    requested status scope, and mark the response as non-cached;
  - if QGA is unavailable, return the last cached data plus QGA/monitor error
    state when available.
- Desired-state apply commands such as export/share/target creation must still
  execute synchronously through the existing async job -> host Agent -> QGA ->
  `ablestack-storagectl` path. The monitoring cache is a read optimization, not
  the authority for configuration changes.
- After a successful desired-state apply, `ablestack-storagectl` should notify
  or trigger the monitor to refresh the affected collector immediately where
  practical. Otherwise the UI may show the last cached value until the next
  monitor interval.
- The System VM template build must install the monitor executable, systemd
  unit, logrotate or jounald policy, cache directory creation, and service
  enablement. Fresh Storage Service System VMs should start the monitor during
  boot before protocol status APIs are used.
- Sensitive values such as SMB AD passwords, CHAP secrets, and DH-HMAC-CHAP
  keys must never be written to monitor cache files. Cache files may record
  authentication mode and enabled/disabled state only.
- The UI should display cache freshness where useful, especially on status
  tables and common detail status. A stale cache should be visible as a waning
  but should not block operators from seeing the last known state.

### NFSv4-Only Listener Port Group Model

Runtime verification on an existing Storage Service System VM showed that
NFS-Ganesha can isolate NFSv4 exports by listener port group, but the current
System VM runtime does not reliably isolate exports by individual
`listen IP + port` pairs. Even when a generated Ganesha configuration contains
`Bind_addr`, the process may listen on all local service IP addresses for the
configured port. Therefore the first supported NFSv4-only isolation unit is the
listener port group, not the individual IP endpoint.

NFSv4-only rules:

- `storage_service_protocol` rows represent enabled NFS listener port groups.
  A row may also record the service IP that caused the listener to be created,
  but export visibility is controlled by the listener port.
- `StorageFileShare.config_json.endpointMode` must remain
  `LISTENER_GROUP`.
- `StorageFileShare.config_json.listenerGroupPorts` is the authoritative list
  of port groups on which the export is exposed.
- NFSv4-only export create/update APIs must reject `listenIps`; IP selection is
  not an export-level control in this mode.
- NFSv4-only export create/update APIs must validate that every requested
  listener port exists as an enabled NFS protocol row for the same Storage
  Service instance.
- If multiple Storage Service IP addresses are present, an export assigned to
  port `2050` is expected to be reachable on all service IPs at port `2050`.
  UI labels must describe this as a port group, not as a single endpoint.
- Adding a new service IP or listener port must not automatically attach that
  listener port group to existing exports. Existing exports keep their stored
  `listenerGroupPorts` until explicitly updated.
- If a legacy export has no `listenerGroupPorts`, the management server may
  migrate it to the current default NFS listener port during read or update,
  but new create/update requests must not silently fall back when the operator
  made an explicit port-group selection.

Dual-mode rules remain unchanged:

- `V3V4_DUAL` is a service-wide NFS policy.
- Per-export listener port group selection is not supported in dual mode.
- Dual-mode exports are exposed through the service-wide NFS port and the UI
  must keep the export endpoint controls disabled or hidden for this mode.

System VM rendering rules:

- For NFSv4-only, `ablestack-storagectl` groups exports by
  `listenerGroupPorts` and renders one Ganesha configuration/process per port
  group.
- Each Ganesha configuration contains only the exports assigned to that port
  group.
- The runtime success probe must verify that the port is listening and that the
  exports assigned to that port can be mounted by their client-visible root
  names.
- The probe must also verify negative isolation where practical: an export
  assigned only to port `2049` must not be visible through a different managed
  port such as `2050`.

UI rules:

- The NFS tab should show all active service IP addresses separately from the
  NFS listener port groups.
- The NFS export create/edit dialogs must label the selector as
  `NFS listener port group` and list values such as
  `Port 2049 / reachable IPs: 10.10.254.80, 10.10.22.201`.
- The export table should show the selected listener port groups and the
  reachable `IP:port` combinations derived from current service IPs.
- Korean UI text must describe the same behavior without exposing raw i18n
  keys or implying unsupported IP-level export isolation.

## System VM Template Build

Current template build path:

- Packer wrapper:
  `tools/appliance/build.sh`
- Packer definitions:
  `tools/appliance/systemvmtemplate/template-base_*`
- Package installation:
  `tools/appliance/systemvmtemplate/scripts/install_systemvm_packages.sh`
- SystemVM services:
  `tools/appliance/systemvmtemplate/scripts/configure_systemvm_services.sh`
- Runtime files copied into the template:
  `systemvm/debian`

Current package baseline already includes:

- `qemu-guest-agent`
- `cloud-init`
- `nfs-common`
- `nfs-server`
- `xfsprogs`
- `samba-common`
- `cifs-utils`

Required additions for the expanded Storage Service VM:

- Common:
  - `jq`
  - `python3-yaml`
  - `quota`
  - `acl`
  - `parted`
  - `lvm2`
- SMB and AD:
  - `samba`
  - `smbclient`
  - `winbind`
  - `krb5-user`
  - `realmd`
  - `sssd`
  - `adcli`
  - `libnss-winbind`
  - `libpam-winbind`
- iSCSI:
  - `targetcli-fb`
- NVMe-oF:
  - `nvme-cli`
  - `nvmetcli` if available in the target Debian repository
- Monitoring:
  - `ablestack-storage-monitor`
  - `ablestack-storage-monitor.service`
  - `/run/ablestack-storage/monitor` runtime cache directory
  - collectors for health, inventory, sessions, and capacity cache files
  - boot-time service enablement and jounald/log retention policy

Template strategy:

1. Phase 1: add packages and storage control scripts to the common System VM
   template to minimize ABLESTACK template-selection changes.
2. Phase 2: introduce a dedicated `storageservicevmtemplate` build profile if
   image size, security scope, or operational separation requires it.

If a dedicated template is introduced, the manager must stop relying only on
`findSystemVMReadyTemplate(zoneId, hypervisor)` and select the Storage Service
template explicitly.

NVMe-oF template and service-offering strategy:

- `KERNEL_NVMET` mode belongs in the normal Storage Service System VM template.
- `SPDK` mode is kept as a Storage Service extension point, but it must not own
  HugePage, NUMA, CPU pinning, memlock, SR-IOV, or PCI passthrough controls.
  Those controls belong to a future VM Runtime Capability feature in the VM
  creation and management layer.
- Until the VM Runtime Capability feature exists, Storage Service should expose
  only SPDK design metadata and prerequisite reporting. It must reject actual
  SPDK enablement with a clear `PreparationRequired` state that points to the
  missing VM runtime capability support.

## NFS Design

NFS must be upgraded from the legacy fixed `/export` share to first-class export
management. In the expanded Storage Service design, `/export` is not a
user-facing NFS export. It is a legacy SharedFS compatibility root and may be
used only as an internal mount/root directory for existing lifecycle code while
the compatibility layer is being retired.

The Storage Service must never advertise `/export` itself through NFS or SMB.
The client-visible service name is always the protocol object name, not the
internal filesystem path:

- NFS clients mount `<service-ip>:/<export-name>`.
- SMB clients mount `\\<service-ip>\<share-name>`.
- iSCSI clients discover/login to the configured target IQN.
- NVMe-oF clients discover/connect to the configured subsystem NQN.

This is a cross-protocol rule, not an NFS-only rule. Regardless of where the
data is actually stored inside the Storage Service VM, the extenally exposed
root identifier is the operator-defined protocol object name: export name for
NFS, share name for SMB, target IQN for iSCSI, and subsystem NQN for NVMe-oF.
UI connection guidance, API responses intended for operators, and SystemVM
runtime rendering must derive client connection examples from those protocol
object names. Internal backing directories or device paths may be shown for
administration, capacity, and troubleshooting, but they must not be presented
as the client root path.

The internal directory may be `/export/<share-name>` during the SharedFS
compatibility transition, or the preferred long-term path
`/srv/ablestack-storage/.../<share-name>`. Operators may choose the internal
directory, but the UI and API must reject the root path `/export` as an
internal share/export path. They should also normalize and validate
user-provided paths so that an operator cannot accidentally publish the entire
backing filesystem root. The UI must label this field as an internal/backing
directory and show the client-visible root name separately.

Features:

- export create/update/delete
- export enable/disable
- internal export directory management
- client-visible export name management
- export-level backing volume
- export-level capacity limit
- export-level usage reporting
- CIDR/IP ACL management
- `rw` / `ro`
- `root_squash` / `no_root_squash`
- `sync` / `async`
- `secure` / `insecure`
- optional `anonuid` and `anongid`

Capacity strategy:

- Phase 1: one backing ABLESTACK volume per NFS export.
  - The volume size is the hard capacity boundary.
  - Resize maps to ABLESTACK volume resize plus filesystem grow.
  - Snapshot, backup, and delete are straightforward.
- Phase 2: multiple exports on one backing volume with XFS project quota.
  - Better density.
  - More complex reconciliation and recovery.

Runtime implementation:

- Mount export volumes under a controlled root, for example:
  `/srv/ablestack-storage/nfs/<share-uuid>`
- During the SharedFS compatibility transition, the legacy data disk may still
  be mounted at `/export`, but `/export` itself must not be rendered into
  `/etc/exports` or `/etc/exports.d`.
- User-facing NFS exports must be root-level service names, for example:
  `/nfs01` for export name `nfs01`. They must not expose the internal backing
  path such as `/export/nfs01` or
  `/srv/ablestack-storage/nfs/<share-uuid>`.
- `ablestack-storagectl` must create a controlled bind-mount alias from the
  internal backing path to the root-level client-visible export name, and render
  only the alias path into `/etc/exports.d`.
- The alias path is derived from the export name by allowing
  `[A-Za-z0-9_.-]` and normalizing other characters to `-`; names that would
  resolve to `/`, `/export`, or a path under `/export` are invalid.
- NFS ACL principal `0.0.0.0/0` or `::/0` is an operator-facing "allow all"
  CIDR value. Runtime exports must render it as `*` so Linux NFS export
  matching behaves as intended, while API/UI can keep showing the CIDR value
  the operator entered.
- Root Squash must remain the safe default, but the product must make the
  resulting POSIX permission behavior explicit. When `root_squash` is enabled,
  a client-side root process is mapped to the anonymous NFS UID/GID, so a
  `root:root 0755` backing directory is not writable by that client. This is
  correct Linux NFS behavior, not a connection failure.
- For operator-friendly behavior, a read/write NFS export with Root Squash
  enabled defaults to an anonymous-write POSIX profile when the operator does
  not specify POSIX values: `anonUid=65534`, `anonGid=65534`,
  `ownerUid=65534`, `ownerGid=65534`, `mode=0775`, and
  `recursivePermission=false`. The UI must show these effective defaults in
  the export dialog and tables. The SystemVM apply path must also compute the
  same effective defaults for older export configs so an existing export can be
  repaired by reapplying desired state.
- NFS export create/update APIs must support the following permission controls
  in the export config JSON:
  - `rootSquash`
  - `allSquash`
  - `anonUid`
  - `anonGid`
  - `ownerUid`
  - `ownerGid`
  - `mode`
  - `recursivePermission`
- NFS ACL create/update APIs may override the squashing and anonymous UID/GID
  behavior per principal with `rootSquash`, `allSquash`, `anonUid`, and
  `anonGid`.
- `ablestack-storagectl` must apply POSIX owner/mode to the internal backing
  directory before rendering `exportfs` rules. Recursive application is an
  explicit operator choice because it can be expensive and destructive on
  existing data.
- A recommended write-compatible profile for root-squashed test clients is:
  `rootSquash=true`, `anonUid=65534`, `anonGid=65534`, backing directory owner
  set to the same UID/GID, and a mode such as `0770` or `0775` depending on the
  sharing policy. The default profile uses `0775`.
  If every client identity should be collapsed to one shared identity, use
  `allSquash=true` with the same anonymous UID/GID instead of disabling Root
  Squash.
- `no_root_squash` is allowed only as an explicit advanced choice and the UI
  must make clear that it trusts client-side root.
- NFS protocol mode is explicit and per export. The UI must support `V4_ONLY`
  and `V3V4_DUAL` at SharedFS create time, NFS export create time, and NFS
  export edit time. `V4_ONLY` is the default.
- NFS listener ports are endpoint-level values, not export-owned values. The
  UI should keep port editing under protocol enablement/listen-IP management,
  while export rows only display the listener port that currently serves them.
- The NFS protocol selector must appear in the initial SharedFS wizard, the
  NFS export creation modal, and the NFS export edit modal. The modal default
  is `V4_ONLY`, and the selected mode must be persisted in the export config
  so the SystemVM can render the matching Ganesha protocol set at runtime.
- Protocol enablement may request a listen IP that is not currently configured
  on the Storage Service VM. The UI should offer "existing IP" and "new IP"
  modes. The final runtime validator is `ablestack-storagectl`: it must inspect
  current VM NIC IPv4 addresses, verify the requested listen IP is in the same
  CIDR as an existing NIC, and add it as a secondary address before applying
  the protocol desired state.
- Render export rules into:
  `/etc/exports.d/ablestack-<share-uuid>.exports`
- Do not render legacy cloud-init or SharedFS-generated `/export` rules when
  the expanded Storage Service feature is enabled. If an old template or
  cloud-init payload creates `/export` export rules, `sharedfsvm.sh` or
  `ablestack-storagectl` must remove or neutralize those rules before applying
  Storage Service desired state.
- Apply with:
  `exportfs -ra`
- Report state with:
  `exportfs -v`
  `df`
  `findmnt`

Path validation rules:

- Reject `/export`, `/export/`, `/`, empty paths, relative paths, and paths with
  traversal segments such as `..` for the internal/backing directory.
- Allow managed child backing paths under `/export/<name>` only for
  compatibility SharedFS-backed instances.
- Prefer native Storage Service paths under `/srv/ablestack-storage/...` for new
  protocol objects.
- Display a clear UI hint that the selected path is the internal directory. The
  client mount example must use the share/export name as the root export. For
  v4-only exports, prefer `mount -t nfs4 -o vers=4.1,port=<endpoint-port>
  <service-ip>:/<export-name> <mount-path>`. When `V3V4_DUAL` is selected, the
  UI should also show the legacy v3 form `mount -t nfs -o vers=3,port=<endpoint-port>
  <service-ip>:/export/<export-name> <mount-path>` as a compatibility example.

## Existing Volume Attachment For File Services

The Storage Service must support exposing an existing ABLESTACK volume through
NFS or SMB. This is required for data migration, recovery, and converting an
existing data disk into a managed file service without copying data.

SMB share creation and update must use the same backing volume model as NFS
export creation. The UI must not expose a reduced "existing volume only"
workflow for SMB. For both NFS and SMB, operators choose one of the following
backing modes:

- Current backing volume
  - Selects one of the data volumes already attached to the Storage Service
    System VM and managed by the Storage Service.
  - Reuses the existing managed mount metadata and must not run the new-device
    safe-candidate discovery path that is used for freshly attached volumes.
    Already managed volumes can be mounted and bound into more than one share
    path, so the backend must resolve `volumeMountPath` or the last inspection
    result first and only call guest inspection when that metadata is missing.
  - Uses a non-destructive reuse mode. It must not format or require the volume
    to look empty. If a managed volume is already mounted, the operation creates
    or validates only the requested share directory below that volume.
- Existing volume
  - Selects a detached ABLESTACK data volume from the volume list.
  - Uses non-destructive `MOUNT_EXISTING`; if no supported filesystem exists,
    the operation fails with a clear message instead of formatting data.
- New volume
  - Creates a new ABLESTACK volume from an explicit disk offering and primary
    storage, waits for the async job and attachable state, then calls the
    Storage Service share API with `FORMAT_EMPTY`.
  - On apply failure, the UI-created volume is marked with
    `cleanupvolumeonfailure=true` so the backend can remove incomplete share
    records and clean up the unused newly created volume.

The SMB share API therefore accepts `volumeid`, `filesystem`, `importmode`,
`createdirectory`, and `cleanupvolumeonfailure` in the same lifecycle role that
the NFS export API uses. The backend must pass these values to the common
`prepareFileShareBackingVolume` flow before applying SMB desired state through
QGA. This keeps SMB/NFS volume handling, fstab persistence, filesystem probing,
mount path selection, and failure cleanup consistent.

Supported attachment modes:

- New volume mode
  - The UI creates a new ABLESTACK data volume from the selected disk offering
    and passes the resulting `volumeid` to the Storage Service export/share API.
  - The manager attaches that volume to the Storage Service System VM before
    applying the desired state.
  - QGA runs `volume attach inspect` with `FORMAT_EMPTY`; the SystemVM formats
    the device only when it has no filesystem signature, then mounts and
    exports it.
- Existing volume mode
  - The caller passes an existing `volumeid`.
  - The manager validates that the volume is detached or can be safely detached
    from its current owner according to ABLESTACK volume rules.
  - The manager attaches the volume to the Storage Service System VM.
  - QGA discovers the device by stable disk metadata, probes filesystem
    signatures, and reports the result.
  - The default import mode is non-destructive `MOUNT_EXISTING`.

Existing volume safety rules:

- Never format a volume by default.
- If no supported filesystem is detected, fail with a clear error unless
  `FORMAT_EMPTY` was requested for a newly created empty volume.
- Destructive force-format is not exposed in this implementation. If it is
  added later, it must be explicit, async, and auditable.
- If the filesystem is dirty or requires repair, return a blocked state and
  expose the needed operator action. Do not run destructive repairs
  automatically in the first implementation.
- NFS/SMB ACLs must be rendered before the service is advertised to clients.
- On apply failure, the manager should roll back the export/share and leave the
  volume attached but unexported for inspection, or detach it if no filesystem
  changes were made.

Guest-side attach workflow:

1. QGA receives `volume attach inspect` with expected volume UUID/device hints.
2. `ablestack-storagectl` resolves the Linux device using `/dev/disk/by-id`,
   serial, WWN, or ABLESTACK-provided metadata.
3. The tool runs `blkid`, `lsblk`, and `findmnt` to detect signatures and mount
   state.
4. The tool mounts the filesystem under:
   `/srv/ablestack-storage/volumes/<volume-uuid>`
5. File shares use subpaths under that mount, for example:
   `/srv/ablestack-storage/volumes/<volume-uuid>/shares/<share-name>`
6. The resulting device, filesystem UUID, mount path, and capacity are stored in
   `storage_file_share.config_json`.

Initial filesystem support should be `xfs` and `ext4`. XFS is preferred for new
file-service volumes because it supports online grow and project quotas.

## Runtime Endpoint Authority

ABLESTACK VM/NIC metadata can be incomplete or ambiguous for Storage Service
System VMs. In particular, a secondary address can be reported as the VM
`ipaddress` while the guest still has a different primary address. Therefore:

- The SystemVM monitor cache must include guest-visible IPv4 addresses from
  `ip addr`, including CIDR prefix and primary/secondary role.
- UI endpoint lists and duplicate checks must prefer this runtime evidence over
  API NIC metadata when the same IP appears in both sources.
- Protocol endpoint deletion must treat guest primary IPs as non-removable and
  allow only configured or guest secondary listen IPs to be removed.
- Backend CIDR preflight may use API NIC metadata as an optimization, but the
  final source of truth for a newly added listen IP is the SystemVM guest-side
  validation and application path.

## File Service Capacity Expansion

File-service resize must be an explicit async workflow that coordinates
ABLESTACK volume resize with guest-side filesystem growth.

API direction:

- `resizeStorageFileShare`
  - Parameters: `id`, `size`, `quotabytes`, `resizevolume`, optional
    `shrink=false`.
  - Applies to NFS exports and SMB shares.
  - The first implementation supports grow only.
  - `size` follows existing ABLESTACK volume resize semantics and is an
    integer GiB-compatible value.
  - `quotabytes` is a per-share file-service capacity limit in bytes. The UI
    must collect it through a value plus IEC unit selector and convert it to
    bytes before submission.
- Existing SharedFS `changeSharedFileSystemDiskOffering` remains available for
  API compatibility, but is not exposed in the current Storage Service UI and
  must not imply that all backing volumes are changed together.
- Backing volume growth is managed per volume through
  `resizeStorageServiceBackingVolume`; the row-level volume action is the
  authoritative UI workflow.

Resize workflow:

1. Validate the target share and backing volume.
2. Validate the requested backing volume size, when supplied, is larger than
   the current volume size.
3. Ask ABLESTACK to resize the backing volume or change the disk offering only
   when `resizevolume=true`.
4. Send a QGA `filesystem resize` operation to the Storage Service System VM.
5. Inside the VM, rescan the block device:
   - `echo 1 > /sys/class/block/<dev>/device/rescan` where available
   - `partprobe` when partitions are used
6. Grow the filesystem:
   - XFS: `xfs_growfs <mountpoint>`
   - ext4: `resize2fs <device>` after ensuring the block device is resized
7. If XFS project quota is used, update the project hard limit to
   `quota_bytes`.
8. Return health, size, used bytes, filesystem, and mount path in the async job
   result.

Capacity meaning:

- Backing disk size is the size of the ABLESTACK volume attached to the
  Storage Service System VM. It is the physical upper bound for the file share
  or block target.
- File share quota is a protocol resource limit:
  - NFS quota applies to the specific NFS export path.
  - SMB quota applies to the specific SMB share path.
  - If NFS and SMB expose different paths, they must have independent quota
    fields.
  - If NFS and SMB intentionally expose the same path, the UI should show that
    the quota is shared by both services.
- iSCSI and NVMe-oF should not use "file share quota" wording. They should use
  `LUN size` and `Namespace size`, stored as bytes in the expanded model and
  collected with the same IEC unit selector in the UI.
- Backing disk resize permission should be worded as `Allow data disk resize
  when required`, displayed in Korean as `필요 시 데이터 디스크 확장 허용`.
  This means the workflow may resize the ABLESTACK backing volume if a
  requested export/share/LUN/namespace size cannot fit in the current backing
  volume. It is not itself a quota.

Existing volume capacity behavior:

- When `EXISTING_VOLUME` is selected, the create dialog must not ask for a new
  data disk size.
- The UI must show the selected existing volume's actual size.
- If the selected volume size differs from a previously entered data disk size,
  the existing volume size wins and the new-disk size is ignored.
- If a service quota, LUN size, or namespace size is greater than the selected
  existing volume size:
  - fail validation when backing disk resize is not allowed;
  - attempt the ABLESTACK volume resize first when backing disk resize is
    allowed and the volume/disk offering permits resize.

Failure handling:

- If ABLESTACK volume resize succeeds but filesystem grow fails, mark the
  share `ResizeError`, keep the service mounted, and expose a retry action.
- Shrink is not supported in the first implementation because it requires
  filesystem-specific offline steps and has high data-loss risk.
- Multi-share-on-one-volume capacity is enforced through XFS project quota; the
  volume grow and quota grow are separate state transitions.

### Dedicated Backing Volume Resize Workflow

Backing volume resize must be separated from file-share capacity resize. The
file-share workflow owns protocol quota and filesystem growth. The backing
volume workflow owns the ABLESTACK data volume size and block-device rescan.
This prevents block protocols such as iSCSI and NVMe-oF from opening a
file-share selector or calling a file-share API with a target/namespace ID.

| Area | As-is | To-be |
| --- | --- | --- |
| UI modal | `resizeVolume` reuses the `resizeShare` form. The first field is labeled `file share` and is populated with export/share/target/namespace IDs depending on the caller. | `resizeBackingVolume` has its own vertical modal. It shows the selected volume name, volume UUID, current size, storage pool, disk offering, and the resources using the volume. The only editable field is the requested new volume size in GiB. |
| File-share resize API | `resizeStorageFileShare` accepts `id`, `size`, `quotabytes`, and `resizevolume`; the ID must be a `storage_file_share` row. | Keep `resizeStorageFileShare` for NFS/SMB file-share quota and optional filesystem grow only. It must not be used by iSCSI or NVMe-oF backing-volume table actions. |
| Backing-volume resize API | No Storage Service specific API exists. The UI currently routes every protocol's backing-volume button to `resizeStorageFileShare`, so iSCSI/NVMe-oF can submit invalid IDs. | Add `resizeStorageServiceBackingVolume` with `instanceid`, `volumeid`, and `size`. The backend validates that the volume belongs to the viewed Storage Service instance and that the requested size is larger than the current size, then calls ABLESTACK volume resize. |
| Runtime refresh | File-service resize sends `filesystem resize` after volume resize. Block protocols may not need filesystem grow, but they still need guest-side block rescan and desired-state refresh. | After volume resize, send a Storage Service VM rescan operation for the selected volume. NFS/SMB file paths may continue to use filesystem grow when applicable. iSCSI and NVMe-oF refresh raw block device size and reapply their target/namespace desired state without creating a filesystem. |
| Data safety | A generic volume expand button may appear to expand the protocol resource, while actually operating through a file-share object. | The modal explains that this grows only the ABLESTACK backing volume. Namespace/LUN/share quota expansion remains a separate protocol-resource action where that feature exists. Shrink is never offered. |

Code-level targets:

| Component | Required change |
| --- | --- |
| `api/src/main/java/org/apache/cloudstack/api/command/user/storage/dataservice/ResizeStorageServiceBackingVolumeCmd.java` | Add a user API command named `resizeStorageServiceBackingVolume`. Required parameters: `instanceid`, `volumeid`, `size`. Response should be `StorageServiceRuntimeResponse` or a dedicated volume response that includes refreshed volume metadata. |
| `api/src/main/java/org/apache/cloudstack/storage/dataservice/StorageService.java` | Add `resizeStorageServiceBackingVolume(ResizeStorageServiceBackingVolumeCmd cmd)`. |
| `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | Register the new command, validate instance ownership, validate the volume is attached to or recorded by the Storage Service instance, reject shrink/no-op sizes, call the existing `resizeBackingVolume(volumeId, sizeGb)` helper, then request guest rescan and protocol desired-state refresh for resources using the volume. |
| `systemvm/debian/usr/local/bin/ablestack-storagectl` | Add or reuse a safe backing-volume rescan command. It must resolve the volume by UUID/name using the existing safe disk resolver, rescan `/sys/class/block/<dev>/device/rescan` when present, refresh `blockdev --getsize64`, and update monitor cache. It must not format, mount, or modify filesystems for iSCSI/NVMe-oF raw block volumes. |
| `ui/src/views/storage/SharedFSTab.vue` | Split `forms.resizeShare` and `forms.resizeBackingVolume`. Backing-volume table buttons call `openActionModal('resizeBackingVolume', { volumeid, volumeName, currentSize, protocolRefs })`. The submit handler calls `resizeStorageServiceBackingVolume`. The modal never renders `label.storage.service.file.share`. |
| `ui/public/locales/ko_KR.json`, `ui/public/locales/en.json` | Add dedicated labels/help text for backing volume name, volume UUID, current size, new size, using resources, and block-client rescan guidance. Remove any raw i18n key exposure in backing-volume resize dialogs. |

Preflight rule:

- The current defect is a deterministic UI/API object-mapping defect, so a live
  service-VM code injection is not required to prove the modal/API split.
- Before enabling the SystemVM rescan path in a deployable build, run a
  disposable-service preflight that expands one attached iSCSI or NVMe-oF
  backing volume, verifies guest block size refresh, verifies protocol
  reconnect/read/write behavior, and verifies reboot reconcile keeps the larger
  size visible.

### Backing Volume Resize Identity, Runtime, and Deployment Contract

The dedicated backing-volume workflow must fail closed at every layer. A block
target UUID, namespace UUID, file-share UUID, or a fallback SharedFS volume must
never be accepted as the selected backing-volume identity. The operator-visible
modal and the API request must carry the same canonical ABLESTACK volume UUID.

Observed evidence on 2026-07-10:

- The NVMe-oF backing-volume row for `sharedfs-DATA-591` represented volume UUID
  `59121317-293c-45d6-87ac-da70d5f5a1e8`, current size `50 GiB`, and resource
  `nqn.2026-06.local.storage:tc03d01 / Namespace 1`.
- The served legacy modal instead displayed
  `b331a969-1041-4b44-b577-a62abbe9161b`, which is the namespace
  `storage_block_target` UUID, under a `file share` label.
- The current source and local UI build already contain the dedicated
  `resizeBackingVolume` form, but the effective 22.10 webroot served an older
  entry bundle. This proves that source correctness and deployment correctness
  must be separate release gates.
- The live Storage Service VM resolved the volume by normalized serial before
  reboot and again after its Linux device name changed. NVMe-oF connect and a
  reversible raw-block write/read/restore test passed before and after reboot.

Canonical UI row contract:

| Field | Source | Rule |
| --- | --- | --- |
| `volumeId` | exact match from `storageService.backingVolumes` using target `volumeid`/`volumeUuid` | Required. Never fall back to the target/namespace row ID or the default SharedFS volume. |
| `volumeName` | matched volume response | Read-only summary value. |
| `currentSizeBytes` | matched volume `size` | Required numeric value used to calculate the minimum expansion size. |
| `currentSizeGiB` | ceiling of `currentSizeBytes / GiB` | Read-only summary value. |
| `resourceRefs` | all NFS/SMB/iSCSI/NVMe-oF resources referencing the volume | Read-only summary; multiple references are rendered as a list or tooltip. |
| `diskOffering` / `storagePool` | matched volume response | Read-only summary values. |
| `newSizeGiB` | operator input | Integer only and strictly greater than `currentSizeGiB`. |

UI implementation rules for `SharedFSTab.vue`:

1. Replace permissive `volumeForTarget()` use in backing-volume action rows
   with an exact resolver, for example `exactVolumeForTarget(target)`. An exact
   resolver returns `null` when no matching volume UUID exists; it does not
   return `currentSharedFsVolume`, `this.volume`, or another fallback object.
2. Build protocol backing-volume rows through one canonical row factory. The
   action record contains explicit `volumeId`, `volumeName`,
   `currentSizeBytes`, `currentSizeGiB`, and `resourceRefs`; the generic `id`
   field is only the table key.
3. Disable `volume expansion` when the exact volume or numeric current size is
   missing. The tooltip must identify the missing mapping instead of opening a
   partially populated modal.
4. `populateResizeBackingVolumeForm()` accepts only the canonical row contract.
   It must not inspect `context.id`, `raw.id`, target IDs, or namespace IDs as a
   fallback volume identifier.
5. The vertical dark-mode modal displays the read-only summary first. Its only
   editable field is `new volume size (GiB)`, with `min = currentSizeGiB + 1`,
   integer precision, and a grow-only validation message. Confirm remains
   disabled until the input is valid.
6. Submission calls only `resizeStorageServiceBackingVolume` with
   `instanceid`, canonical `volumeid`, and `size`. Successful completion
   refreshes backing volumes, affected resources, runtime inventory, health,
   and sessions without changing the active tab or full-width preference.

Backend and SystemVM sequence:

| Step | Component | Required behavior |
| --- | --- | --- |
| 1 | `ResizeStorageServiceBackingVolumeCmd` | Accept only `instanceid`, `volumeid`, and integer GiB `size`; do not accept a share/target/namespace selector. |
| 2 | `StorageServiceManagerImpl.resizeStorageServiceBackingVolume()` | Require a DATADISK attached to the selected Storage Service VM, reject shrink/no-op requests, and ensure the volume is represented by a Storage Service file share or block target before resizing. |
| 3 | ABLESTACK volume service | Resize the canonical volume, reload it from the DAO, and verify the persisted size is at least the requested byte size. |
| 4 | `ablestack-storagectl volume rescan` | Match exactly one non-root disk by normalized volume UUID/serial. Zero or multiple matches are errors. Never rescan every non-root disk as a fallback. |
| 5 | `ablestack-storagectl volume rescan` | Rescan the exact device, settle udev, read `blockdev --getsize64`, and return volatile `observedDevicePath`, `matchedBy`, `expectedSizeBytes`, and `actualSizeBytes`. A legacy `devicePath` alias may be emitted during compatibility rollout but is never selector authority. Fail when the observed size is smaller than requested. |
| 6 | protocol reapply | Reapply only protocols that reference the resized volume. iSCSI/NVMe-oF use the raw block device without formatting or mounting it; NFS/SMB filesystem growth remains in the file-share workflow. |
| 7 | runtime verification | For NVMe-oF, verify the namespace configfs `device_path` still resolves to the matched disk and report the refreshed block size. Monitor cache is updated only after verification succeeds. |

No DB schema migration is required. Existing volume, file-share, and block-target
relations provide the ownership and affected-resource mapping.

Deployment is part of the feature contract. A successful UI build is not a
successful deployment until the effective HTTP webroot serves that build:

1. Build in the canonical WSL tree and record the SHA-256 of `ui/dist/index.html`
   and the hashed app entry referenced by it.
2. Deploy scoped static assets to the effective management webroot
   `/usr/share/cloudstack-management/webapp/` and to
   `/usr/share/cloudstack-ui/` when that secondary root is required. Preserve
   `WEB-INF` and backend libraries; never run an unscoped delete over the
   management webapp root.
3. Reject `/usr/share/cloudstack-management/webapp/client/` as the sole target;
   it is not the effective `/client/` webroot in the validated 22.10 runtime.
4. Verify local `index.html` hash equals the effective remote webroot hash and
   the body hash returned by `GET /client/`.
5. Parse the served `index.html`, require every referenced hashed JS/CSS asset
   to return HTTP 200, and require the obsolete entry bundle to be absent from
   the served document.
6. Open the backing-volume action in the browser and assert that the dialog
   contains the volume summary and does not contain the file-share selector.

## SMB Design

SMB must support both local authentication and AD domain backed authentication.

Features:

- SMB protocol enable/disable
- SMB share create/update/delete
- local user/group ACLs
- AD user/group ACLs
- read/write/admin permissions
- guest access policy, disabled by default
- AD domain join and leave
- AD domain health checks

AD join requirements:

- DNS server validation
- time synchronization validation
- Kerberos configuration
- `realm` or `adcli` join
- winbind or sssd configuration
- NSS/PAM integration only where required
- no plaintext persistence of join credentials
- The NetBIOS domain/workgroup is mandatory for Samba AD membership. If the
  operator leaves it blank, the UI and backend derive it from the AD FQDN first
  label, for example `ablestack.local` becomes `ABLESTACK`; `WORKGROUP` must
  not be used as the AD join default.
- The SystemVM must render `smb.conf` before joining with `security = ADS`,
  the derived or supplied `workgroup`, uppercase `realm`, `kerberos method =
  secrets and keytab`, and an explicit default idmap range such as `idmap
  config * : range = 10000-999999`.
- After AD DNS is applied, the SystemVM should run `net ads lookup` where
  possible and prefer the discovered Pre-Windows 2000 domain name over a
  guessed value before executing `net ads join`.
- AD join success is not just command submission. It requires `net ads
  testjoin` and the domain status cache to report `JOINED`; otherwise the SMB
  tab must show the join phase and error message while keeping the partially
  created service manageable.
- The SMB UI treats AD as a single domain membership state for one Storage
  Service instance, not as a multi-domain list. Before a domain is configured,
  the visible action is `AD domain join`. After a domain record exists, the UI
  must replace that action with state management actions: `check AD status`,
  `rejoin AD domain`, and `leave AD domain`. Rejoin reuses the same join API
  and pre-fills non-secret domain fields from the current domain status. Leave
  requires an explicit confirmation using the current domain name and warns
  that AD user/group ACLs may become invalid.

Sensitive fields, such as AD join passwords, must be accepted as runtime
secrets and masked in logs, events, QGA payload traces, and async job details.

SMB share path and ACL rules:

- The client-visible SMB share name is independent from the guest runtime
  directory. UI and API responses may show the operator-facing path such as
  `/export/<share-name>` as display context, but the Samba runtime `path` must
  be the mounted backing-volume path resolved inside the SystemVM.
- The backend must resolve the runtime path from share config in this order:
  `backingPath`, `lastInspection.backingPath`, known attached volume mount root
  plus normalized share path. It must not ask the SystemVM to render an SMB
  share directly on the root filesystem `/export/<share-name>` when the share is
  volume-backed.
- The SystemVM must reject SMB desired-state entries that would render a Samba
  share on the root `/export` tree. This prevents accidental data placement on
  the system disk and makes NFS/SMB cross-protocol shares use the same backing
  volume model.
- SMB ACL list APIs must be scoped by Storage Service instance and SMB share.
  They must not return stale NFS or unrelated file-share access rules from other
  instances.
- SMB ACL application must resolve both local and AD principals, apply Samba
  access controls, and align POSIX ACLs on the resolved backing directory. AD
  and local password values are runtime-only and are never stored in UI state,
  database config JSON, monitoring cache, or logs.
- AD domain join credentials and SMB share access principals are separate
  concepts. The UI, API, backend, and SystemVM desired state must never derive
  a share ACL from the AD join account unless the operator explicitly selects
  that user as the SMB access principal.
- Initial AD-backed SMB access must not default to `Domain Users`. The default
  principal type is `AD_USER` with an empty principal field, forcing the
  operator to choose the exact user or group to grant. Group access is allowed
  only when the operator selects `AD_GROUP`.
- Samba share-level principals must be rendered with principal-type aware
  quoting. AD/local users are rendered as quoted user names such as
  `"ABLESTACK\\ablecloud"`; AD/local groups are rendered as group tokens
  with a quoted group body such as `@"ABLESTACK\\Domain Users"`. This is
  required for groups whose names contain spaces and prevents `valid users` or
  `write list` token splitting.
- POSIX ACL application must remain principal-type aware: user ACLs are applied
  as user entries and group ACLs as group entries. The runtime access cache must
  include `principalType`, `principal`, `permission`, and access state so the UI
  can distinguish an AD user ACL from an AD group ACL.
- SMB shares with no explicit access rule are not open shares. Unless the
  operator explicitly enables guest access, an SMB share without a local or AD
  ACL must remain inaccessible to normal clients. This is a security policy, not
  an error. The UI should communicate that share creation prepares the backing
  volume and Samba share, while client read/write access requires an explicit
  ACL.
- Guest access is the only operator-visible exception to the explicit ACL rule.
  When guest access is disabled, the SystemVM must not loosen POSIX permissions
  merely because no ACL exists.
- Failed share creation must clean up all layers that were partially prepared:
  file-share rows, desired-state entries, fstab markers, temporary bind aliases,
  and newly created volumes when `cleanupvolumeonfailure=true`. Reusing an
  already managed current backing volume must not leave duplicate fstab entries.

SMB modal layout rules:

- SMB share, SMB ACL, and SMB AD join action dialogs use the same vertical
  single-column modal standard as the NFS action dialogs.
- In dark mode, SMB dialogs must use the Storage Service field, section, alert,
  scrollbar, and footer styles already defined for NFS. Two-column desktop form
  layouts are not used for SMB action dialogs because long domain, principal,
  share, and backing-volume values reduce readability.
- Storage Service action dialogs must have exactly one vertical scroll owner:
  the fixed modal header/footer stay outside the scroll area and the modal body
  scrolls through the shared `.storage-modal-body` container. Nested form,
  section, or panel scrollbars are not allowed because they create double
  scrollbars and make dark-mode modal interaction confusing.
- NFS and SMB backing-volume tables must display filesystem, used capacity, and
  mount information from the same runtime inspection source. UI display priority
  is SystemVM inspection/cache, current backing-volume mapping, Cloud volume
  metadata, then the SharedFS default filesystem fallback. A volume shared by
  NFS and SMB must therefore show the same filesystem value in both tabs.
- Table action columns are fixed to the right and right-aligned. Horizontal
  scrolling must not overlay the action column; fixed-column backgrounds and
  empty-state foreground colors must remain readable in dark mode.

## iSCSI Design

iSCSI should use Linux LIO through `targetcli-fb`. The first implementation
stores the desired state in `storage_block_target` and applies target/LUN/ACL
configuration through `ablestack-storagectl iscsi target apply <payload.json>`.
Secrets such as CHAP passwords are runtime-only payload fields and are not
persisted in the database.

Features:

- target IQN create/update/delete
- LUN create/delete
- backing volume mapping
- initiator IQN ACL
- CHAP enable/disable and mutual CHAP enable/disable per initiator ACL
- CHAP user names stored in the desired-state model, with CHAP secrets passed
  only in the async API/QGA runtime payload
- target session listing
- LUN resize

The iSCSI user experience must follow the mature NFS/SMB Storage Service
standard. Operators manage iSCSI from the iSCSI protocol tab with dense tables,
right-aligned row actions, fixed action columns, dark-mode safe colors, and
vertical action dialogs that never introduce modal-level horizontal scrolling.

Target and LUN lifecycle:

- The target create/edit dialog exposes Target IQN, LUN number, LUN size, backing
  volume mode, and listener port group selection.
- The backing volume mode is the same operator model used by NFS and SMB:
  current backing volume, existing unattached volume, or new volume creation.
- New volume creation must require disk offering, primary storage, and volume
  size. The primary storage selector is filtered by the selected disk offering
  tag when a tag exists.
- The API receives a prepared olumeid; the UI may create or attach the volume
  before calling the iSCSI target API. The backend still validates that the
  selected volume is either already attached to the Storage Service System VM or
  is otherwise safe for that Storage Service instance.
- LUN size is a block object size and must not reuse file-share quota wording.
  If omitted, the backing volume size is used.

iSCSI endpoint model:

- storage_service_protocol rows represent enabled iSCSI listeners. The default
  port is TCP 3260.
- A target can select one or more listener port groups. A listener port group is
  all enabled iSCSI listeners with the same TCP port. For example, selecting
  port 3261 exposes the target on every enabled iSCSI listener whose port is
  3261.
- The target API persists this as endpointMode=LISTENER_GROUP and
  listenerPorts=[3260,3261] in storage_block_target.config_json. This avoids
  adding a new schema table and keeps the model aligned with the NFSv4 listener
  group approach.
- Legacy targets without listener-port metadata are read as the current default
  iSCSI listener port. Update paths should preserve existing behavior unless the
  operator explicitly changes listener port groups.
- The backend must validate that every requested listener port exists as an
  enabled iSCSI protocol row for the same Storage Service instance.

ACL and authentication:

- iSCSI ACLs are per target and per initiator IQN.
- ACL create/edit supports permission, CHAP, and mutual CHAP.
- CHAP usernames and enablement flags are stored as desired state. CHAP secrets
  are accepted only in the async request and forwarded to the SystemVM QGA
  payload; they must not be returned by list APIs, displayed in the UI, written
  to monitor cache, or logged.
- Updating an ACL with CHAP enabled but without a new secret preserves the
  existing runtime credential if present. Disabling CHAP clears runtime
  authentication for that initiator.

Capacity is controlled by the backing volume size. When an operator supplies an
explicit raw `backingPath`, the SystemVM may expose that block device directly
through a LIO block backstore only if the device is not mounted. When the
Storage Service is using the default SharedFS data disk, the disk can already be
mounted by the legacy `/export` initialization path. In that case iSCSI must
create a managed file-backed LUN under the backing volume, for example
`/export/.ablestack-storage/iscsi/<target-uuid>.img`, and expose it through a
LIO `fileio` backstore. This avoids unmounting or destructively reformatting the
SharedFS backing disk while still presenting a block LUN to clients.

The QGA apply path must fail the async operation if:

- `targetcli` is missing while enabled targets exist
- the backing block device or file-backed LUN cannot be resolved or created
- target/LUN/portal creation fails
- any requested iSCSI listener port is not listening after target application and
  service restart

The runtime monitor cache must include iSCSI listen status, target inventory,
listener inventory, session inventory, and the latest generated timestamp so the
UI can render fast status without running expensive target inspection on every
page refresh. The monitor must not assume port 3260; it must use the desired
listener list stored in /etc/ablestack-storage/iscsi-targets.json.

SystemVM runtime rendering:

- blestack-storagectl iscsi target apply writes the sanitized desired state to
  /etc/ablestack-storage/iscsi-targets.json.
- It renders LIO targets idempotently with 	argetcli, removes stale portals for
  the managed target, creates the selected portals, creates LUNs, applies ACLs,
  and saves the target configuration.
- It starts/enables `target` or `rtslib-fb-targetctl` and opens requested TCP
  ports best effort through the guest firewall.
- The boot reconcile service must reapply stored iSCSI desired state so targets
  survive Storage Service System VM reboot.

iSCSI UI tab table standard:

- iSCSI targets table: target IQN, LUN, listener endpoints, backing volume, LUN
  size, effective size, backstore type, ACL summary, state, and right-aligned row
  actions for edit, resize, and delete.
- iSCSI ACLs table: target IQN, initiator IQN, permission, CHAP enabled, CHAP
  username, mutual CHAP enabled, state, and right-aligned row actions for edit
  and delete.
- Backing volumes table: volume name, ABLESTACK volume ID, size, used capacity
  when available, disk offering, storage pool, connected target, state, and
  right-aligned row actions.
- Sessions table: initiator/client, target IQN, LUN, endpoint, state,
  connected timestamp when available, and right-aligned disconnect action.

## NVMe-oF Design

NVMe-oF should start with TCP transport.

Features:

- subsystem NQN create/update/delete
- namespace create/delete
- backing volume mapping
- host NQN ACL
- optional DH-HMAC-CHAP host authentication and controller authentication for
  host ACLs, with keys passed only in the async API/QGA runtime payload
- discovery information
- namespace resize

DH-HMAC-CHAP enforcement is mandatory when requested. `ablestack-storagectl`
must verify that the selected SystemVM kernel/configfs exposes the required
`dhchap_key` and `dhchap_ctrl_key` attributes before reporting a host ACL as
applied. If the template/kernel cannot enforce the requested authentication,
the QGA apply command must fail and the affected ACL must remain `Error`
instead of being reported as `Ready`.

DH-HMAC-CHAP is capability gated. `ablestack-storagectl health` and
`ablestack-storagectl inventory` must report
`capabilities.nvmeof.dhChapSupported` and
`capabilities.nvmeof.dhChapCtrlSupported` by probing the live
`/sys/kernel/config/nvmet/hosts/<sample-host>/dhchap_key` and
`dhchap_ctrl_key` attributes. The UI must disable DH-HMAC-CHAP host and
controller authentication controls when those attributes are missing, while
still displaying that DH-HMAC-CHAP authentication is unsupported by the current
SystemVM kernel/configfs. For create workflows where the target SystemVM does
not exist yet, the current Storage Service SystemVM template baseline is treated
as unsupported until a future template exposes the capability.

DH-HMAC-CHAP secrets are one-time runtime inputs only. They may exist in the API
request, async job payload, and QGA apply payload long enough to apply configfs,
but must be removed before writing SystemVM desired-state files, monitor cache
files, runtime API result JSON, UI tables, logs, events, or validation records.
Persisted ACL configuration may retain only enabled flags and non-secret
identity fields.

The SystemVM session monitor should track first-seen and last-seen timestamps
in its local runtime cache. NVMe-oF sessions should include the subsystem NQN
when it can be unambiguously derived from the active subsystem state; when it
cannot be derived, the UI must show an unknown value rather than implying that
no subsystem is attached.

For NFS-Ganesha sessions, the TCP connection only proves that a client is
connected to an NFS listener. It does not reliably identify the exact export
used by that client. The collector must therefore enrich NFS rows from the
rendered Ganesha configuration in `/etc/ganesha/ablestack-storage/*.conf`:

- normalize IPv4-mapped IPv6 addresses such as `::ffff:10.10.21.102` before
  storing or displaying them;
- derive active NFS listener ports from `NFS_Port` values instead of assuming
  only TCP 2049;
- map a session's local listener to configured `Pseudo` and `Path` entries;
- if exactly one export exists on that listener, set the session
  `resourceName`, `exportName`, client-visible path, and backing path from that
  export;
- if more than one export exists on the listener, return `possibleExports`
  and let the UI show that the exact export is ambiguous instead of selecting
  an arbitrary export.

For NVMe-oF TCP sessions, the monitor derives the live transport connection set
from `ss` because Linux kernel `nvmet` exposes one TCP connection per I/O queue
rather than a single high-level controller row. The collector must enrich and
collapse those TCP rows from Linux `configfs`, not from a fixed port assumption:

- read `/sys/kernel/config/nvmet/ports/*` and collect each `addr_traddr`,
  `addr_trsvcid`, and linked subsystem;
- read `/sys/kernel/config/nvmet/subsystems/<nqn>/namespaces/*` to keep only
  subsystems with active namespaces;
- read `allowed_hosts` and `attr_allow_any_host` as configured access policy.
  Configured ACL identity must never be reported as an observed client Host NQN;
- treat every configured listener port as valid, including non-default ports
  such as 4421 or 4422. The session collector must never hard-code only 4420;
- group TCP rows by `(local endpoint, client address)` and expose one transport
  aggregate with a `queueCount` value. This tuple is not a controller identity;
- when exactly one subsystem with a namespace is linked to the listener, set
  subsystem NQN and namespace ID as exact. Set `observedHostNqn` only when an
  independent runtime controller source reports it;
- when multiple candidate subsystems are linked to the same listener, return
  `mappingStatus=AMBIGUOUS`, `possibleSubsystems`, and `possibleNamespaces`
  instead of inventing a subsystem, namespace, or Host NQN;
- when no namespace-backed subsystem is linked to a listener that has live TCP
  queues, mark the row as unmapped and degrade monitor status.

The transport cache key stays stable for each TCP peer/local tuple so the
monitor can retain `connectedAt` and update `lastSeen` across polling cycles.
The API and UI must call this value `transportSessionId`, not a logical
controller session ID. A `logicalSessionId` may be emitted only when an exact
runtime controller source provides a stable controller identity.

The current stock Storage Service kernel does not expose a stateless query that
joins endpoint, client address, Host NQN, subsystem NQN, and controller ID.
`nvmet` tracepoints expose only partial request/controller data, and kernel log
creation messages are not a durable query API. Therefore:

- a single-candidate transport aggregate may show exact subsystem/namespace
  attribution, but its Host NQN remains unknown unless independently observed;
- a multi-candidate transport aggregate remains one warning row with candidate
  metadata and must not be split heuristically;
- disconnect is disabled for ambiguous aggregates because there is no safe
  controller-specific handle;
- `queueCount` means transport queues, not logical sessions;
- future exact controller attribution requires a dedicated runtime event source
  such as kernel instrumentation or a target-side controller event daemon. It
  is outside this focused correction.

Required runtime/API session fields:

| Field | Rule |
| --- | --- |
| `transportSessionId` | SHA-256 of protocol, local endpoint, and client endpoint. Stable only for the transport aggregate. |
| `logicalSessionId` | Optional. Present only when the runtime supplies an exact controller identity. |
| `mappingStatus` | `EXACT`, `AMBIGUOUS`, or `UNMAPPED`. |
| `queueCount` | Number of established TCP queues in the transport aggregate. |
| `subsystemNqn`, `namespaceIds` | Populated only for exact attribution. |
| `possibleSubsystems`, `possibleNamespaces` | Candidate metadata for ambiguous attribution. |
| `observedHostNqn` | Runtime-observed Host NQN only. Never copied from configured ACLs. |
| `hostPolicy` | `ALLOW_ANY` or `EXPLICIT`; describes configured policy separately from observed identity. |
| `configuredAllowedHosts` | Redacted, non-secret configured Host NQN list. It is not a session observation. |

Code-level session targets:

| File | Required change |
| --- | --- |
| `systemvm/debian/usr/local/bin/ablestack-storagectl` | Refactor `nvme_tcp_sessions()` so `(local, client)` creates a transport aggregate. Emit `transportSessionId`, normalized mapping status, candidate NQN/NSID lists, access policy, and configured ACLs separately. Remove the assignment that copies a single configured `allowedHosts` value into `hostNqn`. Keep ambiguous aggregates healthy-with-warning and unmapped aggregates degraded. |
| `systemvm/debian/usr/local/bin/ablestack-storage-monitor` | Preserve the extended session JSON and first/last-seen timestamps keyed by `transportSessionId`. Do not synthesize logical controller rows. |
| `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | Preserve the new session fields while merging monitor cache into runtime status. Do not collapse candidate arrays or rename configured ACL identity to observed Host NQN. |
| `ui/src/views/storage/SharedFSTab.vue` | Extend `nvmeSessionColumns` and `nvmeSessionRows` with mapping status, candidate summary, host policy, and transport queue terminology. Use `transportSessionId` as the row key. Show exact values only for exact mapping, show candidate count/details for ambiguous mapping, and disable termination unless a controller-specific logical handle exists. |
| `ui/public/locales/ko_KR.json`, `ui/public/locales/en.json` | Add translated labels/tooltips for transport aggregate, mapping warning, candidate subsystem/namespace list, observed Host NQN, configured host policy, and disabled disconnect reason. |

Backward compatibility rule: old monitor rows that contain only `sessionId` and
lowercase `exact`/`candidate`/`unmapped` remain readable. The UI normalizes them
to the new model, but it must not promote a legacy candidate row to exact.

### NVMe-oF Runtime Namespace Observation Contract

NVMe-oF namespace rows need both desired and observed storage facts. The
current generic block-target response reads `lunSizeBytes`, while NVMe-oF
desired state stores `namespaceSizeBytes`, and the SystemVM inventory returns
desired-state JSON without configfs/block-device enrichment. This produces `-`
for namespace size and runtime backing path even when the namespace is healthy.

| Component | As-is | To-be |
| --- | --- | --- |
| API response | Reuses generic LUN size fields and does not expose an explicit namespace size. | Add `namespaceSizeBytes` while preserving `effectiveSizeBytes` for compatibility. NVMe-oF rows read configured size from `namespaceSizeBytes`, not `lunSizeBytes`. |
| SystemVM inventory | Returns the desired subsystem JSON as runtime inventory. | Add `enrich_nvmeof_subsystems()` and merge configfs observations by canonical `(subsystemNqn, namespaceId)`. |
| Backing identity | A transient `/dev/sdX` path can appear as if it were the durable identity. | Desired identity remains volume UUID/serial. `runtime.backingPath` is observed-only and may change after reboot. |
| Size fields | UI falls back inconsistently to volume size or `-`. | Emit `configuredSizeBytes`, `volumeSizeBytes`, `actualBackingSizeBytes`, and `effectiveSizeBytes`; UI shows effective size and a source tooltip. |
| Runtime mismatch | Missing enrichment is indistinguishable from an empty namespace. | Emit `runtime.mappingStatus`, `runtime.enabled`, and a warning when configfs or block-device mapping is missing or ambiguous. |

Code-level targets:

| File | Required change |
| --- | --- |
| `api/src/main/java/org/apache/cloudstack/api/response/StorageBlockTargetResponse.java` | Add `namespaceSizeBytes` and keep existing generic size fields backward compatible. |
| `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | In `createBlockTargetResponse`, branch on NVMe-oF namespace type, read `namespaceSizeBytes`, and merge observed namespace fields without treating `/dev/sdX` as persistent identity. |
| `systemvm/debian/usr/local/bin/ablestack-storagectl` | Add `enrich_nvmeof_subsystems()` that reads configfs namespace `device_path`, `enable`, and block-device byte size, then merges by NQN/NSID. |
| `ui/src/views/storage/SharedFSTab.vue` | Render observed size/path first, configured values second, and volume fallback last. Display mapping source/status rather than a silent dash. |

#### Canonical Namespace join and coherent UI observation

The runtime contract above is only useful when every consumer applies the same
identity rule. Namespace ID is scoped to a subsystem and is not globally unique.
The 2026-07-17 preflight proved that several healthy subsystems can all have
Namespace ID `1` while using different backing devices. A bare NSID match must
therefore never select a runtime namespace.

| Area | As-is | To-be |
| --- | --- | --- |
| UI runtime join | `runtimeBlockTarget()` compares target UUID, row ID, and bare LUN/Namespace ID in one common ID set. The first runtime row with NSID `1` can be returned for every subsystem. | Use an ordered, fail-closed matcher. First match a persistent target UUID only against an explicit runtime `resourceUuid`. Otherwise match the normalized composite `(subsystemNqn, namespaceId)`. Use volume UUID/serial only as a tie-breaker. Zero or multiple matches return `UNMAPPED` or `AMBIGUOUS`; they never return another namespace path. |
| Desired and observed path | The namespace row can prefer `target.backingPath` and can display one transient `/dev/sdX` path as if it were durable. | Keep `backingPath` as desired/configured metadata. Add `runtimeBackingPath` as an observed field. The UI column named actual/runtime backing path uses only `runtimeBackingPath`; volume UUID/serial remains the durable identity. |
| API response | `StorageBlockTargetResponse.runtimeStatus` is assembled from DB state and endpoint metadata, while the UI independently joins monitor inventory. | Merge the monitor-cache namespace observation in the backend using the same canonical composite key and expose `runtimeBackingPath`, `runtimeMappingStatus`, `runtimeEnabled`, `actualBackingSizeBytes`, and `runtimeObservedAt`. Keep existing fields for backward compatibility. |
| Namespace size | An empty configured namespace limit can render as a dash even when the namespace intentionally consumes the whole backing volume. | Return the effective observed size separately. The UI renders an empty configured limit as `entire backing volume` and shows the observed byte/GiB size as effective capacity. |
| Reboot refresh | Subsystems, namespaces, ACLs, and monitor inventory can replace UI arrays at different times. A namespace can briefly show no ACL or a stale path until the manual Update action completes. | Fetch the four datasets under one refresh generation, stage them, reject older monitor snapshots, and atomically replace the NVMe-oF view model. While refreshing, retain the previous coherent snapshot and update rows in place; do not clear or flicker the whole tab. |
| Missing translation | The required locale keys exist in source, but a stale or partially deployed locale file can expose raw `label.storage.service.*` values. | Treat UI JavaScript, CSS, and locale JSON as one release unit. The deployment gate must verify the effective webroot and HTTP body hashes for the app entry and both locale files, then fail if a browser smoke test finds a raw storage-service key. |

Code-level implementation targets:

| File | Required change |
| --- | --- |
| `api/src/main/java/org/apache/cloudstack/api/response/StorageBlockTargetResponse.java` | Add backward-compatible runtime observation fields: `runtimeBackingPath`, `runtimeMappingStatus`, `runtimeEnabled`, `actualBackingSizeBytes`, and `runtimeObservedAt`. Do not overload configured `backingPath`. |
| `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | Add one canonical NVMe namespace observation mapper keyed by normalized subsystem NQN plus normalized NSID. Merge monitor data only on an exact composite match and include mapping status/warnings in the response. Never match on NSID alone. |
| `ui/src/views/storage/SharedFSTab.vue` | Replace the mixed-ID search in `runtimeBlockTarget()` with a protocol-specific matcher. For NVMe-oF, compare resource UUID only to resource UUID, then compare `(NQN, NSID)`; detect duplicates. Change `nvmeNamespaceRows()` so actual path and size use explicit runtime fields first and never silently borrow another row. Add a refresh generation token and atomically commit subsystem, namespace, ACL, and inventory results. |
| `ui/public/locales/ko_KR.json`, `ui/public/locales/en.json` | Preserve the existing session labels and add only missing labels for `entire backing volume`, mapping status, ambiguous mapping, and runtime observation time. JSON validation and raw-key browser checks are mandatory. |
| `systemvm/debian/usr/local/bin/ablestack-storagectl` | No behavior change is required for this correction. Its enriched inventory already emits NQN, NSID, enabled state, runtime backing path, and serial-resolved size. Keep the composite identity contract and add regression fixtures only if the inventory format changes. |

Compatibility and failure rules:

- No database schema migration is required.
- Older management responses without explicit runtime observation fields remain
  readable through the same composite UI fallback.
- A mapping warning must not be converted into a healthy path by falling back to
  the first inventory row.
- A transient Linux device name may change after reboot without changing the
  namespace identity or causing a false error.

### Storage Service Endpoint Identity Invariants

The VM NIC primary address and service listener aliases are different identity
classes. A secondary listener must never replace the primary address because of
array order or the last endpoint operation.

| Layer | Required invariant |
| --- | --- |
| Backend payload | NIC `primaryIp` is the immutable VM NIC primary address from DB. Each listener alias carries `listenIp`, `port`, `protocol`, `isAlias`, and `coveredByWildcard`. |
| SystemVM state | Store `interfaces[]` and `aliases[]` separately. Alias application never mutates interface `primaryIp`. |
| Inventory | Derive live addresses from `ip -j addr`, reconcile them with backend NIC identity, and mark secondary addresses as aliases. Never infer primary from list order. |
| UI | Show all effective endpoints. Apply the `primary` badge only to the backend/runtime-agreed primary address. |

This correction uses a versioned desired-state contract and does not require a
database schema change.

#### Cross-protocol listener inventory contract

NFS, SMB, iSCSI, and NVMe-oF must expose listener information through one
management contract even though their runtime listener implementations differ.
The canonical listener identity is `(instanceId, protocol, listenIp, port)`.
A share, target, subsystem, or namespace may reference a listener port group,
but it must not become the source of listener inventory.

The integrated runtime validation on SharedFS
`63efbcd0-65ee-4e73-bf92-bfe09c62703a` proved that all four protocol data paths
and reboot reconciliation are healthy. The remaining defects are management
inventory, NIC identity, and UI composition defects. This change therefore does
not alter the SystemVM desired-state or monitor-cache schema.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Listener source | NFS, SMB, and iSCSI screens infer endpoints from protocol objects, shares/targets, or defaults. NVMe-oF has an explicit listener table. | `listStorageServiceProtocols` is the authoritative desired-listener source for every protocol. Runtime cache enriches state only; shares and targets never create listener rows. |
| Wildcard listener | `0.0.0.0:<port>` may be hidden, expanded differently, or replaced by the first service IP. | Preserve the logical wildcard row and return its effective endpoints as the immutable primary IP plus all service aliases. |
| Dedicated listener | A configured alias can be displayed as the VM primary address because UI sources are merged by arrival order. | Return `primaryIp` and endpoint roles explicitly. A dedicated listener is rendered as an alias unless it equals the authoritative primary IP. |
| Protocol tabs | Only NVMe-oF exposes a listener-port-group table. | NFS, SMB, iSCSI, and NVMe-oF all render the same table grammar: listen IP, port, listener type, effective endpoints, runtime state, linked-resource count, and actions. |
| Refresh | Collection requests mutate `storageService` independently while `Promise.all` is still resolving. | Fetch into an immutable generation-scoped snapshot and commit all protocol/resource/ACL/runtime arrays once. |
| NFS commands | `nfsConnectionCommands` calls a missing `nfsRuntimeEndpointDetails()` method. | One tested endpoint adapter supplies the status summary, connection examples, listener table, and export endpoint display. |
| Active service summary | A protocol is considered active only when a share/target/subsystem array is non-empty. | Enabled protocol listener rows are authoritative, so an enabled service with no child resource remains visible. |

API response changes are backward-compatible additions to
`StorageServiceProtocolResponse`:

| Field | Type | Contract |
| --- | --- | --- |
| `listenertype` | string | `WILDCARD` for `0.0.0.0`/`::`; otherwise `DEDICATED`. |
| `primaryip` | string | Authoritative VM NIC primary IPv4 address. It is never selected from endpoint ordering. |
| `effectiveendpoints` | list | Structured endpoint rows containing `ipaddress`, `port`, `role`, and `coveredbywildcard`. |
| `runtimestate` | string | `LISTENING`, `UNUSED`, `ERROR`, or `UNKNOWN`. Desired state remains in the existing `state` field. |
| `linkedresourcecount` | integer | Number of exports, shares, targets, or namespaces that reference the listener port group. |

`StorageServiceProtocolEndpointResponse` is the nested response object for
`effectiveendpoints`. The server must build it from one instance-scoped endpoint
inventory, not by querying the NIC and runtime cache once per listener row.

Required server changes:

1. `StorageServiceManagerImpl.listStorageServiceProtocols` loads the instance,
   protocol rows, authoritative NIC identity, secondary IPs, and runtime
   listener observation once. It then calls
   `createProtocolResponse(protocol, listenerContext)` and sorts responses by
   protocol, port, and listen IP.
2. `buildProtocolListenerContext` keeps `primaryIp`, aliases, and runtime
   observations separate. Wildcard expansion includes the primary and aliases;
   dedicated expansion contains only its configured IP.
3. `linkedResourceCount` is computed by protocol-specific reference readers:
   NFS export listener ports, SMB share exposure, iSCSI target listener ports,
   and NVMe-oF namespace listener ports. It is informational and must not alter
   deletion validation.
4. An enabled protocol row is returned even when it has no linked resource.
   The server must not synthesize a default row when no persisted listener
   exists.
5. `registerProtocolListenAddress` remains a narrow transaction: it may set only
   the secondary-IP flag and insert `nic_secondary_ips`. It must compare the NIC
   primary address before and after the transaction and roll back on drift.
6. Existing instances with DB/runtime primary disagreement are reported as an
   identity warning. Automatic repair is prohibited; repair requires an
   explicit maintenance operation using deployment and runtime evidence.

Protocol-specific display rules:

- NFS `V4_ONLY`: each listener port group is displayed independently; exports
  show the groups they reference.
- NFS `V3V4_DUAL`: show the service-wide fixed listener group and its effective
  IPs; per-export listener selection remains unavailable.
- SMB: Samba's wildcard TCP 445 listener is one logical wildcard group. The UI
  displays all effective service IPs without pretending that separate `smbd`
  listeners exist.
- iSCSI: each LIO portal IP/port is a dedicated listener group; linked target
  count is shown.
- NVMe-oF: keep the current wildcard/dedicated model and row-action constraints,
  but render it through the same common adapter.

Required UI changes in `SharedFSTab.vue`:

1. Replace protocol-specific endpoint inference with
   `protocolListenerRows(protocol)` and `protocolListenerColumns(protocol)`.
2. Add a listener-port-group table to NFS, SMB, and iSCSI using the same
   dark-mode, fixed-right action-column, compact-scroll, ellipsis, and no-data
   conventions already used by NVMe-oF.
3. Replace the missing `nfsRuntimeEndpointDetails()` call with the common
   endpoint adapter. The NFS status card and connection examples must consume
   the same rows as the NFS listener table.
4. Compute `activeServiceTypes` from enabled protocol rows and use child
   resources only as compatibility evidence for older responses.
5. Replace mutating `fetchCollection` calls with pure collection loaders.
   `fetchStorageServiceData` commits one complete snapshot only when the refresh
   generation still matches.
6. Remove fabricated default listener rows when the current API is available.
   Empty or failed protocol inventory shows the standard no-data/error state
   instead of a healthy-looking default port.

Implementation tests:

- `StorageServiceManagerImplTest` covers wildcard expansion, dedicated
  listeners, deterministic ordering, linked-resource counts, empty-child
  protocol visibility, and primary-IP preservation during two alias retries.
- `SharedFSTab.spec.js` covers the four protocol adapters, the missing NFS
  endpoint-method regression, wildcard endpoint expansion, atomic snapshot
  commit, active service calculation, dark-mode table classes, and no raw i18n
  keys.
- API compatibility tests confirm older clients can ignore the added fields and
  that no database migration is required.

#### Primary NIC preservation transaction

Registering a service alias is an ownership transaction, not a NIC identity
update. The current raw `NicVO` update can write every field from a stale entity
while only intending to set the secondary-IP flag. The implementation must
preserve and verify the original primary address.

| Component | Required change |
| --- | --- |
| Backend registration | Capture `originalPrimaryIp` and NIC version before registration. Persist the secondary-IP flag and `nic_secondary_ips` row in one transaction using a narrowly scoped DAO/service operation. Reload the NIC and fail/rollback if `ip4_address` changed. |
| Guest apply | Send the immutable primary IP and the requested alias as separate payload fields. QGA applies the alias but never rewrites the primary address. |
| API inventory | Return `primaryIp` from the authoritative NIC identity and aliases from `nic_secondary_ips`. Do not pick a primary from endpoint order. |
| UI summary | Render the primary badge only when DB and runtime agree. If they disagree, show a consistency warning and list the runtime address and aliases without silently relabeling an alias as primary. |
| Existing inconsistent rows | Repair requires an explicit maintenance path using authoritative deployment/runtime evidence. Never infer the original primary from the latest alias or array order. |

Required tests:

- Adding two aliases leaves the NIC primary address byte-for-byte unchanged.
- Retrying an existing alias is idempotent and does not alter the primary.
- QGA failure removes only the new alias/protocol row and preserves the NIC.
- Reboot inventory continues to classify the same primary and aliases regardless
  of address ordering returned by the guest.

#### Runtime NIC reconciliation and desired-state finalization

Integrated validation on SharedFS
`636bab5d-553b-451e-8f02-f792ea83b8b3` confirmed that NFS, SMB, iSCSI, and
NVMe-oF listeners, data paths, and reboot reconciliation are healthy. The
remaining failure is a management-plane identity drift: the guest primary
address is `10.10.254.140`, while `nics.ip4_address` contains listener alias
`10.10.22.202`. The same alias also exists correctly in `nic_secondary_ips`.

The drift has two independent write hazards and one response amplification:

1. `registerProtocolListenAddress` loads a complete `NicVO` and calls the
   generic DAO update while intending to change only `secondary_ip`.
2. `StatsCollector.VmStatsCollector` receives one IPv4 value per MAC from the
   KVM/QGA statistics path and writes it directly to `nics.ip4_address` for L2
   NICs. The KVM collector currently overwrites the map value for every IPv4
   address returned for the interface, so the last alias can replace the VM
   primary.
3. `buildProtocolResponseContext` trusts the corrupted DB value as primary and
   wildcard expansion consequently omits the actual runtime primary address.

The NFS desired-state file has a separate finalization defect. NFS create and
update flows serialize the current DB workflow state (`Creating` or `Updating`)
before the DB row is moved to `Ready`. `ablestack-storagectl` correctly persists
the successful payload, but that payload remains transient and is reused during
boot reconcile.

| Component | AS-IS | TO-BE |
| --- | --- | --- |
| KVM NIC statistics | `Map<MAC, IPv4>` may contain any one IPv4 returned by QGA, so it cannot identify the primary when aliases exist. | Keep the existing agent wire contract unchanged. Treat the single address as a non-authoritative observation and do not deploy a newer KVM resource class into the older host runtime. |
| `StatsCollector` | Every reported L2 address can replace `nics.ip4_address`. | Treat VM statistics as observation. Never replace a populated DB primary from the single-address agent map. Populate an empty primary only when the observed value is not present in `nic_secondary_ips`; otherwise warn and skip. Storage Service aliases never update the primary. |
| Alias registration DAO | Generic `nicDao.update(id, NicVO)` can write unrelated stale fields. | Add a narrow DAO operation that updates only `secondary_ip`, guarded by NIC id and expected primary IP. Insert the alias row in the same transaction and fail on a zero-row optimistic update. |
| Protocol response | DB primary is trusted even when runtime disagrees; wildcard endpoints can omit the actual primary. | Load DB primary, secondary rows, and runtime interface observations once. Return both identity sources, an explicit consistency status, and a deduplicated endpoint union. Never label a known alias as primary during drift. |
| Existing drift | No safe automated correction exists. | Provide an admin-only dry-run/repair operation. Repair is allowed only when one default NIC/MAC is identified, runtime reports one non-secondary primary, and the incorrect DB value is already recorded as an alias. Emit an audit event and do not change guest networking. |
| NFS desired state | Persisted payload can contain `Creating` or `Updating` after a successful apply. | Persist only operational desired state (`Ready`) for resources included in an apply payload. Keep workflow state in DB/event history; do not leak it into the boot-reconcile contract. |
| UI refresh | Instance metadata can be committed before protocol, runtime, and resource collections finish. | Build one generation-scoped immutable snapshot and commit instance, protocols, resources, ACLs, runtime inventory, sessions, and volumes together. |

Backward-compatible API additions to `StorageServiceProtocolResponse`:

| Field | Contract |
| --- | --- |
| `runtimeprimaryip` | Primary IPv4 observed on the matching runtime interface. It is diagnostic and does not mutate DB identity. |
| `identitystatus` | `CONSISTENT`, `DRIFT`, or `UNKNOWN`. |
| `identitywarning` | Localized warning input containing DB primary and runtime primary when they disagree. |

`effectiveendpoints` keeps its existing shape. During `DRIFT`, wildcard
expansion includes the runtime primary and every persisted alias exactly once;
the conflicting DB address is returned with alias role when it exists in
`nic_secondary_ips`. Dedicated listeners remain limited to their configured
IP and are never broadened by the drift fallback.

Code-level implementation targets:

| File/object | Required change |
| --- | --- |
| `server/.../StatsCollector.java` | Move NIC updates behind `reconcileObservedNicAddresses`. Preserve every populated primary regardless of the legacy single observation. Exclude every `nic_secondary_ips` value and populate an empty primary only from one non-alias observation. |
| `engine/schema/.../NicDao.java`, `NicDaoImpl.java` | Add field-scoped secondary-IP flag update with expected-primary guard. Do not call the generic full-row update for alias registration. |
| `server/.../StorageServiceManagerImpl.java` | Use the narrow alias transaction, construct one identity context per instance, add consistency response fields, and normalize applied NFS resource states to `Ready`. |
| `api/.../StorageServiceProtocolResponse.java` | Add the backward-compatible runtime-primary and identity-status response fields. |
| `api/.../RepairStorageServiceNicIdentityCmd.java` | Add an admin-only command with `sharedfilesystemid`, `dryrun` (default `true`), and `expectedruntimeprimary`. Reject ambiguous runtime/default-NIC/MAC evidence. |
| `ui/.../SharedFSTab.vue` | Consume one identity adapter for summary cards and all protocol listener tables; display a drift warning and commit refresh data atomically. |

`repairStorageServiceNicIdentity` performs no guest command. Dry-run returns the
DB primary, runtime primary, aliases, NIC id, and eligibility reason. Apply is
allowed only when the caller repeats the expected runtime primary, the DB value
is a persisted alias, and the same default NIC/MAC still reports the runtime
primary. The transaction updates only `nics.ip4_address`, preserves all alias
rows, and emits an administrator event containing before/after values.

`applyNfsDesiredState` must not move the DB object to `Ready` before runtime
success. Instead, `createNfsDesiredStatePayload` writes `Ready` as the desired
operational state for every included listener, export, and ACL. The existing
DB workflow continues to transition `Creating/Updating -> Ready` only after a
successful QGA apply; failure transitions and rollback behavior remain
unchanged.

No database schema migration is required. Existing `nics` and
`nic_secondary_ips` rows contain enough evidence for guarded repair. No
SystemVM runtime or template change is required because listener execution,
monitor-cache collection, storage paths, and reboot restore passed. A SystemVM
preflight becomes mandatory only if implementation later changes the monitor
schema or desired-state consumer.

Deployment is management and UI only. Host agent JARs are intentionally left
unchanged: the running 22.x agent runtime is not aligned with the current KVM
resource class and a class-only hot patch can introduce unrelated script
dependencies. The management fix accepts the existing single-address stats
contract and prevents it from overwriting a populated primary. UI deployment
follows the existing served-asset hash gate.

Required regression tests:

- QGA IPv4 order permutations never change a populated primary into an alias.
- A legitimate first primary observation can populate an empty L2 NIC address.
- Alias registration updates only `secondary_ip` and `nic_secondary_ips`.
- DB/runtime drift returns `DRIFT`, includes the actual runtime primary in a
  wildcard endpoint, and never expands a dedicated listener.
- NFS create, export update, and ACL update persist `Ready` desired-state rows
  after a successful apply; boot reconcile sees no transient state.
- One UI refresh generation cannot expose new instance metadata with stale or
  empty protocol collections.

#### Management UI deployment integrity gate

The management web root contains both generated UI assets and the management
API servlet descriptor. UI deployment must therefore preserve server-owned
files and prove that the static UI and API runtime belong to one healthy
release.

| Target | Deployment rule |
| --- | --- |
| `/usr/share/cloudstack-management/webapp` | Synchronize generated UI assets without deleting `WEB-INF/**`. Stage first, preserve `WEB-INF/web.xml`, then switch or sync atomically. A plain `rsync --delete` against this root is prohibited. |
| `/usr/share/cloudstack-ui` | Replace the generated static tree atomically. This compatibility root does not own the management servlet descriptor. |
| Management JAR | Install the aligned aggregate JAR before restarting `mold.service`; retain a timestamped backup of both JAR and web roots. |
| Post-deploy runtime | Require `mold.service=active`, `GET /client/ = 200`, and unauthenticated `GET /client/api/?command=listApis&response=json = 401`. A static-page 200 without the API 401 is a failed deployment. |
| Asset consistency | Parse the served `index.html`, require every referenced JS/CSS asset to return 200, and compare served entry/locale hashes with the local build. Run a fresh-browser smoke test that finds no raw `label.storage.service.*` key. |

If `WEB-INF/web.xml` is absent after staging, restore it from the same deployed
management JAR (`META-INF/webapp/WEB-INF/web.xml`) or the timestamped backup
before service restart. This is recovery only; the normal deployment path must
never remove it.

Runtime can use `nvmetcli` if available, or manage Linux `configfs`
directly through `ablestack-storagectl`.

The first implementation uses the shared `storage_block_target` table for both
subsystems and namespaces. A subsystem row owns the NQN and host ACLs, and
namespace rows reuse the same NQN with their namespace ID and backing volume.

NVMe-oF desired-state lifecycle consistency:

- The API/engine may use transient DB states such as `Creating` or `Updating`
  while an async job is applying a subsystem, namespace, or host ACL.
- Transient states must not be persisted into the System VM desired-state files
  after a successful apply. The QGA payload should represent the final intended
  runtime state for the object being reconciled.
- For the current state model, the successful runtime state is `Ready` when a
  Storage Service System VM exists and `Allocated` when the object is stored but
  cannot yet be applied because no System VM is mapped.
- If QGA apply fails, the engine must persist the affected API object as
  `Error` and return the failure to the async job. This prevents stale
  `Creating`/`Updating` rows from being shown as pending forever and prevents
  the System VM state file from disagreeing with API state after a later
  successful reapply.
- Host ACL secret values remain runtime-only: DH-HMAC-CHAP keys may be present
  in the one-time QGA payload but must not be retuned by list APIs or stored in
  UI state.

### NVMe-oF Engine Modes And VM Preparation

NVMe-oF should support two target engines:

- `KERNEL_NVMET`
  - Default first implementation.
  - Uses Linux kernel `nvmet` with configfs and TCP transport.
  - Requires kernel modules such as `nvmet`, `nvmet-tcp`, and `configfs`.
  - Does not require guest hugepages by design.
- `SPDK`
  - Planned high-performance mode.
  - Uses an SPDK NVMe-oF target process.
  - Requires future VM Runtime Capability support for HugePage, NUMA, CPU
    pinning, memlock, and optional PCI passthrough or SR-IOV/VF assignment.
  - Stays in this Storage Service design only as protocol metadata,
    prerequisite reporting, and a later integration target.

The API `prepareStorageServiceNvmeOfVm` should prepare or validate the System VM
for the requested engine before NVMe-oF subsystems are enabled.

Parameters:

- `instanceid`
- `engine`: `KERNEL_NVMET` or `SPDK`
- `transport`: initially `tcp`
- `runtimecapabilityprofileid`: future parameter, accepted only after VM
  Runtime Capability support is implemented
- `validateonly`: report missing prerequisites without changing the VM

Management-side VM preparation:

1. Validate the Storage Service System VM service offering.
2. For `KERNEL_NVMET`, ensure the template has `nvme-cli`, `nvmetcli` when
   available, and a kernel with NVMe target modules.
3. For `SPDK`, do not attempt guest-side HugePage or NUMA changes from Storage
   Service. Return `PreparationRequired` until a VM Runtime Capability profile
   can be attached to the System VM.
4. Generate a desired capability document in `StorageServiceProtocol.config_json`.
5. Send QGA `nvmeof prepare` to the System VM only for supported engine states.

Deferred SPDK integration:

1. VM management adds Runtime Capability profiles for HugePage, NUMA, CPU
   pinning, memlock, and optional passthrough or SR-IOV.
2. Storage Service accepts `runtimecapabilityprofileid` for SPDK requests and
   validates that the target System VM was created or restarted with that
   profile.
3. The System VM template verifies SPDK runtime packages, `setup.sh`
   prerequisites, `hugetlbfs`, service limits, and SPDK target service state.
4. QGA `nvmeof prepare` reports SPDK readiness only after the VM-level runtime
   profile is present and active.

The first implementation must keep `KERNEL_NVMET` as the only enabled NVMe-oF
engine. The UI may show `SPDK` as planned or unavailable, but it must not offer
HugePage, CPU, NUMA, memlock, SR-IOV, or PCI passthrough controls inside the
Storage Service workflow.

## UI Design

The UI must extend the existing `Shared FileSystems` menu, creation dialog, and
detail page. The first implementation must not introduce a completely separate
required menu for the expanded Storage Service workflow. Operators should start
from the existing `Shared FileSystems` list and use the existing
`Create Shared FileSystem` action to create a SharedFS-backed Storage Service.
The detail page is reserved for post-creation operations and ongoing management.

The UI implementation must follow the existing Vue and Ant Design Vue patterns
used by the ABLESTACK UI. The SharedFS extension must reuse the current
section/action, detail tab, async job polling, status, metric, and event tab
conventions instead of introducing an unrelated visual system.

Styles must be compatible with both normal and dark modes. New components should
use existing Ant Design Vue component states and local ABLESTACK UI styling
conventions. Local SharedFS extension styles must use theme-friendly borders,
backgrounds, inherited text colors, and restrained neutral surfaces so colors,
forms, status indicators, disabled states, lists, and runtime JSON remain
readable in both modes.

SharedFS creation dialog extension:

- The existing `Create Shared FileSystem` dialog becomes the primary creation
  workflow for Storage Service-backed SharedFS instances.
- The dialog must allow the operator to select one or more initial services:
  `NFS`, `SMB`, `iSCSI`, and `NVME_OF`.
- At least one service must be selected. For backward compatibility, `NFS`
  remains the default selection when the expanded Storage Service feature is
  enabled and no explicit protocol selection is supplied.
- The dialog must create the SharedFS/SystemVM resource and then create or
  reconcile the corresponding Storage Service model through async Cloud APIs.
- Protocol-specific desired state is submitted after the SharedFS VM exists and
  the Storage Service instance is available. The UI must poll each async job and
  present creation progress without running any storage command directly.

Creation dialog structure:

- `Owner type`
  - stays at the top of the dialog, before the main creation form, because
    ownership affects all subsequent resource choices
  - is collapsed by default for operators because the default owner is usually
    correct and rarely edited during normal creation
  - the collapsed header must keep the section title as `Owner type` and append
    the currently selected type/domain/account or project summary in
    parentheses, for example `Owner type (Account / ROOT / admin)`
  - expanding the section exposes the existing owner selector without changing
    its behavior
- `Basic information`
  - name, description, zone, network, filesystem, service offering, disk
    offering, data disk size in GiB for new-volume mode, and custom IOPS
  - data disk size represents the ABLESTACK backing volume size, not an
    NFS/SMB share quota or an iSCSI/NVMe-oF protocol object size
  - when existing-volume mode is selected, data disk size is disabled or hidden
    and the selected existing volume's actual size is shown instead
- `Volume and backing capacity`
  - must appear immediately after `Basic information` and before protocol
    service configuration
  - owns only physical/backing storage decisions: new data volume, existing
    detached ABLESTACK volume, selected volume size, import mode, backing
    filesystem, and whether backing data disk resize is allowed
  - detached candidate volumes must be selected from a dropdown, not typed as a
    raw volume ID, and the UI must display the selected volume's actual size
  - the UI label for resize permission should be `Allow data disk resize when
    required` / `필요 시 데이터 디스크 확장 허용`
  - the help text must explain that this controls whether the ABLESTACK
    backing volume may be resized to satisfy a larger export/share/LUN/namespace
    request; it is not the same as the service capacity limit
  - service-level quota, LUN size, and namespace size do not belong in this
    section; they belong to the owning protocol section below
- `Service selection`
  - compact selectable service cards or checkboxes for NFS, SMB, iSCSI, and
    NVMe-oF
  - desktop card layout must show one or two cards per row only; four services
    should be shown as a balanced two-by-two grid, not a three-plus-one layout
  - clear selected and unavailable states
  - a visible validation message when no service is selected
- `NFS`
  - visible when NFS is selected
  - initial export name, path, read/write policy, root squash, sync/secure
    flags, allowed CIDR ACL, and export capacity limit
  - export capacity limit is scoped to this NFS export path and is entered as a
    number plus `B`/`MiB`/`GiB`/`TiB`, then submitted as `quotabytes`
- `SMB`
  - visible when SMB is selected
  - initial share name, path, guest/browse/read-only flags, SMB ACL intent, and
    share capacity limit
  - share capacity limit is scoped to this SMB share path and is entered as a
    number plus `B`/`MiB`/`GiB`/`TiB`, then submitted as `quotabytes`
  - local account mode by default
  - local account mode must collect an initial local user name, password,
    password confirmation, and ACL permission. The password is submitted only in
    the asynchronous ACL request and must never appear in the review panel,
    result tables, monitor cache, browser storage, or API logs.
  - optional Active Directory domain join section with domain, usename, target
    OU, DNS/server hints, and sensitive password submission
- `iSCSI`
  - visible when iSCSI is selected
  - initial target name, target IQN, LUN size, LUN or backing volume, initiator
    IQN ACL, CHAP use, CHAP user/secret, mutual CHAP use, and mutual CHAP
    user/secret
  - LUN size is entered as a number plus `B`/`MiB`/`GiB`/`TiB`; it is not a
    file share quota
  - the review panel shows only whether CHAP and mutual CHAP are enabled, never
    the secret values
- `NVMe-oF`
  - visible when NVMe-oF is selected
  - initial subsystem NQN, namespace size, namespace or backing volume, engine
    selection, host NQN ACL, optional DH-HMAC-CHAP host/controller
    authentication, and prerequisite-gated SPDK state
  - namespace size is entered as a number plus `B`/`MiB`/`GiB`/`TiB`; it is not
    a file share quota
  - DH-HMAC-CHAP keys are available only when a host NQN ACL is configured, and
    the review panel shows only whether authentication is enabled
  - if the current SystemVM template/kernel capability does not support
    DH-HMAC-CHAP, the host and controller authentication switches are disabled
    and the dialog shows an explicit unsupported message
- `Existing volume`
  - this is part of `Volume and backing capacity`, not a protocol sub-section
  - supports attaching an existing ABLESTACK volume to the new service
  - import mode must distinguish inspect-only, mount-existing, and format/new
    workflows so destructive operations require an explicit confirmation
- `Capacity`
  - do not use one global capacity section for both physical backing disk and
    service limits
  - physical backing disk controls belong to `Volume and backing capacity`
  - service capacity controls belong to the owning protocol section:
    NFS export capacity limit, SMB share capacity limit, iSCSI LUN size, and
    NVMe-oF namespace size
- `Review`
  - summarizes selected services, network, storage, identity, access rules,
    destructive actions, and known prerequisites before submission
  - review items must prioritize value readability over fixed two-column
    alignment. Labels and values should be stacked within the narrow review
    panel so long SharedFS names, network names, volume labels, IQNs, NQNs, and
    other identifiers can use the full panel width.
  - review values must allow safe wrapping for long identifiers with no natural
    whitespace. Do not truncate critical identifiers unless a tooltip or copy
    path preserves the full value.

Dialog usability rules:

- The dialog must be sectioned and progressive. It should not present every
  protocol field at once.
- The dialog layout must use a two-column operator workflow on desktop:
  - the left column is a persistent review/summary panel for selected services,
    name, zone, network, filesystem, identity mode, existing-volume mode, and
    import mode
  - the right column contains the actual configuration sections
  - on narrow/mobile viewports the columns collapse to a single column
- The review panel is intentionally narrow. It must avoid fixed label/value
  columns that leave the value column too small. Each item should render as a
  compact label above a full-width value with `overflow-wrap` behavior suitable
  for long technical identifiers.
- The dialog action area must stay fixed or sticky at the bottom of the modal
  with `Cancel` and `OK` actions so long configuration sections do not hide the
  final decision controls.
- The dialog content height must never exceed the visible browser viewport. The
  modal body should use a bounded flex layout where owner selection, alert, and
  action areas are fixed-height regions, and only the central review/config
  region scrolls. The action buttons must not overlap protocol sections.
- Sections should be ordered by operator decision flow: identity and placement,
  backing volume/capacity, service selection, service-specific configuration,
  access, then review.
- Required fields should appear only when their owning service is selected.
- Disabled or prerequisite-gated options must explain the reason in place.
- Advanced fields should be collapsed by default and use existing Ant Design
  Vue controls such as collapse panels, alerts, checkboxes, switches, selects,
  and input groups.
- Info and waning alerts must remain legible in dark mode, including the alert
  icon, message text, border, and background. Avoid high-contrast filled alert
  surfaces that hide icons or make labels unreadable.
- The primary action should stay disabled until the minimum valid configuration
  is present. Validation errors must scroll to the first failing section.
- Async creation progress should show the current phase: SharedFS creation,
  Storage Service model creation, protocol enablement, export/share/target
  creation, ACL creation, and final refresh.
- The dialog must take an immutable setup snapshot at submit time and use that
  snapshot for all dependent asynchronous protocol/share/ACL jobs. Follow-up
  setup must not read mutable modal form state after the modal is closed or
  after password fields are cleared.
- The creation dialog must not display final Storage Service success merely
  because the SharedFS VM creation job succeeded. It must either complete and
  verify the selected initial service setup jobs or show a clear partial-create
  error with a retry path in `File Service Management`.
- Initial service setup must be treated as one UI-managed asynchronous
  transaction with explicit phases:
  `CREATE_SHAREDFS`, `RESOLVE_SHAREDFS`, `RESOLVE_STORAGE_SERVICE_INSTANCE`,
  `ENABLE_PROTOCOLS`, `CREATE_PROTOCOL_RESOURCES`, `CREATE_ACCESS_RULES`, and
  `VERIFY_RUNTIME_STATE`.
- The UI must wait for the complete setup transaction before closing the create
  dialog or showing a final success message. A successful `createSharedFileSystem`
  job alone is only a partial result.
- After `createSharedFileSystem` completes, the UI should reload the created
  SharedFS by ID and use that fresh response as the preferred source for VM,
  backing volume, zone, network, and account information. This reload is a
  freshness step, not the only gate for follow-up setup. If the reload returns
  a different wrapper shape or temporarily returns no row, the UI must fall back
  to the completed async job result and continue setup when the SharedFS ID and
  VM ID are already available.
- The setup code must accept both current and legacy response wrappers, including
  `sharedfs`, `sharedfilesystem`, `sharedfilesystems`, singular objects, arrays,
  and direct job-result objects. It must fail visibly only when the created
  SharedFS ID or the VM ID required to map the Storage Service instance cannot
  be resolved from either the async job result or the follow-up list response.
- The SharedFS creation result and initial Storage Service setup result must be
  reported separately. SharedFS VM creation success followed by protocol/share/
  ACL setup failure is `Created / setup incomplete`, not a failed VM creation
  and not full success.
- The creation dialog is an input collection surface only. After the operator
  presses `OK` and the create request is accepted, the dialog must close
  immediately. Long-running work must continue as background asynchronous setup
  with a persistent top notification or task banner that reports the current
  phase and final result.
- Background setup notification phases must be operator-readable:
  `SharedFS creation accepted`, `SharedFS VM creation`, `Storage Service
  instance resolution`, `protocol enablement`, `share/export/target creation`,
  `ACL/account/domain application`, and `runtime verification`.
- Partial failure notification must say which part failed. For example,
  `SharedFS was created, but SMB access rule application failed`. The detail
  page must remain reachable so the operator can inspect the partially created
  service and retry management actions.
- SharedFS stop responses must be safe even when a response lookup receives an
  empty ID list after a state transition. DAO response builders must return an
  empty result instead of emitting an invalid `IN ()` SQL clause, and lifecycle
  stop must pass the operator's forced-stop flag through to the VM stop layer.
- The UI must guarantee that selected service follow-up APIs are attempted after
  SharedFS creation: `enableStorageServiceProtocol` first, then the selected
  export/share/target and ACL/account/domain operations. A response parsing
  mismatch in the SharedFS refresh path must never silently skip these calls.
- Protocol enablement, share/export/target creation, ACL creation, and final
  verification errors must not be swallowed. If any selected initial service is
  not configured, the result is `Created / setup incomplete`, not success.
- Runtime monitor/cache verification is not a blocking creation gate. The final
  initial-setup result must be based on successful asynchronous API jobs and QGA
  desired-state application for protocol/export/share/target/ACL resources.
  Monitor cache inventory may lag immediately after apply, so a stale or empty
  cache row must not produce the partial-create error notification. The UI may
  poll the cache briefly for display freshness and log a non-blocking pending
  state, but API object existence and QGA job success remain the correctness
  gates.
- When creating a new initial backing data disk, the create dialog must require
  an explicit primary storage selection and send it as `storageid` together
  with disk offering and size. Existing-volume flows do not require `storageid`
  because the selected volume already determines its storage location. The UI
  must order the fields as disk offering first and primary storage second. When
  the selected disk offering has storage tags, the primary storage list must
  include only pools that satisfy all offering tags; when the offering has no
  tags, all usable pools in the zone may be shown. The backend must repeat the
  same validation before deployment. The lifecycle implementation must enforce
  the selected pool for the initial backing volume, either by passing the pool
  into the deployment path or by creating/attaching the initial backing volume
  through an explicit volume workflow. A mismatched or unavailable pool must
  fail before partially configured Storage Service resources are exposed.
- SMB local account application inside the Storage Service System VM must be
  idempotent. For a local user ACL:
  - if the Linux user already exists, update/enable the Samba password only;
  - if the Linux user does not exist but a same-name group exists, create the
    user with that existing primary group instead of failing;
  - if neither user nor group exists, create the user normally;
  - reject empty or unsafe local user names before invoking OS account tools;
  - repeated application of the same ACL must not fail because of existing
    users, groups, or Samba passdb entries.
- SMB ACL desired-state rows must not mislead operators after guest-side apply
  failures. The backend should either roll back the row or mark it `Error` with
  `last_error`; the UI must display only actually applied rows as `Ready`.
- `File Service Management` must expose a retry action for incomplete initial
  setup. Retry APIs and UI orchestration must be idempotent: enabling an already
  enabled protocol or creating an already existing desired object should return
  the existing state or reconcile it rather than creating duplicates.
- The progress UI should show each setup phase and the current service being
  configured. This is required so operators can distinguish VM creation delays
  from Storage Service protocol setup failures.
- Sensitive fields, especially SMB AD credentials, must not be persisted in UI
  state beyond submission and must not be shown in review text.
- SPDK may appear only as planned or prerequisite-gated. HugePage, CPU pinning,
  NUMA, memlock, SR-IOV, and PCI passthrough must not appear in this creation
  dialog because they belong to the future VM Runtime Capability feature.
- The creation dialog must explicitly support normal and dark mode. Section
  containers, collapse content, review panels, service cards, form labels, input
  backgrounds, borders, waning/info surfaces, and sticky footer surfaces must
  use theme-friendly inherited colors and restrained neutral surfaces. The UI
  must not leak a hard-coded light background into dark mode.

SharedFS detail extension:

- Storage Service creation controls must not be the primary workflow in the
  detail tab. If an existing legacy SharedFS resource has no mirror, the detail
  tab may show a reconcile action for administrative recovery only.
- The detail page must split service operation and service monitoring into two
  separate tabs:
  - `File Service Management`, displayed in Korean as `?뚯씪 ?쒕퉬??愿由?.
  - `File Service Status`, displayed in Korean as `?뚯씪 ?쒕퉬???곹깭`.
- The `?뚯씪 ?쒕퉬??愿由? tab owns configuration and state-changing workflows:
  - mirrored Storage Service instance summary, provider, VM ID, and service IPs
  - protocol enablement for NFS, SMB, iSCSI, and NVMe-oF listen IP and port
  - NFS export, capacity limit, root squash, sync/secure flags, and ACL creation
  - SMB share, capacity limit, guest/browse/read-only flags, ACL creation, and
    Active Directory domain join form
  - iSCSI target creation/listing and initiator ACL creation
  - NVMe-oF preparation, subsystem creation/listing, and host NQN ACL creation
  - existing ABLESTACK volume attach/import to a file share with inspect-only
    or mount-existing mode
  - file share capacity expansion and optional backing ABLESTACK volume resize
- The `?뚯씪 ?쒕퉬???곹깭` tab owns monitoring and read-only runtime views:
  - QGA-backed health result
  - service IPs and endpoint summary
  - active NFS/SMB/iSCSI/NVMe-oF object counts
  - runtime inventory
  - active sessions
  - SMB domain status
- Tab labels, form labels, button labels, descriptions, wanings, and empty
  states must use i18n keys. The Korean UI should avoid English text except
  protocol names, technical identifiers, acronyms, command values, or other
  terms that should remain untranslated such as `NFS`, `SMB`, `iSCSI`,
  `NVMe-oF`, `IQN`, `NQN`, `SPDK`, and `QGA`.

SharedFS detail page redesign:

This subsection supersedes the earlier split between `File Service Management`
and `File Service Status`. The detailed UI must now be protocol-oriented while
keeping common service state in the first details tab.

- The legacy `Access` tab must be removed from the expanded Storage Service
  UI. It exposes only the old NFS mount pattern and can show the deprecated
  `/export` root, which conflicts with the new explicit export/share model.
- The previous `File Service Management` and `File Service Status` tabs must be
  replaced by protocol-oriented tabs:
  - `NFS`
  - `SMB`
  - `iSCSI`
  - `NVMe-oF`
- The first `Details` tab remains the common overview tab. It must show both
  the existing SharedFS details and an expanded Storage Service overview:
  - active service types, for example `NFS`, `SMB`, `iSCSI`, and `NVMe-oF`
  - Storage Service instance state, provider, service VM ID, and service VM name
  - service VM power state, host, template, service offering, and data disk
    summary
  - QGA health, last health check status, and last command timestamp
  - service endpoint IPs and ports
  - common capacity summary: backing data disk size, attached volume, and
    whether data disk resize is allowed
  - aggregate object counts such as number of exports, shares, targets,
    namespaces, ACLs, and active sessions
  - wanings for partial setup, missing QGA, missing protocol packages, stale
    desired state, or legacy `/export` root exposure
- Common state belongs in `Details`; protocol-specific state belongs in the
  protocol tab. Do not repeat common SystemVM/QGA status cards in every
  protocol tab unless a protocol-specific error needs to be highlighted.
- Each protocol tab must follow one consistent information architecture:
  - service status: protocol enabled state, daemon/runtime status, listen IP,
    port, last apply result, and last inventory refresh
  - resource inventory:
    - NFS: exports
    - SMB: shares and domain membership summary
    - iSCSI: targets, LUNs, and initiator rules
    - NVMe-oF: subsystems, namespaces, engine state, and host rules
  - capacity and backing storage: backing volume, mount or block path, quota,
    LUN size, namespace size, used/free values when available, and resize
    eligibility
  - connection guidance:
    - NFS mount examples for the selected export path, with a legacy
      `showmount` compatibility example only when `V3V4_DUAL` is enabled
    - SMB UNC and Linux CIFS mount examples for the selected share
    - iSCSI discovery/login examples for the selected target
    - NVMe-oF discovery/connect examples for the selected subsystem
  - access allow list: CIDR, SMB account/group, initiator IQN, host NQN, CHAP,
    mutual CHAP, or DH-HMAC-CHAP state as appropriate
  - active sessions: client, protocol, local/peer addresses, state, associated
    resource when known, age when available, and last refresh time
- All state-changing operations in protocol tabs must use action buttons that
  open modals. Inline state-changing forms should be avoided. This keeps NFS,
  SMB, iSCSI, and NVMe-oF workflows consistent and easier to test.
- Required action modal families:
  - service enable/disable or protocol setting update
  - create/update/delete export/share/target/subsystem/namespace
  - create/update/delete access rule
  - attach existing ABLESTACK volume
  - resize backing data disk or protocol capacity
  - AD join/leave for SMB
  - prepare or validate NVMe-oF engine prerequisites
  - disconnect active session
- Action modals must reuse the completed SharedFS creation modal visual
  language:
  - sectioned layout with clear titles and restrained borders
  - Ant Design Vue form controls and validation states
  - normal and dark mode compatible backgrounds, labels, borders, alerts,
    selected cards, disabled states, tables, and sticky footers
  - fixed footer with `Cancel` and `OK` actions for complex modals
  - no hard-coded light surfaces that leak into dark mode
  - no low-contrast inherited text for radio labels, checkbox labels, table
    text, alert icons, empty states, or command snippets
  - service command examples should use code blocks or copyable text surfaces
    whose background and foreground colors are legible in dark mode
- Protocol tab layout and table standard:
  - This standard applies to all Storage Service protocol tabs: `NFS`, `SMB`,
    `iSCSI`, and `NVMe-oF`.
  - Each protocol tab must fill the available detail-tab width and should avoid
    small isolated panels that leave large unused empty space. Low-data states
    must still render the same sections and tables with clear empty states.
  - The top area should contain a compact protocol summary and right-aligned
    action toolbar. All action buttons must include an icon and open a modal for
    state-changing operations.
  - Dense repeated information must be rendered as tables, not as card lists.
    Use tables for exports/shares/targets/subsystems, ACLs, backing volumes,
    active sessions, and protocol-specific runtime status where multiple rows or
    comparable fields are possible.
  - Non-repeated guidance, wanings, and one-off summaries may use cards. Cards
    must be full-width or part of an intentional grid and must not appear as
    small floating islands.
  - Tables must follow the existing ABLESTACK network-tab table look and feel:
    compact row height, restrained borders, readable header contrast, and
    normal/dark mode compatible background and hover states.
  - Tables with many columns must use internal horizontal scrolling rather than
    breaking the tab layout. The tab itself should remain stable while the table
    body scrolls inside its own boundary.
  - Table scrollbars must be visually small and must match the normal and dark
    mode visual tone.
  - Important identifier columns must remain visible with fixed columns when
    the table scrolls horizontally. At minimum, the resource name or primary
    identifier should be fixed to the left, and row actions should be fixed to
    the right when actions are present.
  - Long values such as volume IDs, share paths, export paths, IP/CIDR lists,
    IQNs, NQNs, session identifiers, command examples, and error strings must
    use single-line ellipsis in table cells with a tooltip that shows the full
    value. Where copying is useful, the tooltip or cell control should expose a
    copy action.
  - Avoid wrapping long protocol identifiers across multiple table lines by
    default. Wrapping may be used only in expanded-row details or explicit
    detail drawers.
  - Tables should support expanded rows or detail drawers for secondary fields
    that would otherwise create too many columns.
  - Empty tables must still display headers and a localized empty message so
    operators can understand which data category is absent.
  - Numeric capacity fields should show human-readable IEC units in the table
    and keep exact byte values available in a tooltip or detail view.
  - Status columns should use compact tags or badges with theme-compatible
    colors. Do not rely only on color; include readable status text.
  - Any inline error, waning, or partial-state indicator in a table must be
    readable in dark mode, including icon, tag text, and tooltip text.
  - Pagination should be avoided for small protocol inventories, but large
    session or ACL tables may paginate inside the table boundary.
  - The same column behavior should be used consistently across service tabs:
    fixed resource identifier, scrollable secondary fields, fixed actions,
    ellipsis plus tooltip for long values, compact scrollbar, and dark-mode
    safe colors.
- NFS tab table standard:
  - `NFS exports` table: export name, client-visible mount root, internal path,
    service IP/port, permission, ACL summary, quota/capacity, backing volume,
    runtime state, and actions.
    The only row action in this table should be file-share capacity expansion;
    ACL creation belongs to the ACL table action area. Do not duplicate the
    same capacity action in both the section header and each row.
  - `NFS ACLs` table: export name, principal or CIDR, permission, root squash,
    all squash, anonymous UID/GID, sync, secure port, runtime state, and
    actions.
    The section action area must include NFS ACL creation. ACL rows are mapped
    to exports by the Storage Service export ID, including `resourceid` fields
    retuned by access-rule APIs.
  - NFS action modals must be vertical forms, not wide horizontal rows. The
    protocol enable modal must include existing/new listen IP mode selection.
    All action modals must be centered in the browser viewport horizontally
    and vertically. The modal header and footer remain fixed, and only the
    body scrolls when the form is taller than the viewport.
    Field help must be provided through required markers, tooltip icons, and
    validation messages. Persistent explanatory text below every normal input
    should be avoided because it makes the dialog noisy; inline help is reserved
    for actual validation errors or exceptional wanings.
    Existing listen IP choices must be deduplicated across NIC primary and
    secondary IP data. When the operator adds a new listen IP, the backend must
    validate that it is in the same CIDR as one Storage Service NIC, reject IP
    conflicts, persist it as a secondary IP for that NIC, and the SystemVM
    runtime must open the selected TCP service port when applying the desired
    protocol state. CIDR validation must not depend only on the NIC `netmask`
    column, because L2/ConfigDrive networks may not expose that value reliably.
    The resolver should try NIC netmask first, then network CIDR or reserved
    network CIDR, then the zone guest CIDR. Runtime application still validates
    the actual guest NIC prefix through the SystemVM command before adding the
    listen IP. If the Cloud DB cannot provide usable CIDR evidence and the
    Storage Service System VM has exactly one NIC, the backend may select that
    NIC as the candidate and defer the final CIDR decision to the SystemVM
    guest command. In that path, the backend must persist the protocol desired
    state first, let the SystemVM apply command validate and add the listen IP,
    and only then register the Cloud secondary IP. If the guest apply fails,
    the backend must rollback the protocol desired state and avoid leaving a
    stale secondary IP row.
    If the selected listen IP is already the primary IP of the candidate
    Storage Service NIC, the backend must not create a duplicate secondary-IP
    record; it should treat the primary address as already registered.
    Initial SharedFS creation must verify NFS runtime state by the
    client-visible export root name, not by the internal backing path. The UI
    must tolerate monitor-cache lag by polling the runtime inventory for a
    bounded period after the export and ACL API jobs complete. A configured
    all-CIDR ACL such as `0.0.0.0/0` or `::/0` must be compared with the
    SystemVM-rendered NFS wildcard `*`. Runtime success must also include a
    temporary client-mount probe from inside the Storage Service System VM: in
    `V4_ONLY` mode the probe uses `mount -t nfs4` against
    `<service-ip>:/<export-name>`, and in `V3V4_DUAL` mode it must additionally
    verify the legacy `mount -t nfs` path against
    `<service-ip>:/export/<export-name>`. A listening Ganesha endpoint without
    a successful probe is only a partial runtime state and must be treated as
    failed apply.
    The NFS export modal must include an "NFS access permission" area for Root
    Squash, All Squash, anonymous UID/GID, POSIX owner UID/GID, directory mode,
    and recursive apply. The NFS ACL modal must show export options by export
    name only; after selection, it shows the internal backing path and
    client-visible mount root as read-only context. NFS ACL option groups must
    use compact boxed sections with normal-sized labels so Root Squash, All
    Squash, sync, secure port, and anonymous UID/GID mapping are visually
    consistent with the export dialog.
    The NFS export modal must also include endpoint binding. Operators can
    expose an export through all configured Storage Service endpoints or a
    selected subset of endpoint IPs. The create/update API stores the selected
    endpoint intent as `endpointMode=ALL|SELECTED` in the export configuration
    and returns `endpointmode` in list/detail responses so the UI can show
    endpoint-to-export mapping after refresh. `SELECTED` requires one or more
    selected endpoint IPs and persists them as `listenIps`; `ALL` removes
    export-level `listenIps` and means the export is exposed through every
    configured Storage Service NFS endpoint. Existing rows that have
    `listenIps` but no `endpointMode` are interpreted as `SELECTED`; existing
    rows that have neither value are interpreted as `ALL`.
    The create dialog must default to `SELECTED` with no endpoint preselected
    so the operator makes an explicit endpoint decision. The UI confirmation
    path and backend API must both reject `SELECTED` without at least one IP.
    The backend must persist and return this endpoint intent from a valid
    `mediumtext` configuration payload. If the payload is invalid, the response
    must expose `configvalid=false`; it may recover `endpointMode` and complete
    IPv4 values from the raw payload for display, but it must not silently
    coerce the export to `ALL`.
    Because the Linux kernel NFS server does not provide a first-class
    per-export/per-listen-IP visibility model in the current SystemVM design,
    Storage Service managed NFS must move to NFS-Ganesha for endpoint-aware
    serving. Endpoint binding is no longer only metadata. The SystemVM desired
    state renderer must generate endpoint-specific Ganesha configuration where
    each endpoint is `listen IP + TCP port` and each endpoint contains only the
    exports bound to that endpoint. Kernel `exportfs` may remain available for
    platform compatibility, but Storage Service NFS must not render active
    exports into `/etc/exports.d`.
    Endpoint selection lists must be built from the merged endpoint model:
    ABLESTACK VM NIC IPs, secondary IPs, SystemVM runtime monitor IPs, protocol
    listen IPs, and export-level explicit `listenIps`, de-duplicated by IP.
    When an operator chooses "new IP", the UI must reject an IP that is already
    present in this merged model and show an explicit duplicate message instead
    of reporting a silent success for an idempotent no-op.
    The NFS export table and connection guidance must show all selected
    endpoint IPs for each export. Generic connection examples must remain
    export-name based, for example `<endpoint-ip>:/<export-name>`, instead of
    exposing the internal backing path.
    NFS-Ganesha rendering rules are:
    - the client-visible pseudo path is always `/<export-name>`;
    - the internal operator-facing alias remains `/export/<export-name>`;
    - the real data path remains under
      `/srv/ablestack-storage/volumes/<volume-uuid>/export/<export-name>`;
    - the SystemVM must not export `/export/<export-name>` to clients;
    - a non-default endpoint port must be shown in the UI connection help as
      `mount -t nfs -o vers=4,port=<port> <endpoint-ip>:/<export-name> ...`;
    - endpoint create/update/delete regenerates Ganesha configuration,
      reloads or restarts the affected endpoint service, and opens the
      configured TCP port in the SystemVM firewall;
    - legacy Storage Service `/etc/exports.d/ablestack-*.exports` files must be
      emptied or removed during apply so stale kernel exports cannot leak
      `/export/*` paths.
    - NFS apply success is not the same as desired-state persistence. The
      management server must treat the protocol action as successful only when
      the SystemVM reports that every configured Ganesha endpoint is running
      and listening on its configured IP and port; if the guest reports a
      startup or listen failure, the create/update flow must fail and leave the
      resource in `Error` instead of showing a false ready state.
    - Ganesha configuration is type-sensitive. The renderer must quote string
      paths such as Path and Pseudo, but must render Bind_addr and
      CLIENT.Clients as raw Ganesha literals. 0.0.0.0/0 and ::/0 are operator
      CIDR values and must be normalized to the raw wildcard *. Concrete CIDR
      values such as 10.10.0.0/16 and comma-separated ACL lists must be rendered
      as raw client tokens, not double-quoted strings. The SystemVM must
      preserve failed managed Ganesha config/log evidence so the next diagnosis
      does not depend only on a toast message.
    - Storage Service managed Ganesha endpoints require rpcbind to be active
      before endpoint startup, even for the NFSv4-only runtime used here. Some
      Ganesha builds still register auxiliary RPC programs during startup, and
      startup can fail before the NFSv4 mount probe if rpcbind is missing.
    - Initial NFS creation with an ACL is one desired-state transaction from an
      operator perspective. The UI may submit the export first, but the API must
      support a staged deferapply path so the export is persisted and backing
      storage is prepared without applying incomplete NFS state. The following
      ACL create call applies the combined export+ACL state and only then marks
      both rows Ready. If apply fails, both rows remain diagnostic Error rows.
  - After a protocol action modal closes, the UI must keep the current protocol
    tab and update only the affected protocol data, runtime summary, sessions,
    ACLs, and backing-volume rows. It must not navigate back to the Details tab
    or mask/blur the whole tab area for a routine data refresh. Action refresh
    code must snapshot the current protocol tab and wide-layout query state
    before the async job starts, suppress parent resource full-fetch behavior
    during async job completion when the protocol tab can refresh its own data,
    then restore those route query values through router replace after the job
    completes.
  - `Backing volumes` table: volume name, volume ID, size, used/free when
    available, disk offering, storage pool, filesystem, attached export, and
    ABLESTACK volume state.
    Physical backing volume expansion is a row-level action because it targets
    one concrete backing volume. Do not duplicate it in the section header.
    If an export references a stale or legacy volume ID, the UI should prefer
    the current SharedFS/SystemVM backing volume that belongs to the active
    Storage Service VM and keep the raw ID visible only as secondary evidence.
    Creation workflows must not rely on a stale page resource or list-row
    cache for the backing volume ID. After `createSharedFileSystem` completes,
    the UI must reload the created SharedFS by ID, use that response as the
    authoritative VM/volume source for initial protocol creation, and fail the
    setup if the current backing volume cannot be resolved.
    Backend create/update APIs for NFS, SMB, iSCSI, and NVMe-oF volume-backed
    resources must reject a volume that is already attached to a different VM.
    Detached volumes are allowed only for the explicit attach/import workflow;
    volumes already attached to the current Storage Service System VM are valid.
  - `NFS sessions` table: client IP or session identifier, connection state,
    connection time or age when available, associated export when known, local
    endpoint, and disconnect action when supported.
    Session rows should only expose session disconnect actions. Empty session
    tables must use the standard Ant Design empty state with an icon instead of
    plain `Nodata` text.
  - Storage Service read APIs may be newer than the UI permission cache in an
    upgraded management server. Protocol tab reads should try `listStorage*`
    status and inventory APIs directly, then normalize both exact object
    wrappers and array wrappers so exports, ACLs, runtime health, and backing
    volume rows do not disappear because of wrapper naming drift.
  - For NFS ACL display, the UI may request `listStorageNfsAcls` without an
    `exportid` filter and then map rows client-side by `resourceid`/export ID.
    This avoids losing visible ACL state when an upgraded API has entity
    reference drift on the filtered list parameter.
  - `NFS status` table or compact cards: daemon state, exportfs apply state,
    last desired-state apply result, last inventory refresh, and any
    protocol-specific waning. Runtime status should be read from the
    System VM monitoring cache whenever the cache is fresh, not from a full
    on-demand QGA inventory command on every UI refresh.
    The summary card must show the service endpoint and the last monitoring
    cache refresh time whenever the information is available.
  - NFS connection guidance must be representative, not tied to one export row.
    It should show examples such as
    `mount -t nfs4 -o vers=4.1,port=<endpoint-port> <service-ip>:/<export-name> <local-mount-path>`
    for `V4_ONLY`, and also show the legacy
    `mount -t nfs -o vers=3,port=<endpoint-port> <service-ip>:/export/<export-name> <local-mount-path>`
    compatibility form when `V3V4_DUAL` is selected.
- SMB tab table standard:
  - The SMB tab must use the same visual density, table behavior, action
    placement, dark-mode handling, fixed-column rules, tooltip rules, and empty
    states as the NFS tab standard.
  - The top summary card must show the SMB service endpoint, authentication
    mode, AD domain or workgroup when applicable, domain join state, daemon
    state for `smbd`/`nmbd`/`winbind`, monitor cache state, last monitoring
    cache refresh, and waning state when the desired SMB configuration has not
    been applied.
  - SMB connection guidance must be representative rather than tied to one
    share row. It should show examples such as `\\<service-ip>\<share-name>`,
    `net use * \\<service-ip>\<share-name> /user:<user>`, and
    `smbclient //<service-ip>/<share-name> -U <user>`, using the actual service
    IP and placeholder share/user values because multiple shares and identity
    modes can exist.
  - SMB effective endpoint display rule: Samba normally listens on
    `0.0.0.0:445` inside the Storage Service VM. When SMB is enabled, every
    non-wildcard service IP assigned to the VM is therefore a client-accessible
    SMB endpoint unless the backend later introduces explicit per-IP Samba bind
    control. The UI must compute `effective SMB endpoints` from the SMB protocol
    rows, the monitor-cache network addresses, and the default SMB port 445,
    then display all resulting `IP:port` and UNC roots. It must not show only
    the primary service IP when secondary IPs have been added through protocol
    activation.
  - `SMB shares` table: share name, client-visible UNC root, internal
    display path, resolved SystemVM backing path, service IP/port, browsable
    flag, guest access flag, read-only flag,
    quota/capacity, backing volume, runtime state, and actions.
    Share row actions should include share edit, share disable/delete when
    supported, and share capacity expansion. Do not duplicate the same action in
    both the section header and each row.
  - `SMB access and accounts` table: share name, principal type, principal
    name, permission, authentication mode, AD/local source, runtime state, and
    actions. The section action area must include local user creation, SMB ACL
    creation, and password rotation when supported. Passwords and secrets must
    never be displayed, cached, or written to the monitor cache.
  - `SMB identity` section: authentication mode, workgroup, AD domain, DNS
    hints, target OU, join state, winbind state, and last join/apply result.
    AD join, AD leave, and domain connectivity test actions belong here and
    must open modals. These controls must not be mixed into the share table.
  - `SMB sessions` table: client endpoint, username, SMB share name, SMB
    dialect/version, state, connected time, service endpoint, Samba session ID,
    tree ID, and terminate action. The SystemVM session collector must enrich
    TCP session data with `smbstatus --json`; plain `ss -tn` output is not
    sufficient because it cannot provide username, share name, dialect, or tree
    connection IDs. The collector should join `sessions` and `tcons` by
    `session_id`, then keep `ss` data only to supplement local/peer endpoint and
    TCP state.
  - `Backing volumes` table: volume name, volume ID, size, used/free when
    available, disk offering, storage pool, filesystem, attached SMB share, and
    ABLESTACK volume state. Physical backing volume expansion remains a
    row-level action because it targets one concrete backing volume.
  - `SMB sessions` table: client address or session identifier, user, share,
    SMB dialect/protocol version when available, connection state, connection
    time or age when available, local endpoint, tree/session ID when available,
    and disconnect action. Session rows should expose only session disconnect
    actions.
  - SMB runtime data should be built from Storage Service APIs and the
    System VM monitoring cache. The UI should normalize both exact object
    wrappers and array wrappers so shares, ACLs/accounts, sessions, and backing
    volume rows remain visible after API wrapper drift in upgraded systems.
  - If the SMB tab detects `protocol enabled` but no desired share row after a
    create workflow that selected SMB, it must show a setup-incomplete waning
    and expose the initial setup retry action instead of rendering an apparently
    empty healthy SMB service.
- iSCSI tab table standard:
  - The iSCSI tab must use the same full-width protocol-tab layout, table
    density, fixed-column behavior, ellipsis/tooltip handling, row-level action
    placement, compact scrollbars, and dark-mode table styling as the NFS and
    SMB tabs.
  - The top summary card must show the iSCSI endpoint, TCP 3260 listen state,
    target service state, monitor cache state, last monitoring cache refresh,
    target count, LUN count, ACL count, and waning state when the desired iSCSI
    configuration has not been applied.
  - iSCSI connection guidance must be representative rather than tied to one
    target row. It should show examples such as
    `iscsiadm -m discovery -t sendtargets -p <service-ip>` and
    `iscsiadm -m node -T <target-iqn> -p <service-ip> --login`, using the actual
    service IP and placeholders for target-specific values because multiple
    targets can exist.
  - `iSCSI targets` table: target IQN, LUN number, endpoint, backing volume,
    exposed LUN size, authentication mode, runtime state, and actions. Target
    actions must be row-level and must not be duplicated in the section header.
  - `iSCSI access` table: target IQN, allowed initiator IQN, authentication
    mode, CHAP user name when configured, mutual CHAP user name when configured,
    permission, runtime state, and actions. Secrets must never be displayed,
    cached, or written to monitor cache files.
  - `Backing volumes` table: volume name, volume ID, LUN/file-backed path when
    known, size, used/free when available, disk offering, storage pool,
    filesystem, attached iSCSI target, and ABLESTACK volume state. Physical
    backing volume expansion remains a row-level action because it targets one
    concrete backing volume.
  - `iSCSI sessions` table: initiator IQN or client address, target IQN, TPG or
    session identifier, connection state, connection time or age when available,
    endpoint, and disconnect action. Session rows should expose only session
    disconnect actions.
  - iSCSI runtime data should be built from Storage Service APIs and the
    System VM monitoring cache. The UI should normalize both exact object
    wrappers and array wrappers so targets, ACLs, sessions, and backing volume
    rows remain visible after API wrapper drift in upgraded systems.
- NVMe-oF tab table standard:
  - The NVMe-oF tab follows the same table standard as the iSCSI tab, replacing
    target/LUN terms with subsystem/namespace terms.
  - `NVMe-oF subsystems` table: subsystem NQN, namespace ID, endpoint, backing
    volume, namespace size, authentication mode, runtime state, and actions.
  - `NVMe-oF access` table: subsystem NQN, host NQN, authentication mode,
    DH-HMAC-CHAP user/key status where applicable, runtime state, and actions.
    Secrets must never be displayed, cached, or written to monitor cache files.
  - `NVMe-oF sessions` table: client address, host NQN when known, subsystem
    NQN, namespace ID, TCP queue count, connection state, service endpoint, and
    disconnect action. Long NQNs must use ellipsis plus tooltip. The action
    column remains right aligned and fixed when the table scrolls horizontally.
- Protocol tab refresh behavior:
  - Initial loading may use a page spinner, but subsequent status refreshes
    must not clear the existing tab data or re-render the whole tab. The UI
    should update only the changed collections after new API responses arrive.
    Parent page loading state must not keep a protocol tab covered by a global
    disabled overlay after the tab has its own data.
  - Table empty states, row text, fixed columns, horizontal scrollbars, and
    action columns must be checked in dark mode. Missing i18n keys must never
    leak to the visible UI.
  - Table body cells and fixed columns must not hard-code light-theme dark text
    colors. They should inherit the active ABLESTACK theme foreground color so
    values remain visible under `body.dark-mode` and normal mode.
- Active sessions must be visible per protocol. Session termination must be
  provided through a modal that explains protocol-specific limits:
  - SMB sessions can usually be terminated explicitly.
  - NFS session termination is best effort because NFSv3 is weakly sessioned.
  - iSCSI and NVMe-oF termination depends on target/transport capabilities and
    may require ACL disable plus desired-state reapply.
- `listStorageServiceSessions` should accept `instanceid`, `protocol`,
  `resourceid`, `client`, and `state` filters for UI tab use.
- `disconnectStorageServiceSession` should be asynchronous and return a runtime
  response or job result containing protocol, target session, command output,
  and whether termination was complete or best effort.
- `listStorageServiceProtocolSummary` should be added if the UI cannot build
  the protocol cards efficiently from existing list, health, inventory, and
  session APIs. The response should aggregate protocol enabled state, endpoint,
  runtime service state, resource counts, ACL counts, capacity summary, and
  session count. This API should prefer the System VM monitoring cache and
  include cache freshness, monitor service state, and stale-cache waning fields
  so the UI can render status quickly and honestly.

The Mold UI must submit Cloud API requests asynchronously and poll async jobs
only. The UI must never run storage commands directly. Commands continue through
Management Server, async job, Mold Host Agent on the host running the Storage
Service System VM, QGA, and `ablestack-storagectl` inside the service VM.
Read-only status requests may use the same QGA channel to read monitoring cache
files, but they should not execute expensive live discovery commands unless the
cache is missing, stale, or explicitly refreshed by the operator.

SPDK NVMe-oF may be visible as a planned or prerequisite-gated engine, but the
SharedFS UI must not expose HugePage, CPU pinning, NUMA, memlock, SR-IOV, or PCI
passthrough controls. Those remain future VM Runtime Capability features outside
the Storage Service workflow.

## Implementation Phases

### Phase 1: Foundation

- Add the new DB model and DAO/query classes.
- Add the new API package and manager skeleton.
- Add QGA guest command dispatcher abstractions.
- Add `ablestack-storagectl` skeleton to `systemvm/debian`.
- Extend System VM template package list for storage runtime dependencies.

### Phase 2: NFS on the New Engine

- Implement Storage Service instance lifecycle.
- Implement NFS protocol enablement.
- Implement NFS export CRUD.
- Implement NFS ACL CRUD.
- Implement export-level backing volume and capacity limit.
- Implement usage/status reporting through QGA.

### Phase 3: SharedFS Compatibility

- Keep existing SharedFS API paths.
- Map `createSharedFileSystem` to one Storage Service instance and one NFS
  export, or keep the old path until the new NFS engine is stable.
- Preserve response shape and existing UI behavior.

Implementation note:

- The existing SharedFS lifecycle remains the compatibility authority for VM
  deployment, volume creation, and UI/API responses.
- When `storage.service.feature.enabled` is enabled, SharedFS lifecycle changes
  are mirrored into the new Storage Service model by matching the SharedFS
  `vm_id` and `volume_id`.
- The compatibility mirror creates or updates one `StorageServiceInstance` and
  one enabled NFS `StorageServiceProtocol`, but it must not publish the legacy
  `/export` root as an open NFS export.
- For compatibility instances that need an initial file export, the mirror or
  UI setup must create an explicit child export such as `/export/<share-name>`
  with an explicit ACL. The old open-root behavior is not carried forward into
  the expanded Storage Service model.
- Existing cloud-init and SharedFS bootstrap logic that formats and mounts the
  data disk at `/export` must be changed so it no longer writes or enables an
  NFS export for `/export` when the expanded Storage Service feature is active.
  Storage Service desired state, applied through the Mold host Agent and QGA, is
  the only authority that may render NFS export files.
- Compatibility mirror failures are logged and do not change existing SharedFS
  API behavior.
- All Storage Service desired-state rows must carry explicit `created`
  timestamps in the VO layer and the schema must provide `DEFAULT
  CURRENT_TIMESTAMP` for fresh installations. UI-created service workflows must
  not depend on database implicit defaults being present or absent.
- UI creation must treat the workflow as two visible phases:
  1. SharedFS/SystemVM creation through the existing asynchronous
     `createSharedFileSystem` job.
  2. Initial Storage Service setup through asynchronous protocol/export/share/
     target/ACL jobs.
- The UI must poll every initial Storage Service async job to completion before
  using retuned object IDs, creating dependent ACLs, or showing final Storage
  Service setup success.
- The UI must verify the selected service objects after setup:
  NFS export and optional ACL, SMB share plus SMB local ACL when local identity
  mode is selected, iSCSI target and optional ACL, and NVMe-oF subsystem and
  optional host ACL. If verification fails, the UI must report a partial-create
  error and the detail page must remain available for retry/reconciliation.

### Phase 4: SMB

- Implement SMB local user/share mode.
- Implement SMB ACL management.
- Create-dialog local SMB creation must run in this order: enable SMB protocol,
  create SMB share, create `LOCAL_USER` ACL with one-time password payload,
  verify share and ACL API state, then verify runtime inventory/cache state.
- All SMB APIs that accept `shareid` must resolve UUIDs through the same
  `StorageFileShare` entity reference used by generic file-share APIs. The
  SMB-specific response object must therefore carry
  `@EntityReference(StorageFileShare.class)` so `createStorageSmbAcl`,
  `listStorageSmbAcls`, `updateStorageSmbShare`, `deleteStorageSmbShare`, and
  `listStorageSmbShares id=...` cannot fail during API parameter conversion.
- If ACL creation fails after the share has already been created, the workflow
  is a partial creation, not a complete failure. The UI must keep the detail
  page usable, show the SMB share row, and provide a retry path for ACL
  creation from the SMB protocol tab.
- Implement AD domain join/leave/status.
- Implement AD user/group ACLs.

### Phase 5: Block Protocols

- Implement iSCSI target/LUN/ACL/CHAP.
- Implement NVMe-oF subsystem/namespace/host ACL and DH-HMAC-CHAP host ACL
  authentication.
- Add session and discovery views.

### Phase 6: Operations

- Add QGA-backed runtime health, inventory, and active session checks.
- Add metrics and health checks.
- Add event/audit details.
- Add backup/snapshot/resize integration.
- Add upgrade and template compatibility checks.
- Evaluate dedicated Storage Service System VM template profile.

### Phase 7: Existing Volumes, Resize, And NVMe-oF Kernel Preparation

Confirmed next implementation scope:

1. Storage capacity expansion for file services.
2. Service activation from existing ABLESTACK volumes attached to the Storage
   Service System VM.
3. NVMe-oF SPDK code adjustment so any VM-level runtime resource handling is
   removed from Storage Service and left as a dependency on the future VM
   Runtime Capability feature.

- Add explicit existing-volume import APIs for NFS and SMB file shares.
- Implement QGA `volume attach inspect` and non-destructive mount discovery.
- Implement `resizeStorageFileShare` and QGA `filesystem resize` for XFS and
  ext4 grow.
- Add XFS project quota support for multi-share-on-one-volume capacity
  enforcement.
- Add `prepareStorageServiceNvmeOfVm` for `KERNEL_NVMET` validation.
- Keep SPDK as a planned engine state that returns prerequisite information and
  `PreparationRequired` until VM Runtime Capability support is available.
- Add UI workflows for:
  - selecting existing ABLESTACK volumes
  - choosing import mode
  - reviewing detected filesystem and mount status
  - resizing file services
  - selecting NVMe-oF `KERNEL_NVMET` mode and viewing prerequisite checks
  - showing SPDK as planned or unavailable without exposing VM runtime controls.

Recommended implementation order:

1. Existing volume import model and API validation.
2. QGA volume inspection and mount workflow.
3. File share resize API and filesystem grow workflow.
4. NVMe-oF `KERNEL_NVMET` prerequisite validation.
5. SPDK planned-state response and VM Runtime Capability dependency message.
6. Remove or gate any SPDK code path that attempts HugePage, NUMA, CPU pinning,
   memlock, SR-IOV, PCI passthrough, or other VM-level runtime changes from
   Storage Service.
7. UI integration and end-to-end validation.

### iSCSI LUN Backing And Runtime Inventory Rules

- iSCSI desired state must carry the ABLESTACK backing volume UUID, name, and
  size in addition to the database row ID. SystemVM scripts must not use
  numeric database IDs as disk-match tokens because they can accidentally match
  unrelated local device identifiers.
- The SystemVM must resolve the backing disk by volume UUID/name/serial and
  fail the apply operation when no guest disk matches. It must not fall back to
  the root filesystem or `/var/lib/ablestack-storage` for iSCSI LUN images.
- When a file-backed iSCSI LUN is used, the LUN image is created only below the
  matched backing disk mountpoint, for example
  `<data-mount>/.ablestack-storage/iscsi/<target-uuid>.img`.
- If the operator leaves LUN size blank, the effective LUN size is the backing
  volume size. If the operator sets a LUN size, that configured size is shown
  separately from the effective/runtime size.
- `listStorageIscsiTargets` and the QGA desired-state payload must expose
  volume name, volume size, configured LUN size, effective LUN size, and
  configured backing path where present.
- The monitor cache inventory must enrich iSCSI target rows with runtime
  backstore type, runtime backing path, and runtime file size from target state
  so the UI can render quickly without issuing expensive live QGA commands on
  every refresh.
- The iSCSI tab follows the shared Storage Service table standard: important
  identity columns are fixed, wide runtime paths scroll inside the table, long
  values are shortened with tooltip support, and dark-mode table/empty states
  remain readable.

### NVMe-oF Reconcile And Namespace Backing Rules

- NVMe-oF `KERNEL_NVMET` desired-state apply must be idempotent. The SystemVM
  must reconcile the requested state with `/sys/kernel/config/nvmet` instead of
  rewriting every configfs attribute on every request.
- A configured NVMe-oF port must write `addr_trtype`, `addr_adrfam`,
  `addr_traddr`, and `addr_trsvcid` only before subsystem links are active. If
  the port already has linked subsystems, Host ACL or namespace updates must not
  rewrite those active port attributes.
- Host ACL creation must be independent from port and namespace creation:
  create or reuse `/sys/kernel/config/nvmet/hosts/<hostNqn>`, then create the
  `allowed_hosts/<hostNqn>` symlink if it does not already exist. Existing
  symlinks are treated as success; unexpected non-symlink paths are an error.
- Namespace updates must not rewrite `device_path` while the namespace is
  enabled. If the desired backing device changes, the SystemVM must disable the
  namespace, update `device_path`, and re-enable it. If the device is unchanged,
  no configfs write is needed.
- NVMe-oF namespace desired state must carry enough ABLESTACK volume metadata
  to resolve the guest disk by volume UUID, volume name, or safe serial token.
  Numeric-only or broad fallback tokens must not be used.
- When the selected ABLESTACK backing volume is already mounted by the SharedFS
  compatibility path, NVMe-oF must not expose the mounted block device directly.
  It should create a managed namespace image below the mounted data disk, for
  example `/export/.ablestack-storage/nvmeof/<namespace-uuid>.img`, attach it
  to a loop device, and set the kernel namespace `device_path` to that loop
  device.
- The SystemVM must refuse to place NVMe-oF namespace backing files on the root
  filesystem when the intended ABLESTACK data volume cannot be resolved.
- The Management Server should mark Host ACL rows `Ready` only after the QGA
  desired-state apply succeeds. Failed apply attempts must surface a clear
  operator error and must not leave UI/API state that looks complete.

### NFS Lifecycle CRUD And Deletion Safety Rules

- Storage Service protocol activation and full protocol deletion are endpoint-scoped lifecycle actions.
- NFS endpoint removal is a separate endpoint-level lifecycle operation. It
  removes one selected listen IP from protocol/export desired state and, when
  that IP is a Cloud secondary IP for the Storage Service VM, removes the
  secondary-IP record after QGA desired-state reapply. The UI must label this
  action as endpoint removal, show the selected endpoint before confirmation,
  and require the operator to type the selected IP before deletion is enabled.
  The primary NIC IP is not removable through this endpoint action.
- Protocol deletion is allowed only after dependent resources are removed:
  NFS exports for NFS, SMB shares and AD domain state for SMB, iSCSI targets
  for iSCSI, and NVMe-oF subsystems/namespaces for NVMe-oF. This prevents a
  protocol from being disabled while visible shares or block targets still
  exist.
- NFS listener ports are endpoint-scoped rather than export-owned. The default
  listener port is `2049`, but operators may change the port per
  endpoint/listener in the protocol management workflow. Multiple listen IPs
  may be registered, and the UI/API must display the actual endpoint port for
  each listener instead of implying an export-owned port field.
- NFS uses the Storage Service VM Ganesha listener model. The default runtime
  mode is `V4_ONLY`, but the operator may opt into `V3V4_DUAL` for legacy
  clients. The listener port belongs to the protocol endpoint (`listen IP +
  port`) and may be changed from protocol management; exports inherit the
  current endpoint port for display but do not own it. `V3V4_DUAL` requires
  `rpcbind` in the Storage Service VM. The runtime validator must treat a
  non-2049 NFS listener as valid when the desired endpoint configuration says
  so, and the UI must not hard-code a service-wide 2049-only assumption.
- The NFS `secure` export option is exposed as "Require privileged port", not
  as a generic "secure port". It means Linux NFS clients must use a source port
  below 1024. This is an access-control compatibility option, not encryption.
- NFS export desired-state rendering must de-duplicate effective
  `(client-visible export root, principal)` entries before writing
  `/etc/exports.d`. Duplicate ACL rows in the database or repeated UI submits
  must not cause `exportfs: duplicated export entries` to break unrelated NFS
  exports during full desired-state reapply.
- NFS export rows support create, edit, resize, and delete from the NFS tab
  table action column. Edit reuses the create dialog with the selected export
  values prefilled, including client-visible export name, internal backing
  path, selected endpoints, capacity limit, and POSIX/root-squash options.
- NFS export creation supports three backing-volume modes: use the current
  SharedFS backing volume, attach an existing detached ABLESTACK data volume,
  or create a new ABLESTACK data volume by selecting a disk offering and a GiB
  size. The UI starts from a dropdown, never a raw volume ID field.
- Deleting an NFS export removes the export, ACL rows, desired-state entry, and
  client-visible alias mount, but does not detach or destroy the backing
  ABLESTACK data volume. A backing volume is a reusable storage container and
  can host multiple export directories.
- When NFS export creation uses a currently attached backing volume, the UI
  must require an explicit current-volume selection whenever more than one
  attached backing volume is available; in that case the selector has no
  default value. The selected volume ID is sent to the API with no import mode,
  so the Management Server records the mapping and reapplies NFS desired state
  without reattaching, reformatting, or remounting that volume.
- The NFS tab backing-volume table must list all data volumes attached to the
  Storage Service System VM, including volumes whose previous export was
  deleted and therefore no longer has a file-share row. The table must show the
  ABLESTACK volume identity and any attached export names; row actions that
  require a file-share row stay disabled when a volume currently has no export.
- The NFS export create/edit dialog exposes a single operator-facing path:
  `internal backing path`. The separate `directory inside backing volume` field
  is removed because it conflicts with the Storage Service path model.
- NFS export names must follow Linux directory naming rules: letters, numbers,
  `.`, `_`, and `-` only; no slash, no whitespace, no empty value, and no `.`
  or `..`. The default internal backing path is generated from the export name
  as `/export/<export-name>`.
- NFS internal backing paths must stay exactly one level below `/export`.
  `/export`, `/export/`, paths outside `/export`, and nested paths such as
  `/export/<name>/<child>` are rejected by both UI validation and the API.
- Existing/current backing-volume import separates the private volume mount
  root from the operator-facing export path. The SystemVM mounts the selected
  volume at a stable private path such as
  `/srv/ablestack-storage/volumes/<volume-uuid>`, resolves the corresponding
  `export/<export-name>` directory inside that mounted volume, and stores the
  effective private path in config/inspection as `backingPath`. NFS publishes
  only the `/export/<export-name>` alias.
- Existing or current backing volumes must be inspected before the export is
  applied. The SystemVM records the detected filesystem, filesystem UUID,
  private mount root, and effective backing path in `lastInspection`.
  Existing-volume import supports only `xfs` and `ext4`; an unsupported or
  undetected filesystem fails before the export/share is marked Ready.
- New backing-volume creation must expose a filesystem selector in the UI. The
  selected value is passed for format workflows and is limited to `xfs` or
  `ext4`. Current SharedFS backing volumes use `FORMAT_IF_EMPTY`: an existing
  supported filesystem is reused, and an empty device is formatted with the
  selected filesystem.
- The directory creation policy is explicit. `Create directory if missing` is
  enabled by default. If disabled and the corresponding `export/<export-name>`
  directory is absent inside the selected backing volume, the operation fails
  and the error is retuned to the UI.
- Cloud-init must not mount `/export`, format data disks, register `/export` in
  `/etc/fstab`, resize filesystems, or publish legacy NFS exports. It is limited
  to bootstrapping the Storage Service System VM so QGA, persistent DHCP, and
  monitor services are available. All Storage Service state changes, including
  initial NFS export creation requested at SharedFS creation time, are applied
  through Mold API -> engine -> host agent -> QGA -> `ablestack-storagectl`.
- The NFS backing-volume table exposes a row-level detach action for unused
  backing volumes. The action is disabled when any NFS export, SMB share, iSCSI
  target, or NVMe-oF namespace still references the volume. Detach unmounts the
  volume from the Storage Service System VM and removes fstab markers, but it
  never deletes the ABLESTACK volume or its data. Operators must delete the
  detached volume separately from the Volumes menu when data deletion is
  desired.
- NFS ACL rows support create, edit, and delete from the ACL table action
  column. Create accepts either one CIDR/IP or multiple comma-separated
  CIDR/IP values and persists one ACL row per value. Edit is intentionally
  single-row only.
- Destructive operations use a dark-mode-compatible confirmation dialog.
  Operators must type the displayed resource name before the OK button becomes
  active. Delete actions refresh only the affected protocol tab data and must
  preserve the current tab and wide-layout state.
- Capacity expansion and backing volume expansion dialogs must use the same
  vertical action-modal standard as NFS export and ACL dialogs: centered modal,
  fixed header/footer, scrollable body inside the browser viewport, required
  markers, tooltip labels, and no horizontal row-only layout.
- The UI remains based on Vue Ant Design and follows the established Storage
  Service table pattern: fixed important columns, compact row action buttons
  with icons, internal table scrolling, ellipsis/tooltip handling for long
  values, and explicit dark-mode colors for waning and delete states.

## Open Decisions

## TC-04A NFS Backing Volume And Runtime Validation Refinement

The TC-04A retest exposed a case where Storage Service DB rows looked ready
while the System VM runtime was not authoritative: the monitor cache was empty,
`ablestack-storagectl` in the generated System VM image could be empty or
unusable, and an NFS export backed by a newly created ABLESTACK data volume
could remain detached.

The refined design is:

- NFS export create/update must treat an explicitly selected backing volume as
  a full lifecycle operation, not metadata only. When `importmode` is provided,
  the Management Server attaches the volume to the Storage Service System VM,
  runs QGA `volume attach inspect`, persists the inspected filesystem and mount
  path, then applies NFS desired state.
- `importmode=MOUNT_EXISTING` mounts only volumes that already have a supported
  filesystem. `importmode=FORMAT_EMPTY` is reserved for UI-created empty
  volumes and must refuse to format a device that already has a filesystem
  signature. `INSPECT_ONLY` may record metadata without mounting.
- The Management Server must reject duplicate file-share paths and must reject
  overlapping backing-volume paths when different volumes would be mounted
  over the same path tree. The System VM must also reject an exact mount target
  already occupied by another device.
- `importmode` is optional for initial SharedFS compatibility flows that use
  the already mounted primary SharedFS data volume. If it is omitted, the NFS
  export keeps the requested child path and does not re-inspect the existing
  primary `/export` mount as the share path.
- The System VM template build must fail if `/usr/local/bin/ablestack-storagectl`
  or `/usr/local/bin/ablestack-storage-monitor` is missing or empty. Fresh
  Storage Service System VMs must unmask and enable
  `ablestack-storage-monitor.service`; the setup script must refuse to continue
  with an empty storage control binary.
- Runtime status APIs and the UI must distinguish DB readiness from runtime
  readiness. Empty monitor cache or invalid runtime JSON is a runtime
  unavailable state, not proof that an export is active.
- NFS ACL create remains a multi-CIDR workflow. NFS ACL edit is a single-row
  workflow and the UI must show a single principal input instead of a tag list.
  This prevents the operator from entering multiple principals into an update
  operation that can only update one ACL row.
- Storage Service file-share mounts must be boot-safe. The physical backing
  volume mount and the client-visible export alias are separate concepts. A
  backing volume is mounted only at its internal backing path, while a
  client-visible NFS root such as `/nfs02` is a bind mount alias to that backing
  path and is the only path rendered into `/etc/exports.d`.
- `ablestack-storagectl volume attach inspect` must persist explicit backing
  volume mounts in `/etc/fstab` using `UUID=<filesystem-uuid>` rather than
  `/dev/sdX`. The entry must include an `ablestack-storage:` marker with the
  share UUID and `backing` role so it can be updated safely.
- `ablestack-storagectl nfs export apply` must persist NFS alias bind mounts in
  `/etc/fstab` using `none bind,nofail,x-systemd.requires-mounts-for=<backing
  path>`. The entry must include an `ablestack-storage:` marker with the export
  UUID and `alias` role.
- Deleted or disabled exports must remove their managed alias fstab entry and
  unmount the alias when it is mounted. Managed fstab operations must never
  touch root, boot, ISO, swap, or unrelated operator entries.
- After changing managed fstab entries, the System VM must run
  `systemctl daemon-reload` when systemd is present and verify mount state with
  `findmnt` before running `exportfs -ra`.
- The fstab update path must be covered by an execution-level regression test,
  not only Python compilation. Python heredoc blocks run as independent
  interpreters, so every block that calls managed fstab helpers must import its
  own runtime dependencies such as `tempfile`. A missing import must fail the
  template validation before deployment.
- The monitor capacity collector must read
  `/etc/ablestack-storage/nfs-export-aliases.json` and include every managed
  backing path and alias path instead of scanning only `/export`. UI capacity
  tables then receive all exports, including exports backed by additional
  volumes.
- Runtime readiness checks should flag an export as degraded when its DB row is
  Ready but either the backing mount or the client-visible alias mount is
  missing.
- Storage Service desired-state JSON must be treated as authoritative data, not
  optional decoration. All `config_json` columns for Storage Service protocol,
  file share, block target, access rule, and identity-domain rows must be
  physically verified as `MEDIUMTEXT` in upgraded 22.x environments. The entity
  mapping must also declare the column as a large object or `MEDIUMTEXT`, and
  must set `@Column.length` to the `MEDIUMTEXT` capacity (`16777215`). Mold's
  `GenericDaoBase` uses the JPA `Column.length` value while binding String
  fields; if the length is omitted, the default `255` is applied even when the
  physical DB column is `MEDIUMTEXT`, which truncates Storage Service desired
  state JSON before it reaches the System VM.
- The Management Server must strictly parse NFS export and ACL `config_json`
  before building QGA desired-state payloads. If a stored config is truncated,
  invalid JSON, or missing the resolved `backingPath` and `volumeMountPath` for
  an active export, the manager must fail before sending the QGA command. It
  must not silently replace the missing backing path with `/export/<name>`.
- `ablestack-storagectl nfs export apply` must enforce the same guard inside
  the System VM. `config.backingPath` and `config.volumeMountPath` are required
  for active NFS exports. The backing path must be under
  `/srv/ablestack-storage/volumes/<volume-uuid>`, the volume mount must already
  be mounted, and `/export/<export-name>` must be a bind mount alias to that
  backing path before `exportfs` is run.
- The System VM must refuse to hide a non-empty root-filesystem directory with a
  bind mount. If `/export/<export-name>` already exists on rootfs and contains
  data, the command fails and reports that manual recovery is required. Empty
  stale alias directories may be reused as bind mount targets.
- The create SharedFS dialog's initial capacity controls must use a stable
  numeric-input plus unit-select layout. The numeric input must not depend on
  Ant Design compact input-group sizing because the dark modal layout can
  collapse the input and leave only the unit selector visible.
- Existing damaged exports whose `config_json` was already truncated cannot be
  trusted as repair sources. After the DB and runtime guards are deployed, the
  operator should delete/recreate the export or run a controlled repair that
  unexports the stale rootfs path, removes only an empty stale alias, regenerates
  the full backing config, bind-mounts `/export/<name>`, persists fstab, and
  reapplies `exportfs`.
- Initial NFS export creation failures must preserve enough evidence for
  operation and UI diagnosis. When a create request reaches DB persistence but
  the QGA desired-state apply fails, the file-share row is kept in `Error`
  state with a valid `config_json.lastError` object instead of being deleted.
  Created temporary volumes may still be cleaned up when the request explicitly
  marks them as disposable, but the failed export metadata remains visible.
- Desired-state generation must include only active resources. NFS exports and
  ACLs in `Ready` or `Updating` state are sent to the System VM; rows in
  `Error`, `Disabled`, `Destroyed`, or other non-active states are excluded so a
  failed row cannot poison all later NFS reconciliation.
- The Management Server must validate generated Storage Service JSON before
  writing it to the database. Invalid generated JSON is rejected immediately.
  If an apply failure has to be recorded, the preserved error record must itself
  be valid JSON so list APIs and the UI can show the failed object and reason.
- The NFS tab must surface failed export rows through an operator-facing alert
  before the export table. This keeps the normal table focused on usable
  exports while still showing why the initial service setup produced a partial
  waning.

These rules preserve the existing SharedFS API surface while making the Storage
Service runtime state auditable through QGA and the monitor cache.

- Whether Phase 1 should use the common System VM template or immediately add a
  dedicated Storage Service template.
- Whether Storage Service instances should support one protocol per VM or mixed
  protocols in one VM.
- Whether HA should be active/passive in the first version or deferred.
- Whether NFS multi-export quota should wait for XFS project quota support or be
  implemented only through one-volume-per-export initially.
- Whether SMB identity should standardize on winbind, sssd, or support both.
- Whether NVMe-oF should require `nvmetcli` or use configfs directly.
- Whether SPDK mode should later use the same Storage Service System VM
  template or require a dedicated high-performance template after VM Runtime
  Capability profiles are implemented.
- Whether VM Runtime Capability should be modeled only through service offering
  details first or receive normalized profile tables before SPDK is enabled.
- Whether existing volumes that are currently attached to another VM should be
  automatically detached by the Storage Service workflow or require an explicit
  operator detach step first.

## NFS Ganesha Runtime Ownership

Storage Service owns the NFS Ganesha runtime inside the System VM. The package
provided nfs-ganesha.service must not run with the default sample
/etc/ganesha/ganesha.conf, because that service can bind TCP 2049 without any
Storage Service exports and make a later endpoint probe observe the wrong
process.

Runtime rules:

- The System VM template build and sharedfsvm boot setup must disable and mask
  nfs-ganesha.service. The service is present only as a package payload, not as
  the Storage Service control plane.
- ablestack-storagectl nfs export apply must defensively stop the default
  nfs-ganesha.service before starting managed endpoint instances.
- Every managed endpoint instance must start ganesha.nfsd directly with a
  Storage Service generated config, a per-endpoint log file, and an explicit
  per-endpoint pidfile under /run/ablestack-storage/ganesha.
- Endpoint readiness must verify the managed PID, not just the listening port.
  A default or stale Ganesha process listening on the requested port is a
  failure, even if a TCP connection succeeds.
- Startup errors must include the managed endpoint log tail so the operator sees
  root causes such as Ganesha already started before the later mount probe
  reports a generic missing export path.
- NFSv4 visibility probes remain valid only after the managed endpoint process
  is confirmed alive and serving the generated export config.

This keeps System VM package installation separate from Storage Service runtime
ownership and prevents the default distro service from masking failed export
application.

## References

- SPDK System Configuration User Guide:
  <https://spdk.io/doc/system_configuration.html>
- SPDK NVMe-oF Target documentation:
  <https://spdk.io/doc/nvmf.html>
- SPDK Getting Started guide:
  <https://spdk.io/doc/getting_started.html>
- Red Hat Enterprise Linux NVMe-oF configfs workflow:
  <https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/9/html/managing_storage_devices/configuring-nvme-over-fabrics-using-nvme-rdma_managing-storage-devices>

## NFS Initial Desired-State Finalization Update (2026-06-09)

The initial SharedFS create workflow may stage the first NFS export before the
initial ACL row exists. In that case createStorageNfsExport uses deferapply=true
and stores the export in Allocated state. The final ACL creation step is the
first point where the complete desired state exists.

Required behavior:

- createStorageNfsExport and updateStorageNfsExport accept protocolmode and
  store it in the NFS export config. The list/detail response also returns
  protocolmode so the UI does not have to infer the current mode from stale
  local form state.
- Normal desired-state reconciliation includes only active Ready and Updating
  resources.
- Initial finalization may explicitly include staged Allocated NFS exports and
  ACLs. This is allowed only for the initial create path where the export and
  its first ACL are committed as one logical operation.
- After QGA returns, the management server must verify that a request containing
  one or more NFS exports produced at least one applied export and at least one
  runtime endpoint. A result such as exports=0,endpoints=0 is a failed apply,
  not a successful no-op.
- Ganesha runtime success is determined by the managed process/listener/probe
  result returned by ablestack-storagectl; DB rows alone are not sufficient to
  mark the service as operational.

This rule prevents the exact failure where API rows became Ready while the
System VM received an empty exports payload and therefore never started a
managed Ganesha endpoint.

## NFS Ganesha Export ID Safety Update (2026-06-09)

The Storage Service System VM must render Ganesha `Export_Id` values inside the
range accepted by the deployed nfs-ganesha runtime. The previous CRC-derived
value could exceed the runtime limit and made Ganesha reject an otherwise valid
export block while the endpoint port could still appear open.

The corrected renderer uses these rules:

- Generate `Export_Id` values in the bounded range `1000..65535`.
- Prefer the export UUID as the stable seed, then export name/path as fallback.
- Resolve collisions inside each endpoint configuration with deterministic
  linear probing, so one endpoint never emits duplicate `Export_Id` values.
- Copy export render objects per endpoint before assigning IDs, because the same
  export can be exposed through more than one listener.
- Treat Ganesha log markers such as `No export entries found`, `out of range`,
  `invalid param value`, or `CONFIG :CRIT` as startup failure when the rendered
  configuration contains export blocks.
- Truncate the managed endpoint log on each start and preserve the failed config
  and log tail so a rejected config is visible in the apply failure reason.

This means Storage Service NFS readiness is not just "TCP port is listening".
A managed Ganesha process, accepted configuration, listener readiness, and export
visibility probe must all succeed before the desired state is considered active.

## NFS Endpoint Port and Ganesha Startup Race Update (2026-06-09)

NFS listener ports are endpoint properties. The management server must not force
NFS to TCP 2049 when the operator selects a different endpoint port. TCP 2049 is
only the default value when the request omits a port.

Port handling rules:

- NFS protocol enable/create accepts an explicit TCP port in `1..65535`.
- When the port is omitted, NFS defaults to 2049 for compatibility.
- Port ownership belongs to the protocol endpoint, not to the export.
- The engine must reject invalid numeric ranges, but it must not reject a valid
  custom NFS port just because the service previously used 2049.
- The System VM applies the selected endpoint port through the generated
  Ganesha `NFS_Port` and through the listener readiness probe.

Ganesha startup validation must avoid a process-start race. Immediately after
`Popen()`, `/proc/<pid>/cmdline` may not yet contain the expected managed
command string even though the process is alive and will start listening within
seconds. Therefore the System VM must:

- Treat real process exit as failure only when `/proc/<pid>` disappears or the
  process is no longer alive.
- Continue probing the endpoint socket while the process exists, even if the
  managed command-line check is not yet conclusive.
- After the socket is listening, wait briefly for the managed command-line check
  to become conclusive before failing.
- Classify Ganesha log failures by config rejection markers only. Kerberos or
  keytab warnings such as `Cannot acquire credentials for principal nfs` are not
  fatal for `SecType=sys` NFS service.

A successful NFS apply requires the managed process to remain alive, the endpoint
port to listen, no current config-rejection marker in the freshly truncated log,
and the export visibility probe to succeed.

## NFS Ganesha Visibility Probe Host Selection Update (2026-06-10)

The NFS export visibility probe must validate the same access path that a real
client can use. It must not hard-code `127.0.0.1` when the endpoint is rendered
as `0.0.0.0`, because Ganesha applies the export `CLIENT` access list to the
probe mount as well. If the operator allowed `10.10.0.0/16`, a loopback mount
from `127.0.0.1` is outside the allowed client set and can fail with
`No such file or directory` even though the export is valid for the service
network.

Visibility probe host selection rules:

- For a concrete endpoint IP, probe that IP only when it is allowed by at least
  one export `CLIENT` entry.
- For a wildcard endpoint such as `0.0.0.0`, enumerate the System VM's global
  IPv4 addresses and choose the first address allowed by the export ACL.
- Use `127.0.0.1` only when the export ACL explicitly allows all clients through
  `*`, `0.0.0.0/0`, or `::/0`, or when loopback is otherwise intentionally
  allowed.
- If no local global IPv4 address is allowed by the export ACL, fail with a
  clear host-selection error instead of attempting a misleading loopback mount.
- The failure message must show the selected endpoint, export, probe target,
  and the ACL-derived reason so the operator can distinguish an ACL mismatch
  from a broken Ganesha runtime.

This keeps the readiness check strict while avoiding false failures caused by a
probe source address that the operator did not permit.

## Runtime Monitor Collector Scope Update (2026-06-10)

Storage Service runtime monitoring is a read optimization and must not report a
healthy NFS service as failed because a collector cannot execute independently.
The runtime collectors embedded in `ablestack-storagectl` are executed outside
the main NFS apply Python context, so every collector must define or import the
helper functions it calls.

Monitor collector rules:

- `health`, `inventory`, `capacity`, and `sessions` collectors must be runnable
  as standalone `ablestack-storagectl <collector>` commands.
- `health` and `inventory` must evaluate Storage Service managed Ganesha by
  managed pid files, managed config files, endpoint listener state, and
  protocol mode. They must not treat masked or inactive package
  `nfs-ganesha.service` as a failure when the Storage Service managed Ganesha
  process is running.
- Helper functions used by more than one collector, including NFS protocol-mode
  parsing and rpcbind activity checks, must be present in each standalone
  collector context or moved to a shared executable helper.
- Cache files must include collector-specific errors with the original exception
  message whenever possible. A generic `collector failed` message is not
  sufficient for operator diagnosis.
- The UI should continue showing per-collector status. If NFS capacity and
  session collectors are healthy but inventory or health is degraded, the
  summary must make the partial collector failure visible without implying that
  the export itself is unreachable.

For the observed `i-2-506-VM` failure, NFS Ganesha was active and client mount
succeeded, but `health.json` and `inventory.json` were written as error because
the standalone collector Python blocks called an undefined `rpcbind_active()`
helper. The fix is to make those collectors self-contained and to preserve the
actual exception text in future cache errors.

## NFS Action Modal and Root Squash RW Update (2026-06-10)

NFS protocol activation and NFS export creation are separate operator actions
and must not share one modal body. The protocol activation modal manages only
runtime protocol state: NFS protocol mode, listen IP mode, listen IP, and the
endpoint-owned port. NFS export creation and edit modals manage export-owned
state: export name, internal backing path, backing volume selection, quota,
endpoint exposure selection, export options, and POSIX/squash ownership fields.

UI rules:

- `enableProtocol` shows protocol, listen IP, port, and NFS protocol mode only.
- `nfsExport` and `editNfsExport` show the full export form including name and
  internal backing path.
- Protocol activation must not show backing volume or export fields.
- Export creation must not render an empty modal if no export-specific block is
  active.

Runtime rules for writable Root Squash exports:

- When an export is writable and Root Squash is enabled, the desired state must
  carry effective anonymous UID/GID defaults. The default is UID/GID `65534`
  unless the UI/API supplies explicit values.
- The System VM must apply POSIX ownership/mode to the internal backing path
  using the same effective anonymous UID/GID and writable mode defaults.
- The Ganesha renderer must emit `Anonymous_uid` and `Anonymous_gid` in the
  export/client configuration so NFSv4 write behavior matches the directory
  ownership prepared by `ablestack-storagectl`.
- ACL-level anonymous UID/GID values override export-level values for the
  matching client block; otherwise export-level defaults apply.

This avoids the case where the mount succeeds but writes fail with
`Permission denied` because Ganesha maps root to a different anonymous identity
than the backing directory owner.

## NFS Endpoint Exposure and Implicit ACL Update (2026-06-10)

NFS protocol endpoints and NFS export exposure must be managed as separate
concepts. Adding a new endpoint only prepares a listener IP/port and must not
automatically expose existing exports through that endpoint. An export is
visible only on the endpoint set stored in the export configuration. If an
older export has no explicit endpoint metadata, it is treated as a selected
endpoint export and is rendered only through the current/default endpoint used
for that apply operation, not through every future endpoint.

NFS ACL handling uses an implicit-open model:

- When an export has no explicit ACL rows, the System VM renders a computed
  wildcard Ganesha `CLIENT` entry (`Clients = *`) using the export-level
  permission, squash, sync, secure, and anonymous UID/GID defaults.
- When one or more explicit ACL rows exist, only those ACL rows are rendered;
  the implicit wildcard entry is suppressed.
- If the operator deletes every explicit ACL row for an export, the runtime
  reverts to the implicit wildcard entry.
- The implicit wildcard entry is not stored as a DB ACL row. It is calculated
  during desired-state rendering and shown in the UI as a non-editable,
  non-deletable default row so the operator can see why the export is open.
- Runtime visibility probes must use the same rendered clients. Therefore an
  export with no explicit ACL must probe as wildcard-open instead of failing
  with `no local IPv4 address is allowed by export clients: none`.

UI endpoint and port display rules:

- Export tables display the actual endpoint IPs selected for that export.
- Endpoint port display is derived from runtime/protocol endpoint state; the
  export itself does not own the port.
- Protocol activation remains the place to add or edit endpoint IP/port.
  Existing exports are not attached to a newly added endpoint unless the export
  configuration explicitly selects that endpoint or the operator edits the
  export to use all endpoints.

## NFS Protocol Mode Fixed-Policy Update (2026-06-10)

The NFS protocol mode is a Storage Service instance policy, not an export-owned
setting. The operator chooses the mode when the shared file system is created:

- `V4_ONLY` is the default. It is the feature-complete mode and supports
  endpoint-specific listener IP/port isolation, export-to-endpoint selection,
  and independent Ganesha managed endpoint processes.
- `V3V4_DUAL` is an opt-in compatibility mode for clients that require NFSv3.
  Because NFSv3 relies on VM-global rpcbind/mountd/rquota behavior, this mode
  is service-wide and must not be mixed per export or per endpoint.

After creation, the mode is immutable:

- `enableStorageServiceProtocol`, `createStorageNfsExport`, and
  `updateStorageNfsExport` may echo the existing mode, but a different requested
  mode is rejected.
- The UI shows the current mode as read-only in protocol/export actions after
  creation. Export rows may display the mode for context, but they do not own
  it.
- In `V3V4_DUAL`, the port is fixed to TCP `2049` and additional per-endpoint
  custom IP/port isolation is rejected. Operators that need custom ports or
  endpoint-level exposure should use `V4_ONLY`.

SystemVM rendering follows the same policy:

- Desired-state payloads carry a service-level `protocolMode`.
- In `V4_ONLY`, the renderer keeps the existing endpoint-specific Ganesha
  process model.
- In `V3V4_DUAL`, the renderer collapses exports into one service-wide managed
  Ganesha runtime, renders `Protocols = 3,4`, enables `mount_path_pseudo`, and
  validates NFSv3/NFSv4 visibility through the client-visible pseudo path such
  as `/<export-name>`.

Initial NFS ACL entry is optional:

- If the operator leaves the allowed CIDR blank during creation, no DB ACL row
  is created and the runtime uses the implicit wildcard `CLIENT` entry.
- The review panel and NFS ACL table must describe this as an implicit
  `allow all` state, not as a missing ACL.
- Adding any explicit ACL suppresses the wildcard. Deleting all explicit ACLs
  restores the wildcard runtime behavior.

Compatibility-created protocol rows are not mode decisions:

- The legacy SharedFS compatibility sync may create the Storage Service instance
  and an NFS protocol row immediately after the SharedFS VM and backing volume
  are bound. This row only records that the compatibility NFS protocol exists;
  it must not be treated as the immutable NFS protocol mode decision.
- An NFS mode is considered fixed only when the protocol config explicitly
  contains `protocolMode`/`protocolmode`.
- If the compatibility row exists without an explicit mode, the first
  `enableStorageServiceProtocol` call is allowed to set the service mode to the
  operator-selected value (`V4_ONLY` or `V3V4_DUAL`). If the request omits a
  mode, the service stores the default `V4_ONLY` mode at that point.
- Once an explicit mode is stored, later protocol/export actions may only echo
  that mode. A different requested mode is rejected.
- This prevents a creation-time `V3V4_DUAL` selection from being rejected as a
  V4-only change merely because the SharedFS compatibility sync pre-created an
  unconfigured NFS protocol row.

UI runtime mode source of truth:

- The NFS management UI must not infer the service mode from a hard-coded
  default after creation.
- The preferred source is the protocol API response field
  `protocolmode`/`protocolMode` when available.
- Runtime cache fallback must read the SystemVM monitor Ganesha fields in this
  order: `health.nfsGanesha.protocolMode`,
  `health.nfsGanesha.endpoints[].protocolMode`,
  `inventory.nfsGaneshaRuntime.protocolMode`, and
  `inventory.nfsGaneshaExports[].protocolMode`.
- Only if no protocol/API/runtime cache value exists may the UI fall back to
  `V4_ONLY`.
- In `V3V4_DUAL`, the protocol activation modal keeps only the NFS mode and
  port fixed. It must show `NFSv3 + NFSv4`, fix the port to `2049`, and keep
  the listen-IP mode, existing-IP selector, and new-IP input enabled.
- A Dual Mode protocol activation request with an additional listen IP is a
  service-IP registration operation. The backend registers the IP on the
  Storage Service System VM but does not create a separate Ganesha endpoint and
  does not mutate the service-wide protocol row's canonical listen IP.
- Dual Mode operator guidance must use dark-mode-safe alert styling and must be
  sourced from UTF-8 locale files. Broken placeholder text such as repeated `?`
  is treated as a release-blocking UI defect.
- Dialog behavior must be mode-aware:
  - `V4_ONLY` uses listener port group controls. Protocol activation registers
    service IP/port listeners, and NFS export create/update selects one or more
    listener group ports. The UI must not present destination-IP-specific export
    isolation because the runtime model exposes a selected port group through
    all active Storage Service IPs.
  - `V3V4_DUAL` uses service-wide exposure. Protocol activation may add service
    listen IPs, but NFS export create/update must hide endpoint/port-group
    selection and show a read-only summary that every export is exposed through
    all service listen IPs on port `2049`.
- ACL behavior is export-scoped in both modes. ACL rows are client source
  IP/CIDR rules attached to one export. They are not destination endpoint rules.
  In `V3V4_DUAL`, the selected export's ACL applies identically through every
  service listen IP because the single Ganesha runtime binds `0.0.0.0:2049` and
  shares one export namespace.

### NFS Dual Mode listen-IP synchronization correction (2026-06-11)

Dual Mode uses one VM-wide Ganesha listener (`0.0.0.0:2049`) and exposes every
NFS export through every active Storage Service listen IP. The Cloud DB
secondary-IP row is not sufficient evidence that the SystemVM can actually
serve that IP; the guest OS must also have the address assigned on the selected
NIC.

The protocol activation flow therefore uses the following order for a new
listen IP:

1. Resolve the target NIC by CIDR/netmask using Cloud network data.
2. Persist the secondary IP only when it is not already present on the Storage
   Service VM NIC.
3. Dispatch QGA command `network endpoint apply` to add the address inside the
   guest (`ip addr add <listen-ip>/<prefix> dev <iface>`) and persist the
   desired endpoint in `/etc/ablestack-storage/network-endpoints.json`.
4. Apply the protocol desired state only after the guest address is active.
5. Roll back the newly-created DB secondary-IP row if the guest activation or
   desired-state apply fails.

The SystemVM monitor must run `network endpoints reconcile` before collecting
health/inventory/session cache. This makes secondary listen IPs boot-safe and
keeps UI endpoint summaries based on real runtime state.

UI endpoint rendering must not collapse a runtime wildcard listener
`0.0.0.0:2049` to the primary service IP. Instead, the UI expands the wildcard
listener to the merged service endpoint list after guest/network reconciliation
has made those addresses active. In Dual Mode, export rows display every active
service listen IP; in NFSv4-only mode, export rows continue to display the
selected endpoint subset.
### NFS listener group exposure model correction (2026-06-11)

Runtime validation on the Storage Service System VM showed that NFS-Ganesha does not provide a reliable per-export destination-IP isolation model in the current template. A Ganesha configuration rendered with a specific `Bind_addr` can still make the same export reachable through other service IPs on the same TCP port. Therefore Storage Service NFS must not present or persist IP-specific export exposure for NFSv4-only mode.

The implementation standard is:

- `V3V4_DUAL` mode is service-wide and fixed to TCP `2049`. Every NFS export is rendered into the same service-wide Ganesha listener and is reachable through all active Storage Service IPs.
- `V4_ONLY` mode separates exports by listener group port, not by individual IP. A listener group is identified by its TCP port. Every export assigned to a listener group is reachable through all active Storage Service IPs on that port.
- Adding a service IP to an existing listener group exposes that listener group's existing exports on the new IP. The UI must warn about this behavior.
- Adding a new port creates an empty listener group until an export is explicitly assigned to that port.
- `storage_file_share.config_json.listenerGroupPorts` is the source of truth for export exposure in `V4_ONLY` mode. The legacy `listenIps` field is retained only for read compatibility and must not drive new Ganesha rendering.
- The SystemVM renderer groups NFS exports by listener group port and writes one managed Ganesha configuration per port, using wildcard `0.0.0.0:<port>` listener semantics.
- The UI must display accessible endpoints as the cross product of active service IPs and the selected listener group ports. It must not imply that an export is limited to a single selected IP.

This keeps the user-visible model aligned with verified runtime behavior and prevents accidental automatic IP-to-export binding from being presented as a supported feature.

### NFS listener group implementation artifact (2026-06-11)

- UI/API/backend deployment target: 22.10 management server (10.10.22.10).
- SystemVM template artifact: http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606111104.qcow2.bz2.
- Artifact SHA256: c49fd8df80077da5f17c0c21019018c0c0060840afc7cbe7df66588507b1c57e.
- The deployed model treats NFSv3+v4 dual mode as service-wide TCP 2049 exposure across all service IPs. NFSv4-only exports are grouped by listener port, and every service IP exposes the exports assigned to that listener group.
- Export-specific endpoint selection by individual destination IP is intentionally not represented because the current Ganesha runtime binding does not enforce that isolation reliably.

### NFS listener persistence correction (2026-06-11)

The initial NFS listener created with a SharedFS/Storage Service instance is a
first-class listener, not an implicit default. Its port must be stored and
reapplied exactly like listeners added later. Losing the initial listener row
causes a later protocol activation such as `10.10.22.201:2050` to replace the
runtime default and stop the original `2049` Ganesha process.

The corrected model is:

- `storage_service_protocol` may contain multiple NFS rows for one Storage
  Service instance. The logical key is `instance_id + protocol + listen_ip +
  port`; a blank `listen_ip` represents the wildcard/default listener.
- SharedFS initial synchronization creates or preserves the default NFS row with
  port `2049`. It must not overwrite later NFS listener rows such as `2050`.
- `enableStorageServiceProtocol` for NFS creates or updates only the matching
  endpoint row. It must not update an unrelated existing NFS row selected only
  by `instance_id + protocol`.
- `applyNfsDesiredState` sends all enabled NFS listener rows to the SystemVM and
  selects `2049` as the fallback port whenever that listener exists.
- Every NFS export in `V4_ONLY` must have `listenerGroupPorts`. Legacy export
  JSON with `ALL`, `SELECTED`, or `listenIps` is normalized before desired-state
  apply and persisted back with `endpointMode=LISTENER_GROUP` and an explicit
  listener port list.
- Adding a new listener port never mutates existing exports. Existing exports
  stay attached to their stored `listenerGroupPorts`; newly-added ports remain
  inactive until an export is explicitly assigned to them.
- Deleting a listener must be explicit and must not remove other NFS listener
  rows. If an export still references the listener port, deletion is blocked by
  validation.

### NFS listener group list API correction (2026-06-11)

The UI source of truth for NFSv4-only listener port group selection is the
persisted `storage_service_protocol` listener row set, not only the current
runtime Ganesha listeners or the listener groups already referenced by exports.
This distinction is required because a newly enabled listener port can be valid
and selectable before any export is assigned to it; in that state the SystemVM
may not start a Ganesha process for the port yet.

Implementation rules:

- Add `listStorageServiceProtocols` and return every protocol/listener row for
  the Storage Service instance, including NFS rows with explicit `port`,
  `listen_ip`, `state`, `protocolMode`, and `config`.
- In the NFS tab, build V4-only listener port group choices from:
  persisted protocol listener rows first, then runtime observations, then
  existing export config for compatibility.
- Do not infer that a configured but unused listener is broken merely because no
  managed Ganesha process is listening on that port. Runtime listener readiness
  is required after an export selects the listener group.
- Dual Mode behavior remains unchanged: it is service-wide, fixed to TCP 2049,
  and the UI must not expose per-export listener group selection.

This rule prevents the observed failure where adding `2050` shut down the
initial `2049` listener and moved an existing export to the new default port.

### NFSv4 listener-group Ganesha isolation and create rollback correction (2026-06-11)

Runtime validation on `i-2-530-VM` showed that multiple managed Ganesha
processes can parse their endpoint configs and still fail when each process also
tries to bind shared auxiliary RPC services. The observed fatal marker was an
RQUOTA/IPv6 bind collision while starting another NFSv4-only listener group.

The corrected NFS runtime rules are:

- Dual Mode remains unchanged. `V3V4_DUAL` is still service-wide, fixed to TCP
  `2049`, and does not support per-export listener-group selection.
- `V4_ONLY` listener-group configs must render the endpoint as TCP-only NFSv4
  and explicitly disable auxiliary services that are not required by NFSv4-only
  mounts:
  - `Transports = TCP`
  - `Enable_NLM = false`
  - `Enable_RQUOTA = false`
- The Storage Service still renders one managed Ganesha config/process per
  listener-group port. Each process keeps its own config, pid file, and log file
  under `/etc/ganesha/ablestack-storage` and
  `/run/ablestack-storage/ganesha`.
- Successful apply is not a DB state transition. It requires the managed process
  to stay alive, the listener port to accept TCP, and the local NFSv4 visibility
  probe for every rendered export to pass.

Create-time rollback must also be strict. If an NFS export create operation
prepares a backing volume and then fails during desired-state apply, the
management server must remove the just-created export row and ACL rows instead
of leaving an `Error` export in the operational list. When the request supplied a
newly-created backing volume with cleanup enabled, the management server must
ask the System VM to run `volume detach prepare` before detaching/destroying the
volume so guest mountpoints and fstab entries do not remain after the Cloud
volume is removed.

This keeps failed exports out of later desired-state rendering and prevents the
specific stale state where a failed `nfs03` row referenced an expunged volume
while `/export/nfs03` remained on the root filesystem.

### Storage Service boot reconcile for NFS runtime recovery (2026-06-12)

Runtime comparison between `i-2-532-VM` and rebooted `i-2-531-VM` showed that
fstab-backed data-volume mounts and `/export/<name>` bind mounts survive a
SystemVM reboot, but the managed Ganesha processes do not. The monitor cache
service is intentionally a collector and must not be treated as the desired
state applier. A Storage Service VM can therefore return with database state
`Ready` while no NFS listener is running.

The corrected boot behavior is:

- Successful `ablestack-storagectl nfs export apply` persists the exact last
  successful NFS apply payload under
  `/etc/ablestack-storage/desired-state/nfs-export-apply.json`.
- A new oneshot `ablestack-storage-reconcile.service` runs during SystemVM boot
  after local filesystems and network-online. It is ordered before
  `ablestack-storage-monitor.service`.
- The reconcile service runs `mount -a`, `network endpoints reconcile`, and then
  reapplies the saved NFS payload through the existing
  `ablestack-storagectl nfs export apply` command. This intentionally reuses the
  same V4-only listener-group and Dual Mode validation paths used by QGA
  commands.
- If volumes or generated systemd mount units are still settling, the reconcile
  service retries for a bounded period. On success it refreshes the monitor
  cache once so UI status no longer reports a stale degraded runtime.
- The service is a local recovery safety net. The preferred authoritative path
  remains management-server/host-agent desired-state reconciliation after VM
  start events, but the SystemVM must still be able to restore the last known
  good NFS runtime while waiting for that external reconciliation.

Dual Mode constraints remain unchanged by boot reconcile. A saved Dual Mode
payload still renders service-wide TCP `2049` only, while V4-only payloads can
restore multiple listener-group ports such as `2049`, `2050`, and `2051`.

### Systemd-owned Ganesha endpoint runtime for boot-safe NFS recovery (2026-06-12)

Runtime hot-patch validation on `i-2-535-VM` confirmed that boot reconcile must
not leave `ganesha.nfsd` as a child process of the oneshot reconcile command.
When Ganesha is started with `subprocess.Popen()` from
`ablestack-storage-reconcile.service`, systemd can clean up the service cgroup
when the oneshot unit exits. The reconcile log can therefore report success while
no NFS listener remains alive.

The corrected runtime ownership model is:

- `ablestack-storagectl` renders one config per NFS listener group under
  `/etc/ganesha/ablestack-storage/<listener-key>.conf`.
- Each listener group is started by systemd as
  `ablestack-storage-ganesha@<listener-key>.service`, not as a direct child of
  the QGA/reconcile command.
- The unit runs `ganesha.nfsd -F` with per-listener pid/log files under
  `/run/ablestack-storage/ganesha`. It uses `Restart=on-failure` and remains in
  its own systemd cgroup after the apply command exits.
- `start_ganesha_endpoints()` restarts only the listener keys present in the
  desired state, stops stale Storage Service Ganesha units, verifies the systemd
  `MainPID`, verifies the TCP listener, and then runs the existing export
  visibility probe.
- Boot reconcile remains a bounded `Type=oneshot` desired-state replay. It
  performs `mount -a`, restores guest service IPs, calls
  `ablestack-storagectl nfs export apply`, and refreshes monitor cache once; it
  does not own any long-running NFS process.

Validated behavior on the patched runtime:

- A single-export service reboot restored `0.0.0.0:2049`, `/export/nfs01`, and
  WSL client mount/write.
- After adding a second listener group and export, reboot restored both
  `0.0.0.0:2049` and `0.0.0.0:2050`, restored `/export/nfs01` and
  `/export/nfs02`, and WSL mount/write passed on both ports.
- NFSv4-only listener-group semantics remain unchanged: exports are separated by
  listener port group, and each selected port is reachable through all active
  service IPs. Dual Mode remains service-wide TCP `2049` and is not changed by
  this lifecycle fix.

### NFS runtime display normalization and current-instance API scope (2026-06-12)

NFS-Ganesha listener groups can be rendered internally as wildcard listeners such
as `0.0.0.0:<port>`. This is a SystemVM runtime implementation detail and must
not be exposed to operators as a client endpoint. UI and API consumers must show
client-facing endpoints by expanding wildcard listeners to the active Storage
Service IP list collected from runtime network state, Cloud NIC/secondary-IP
state, and stored Storage Service endpoint metadata. If no real service IP is
available, the UI must show `-` rather than `0.0.0.0`.

Session/runtime listing must also stay scoped to the active Storage Service
instance for the SharedFS being viewed. `listStorageServiceSessions` therefore
accepts `sharedfilesystemid` in addition to `instanceid`; when the SharedFS ID is
used, the backend resolves the SharedFS VM and returns only the active Storage
Service instance attached to that VM. Stale or removed Storage Service instance
rows must not create extra error cards in the NFS tab.

NFS service tables use a common action-column standard: the final action column
is fixed to the right, action buttons are right-aligned, and the fixed column has
an explicit light/dark background so horizontal table scrolling does not overlay
or visually corrupt the buttons.
## SMB Local Mode Direction From Empirical Test - 2026-06-12

The first SMB implementation phase must target non-AD local-user mode and
reuse the Storage Service lifecycle pattern proven by NFS: protocol activation,
share management, access policy, backing volume management, session/status
monitoring, and QGA-driven System VM desired-state application.

Empirical validation on a running NFS Storage Service VM confirmed that Samba
can run alongside managed NFS Ganesha listeners. A temporary standalone Samba
configuration successfully exposed a local SMB share while NFS listeners on
ports 2049 and 2050 stayed active. SMB share listing worked immediately, but
write access failed until the share directory ownership and mode were changed
from root-owned `0775` to the SMB local user/group with `0770`. This makes POSIX
ownership and directory mode first-class SMB share settings, not optional
implementation details.

The SMB local-user design is therefore:

- Manage Samba users and Linux backing users together through QGA; passwords
  are transient API inputs and are not stored in UI state.
- Create/update shares from a desired-state model that renders managed Samba
  config, validates the backing path, applies POSIX owner/group/mode, and then
  reloads or restarts Samba.
- Keep the client-visible name as `\\<service-ip>\<share-name>` while the
  internal backing path follows the Storage Service `/export/<share-name>`
  convention backed by mounted data volumes.
- Treat SMB port customization as an advanced service endpoint option. Samba
  supports multiple `smb ports`, but normal Windows UNC access does not encode a
  port, so the default operator path remains TCP 445.
- Extend the monitor cache to read managed SMB config and runtime state instead
  of distro-default shares such as `homes`, `printers`, and `print$`.
- Add AD domain join as a later authentication mode over the same share and
  backing-volume model, not as part of the first local-mode implementation.

AD member mode was also validated against `ablestack.local` on 2026-06-13.
That test confirms the following implementation rules:

- The System VM must temporarily or persistently use AD DNS for join and domain
  lookup. Normal external resolvers are not enough for Samba DC discovery.
- The generated NetBIOS name must be stable and no longer than 15 characters;
  the long System VM hostname cannot be used directly.
- Samba AD member state must be persistent and managed. A fully throwaway
  `private/lock/state/cache/pid` directory layout allowed domain join and
  identity lookup, but `smbd` did not open TCP 445. Using Samba's normal
  persistent state directories allowed `smbd`, `winbindd`, domain identity
  lookup, and AD-authenticated SMB access to work.
- Share authorization must align Samba ACL entries with POSIX ownership or ACLs
  derived from winbind idmap. In the validation, `ABLESTACK\Domain Users`
  mapped to GID `10513`; setting the share directory to `root:10513` with mode
  `0770` allowed the domain user `ablecloud` to write through the SMB share.
- AD join credentials and SMB application-user credentials are different
  concerns. The UI/API must collect join credentials only for join/leave and
  must test share access with the selected domain users or groups separately.

Local and AD SMB shares can coexist in the same Samba runtime. In AD member
mode, Samba still accepts local passdb users, but local clients must qualify
the user with the managed server NetBIOS name, for example
`STOR536MIX\local-user`; a bare local username can be interpreted as a domain
login and fail with `NT_STATUS_LOGON_FAILURE`. AD clients continue to use the
domain form, for example `ABLESTACK\domain-user`, and share ACLs can target
either explicit AD users or AD groups such as `ABLESTACK\Domain Users`.

The implementation must therefore model SMB share principals as typed entries:

- `LOCAL_USER` / `LOCAL_GROUP`: created and managed inside the Storage Service
  VM, shown to clients as `<server-netbios>\<name>` when the service is joined
  to AD, and backed by local POSIX ownership or ACLs.
- `AD_USER` / `AD_GROUP`: resolved through winbind, stored as domain-qualified
  names, and backed by winbind UID/GID ownership or POSIX ACLs.

Generated Samba share configuration must not flatten these identities into a
single string list. It must render local and AD principals with the correct
Samba syntax, apply matching filesystem ownership/mode, and keep local and AD
authorization boundaries separate even when both share types are active in the
same `smbd` process.

NFS export directories may be reused as SMB shares, but only through an
explicit cross-protocol sharing model. Empirical validation showed that pointing
an SMB share at `/export/nfs01` while the same directory was already exported
through NFS works when the SMB principal has POSIX access to that directory. SMB
writes were visible through the NFS mount, and NFS writes were readable through
SMB. The implementation must therefore support a "share existing backing path"
workflow, but it must also make the permission mapping explicit.

The cross-protocol rules are:

- Do not silently create an SMB share on a path already used by NFS; the UI must
  identify the existing protocol use and require the operator to confirm shared
  visibility.
- Do not force SMB access to `nobody` or another unmanaged system account. That
  can fail with invalid Samba SID mappings and hides the actual ownership model.
- Require a managed SMB principal, either local or AD, and apply matching POSIX
  ownership, mode, or POSIX ACLs to the reused directory.
- Show the reused backing path in both NFS and SMB tabs and mark it as
  cross-protocol so operators know both protocols expose the same files.
- Preserve protocol-specific ACLs separately: NFS client CIDR rules still govern
  NFS clients, while SMB local/AD users and groups govern SMB clients.

### SMB AD Join Runtime Hardening - 2026-06-13

Fresh NFS+SMB creation with AD authentication exposed a System VM runtime
defect: `smb_domain_join()` failed before `net ads join` because the embedded
Python join helper used `re.sub` and `re.split` without importing `re`. The
resulting partial state is misleading: the SharedFS, Storage Service instance,
NFS protocol, SMB protocol, and SMB share rows can remain `Ready`, while Samba
is still configured as a local `WORKGROUP` server and the AD trust is absent.

The implementation must harden AD join as follows:

- Every embedded Python block in `ablestack-storagectl` must be syntax checked
  and must import all modules it uses. For AD join, `json`, `os`, `re`,
  `subprocess`, and `sys` are required.
- `smb_domain_join()` must be idempotent and phase-oriented:
  1. validate payload fields and generated NetBIOS name
  2. apply AD DNS resolver state
  3. render Samba AD member global configuration
  4. run `testparm -s`
  5. execute `net ads join`
  6. restart or enable `smbd`, `nmbd` when needed, and `winbind`
  7. verify `net ads testjoin` and `wbinfo -t`
  8. persist AD state only after trust verification succeeds
- A failed join must not be reported as a healthy AD-authenticated SMB service.
  The management layer and monitor cache must surface an identity state such as
  `JOIN_FAILED` or an equivalent warning while preserving the already-created
  SMB share rows for retry.
- SMB desired-state application and AD join must remain separate retryable
  operations. Operators should be able to retry AD join from the SMB tab without
  deleting the SharedFS or recreating the SMB share.
- The monitor cache must distinguish:
  - `smbd`/`nmbd`/`winbind` daemon state
  - Samba security mode (`user` vs `ADS`)
  - configured workgroup/realm/netbios name
  - AD trust result from `net ads testjoin`
  - winbind trust result from `wbinfo -t`
- Sensitive join inputs, especially the AD join password, must remain transient
  QGA/API payload data and must never be written to `smb-domain.json`, monitor
  cache files, logs, review panels, or UI state.

Runtime acceptance for AD mode requires all of the following:

- `/etc/samba/smb.conf` contains `security = ADS`, the expected `realm`,
  `workgroup`, and managed `netbios name`.
- `smbd` and `winbind` are active.
- TCP 445 is listening.
- `net ads testjoin` succeeds.
- `wbinfo -t` succeeds.
- At least one selected AD user or group can access the configured share with
  the expected read/write behavior.

### SharedFS Create Modal Owner Dark-Mode Alignment - 2026-06-13

The create modal owner section is a collapsible block above the main Storage
Service sections. It currently uses a separate low-level gray `rgba` palette,
which makes the owner block visually inconsistent with the rest of the dark
mode modal. The owner selector is functionally correct but must follow the same
visual standard as the Storage Service creation sections.

UI design rules:

- Keep the owner selector in the current top position and keep the collapsed
  summary format `Owner Type (type / domain / account-or-project)`.
- Use the same dark-mode background, border, header, and content color tokens
  as the other Storage Service collapse sections.
- Avoid bright outer borders that create a white-box effect in dark mode.
- Ensure labels, selected values, dropdown arrows, required marks, and disabled
  states remain readable in normal and dark modes.
- The owner block must not introduce a separate visual hierarchy from the rest
  of the modal; it is an advanced ownership section, not a separate dialog.
- Retest with Korean UI, dark mode, and the current two-pane create modal
  layout before marking the create dialog look-and-feel as passed.

## SMB Storage Service Integrated Implementation Design - 2026-06-13

### Implementation baseline update - 2026-06-13

- SMB shares use the same client-root rule as NFS: the exposed share name is the client root, while the backing directory is a direct child of `/export`.
- SMB backing paths default to `/export/<share-name>`. Reusing an NFS backing path is allowed only when the caller explicitly enables cross-protocol sharing.
- SMB desired state is persisted separately from NFS desired state and replayed independently during SystemVM boot reconcile.
- AD-joined SMB keeps a deterministic NetBIOS name for the Storage Service VM so local and AD principals can coexist predictably.
- SMB share ACL application includes Samba `valid users`/`write list`/`admin users` rendering and POSIX ACL updates for local or AD users/groups when the SystemVM can resolve them.
- The SMB UI follows the NFS tab pattern: vertical dark-mode dialogs, row-level edit/delete actions, explicit directory creation, cross-protocol sharing, and backing directory permission controls.

This section turns the SMB empirical validations into the implementation plan
for SystemVM, backend engine, API, and UI. SMB must follow the same Storage
Service rules already established by the NFS work:

- The Cloud UI submits asynchronous API requests only.
- The backend engine persists desired state and sends commands to the Mold host
  Agent that owns the Storage Service System VM.
- The host Agent delivers all in-guest storage operations through QGA.
- SystemVM boot-time cloud-init must not own service configuration. Runtime
  service state is rendered and reconciled through Storage Service QGA commands.
- Successful desired state is persisted inside the SystemVM and replayed by the
  boot reconcile service.
- Monitor data is read from a SystemVM-side cache file produced by the storage
  monitor service, not from expensive on-demand service probes for every UI
  refresh.
- Backing data paths remain under `/export/<share-name>` unless the operator
  explicitly chooses an existing cross-protocol backing path.
- UI layout, modal behavior, table scrolling, fixed action columns, dark mode,
  and i18n behavior must match the NFS tab standard.

### SystemVM design

The Storage Service SystemVM template must include and validate the SMB runtime
packages before it is published:

- `samba`, `samba-client`, `samba-common-tools`
- `winbind`, `krb5-workstation`, `oddjob-mkhomedir` if required by the
  distribution package set
- `bind-utils` or equivalent DNS diagnostic tools
- `acl` for POSIX ACL management
- Existing QGA and Storage Service utilities

The SystemVM storage command surface extends `ablestack-storagectl` with SMB
operations:

- `smb desired-state apply`
- `smb share apply`
- `smb share delete`
- `smb principal apply`
- `smb principal delete`
- `smb ad join`
- `smb ad leave`
- `smb ad status`
- `smb sessions list`
- `smb session close`
- `smb monitor collect`

SMB desired state is rendered from one managed source file, for example
`/etc/ablestack-storage/desired-state/smb.json`, and one generated Samba config
tree under `/etc/ablestack-storage/smb/`. The implementation may either render a
full managed `smb.conf` or include a generated Storage Service section from the
distribution `smb.conf`, but it must not mix unmanaged distro shares such as
`homes`, `printers`, or `print$` into the Storage Service monitor response.

The SMB apply flow is:

1. Validate the desired state schema and reject unknown share/principal/auth
   modes.
2. Ensure backing volumes are attached, formatted or mounted according to the
   existing NFS backing-volume rules, and persisted in `/etc/fstab` by UUID.
3. Validate every backing path. New SMB-only shares use `/export/<share-name>`.
   Cross-protocol shares may reuse an existing NFS backing path only when the
   desired state explicitly marks it as cross-protocol.
4. Apply POSIX owner/group/mode or POSIX ACLs for the selected SMB principal
   model.
5. Create/update local Linux users and Samba passdb users for local mode.
   Passwords are accepted only as transient command inputs and must not be
   written into desired-state JSON, monitor cache, logs, or UI state.
6. For AD mode, ensure the managed NetBIOS name is stable and no longer than 15
   characters, ensure AD DNS resolver behavior is applied for join/runtime
   lookup, and maintain persistent Samba/winbind private state.
7. Render the managed Samba config and validate it with `testparm`.
8. Start or reload `smbd` and `winbindd` as required.
9. Verify runtime by checking process state, TCP listeners, `smbclient`
   loopback access for configured shares where a non-secret probe is possible,
   and AD join health when the instance is domain joined.
10. Persist the last successful desired state for boot reconcile.
11. Refresh the SMB section of the monitor cache.

The boot reconcile service must replay SMB desired state after `mount -a` and
network endpoint reconciliation, in the same lifecycle family as NFS reconcile.
SMB reconcile must not destroy AD membership or passdb state. It should repair
rendered config, restart `smbd`/`winbindd`, and refresh monitor cache.

The monitor cache must include at least:

- SMB runtime status: `ok`, `degraded`, `error`
- active authentication mode: local, AD member, or mixed
- AD join state and DC reachability when joined
- server NetBIOS name
- service IP and SMB port list
- shares, backing paths, cross-protocol flags, and volume IDs
- principal ACL summaries
- sessions from `smbstatus` with client address, user, share, opened files when
  available, and session age when available
- last refresh time and last error text

### Backend engine design

The backend must reuse the NFS service-instance lifecycle and avoid creating a
parallel SMB-only orchestration path. SMB is a protocol capability attached to
the same Storage Service instance.

Core engine rules:

- All create/update/delete operations are asynchronous jobs.
- DB changes and SystemVM apply must be transactional from the operator point of
  view. If SystemVM apply fails during a create operation, newly-created rows
  must be rolled back or marked as failed with a clear retry/delete path; stale
  successful-looking rows must not remain.
- Update operations must preserve the current tab and refresh only changed data
  in the UI, following the NFS fix for avoiding full detail-page reload.
- Desired state sent to the SystemVM must be built from DB rows, not from UI
  request fragments.
- Password and AD join secrets are command-only values. They are not persisted
  in DB, response objects, async job details, or monitor cache.

Data model additions should be separate from the existing open-source
ABLESTACK SharedFS API path and should follow the Storage Service independent
API namespace used by the NFS extension. The preferred model is:

- `storage_service_smb_share`
  - Storage Service instance ID
  - share name
  - description
  - backing volume ID
  - backing path
  - cross-protocol source protocol/path reference when reused from NFS
  - capacity limit
  - browsable, read-only, guest-access flags
  - create-directory behavior
  - status and last apply error
- `storage_service_smb_principal`
  - share ID
  - principal type: `LOCAL_USER`, `LOCAL_GROUP`, `AD_USER`, `AD_GROUP`
  - principal name and optional domain
  - permission: read-only, read-write, admin/full-control if supported
  - POSIX UID/GID or resolved winbind UID/GID cache
  - status and last resolve/apply error
- `storage_service_smb_account`
  - local managed account metadata only
  - username, enabled/disabled state, description
  - no password material
- `storage_service_smb_ad_domain`
  - Storage Service instance ID
  - domain FQDN, NetBIOS domain, DNS servers, DC preference
  - managed server NetBIOS name
  - join state, last join/check timestamp, last error
  - no join password material
- `storage_service_smb_session`
  - optional cached runtime rows if the engine stores monitor snapshots;
    otherwise list from monitor cache on demand

The engine must generate a stable SystemVM NetBIOS name no longer than 15
characters. It should be deterministic from the Storage Service instance or VM
ID so AD rejoin/reconcile does not create a new computer identity after reboot.

Cross-protocol path reuse is allowed only when the chosen backing path is
already known in Storage Service metadata. If an operator enters an arbitrary
path that overlaps another protocol without declaring reuse, the backend must
reject the request.

### API design

The SMB APIs should mirror the NFS management style and remain independent of
the legacy SharedFS API path. Suggested commands:

- `enableStorageServiceProtocol`
  - protocol: `SMB`
  - service IP mode, endpoint selection, and port remain protocol-level fields.
  - SMB defaults to TCP `445`; non-default port is an advanced option and must
    be surfaced as such because normal UNC paths do not carry a port.
- `createStorageSmbShare`
- `updateStorageSmbShare`
- `deleteStorageSmbShare`
- `listStorageSmbShares`
- `createStorageSmbAcl`
- `updateStorageSmbAcl`
- `deleteStorageSmbAcl`
- `listStorageSmbAcls`
- `createStorageSmbLocalAccount`
- `updateStorageSmbLocalAccount`
- `resetStorageSmbLocalAccountPassword`
- `deleteStorageSmbLocalAccount`
- `joinStorageSmbAdDomain`
- `leaveStorageSmbAdDomain`
- `listStorageSmbAdDomain`
- `listStorageSmbSessions`
- `terminateStorageSmbSession`

API responses must include:

- share ID, name, backing path, backing volume, capacity limit, cross-protocol
  flag, status, and last error
- client connection string examples such as `\\<service-ip>\<share-name>`
- authentication mode and NetBIOS guidance
- AD domain state when joined
- principal type and display name
- runtime status from monitor cache when available

Validation rules:

- Share names follow the same safe naming discipline as NFS export names:
  directory/share safe characters only, no path separators, no traversal, and no
  reserved hidden administrative names.
- Default backing path for new SMB shares is `/export/<share-name>`.
- Cross-protocol shares must reference an existing NFS export/share path and
  require explicit confirmation.
- Local account passwords are required only for create/reset password
  operations and are never returned.
- AD join credentials are required only for join/leave when needed and are never
  persisted.
- In AD member mode, local account connection help must use
  `<server-netbios>\<local-user>`.

### UI design

SMB UI must be implemented as a first-class protocol tab under the existing
SharedFS detail view, matching the NFS tab instead of introducing a separate
navigation model.

SharedFS create modal:

- Keep the existing two-column large modal style established for Storage
  Service creation.
- Add an SMB section next to NFS/iSCSI/NVMe-oF sections.
- Allow initial SMB share creation when SMB is selected.
- Authentication mode options:
  - local account
  - AD domain member
  - mixed, only when AD is joined and local accounts are also enabled
- Local mode fields:
  - share name
  - backing volume selection using the NFS backing-volume component
  - backing path `/export/<share-name>` with validation
  - local account create/select
  - permission and directory mode
- AD mode fields:
  - domain FQDN, NetBIOS domain, DNS server, optional DC
  - join account/password as transient fields
  - AD user/group principal selection or manual entry
  - share permission
- Cross-protocol option:
  - visible only when existing NFS exports/backing paths exist
  - shows the existing NFS export name, backing path, NFS ACL summary, and a
    warning that both protocols expose the same files
  - requires explicit confirmation before submit

SMB tab layout follows NFS:

- Status summary card
  - endpoint(s), port(s), authentication mode, AD join state, monitor cache
    state, last refresh
- Connection information card
  - local mode: `\\<service-ip>\<share-name>` and username guidance
  - AD mode: `ABLESTACK\user` or `ABLESTACK\group`
  - AD member with local user: `<server-netbios>\<local-user>`
- Shares table
  - share name, UNC path, backing path, cross-protocol flag, capacity, auth
    mode, read/write state, status, action column
- Access/principal table
  - share name, principal type, principal name, permission, resolved UID/GID,
    status, action column
- Local accounts table
  - username, enabled state, used-by shares, action column
- AD domain panel/table
  - domain, NetBIOS domain, DNS/DC, join state, last check, action column
- Backing volumes table
  - same visual and behavior standard as NFS, including detach disabled when a
    share still uses the volume
- Sessions table
  - client, user, share, connected time when available, opened files when
    available, terminate action

All SMB modals must use the NFS modal standard:

- vertical layout, centered in the browser viewport
- fixed header and footer, scrollable content body
- required-field marks and tooltip icons
- no duplicated long helper text below every input; show inline validation only
  when needed
- dark-mode friendly colors matching the NFS modal palette
- Korean i18n coverage so label keys are never shown in the UI
- fixed right action column in tables and compact dark-mode scrollbars

SMB action placement:

- Protocol-level actions stay in the tab toolbar or status card:
  enable SMB, edit endpoint/port, join AD, leave AD.
- Share actions stay on share rows:
  edit share, delete share, expand share capacity.
- ACL/principal actions stay on ACL rows:
  edit principal, delete principal.
- Account actions stay on account rows:
  disable, reset password, delete.
- Session actions stay on session rows:
  terminate session.

### Implementation phases

1. SystemVM SMB foundation
   - package verification, storagectl commands, local Samba desired-state apply,
     monitor cache, boot reconcile.
2. Backend/API local SMB
   - share/account/principal CRUD, QGA command dispatch, rollback semantics,
     monitor cache response mapping.
3. UI local SMB
   - create modal SMB section, SMB tab, local accounts, shares, ACLs, backing
     volumes, sessions, dark mode/i18n.
4. AD member mode
   - join/leave/status API, persistent Samba/winbind state, NetBIOS generation,
     AD DNS handling, AD principal resolution.
5. Cross-protocol NFS/SMB reuse
   - explicit reuse workflow, permission mapping, UI warnings, monitor markers.
6. Regression and lifecycle tests
   - create/update/delete, reboot reconcile, local/AD/mixed access, session
     listing/termination, NFS coexistence, and dark-mode visual checks.

The implementation should begin with local SMB because the local-mode test
established the base Samba lifecycle and because the AD member mode builds on
the same share/principal/backing-volume model. AD support must not be bolted on
as a separate SMB implementation.

### SMB AD join reliability model

SMB AD domain join is a retryable identity operation, not a one-time side
effect of SharedFS creation. The UI and API must keep the password ephemeral:
the join password is accepted only for the async API request, passed to the
Storage Service VM through QGA, and never stored in DB, UI state, monitor cache,
or SystemVM state files.

The SystemVM join flow is:

1. Render DNS and Kerberos configuration from the requested AD domain.
2. Derive the Kerberos realm from the FQDN, for example `ablestack.local` to
   `ABLESTACK.LOCAL`.
3. Derive the NetBIOS workgroup from the explicit workgroup when provided, or
   from the FQDN first label, for example `ABLESTACK`.
4. Validate Samba syntax with `testparm -s`.
5. Validate credentials with `kinit <user>@<REALM>` before attempting the domain
   join. If the supplied username has no `@` or `\`, the SystemVM converts it to
   UPN form. If a `DOMAIN\user` value is provided, the user part is used with
   the resolved realm.
6. Join the domain with Kerberos credentials using `net ads join -k`. This keeps
   the password out of the process list and separates credential failures from
   Samba join failures.
7. Clear Samba cache, restart `winbind`, `smbd`, and `nmbd`, then verify with
   `net ads testjoin` and `wbinfo --domain=<WORKGROUP> -t`.
8. Persist `/etc/ablestack-storage/smb-domain.json` only after join has
   completed. Remove `/etc/ablestack-storage/smb-domain-error.json` after final
   trust verification succeeds.

Failures are written as structured phase data:

- `validate`: missing required inputs
- `testparm`: invalid Samba configuration
- `kinit`: invalid AD credentials or Kerberos/DNS configuration
- `join`: domain join command failure
- `testjoin`: Samba machine trust failure after join
- `wbinfo`: winbind trust verification failure

The SMB tab must surface this phase and message and provide an AD domain join
retry action. Retrying must reuse the stored domain/workgroup/DNS metadata but
ask the operator for the password again. This preserves the existing SMB shares
and NFS exports while allowing identity repair.

### SMB AD status UI normalization

The SMB tab must not depend on a single backend field name when displaying AD
domain membership. A Storage Service VM can be correctly joined even when the
API status object, monitor inventory, and monitor health cache expose the same
fact with different field names. The UI must normalize these sources in this
priority order:

1. `listStorageServiceDomainStatus` / `storageService.domains` for persisted
   operator intent and DB state.
2. `inventory.smbDomain` for the latest monitor inventory snapshot.
3. `health.identity.smbDomain` for the latest health snapshot and trust check.

The normalized SMB identity panel must display identity mode, AD domain,
workgroup, join state, health state, trust verification, DNS servers, realm,
NetBIOS name, organizational unit, and any structured join error. Missing UI
fields must be treated as an unknown display value only; they must not be used
to infer that the VM is not joined when monitor cache reports `JOINED` and
trust verification reports success.

Field aliases are part of the UI contract. The UI must accept both camelCase
and lower-case variants such as `joinState`/`joinstate`,
`healthState`/`healthstate`, `trustVerified`/`trustverified`,
`dnsServers`/`dnsservers`, and `netbiosName`/`netbiosname`. This avoids false
negative status displays when the monitor cache, JSON serializer, or API
response uses a different naming style.

### SMB 초기 접근 권한 및 POSIX ACL 적용 보강

- SMB 공유 생성 API는 선택적 초기 ACL(`aclprincipaltype`, `aclprincipal`, `aclpermission`, `aclpassword`)을 함께 받을 수 있다. `aclpassword`는 로컬 사용자 생성에만 런타임으로 전달하고 DB/UI에는 저장하지 않는다.
- 공유 파일 시스템 생성 UI의 SMB AD 모드는 초기 접근 주체 기본값을 `AD_GROUP / Domain Users / READ_WRITE`로 제공한다. 운영자가 값을 비우면 공유는 생성되지만 게스트 접근이 꺼진 상태에서는 `no_acl` 상태로 남아 명시적 ACL 생성 전까지 접근을 허용하지 않는다.
- SystemVM의 SMB 적용기는 매 재적용 시 공유 디렉터리의 확장 POSIX ACL을 초기화한 뒤 현재 DB ACL만 다시 적용한다. 이로써 ACL 수정/삭제 후에도 과거 ACL이 파일시스템에 잔류하지 않도록 한다.
- AD/로컬 ACL은 Samba `valid users`, `write list`, `admin users`와 POSIX ACL(`setfacl`)을 함께 적용한다. AD 그룹/사용자는 winbind를 통해 UID/GID를 확인하고, 확인되지 않는 항목은 Samba 설정에는 남기되 `appliedAclCount`가 증가하지 않아 운영 화면에서 접근 상태를 구분할 수 있게 한다.
- `guest ok`가 켜진 공유는 게스트 접근 정책에 맞춰 디렉터리 권한을 열고, 게스트 접근이 꺼지고 ACL도 없는 공유는 닫힌 공유로 유지한다.
- SystemVM은 `/etc/ablestack-storage/smb-access.json`에 공유별 `accessState`, `configuredAclCount`, `appliedAclCount`, `guestOk`, `directoryMode`를 기록해 모니터링/상태 UI가 빠르게 참조할 수 있게 한다.

### SMB post-validation implementation gates

- Current backing volume reuse must not run the new-device safe-candidate detector. When a selected volume is already attached to the Storage Service VM and is already managed by an existing NFS or SMB share, the backend must reuse the stored managed mount metadata and derive the new share backing path under that mounted volume.
- SMB shares are closed by default when no ACL exists. Read/write access is granted only by an explicit SMB ACL entry or by explicitly enabled guest access. The runtime must not make no-ACL shares broadly writable as a fallback.
- SMB session data shown in the UI must come from `smbstatus --json` when Samba is available. The monitoring cache should merge Samba session and tree-connection data so the UI can display client, user, share name, dialect, state, and connected time instead of TCP-only placeholders.

### UI ?? ??

- Storage Service? NFS/SMB/iSCSI/NVMe-oF ?? ??? ??? ?? ??? ????. ?? ? ?? ?? ??? ?? ??, ?? ??, `storage-table-actions-column` ???? `storage-table-actions` wrapper? ????. ? ????? ?? ???? ?????? ?? ??? ????? ??? ?? ??? ??? ?? ???? ??.
- ?? ???? ??/?? ?? ?? ?? ??? ????, ??/??/??/?? ?? ?? ? ?? ??? ??? ?? ???? ????. ?? ??? ??? ?? ?? ???? ???.
- Storage Service ?? ??? ???? ???? ??, ?? ???? ?? ???? ??? ??? ??. ??? ?? ?? ??? ?? ???? ????, ?? ??/??/??/??/?? ??? `max-width: 100%`, `min-width: 0` ???? ?? ?? ?? ??? ??.
- ?? ????? ?? ?? ??, ????, No Data, ??, ?? ?? ??? ?? ?? ?? ?? ???? ??, ?? ?? ??? ??? ???? ???? ????? ??.

### NFS Ganesha endpoint runtime directory guard

- Storage Service NFS uses managed `ablestack-storage-ganesha@<endpoint>.service` units, not the package default `nfs-ganesha.service`.
- Because the package default unit is disabled by Storage Service, the managed endpoint unit and `ablestack-storagectl` must prepare every runtime directory that Ganesha itself may use.
- Required directories are `/run/ablestack-storage/ganesha`, `/etc/ganesha/ablestack-storage`, and `/run/ganesha` (`/var/run/ganesha`).
- The managed unit must remove only stale default `ganesha.pid` files before start. The Storage Service-owned pid remains under `/run/ablestack-storage/ganesha/<endpoint>.pid`.
- `ablestack-storagectl start_ganesha_endpoints()` must call the same runtime preflight before stopping old endpoint units and again before starting new endpoint units.
- Runtime error reporting must keep the endpoint log tail so a Ganesha fatal startup error is surfaced instead of a generic `Connection refused` message.

### Storage VM user-data resource loading guardrail

- The Storage VM cloud-init payload must be loaded from the Java classpath as `conf/fsvm-init.yml`. The runtime must not use a leading `/` because `ClassLoader.getResourceAsStream()` resolves packaged jar resources as classpath-relative names.
- `StorageVmSharedFSLifeCycle` normalizes any legacy leading slash before delegating to `FileUtil.readResourceFile()` and raises an explicit not-found error when the resource is absent. This prevents a missing classpath resource from being reported as an ambiguous zip or user-data read failure.
- The unit test for the lifecycle must mock the normalized resource path so build-time tests catch regressions in the resource lookup contract.
- Deployment must update the integrated management-server `cloudstack-4.22.0.0-SNAPSHOT.jar` with both `StorageVmSharedFSLifeCycle.class` and `conf/fsvm-init.yml` from the same build output. Updating only API/server jars or only the standalone plugin jar can leave the management server running an old lifecycle class.
- Post-deploy verification must confirm that the deployed jar can read `conf/fsvm-init.yml`, that the lifecycle class contains the normalized resource name, and that `mold.service` is active before SharedFS creation is retested.

### iSCSI Block-Only LUN Contract (2026-06-25)

The iSCSI target workflow exposes only dedicated block volumes. File-backed
LUN images inside a mounted filesystem are intentionally removed because they
make the service model ambiguous and overlap with NFS/SMB file-share semantics.
One iSCSI LUN maps to one ABLESTACK data volume, and the exported LUN size is
the actual volume size.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| LUN model | The UI and runtime exposed both block and file-backed backstore concepts. This made users think an iSCSI LUN could safely be a file under an NFS/SMB backing filesystem. | `BLOCK` is the only supported model. Legacy `backstoretype` API input is accepted only when omitted or set to `BLOCK`; any other value fails before DB/runtime apply. |
| Volume selection | The iSCSI target dialog could select mounted/current file-service volumes and then rely on late SystemVM inspection to reject unsafe devices. | The UI offers current attached volumes only when they are unused by NFS, SMB, iSCSI, or NVMe-oF and not mounted as a filesystem. Existing detached volumes and new volumes remain supported. |
| Current/default volume use | A default Storage Service data volume could be selected even if it was already mounted for `/export` and used by file services. | A current volume can be used for iSCSI only when it is an unused attached raw block candidate. Mounted file-service volumes are excluded from the UI and rejected by SystemVM. |
| Existing volume use | Existing detached volumes were allowed, but their filesystem/file-backed meaning was unclear. | Existing detached volumes are attached as raw block devices for iSCSI. Existing filesystem signatures are allowed as guest-visible content, but the SystemVM must not mount them. |
| New volume use | New volume creation could also ask for an independent LUN size. | New volume creation asks only for disk offering, primary storage, and volume size. The LUN size is the new volume size. |
| Engine config JSON | `lunSizeBytes` and `backstoreType` could imply a file-sized LUN separate from the selected volume. | iSCSI config always stores `backstoreType=BLOCK` and removes `lunSizeBytes`. Responses use volume size as effective LUN size. |
| SystemVM runtime | `ablestack-storagectl` had a FILEIO branch and could create file-backed LUN images. | `ablestack-storagectl` only creates `/backstores/block/<target>`. It rejects OS, boot, swap, mounted devices, or devices with mounted descendants. |
| UI | The iSCSI target dialog exposed backstore type and LUN size controls. | The dialog removes backstore type and LUN-size controls. It shows an info notice that iSCSI is block-only and that the selected volume is consumed as a dedicated LUN. |
| Failure cleanup | A failed target could leave a DB row or newly created volume behind. | Create failure removes the target row and, when a new volume was created by the UI, requests cleanup of the failed volume. |

Operational rules:

- Use iSCSI only with a dedicated raw data volume.
- Do not reuse a volume that backs NFS/SMB shares or NVMe-oF namespaces.
- Do not mount an iSCSI backing volume inside the SystemVM.
- Do not create iSCSI LUN image files under the SystemVM root filesystem or under `/export`.
- If an existing detached volume already contains ext4/xfs or any other guest
  filesystem signature, it is still treated as raw guest data and exposed as a
  block device; the SystemVM does not interpret or mount it.
- The SystemVM template must include `targetcli`/`rtslib-fb-targetctl` and the updated `ablestack-storagectl` script.

### iSCSI Authoritative Reconcile And CHAP Secret Contract (2026-06-29)

Live validation on Storage Service VM `i-2-569-VM` showed that relying on
`targetctl restore` for iSCSI persistence is unsafe. `targetctl` persists LIO
backstores with volatile `/dev/sdX` paths. After a SystemVM stop/start, guest
disk order can change, so a restored target may point at the wrong ABLESTACK
volume. The Storage Service desired state already carries stable volume UUID,
name, and expected serial hints; reboot recovery must use those identifiers as
the source of truth.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Boot restore source | `rtslib-fb-targetctl` restores `/etc/rtslib-fb-target/saveconfig.json`, which stores volatile `/dev/sdX` backstore paths. | Storage Service boot reconcile treats ABLESTACK desired state as authoritative. It clears managed LIO targets/backstores and rebuilds them by resolving each backing volume from UUID/name/serial. |
| CHAP persistence | API/DB desired state intentionally redacts CHAP secrets, so a reboot-time clear/rebuild cannot recreate CHAP ACLs. | `ablestack-storagectl` stores only CHAP secret material in a root-only SystemVM secret store and merges it into desired state during apply. Secrets remain excluded from UI, API responses, logs, and normal desired-state JSON. |
| Runtime apply | A normal apply can preserve existing CHAP credentials from the current LIO ACL, but that does not help after `clearconfig` or reboot. | Every successful apply refreshes `/etc/ablestack-storage/secrets/iscsi-acl-secrets.json` from supplied secrets or currently applied credentials. |
| Reconcile collision | Stale `targetctl` objects can conflict with desired-state reapply and fail with `storage object or path not valid`. | Boot reconcile runs iSCSI apply with `ABLESTACK_STORAGE_ISCSI_AUTHORITATIVE_REBUILD=1`, causing managed LIO state to be deleted before desired state is rebuilt. |
| Verification | Listener presence can make the service look healthy even when target/LUN mappings are missing or wrong. | Monitor inventory must compare desired target rows with runtime target/LUN/backstore data and expose missing runtime rows or serial mismatch as degraded/error. |

Implementation rules:

- Do not use `/dev/sdX` as persistent identity for an iSCSI backing device.
- The secret store path is root-only and local to the SystemVM:
  `/etc/ablestack-storage/secrets/iscsi-acl-secrets.json`.
- The secret store key is target IQN plus initiator IQN. This keeps CHAP
  target-scoped and avoids leaking one target's secret into another target.
- Boot reconcile must remain idempotent. Re-running it may restart/rebuild
  managed iSCSI state, but it must not alter unmanaged targets.
- Deleting an ACL or target prunes the corresponding secret entry on the next
  successful apply.
- The operator-visible validation result is not `Pass` until Cloud-managed
  stop/start has been performed and target/LUN/ACL/portal mapping still matches
  desired state.

### iSCSI New Volume Attach And Multi-LUN Apply Contract (2026-06-25)

When an iSCSI LUN is created from a newly created ABLESTACK volume, volume
creation, Storage Service VM attachment, guest block device discovery, and
target application are one logical operation. A target row must not become
`Ready` until the selected volume is visible as a safe raw guest block device.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| New LUN volume lifecycle | The UI created an ABLESTACK volume and then called `createStorageIscsiTarget` with the new volume ID. The backend persisted the target and immediately sent desired state to the SystemVM. | `createStorageIscsiTarget` guarantees that the selected volume is attached to the Storage Service VM before desired state is applied. A newly created volume is attached, waited for in the guest, then used as the raw block LUN. |
| Guest block discovery | `ablestack-storagectl` resolved a LUN backing device only from currently visible guest disks. If the attach was not complete, apply failed with `volumeUuid or volumeName did not match any guest disk`. | The backend waits for the guest-visible block device before persisting a Ready target. The SystemVM resolver also retries and compares full UUID, hyphenless UUID, UUID prefix, volume name, and optional path hints. |
| Same target IQN with multiple LUNs | Runtime rendering iterated per target row and deleted/recreated the same IQN for each row, which is unsafe when one IQN owns multiple LUNs. | Runtime rendering groups rows by `targetName`. One LIO target is created per IQN, with one LUN per row under that target. ACLs are merged at the IQN level, and inventory is expanded back into row-level runtime data for UI display. |
| Failure cleanup | A failed apply could leave a newly created volume unattached, attached, or expunged inconsistently while existing LUNs were reconciled separately. | If the failed operation created the volume, cleanup detaches it from the Storage Service VM when needed and then destroys/expunges it. Existing Ready LUNs remain intact and are re-applied without the failed row. |

Implementation rules:

- `StorageServiceManagerImpl.createStorageIscsiTarget` must call a dedicated
  `prepareIscsiBackingVolume` step before target persistence/apply.
- Existing or current volumes are allowed only if they are not used by NFS,
  SMB, iSCSI, or NVMe-oF resources in the same Storage Service instance.
- iSCSI volumes are never formatted or mounted inside the SystemVM.
- The SystemVM `iscsi target apply` command must render by IQN group, not by DB
  row, so one target can safely expose LUN `0`, `1`, and later LUNs together.
- UI-created volumes should continue to send `cleanupvolumeonfailure=true` so
  backend rollback can remove only volumes created for the failed operation.

### iSCSI ACL Modal and Runtime Inventory Alignment (2026-06-25)

The iSCSI UI and SystemVM monitoring contract follows the same service-tab rules already established for NFS and SMB: vertical action dialogs, dark-mode readable controls, explicit volume/backing details, and fast status rendering from cached SystemVM inventory files.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| iSCSI ACL dialog layout | The ACL dialog used a horizontal two-column form and could create unnecessary horizontal scroll in dark mode. | The ACL dialog is vertical: target selection, target summary, initiator IQN, permission, optional CHAP section, and fixed footer. Only the modal body scrolls vertically. |
| Target context in ACL dialog | The selected target value was difficult to read because target IQN and LUN/backing information were mixed in one select label. | The select shows the target IQN. A summary box below it shows LUN, backing volume, and listener endpoints so the operator can confirm the ACL target without widening the form. |
| CHAP fields | CHAP-related fields were visible even when CHAP was not enabled, increasing form noise. | CHAP username/password fields are shown only when CHAP is enabled. Password values are passed only in the async request and are never stored or echoed in UI state. |
| Runtime ACL inventory | `targetcli ls` parsing could treat tree glyph tokens such as `o-` as ACL principals. | Runtime inventory prefers configfs ACL directories and uses a strict targetcli regex for IQN/NQN/eui/naa principals, so UI rows show real initiator IQNs only. |
| LUN effective size | Block-backed LUN size could be reported as `0` because `os.path.getsize()` was used on block devices. | SystemVM inventory uses `blockdev --getsize64` for block devices and falls back to file size for file-backed LUNs. |
| Session inventory | iSCSI sessions were detected only on TCP 3260 and did not include target/LUN/initiator context. | Session detection uses the saved desired iSCSI listener ports. When one target maps to the connected port, the session row includes target IQN, LUN, and the configured initiator IQN hint. |

Validation requirements:

- iSCSI ACL creation must accept an initiator IQN without CHAP and must keep the CHAP section hidden unless explicitly enabled.
- A connected iSCSI session must remain visible in the iSCSI tab with client, local endpoint, target IQN, LUN, and initiator IQN when the target/port mapping is unambiguous.
- Block-backed LUN size in the iSCSI target table must match the ABLESTACK volume size and the guest block device size observed by the initiator.
- The SystemVM template must include the updated `ablestack-storagectl`; existing running Storage Service VMs require recreation or manual patching before this runtime inventory behavior appears.

### iSCSI Target Group, ACL, and Session Semantics (2026-06-26)

iSCSI management is normalized around the target IQN. One target IQN can expose
multiple LUNs, and the LIO target portal group applies initiator ACLs to the
target rather than to one individual LUN row. The UI, API responses, and
SystemVM monitoring cache must therefore distinguish between target-level
state and LUN-level backing volumes.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Target rows | Each `storage_block_target` row was displayed as an independent target even when several rows shared the same target IQN. | Rows remain one DB row per LUN for API compatibility, but every iSCSI response includes `targetgroupkey`, `targetluns`, `targetluncount`, and `aclcount` so the UI can present the shared target context. |
| ACL lookup | `listStorageIscsiAcls(targetid=...)` returned only ACL rows attached to that exact LUN row. | For iSCSI, a target ID resolves to its target IQN group and returns ACL rows attached to any LUN row in the same instance with that target IQN. |
| ACL display | ACLs looked LUN-specific even though targetcli applies them to the whole TPG. | The ACL table labels the scope as target IQN level and shows the affected LUNs. |
| Runtime inventory | `targetcli ls` enrichment kept one backing path per target IQN, so multiple LUNs could inherit the same runtime path. | SystemVM inventory parses targetcli LUN lines into a per-LUN runtime list and enriches each desired LUN by IQN + LUN number. |
| Session hints | iSCSI session hinting required exactly one target row for a listener port; a multi-LUN target caused target IQN, initiator IQN, and LUN to be rendered as `-`. | If all targets on a listener port share the same IQN, sessions are attributed to that IQN. The session row shows the target IQN, initiator IQN when exactly one active ACL principal exists, and a comma-separated LUN summary such as `0,1`. |
| UI session table | Connected sessions could show only peer and endpoint. | iSCSI sessions must show client, initiator IQN, target IQN, LUN summary, endpoint, state, connection time, and disconnect action. Long IQNs use the standard ellipsis and tooltip table behavior. |

Validation requirements:

- A target with LUN `0` and `1` under the same IQN must show both LUNs in the
  target and session views.
- Adding an ACL to either LUN row of the same target IQN must display the ACL
  for that target IQN group and must apply to all mapped LUNs at runtime.
- Runtime backing path and effective size must match each LUN's ABLESTACK
  volume and guest block device, not the last LUN parsed from `targetcli`.
- The sessions cache must avoid `-` placeholders for iSCSI target IQN and
  initiator IQN whenever a target IQN group and a single ACL principal can be
  inferred.

### iSCSI Listener Port Group and Target-Scoped CHAP Correction (2026-06-26)

iSCSI listener management follows the NFSv4-only listener-group principle:
listeners are independent service protocol rows, while a target selects one or
more listener port groups. CHAP is not LUN-scoped in LIO targetcli; it is an ACL
property of the target IQN's TPG initiator entry.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Protocol persistence | `enableStorageServiceProtocol` treated non-NFS protocols as one row per protocol, so adding `3261` could overwrite or roll back the existing `3260` row. | iSCSI uses endpoint protocol rows like NFS: `instance + protocol + listenIp + port` is preserved, and `listStorageServiceProtocols` remains the UI source of truth for selectable iSCSI listener port groups. |
| Same-port secondary IP | Adding `10.10.22.x:3260` while `0.0.0.0:3260` existed could reach targetcli as a duplicate NetworkPortal. | SystemVM normalizes wildcard listeners as covering all specific IPs on the same port and skips exact/specific portal creation when wildcard already exists. |
| Additional port group | A target selecting `3261` could fail with `no iSCSI listener port group is enabled` if the protocol row was not persisted. | The backend persists `3261` as an iSCSI protocol listener row before desired-state apply, then validates target `listenerports` against the enabled row set. |
| CHAP scope | LUN `0` and LUN `1` under the same IQN could have conflicting ACL/CHAP rows. SystemVM merged the first ACL and silently dropped CHAP secrets from another LUN row. | Backend rejects conflicting CHAP settings for the same target IQN and initiator. Desired-state payload sends target-group ACLs, including transient secrets, consistently to every LUN row in the IQN group. SystemVM also rejects conflicts defensively. |
| ACL UI summary | The ACL dialog showed only the selected LUN and implied a per-LUN ACL. | The ACL dialog shows `targetluns` for the target IQN group and displays an info notice that iSCSI ACL/CHAP applies to the target IQN. |
| Session hint | Multiple targets on a listener port could leave target and LUN fields blank. | Session hints show the target IQN and LUN summary when unambiguous; when a port contains multiple targets, the cache reports the candidate target names instead of a blank placeholder. |

Validation requirements:

- Enabling `iSCSI / 10.10.22.x / 3261` must keep the original `3260` protocol
  row and expose both `3260` and `3261` as target listener-group choices.
- Enabling `iSCSI / 10.10.22.x / 3260` while `0.0.0.0:3260` exists must not
  call targetcli in a way that creates a duplicate NetworkPortal error.
- A CHAP-enabled ACL for `iqn.1994-05.com.redhat:b48878fe831c` must populate the
  targetcli ACL auth fields and require the initiator to authenticate.
- The ACL table and dialog must show the affected LUN set for the target IQN,
  while the target table keeps per-LUN backing volume information.

### iSCSI Apply Atomicity and Runtime Cleanup Correction (2026-06-26)

iSCSI desired-state application must be treated as an atomic runtime render.
The SystemVM may temporarily call multiple `targetcli` commands, but a failed
apply must not leave target skeletons, partial backstores, or stale configfs
objects that no longer match the database state.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Python dependency | The embedded iSCSI apply script used `Path(...)` inside `iscsi_portal_exists()` without importing `pathlib.Path`, causing QGA apply to fail with `NameError` before portal and LUN creation. | The embedded iSCSI Python block imports `Path` explicitly. Runtime-only imports are kept in the same block as the iSCSI apply code so template smoke tests can catch missing dependencies. |
| Partial target creation | `targetcli /iscsi create` could succeed before a later portal, backstore, LUN, or ACL step failed. This left target IQN skeletons in the SystemVM while the DB/API state showed no usable target. | The apply path tracks target IQNs and block backstores created in the current run. On any exception, it deletes those created resources before returning the error to the backend. |
| Stale runtime cleanup | If the desired payload had no active targets, previously created or partially created managed targets could remain under configfs. | Before rendering, the SystemVM compares the current payload, previous desired-state file, and existing configfs targets. Stale ABLESTACK-managed targets are removed. |
| Ownership boundary | A broad cleanup could delete administrator-created targetcli objects. | Cleanup is limited to ABLESTACK-managed IQNs matching `iqn.*.local.storage:*` and block backstores named `ablestack-*`. |
| Previous state | The state file was overwritten before applying the new desired state, making it harder to identify stale runtime objects after a failed run. | The previous sanitized desired state is loaded first and used with the new payload to determine stale target/backstore cleanup. |
| State-file scope | The iSCSI state-file variables were accidentally placed in the SMB apply block, while the iSCSI apply block wrote `state_file` and later read `previous_payload` without defining them. This caused `NameError` during initial target creation. | SMB must not reference `iscsi-targets.json`. The iSCSI apply block owns `state_file = /etc/ablestack-storage/iscsi-targets.json`, loads `previous_payload` before writing the sanitized current payload, and then uses both current and previous desired state for cleanup. |
| Reboot reconcile | Boot reconcile reused the same apply path and could replay the same runtime error after VM restart. | Boot reconcile inherits the corrected import, cleanup, and rollback behavior through `ablestack-storagectl`. |

Validation requirements:

- Creating an iSCSI-only Storage Service with no target rows must leave no
  managed target skeleton under `/sys/kernel/config/target/iscsi`.
- Creating a target must produce target, backstore, LUN, portal, ACL, and
  listener state together; none of those partial objects may remain after a
  forced apply failure.
- Re-applying an empty target payload must remove stale ABLESTACK-managed
  target skeletons and `ablestack-*` block backstores from the SystemVM.
- SystemVM template validation must include at least a minimal iSCSI apply
  smoke check so missing imports such as `Path` are detected before upload.
- The iSCSI smoke check must verify that `state_file` and `previous_payload`
  are defined inside the iSCSI embedded Python block, not in unrelated SMB
  runtime code.

## iSCSI Listener Group and CHAP Runtime Rule

### Verified Runtime Behavior

- iSCSI port changes are technically possible on the Storage Service System VM when the port is created as a Linux LIO target portal.
- A protocol endpoint by itself is only a candidate endpoint. LIO does not expose an independent global iSCSI listener that is detached from a target TPG.
- A target becomes reachable on a port only when that target TPG owns a `NetworkPortal` for the selected `listenIp:port`.
- CHAP is technically supported by the current System VM runtime, but it requires both target TPG authentication and ACL-level credentials:
  - `tpg1` must have `authentication=1` when any ACL on the target uses CHAP or mutual CHAP.
  - The initiator ACL must have `userid/password` for one-way CHAP.
  - The initiator ACL must also have `mutual_userid/mutual_password` for mutual CHAP.

### Implementation Rule

| Item | Rule |
| --- | --- |
| Protocol endpoint | Stored as an available endpoint candidate. It must not be treated as proof that the port is listening until a target uses it. |
| Target listener group | `listenerGroupPorts` is the source of truth for the target exposure ports. |
| Runtime portal | The System VM creates LIO portals from the union of listener groups selected by active LUNs that share the same target IQN. |
| Port verification | Verify only ports that are attached to at least one active target. Candidate-only ports are not required to listen. |
| CHAP request | CHAP username and secret are required on CHAP create/update requests because secrets are not persisted in the control plane or UI. |
| CHAP apply | The System VM applies ACL credentials first, then sets TPG `authentication=1` if any target ACL requires CHAP. |
| Missing CHAP credential | Runtime apply fails with an explicit missing credential error instead of silently creating a no-auth target. |

### UI Rule

- The iSCSI protocol activation dialog manages candidate endpoints.
- The iSCSI target create/update dialog selects the listener port group used by the target.
- The iSCSI ACL create/update dialog requires CHAP username and secret when CHAP is enabled, and requires mutual CHAP username and secret when mutual CHAP is enabled.
- The UI must state that CHAP secrets are sent only with the request and are not stored in the UI.

## iSCSI Runtime Session Cache and UI Accuracy Rule (2026-06-28)

The iSCSI service can be functionally healthy even when the monitoring cache
does not expose the connected initiator sessions. This is unacceptable for the
service tabs because operators must be able to see active clients and terminate
sessions from the UI. The runtime session pipeline is therefore treated as a
first-class validation path, not a best-effort display hint.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Session source | `ablestack-storagectl sessions` primarily parsed `ss -H -tn` and then inferred the protocol from listener ports. In some cases TCP `ESTAB` connections existed but the generated `sessions.json` and `session-state.json` were empty. | iSCSI session collection must keep every TCP connection whose local port is an enabled iSCSI listener port. It must never drop an iSCSI TCP session merely because target/LUN enrichment is incomplete. |
| Target mapping | A same-port multi-target or multi-LUN setup could not be mapped to one exact target, so target IQN, LUN, and initiator IQN could become blank in the UI. | The collector enriches exact fields when possible and otherwise reports `possibleTargets`, `targetGroupKey`, `targetLuns`, and `mappingStatus=candidate`. Candidate mapping is displayed as degraded information instead of no data. |
| Initiator IQN | TCP sockets do not carry the iSCSI initiator IQN. The previous fallback depended on target ACL inference only when a single ACL was obvious. | If exactly one active ACL principal applies to the inferred target group, use it as `initiatorIqn`. If several principals exist, list them in `possibleInitiators` and keep the session row visible. |
| Cache status | Empty `sessions.json` was indistinguishable from a genuinely idle service. | `sessions.json` includes `observedTcpCount`, `status`, and optional `warnings`. If iSCSI TCP sessions are observed but enrichment is partial, the status is `degraded`; if collection fails, status is `error`. |
| UI behavior | The iSCSI tab showed the generic no-data row when `sessions` was empty, even if runtime TCP sessions existed. | The iSCSI session section shows active rows when available. If the runtime response is `degraded` or `error`, it shows a dark-mode-safe warning above the table so operators can distinguish idle state from collection failure. |

Implementation constraints:

- The SystemVM monitor must continue to redact secrets and must not expose CHAP
  passwords in session, inventory, or health caches.
- The UI must continue to use the existing Storage Service runtime API; no
  browser-side direct VM probing is allowed.
- iSCSI block data validation remains non-destructive by default. The operator
  must explicitly request filesystem formatting before a raw LUN is formatted.

Exact iSCSI session attribution:

- The SystemVM collector first runs `targetcli sessions detail`.
- The collector extracts the connected initiator IQN, peer IP, mapped LUNs, and
  targetcli backstore names.
- It then resolves target IQN and LUN numbers from configfs LUN symlinks under
  `/sys/kernel/config/target/iscsi/<target>/tpgt_1/lun/lun_*`.
- If exactly one active target matches the peer and listener port, the session
  row is emitted with `mappingStatus=exact`.
- If multiple active target sessions match the same peer/listener or targetcli
  detail cannot be resolved to configfs, the row remains visible with
  `mappingStatus=candidate` or `mappingStatus=unmapped` and a runtime warning.

Validation requirements:

- With a WSL initiator logged into a non-CHAP target, the iSCSI session table
  must show client, state, connected time, target IQN or candidate target list,
  LUN or candidate LUN list, and service endpoint.
- With a WSL initiator logged into a CHAP target, the session table must still
  show the session and must not expose the CHAP secret.
- If `ss -ntp` shows established iSCSI sockets but target/LUN enrichment is not
  exact, the UI must show a degraded session warning instead of the normal
  no-session icon.

## SystemVM Template Integrity Gate (2026-06-28)

The Storage Service SystemVM image is part of the runtime contract. A template
that boots with corrupted OS package files can make iSCSI fail before the
Storage Service code runs. The template build and upload path must therefore
verify both the qcow2 image and the compressed artifact.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| KVM qcow2 export | The build produced a compressed KVM qcow2 artifact without proving that the compressed artifact round-tripped to the same qcow2. | The build validates the qcow2, compresses with `bzip2 -k`, decompresses to a temporary qcow2, compares the decompressed image with the source qcow2, and validates the decompressed image again. |
| Image structure | `qemu-img check` was not a hard gate for both the source qcow2 and the decompressed bz2 payload. | `qemu-img check` must pass for the source qcow2 and the decompressed bz2 payload. Any refcount, metadata, or data-cluster error fails the build. |
| Guest OS dependency files | The build did not inspect files required by targetcli and Python GI. | The validator mounts the root partition read-only and checks ELF/text headers for `/usr/bin/python3`, `python3-gi` `_gi.so`, `libmagic.so.1`, `/var/lib/dpkg/status`, and `targetcli`. |
| Runtime diagnostics | A corrupted Python GI library produced a raw `targetcli` traceback. | iSCSI apply performs a dependency preflight and reports a clear SystemVM runtime dependency error when targetcli, python3-gi, libmagic, or dpkg status is broken. |
| Template publication | HTTP 200 on the uploaded bz2 was treated as sufficient. | A publishable Storage Service template must pass local build validation. Operators should only register the validated bz2 URL. |

### Implementation Targets

- `tools/appliance/scripts/validate_systemvm_image.sh`
  - Attaches the qcow2 through NBD.
  - Mounts the SystemVM root partition read-only.
  - Verifies critical runtime files have valid headers.
- `tools/appliance/build.sh`
  - Runs image validation before and after bz2 round-trip.
  - Fails the build if the compressed artifact does not match the source qcow2.
- `systemvm/debian/usr/local/bin/ablestack-storagectl`
  - Runs iSCSI dependency preflight before applying target state.
  - Replaces low-level targetcli tracebacks with actionable template rebuild
    guidance when the SystemVM runtime is corrupt.

## iSCSI Target/LUN Authoritative Session Mapping (2026-06-29)

Live validation on a running Storage Service VM proved that TCP socket data is
not sufficient to identify iSCSI target IQN and LUN. The iSCSI session pipeline
therefore separates two concepts:

- `mappingStatus`: target IQN and LUN attribution.
- `endpointMappingStatus`: exactness of the local service endpoint for the
  session.

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Health listener ports | `listStorageServiceHealth` calculated iSCSI listener health from the boolean desired-state summary. This collapsed multi-port state to `3260` only. | Health calculates listener ports from the persisted `iscsi-targets.json` desired-state document. All listener ports referenced by targets and listeners are checked. |
| Session source | Session rows were generated from `ss -H -tn` and then enriched by listener-port inference. Multiple targets sharing a listener degraded target/LUN mapping. | Session rows are generated from `targetcli sessions detail` and configfs LUN symlinks. TCP sockets remain only supporting connectivity evidence. |
| Target/LUN attribution | A target with multiple portals or shared ports could show `mappingStatus=candidate`, leaving target IQN/LUN unclear in UI. | Target IQN and LUN are authoritative when targetcli reports mapped LUNs and configfs resolves each backstore to `/sys/kernel/config/target/iscsi/<target>/tpgt_1/lun/lun_*`. |
| Endpoint attribution | Endpoint and target mapping were treated as one status, so multi-portal targets made otherwise accurate sessions look degraded. | Endpoint exactness is reported separately. A multi-portal target can have `mappingStatus=exact` and `endpointMappingStatus=candidate`. This is not a service failure. |
| Cache status | Any candidate iSCSI mapping could mark the session response degraded. | Only target/LUN `mappingStatus=unmapped` degrades the session response. Endpoint candidate state is displayed in UI but does not make the service unhealthy. |

Implementation rules:

- `ablestack-storagectl sessions` must prefer `targetcli sessions detail` for
  iSCSI rows and must not duplicate those rows from raw TCP sockets.
- Each iSCSI row must include `initiatorIqn`, `targetIqn`, `lun`,
  `mappingStatus`, `endpointMappingStatus`, `listenerPorts`, and either a
  concrete `local` endpoint or `possibleEndpoints`.
- The UI must show separate columns for target/LUN mapping and endpoint mapping
  so operators can distinguish exact target visibility from multi-portal
  endpoint ambiguity.
- No CHAP or mutual CHAP secret may be emitted in health, inventory, or session
  cache output.

## NVMe-oF iSCSI-Parity Detailed Design (2026-06-29)

This section supersedes the earlier exploratory NVMe-oF notes for the next
implementation pass. The goal is to bring NVMe-oF management to the same
operator model as the current iSCSI service while preserving the existing NFS,
SMB, and iSCSI behavior.

### Runtime Proof Before Implementation

The design below is based on a live non-persistent probe on the current
Storage Service VM:

| Item | Result |
| --- | --- |
| Storage Service VM | `i-2-571-VM` on host `10.10.22.1` |
| Active service baseline | NFS and iSCSI only; no persisted NVMe-oF rows |
| Kernel target modules | `nvmet` and `nvmet-tcp` load successfully |
| configfs root | `/sys/kernel/config/nvmet` appears after module load |
| Temporary subsystem | `nqn.2026-06.local.storage:codex-nvmeof-probe` |
| Temporary namespace | 64 MiB loop-backed namespace, NSID `1` |
| Listener | TCP `0.0.0.0:4420` |
| WSL client result | `nvme discover`, `nvme connect`, block size check, direct write, and direct read all passed |
| Host NQN ACL result | Wrong Host NQN was rejected; the WSL Host NQN was accepted and read/write passed |
| DH-HMAC-CHAP capability | Not supported by the current SystemVM kernel/configfs surface because host directories expose no `dhchap_key` or `dhchap_ctrl_key` attributes |

Implications:

- `KERNEL_NVMET` is technically usable in the current SystemVM when modules are
  loaded before capability detection.
- Host NQN ACL is a supported access-control primitive.
- DH-HMAC-CHAP must remain capability-gated and disabled on the current
  template baseline. If requested through API, the engine must fail before
  reporting the ACL as `Ready`.
- The temporary probe created no persistent DB state and was cleaned up after
  validation.

### API Design

NVMe-oF should expose the same management shape as iSCSI:

| Area | API Direction |
| --- | --- |
| Protocol endpoint | Reuse `enableStorageServiceProtocol`, `disableStorageServiceProtocol`, and `updateStorageServiceProtocol` with protocol `NVME_OF`, transport `tcp`, and default port `4420`. Multiple listener ports may be stored, but the first implementation should validate the kernel target path before enabling non-default ports. |
| Subsystem | Keep `createStorageNvmeOfSubsystem`, `updateStorageNvmeOfSubsystem`, `deleteStorageNvmeOfSubsystem`, and `listStorageNvmeOfSubsystems`. The subsystem owns the subsystem NQN, transport, engine, listener port group, and `allowAnyHost` policy. |
| Namespace | Extend namespace creation to the iSCSI-style backing-volume model: current attached block volume, existing unattached volume, or newly created volume. Add update support for mutable fields that are safe after runtime apply. Namespace create/update must return effective namespace size, backing volume identity, and runtime block device path. |
| Host ACL | Keep create/update/delete/list Host ACL APIs. Host NQN is required unless `allowAnyHost` is explicitly enabled on the subsystem. DH-HMAC-CHAP fields are accepted only when SystemVM health reports support. Secrets remain request-only and are never persisted or returned. |
| Sessions | Reuse `listStorageServiceSessions` and `disconnectStorageServiceSession`. Session rows must include host/client, subsystem NQN, namespace IDs when attributable, endpoint, connected time, last seen time, and mapping status. |

API response objects must include:

- subsystem NQN and UUID
- namespace ID and UUID
- backing volume UUID, name, size, disk offering, primary storage, and file
  system field when relevant to volume inspection
- effective namespace size in bytes
- listener port group
- access mode: `allowAnyHost`, Host NQN ACL, or unsupported authenticated mode
- runtime state and error message

### Backend And Engine Design

The server-side lifecycle must mirror the iSCSI block-only path.

| Area | Rule |
| --- | --- |
| Backing volume | NVMe-oF namespaces are block-only. A namespace must resolve to an attached data block device or explicitly created/attached ABLESTACK data volume. Mounted file-share volumes must not be exposed directly as raw namespaces. |
| New volume flow | UI creates the volume with disk offering and primary storage, waits for async completion, then calls the namespace API. The backend re-checks attachable state before attaching the volume to the Storage Service VM. |
| Existing volume flow | Existing unattached volumes are attached to the Storage Service VM, inspected, and used as whole raw namespace devices. The engine must not format, mount, or create a file inside the volume. |
| Current attached volume flow | Only an unused attached block volume can be selected. Root, boot, swap, ISO, mounted file-share volumes, and volumes already used by NFS, SMB, iSCSI, or another NVMe-oF namespace are rejected. |
| Device identity | Device matching must use ABLESTACK volume UUID/name/serial/size hints and reject ambiguous candidates. Selecting an arbitrary unmounted device is not allowed. |
| Desired-state apply | The DB row remains transitional until QGA apply succeeds. On failure, namespace/subsystem/ACL state becomes `Error` and the error is returned to the async job. |
| Rollback | A newly created volume for a failed namespace create may be detached and expunged by compensation. Operator-selected existing volumes must never be deleted automatically. |
| Reconcile | Boot reconcile reapplies persisted NVMe-oF subsystem, namespace, port, and Host ACL state. It must load `nvmet` and `nvmet-tcp` before evaluating capability. |

### Database Design

The existing Storage Service tables are sufficient for the first parity pass.
No schema migration is required unless later reporting requires a normalized
listener-group table.

| Table | Usage |
| --- | --- |
| `storage_service_protocol` | Store protocol `NVME_OF`, enabled state, listen IP, port, and protocol-level JSON such as transport and listener capability. |
| `storage_block_target` | Store subsystem rows and namespace rows for protocol `NVME_OF`. The `lun_or_namespace` field stores namespace ID for namespace rows. `config_json` stores `type=subsystem` or `type=namespace`. |
| `storage_access_rule` | Store Host NQN ACL rows and non-secret auth metadata. DH-HMAC-CHAP secrets are never stored. |

Recommended `config_json` keys:

```json
{
  "type": "namespace",
  "backstoreType": "BLOCK",
  "volumeMode": "CURRENT_VOLUME|EXISTING_VOLUME|NEW_VOLUME",
  "volumeUuid": "...",
  "volumeName": "...",
  "namespaceSizeBytes": 53687091200,
  "effectiveSizeBytes": 53687091200,
  "runtimeDevicePath": "/dev/...",
  "listenerGroupPorts": [4420]
}
```

```json
{
  "type": "subsystem",
  "engine": "KERNEL_NVMET",
  "transport": "tcp",
  "allowAnyHost": false,
  "listenerGroupPorts": [4420],
  "dhChapCapability": "UNSUPPORTED"
}
```

### SystemVM Runtime Design

`/usr/local/bin/ablestack-storagectl` must own the NVMe-oF runtime path.

| Function | Required Behavior |
| --- | --- |
| `nvmeof_capabilities` | Run `modprobe nvmet` and `modprobe nvmet-tcp` before checking `/sys/kernel/config/nvmet`. Report `kernelTargetSupported`, `tcpSupported`, `configfsRoot`, `dhChapSupported`, and `dhChapCtrlSupported`. |
| `apply_nvmeof_subsystems` | Render configfs subsystem, namespace, port, and Host ACL state idempotently. Write configfs port attributes only before links become active. |
| Namespace apply | Resolve the selected ABLESTACK volume to a safe raw block device and write that path to `device_path`. File-backed loop namespaces are not used for the main parity implementation. |
| Host ACL apply | If `allowAnyHost=false`, write `attr_allow_any_host=0` before creating `allowed_hosts` symlinks. Wrong Host NQNs must be rejected by the kernel path. |
| Auth apply | If DH-HMAC-CHAP is requested and configfs host attributes are absent, fail explicitly with an unsupported capability error. |
| Monitor cache | Inventory and session cache must include subsystem NQN, namespace ID, endpoint, backing volume, Host NQN ACL state, and DH-HMAC-CHAP capability. |
| Boot reconcile | Reapply NVMe-oF desired state after boot and refresh monitor cache. |

### UI Design

The NVMe-oF tab and dialogs must follow the current iSCSI service standard.

| UI Area | Required Shape |
| --- | --- |
| Service tab | Full-width capable tab, dark-mode-safe cards and tables, right-aligned row action buttons, compact scrollbars, fixed action column when horizontal scroll is needed. |
| Status summary | Endpoint, engine, monitoring cache, DH-HMAC-CHAP support state, last refresh, and subsystem/namespace count. |
| Subsystem table | Subsystem NQN, engine, transport, listener port group, access policy, state, and row actions. |
| Namespace table | Namespace ID, subsystem NQN, backing volume, size, endpoint exposure, state, and row actions. |
| Host ACL table | Host NQN, subsystem NQN, auth mode, DH-HMAC-CHAP support/usage, state, and row actions. |
| Backing volume table | Same columns and behavior as iSCSI: volume name, UUID, size, usage, disk offering, primary storage, connected namespace, state, and actions. |
| Session table | Client/Host NQN, subsystem NQN, namespace, endpoint, state, connected time, and disconnect action. |
| Modals | All NVMe-oF action modals use the vertical, viewport-bounded, dark-mode-safe modal standard used by iSCSI/NFS/SMB. No horizontal scrollbar should appear in ordinary desktop width. |
| Backing picker | Current attached volume, existing unattached volume, and new volume options match iSCSI behavior. New volume uses disk offering then tag-filtered primary storage. |
| DH-HMAC-CHAP | Controls are disabled with a clear unsupported message when SystemVM capability reports missing `dhchap_key` or `dhchap_ctrl_key`. |
| SPDK | SPDK remains visible only as planned or disabled capability. VM-level HugePage, NUMA, CPU pinning, memlock, SR-IOV, and PCI passthrough controls are not part of Storage Service in this phase. |

### Validation Gate For Implementation

NVMe-oF parity implementation is not complete until these UI-led tests pass:

1. Create a Storage Service with NVMe-oF only, using a new backing volume.
2. Create a subsystem and namespace with Host NQN ACL.
3. Discover and connect from WSL or a prepared client with the allowed Host NQN.
4. Verify raw block size and direct read/write.
5. Verify a wrong Host NQN is rejected.
6. Verify DH-HMAC-CHAP controls are disabled on the current template and an API
   request that forces CHAP fails explicitly.
7. Reboot the Storage Service VM and confirm reconcile restores subsystem,
   namespace, Host ACL, listener, monitor cache, and session visibility.
8. Confirm UI tables and modals match iSCSI dark-mode and vertical-dialog
   standards.

## NVMe-oF iSCSI-Parity Implementation Scope (2026-06-30)

This implementation pass promotes NVMe-oF from a subsystem-only prototype to
the same operational shape used by iSCSI, while preserving the current kernel
NVMET capability boundary.

| Layer | Implementation Target |
| --- | --- |
| API | `createStorageNvmeOfNamespace` accepts listener port groups and cleanup-on-failure; `updateStorageNvmeOfNamespace` is added for namespace edit workflows. |
| Backend | NVMe-oF namespace create/update/delete validates dedicated backing volumes, verifies requested listener port groups exist, prepares/attaches backing volumes, renders desired state, and rolls back failed namespace creation. |
| Response model | Block target responses expose listener port groups and effective endpoint strings for NVMe-oF namespaces the same way iSCSI targets do. |
| UI | NVMe-oF tab shows subsystem, namespace, Host ACL, backing volume, and session sections. Namespace and Host ACL actions use viewport-bounded vertical modals with the existing dark-mode storage-service style. |
| SystemVM | `ablestack-storagectl` applies kernel NVMET subsystems, raw block namespaces, Host NQN ACLs, and multiple listener port groups from desired state. Mounted file-system paths are rejected for namespaces. |
| Monitor cache | Inventory and health derive NVMe-oF listening state from desired listeners and namespace listener groups instead of hard-coding TCP 4420. |
| Unsupported auth | DH-HMAC-CHAP remains capability-gated. If configfs does not expose `dhchap_key` or `dhchap_ctrl_key`, UI controls stay disabled and forced requests fail explicitly. |

### Code-Level Contracts

| Object | Contract |
| --- | --- |
| `CreateStorageNvmeOfNamespaceCmd` | Must provide `subsystemid`, `namespaceid`, `volumeid`, optional `namespacesizebytes`, optional `listenerports`, and optional `cleanupvolumeonfailure`. |
| `UpdateStorageNvmeOfNamespaceCmd` | Must update namespace ID, backing volume, backing path, namespace size, and listener port groups without changing the parent subsystem. |
| `StorageServiceManagerImpl` | Must use the same volume safety rules as iSCSI: a backing volume can belong to only one block target, raw block namespaces cannot use mounted file-system paths, and failed new-volume namespace creation must clean up its DB row and optionally its volume. |
| `ablestack-storagectl apply-nvmeof` | Must load `nvmet` and `nvmet-tcp`, create configfs ports before linking subsystems, disable namespaces before changing `device_path`, and link each subsystem only to requested listener port groups. |
| `SharedFSTab.vue` | Must not show raw i18n keys in Korean UI. All NVMe-oF action controls must use the same dark-mode-safe vertical modal pattern as iSCSI. |

## NVMe-oF Boot Reconcile Contract (2026-06-30)

NVMe-oF desired state is persisted in the Storage Service SystemVM at
`/etc/ablestack-storage/nvmeof-subsystems.json`. The runtime state is kernel
configfs state under `/sys/kernel/config/nvmet`, so it is lost when the
SystemVM reboots. The boot reconcile service must therefore treat NVMe-oF the
same way as NFS, SMB, and iSCSI: saved desired state is authoritative and must
be reapplied before the monitor cache reports the service as healthy.

The reconcile flow is:

1. Mount configfs when `/sys/kernel/config` is not mounted.
2. Load `nvmet` and `nvmet-tcp` before applying desired state.
3. If `/etc/ablestack-storage/nvmeof-subsystems.json` exists and is non-empty,
   run:

   ```bash
   /usr/local/bin/ablestack-storagectl nvmeof subsystem apply /etc/ablestack-storage/nvmeof-subsystems.json
   ```

4. Refresh the monitor cache after a successful apply.
5. Mark reconcile failure if the apply command fails, because a stored
   NVMe-oF subsystem without a live listener is a degraded Storage Service.

Post-boot health evidence must include:

- a listening NVMe-oF TCP endpoint for every configured listener port group;
- existing configfs subsystem and namespace entries;
- `ablestack-storage-monitor --once` reporting `health_status=ok`;
- a client-side `nvme discover`, `nvme connect`, block read/write, and
  disconnect test for the exposed namespace.

## NVMe-oF Listener Persistence Contract (2026-07-01)

Runtime validation on Storage Service VM `i-2-573-VM` confirmed that kernel
NVMET can expose one subsystem through multiple TCP listeners at the same time
when the desired-state payload preserves every listener row.

The implementation contract is:

| Area | Required Behavior |
| --- | --- |
| Protocol persistence | `NVME_OF` is an endpoint protocol. `enableStorageServiceProtocol` must create or update rows by `instance_id + protocol + listen_ip + port`, not by `instance_id + protocol` only. |
| Desired-state rendering | NVMe-oF apply payloads must include all enabled `NVME_OF` listener rows for the instance. |
| Namespace exposure | A namespace may keep `listenerGroupPorts` metadata for UI/API compatibility, but kernel NVMET links listeners at subsystem scope. The SystemVM links each active subsystem to every real listener endpoint. |
| Runtime apply | `ablestack-storagectl nvmeof subsystem apply` must keep existing listener ports when a new listener is added and must not collapse the service to the latest port only. |
| Boot reconcile | `/etc/ablestack-storage/nvmeof-subsystems.json` remains authoritative and must restore all listener ports after reboot. |
| UI/API | Protocol activation must show every persisted listener and namespace dialogs must offer every valid listener port group. |

The validated hot-patch state used listeners `10.10.22.202:4420` and
`10.10.22.201:4421`. After reboot, both listeners were restored and WSL
`nvme discover`, `nvme connect`, direct write/read, and disconnect passed
against both endpoints.

## NVMe-oF Wildcard Listener Conflict Contract (2026-07-01)

Runtime validation on Storage Service VM `i-2-575-VM` confirmed that kernel
NVMET treats a wildcard listener (`0.0.0.0:<port>`) as covering every IPv4
address on that port. Creating a second configfs port for a specific service IP
with the same port can leave stale configfs port directories and fail with
`Address already in use` when the subsystem is linked again.

The implementation contract is:

| Area | Required Behavior |
| --- | --- |
| API/backend validation | For `NVME_OF`, reject `specific-ip:<port>` when `0.0.0.0:<port>` already exists, and reject `0.0.0.0:<port>` when any specific listener already exists on that port. Exact endpoint activation remains idempotent. |
| Desired-state rendering | If legacy DB state already contains both wildcard and specific listeners for the same port, render only the wildcard listener to the SystemVM. |
| SystemVM apply | Normalize desired listeners before touching configfs. A wildcard listener absorbs specific listeners on the same port. Existing configfs ports are reused by `(listenIp, port)` and active subsystems are linked to every real listener endpoint. |
| Stale configfs cleanup | Remove stale `/sys/kernel/config/nvmet/ports/<id>` directories after unlinking subsystems so a failed apply cannot poison the next apply. |
| Monitor cache | Health must be based on namespace-exposed listener ports, not every registered protocol endpoint. Registered but unexposed endpoints must not mark NVMe-oF degraded. |
| UI | Protocol activation warns and blocks wildcard/specific same-port conflicts before the API call. Namespace endpoint summaries display endpoints from the normalized listener set. |

This rule does not remove the previously validated multi-listener capability:
different ports can still coexist, and specific listeners on the same port can
coexist only when no wildcard listener exists for that port.

### NVMe-oF wildcard listener endpoint alias rule

For NVMe-oF kernel NVMET, a `0.0.0.0:<port>` listener already accepts traffic for every IP assigned to the Storage Service VM on that port. When the operator adds a specific listen IP on the same port, the backend must not create a duplicate configfs listener. Instead, it must persist the requested endpoint as an endpoint alias and ensure the IP is assigned to the SystemVM NIC.

Implementation rules:

- `listeners` in the NVMe-oF desired-state payload represent real configfs listeners only.
- `endpointAliases` represent specific service IPs covered by a wildcard listener on the same port.
- The backend allows `NVME_OF` specific-IP additions when an existing wildcard listener on the same port exists, while keeping the existing iSCSI conflict behavior unchanged.
- The SystemVM applies `endpointAliases` through `storagectl network endpoints apply` before rendering NVMe-oF subsystems.
- Boot reconciliation restores endpoint aliases through `/etc/ablestack-storage/network-endpoints.json` before applying NVMe-oF desired state.
- UI must show this case as an informational reuse message, not as a blocking validation error.

### NVMe-oF multi-port runtime reconcile rule

Kernel NVMET exposes listeners by linking a subsystem into configfs ports.
The listener is therefore a subsystem-level runtime property, not an individual
namespace property. Validation on a running Storage Service VM confirmed that
adding configfs ports for `10.10.22.201:4421` and `10.10.22.203:4422`, then
linking the existing subsystem to those ports, makes `nvme discover`,
`nvme connect`, and direct write/read tests succeed without changing the
namespace backing volume.

- Every enabled real NVMe-oF listener row must become a configfs port.
- Existing configfs ports must be reused by tuple `(listenIp, port)` and must
  not have their address attributes rewritten while active.
- Endpoint aliases covered by a wildcard listener are applied as NIC/IP state
  only and do not create duplicate configfs ports.
- A kernel NVMET port links to a subsystem, not directly to an individual
  namespace. Therefore all namespaces in the same subsystem must use one
  identical listener port group. If a different listener group is required, the
  operator must create another subsystem.
- The backend rejects namespace create/update requests that would mix listener
  port groups inside one subsystem.
- SystemVM reconcile computes the effective listener port group per subsystem
  from its namespaces, links that subsystem only to matching real listener
  ports, and removes stale subsystem symlinks from no-longer-selected ports.
- Endpoint aliases covered by wildcard listeners remain NIC/IP state only and do
  not create duplicate configfs ports.
- UI and API must show Namespace listener ports/endpoints from Namespace
  metadata, while subsystem rows summarize the listener group inherited from
  their namespaces.

### NVMe-oF endpoint IP pre-activation rule

Runtime validation on Storage Service VM `i-2-579-VM` confirmed that an added
NVMe-oF listener such as `10.10.22.201:4421` fails with `Network is unreachable`
when the SystemVM guest has not activated the requested service IP before the
kernel NVMET listener is probed.

| Area | Required Behavior |
| --- | --- |
| Backend ordering | `enableStorageServiceProtocol` registers the listen IP, applies the guest network endpoint, and only then applies NVMe-oF desired state. |
| Payload metadata | NVMe-oF `listeners` and `endpointAliases` include `nicId`, `networkId`, `primaryIp` when available, and `prefixlen`/`networkCidr`. |
| SystemVM network apply | `ablestack-storagectl network endpoint apply` processes `listeners`, `endpoints`, and `endpointAliases`, skips wildcard listeners, and activates every specific listen IP. |
| No-IP guest fallback | If the guest has no global IPv4 address but exactly one non-loopback Ethernet interface, the SystemVM may bring that interface up and add the requested listen IP using the backend-provided prefix. |
| NVMe-oF apply | `nvmeof subsystem apply` calls `network endpoint apply` before touching configfs ports or probing listener readiness. |
| Boot reconcile | Boot reconcile restores saved network endpoints before reapplying NVMe-oF desired state. |

The production implementation must use backend-provided prefix/CIDR metadata;
hardcoded prefixes are allowed only in manual validation scripts.

### NVMe-oF UI endpoint presentation rule

The NVMe-oF runtime source of truth is split across protocol registration,
health, and subsystem/namespace records. The UI must not treat the endpoint
string in `listStorageNvmeOfSubsystems` as the complete listener inventory.

| UI Area | Required Presentation Source |
| --- | --- |
| Status summary endpoint | Build from `listStorageServiceProtocols(protocol=NVME_OF)` and expand wildcard listeners through the current service IP list. |
| Listener/port group table | Show every registered NVMe-oF protocol listener, including wildcard listeners and specific-IP endpoint aliases covered by wildcard listeners. |
| Listener health | Use `listStorageServiceHealth` NVMe-oF port status when available; otherwise show registered listeners as Ready rather than hiding them. |
| Subsystem table | Show listener port groups separately from effective accessible endpoints. |
| Namespace table | Show namespace listener ports separately from effective accessible endpoints so namespace metadata is not confused with the service-wide listener inventory. |

This is a UI-only contract. It must not change backend desired-state rendering,
SystemVM configfs reconciliation, DB schema, or API payload semantics.

### NVMe-oF subsystem modal host access layout rule

The NVMe-oF subsystem create dialog must keep subsystem identity fields and host
access policy visually separate. `allowAnyHost` is an access policy, not a
property of the engine selector.

| UI Area | Required Layout |
| --- | --- |
| Subsystem settings | Keep subsystem NQN and engine as the first vertical input group. |
| Host access policy | Render `allowAnyHost` inside a separate boxed policy block below the engine field. |
| Spacing | Keep a clear vertical gap between the engine field and the host access policy block so the switch does not appear attached to the previous input. |
| Theme | Use the same low-contrast bordered section style as other Storage Service vertical modals, including dark mode. |

This is a UI-only rule. It does not change API parameters, backend validation,
DB schema, SystemVM desired state, or NVMe-oF runtime behavior.

### NVMe-oF canonical subsystem NQN rule

Kernel NVMET has one runtime object per subsystem NQN. The Storage Service DB
must therefore treat an NVMe-oF subsystem NQN as an instance-scoped identity,
even when older UI/API flows or retry paths have already produced duplicate
`BLOCK_TARGET` rows for the same NQN.

| Area | Required Behavior |
| --- | --- |
| API create | `createStorageNvmeOfSubsystem` must return the existing active subsystem when the same NQN already exists in the same Storage Service instance. It must not create a second subsystem row for an already-defined NQN. |
| API list | `listStorageNvmeOfSubsystems` must expose only one subsystem row per NQN to the UI. Duplicate legacy rows must not create duplicate subsystem cards or tables. |
| Desired-state rendering | `applyNvmeOfDesiredState` must build one canonical subsystem payload per NQN and merge duplicate rows into it. |
| Namespace merge | Namespaces are merged by namespace ID under the canonical NQN. Duplicate namespace payloads with the same ID are ignored after the first active entry. |
| Host ACL merge | Host ACLs attached to any duplicate subsystem row are merged into the canonical subsystem. If the same host NQN appears more than once, active rows and rows carrying DH-HMAC-CHAP secrets take precedence. |
| SystemVM apply | `ablestack-storagectl` repeats the same canonicalization before touching configfs so stale DB rows or partially failed retries cannot remove an existing allowed host symlink. |

This rule is especially important for `allowAnyHost=false`: a namespace may be
valid and exposed, but `nvme discover`/`connect` will still fail unless the
allowed host NQN is linked under the single runtime subsystem object.

### NVMe-oF subsystem and namespace API separation rule

NVMe-oF subsystem rows and namespace rows share the same block-target table, but
they are different runtime objects and must not be returned through one UI-facing
collection. A mixed response can make the UI select a namespace ID as if it were
a subsystem ID and then falsely report that a host ACL is missing.

| Area | Required Behavior |
| --- | --- |
| `listStorageNvmeOfSubsystems` | Return only subsystem objects. The response item key is `storagenvmeofsubsystem`. |
| `listStorageNvmeOfNamespaces` | Return only namespace objects. The response item key is `storagenvmeofnamespace`. |
| Host ACL lookup | Resolve the requested subsystem to the canonical subsystem NQN before listing or creating host ACLs. |
| Initial setup verification | Verify subsystem existence through subsystem ID/NQN only, then query host ACLs with the resolved subsystem ID. |
| UI inventory | Use namespace APIs for namespace/backing-volume tables and subsystem APIs for subsystem/ACL tables. |

This rule does not change the DB schema or SystemVM runtime model. It prevents
API/UI ambiguity around the shared block-target table.

### NVMe-oF namespace refresh and display rule

Namespace create, update, and delete actions must refresh both
`listStorageNvmeOfSubsystems` and `listStorageNvmeOfNamespaces`. The namespace
API is the source of truth for namespace rows, listener port groups, backing
volumes, and endpoint presentation. Subsystem rows may summarize namespace
listener groups, but must not replace namespace rows or display service-wide
endpoint summaries as if they were namespace-specific exposure.

### NVMe-oF listener runtime-state separation rule

NVMe-oF protocol listeners and namespace exposure are separate runtime concepts.
A registered protocol endpoint means that the service can use an IP/port group,
but the kernel NVMET listener becomes meaningful only when at least one active
namespace exposes its subsystem through that port group.

| Item | Rule |
| --- | --- |
| Protocol row | Represents configured listener metadata. It can be `Ready` even when no namespace uses its port group yet. |
| Namespace row | Owns the listener port group that exposes the subsystem. Namespace listener ports are the source of truth for configfs subsystem links. |
| SystemVM monitor | Reports each NVMe-oF port as `LISTENING`, `UNUSED`, or `ERROR`. `UNUSED` is not degraded; `ERROR` is degraded. |
| UI status summary | Separates configured endpoints from namespace-exposed endpoints and displays unused listeners explicitly. |
| Namespace modal | Shows listener port groups with runtime status and must not leak raw i18n keys. |
| Reboot reconcile | Restores configured listeners and links only namespace-selected port groups; unused listeners remain configured metadata without forcing a failed listen probe. |

This prevents a listener such as `10.10.22.203:4422` from being shown as a
failed service when it has only been registered and no namespace has selected
the `4422` port group yet. When a namespace later selects that port group, the
SystemVM apply step must link the subsystem to the configfs port and the monitor
must transition the port from `UNUSED` to `LISTENING`.

### NVMe-oF host ACL and allow-any-host exclusivity rule

Kernel NVMET treats `attr_allow_any_host=1` and explicit
`allowed_hosts/<host-nqn>` links as mutually exclusive access models for a
subsystem. On the 2026-07-09 validation service, linking a host NQN under a
subsystem whose `allowAnyHost` policy was enabled failed with kernel configfs
`EINVAL`. Storage Service must therefore prevent this invalid combination before
it reaches the SystemVM runtime.

| Component | As-is | To-be |
| --- | --- | --- |
| UI access table | Shows inherited all-host policy and explicit ACL rows in the same table, but only exposes a create action. Operators can try to add a Host ACL to an all-host subsystem and get a late runtime failure. | Show access policy rows separately from explicit Host ACL rows. Rows derived from `allowAnyHost=true` are read-only policy rows with no edit/delete Host ACL action. Explicit rows have right-aligned edit/delete actions. |
| UI Host ACL create modal | Lists every subsystem as a selectable ACL target. | Filter or disable `allowAnyHost=true` subsystems and show a clear message: all-host subsystems already allow every host; disable all-host policy first to use explicit ACLs. |
| UI Host ACL edit/delete | Backend APIs exist, but the tab has no edit/delete actions. | Add edit/delete actions for explicit Host ACL rows. Edit reuses the create modal with current Host NQN and DH-HMAC-CHAP flags populated. Delete uses a destructive confirmation and refreshes subsystem, ACL, and inventory data without switching tabs. |
| API create | Persists a `storage_access_rule` row first, then the SystemVM may fail with `EINVAL`, leaving an `Error` row. | Resolve the canonical subsystem, parse `config_json.allowAnyHost`, and reject create before persisting if it is true. No DB row is created for this policy conflict. |
| API update | Allows changing an ACL row without rechecking the current subsystem policy. Failed apply leaves the row in `Error`. | Re-read the canonical subsystem before update. If `allowAnyHost=true`, reject the update. If the Host NQN changes, check duplicate Host NQNs under the canonical subsystem before persisting. Preserve the previous row on apply failure where possible. |
| API delete | Deletes explicit ACL rows and reapplies desired state. | Keep this behavior for explicit rows only. The API remains ID-based; policy rows are UI-only and never call delete. |
| Desired-state builder | Can include hosts even when the subsystem payload has `allowAnyHost=true`. | When building the canonical subsystem payload, treat `allowAnyHost=true` as authoritative and omit explicit hosts from that runtime payload. Persisted invalid legacy ACL rows should be surfaced as invalid/error metadata rather than rendered into configfs. |
| SystemVM `ablestack-storagectl` | Writes `attr_allow_any_host`, then blindly creates `allowed_hosts` symlinks for every host row. | If `allowAnyHost=true`, write `attr_allow_any_host=1`, remove stale managed `allowed_hosts` symlinks for that subsystem, skip host linking, and report inherited all-host policy in monitor inventory. If `allowAnyHost=false`, write `0` before linking explicit hosts. |
| Monitor/inventory | Does not distinguish inherited all-host policy from explicit Host ACL rows clearly enough for action routing. | Report subsystem access mode (`ALLOW_ANY_HOST` or `EXPLICIT_HOST_ACL`), explicit ACL count, and inherited policy status. Do not synthesize inherited policy as an editable ACL resource ID. |

Code-level implementation targets:

| File | Required change |
| --- | --- |
| `ui/src/views/storage/SharedFSTab.vue` | Add `actions` handling to `nvmeAclColumns`; implement `editNvmeHostAcl`, `updateNvmeHostAcl`, and `deleteNvmeHostAcl`; keep policy rows actionless; disable or annotate `allowAnyHost` subsystems in the Host ACL modal; refresh only NVMe-oF tab data after action completion. |
| `ui/public/locales/ko_KR.json`, `ui/public/locales/en.json` | Add labels and messages for inherited all-host policy, explicit Host ACL edit/delete, the policy-conflict warning, and destructive delete confirmation. Ensure no raw i18n keys are visible in the NVMe-oF tables or modals. |
| `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | Add a small helper such as `ensureNvmeHostAclAllowed(StorageBlockTargetVO subsystem)` and call it from create/update. Add duplicate Host NQN validation on update when the principal changes. Build NVMe-oF desired state without rendering explicit hosts for all-host subsystems. |
| `api/src/main/java/org/apache/cloudstack/api/command/user/storage/dataservice/*StorageNvmeOfHostAclCmd.java` | No new command is required. Existing create/update/delete/list APIs remain the contract; only validation and UI exposure change. |
| `systemvm/debian/usr/local/bin/ablestack-storagectl` | Guard `apply_nvmeof_subsystems` so all-host subsystems never attempt `allowed_hosts` symlink creation. Make the policy conflict error deterministic if an invalid payload still reaches the VM. |
| `systemvm/debian/usr/local/bin/ablestack-storage-boot-reconcile` | No separate business logic change should be needed if it invokes the corrected `storagectl` desired-state apply. Retest reboot recovery after the `storagectl` change. |

Migration and cleanup rule:

- Existing invalid `storage_access_rule` rows created by this defect should not be
  silently converted to active ACLs. They should either remain visible as failed
  explicit rows until an operator deletes them, or be cleaned by an explicit
  maintenance action after confirming the subsystem is still `allowAnyHost=true`.
- The implementation does not require a DB schema change.

### NVMe-oF row action and safety rule

The NVMe-oF tab must follow the same operational pattern as the completed NFS,
SMB, and iSCSI tabs: every mutable row has an action column, action buttons are
right aligned and fixed to the right edge, and destructive operations are only
enabled when they can be executed without orphaning runtime state or data.

This rule is a UI/API guardrail design. It does not change the NVMe-oF configfs
runtime model unless a requested action already maps to an existing desired-state
operation.

| Area | As-is | To-be |
| --- | --- | --- |
| Listener table | Shows listen IP, port, listener type, and effective endpoints, but has no row actions. Operators cannot remove a wrong listener from the NVMe-oF tab. | Add a fixed right action column. Editing a listener is not offered because listener identity is `protocol + listenIp + port`; changing it is modeled as delete and create. Delete is enabled only when no namespace uses the same listener port group and no active session is on the listener endpoint. |
| Listener delete API | `deleteStorageServiceProtocol` accepts `listenip`, but endpoint removal is explicitly limited to NFS. It also lacks a `port` parameter, so it cannot safely distinguish multiple listeners on the same IP. | Extend the API with an optional `port` parameter and add NVMe-oF endpoint deletion support. The backend removes only the exact `listenIp + port` protocol row, refuses primary service IP deletion, refuses rows still referenced by namespaces, then reapplies NVMe-oF desired state. |
| Listener wildcard rows | A wildcard listener row such as `0.0.0.0:4420` can represent a port group covering all service IPs. | Treat wildcard rows as service-wide port groups. They are read-only in the row action column unless no namespace references the port and another listener remains available. The UI explains that a wildcard row is removed by removing the corresponding port group. |
| Subsystem table | Backend update/delete APIs exist, but the UI table has no row actions. | Add edit and delete actions. Edit opens the existing subsystem form with current NQN, engine, and host policy populated. Engine and transport are read-only after creation. Delete is disabled while namespaces, explicit host ACL rows, or active sessions exist. Backend cascade delete remains protected by the UI and should also reject unsafe deletes server-side. |
| Namespace table | Edit and delete actions exist. | Keep actions, but apply the same action-cell styling as other storage tables. Edit remains limited to mutable fields such as listener port group/size metadata; subsystem identity and backing volume remain immutable after creation. Delete remains destructive and never deletes the backing volume. |
| Backing volume table | Shows volumes but has no actions. In older UI wiring, the volume expansion action reused the file-share resize modal/API and could show a target/namespace UUID in a `file share` field. | Add fixed right actions for backing-volume expansion and backing-volume detach. Expansion opens the dedicated `resizeBackingVolume` modal and calls `resizeStorageServiceBackingVolume`; it never asks for a file share. Detach is disabled when any namespace uses the volume or when active sessions exist for namespaces on that volume. The tab never deletes the volume; volume deletion stays in the volume workflow. |
| Host ACL table | Explicit ACL edit/delete actions exist, while inherited all-host policy rows are actionless. | Keep inherited policy rows read-only. Keep explicit ACL edit/delete actions visible and right aligned. If a subsystem is `allowAnyHost=true`, explicit ACL creation/edit is blocked and stale explicit rows can only be deleted after confirmation. |
| Session table | Session termination exists. | Keep session termination as the only session action. Session rows gate other destructive actions when they reference the same subsystem, namespace, volume, listener IP, or port. |

Code-level implementation targets:

| File | Required change |
| --- | --- |
| `ui/src/views/storage/SharedFSTab.vue` | Add `actions` columns and body-cell handlers for `nvmeListenerColumns`, `nvmeSubsystemColumns`, and `nvmeVolumeColumns`; keep existing namespace and Host ACL actions but normalize them to `storage-table-actions`. Add helpers for `canDeleteNvmeListener`, `canEditNvmeSubsystem`, `canDeleteNvmeSubsystem`, `canDetachNvmeVolume`, and `nvmeActiveSessionRefs`. Add modal wiring for `editNvmeSubsystem`, `resizeBackingVolume`, `detachBackingVolume`, and listener deletion. `resizeBackingVolume` must use the dedicated backing-volume resize form/API, not `resizeStorageFileShare`. |
| `ui/public/locales/ko_KR.json`, `ui/public/locales/en.json` | Add labels/tooltips for listener delete, subsystem edit/delete, volume expand/detach, disabled reasons, wildcard listener protection, namespace reference protection, active session protection, and destructive confirmation messages. No raw i18n keys may be visible in the NVMe-oF tables or modals. |
| `api/src/main/java/org/apache/cloudstack/api/command/user/storage/dataservice/DeleteStorageServiceProtocolCmd.java` | Add optional `port` parameter. Keep backward compatibility: if `port` is omitted, existing NFS behavior is preserved; NVMe-oF deletion requires `port` to avoid ambiguous endpoint deletion. |
| `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | Extend `deleteStorageServiceEndpoint` or add a protocol-specific helper for NVMe-oF. Validate exact listener row existence, primary IP protection, namespace listener-port references, and active session safety before removing the protocol row. After removal, call `applyNvmeOfDesiredState(instance)` and rollback the DB row if runtime apply fails. Add server-side guards for unsafe subsystem deletes. |
| `api/src/main/java/org/apache/cloudstack/api/command/user/storage/dataservice/UpdateStorageNvmeOfSubsystemCmd.java` and `DeleteStorageNvmeOfSubsystemCmd.java` | No new commands are required. Tighten parameter semantics in docs and rely on server-side validation for immutable fields and delete safety. |
| `systemvm/debian/usr/local/bin/ablestack-storagectl` | No direct change is required for UI action exposure. Only change this file if backend listener deletion reveals that desired-state apply leaves stale configfs links; then cleanup must be driven by the desired-state payload, not ad hoc UI behavior. |

Preflight and validation rule:

- A service-VM code injection preflight is not required for pure UI action
  exposure or API validation because the configfs runtime model is unchanged.
- A preflight is required before changing `ablestack-storagectl` listener cleanup
  logic. The preflight must verify deletion of an unused NVMe-oF listener,
  refusal to delete a namespace-referenced listener, and reboot reconcile with
  the listener removed.
- UI validation must include horizontal-scroll tables with fixed right action
  columns so the action buttons do not overlap the scrollbar or disappear when
  the table is scrolled.

## Cross-Protocol Endpoint Read Model And Runtime Alignment (2026-07-18)

This section is the implementation design for the management-plane defects
remaining after the integrated NFS, SMB, iSCSI, and NVMe-oF runtime validation
on SharedFS `63efbcd0-65ee-4e73-bf92-bfe09c62703a`. The data paths and reboot
reconcile passed. The correction is therefore limited to endpoint identity,
runtime-policy alignment, UI semantics, locale completeness, and one SystemVM
unit-file permission defect.

### Canonical listener read model

The common protocol response is the authoritative listener inventory. UI code
must not reconstruct configured listeners from service NICs, child resources,
or default protocol ports when `listStorageServiceProtocols` returned listener
rows.

Priority order:

1. `StorageServiceProtocolResponse.listenip`, `port`, `listenertype`,
   `effectiveendpoints`, `runtimestate`, and `linkedresourcecount`.
2. Parsed protocol `config` only for backward-compatible responses that do not
   include the fields above.
3. Service NIC inventory only to expand a wildcard listener when the API did
   not provide `effectiveendpoints`.
4. Protocol default ports only when no protocol row exists at all; this is an
   explicit legacy/empty-state fallback and must be labelled as inferred.

Wildcard canonicalization is semantic, not destructive. For one protocol and
port, a `0.0.0.0:<port>` row remains the single logical listener and its
effective endpoints contain each service IP exactly once. A dedicated row with
the same port is hidden as redundant only when it adds no distinct lifecycle or
runtime state. The read path never deletes historical DB rows automatically.

### Protocol-specific behavior

| Area | As-is | To-be |
| --- | --- | --- |
| NFS listener inventory | Wildcard and primary-IP rows can both be rendered even though one Ganesha wildcard listener owns the port. | Adapt NFS through the common listener read model. Collapse semantically covered primary rows and preserve distinct NFSv4 listener groups by port. `V3V4_DUAL` remains service-wide. |
| SMB endpoint calculation | `smbEffectiveEndpointPairs` reads selected protocol IPs and then appends every service NIC, so share paths imply access on addresses not selected by the operator. Runtime `smbd` currently listens on `0.0.0.0:445`. | Build UI share paths only from canonical SMB listener rows. Render `interfaces` and `bind interfaces only = yes` in `smb.conf` for dedicated SMB listeners. A wildcard SMB listener intentionally expands to every service IP. Runtime inventory must compare desired and observed bindings. |
| iSCSI status and commands | `iscsiConnectionCommands` composes the service primary IP and hard-coded port 3260 even when the target uses another portal. | Resolve endpoint pairs from the target's listener port groups and canonical iSCSI listener rows. Status cards and commands display all effective portals; the command example uses the first active portal only as an example. |
| iSCSI LUN size | A whole-volume LUN can show requested size `-` and applied size `50 GiB`, which looks incomplete. | Display the requested size as `백킹 볼륨 전체` / `Entire backing volume` when no explicit size was requested, while retaining the observed applied capacity in the effective-size column. |
| NVMe-oF all-host session label | The raw key `label.storage.service.nvme.allow.any.host` can escape into the session table. | Add the locale key in both bundles and route policy text through `storageLabel` only as a last-resort safety fallback. A locale test fails when any rendered `label.storage.service.*` text remains. |
| Systemd unit packaging | `resize-sharedfs@.service` is created with mode `0700`, producing a systemd unit permission warning. | Change only the unit-file mode to `0644`; keep `/usr/local/bin/sharedfs/resize-filesystem` executable at `0700`. |

### Code-level implementation targets

| Component/file | Required change |
| --- | --- |
| `api/src/main/java/org/apache/cloudstack/api/response/StorageServiceProtocolResponse.java` | Keep the existing response contract. No new field is required. Clarify that `effectiveendpoints` is the canonical expansion of a logical listener and `linkedresourcecount` is scoped to the listener port group. |
| `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | Keep `createProtocolResponse`, `buildProtocolResponseContext`, and `createEffectiveProtocolEndpoints` as the common adapter. Add deterministic wildcard deduplication in the response assembly or a helper used by `listStorageServiceProtocols`; do not infer the primary IP from the newest secondary address. Extend `applySmbDesiredState` with canonical enabled listener IPs and ports. |
| `ui/src/views/storage/SharedFSTab.vue` | Introduce one adapter such as `canonicalProtocolListenerRows(protocolName)` and make NFS, SMB, iSCSI, and NVMe-oF listener tables, summaries, connection commands, and child-resource endpoint labels consume it. Remove the unconditional `serviceEndpoints.forEach(addIp)` branch from `smbEffectiveEndpointPairs`. Replace the hard-coded `serviceEndpoint:3260` path in `iscsiConnectionCommands`. Add an explicit whole-volume formatter for `iscsiTargetRows`. |
| `ui/public/locales/ko_KR.json`, `ui/public/locales/en.json` | Add the missing NVMe-oF allow-any-host policy label and whole-backing-volume LUN label. Maintain a locale parity test for every new storage-service key. |
| `systemvm/debian/usr/local/bin/ablestack-storagectl` | In SMB desired-state apply, derive dedicated listener addresses from the payload and render `interfaces = lo <selected addresses>` plus `bind interfaces only = yes`. Omit both settings for a wildcard SMB listener. After restart, verify observed TCP 445 addresses and report mismatch through monitor inventory instead of silently returning success. |
| `plugins/storage/sharedfs/storagevm/src/main/resources/conf/fsvm-init.yml` | Change `/etc/systemd/system/resize-sharedfs@.service` permissions from `0700` to `0644`. No service behavior changes. |
| `ui/tests/unit/views/storage/SharedFSTab.spec.js` | Add table-driven tests for wildcard expansion, redundant NFS row suppression, dedicated SMB isolation, iSCSI portal-derived commands, whole-volume LUN labels, and raw-key rejection. |
| `server/src/test/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImplTest.java` | Add protocol response tests for wildcard effective endpoint expansion, secondary-IP preservation, and linked-resource counts by protocol/port. |

### SMB preflight evidence and runtime contract

A non-destructive QGA preflight was executed on `i-2-597-VM`:

- current Samba configuration had no `interfaces` value and
  `bind interfaces only = No`;
- runtime `smbd` listened on IPv4 and IPv6 wildcard TCP 445;
- a temporary configuration containing
  `interfaces = lo 10.10.22.201/32` and
  `bind interfaces only = yes` passed `testparm` and normalized to the expected
  values;
- no live Samba configuration or process was changed by this preflight.

This proves the selected-IP model is syntactically supported in the current
SystemVM image. Implementation still requires a data-path regression showing
that selected addresses accept SMB and unselected addresses reject SMB without
affecting NFS, iSCSI, or NVMe-oF listeners.

### Scope boundaries and rollout gates

- No DB schema migration is required. Existing protocol rows already hold the
  required listener identity.
- API response shape remains backward compatible. The management aggregate JAR
  and API classes must still be deployed as one aligned set.
- A SystemVM template rebuild is required because SMB rendering and the
  cloud-init unit permission change are inside the image.
- NFS Ganesha, LIO iSCSI, kernel NVMET, backing-volume mapping, ACL semantics,
  and reboot reconcile algorithms are out of scope and must remain unchanged.
- Release is blocked unless all four protocols pass connection/write checks,
  reboot recovery, listener-table UI checks, locale checks, and the served UI
  asset/hash gate.

## Existing NIC Drift Repair and iSCSI Session Authentication Closure

This section closes the two management-plane defects found during the
four-protocol validation of an existing Storage Service. It supplements the
primary-NIC preservation design above and does not reopen listener, backing
volume, ACL, or reboot-reconcile behavior.

### Scope and invariants

- A populated `nics.ip4_address` remains authoritative until an explicit,
  administrator-approved repair is applied.
- Runtime QGA addresses and `nic_secondary_ips` are observations. They may
  prove drift, but may not silently rewrite primary identity.
- Repair is allowed only when the observed runtime primary is unambiguous and
  the incorrect DB primary is still present as a runtime/DB alias.
- An established LIO iSCSI session is not an authentication failure merely
  because `targetcli sessions detail` prints `(NOT AUTHENTICATED)`.
- CHAP secrets must never be returned by the session API, written to the
  monitoring cache, or rendered by the UI.

### AS-IS / TO-BE

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Existing NIC drift | Prevention and an administrator repair API exist, but the SharedFS UI only warns. Existing drift therefore remains operationally unresolved. | The warning exposes a guarded dry-run and explicit repair action when the repair API is available. The UI shows DB primary, runtime primary, aliases, and the exact proposed change before confirmation. |
| NIC repair result | The repair command updates the guarded primary field, but the UI has no closed-loop postcondition. | After the async job succeeds, refetch protocol/identity context and require `identityStatus=CONSISTENT`; aliases and protocol endpoint rows must remain unchanged. A failed postcondition is shown as a repair failure, not success. |
| iSCSI authentication observation | `ablestack-storagectl` maps the literal targetcli text `(NOT AUTHENTICATED)` to `authenticated=false`, even for a CHAP-protected `LOGGED_IN` LIO session. | Authentication is classified from LIO policy, matching ACL configuration, session state, and explicit failure evidence. The targetcli phrase is retained only as a diagnostic observation. |
| iSCSI session API | Session JSON exposes a misleading boolean without explaining whether CHAP was configured or required. | Session JSON exposes `chapConfigured`, `authRequired`, `authVerification`, and optional backward-compatible `authenticated`; no credential value is exposed. |
| iSCSI session UI | The table cannot distinguish no-auth sessions, verified CHAP sessions, unknown observations, and failures. | Add translated `CHAP 구성` and `인증 상태` columns. `UNKNOWN` includes a tooltip; `FAILED` is reserved for explicit authentication failure evidence. |

### NIC repair implementation contract

The existing `repairStorageServiceNicIdentity` API is the only write path. No
new repair command or DB migration is required.

1. `SharedFSTab.vue` reads the identity diagnostic fields already returned by
   the protocol response and displays the repair control only when:
   - `identityStatus` is `DRIFT`;
   - the current API catalog contains `repairStorageServiceNicIdentity`;
   - DB primary, runtime primary, and alias evidence are all populated.
2. The first action always calls the command with `dryrun=true`. The response
   populates a confirmation dialog; the UI must not derive the proposed values
   independently.
3. Apply calls the same command with `dryrun=false` and
   `expectedruntimeprimary=<dry-run runtime primary>` to reject stale evidence.
4. The existing async-job helper waits for completion. Only the identity and
   protocol data sets are refreshed; the selected service tab and wide-mode
   state are preserved.
5. The success postcondition is `identityStatus=CONSISTENT`, the repaired DB
   primary equals the runtime primary, and the alias set is unchanged.
6. `NicDaoImpl.updateSecondaryIpFlag` remains a field-scoped, expected-value
   guarded update. A full `NicVO` update is prohibited for this flow.

### iSCSI authentication classification contract

`ablestack-storagectl` must separate parsing from classification:

```text
parse_targetcli_session_auth(text) -> raw observation
classify_iscsi_auth_session(session, target, acl, runtime_attrs) -> contract
```

The classifier returns one of:

| `authVerification` | Required evidence | Compatibility field |
| --- | --- | --- |
| `VERIFIED` | Session is `LOGGED_IN`, LIO authentication is required, and the matched desired/runtime ACL has CHAP configured. | `authenticated=true` |
| `NOT_REQUIRED` | Session is `LOGGED_IN` and target/TPG authentication is disabled. | omit `authenticated` |
| `UNKNOWN` | Session exists but required policy or ACL evidence cannot be resolved safely. | omit `authenticated` |
| `FAILED` | Explicit login/authentication failure evidence exists and no established session supersedes it. | `authenticated=false` |

The literal targetcli observation is stored as
`targetcliAuthObservation=AUTHENTICATED|NOT_AUTHENTICATED|UNSPECIFIED` for
diagnostics only. It cannot directly select `FAILED`.

For each session row, the collector also emits stable correlation fields:

- initiator IQN;
- target IQN;
- mapped LUN/namespace identifier;
- portal endpoint;
- `chapConfigured` and `authRequired` booleans when known;
- `authVerification`;
- connection state and observed time.

The monitoring/session payload uses `sessionSchemaVersion=2`. Readers must
continue accepting version 1 rows and map their authentication state to
`UNKNOWN` unless explicit positive evidence is present.

### Code-level implementation targets

| Component/file | Required change |
| --- | --- |
| `ui/src/views/storage/SharedFSTab.vue` | Add the API-catalog-gated NIC repair dry-run/confirmation/apply flow, preserve the active tab during focused refresh, and add iSCSI `chapConfigured`/`authVerification` columns with backward-compatible row adaptation. |
| `ui/public/locales/ko_KR.json`, `ui/public/locales/en.json` | Add repair confirmation/result labels and iSCSI authentication-state labels. Enforce locale-key parity and raw-key rejection. |
| `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | Retain the current guarded repair implementation. Add/verify the post-repair identity response postcondition and preserve aliases/listener rows. Do not add an automatic repair path. |
| `engine/schema/src/main/java/com/cloud/vm/dao/NicDao.java`, `engine/schema/src/main/java/com/cloud/vm/dao/NicDaoImpl.java` | Retain the expected-primary, field-scoped update contract and add regression coverage. No schema change. |
| `server/src/main/java/com/cloud/server/StatsCollector.java` | Retain the rule that observed aliases cannot replace a populated primary. Add ordering/permutation regression coverage only. |
| `api/src/main/java/org/apache/cloudstack/api/command/admin/storage/dataservice/RepairStorageServiceNicIdentityCmd.java` | Keep dry-run as the default and require expected runtime primary for apply. No new API surface is required. |
| `systemvm/debian/usr/local/bin/ablestack-storagectl` | Replace direct targetcli-string boolean mapping with the versioned iSCSI authentication classifier and secret-free session fields. |
| `server/src/test/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImplTest.java` | Cover dry-run, stale evidence rejection, ambiguous runtime addresses, alias preservation, and post-repair `CONSISTENT` identity. |
| `ui/tests/unit/views/storage/SharedFSTab.spec.js` | Cover repair control visibility, confirmation values, tab preservation, version 1 session fallback, and all authentication badges. |
| `systemvm/test/test_ablestack_storagectl_iscsi_sessions.py` | Add table-driven fixtures for CHAP verified despite targetcli `NOT AUTHENTICATED`, no-auth, unknown policy, explicit failure, and secret redaction. |

### Build, deployment, and acceptance gates

- DB migration: not required.
- Host agent deployment: not required.
- Management/API/UI deployment: required for repair workflow and session/UI
  contract consumers.
- SystemVM template rebuild: required because the iSCSI collector lives in
  `ablestack-storagectl`.
- Existing running SystemVMs require either an explicit collector hot patch for
  preflight or recreation from the rebuilt template; management-side NIC repair
  does not require a SystemVM change.
- Deployment is accepted only when the served UI asset hashes match the local
  build, the repair dry-run/apply postcondition passes, CHAP login/write remains
  successful, the session row reports `VERIFIED`, and all four protocols pass
  reboot recovery without listener or alias changes.

## Protocol-Scoped Session Warning and NFS Volume Presentation Closure (2026-07-20)

### Validated evidence and scope

The four-protocol validation of Storage Service instance 144 and Service VM
599 passed DB/runtime correlation, NFS/SMB/iSCSI/NVMe-oF connection and
reversible write tests, and reboot recovery. Two remaining defects are limited
to UI-derived presentation data:

- the iSCSI tab rendered an NVMe-oF mapping warning because
  `iscsiSessionRuntimeWarning` consumed the top-level, cross-protocol
  `sessions.json.warnings` array;
- the NFS backing-volume table iterated `currentBackingVolumes`, which is the
  intentionally broad attached-DATADISK selector used by create dialogs. It
  therefore included SMB, iSCSI, and NVMe-oF volumes and could fall back to the
  SharedFS default filesystem, displaying an ext4 raw block volume as XFS.

No Service VM code injection is required for this closure. The runtime cache,
block-device filesystem probes, listener state, protocol resources, and
post-reboot data paths were already correct. Injecting code into a healthy
Service VM would not exercise the faulty Vue computed properties and would add
unnecessary risk.

### AS-IS / TO-BE

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| iSCSI warning source | `iscsiSessionRuntimeWarning` joins every top-level runtime warning. An NVMe-oF candidate-mapping warning can therefore appear above an exact iSCSI session row. | Derive the iSCSI warning only from iSCSI counters and iSCSI session rows. Never render another protocol's `mappingWarning` in the iSCSI tab. |
| iSCSI warning text | Runtime English diagnostic text is appended to a translated iSCSI banner. | Render a fully translated, protocol-specific summary. Keep raw runtime diagnostics out of the normal table surface; they remain available to diagnostic tooling. |
| NFS table volume source | `nfsVolumeRows` iterates all DATADISK volumes attached to the Service VM through `currentBackingVolumes`. | Add an NFS-specific volume projection keyed by the `volumeId`/`volumeUuid` referenced by NFS exports. The broad collection remains available only to volume-selection dialogs. |
| NFS filesystem value | Missing NFS inspection data can fall back to `resource.filesystem`, causing unrelated raw block volumes to display as XFS. | Resolve filesystem from the matched NFS export inspection, export filesystem field, or exact volume runtime metadata. If none is authoritative, display `-`; never infer XFS from the SharedFS default. |
| Refresh behavior | One atomic refresh loads all protocol resources and all attached volumes, but table projections do not consistently preserve protocol ownership. | Keep the existing atomic refresh and build protocol-scoped computed projections after the snapshot commits. Do not issue extra API calls per row. |

### UI implementation contract

`ui/src/views/storage/SharedFSTab.vue` must implement the following contracts:

1. Add `nfsBackingVolumes`, a computed projection that:
   - builds a set from every NFS export `volumeid`, `volumeId`, `volumeuuid`,
     and `volumeUuid`;
   - exact-matches those identifiers against `storageService.backingVolumes`;
   - retains the default SharedFS volume only when an NFS export explicitly
     references it;
   - deduplicates by canonical ABLESTACK volume ID;
   - never includes a volume solely because it is attached to the Service VM.
2. Change `nfsVolumeRows` to iterate `nfsBackingVolumes`. Keep
   `currentBackingVolumes` unchanged because NFS/SMB create dialogs require the
   full set of safe, attached DATADISK candidates.
3. Add an NFS-only filesystem resolver. Resolution order is:
   `config.lastInspection.filesystem` -> export `filesystem`/`fsType` -> exact
   volume runtime `filesystem`/`fsType` -> `-`. Remove `resource.filesystem`
   from NFS table-row fallback logic.
4. Replace `iscsiSessionRuntimeWarning` consumption of global `warnings` with
   protocol evidence:
   - `observedIscsiTcpCount` is the observed transport count;
   - `protocolSessions('ISCSI')` is the logical row set;
   - `UNMAPPED` iSCSI rows may produce an iSCSI-specific translated warning;
   - exact or otherwise mapped iSCSI rows suppress the incomplete-detail
     warning even when another protocol has a mapping warning.
5. Do not display raw runtime warning strings in the standard iSCSI tab. A new
   locale key provides the count-only incomplete-session message in Korean and
   English. Diagnostic raw text remains available in the runtime payload.

### Code-level targets

| Component/file | Required change |
| --- | --- |
| `ui/src/views/storage/SharedFSTab.vue` | Add `nfsBackingVolumes`; scope `nfsVolumeRows`; remove default-filesystem fallback from NFS table rows; make `iscsiSessionRuntimeWarning` protocol-specific and translation-only. |
| `ui/public/locales/ko_KR.json`, `ui/public/locales/en.json` | Add the protocol-specific incomplete iSCSI session message and keep locale-key parity. No raw runtime English warning is rendered. |
| `ui/tests/unit/views/storage/SharedFSTab.spec.js` | Add mixed-protocol warning fixtures, exact iSCSI suppression, iSCSI-unmapped warning, NFS-only volume filtering, ID/UUID matching, deduplication, and unknown-filesystem `-` cases. |
| API, server, schema, SystemVM | No change. Existing list APIs and runtime cache already contain sufficient authoritative data. |

### Acceptance gates

- A runtime payload containing an exact iSCSI session and an NVMe-oF
  `mappingWarning` renders no iSCSI incomplete-session banner.
- An observed iSCSI transport with no logical iSCSI row renders only the
  translated iSCSI warning and the observed count.
- The NFS backing-volume table contains exactly the volumes referenced by NFS
  exports. SMB/iSCSI/NVMe-oF-only volumes are absent.
- An NFS volume with authoritative ext4 inspection displays `ext4`; an unknown
  filesystem displays `-` and never falls back to XFS.
- NFS/SMB current-volume selectors still list all safe attached DATADISK
  candidates; this presentation fix must not reduce create-dialog capability.
- Locale JSON parsing, locale-key parity, focused Jest tests, lint, and the UI
  production build pass.
- Deployment scope is UI only. DB migration, management/API deployment,
  `mold-agent.service` restart, and SystemVM template rebuild are not required.

## Stable File-Share Backing-Volume Identity And Runtime Observation (2026-07-21)

### Scope and validated failure

This closure is limited to NFS/SMB backing-volume identity after Service VM
reboot and guest disk re-enumeration. It must not change NFS export behavior,
SMB share or ACL behavior, iSCSI/NVMe-oF raw-block ownership, or any existing
four-protocol reboot contract.

A read-only QGA preflight against Service VM 599 proved that Linux device names
are not stable volume identities:

- the NFS volume recorded `/dev/sdb` in `config_json.lastInspection`, but the
  running guest resolved that ABLESTACK volume UUID and filesystem UUID to
  `/dev/sde` after reboot;
- the SMB volume recorded `/dev/sdc`, but the running guest resolved it to
  `/dev/sdd`;
- the old `/dev/sdb` and `/dev/sdc` names were valid, safe data disks belonging
  to different volumes, so a safety check based only on disk type would not
  prevent a wrong-volume selection;
- UUID-backed `/etc/fstab` entries and stable per-volume mount directories
  recovered correctly, and all protocol data paths remained healthy.

The failure is therefore metadata drift plus a latent wrong-device risk. It is
not a mount, listener, filesystem, or protocol-service failure.

### AS-IS / TO-BE

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Desired volume identity | `attachedVolumeUuid` exists, but some guest operations accept a cached `config.devicePath` first. | `attachedVolumeUuid` and the persisted ABLESTACK volume ID are the authoritative desired identity. |
| Guest device identity | `/dev/sdX` from `lastInspection.devicePath` can be reused as a selector after reboot. | `/dev/sdX` is volatile telemetry only. Selection uses stable volume serial/by-id evidence, filesystem UUID, and the mounted source. |
| Inspection schema | `lastInspection` mixes stable and volatile fields and emits `fsUuid` without a schema version or observation epoch. | Store a versioned observation with `filesystemUuid`, `observedDevicePath`, `observedAt`, `bootId`, and `matchedBy`; retain legacy read aliases. |
| Resolver implementations | Attach, rescan, resize, and boot reconcile have partially different lookup orders. | All operations call one strict resolver with the same identity order and ambiguity rules. |
| API semantics | Raw `config_json` can make a historical path look current. | Desired identity and live runtime observation are separate response fields with an explicit mapping status. |
| UI semantics | A cached guest device path can be interpreted as the current path. | The backing-volume table shows stable volume identity and mount intent; a guest path is shown only from current runtime inventory and is labelled as current observation. |
| Legacy records | Existing `devicePath`/`fsUuid` records have no deterministic normalization path. | Legacy records remain readable, but `devicePath` never selects a disk; the next successful inspect/apply writes schema version 2. |

### Stable identity and observation contract

`config_json` remains the persistence container; no DB column migration is
required. A successful inspection writes the following normalized shape:

```json
{
  "attachedVolumeUuid": "<ABLESTACK volume UUID>",
  "attachedVolumeName": "<volume name>",
  "volumeMountPath": "/srv/ablestack-storage/volumes/<volume UUID>",
  "lastInspection": {
    "schemaVersion": 2,
    "volumeUuid": "<ABLESTACK volume UUID>",
    "filesystemUuid": "<filesystem UUID>",
    "filesystem": "xfs|ext4",
    "observedDevicePath": "/dev/sdX",
    "observedAt": "<UTC ISO-8601>",
    "bootId": "<guest boot ID>",
    "matchedBy": "VOLUME_SERIAL|FILESYSTEM_UUID|MOUNT_SOURCE",
    "volumeMountPath": "/srv/ablestack-storage/volumes/<volume UUID>",
    "backingPath": "/export/<share name>"
  }
}
```

Compatibility rules are mandatory:

1. Read legacy `fsUuid` as an alias for `filesystemUuid`.
2. Read legacy `devicePath` only as historical diagnostic evidence. It must
   never be passed to a disk selector.
3. Do not mutate the DB from a list/read API. A boot-local reconcile updates
   runtime inventory; an explicit inspect/apply operation may normalize the
   persisted observation.
4. Preserve `volumeMountPath` and UUID-based `/etc/fstab` entries as desired
   mount intent. A changed `/dev/sdX` must not rewrite that intent.

### SystemVM resolver contract

`systemvm/debian/usr/local/bin/ablestack-storagectl` must expose one shared
resolver used by `volume_attach`, `volume_rescan`, `filesystem_resize`, and boot
reconcile. The resolver order is strict:

1. Build the safe candidate set and reject root, boot, swap, optical, read-only,
   and unrelated mounted devices.
2. Match the compact ABLESTACK volume UUID against stable `/dev/disk/by-id`
   links and the guest disk serial. This is the primary match.
3. If a known filesystem UUID exists, resolve `/dev/disk/by-uuid` and require it
   to agree with stable volume evidence whenever serial evidence is available.
4. Resolve the mounted source with `findmnt --target <volumeMountPath>` and
   verify that source against the expected volume/filesystem identity.
5. Permit a size-only fallback only for the first attachment of a blank volume,
   only when exactly one safe candidate exists. Reboot reconcile, rescan, and
   resize must never use this fallback.
6. Return an error for zero or multiple candidates. Never guess and never fall
   through to a cached `devicePath`.
7. Return the current kernel path plus `matchedBy`, filesystem UUID, boot ID,
   and observation time as telemetry.

The attach result must emit `filesystemUuid`. It may also emit `fsUuid` during a
compatibility window, but management code must normalize both to the canonical
field. `filesystem_resize` must resolve by mount target or stable identity
before invoking the filesystem tool; `volume_rescan` must use the same resolver
before any capacity or mount operation.

### Management and API contract

`StorageServiceManagerImpl.inspectAttachedFileShareVolume` and
`buildFileShareAttachConfigJson` must:

- preserve `attachedVolumeUuid`, DB `volume_id`, and `volumeMountPath` as desired
  state;
- normalize `fsUuid`/`filesystemUuid` and `devicePath`/`observedDevicePath` into
  the version 2 observation;
- add `observedAt`, guest `bootId`, and `matchedBy` from the command result;
- never copy a historical device path into a future command's selector input;
- refuse an inspection result whose resolved volume identity differs from the
  requested ABLESTACK volume UUID.

`StorageFileShareResponse` and `StorageSmbShareResponse` must expose separate
stable and runtime fields instead of requiring the UI to interpret raw JSON:

- desired: `volumeId`, `volumeUuid`, `filesystemUuid`, `volumeMountPath`;
- runtime: `runtimeDevicePath`, `runtimeObservedAt`, `runtimeBootId`,
  `runtimeMatchedBy`;
- status: `mappingStatus` with `EXACT`, `STALE`, `UNMAPPED`, or `AMBIGUOUS`.

Current runtime fields come from the monitoring inventory/cache. Historical
`lastInspection.devicePath` must never be returned as `runtimeDevicePath`.
`STALE` means the persisted observation belongs to another boot or no longer
matches current stable identity; it is diagnostic and does not override a live
`EXACT` mapping.

### UI contract

The NFS and SMB backing-volume rows in
`ui/src/views/storage/SharedFSTab.vue` must display:

- ABLESTACK volume name and UUID;
- filesystem and stable mount path;
- mapping status from the API;
- optional `현재 게스트 장치` only when current runtime inventory supplies an
  exact path;
- observation time and boot ID in a tooltip when runtime telemetry exists.

If runtime inventory is unavailable or stale, display `-` for the guest device
and a translated stale/unavailable status. Do not degrade a healthy share solely
because a historical `/dev/sdX` differs. Existing dark-mode table, compact
scrollbar, fixed action-column, and tooltip conventions remain unchanged.

### Code-level targets

| Component/file | Required change |
| --- | --- |
| `systemvm/debian/usr/local/bin/ablestack-storagectl` | Add the shared stable resolver; remove `config.devicePath` precedence; normalize attach output; reuse resolver in attach/rescan/resize/reconcile. |
| `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | Normalize inspection schema version 2, preserve desired identity, validate returned identity, and stop propagating stale paths as selector input. |
| File-share/SMB response classes and response builders | Add desired/runtime identity fields and `mappingStatus`; source runtime fields only from current inventory. |
| `ui/src/views/storage/SharedFSTab.vue` | Render stable identity separately from current guest observation and handle exact/stale/unmapped/ambiguous states. |
| Korean and English locale JSON | Add current-device, observation, and mapping-status labels with key parity. |
| Focused server, SystemVM, and UI tests | Cover legacy normalization, reordered disks, ambiguous candidates, runtime-only display, and stale observation behavior. |
| DB schema, host agent | No change. |

### Acceptance and deployment gates

- A fixture where cached `/dev/sdb` now belongs to another safe data disk must
  resolve the expected volume by ABLESTACK UUID/serial and return the new path.
- A reboot that reorders NFS and SMB disks must recover UUID-backed mounts and
  aliases, preserve desired volume UUIDs, and report `mappingStatus=EXACT`.
- Ambiguous or mismatched stable identities must fail before format, mount,
  resize, or export/share apply.
- Legacy `fsUuid` is normalized; legacy `devicePath` is never used as authority.
- List APIs remain read-only and do not rewrite observations.
- NFS/SMB connect and reversible write tests pass before and after reboot;
  iSCSI and NVMe-oF regressions remain passing.
- Management/API and UI artifacts are rebuilt and deployed together. The
  SystemVM template is rebuilt because the resolver changes. Served UI asset
  hashes and management class signatures must match the build.
- No DB migration and no `mold-agent.service` deployment or restart are needed.

## Integrated Runtime Observation Completion (2026-07-21)

### Scope and read-only preflight evidence

This closure completes the runtime-observation side of the stable volume
identity design. It is limited to NFS/SMB backing-volume projection, iSCSI
actual backing-device projection, and the SharedFS VM boot-network owner. It
must not change protocol desired state, ACL semantics, listener selection,
filesystem contents, raw-block ownership, or the existing four-protocol reboot
reconcile order.

A read-only QGA preflight against Service VM 600 established the following:

- `findmnt -J` returned one top-level node and 29 nodes recursively; both managed
  file-service mounts were child nodes, so the current top-level-only collector
  returned no observations for otherwise healthy `/dev/sdd` and `/dev/sde`
  mounts;
- `targetcli ls /iscsi` resolved the configured IQN and LUN 0 to `/dev/sdc`,
  proving that the SystemVM collector has the actual iSCSI backing path while
  the management list response drops that observation;
- `networking.service` was failed with `ifup: unknown interface eth0`; the file
  declared `auto lo eth0` but contained only an `iface lo` stanza, while the
  active SharedFS NIC was correctly owned by `cloud-dhclient@eth0.service` and
  storage endpoint reconciliation;
- all four protocol data paths and reboot recovery remained healthy. These are
  observation and service-ownership defects, not desired-state failures.

No mutating code injection is required for this closure. The live evidence is
complete and the proposed changes are collector traversal, response merging,
presentation semantics, and SharedFS-only unit ownership.

### AS-IS / TO-BE

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| File-share mount discovery | `file_share_volume_runtime()` iterates only `findmnt.filesystems[]`. Managed mounts below the root node are silently omitted. | Recursively flatten every `children[]` node, then select exact canonical managed mount targets. |
| File-share mapping result | A missing observation is defaulted to `UNMAPPED` even when inventory collection itself was unavailable or incomplete. | Distinguish `EXACT`, `STALE`, `UNMAPPED`, `AMBIGUOUS`, and `UNAVAILABLE`; absence caused by collector failure is never called unmapped. |
| NFS/SMB UI | A healthy mounted volume can display current device `-` and mapping `unmapped`. | Display the current device only from an `EXACT` live observation and show translated unavailable/stale diagnostics otherwise. |
| iSCSI runtime collection | SystemVM inventory contains target/LUN `backingPath`, backstore type, and effective size. | Preserve that existing collector output and add an observation timestamp and boot ID. |
| iSCSI management response | `listStorageIscsiTargets` calls the response builder without runtime observation, and runtime response fields are populated only for NVMe-oF namespaces. | Load inventory once per instance, join by normalized `(target IQN, LUN)`, and populate runtime backing path, mapping status, size, and observation metadata for iSCSI. |
| iSCSI UI | The `actual backing path` column falls back to desired config and commonly renders `-` for block targets. | Render `runtimeBackingPath` as the actual path; keep configured backing intent separate and never fabricate an actual path. |
| SharedFS boot networking | Generic `networking.service` attempts an invalid ifupdown `eth0` stanza, while persistent DHCP is independently managed by `cloud-dhclient@eth0`. | Declare persistent DHCP as the only SharedFS NIC owner, disable the generic unit for SharedFS VMs, and order storage reconcile after the DHCP unit. |
| Health semantics | A failed unused networking unit can coexist with healthy protocol listeners without a clear ownership explanation. | No unused failed networking unit remains; health reports DHCP ownership and endpoint-reconcile status explicitly. |

### SystemVM runtime inventory contract

`systemvm/debian/usr/local/bin/ablestack-storagectl` must add a pure helper such
as `flatten_findmnt_filesystems(nodes)` and use it in
`file_share_volume_runtime()`.

1. Execute `findmnt -J -o SOURCE,TARGET,FSTYPE,FSROOT,OPTIONS` once.
2. Recursively yield each node and all `children` in deterministic order.
3. Normalize target paths and accept only an exact
   `/srv/ablestack-storage/volumes/<volume UUID>` target. Bind aliases such as
   `/export/<name>` are secondary observations and must not create duplicate
   volume rows.
4. Resolve the mount source through the existing strict
   `resolve_volume_device()` implementation. Never use `/dev/sdX` as desired
   identity.
5. Emit one observation per volume UUID with `observedDevicePath`,
   `volumeMountPath`, `filesystem`, `filesystemUuid`, `matchedBy`, `observedAt`,
   and `bootId`.
6. If two canonical mounts claim one volume UUID, emit `AMBIGUOUS` and do not
   choose one. If collection fails, mark the inventory envelope unavailable
   rather than returning a successful empty list.

The inventory result must add an envelope timestamp and boot ID. Existing
`fileShareVolumes`, `iscsiTargets`, and `nvmeofSubsystems` keys remain backward
compatible. A successful empty observation and an unavailable collector are
different states.

The existing `targetcli_iscsi_runtime()` output remains authoritative for the
live iSCSI backing path. It must add `observedAt` and `bootId`, and its parser
must have a fixture matching the deployed targetcli tree format. ConfigFS may be
used as corroborating evidence, but the join key remains normalized target IQN
plus normalized LUN number.

### Management and API merge contract

`StorageServiceManagerImpl` must introduce an immutable runtime snapshot result
instead of returning an unqualified map. The snapshot carries `available`,
`observedAt`, `bootId`, and the protocol-specific observations.

- `loadFileShareVolumeRuntimeObservations()` indexes observations by normalized
  volume UUID and preserves snapshot availability.
- NFS/SMB response builders return `UNAVAILABLE` when inventory cannot be read,
  `UNMAPPED` only when a successful current snapshot lacks the expected volume,
  and `EXACT` only for a unique stable-identity match.
- `loadIscsiTargetRuntimeObservations()` reads `iscsiTargets` once per Service
  VM and indexes each LUN by `lowercase(trim(targetName)) + '|' + normalizedLun`.
- `listStorageIscsiTargets()` groups targets by instance, fetches one snapshot
  per instance, and passes the matching observation into
  `createBlockTargetResponse()`.
- The block-target response builder applies the existing
  `runtimeBackingPath`, `runtimeMappingStatus`, `actualBackingSizeBytes`, and
  `runtimeObservedAt` fields to both iSCSI LUNs and NVMe-oF namespaces. It must
  not overload configured `backingPath` with runtime data.
- List/read APIs remain read-only. No runtime observation is written back to DB
  resource config.

No DB migration is required. Existing response fields are sufficient; only the
mapping-status enum is extended with backward-compatible `UNAVAILABLE`.

### SharedFS boot-network ownership contract

`plugins/storage/sharedfs/storagevm/src/main/resources/conf/fsvm-init.yml` is
the SharedFS-specific owner and must not change networking behavior for router,
console-proxy, or secondary-storage System VMs.

1. Start and enable `cloud-dhclient@<storage-interface>.service` first.
2. Verify that the interface has a global IPv4 address before declaring setup
   complete.
3. Reduce `/etc/network/interfaces` to loopback-only content for this VM type,
   disable `networking.service` for future boots, and reset its stale failed
   state without stopping the active DHCP-owned interface.
4. Add `Before=network-online.target` to the persistent DHCP template and order
   `ablestack-storage-reconcile.service` after the concrete DHCP unit and QGA.
5. Keep secondary-IP persistence in `ablestack-storagectl network endpoints
   reconcile`; endpoint reconciliation remains idempotent and must not become a
   second primary-address owner.

### UI contract

`ui/src/views/storage/SharedFSTab.vue` must keep the current dark-mode table and
fixed action-column layout while changing only observation projection:

- NFS/SMB current-device cells use runtime fields only when status is `EXACT`;
- `UNAVAILABLE`, `STALE`, `UNMAPPED`, and `AMBIGUOUS` receive distinct translated
  labels and tooltips containing observation time, boot ID, matched-by evidence,
  or the warning reason when present;
- the iSCSI actual-backing column reads `runtimeBackingPath` before any legacy
  alias and displays `-` when no exact runtime mapping exists;
- desired mount path and configured backing intent stay in their existing
  columns and are never presented as runtime truth.

### Code-level targets

| Component/file | Required change |
| --- | --- |
| `systemvm/debian/usr/local/bin/ablestack-storagectl` | Recursively flatten `findmnt`, emit snapshot metadata and collection availability, and timestamp iSCSI runtime observations. |
| `systemvm/test/TestStorageVolumeIdentity.py` and focused inventory fixtures | Cover nested mounts, duplicate canonical mounts, unavailable findmnt, reboot device reorder, and targetcli IQN/LUN backing paths. |
| `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | Add qualified runtime snapshots, truthful file-share status derivation, iSCSI observation loading/joining, and block-target runtime merge. |
| `api/src/main/java/org/apache/cloudstack/api/response/StorageBlockTargetResponse.java` and file-share responses | Reuse existing runtime fields and document `UNAVAILABLE`; add no incompatible field or signature change. |
| `ui/src/views/storage/SharedFSTab.vue` | Project current device/backing path only from exact live observations and distinguish unavailable from unmapped. |
| `ui/public/locales/en.json`, `ui/public/locales/ko_KR.json` | Add parity-checked unavailable and runtime-observation diagnostic labels. |
| `plugins/storage/sharedfs/storagevm/src/main/resources/conf/fsvm-init.yml` | Make persistent DHCP the SharedFS NIC owner and disable/reset the unused generic networking unit safely. |
| `systemvm/debian/etc/systemd/system/ablestack-storage-reconcile.service` and DHCP unit emitted by `fsvm-init.yml` | Establish DHCP-before-network-online-before-storage-reconcile ordering. |
| DB schema and host agent | No change. Do not deploy or restart `mold-agent.service`. |

### Acceptance and deployment gates

- A nested `findmnt` fixture reports both managed mounts as `EXACT` and never
  duplicates their `/export/<name>` aliases.
- A failed inventory command yields `UNAVAILABLE`; a successful current snapshot
  with no matching volume yields `UNMAPPED`.
- Deployed iSCSI target/LUN `/dev/sdc` is returned as `runtimeBackingPath` with
  an exact mapping, effective size, current boot ID, and observation timestamp.
- After reboot and disk reorder, NFS/SMB current device paths follow stable
  identity and all four protocol read/write paths still pass.
- A new SharedFS VM has active persistent DHCP, no failed
  `networking.service`, restored secondary IPs, and all configured listeners.
- SystemVM tests, focused server tests, locale parity, UI unit tests, lint, and
  production builds pass.
- Management/API and UI are deployed together; the SystemVM template is rebuilt
  and published because collector and SharedFS boot ownership change. Served UI
  hashes, management class signatures, and template artifact checksums are
  verified before retest.
- No DB migration and no host-agent deployment are required.

## SharedFS Runtime Locale Completeness Closure (2026-07-22)

### Scope and confirmed cause

The integrated four-protocol runtime, API/DB projection, client I/O, and reboot
recovery are passing. This closure is limited to four literal i18n keys used by
`SharedFSTab.vue` that are absent from both runtime locale bundles:

- `message.storage.service.nfs.export.name.help`
- `message.storage.service.nfs.backing.path.help`
- `message.storage.service.iscsi.chap.credential.required`
- `message.storage.service.iscsi.mutual.chap.credential.required`

The NFS keys are rendered by export-create field tooltips. The iSCSI keys are
rendered by CHAP and mutual-CHAP validation failures. They are not SystemVM
messages and must not be solved by a guest-side fallback or by hard-coded text
in the Vue component.

The current `i18n:report` script scans `src/locales/**/*.json`, while the
runtime bundles are loaded from `public/locales/<locale>.json`. A successful
UI build therefore does not prove that literal storage-service keys used by the
component exist in the deployed locale files.

### AS-IS / TO-BE

| Concern | AS-IS | TO-BE |
| --- | --- | --- |
| NFS help text | Literal tooltip keys exist only in `SharedFSTab.vue`; a missing runtime translation can expose the raw key. | Add meaningful English and Korean values to both runtime locale bundles. Keep the tooltip call sites unchanged. |
| iSCSI validation | CHAP validation emits literal i18n keys that are missing from both bundles. | Add explicit one-way and mutual-CHAP credential validation messages in both locales. Never include entered credentials in the message. |
| Locale parity | A key can be added to one locale or to the component only without failing the build. | Add a focused locale-contract test that extracts literal storage-service `$t()` keys from `SharedFSTab.vue` and `CreateSharedFS.vue`, requires them in both bundles, and requires English/Korean storage-service key parity. |
| Existing report command | `i18n:report` points at a locale path that is not the runtime source of truth. | Correct the report path to `public/locales/**/*.json`, or replace it with a repository script that reads the two runtime files directly. The focused test remains the mandatory gate. |
| Deployment verification | App entry hash validation can pass even when locale files are stale or incomplete. | Treat `index.html`, hashed JS/CSS, `en.json`, and `ko_KR.json` as one release unit. Verify local/served hashes and query the four required keys from the served JSON. |

### Code-level targets

| Component/file | Required change |
| --- | --- |
| `ui/public/locales/en.json` | Add concise English NFS export-name help, NFS backing-path help, CHAP credential-required, and mutual-CHAP credential-required messages. |
| `ui/public/locales/ko_KR.json` | Add equivalent Korean messages in UTF-8 with the same keys and semantics. |
| `ui/tests/unit/views/storage/SharedFSLocale.spec.js` | New focused test. Parse both runtime locale JSON files, extract literal `label.storage.service.*` and `message.storage.service.*` `$t()` calls from `SharedFSTab.vue` and `CreateSharedFS.vue`, and fail on a missing key, empty value, raw-key value, or en/ko parity difference. |
| `ui/tests/unit/views/storage/SharedFSTab.spec.js` | Add focused assertions that the two CHAP validation branches select different translated messages and that no secret value is included in emitted UI text. Preserve existing protocol behavior tests. |
| `ui/package.json` | Point `i18n:report` at the runtime locale location or invoke the new locale-contract test explicitly. Do not make the legacy report the sole release gate. |
| Backend, API, DB, SystemVM, template, host agent | No change. The verified runtime data path must remain untouched. |

### Message semantics

- NFS export-name help explains that the client-visible root is
  `/<export-name>` and that the name must satisfy the existing Linux directory
  name validation.
- NFS backing-path help explains that the path must be exactly one level below
  `/export`, for example `/export/nfs01`.
- One-way CHAP validation asks for both username and secret without echoing
  either value.
- Mutual CHAP validation asks for the controller username and secret without
  echoing either value.
- Korean and English messages express the same constraint. A generic fallback
  such as `label.error` is not an acceptable replacement for these field-level
  validation messages.

### Preflight and acceptance gates

No Service VM code injection is required. The failure is reproduced entirely
from the deployed UI source and locale bundles, while all four protocol data
paths and reboot recovery have already passed. Guest mutation would not test
the affected layer.

1. `jq empty` passes for `en.json` and `ko_KR.json`.
2. The focused locale-contract test reports zero missing, empty, or asymmetric
   storage-service keys.
3. `SharedFSTab.spec.js` passes the NFS/iSCSI focused cases and existing
   protocol regression cases.
4. UI lint and the Node 16 production build pass using the established legacy
   OpenSSL provider setting when required.
5. The effective Management Server webroot and `GET /client/` return the same
   app-entry hash as the local build, and every referenced JS/CSS asset returns
   HTTP 200.
6. Served `locales/en.json` and `locales/ko_KR.json` hashes match the build and
   contain all four keys with non-key values.
7. Korean browser smoke checks open NFS export creation and exercise iSCSI CHAP
   validation without displaying `label.storage.service.*` or
   `message.storage.service.*` text.
8. No management service, host agent, DB migration, or SystemVM template build
   is part of this UI-only closure.
