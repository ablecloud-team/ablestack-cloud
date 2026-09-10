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

# SharedMountPoint QCOW2 DR Plan and UI Lifecycle Design

> 2026-09-01 cutover authority serialization and Failback readiness convergence:
> `625-dr-cutover-authority-commit-serialization-and-failback-readiness-convergence-design-20260901.md`
>
> 2026-09-03 dynamic placement and transparent worker scheduling:
> `627-dr-dynamic-placement-and-transparent-worker-scheduling-design-20260903.md`
>
> 2026-09-05 Test Cleanup operation/protection authority separation:
> `629-dr-test-cleanup-operation-and-protection-authority-design-20260905.md`

The Plan never pins a VM host, coordinator, or transfer worker. SharedMountPoint
workers are selected from the current eligible site pool for each Run/Cycle;
VM-local control resolves live placement only at command time. Any older clause
that describes `sourceHostUuid` as durable authority is superseded by design
627.

## 1. Authority and Test Object

The 31 ABLESTACK cluster owns DR site, plan, run, replica, and UI state. The 13
cluster is controlled through its registered Mold API and source Agent. The
only test object is the existing plan:

- plan: `rocky9-vm DR Plan`
- UUID: `41886f03-c19e-4382-927d-89bc4d6ce8e9`
- source VM: `48bdce4a-8bba-4984-80f1-46b1c92042cd`
- source/target storage: `/mnt/glue-gfs`, SharedMountPoint, qcow2

Updating the existing plan is allowed. Creating a replacement site or plan is
outside scope.

## 2. Inventory Corrections

Remote Mold inventory must use the same KVM conventions as local inventory:

- KVM VM with a non-empty `UEFI` detail is UEFI; `SECURE` means secure boot.
- KVM VM without a `UEFI` detail is BIOS/LEGACY, not unknown.
- RBD volumes are raw.
- SharedMountPoint file volumes are qcow2 unless the API supplies a more
  specific format.
- Source paths remain absolute canonical paths for the Agent profile.

The guided-spec preview must therefore remove
`SOURCE_HARDWARE_INVENTORY_REQUIRED` for the existing BIOS VM and emit a
file/qcow2 disk mapping.

Implementation is isolated in `DrMoldInventoryClient`: remote KVM inventory
uses the same absent-UEFI BIOS default as local inventory, and volume format is
resolved from an explicit API value, pool type, then path extension. Unit tests
preserve the existing UEFI and RBD/raw contracts.

## 3. Async UI/API Flow

All commands remain asynchronous:

1. UI submits an action and receives an accepted Cloud run.
2. Backend persists intent and dispatches Agent work.
3. Agent invokes FTCTL and returns acceptance/runtime evidence.
4. Cloud polling projects run, cycle, replica, and readiness state.
5. UI shows accepted, transferring, materializing, boot verification, and
   terminal result separately.

The UI never calls FTCTL, QMP, libvirt, or a remote Mold API directly.

## 4. Cloud-owned Materialization

Cloud creates/imports the target qcow2 volume in the selected SharedMountPoint
pool and creates the target VM only after a durable checkpoint. The mapping
keeps the absolute engine path while the Cloud volume row and resource
ownership records remain authoritative for VM lifecycle actions.

Firmware, secure boot, disk controllers, I/O threads, `io.policy=io_uring`, CPU,
memory, network, offering, and volume format are copied from the resolved
contract. Materialization must fail on a mismatch rather than silently create
a different VM.

## 5. UI Completion Matrix

The existing plan is exercised in the 31 UI in this order:

1. plan update/preview;
2. full synchronization;
3. automatic incremental synchronization and RPO display;
4. pause/resume and full resynchronization;
5. test failover and test cleanup;
6. failover;
7. reprotect and failback;
8. release with both target-retain and target-delete dispositions;
9. plan delete after resource disposition verification.

Each action passes only when UI terminal state agrees with API, DB, Agent,
FTCTL, target file, and target VM state. A modal close or accepted job is not a
PASS.

## 6. Dark Mode and Status

Existing DR components and tokens are reused. No raw JSON is shown. File/qcow2
provider, bitmap health, target export, and transfer progress are exposed as
typed labels. Warning and disabled states use the established dark-mode tokens;
hard-coded light backgrounds or black text are prohibited.

## 7. Regression and Deployment

- Changed Maven modules are built from a WSL ext4 clone.
- FTCTL packages are built through GitHub Actions.
- UI static assets are deployed without replacing the active webapp or
  deleting `WEB-INF`/`META-INF`.
- VMware-to-RBD and RBD-to-RBD action-contract suites are mandatory gates.
- Deployment markers and installed host scripts are verified on 13 and 31
  before the existing plan is run.
- The changed disaster-recovery Maven module must pass
  `DrMoldInventoryClientTest`; the FTCTL release must pass both the existing
  remote-RBD smoke and the new SharedMountPoint qcow2 smoke.
- Live preflight evidence must include a full seed, an actual guest write,
  bitmap-observed changed bytes, incremental application, and equal source and
  target logical hashes before the existing plan is submitted from the UI.

## 8. AS-IS / TO-BE

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Inventory | Remote BIOS and qcow2 unresolved | Local/remote KVM semantics identical |
| API | Guided spec contains hardware blocker/raw format | BIOS/LEGACY and file/qcow2 contract |
| Backend | Target transport assumes RBD wording/behavior | Provider-capability dispatch |
| Agent | Target file export not accepted | Validated SharedMountPoint qcow2 export |
| FTCTL | RBD-only incremental | QMP bitmap push backup for qcow2 |
| DB | Existing plan stores stale preview evidence | Same plan updated, no duplicate plan |
| UI | Cannot complete the existing plan | Full menu lifecycle with terminal evidence |

### 8.1 Stable source Detail and remote power transition contract

ABLESTACK-to-ABLESTACK hardware identity uses only VM Details that can be
replicated to the target. Runtime ownership and maintenance keys such as
`clone.fast.*`, `dr.*`, and `ftctl.*` are excluded from both the target Detail
set and the source hardware fingerprint. A lifecycle progress update must not
be reported as a source hardware change.

Planned Failover also requires the remote source Mold to accept and complete
its asynchronous `stopVirtualMachine` job before target promotion. Cloud must
poll `queryAsyncJobResult`, preserve the remote error code and message, and
only then confirm `POWERED_OFF`. A SharedMountPoint clone flatten dependency
therefore appears as an actionable readiness failure instead of a generic
power-state timeout. The target remains powered off while this condition is
unresolved.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Hardware identity | Transient clone/DR state can change the fingerprint | Fingerprint contains only replicable VM Details |
| Remote Mold control | Ignores async job result and polls VM state for 60 seconds | Polls the job first and preserves its exact terminal failure |
| Cutover safety | Repeats source stop and reports a generic timeout | Blocks promotion with the concrete source lifecycle dependency |
| UI | Stays at 70% without an actionable reason | Shows the remote stop failure and required operator action |

