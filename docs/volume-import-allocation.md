<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# GFS2 volume import allocation contract

Issue: https://github.com/ablecloud-team/ablestack-qemu-exec-tools/issues/87

The explicit import contract preserves the selected policy through stopped VM assembly and the first guest start. It does not infer a historical qemu command from sparse extents or accept a client `verified` flag.

## Client cache policy and incomplete verification

[Tools issue #92](https://github.com/ablecloud-team/ablestack-qemu-exec-tools/issues/92) changes new v2k/n2k jobs to `cachemode=none`, with explicit `--target-disk-cache writeback` support. Allocation policy still defaults to `falloc`. Clients create/reuse separate None offerings instead of modifying existing Writeback offerings. An explicit offering must match the requested cache, storage and allocation intent. ROOT and DATA are checked by final volume UUID, VM UUID, device index and positive `cachemode` evidence.

The existing `DiskOffering.CacheMode.NONE`, offering API, root/data import and KVM XML rendering already support this value; this increment requires no additional Mold API, schema, management jar or agent deployment. Existing sealed Writeback jobs keep their original intent. Live GFS2 validation checks API `cachemode=none` and running XML `cache='none'` for both tools.

Incomplete guest Verification does not roll back the imported VM/volumes or change their power state. The tool records `runtime.cloud.retention.status=RETAINED_UNVERIFIED` locally, with IDs, phase/reason, failure exit code and resume instructions, while complete/validated remain false. This is distinct from Mold's VM state and guest reconciliation status. `define-only`/explicit guest-policy `off` also remain unverified. Safety gates before first start continue to apply. Reconciliation uses the same sealed manifest/VM/volume identities without reopening managed images. A successful reconciliation resolves the retention marker while preserving its history.

The 2026-11-28 test intentionally uses tiny nonbootable/no-QGA fixtures to force a timeout after real Mold start. This qualifies cache propagation, failed-verification resource preservation and idempotent retry, **not guest OS boot or complete external-hypervisor migration**. See the paired Tools PR #88 validation report for resource IDs and limits. Earlier Windows/Linux boot qualifications retain their originally recorded Writeback policy.

## Public API

* `getVolumeAllocationCapabilities`: `storageid`, optional `hostid`, `allocationpolicy`, `allocationformat`, `operation=import`. A live storage worker must positively acknowledge the exact version/pool/format/method tuple. Import is separately authorized from ordinary create/copy/restore capabilities.
* `importVolume`: existing fields plus `allocationpolicy`, `allocationformat`, integer `allocationcontractversion=1`, canonical UUID `allocationrequestid`, boolean `allocationallowcopy`. All expectations must equal the explicit disk offering intent. Legacy input remains on the existing guarded import path.
* `verifyImportedVolumeAllocation`: root admin only; `id`, `allocationpolicy`, `allocationformat`, `allocationrequestid`. Stopped images are verified read-only. Running images return the recorded first-start receipt without offline image access. A previously started stopped image cannot regain a new first-start claim.

The request UUID becomes the registered volume UUID. It is persisted by clients before mutation. A matching committed request is returned rather than reimported. An uncommitted allocation copy uses the existing owned-stage receipt, byte comparison and atomic publish; retry verifies that same result. A conflicting UUID/scope is rejected.

## Qualified methods

| Format | Policy | Method and evidence scope |
| --- | --- | --- |
| qcow2/raw | off | adopt, `import-adopt-off`, import-time |
| qcow2/raw | falloc | adopt, `import-adopt-falloc`, import-time; observed allocation must cover virtual size |
| qcow2 | metadata | explicit approved copy, `qemu-preallocation-metadata`, creation-or-copy-time |
| qcow2/raw | full | explicit approved copy, `qemu-preallocation-full`, creation-or-copy-time |

GFS2 mount identity, actual qemu-detected standalone image format, virtual size, regular file, no backing/external data/encryption, source/final identity and final allocated bytes are checked by the storage worker. Metadata/full never zero or rewrite an unmanaged source in place. Copy approval is required because this consumes additional capacity and I/O. Unknown/old Agent and unsupported storage fail closed.

## Persistence and VM assembly

No table or column migration is added. Existing `disk_offering_details` stores the explicit `allocation.*` intent; existing `volume_details` stores the intent and trusted execution evidence. Import adds `allocation.import.requestid`, `requestpath`, `sourcepath`, `sourceidentity`, `finalpath`, `identity`, `strategy`, `pool`, `verifiedat`, `startedat` (each prefixed `allocation.import.`). Existing `allocation.*` backend/method/operation/observed fields remain authoritative. The existing source-format provenance detail is `driver-inspected`.

The strict import path applies the same disk offering ACLs, storage restrictions, custom size validation and account resource reservations as import. Ordinary legacy allocation guards are retained. ROOT assembly preserves the imported disk offering and inspected RAW/qcow2 format. The common first-start path verifies every tagged Ready volume against its actual final pool/path before starting. A failed check leaves the VM stopped. New verification never creates or repairs missing files. Successful first start records a historical scope; it is not continuous allocation enforcement after guest writes, discard, migration or later lifecycle changes.

## Validation

Selected Maven tests cover request/evidence validation, legacy import behavior, capability/lifecycle guards, real qemu data comparison for all seven format/policy combinations, source preservation, read-only verification, wrong format, sparse-as-falloc, symlink/unsupported filesystem, and Agent cwd `/`.

The operator-authorized cluster test uses synthetic BIOS/raw source bytes through the production v2k/n2k image-copy and Cloud cutover functions. Source VMware/Nutanix discovery is substituted; Mold API, DB, KVM Agent, GFS2, final images, VM assembly and first start are real. UI session authentication is held in memory; the production signed API parameters are unchanged. Test evidence must distinguish this target integration scope from a full external-hypervisor migration.