While the dependency remains retryable, the accepted Failover Run stays
`RUNNING` at `source-isolation-wait`; it is not falsely failed and the target
is not promoted. The Run and its source-isolation step carry
`DR_SOURCE_CLONE_FLATTEN_ACTIVE` plus the Mold error message. Once flatten
finishes, the same Run retries source isolation and clears the warning during
successful terminal reconciliation.

## 9. Source format contract and failed-seed recovery

The first UI-driven full synchronization of the existing plan proved that the
source volume path does not carry a filename extension. The file is qcow2, but
the guided-spec sanitizer discarded the inventory `format` field. FTCTL then
treated the empty format as raw, the full seed failed before transfer, and the
next scheduler attempt incorrectly selected incremental mode solely because
the sequence number had advanced.

The corrected contract is intentionally narrow:

- the UI preserves the source inventory `type` and `format` in both the flat
  disk mapping and nested `source` object;
- Cloud guided-spec sanitization preserves those fields;
- FTCTL may inspect a missing format with `qemu-img info --force-share` only
  for an ABLESTACK-to-ABLESTACK local file source;
- VMware/VDDK and RBD sources are never inferred or rewritten;
- an ABLESTACK-to-ABLESTACK plan without a durable completed checkpoint always
  retries a full seed, regardless of the failed sequence number;
- provider-specific failures identify the ABLESTACK mover instead of the
  VMware mover.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| UI | Source type/format are dropped when disk rows are rebuilt | Inventory type/format survive create and edit serialization |
| Cloud | Guided spec keeps target format only | Source and target type/format are preserved |
| FTCTL | Extensionless qcow2 becomes an empty format and falls through as raw | ABLESTACK file source is inspected and canonicalized before dispatch |
| Scheduler | Failed sequence 1 is followed by incremental sequence 2 | No durable baseline means full seed retry only |
| Observability | Native replication failure is attributed to `vmware-mover` | Failure component follows the selected provider |

## 10. Remote KVM source observation

The controller-local `dr_plan.source_worker_host_id` remains a foreign key for
hosts owned by the controller Mold only. It must not contain a host ID from a
remote source Mold. For a remote ABLESTACK source, signed Mold inventory is
authoritative for the VM's current placement observation. That observation is
not worker authority and must not be required in the guided mapping. A host
UUID/name may be displayed with its observation time, but Cloud must resolve
placement again for an actual VM-local command and schedule SharedMountPoint
work independently.

KVM virtual machines without an explicit UEFI detail use the existing Cloud
contract: no UEFI detail means BIOS/legacy firmware and Secure Boot is false.
The absence of the optional UEFI detail must not discard otherwise valid source
host authority. `DrPlanResponse` therefore exposes the remote source worker
UUID and name separately, and the UI displays the local host ID or the remote
name/UUID as appropriate.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Source inventory | Missing optional UEFI data replaces valid host data with an inventory error | Remote KVM defaults to BIOS and preserves host UUID/name |
| Plan persistence | Remote host cannot be represented by the local host foreign key | Do not persist remote placement as routing authority; retain only optional timestamped observation |
| API | Only controller-local `sourceworkerhostid` is exposed | Runtime response exposes current observation and independently selected worker lease |
| UI | Remote source worker is shown as `-` | Show automatic placement readiness and read-only current runtime assignment |
| Existing-plan edit | Missing host evidence can invalidate a valid Plan | Host presence is not Plan completeness; refresh placement only when a VM-local command needs it |
| Direction vocabulary | Refresh gating recognizes only the internal `KVM_TO_KVM` value | Refresh gating accepts the API/UI `ABLESTACK_TO_ABLESTACK` value and the mapping's internal `KVM_TO_KVM` value while excluding VMware sources |
| Detail placement display | The source worker row implies durable authority | Separate last-observed VM placement from the current coordinator/transfer lease |

## 11. Existing-plan-only validation and completed transfer telemetry

Validation reuses the operator-created plan
`41886f03-c19e-4382-927d-89bc4d6ce8e9`. The implementation and test flow must
not create a replacement site or DR plan.

Each SharedMountPoint full seed records per-disk transferred bytes in the
durable checkpoint. The qcow2 bitmap push path uses the provider result
`changedBytes`; other ABLESTACK full-seed paths use the resolved source virtual
size as the conservative transfer value. The scheduler then publishes those
checkpoint values as `latest_completed_*`, Cloud projects the canonical
completed Cycle into `dr_sync_cycle`, and the protection UI reads that Cycle.

`READY` with a completed non-empty full seed displayed as `0 B` is not a UI
PASS when the FTCTL progress journal proves a non-zero transfer. UI PASS
requires the completed Cycle sequence, mode, byte counts, and durability
timestamps to agree across FTCTL, Cloud DB, and the protection tab.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| FTCTL full seed | Progress journal has bytes but the durable checkpoint omits them | Aggregate per-disk bytes and persist changed/read/written/payload metrics |
| Cloud Cycle | Completed Cycle receives `NULL` metrics | Canonical Cycle receives checkpoint metrics during normal projection |
| UI | Missing metrics are rendered as `0 B` | The existing plan shows the completed full-seed transfer amount |
| Regression scope | A telemetry fix could alter another provider path | Change is limited to ABLESTACK full-seed checkpoint creation; VMware and RBD mechanics remain unchanged |

## 12. Zero-change incremental cycle contract

The existing plan's source scheduler completed automatic SharedMountPoint
qcow2 cycles, but Cloud retained Cycle 5 while FTCTL advanced through later
cycles. The periodic projection scheduler was running; its Agent status
validation rejected each completed cycle as
`DR_STATUS_CYCLE_EVIDENCE_CONFLICT` because the checkpoint reported zero
changed and written bytes with `effectiveMode=CBT_INCREMENTAL`.

The shared DR status contract already defines a zero-byte durable cycle as
`NO_CHANGE`. The contract validator must remain strict because weakening it
would also change the validated VMware-to-RBD and RBD-to-RBD paths. The
ABLESTACK driver therefore normalizes only its completed-cycle evidence:

- `requestedMode` remains `CBT_INCREMENTAL` because the scheduler requested an
  incremental cycle;
- `effectiveMode` is `NO_CHANGE` when the aggregate changed byte count is zero;
- `effectiveMode` remains `CBT_INCREMENTAL` when at least one byte changed;
- durability, bitmap advancement, Cycle token, and NBD teardown evidence are
  still required and are not inferred by Cloud;
- the rule applies uniformly to ABLESTACK remote RBD, SharedMountPoint qcow2
  bitmap push, and site-agent NBD implementations.

After deployment, the existing Plan must converge without DB repair: the next
automatic cycle is projected, stale active Cycle aliases are superseded, the
Plan runtime reaches `READY/IDLE`, and the cached protection view follows the
latest completed sequence. No replacement Site or Plan may be created.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| FTCTL | Zero-byte incremental completion is labeled `CBT_INCREMENTAL` | Requested mode stays incremental; effective mode is `NO_CHANGE` |
| Agent | Correctly rejects zero-byte `CBT_INCREMENTAL` evidence | Existing strict validation accepts the corrected `NO_CHANGE` evidence |
| Cloud | Projection cache remains on a stale active Cycle | Periodic projection consumes the next valid completed Cycle |
| DB | Plan runtime sequence trails the source scheduler | Runtime and canonical Cycle advance atomically without manual repair |
| UI | Existing Plan appears indefinitely syncing | Existing Plan converges to `READY/IDLE` and shows the latest durable Cycle |

## 13. SharedMountPoint full-reseed byte authority

The existing Plan's UI-triggered full reseed completed in QEMU, but the
durable checkpoint used `changedBytes=0` from bitmap initialization even
though the same provider result contained non-zero `bytesProcessed`,
`sourceReadBytes`, and `targetWrittenBytes`. The strict Agent contract then
correctly rejected a zero-byte `FULL_SEED` completion as conflicting evidence,
leaving Cloud Cycle 15 in `TRANSFERRING` after the request Run had succeeded.

The common Agent and Cloud validators remain unchanged. For an ABLESTACK
SharedMountPoint full seed, FTCTL selects the first positive value from the
provider's target-written, source-read, processed, payload, and changed-byte
fields. A non-qcow2 provider still uses the resolved virtual-size fallback.
Incremental byte accounting and the previously validated VMware and RBD paths
are not changed.

After deployment, validation reuses Plan
`41886f03-c19e-4382-927d-89bc4d6ce8e9` only. A new UI full reseed must publish
non-zero full-seed metrics, allow the Agent to accept the terminal Cycle, mark
the previous incomplete alias `SUPERSEDED`, and converge the protection view
to `READY / IDLE` without direct DB repair.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| FTCTL qcow2 full seed | Persists bitmap `changedBytes=0` despite a completed full copy | Persists the actual processed/read/written byte count |
| Agent | Rejects zero-byte `FULL_SEED` as an evidence conflict | Accepts unchanged strict contract after provider evidence is corrected |
| Cloud | Request Run succeeds while its Cycle remains `TRANSFERRING` | Canonical Cycle is completed and stale alias state is superseded |
| UI | Shows `RESEEDING / DEGRADED` after the copy finishes | Shows non-zero transfer metrics and returns to `READY / IDLE` |

## 14. SharedMountPoint replica locator and test artifact authority

The existing Plan `41886f03-c19e-4382-927d-89bc4d6ce8e9` exposed a path
authority defect after its full seed and automatic incremental cycle completed.
The target pool was `/mnt/glue-gfs` with type `SharedMountPoint`, while the
Cloud volume path and disk mapping intentionally stored the pool-relative path
`rocky9-vm-dr-disk-0`. The target Agent passed that relative path directly to
`qemu-nbd`, whose working directory was `/`; the resulting replica was opened
as `/rocky9-vm-dr-disk-0` instead of the Cloud-managed
`/mnt/glue-gfs/rocky9-vm-dr-disk-0`. Test failover then correctly rejected the
relative artifact locator before creating a test VM.

The corrected contract keeps the successful provider paths isolated:

- for `SharedMountPoint` file disks only, a pool-relative target path is
  resolved beneath the absolute pool root before any file creation or export;
- an already absolute file path is preserved;
- an empty pool root, an absolute-path escape, `..` traversal, or a result
  outside the configured pool is rejected before `qemu-nbd` starts;
- RBD locator construction and the validated VMware-to-RBD and RBD-to-RBD
  paths are unchanged;
- the Cloud test artifact manifest applies the same normalization, so the
  test clone reads the same durable file that the target export writes and the
  Cloud target volume resolves;
- the target export status returns the canonical absolute `targetPath` and the
  UI reports test failover success only after the test VM is created and its
  configured boot validation succeeds.

Deployment validation must stop the stale target export that holds the root
filesystem file, install the patched FTCTL and Cloud classes, and reuse the
same Plan for a UI full reseed. PASS requires the new export process and
checkpoint to reference `/mnt/glue-gfs/rocky9-vm-dr-disk-0`, followed by UI
test failover, test cleanup, failover, and failback. No replacement Site or DR
Plan may be created. The obsolete root-level file may be removed only after
the corrected GFS replica is durable and no process has it open.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| FTCTL target export | Opens the relative target path from `/` | Opens a validated absolute path below `/mnt/glue-gfs` |
| Cloud artifact spec | Rejects the relative mapping without resolving the pool root | Emits the same canonical SharedMountPoint file locator as FTCTL |
| Target volume | Logical Cloud path and physical export diverge | Cloud pool root plus volume path equals the physical replica |
| UI test failover | Request Run fails before a test VM exists | Progress reflects artifact preparation, VM creation, boot validation, and terminal completion |
| Regression scope | A generic file-path relaxation could affect other providers | Normalization is limited to SharedMountPoint; RBD and VMware contracts remain unchanged |

## 14.1. SharedMountPoint Immutable Checkpoint Transition Boundary

The controller must establish a stable FILE checkpoint before it asks FTCTL to
materialize a Test Failover disk. Request acceptance and a Running libvirt VM
are not sufficient. The existing Plan remains the only validation object. This
rule is selected from the target storage contract, not from the source
provider: VMware-to-SharedMountPoint and KVM-to-SharedMountPoint use the same
immutable FILE boundary after their provider-specific capture has committed.

For every SharedMountPoint target plan, Cloud owns this transition. A remote
`KVM_TO_KVM` plan pauses its source scheduler through the source Site Agent; a
locally managed VMware plan pauses the VDDK data-plane scheduler through the
automatically selected local coordinator:

1. submit and persist the asynchronous Test Failover Run;
2. pause the provider scheduler through its owning Agent and wait for a
   terminal pause acknowledgement;
3. reread the latest target-ready restore point after the pause and bind that
   sequence and token to both the request and artifact specification;
4. stop and drain a Plan-owned forward target export when that transport is in
   use;
   its checkpoint sequence identifies the immutable Test Failover boundary and
   never creates a reverse Failover baseline;
5. dispatch `TEST_PREPARE` with the same latest durable sequence and
   checkpoint reference in both request and artifact contract;
6. project FTCTL `checkpoint_lease_state=HELD`,
   `test_checkpoint_seal_state=SEALED`, and
   `test_checkpoint_integrity_state=PASSED` before target VM creation;
7. continue through Cloud volume import, test VM creation, and configured boot
   validation;
8. on Test Cleanup or pre-materialization failure, restart the applicable
   transport and resume the same provider scheduler without DB repair.

The provider-specific capture remains unchanged. VMware-to-RBD and RBD-to-RBD
retain their existing action order, while VMware-to-SharedMountPoint adds the
FILE boundary only after the existing VMware durable checkpoint. A FILE
transition failure must compensate only
resources that were acquired by that Run and must preserve the original
structured error.

The UI presents the sequence as **검증 체크포인트**, the seal as **불변
체크포인트**, and the filesystem result as **게스트 파일시스템 일관성**.
Test Failover cannot be shown as successful until all three are authoritative
and the Cloud test VM boot-validation state is terminal `PASSED`.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Cloud transition | Target writer may remain active during FILE copy | Remote scheduler pause and target writer drain are barriers |
| Agent/FTCTL | Mutable locator can be copied before lease | Lease and immutable sequence seal precede materialization |
| API evidence | Artifact state does not prove guest consistency | Sequence, seal, integrity, and boot states are typed fields |
| UI | Running VM can appear as Test Failover success | Checkpoint integrity and boot completion are separate mandatory gates |
| Cleanup | FILE transport can remain paused | Cleanup restores export and automatic protection |

The export-stop contract is purpose-aware. `TEST_FAILOVER` uses the durable
sequence only in the artifact request, lease, seal metadata, and UI evidence.
`FAILOVER` may pass the cutover sequence to FTCTL because that transition owns
the reverse baseline needed by a later Failback. Mixing those contracts makes
an otherwise normal initial export drain fail before checkpoint sealing, so
Cloud strips the sequence for Test Failover and FTCTL independently rejects
reverse-baseline work when the profile action intent is `TEST_FAILOVER`.

## 15. Artifact-free failed test session terminalization

The existing Plan must remain the only validation object. A failed test
failover Run can stop before FTCTL creates an artifact, Cloud volume, or test
VM. In that case the `dr_test_session` row previously remained `REQUESTED`, so
the UI incorrectly replaced **Test Failover** with **Test Cleanup** even though
there was nothing to delete.

Cloud now treats a test session as artifact-free terminal history only when all
of the following facts agree: its owning Run is `FAILED` or `CANCELED`, the
session is still before materialization, `artifact_manifest` is empty,
`target_vm_id` is null, and `cleanup_required=false`. The Run executor closes
the session with the Run failure in the same failure flow. Action evaluation
ignores a legacy row that satisfies the same strict proof, and submitting a new
UI test on the existing Plan soft-closes that legacy row transactionally before
creating the replacement session.

Any artifact manifest, target VM, cleanup obligation, or cleanup failure keeps
the existing **Test Cleanup** requirement. No DB repair, new Site, or new Plan
is part of the recovery path.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Cloud Run | Adapter failure terminates only `dr_run` | Artifact-free `dr_test_session` is terminated with the failed Run |
| Action availability | Stale `REQUESTED` session is treated as an active test | Terminal artifact-free history does not block **Test Failover** |
| Orchestrator | New test request is rejected by the stale row | The stale row is safely soft-closed in the existing-Plan transaction |
| Resource safety | Operator is offered cleanup with no resources | Cleanup remains mandatory whenever any artifact or target VM exists |
| UI validation | Existing Plan shows **Test Cleanup** after a pre-artifact failure | Existing Plan shows **Test Failover** and verifies real VM creation before success |

## 16. SharedMountPoint test failover disk isolation

The target qcow2 can remain open by the paused replication writer. A test
artifact must therefore not use that mutable file as a backing image. For a
`FILE` artifact, FTCTL produces and validates an independent `qcow2-copy` from
the latest durable checkpoint using force-share read access. Cloud materializes
the test volume from the published copy path and preserves the existing RBD
snapshot/clone contract for RBD plans.

UI success remains terminal: request acceptance is not success. The test
failover is complete only after the independent disk is prepared, the Cloud
test VM exists, and the configured boot validation succeeds. Test Cleanup
removes the test VM and the independent copy but never the durable replica.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| FTCTL FILE artifact | Mutable durable target is used as a backing file | A validated independent sparse qcow2 copy is published |
| Replication safety | Later target writes can affect a running test | Scheduler pause plus independent copy freezes the tested checkpoint |
| Cloud materialization | No VM is created when backing-file locking fails | Test volume and VM are created from the independent copy |
| Cleanup | Overlay cleanup is assumed | Only the generated copy and test VM are removed |
| Existing providers | Shared implementation can alter RBD behavior | RBD snapshot/clone and VMware paths remain unchanged |

## 17. Test Failover guest preparation preflight projection

The existing SharedMountPoint Plan failed before independent qcow2 copy
creation with the generic `DR_GUEST_PREP_RUNTIME_UNAVAILABLE` code. Cloud and
the UI must preserve the exact FTCTL prerequisite failure instead of replacing
it with a combined runtime/ISO message.

FTCTL publishes `guest_preflight_state`, `guest_preflight_error_code`, and
`guest_preflight_error_message`. Cloud promotes the specific code and message
to the owning Run, Run step, and test session. The UI translates known codes
and falls back to the safe backend message for future codes.

Linux plans do not require the Windows WinPE or virtio ISO. Target-host
preflight therefore passes for Rocky Linux when the test session, manifest
tool, and v2k runtime are present. A failed prerequisite remains terminal and
offers Test Cleanup only when the session proves cleanup is required.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| FTCTL | exit 47 with one generic code | exact session/tool/v2k/profile/ISO code and message |
| Cloud | generic runtime/ISO projection | preserves specific FTCTL evidence |
| UI | opaque failed execution | localized actionable cause on Run and step |
| Validation | target package drift is discovered after acceptance | host prerequisites and hashes are checked before UI retest |

## 18. Qcow2 Copy Consumption and Accepted-Run Terminal Convergence

SharedMountPoint Test Failover is an asynchronous operation. FTCTL first
accepts the command and then produces, prepares, and boots the test object. The
published file artifact type is `qcow2-copy`; Cloud must not report success at
request acceptance, and a terminal FTCTL error occurring after acceptance must
converge promptly into the Run and test session.

FTCTL preserves the manifest helper's original `error_code`, `error_message`,
and exit code. Cloud performs a bounded background projection watch for every
accepted finite Test Failover Run in addition to the periodic projection
scheduler. The watch stops when the Run is terminal, removed, or its retry
budget expires. It never blocks the UI request thread.

When FTCTL reports terminal failure, Cloud updates the owning Run, current
step, and `dr_test_session` from the same structured evidence. The UI displays
request acceptance, disk preparation, VM creation, boot verification, and
terminal completion as separate states. A successful notification is allowed
only after the Cloud test VM exists, is Running, and the configured boot
validation has passed.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| FTCTL artifact contract | Producer emits `qcow2-copy`, consumer rejects it | Producer and consumer share the canonical `file/qcow2` copy contract |
| Cloud projection | One best-effort refresh at acceptance can miss a later failure | Bounded asynchronous projection watch plus periodic reconciliation |
| Run/session error | Generic or stale ACCEPTED state | Original terminal code and message atomically projected |
| UI progress | Acceptance can appear equivalent to completion | Acceptance, artifact, VM, boot, and terminal states are distinct |
| PASS gate | Request accepted | UI history `SUCCEEDED`, real test VM Running, boot validation `PASSED` |
| Regression scope | Shared status logic may affect validated paths | Changes are limited to finite Test Failover convergence; RBD and VMware behavior is retained |

## 19. Dual-site Cloud Agent command-contract parity

Remote-source checkpoint fencing is a Cloud-to-Cloud operation, but the final
`PAUSE_SYNC` and `RESUME_SYNC` commands are deserialized and executed by the
source site's Mold Agent. Deploying the management plug-in without deploying
the matching Cloud Agent command and KVM wrapper classes leaves the remote API
reachable while every checkpoint barrier fails before FTCTL is invoked.

Both source and target sites must therefore use one test-release contract. The
deployment gate compares the installed Cloud core command classes, KVM wrapper
classes, FTCTL capability response, and action-contract version on every
participating host. Management and Agent services are restarted only after
their matching artifacts are present. A remote pause preflight must prove that
the scheduler acknowledges a new control generation and returns to running
after resume before the UI exposes Test Failover as ready.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Source management | FTCTL service module can be added independently | Service module and schema DAO beans are packaged in the release |
| Source Agent | Older Agent cannot deserialize `FtctlDrActionCommand` | Core command and KVM wrapper classes match the management contract |
| Deployment | Management HTTP 200 is treated as sufficient | Both sites pass command-class, capability, service, and remote pause/resume gates |
| Runtime evidence | Pause timeout is reported as engine unavailability | Agent deserialization errors are terminal deployment incompatibility evidence |
| UI retest | Test Failover can be submitted against an incompatible source | Readiness is granted only after the remote scheduler barrier preflight passes |

## 20. FILE checkpoint publication and UI success gate

The 2026-08-29 UI retest demonstrated that Cloud `Running` and FTCTL
`POWER_STATE_VALIDATED` cannot compensate for a filesystem-inconsistent FILE
checkpoint. A test VM reached Rocky Linux emergency mode even though the Run
and test session had been marked successful. Inspection found `/boot` XFS
`Structure needs cleaning` and a byte mismatch between the immutable
checkpoint and the drained canonical replica.

Cloud keeps the existing asynchronous workflow, but FILE test materialization
is now governed by a stronger FTCTL contract:

1. remote source scheduler paused and acknowledged;
2. target export stopped and writer drain acknowledged;
3. selected durable sequence leased;
4. canonical target proven locally unwritable;
5. qcow2 container copied with sparse/reflink preservation into a temporary
   checkpoint, byte-compared, and guest-filesystem inspected;
6. immutable checkpoint atomically published;
7. Cloud test VM created from a disposable overlay only after FTCTL reports
   `checkpointSealState=SEALED` and `checkpointIntegrityState=PASSED`.

The UI operation history must preserve the structured FTCTL error when any
gate fails. It must not show Test Failover success merely because the VM power
state is `Running`. For the no-QGA case, the deterministic boot-safety claim is
the checkpoint publication gate; power-state validation remains a separate
runtime observation. Operator validation still inspects the Cloud console
during the release test.

Existing invalid immutable files are not part of normal Test Cleanup. They are
failed engineering artifacts and are removed only after their active test
session and lease have been safely cleared. This prevents routine customer
cleanup from absorbing migration debris.

The change is FILE/SharedMountPoint-only. RBD and VMware action contracts,
artifact ownership, and cleanup behavior are unchanged.

## 21. Terminal Run and active test-session UI authority

A completed `TEST_FAILOVER` Run and its still-active test session represent two
different facts. The finite Run is terminal and must never expose `cancelRun`;
the active test session owns the disposable VM and must expose
`stopTestFailover` until cleanup completes.

The versioned protection-view projection and `listDrPlans` action availability
are authoritative for the action menu. After a terminal Run is observed, the
UI must clear any older cached `activeRun`, retain the terminal Run only as
history, and render Test Cleanup from the active session. Reopening the menu or
polling the detail page must not preserve a pre-terminal menu closure.

The deployment gate therefore verifies all of the following through the active
web application:

1. `cancelRun` is absent after `TEST_FAILOVER/SUCCEEDED`;
2. `stopTestFailover` is visible and enabled while the test session is ACTIVE;
3. Test Cleanup transitions the session to CLEANED, removes the disposable VM
   and overlay, and resumes protection scheduling;
4. a new durable checkpoint is produced before Test Failover becomes available
   again.

This UI projection rule is independent of checkpoint contents. It prevents a
correct Cloud/FTCTL terminal state from being hidden behind a stale browser
action menu while retaining the asynchronous backend contract.

## 22. Checkpoint invariance deployment and UI validation

The FILE/SharedMountPoint checkpoint-invariance patch was validated on the
13-to-31 test path with plan
`41886f03-c19e-4382-927d-89bc4d6ce8e9`. The FTCTL release artifact was built
by GitHub Actions run `33193935352` from qemu commit `72daf0a` and installed on
all 13 and 31 compute hosts. Cloud UI commit `db5307d897` was deployed to both
management servers while preserving `WEB-INF`.

The accepted Test Failover Run
`a46edf36-4127-4a52-a05c-337f3d189f33` consumed immutable checkpoint sequence
`280`. Its version-2 metadata binds the source path, file size, mtime, virtual
size, and contract digest. `qemu-img compare` against the drained canonical
replica and `qemu-img check` both passed before Cloud materialization.

The UI operation history converged to `TEST_FAILOVER / SUCCEEDED / 100%` only
after test VM `i-2-164-VM` existed and was Running. The Cloud console showed a
Rocky Linux 9.3 login prompt, rather than the former emergency-mode boot. This
is the release evidence that the immutable checkpoint, not a repaired test
artifact, supplied a bootable guest image.

UI Test Cleanup Run `941267da-8680-4c90-ae29-139622f24acc` then converged to
`SUCCEEDED / 100%`. The test session became `CLEANED`, its VM became
`Expunging`, its overlay volume became `Expunged`, and active plan leases
became zero. Because this plan was paused before the final test, the operator
used the UI `Resume Sync` action. The plan subsequently converged to `READY`,
the scheduler to `RUNNING`, the cycle to `IDLE`, and freshness to
`WITHIN_RPO` after durable incremental sequence `281`.

One environment prerequisite was also exposed during validation: the first
post-patch materialization failed because the target host lacked libvirt base
iptables chains. Restoring the standard libvirt chains on all 31 compute hosts
allowed the same immutable-checkpoint path to pass. This host repair is not a
normal Test Cleanup responsibility and does not alter the checkpoint contract.

The validated release gate is therefore:

1. FTCTL checkpoint metadata version 2 is present;
2. canonical-to-checkpoint compare and qcow2 structural check pass;
3. UI history reports Test Failover `SUCCEEDED` only after VM and boot gates;
4. the Cloud console reaches the guest login prompt;
5. UI Test Cleanup removes only disposable VM/overlay/session resources;
6. plan protection returns to `READY/RUNNING/WITHIN_RPO` with no active lease.

## 23. Actual Failover reverse-baseline barrier

UI actual Failover is not complete when the final delta alone is durable. For
SharedMountPoint qcow2, Cloud must wait for FTCTL to establish a persistent
reverse dirty-bitmap baseline on the promoted target disk before it creates or
starts the target VM and commits TARGET authority.

The 2026-08-29 UI Run `ed096e21-8bab-4760-b912-7c2c64da501c` exposed the former
gap: final delta checkpoint 344 was durable and the source VM was powered off,
but `TARGET_EXPORT_STOP` repeatedly returned exit 32 because the FTCTL target
worker invoked the RBD-only snapshot baseline helper for a FILE disk. The UI
correctly remained at `final-delta-apply` and the target VM remained powered
off; this state is not a successful Failover.

The corrected lifecycle is:

1. Cloud keeps the Run non-terminal while the target writer drains.
2. FTCTL resolves the retained target volume under its SharedMountPoint root,
   verifies no writer, and creates or validates the Plan/disk persistent qcow2
   bitmap.
3. FTCTL returns `reverse_baseline_state=READY` idempotently.
4. Cloud starts the target VM, validates the configured boot contract, commits
   TARGET authority, and only then marks the Run `SUCCEEDED`.
5. UI history and protection information show the accepted checkpoint,
   promoted VM power state, boot state, and terminal Run. Request acceptance or
   source power-off alone never renders success.

RBD and VMware paths retain their existing reverse-baseline implementation.
The FILE provider branch is a strict capability dispatch and cannot fall back
to RBD helpers.

## 24. Pre-action capability consistency gate

Agent capability discovery is an action-availability input, not an execution
failure mechanism. Cloud caches the authoritative FTCTL capability response
per dispatch endpoint for 30 seconds and evaluates the command surface before
publishing `actionAvailability` to the UI. The cache prevents a plan list with
many rows from issuing one Agent command per row while keeping package rolling
updates visible within a bounded interval.

For every FTCTL control action, Cloud requires the advertised action name and
CLI command. Reprotect additionally requires the Cloud writer's authority
contract version in `reprotect_authority_contract_versions`. A missing answer,
missing command, or incompatible contract makes only the affected action
unavailable with a stable reason code and arguments. The UI keeps the action
visible when it is state-applicable, disables it, and explains the compatibility
failure before the confirmation dialog can submit a Run.

The API command validates the same typed availability immediately before Run
creation. The adapter repeats capability validation immediately before Agent
dispatch as a TOCTOU guard. These are the same contract at three points, not
three independently maintained version literals.

| Layer | Responsibility |
| --- | --- |
| FTCTL | Publish supported commands, features, and reader contract versions |
| Cloud availability | Cache endpoint snapshot and compute action-specific readiness |
| UI | Render enabled/disabled state and localized blocking reason |
| API/adapter | Revalidate the same result before Run creation and dispatch |

The release gate covers VMware-to-RBD, ABLESTACK RBD-to-RBD, and
SharedMountPoint qcow2-to-qcow2 so a shared contract change cannot ship as a
single-path fix.

## 25. ABLESTACK-to-ABLESTACK VM Detail preservation contract

Guest operating-system detection is not the authority for target VM hardware.
For every `KVM_TO_KVM` Plan, Cloud snapshots the source
`vm_instance_details` values through the local DAO or the remote Mold API and
stores that snapshot under `source.hardware.vmDetails`. The snapshot is part
of the source hardware fingerprint and is refreshed by the read-only
materialization preflight before a replica or test VM is created.

Cloud copies the source Detail values to the target before adding DR-owned
metadata. The exclusion boundary is deliberately small and provider-scoped:

- `clone.fast.*` is a source-side transient clone operation;
- `ftctl.*` and `dr.*` belong to the source protection or target DR lifecycle;
- `volumeId` and `deployvm` are generated for the target Cloud resource;
- legacy `boot.mode` is not an authoritative KVM Detail.

Every other source Detail, including `UEFI`, `tpmversion`, `io.policy`,
`iothreads`, disk-controller settings, and supported future Detail keys, keeps
its exact key and value. The official Cloud firmware rule remains: a source
`UEFI=LEGACY` or `UEFI=SECURE` value is copied as-is; absence of `UEFI` means
BIOS and must not create a synthetic `BIOS`, `bootType`, or `bootMode` Detail.

The target stores `dr.source.vm.details.keys` as the manifest of copied keys.
Reconciliation can therefore add changed values and remove only previously
copied keys that disappeared from the source without touching target-owned
details. The same reconciliation transaction also replaces
`dr.source.hardware.fingerprint` with the fingerprint computed from that
source snapshot. Updating copied values without their fingerprint leaves a
semantically current replica looking stale and is forbidden. Target
materialization and test failover verify every manifest value before reporting
hardware readiness. A mismatch is
`TARGET_VM_DETAIL_MISMATCH` and blocks boot rather than guessing from the guest
OS.

This contract applies equally to Windows and Linux SharedMountPoint or RBD
ABLESTACK-to-ABLESTACK Plans. VMware inventory and its validated target
mapping remain unchanged and do not receive an empty KVM Detail snapshot or a
different fingerprint.

### Action-time snapshot ordering and checkpoint readiness (2026-08-29)

For a remote `KVM_TO_KVM` SharedMountPoint Test Failover, source hardware
inventory is refreshed before `FtctlDrActionCommand` and its profile are built.
Refreshing only during later Cloud VM materialization is invalid because the
FTCTL checkpoint session would already be bound to stale source Detail values.
An unavailable or incomplete source inventory blocks the action before the
writer-drain transition with a stable source-inventory reason.

Source lifecycle metadata remains outside the hardware fingerprint and target
VM Detail manifest. When the source reports `clone.fast.status` or
`clone.fast.flatten.status` as `pending` or `running`, the common KVM-to-KVM
action preflight returns `DR_SOURCE_CLONE_FLATTEN_ACTIVE` before dispatching an
FTCTL cutover command. Runtime projection applies the same reason if a clone
dependency appears after acceptance, keeps the Run retryable at source
isolation, and must not promote the target while the source VM is still
running.

The resulting order is:

1. resolve the remote source VM and authoritative VM Detail values;
2. persist the refreshed `source.hardware` snapshot and fingerprint;
3. build the action profile and immutable checkpoint request from that snapshot;
4. pause and drain the SharedMountPoint writer;
5. publish only a qcow2 checkpoint that passes the provider-specific guest
   filesystem gate;
6. reconcile the same Detail manifest onto the test VM before boot.

Windows readiness is independent of QGA. FTCTL proves qcow2 container equality,
read-only NTFS root access, and the Windows SYSTEM registry hive before Cloud
creates a VM. A missing libguestfs filesystem helper is reported as
`DR_TEST_CHECKPOINT_GUEST_FS_DRIVER_UNAVAILABLE`, while actual dirty or
unreadable guest metadata is
`DR_TEST_CHECKPOINT_GUEST_FS_INCONSISTENT`. The UI must display the original
reason and never report Test Failover success until VM creation, power-state
validation, and terminal Run projection all succeed.

## 26. Planned KVM failover checkpoint ordering

A planned `KVM_TO_KVM` Failover must create its final checkpoint from an
immutable source. Producing the final delta while the source VM is still
running and attempting source isolation afterward is invalid: an operational
delay such as SharedMountPoint clone flatten can resume the scheduler, advance
the durable sequence, and leave the cutover manifest bound to an older
checkpoint.

The Cloud-owned order is therefore fixed as follows:

1. refresh source hardware and VM Detail inventory and reject active clone
   flatten;
2. validate FTCTL capabilities, the latest durable checkpoint, and target
   transport readiness;
3. pause the remote source scheduler and require its acknowledgement;
4. for SharedMountPoint qcow2, FTCTL resolves and freezes the live source disk
   map, issues QMP `stop`, and requires `query-status=paused`; this keeps the
   QEMU process and persistent bitmap available while preventing further guest
   writes;
5. dispatch the FTCTL Failover final delta from the frozen map while the guest
   remains QMP-paused;
6. after FTCTL publishes `CUTOVER_READY`, stop the source VM through its owning
   Mold and require `POWERED_OFF`;
7. establish the reverse baseline, start the target VM as a non-authoritative
   promotion candidate, and validate its boot contract;
8. submit that target power and boot evidence with the immutable checkpoint to
   FTCTL, then commit TARGET authority only after the engine acknowledgement.

For RBD and VMware-backed Plans, the already validated provider-specific
power-off/final-checkpoint order remains unchanged. The QMP pause barrier is
limited to remote `KVM_TO_KVM` SharedMountPoint qcow2 because its incremental
writer consumes a persistent QEMU dirty bitmap and cannot run after Mold has
destroyed the source domain.

Target boot precedes the authority commit because
`DR_CUTOVER_COMMIT_V2` deliberately includes the target VM identity, power
state, and boot-validation evidence. This does not create dual authority: the
source is already powered off and its scheduler remains paused, while the
target remains a promotion candidate until both FTCTL and Cloud acknowledge
the same immutable checkpoint.

If scheduler pause, QMP quiesce, source stop, or final-delta dispatch fails
before Agent acceptance, Cloud releases an FTCTL-owned QMP pause, powers the
source VM back on when necessary, and resumes forward protection only after
`POWERED_ON` is confirmed.
The Run fails with the original source-isolation or dispatch reason and cannot
leave both sites active. After Agent acceptance, the source remains off and
runtime projection verifies the same isolation state idempotently.

This barrier applies only to planned remote `KVM_TO_KVM` Failover. Disaster
Failover keeps its explicit source-isolation acknowledgement contract, and the
validated VMware-to-RBD and local RBD-to-RBD dispatch paths are unchanged.
Regression tests must prove the SharedMountPoint order `target export ->
scheduler pause -> frozen disk-map -> QMP paused -> final-delta -> source
power-off`, the rollback path `QMP cont -> source POWERED_ON -> scheduler
resume`, and the absence of this QMP lifecycle contract for the unaffected
providers.

## 27. Canceled planned-failover compensation ordering

A canceled `KVM_TO_KVM` Failover Run is terminal operator intent and can never
re-enter target promotion merely because the engine still reports
`CUTOVER_READY`. Cloud reconciles the canceled Run before evaluating normal
cutover readiness. Runtime `target_power_state` is supporting evidence only;
the Cloud target VM row is authoritative for the actual candidate power state.

When SOURCE authority is still committed, cancellation compensation is fixed
to the following order:

1. stop the Cloud-managed target candidate VM and require `Stopped`;
2. send the FTCTL failover-abort command for the accepted cutover session;
3. restore the Plan-owned forward target export;
4. power the remote source VM on and require `POWERED_ON`;
5. resume the remote source scheduler;
6. close the cutover session as `ABORTED` and project the Plan and replica back
   to SOURCE/READY.

The target candidate is safe to stop only while Cloud still owns SOURCE
authority and the canceled Run has not committed TARGET authority. Failure of
any compensation step leaves the session `ABORT_FAILED`, keeps the remaining
authority explicit, and blocks a new action. Cloud must not restart forward
replication while a target qemu process still holds the retained qcow2 file
writable.

Regression tests cover a canceled Run whose runtime incorrectly says
`POWERED_OFF` while the actual Cloud target VM is Running, verify target-stop
before export restoration, verify source power-on before scheduler resume, and
prove that the canceled Run never invokes target power-on. VMware-to-RBD and
local RBD-to-RBD provider behavior remains unchanged.

Cancellation compensation is evaluated before ordinary FTCTL status-result
projection. An authority response in `ERROR` with `result=false` is the state
that compensation is expected to recover and therefore cannot short-circuit
that path. If error publication omits `active_side`, Cloud may use the
persisted `SOURCE` authority together with an active, uncommitted
`CUTOVER_READY`, `ABORTING`, or `ABORT_FAILED` session as the authority proof.
This exception is limited to canceled Failover compensation; normal runtime
projection continues to require explicit authority evidence.

## 28. Failover-abort Agent terminal acknowledgement

`dr-failover-abort` deliberately publishes `accepted=false` after it has
completed because the canceled Failover must never be accepted for continued
promotion. That terminal value is not a command failure. The KVM Agent wrapper
must recognize the completed abort only when all of the following structured
fields agree:

```text
action == FAILOVER_ABORT
exit_code == 0
result in {ok, success}
state == ABORTED
step == failover-preparation-aborted
active_side == SOURCE
target_power_state == POWERED_OFF
error_code is empty
```

The exception is action-scoped and does not weaken generic semantic failure
detection. A canceled, failed, TARGET-authority, powered-on target, incomplete
step, nonzero exit, or error-bearing response remains a failure. Cloud can
close the compensation session only after this Agent acknowledgement and its
own target-stop verification both succeed.

Regression coverage must include the real terminal payload with
`accepted=false`, plus a near-match whose target remains powered on. The
validated VMware-to-RBD, local RBD-to-RBD, normal Failover, and Failback action
contracts are unchanged.

## 29. Target compute offering and replicated VM Detail boundary

ABLESTACK-to-ABLESTACK source inventory can contain `cpuNumber`, `cpuSpeed`,
and `memory` in `vmDetails`. These keys are effective values of the source
compute offering, not portable VM Detail settings. Passing them unchanged to
`deployVirtualMachineForVolume` conflicts with a target offering that fixes
only some dimensions. For example, a target offering may allow custom CPU and
memory while fixing CPU speed at 2000 MHz; sending `cpuSpeed=2000` is still an
invalid customization request.

The target materialization contract is therefore:

1. preserve portable source VM Details such as `UEFI`, TPM, disk controllers,
   I/O policy, and I/O thread settings;
2. exclude `cpuNumber`, `cpuSpeed`, and `memory` from the replicated Detail
   manifest and hardware reconciliation loop;
3. derive target sizing from the guided target placement and selected service
   offering;
4. emit a sizing key in the deploy command only when the corresponding target
   offering field is actually customizable (`NULL` in the offering);
5. keep fixed offering values out of `cmd.getDetails()` even when their value
   equals the requested or source value.

This rule applies to both persistent replica and test VM materialization because
they share `DrReplicaDeployVMVolumeCmd`. It does not alter disk transfer,
checkpoint, VMware-to-RBD, or RBD-to-RBD provider behavior.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Source Detail policy | Copies source sizing keys as portable VM Details | Treats sizing as target placement data |
| Target deploy command | Fixed CPU speed can be sent as a custom value | Sends only offering-supported custom dimensions |
| Target reconciliation | Reapplies source sizing keys after creation | Reconciles portable VM Details only |
| UI/Run | Full sync is accepted, then fails before Agent dispatch | Run reaches materialization and FTCTL dispatch without offering conflict |

Regression coverage must include a partially customizable offering (custom CPU
and memory, fixed CPU speed), a fully static offering, and the existing KVM VM
Detail preservation case. The UI full-resync Run must become terminal only from
the normal Cloud/Agent/FTCTL evidence path; failed rows are not repaired by
direct DB updates.

## 30. Protected plan edit and asynchronous completion contract

Plan metadata edits must not reconstruct a protected plan's runtime mapping.
The absence of `sourceHostUuid` is the expected representation of dynamic
placement and is not evidence that source hardware discovery is incomplete.
The guided edit form may request a structural refresh only when the persisted
mapping contains an explicit source hardware discovery error or an incomplete
disk storage contract and the operator has actually changed a structural
placement field. Metadata, RPO, RTO, and policy-only edits never opt into that
repair path. Otherwise the form sends changed fields only.

The update dialog remains in edit mode while the asynchronous Cloud job is
running. It closes and emits success only after the job reaches `SUCCEEDED`.
Admission is not completion. A failed job keeps the populated edit form open,
shows one failure notification, and must never reset the same dialog into plan
creation mode.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Placement refresh | Missing `sourceHostUuid` forces guided mapping regeneration | Dynamic host placement remains valid; refresh only explicit incomplete inventory |
| Update payload | Metadata-only edit can include regenerated mapping JSON | Metadata-only edit sends only changed mutable fields |
| Async UI state | Admission shows success and resets the form before terminal result | Terminal success closes the form; terminal failure preserves edit state |
| Runtime guard | Correctly rejects changed protected mappings | Remains unchanged and receives no mapping field for metadata-only edits |

Regression coverage includes a host-unpinned KVM mapping, explicit discovery
failure, incomplete SharedMountPoint disk metadata, and changed-field payload
selection. UI validation must confirm exactly one terminal notification and a
persisted description without `DR_RUNTIME_RESOURCE_EXISTS`.

## 31. Reprotect canonical checkpoint identity

For ABLESTACK-to-ABLESTACK cutover, the FTCTL `plan_cycle_sequence` is the
suffix of the canonical `cycle_token`; it is not the Cloud `dr_sync_cycle`
row's `sequence`. Cloud sequencing can advance independently while projection
aliases are reconciled. Reprotect preflight must therefore resolve the durable
cutover Cycle by `(plan_id, cycle_token)` first. A lookup by Cloud sequence is
retained only as a legacy fallback, and the resolved row must still be
`READY`, `LOCAL_DURABLE`, have `target_durable_at`, and carry the exact expected
token. The engine checkpoint sequence remains the FTCTL checkpoint identity
passed to Reprotect and must not be rewritten to the Cloud row sequence.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Cutover identity | Treats `plan_cycle_sequence` as a Cloud DB sequence | Treats it as the canonical token suffix |
| Durable lookup | Misses a valid row when DB sequence and token suffix diverge | Resolves exact token first, then guarded legacy fallback |
| Reprotect gate | Rejects a durable cutover with `DR_REPROTECT_CHECKPOINT_MISMATCH` | Accepts only the exact durable canonical Cycle |

Regression coverage must include an FTCTL checkpoint, token suffix, and Cloud
row sequence that are all different. VMware-to-RBD and RBD-to-RBD retain their
existing checkpoint validation paths.

## 32. VMware FILE cutover checkpoint authority

A planned VMware failover creates one final operation-owned checkpoint after
the preceding scheduler Cycle. During `CUTOVER_READY`, the guest preparation
manifest belongs to that final checkpoint. The older
`latest_completed_checkpoint_sequence` remains scheduler history and must not
be used to reject the cutover.

Cloud resolves the promotion sequence in this order:

1. `failover_restore_point_sequence`;
2. operation `checkpoint_sequence`;
3. latest completed scheduler sequence only as a legacy fallback.

The guest preparation sequence must equal the resolved promotion sequence and
the final checkpoint must already have a durable restore point. This preserves
the existing VMware-to-RBD capture contract while allowing the same VMware CBT
producer to promote a SharedMountPoint qcow2 target.

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Cloud projection | Compares the final guest manifest with the previous scheduler Cycle | Compares it with the operation-owned failover checkpoint |
| UI Run | Remains `RUNNING / runtime-transfer` after FTCTL reaches `CUTOVER_READY` | Proceeds through target power-on, commit acknowledgement, and `SUCCEEDED` |
| Existing paths | VMware capture works but target format leaks into cutover sequencing | VMware capture remains unchanged; target-specific reverse baseline is isolated |

Regression coverage must include `latest_completed=36`, final checkpoint 37,
and guest preparation sequence 37. A stale or mismatched guest preparation
sequence must remain blocked.
