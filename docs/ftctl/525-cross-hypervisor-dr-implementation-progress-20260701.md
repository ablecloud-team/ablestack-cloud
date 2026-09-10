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

# Cross Hypervisor DR Implementation Progress - 2026-07-01

> 2026-08-10 current pending corrective scope: document
> [600](600-cross-hypervisor-dr-vmware-cbt-activation-convergence-design-20260810.md)
> records the powered-on VMware CBT activation false-negative. Live preflight
> proved CTK creation, a valid per-disk change ID, and successful
> `QueryChangedDiskAreas`; temporary snapshots were removed. Detailed design is
> complete. Code implementation, tests, builds, deployment, failed-run cleanup,
> full-seed retest, and one incremental-cycle verification remain pending.

> 2026-08-06 current pending corrective scope: document
> [599](599-cross-hypervisor-dr-forward-cutover-commit-contract-and-authority-convergence-design-20260806.md)
> records the forward cutover command-field loss and premature TARGET projection.
> The design and non-destructive preflight are complete; implementation, build,
> paired deployment, existing-session recovery, and retest remain pending.

> 2026-08-05 current pending corrective scope: document
> [595](595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md)
> records the post-transfer route-contract and session-only terminal defect.
> Document 594 is implemented and deployed; document 595 and FTCTL companion
> 452 are designed and live-preflight verified but not yet implemented.

> 2026-08-04 design status: live-runtime Failback preflight convergence is
> designed in document 592 with qemu companion 449. Implementation, focused
> tests, changed-module builds, paired deployment, target VM reconciliation,
> and live retest remain pending.

> 2026-08-03 design status: async plan mutation and target resource ownership
> hardening is designed in document 590. Its P0-P8 implementation, build,
> deployment, collision reconciliation, and clean retest remain pending.

> 2026-07-31 design status: source-isolation internalization design is complete
> in document 587. Implementation/build/deployment are the next phase.

이 문서는 `524-cross-hypervisor-dr-implementation-smoke-build-plan-20260701.md`의 12단계 구현 진행 결과를 누적한다.

핵심 원칙은 모든 단계에서 UI -> API -> Cloud backend -> DB/runtime state -> API response -> UI refresh 고리가 끊어지지 않게 구현하는 것이다. Agent/ftctl 실제 실행은 후속 단계에서 붙더라도, 해당 단계의 계약과 상태 응답은 반드시 UI까지 되돌아와야 한다.

## Overall 12-Step Plan

| Step | Scope | Status |
| --- | --- | --- |
| 1 | Cloud FTCTL_DR contract, plan validation, response/action surface | Done |
| 2 | Cloud Agent command/answer contract and async dispatch bridge | Done |
| 3 | ftctl DR profile/session/checkpoint skeleton with runnable status path | Done |
| 4 | ABLESTACK source reader/writer drivers | Done |
| 5 | VMware source/target VDDK/VADP driver contract | Done |
| 6 | Continuous replication scheduler and checkpoint loop | Done |
| 7 | DR protection setup orchestration | Done |
| 8 | Test failover and cleanup execution | Done |
| 9 | Planned/disaster failover execution | Done |
| 10 | Failback and reprotect execution | Done |
| 11 | UI runtime/preflight/progress hardening | Done |
| 12 | Smoke validation, build gates, packaging handoff | Done |

## Step 1 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| Cloud constants | Added `FTCTL_DR` engine/binding, sync pause/resume/release run types, and DR plan states for syncing/testing/paused. |
| Plan DB model | Added `active_side`, worker host bindings, and latest source/target checkpoint timestamps to `DrPlanVO`. |
| API response | Exposed active side, worker host IDs, and checkpoint timestamps in `DrPlanResponse` and `DrResponseGenerator`. |
| Plan create/update API | Added source/target/coordinator worker host parameters. |
| Service validation | Allowed `FTCTL_DR` for all four directions: `KVM_TO_KVM`, `KVM_TO_VMWARE`, `VMWARE_TO_VMWARE`, `VMWARE_TO_KVM`. Kept legacy `FTCTL` limited to `KVM_TO_KVM`. |
| Action eligibility | Added `sync`, `pauseSync`, `resumeSync`, `testFailover`, `stopTestFailover`, `failover`, `confirmFenceClear`, `failback`, `reprotect`, `adoptReplica`, `releaseProtection`, and `migrationOnly` calculations. |
| V2K boundary | Marked V2K as `migrationOnly` so DR action buttons and production DR runs do not open through V2K. |
| Contract adapter | Registered `FtctlDrContractAdapter` for `FTCTL_DR:FTCTL_DR`; it validates contract and records explicit pending-runtime status until Agent/ftctl dispatch is implemented in later steps. |
| API commands | Added `pauseDrSync`, `resumeDrSync`, and `releaseDrProtection` as async DR run commands. |
| UI API helper | Added response extraction for the new action commands. |
| UI action toolbar | Added pause, resume, release buttons wired to backend eligibility and API availability. |
| UI plan create flow | Default engine changed to `FTCTL_DR`; V2K is disabled as migration-only; worker host fields and RTO are accepted; empty optional values are removed before API call. |
| UI plan detail flow | Active side, worker host IDs, and checkpoint timestamps are shown in the plan overview. |
| Schema | Updated `dr_plan` create schema and 4.22 upgrade schema with worker/checkpoint columns and idempotent column additions. |
| Unit tests | Updated plan eligibility tests for V2K migration-only and added FTCTL_DR action/topology validation coverage. |

## Step 1 Verification

Validation status: Passed

| Check | Command | Result |
| --- | --- | --- |
| DR plugin unit test with required Cloud modules | `mvn -pl :cloud-plugin-integrations-disaster-recovery -am -Dtest=DrPlanServiceImplTest -DfailIfNoTests=false test` | Passed. `DrPlanServiceImplTest`: 5 tests, 0 failures, 0 errors. |
| DR plugin Maven build/install smoke | `mvn -pl :cloud-plugin-integrations-disaster-recovery -am -DskipTests -DfailIfNoTests=false install` | Passed. DR plugin JAR generated and installed to the local Maven repository. |
| Cloud UI production build smoke | `NODE_OPTIONS=--openssl-legacy-provider npm run build` from WSL ext4 `ui` directory | Passed. Production `dist` bundle generated; only existing asset-size and Browserslist warnings were reported. |

## Flow Coverage After Step 1

Implemented end-to-end loop:

1. UI creates a DR plan with `FTCTL_DR`, direction, RPO/RTO, and optional worker hosts.
2. `createDrPlan` API receives the request asynchronously through the Cloud API command path.
3. `DrPlanServiceImpl` normalizes `FTCTL_DR`, validates site hypervisor direction, stores the plan, and calculates eligibility.
4. `DrPlanResponse` returns engine, worker, checkpoint, RPO/RTO, active side, and action eligibility to UI.
5. UI list/detail refresh uses the returned response to render RPO, worker/checkpoint details, and action button states.
6. UI action buttons call async run APIs. For `FTCTL_DR`, Cloud accepts the run path through the registered contract adapter; actual Agent/ftctl runtime dispatch remains Step 2 and later.

Not yet implemented:

| Gap | Planned Step |
| --- | --- |
| Host worker selection list API | Step 2 or Step 11 |
| ftctl DR session/profile/checkpoint runtime | Step 3 |
| Actual continuous data transfer | Steps 4-6 |
| Real failover/failback execution | Steps 8-10 |

## Step 2 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| Agent command contract | Added `FtctlDrActionCommand`, `FtctlDrStatusCommand`, `FtctlDrCancelCommand`, and `FtctlDrPreflightCommand` with matching answer payloads. |
| KVM Agent wrappers | Added Libvirt wrapper entry points that map Cloud Agent commands to `ablestack_vm_ftctl dr-*` commands and pass DR profile JSON through temporary files. |
| Async dispatch bridge | Replaced the `FTCTL_DR` contract-only adapter registration with `FtctlDrUnifiedActionAdapter`, which sends DR actions through `AgentManager` to the coordinator/source/target worker host. |
| Accepted run state | Added non-terminal `DrAdapterResult.accepted(...)` handling so long-running DR actions move from Cloud dispatch to `ACCEPTED` instead of being marked completed immediately. |
| Control actions | Kept pause, resume, release, and test cleanup as terminal control actions after Agent acceptance, while sync, test failover, failover, failback, and reprotect remain long-running accepted actions. |
| Runtime projection bridge | Added `FtctlDrRuntimeProjectionAdapter` for `FTCTL_DR` so Cloud can poll Agent status and project DR plan state, checkpoints, durable target time, and target RPO back into API/UI responses. |
| Agent payload redaction | Redacted password, secret, token, apikey, api_key, and credential-like values before sending `profileJson`, `requestJson`, command context, result details, or status payloads beyond Cloud. |
| Spring wiring | Registered `FtctlDrUnifiedActionAdapter` as the active `FTCTL_DR:FTCTL_DR` replication engine and added the runtime projection adapter to projection refresh wiring. |
| Unit tests | Added coverage for Agent dispatch, coordinator host selection, accepted run state, projection refresh, and secret redaction. |

## Step 2 Verification

Validation status: Passed

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| DR plugin unit tests with changed Cloud modules | `mvn -pl :cloud-plugin-integrations-disaster-recovery,:cloud-plugin-hypervisor-kvm -am -Dtest=DrRunExecutorImplTest,FtctlDrUnifiedActionAdapterTest -DfailIfNoTests=false test` | Passed. `DrRunExecutorImplTest`: 3 tests, 0 failures, 0 errors. `FtctlDrUnifiedActionAdapterTest`: 2 tests, 0 failures, 0 errors. |
| Changed Cloud module Maven build/install smoke | `mvn -pl :cloud-plugin-integrations-disaster-recovery,:cloud-plugin-hypervisor-kvm -am -DskipTests -DfailIfNoTests=false install` | Passed. KVM and DR plugin JARs were generated and installed to the local Maven repository. |

## Flow Coverage After Step 2

Implemented end-to-end loop:

1. UI action buttons call async DR APIs and receive run/job state through existing Cloud API response handling.
2. API creates a `DrRun` and the backend executor moves it through `QUEUED`, `DISPATCHING`, and `RUNNING`.
3. `FtctlDrUnifiedActionAdapter` builds a redacted DR runtime profile and sends the action through `AgentManager` to the selected coordinator worker host.
4. KVM Agent wrapper converts the command into `ablestack_vm_ftctl dr-* --json` execution and returns an Agent answer.
5. Cloud converts accepted long-running Agent answers into `DrRun.state=ACCEPTED`, records `RUN_ACCEPTED`, stores `externalJobRef`, and refreshes projection.
6. Projection refresh polls `FtctlDrStatusCommand` and updates plan state/checkpoint/RPO fields so API/UI refresh can show runtime progress.

Important boundary:

The Cloud-to-Agent dispatch path is now implemented, but the host-side `ablestack_vm_ftctl dr-*` runtime commands are still the Step 3 deliverable. Until Step 3 lands, a real host execution may return runtime-unavailable or unsupported status from ftctl; that is an expected engine gap, not a UI/API flow break.

## Step 3 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| ftctl DR runtime library | Added `lib/ftctl/dr_runtime.sh` as the host-side FTCTL_DR runtime entry point behind the Cloud Agent wrappers. |
| CLI command surface | Added runnable `ablestack_vm_ftctl dr-plan-apply`, `dr-sync-start`, `dr-sync-pause`, `dr-sync-resume`, `dr-test-failover`, `dr-test-cleanup`, `dr-failover`, `dr-failback`, `dr-reprotect`, `dr-release`, `dr-status`, and `dr-cancel` commands. |
| CLI options | Added `--plan`, `--run`, `--profile-json`, `--restore-point`, `--events-offset`, and `--wait` parsing, including `--wait=false` for start-only Agent execution. |
| Profile persistence | Stores Cloud-provided FTCTL_DR profile JSON under `${FTCTL_RUN_DIR}/dr-runtime/plans/<plan>/profile.json` with password, secret, token, apikey, api_key, and credential-like fields redacted before disk persistence. |
| Session state | Stores plan status in `${FTCTL_RUN_DIR}/dr-runtime/plans/<plan>/status.state` and per-run state in `${FTCTL_RUN_DIR}/dr-runtime/plans/<plan>/runs/<run>.state`. |
| Checkpoint/status projection | Emits Cloud-consumable JSON fields for `state`, `step`, `progress`, `external_job_ref`, `last_source_checkpoint_at`, `last_target_durable_at`, `target_ready_rpo_seconds`, `events_offset`, `events`, and `error_code`. |
| Event bridge | Writes `dr.plan.apply`, `dr.action.accepted`, and `dr.cancel` events through the existing FTCTL `events.log` path, and supports incremental `--events-offset` reads for Cloud projection polling. |
| Lock policy | Keeps `dr-status` read-only and lock-free, keeps `dr-plan-apply --dry-run` lock-free, and serializes mutating DR runtime actions with the existing ftctl lock. |
| Shell completion | Added DR commands and options to `completions/ablestack_vm_ftctl`. |
| Selftest coverage | Added targeted selftest cases for profile redaction, status, cancel, and control action state transitions. |

## Step 3 Verification

Validation status: Passed

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| ftctl syntax smoke | `bash -n bin/ablestack_vm_ftctl.sh lib/ftctl/dr_runtime.sh bin/ablestack_vm_ftctl_selftest.sh completions/ablestack_vm_ftctl` | Passed. |
| New DR runtime shellcheck | `shellcheck lib/ftctl/dr_runtime.sh` from WSL | Passed. |
| Targeted ftctl DR runtime selftests | `FTCTL_SELFTEST_CASES=selftest_case_dr_runtime_profile_status_cancel,selftest_case_dr_runtime_control_actions bash bin/ablestack_vm_ftctl_selftest.sh` | Passed. |
| Cloud wrapper command contract smoke | Direct `dr-plan-apply`, `dr-sync-start --wait=false`, `dr-status --events-offset`, `dr-cancel`, and post-cancel `dr-status` execution against an isolated temp config | Passed. JSON included `accepted`, `state`, `step`, `progress`, `external_job_ref`, `events_offset`, and redacted profile persistence. |
| DR control action smoke | Direct `dr-sync-pause`, `dr-sync-resume`, `dr-test-failover`, `dr-test-cleanup`, `dr-failover`, `dr-failback`, `dr-reprotect`, and `dr-release` execution against an isolated temp config | Passed. Each command returned the expected accepted JSON and state/step transition. |

Note: the full legacy `selftest_main` path was not used as the Step 3 gate because existing repository-wide shellcheck warnings still fail `selftest_run_lint`. The new `dr_runtime.sh` shellcheck and the new targeted DR selftests passed independently.

## Flow Coverage After Step 3

Implemented end-to-end loop:

1. UI action buttons call async DR APIs and receive run/job state through the existing Cloud API response path.
2. API/backend creates a `DrRun`, stores the requested action, and dispatches through the Step 2 Cloud Agent command bridge.
3. KVM Agent wrapper invokes `ablestack_vm_ftctl dr-* --json` with the Cloud-generated plan/run/profile contract.
4. ftctl validates the profile, redacts and persists runtime profile state, records plan/run state, emits operation events, and returns an immediate accepted JSON response.
5. Cloud stores the accepted external job reference and polls `dr-status` through the projection adapter.
6. ftctl returns checkpoint/status/progress/event fields from local runtime state so Cloud can refresh DB projection and the UI can show current progress without blocking the original UI action.

Important boundary:

Step 3 makes the Cloud -> Agent -> ftctl -> status projection loop runnable, but it still records checkpoint/status state at the runtime envelope level. Actual disk delta capture, transfer, target durable checkpoint updates, and RPO calculation are the Step 4 through Step 6 deliverables.

## Step 4 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| ABLESTACK driver library | Added `lib/ftctl/dr_ablestack.sh` as the first concrete FTCTL_DR source/target driver layer for ABLESTACK/KVM disks. |
| Profile disk-map parser | Accepts explicit disk maps from `mapping.disks`, `mapping.diskMappings`, `mapping.volumes`, top-level disk arrays, or paired `source.disks`/`target.disks`, then normalizes them into `${FTCTL_RUN_DIR}/dr-runtime/plans/<plan>/ablestack-disks.json`. |
| Source reader metadata | Resolves source disk path, source format, source type, and virtual size from explicit profile metadata or `qemu-img info`/existing FTCTL blockcopy size helpers. |
| Target writer preparation | Supports QCOW2/file, raw/block, and RBD targets. File targets are created with `qemu-img create`; block targets are size-validated with `blockdev`; RBD targets use `rbd info` and `rbd create` with MiB rounding. |
| Manifest output | Writes per-run target writer manifests under `${FTCTL_RUN_DIR}/dr-runtime/plans/<plan>/manifests/<run>-manifest.json`. |
| Checkpoint metadata | Writes per-run checkpoint metadata under `${FTCTL_RUN_DIR}/dr-runtime/plans/<plan>/checkpoints/<run>-checkpoint.json`. Target preparation records `TARGET_PREPARED`; explicit full seed records `TARGET_READY`. |
| Runtime integration | `dr-sync-start` now invokes the ABLESTACK driver when the profile involves an ABLESTACK source or target. Runtime JSON exposes `driver`, `driver_state`, `disk_map_path`, `manifest_path`, and `checkpoint_path`. |
| Safe async default | Normal Cloud `--wait=false` action remains start-only and bounded: it prepares/validates target writer state and returns accepted/progress JSON. Full disk seed is available only when explicitly requested by profile `request.performFullSeed=true` with wait enabled, or by `FTCTL_DR_ABLESTACK_FULL_SEED_ON_START=1`. |
| Full seed primitive | Added a concrete one-shot full seed path using `qemu-img convert --force-share` from source path to prepared target URI, including RBD URI normalization. This is the data-plane primitive Step 6 scheduler will reuse. |
| Missing disk-map handling | If Cloud has not yet supplied disk mappings, `dr-sync-start` remains accepted but reports `driver_state=WAITING_FOR_DISK_MAP` and `step=ablestack-disk-map-pending` instead of silently pretending replication is active. |
| Selftest coverage | Added targeted selftests for ABLESTACK target preparation and one-shot full seed completion. |

## Step 4 Verification

Validation status: Passed

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| ftctl syntax smoke | `bash -n bin/ablestack_vm_ftctl.sh lib/ftctl/dr_runtime.sh lib/ftctl/dr_ablestack.sh bin/ablestack_vm_ftctl_selftest.sh completions/ablestack_vm_ftctl` | Passed. |
| New driver shellcheck | `shellcheck lib/ftctl/dr_ablestack.sh lib/ftctl/dr_runtime.sh` from WSL | Passed. |
| Targeted FTCTL_DR runtime and ABLESTACK driver selftests | `FTCTL_SELFTEST_CASES=selftest_case_dr_runtime_profile_status_cancel,selftest_case_dr_runtime_control_actions,selftest_case_dr_ablestack_target_prepare,selftest_case_dr_ablestack_full_seed_once,selftest_case_dr_ablestack_missing_disk_map_waits bash bin/ablestack_vm_ftctl_selftest.sh` | Passed. |
| ABLESTACK target prepare smoke | Fake `qemu-img` selftest verified `dr-sync-start --wait=false` creates target writer state, manifest, and `TARGET_PREPARED` checkpoint metadata without running a long copy. |
| ABLESTACK full seed smoke | Fake `qemu-img` selftest verified explicit full seed invokes `qemu-img convert`, returns `state=READY`, `driver_state=TARGET_READY`, `progress=100`, and writes `TARGET_READY` checkpoint metadata. |
| Missing disk-map smoke | Selftest verified minimal Cloud-style ABLESTACK profiles remain accepted but report `driver_state=WAITING_FOR_DISK_MAP` and `step=ablestack-disk-map-pending`. |

Note: as in Step 3, the full legacy `selftest_main` path was not used as the Step 4 gate because existing repository-wide shellcheck warnings still fail `selftest_run_lint`. The new driver and changed runtime files pass shellcheck independently, and the new targeted selftests pass.

## Flow Coverage After Step 4

Implemented end-to-end loop:

1. UI action buttons call Cloud async DR APIs.
2. Cloud backend dispatches the run to the selected Agent through the Step 2 FTCTL_DR command bridge.
3. Agent invokes `ablestack_vm_ftctl dr-sync-start --profile-json ... --wait=false --json`.
4. ftctl persists the profile, normalizes ABLESTACK disk mappings, prepares QCOW2/RBD/block targets when explicit mappings exist, and writes manifest/checkpoint metadata.
5. ftctl returns accepted JSON with driver state and manifest/checkpoint paths.
6. Cloud projection can poll `dr-status` and surface that the ABLESTACK writer is `TARGET_PREPARED`, `WAITING_FOR_DISK_MAP`, or `TARGET_READY` depending on the actual runtime state.

Important boundary:

Step 4 provides concrete ABLESTACK reader/writer primitives and a bounded start-time prepare path. It does not yet provide a continuous replication loop, dirty bitmap generation rotation, retry scheduling, or periodic RPO maintenance. Those remain Step 6.

## Step 5 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| VMware driver library | Added `lib/ftctl/dr_vmware.sh` as the FTCTL_DR VMware source/target driver contract layer. |
| VMware profile parser | Normalizes Cloud profile endpoint and disk metadata into `${FTCTL_RUN_DIR}/dr-runtime/plans/<plan>/vmware-disks.json`, including source/target providers, drivers, VM refs, vCenter/datastore/folder/resource pool/network refs, VMDK refs, CBT `changeId`, snapshot refs, and disk sizes. |
| VDDK/VADP capability preflight | `dr-plan-apply --dry-run --json` now performs VMware capability discovery when a profile uses VMware. It reports `capable=false` with `DR_MISSING_VDDK` when neither nbdkit-vddk nor VMware VDDK tooling is available. |
| Fail-fast runtime guard | `dr-sync-start --wait=false --json` now invokes the VMware driver before ABLESTACK target preparation. If VDDK is missing, ftctl writes an ERROR runtime state, manifest/checkpoint evidence, and returns a non-zero Agent command result with `error_code=DR_MISSING_VDDK`. |
| VMware contract manifest | Writes per-run VMware manifests under `${FTCTL_RUN_DIR}/dr-runtime/plans/<plan>/manifests/<run>-vmware-manifest.json`, including capability state and normalized disk/CBT metadata. |
| VMware checkpoint metadata | Writes per-run VMware checkpoints under `${FTCTL_RUN_DIR}/dr-runtime/plans/<plan>/checkpoints/<run>-vmware-checkpoint.json`. Contract-ready runs record `VMWARE_CONTRACT_READY`; missing VDDK runs record `MISSING_VDDK`; missing disk maps record `WAITING_FOR_VMWARE_DISK_MAP`. |
| Optional local VMDK materialization hook | Added guarded `FTCTL_DR_VMWARE_LOCAL_VMDK_CREATE=1` support for local test-only VMDK target creation through `qemu-img create -f vmdk`. The production path remains VDDK/nbdkit-vddk capability-gated. |
| ABLESTACK/VMware boundary | Updated the ABLESTACK driver so it prepares target disks only when the target provider is `ABLESTACK`. For ABLESTACK -> VMware, ABLESTACK source metadata is parsed but VMware target preparation remains owned by the VMware driver. |
| Runtime integration | `dr-sync-start` now runs VMware preflight/contract handling before ABLESTACK handling, preserving successful ABLESTACK-only behavior while failing VMware paths early when capability is missing. |
| Selftest coverage | Added targeted tests for VMware preflight missing VDDK, VMware contract-ready metadata, and missing-VDDK sync-start blocking. |

## Step 5 Verification

Validation status: Passed

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| ftctl syntax smoke | `bash -n bin/ablestack_vm_ftctl.sh lib/ftctl/dr_runtime.sh lib/ftctl/dr_ablestack.sh lib/ftctl/dr_vmware.sh bin/ablestack_vm_ftctl_selftest.sh completions/ablestack_vm_ftctl` | Passed. |
| Changed driver shellcheck | `shellcheck lib/ftctl/dr_ablestack.sh lib/ftctl/dr_vmware.sh lib/ftctl/dr_runtime.sh` from WSL | Passed. |
| Targeted FTCTL_DR runtime/ABLESTACK/VMware selftests | `FTCTL_SELFTEST_CASES=selftest_case_dr_runtime_profile_status_cancel,selftest_case_dr_runtime_control_actions,selftest_case_dr_ablestack_target_prepare,selftest_case_dr_ablestack_full_seed_once,selftest_case_dr_ablestack_missing_disk_map_waits,selftest_case_dr_vmware_preflight_missing_vddk,selftest_case_dr_vmware_contract_ready,selftest_case_dr_vmware_missing_vddk_blocks_sync bash bin/ablestack_vm_ftctl_selftest.sh` | Passed. |
| VMware missing capability smoke | Selftest verified `dr-plan-apply --dry-run --json` returns `capable=false` and `DR_MISSING_VDDK`, while `dr-sync-start` fails immediately with runtime state `ERROR`, `step=vmware-capability-missing`, and `driver_state=MISSING_VDDK`. |
| VMware contract-ready smoke | Selftest verified forced VDDK-ready mode produces accepted `dr-sync-start` JSON with `driver=VMWARE`, `driver_state=VMWARE_CONTRACT_READY`, and persisted manifest/checkpoint metadata containing CBT `changeId`. |

Note: as in previous steps, the full legacy `selftest_main` path was not used as the Step 5 gate because existing repository-wide shellcheck warnings still fail `selftest_run_lint`. The changed DR runtime and driver files pass shellcheck independently, and the targeted DR selftests pass.

## Flow Coverage After Step 5

Implemented end-to-end loop:

1. UI action buttons call Cloud async DR APIs.
2. Cloud backend dispatches the run to the selected Agent through the FTCTL_DR command bridge.
3. Agent invokes `ablestack_vm_ftctl dr-plan-apply --dry-run --json` for preflight or `dr-sync-start --wait=false --json` for runtime start.
4. ftctl detects VMware source/target participation from the Cloud profile and validates whether VDDK/nbdkit-vddk capability exists before pretending the data plane can start.
5. When VMware capability is missing, ftctl returns explicit `DR_MISSING_VDDK` evidence to the Agent/Cloud path so API/UI can show a real blocker instead of a hanging or misleading accepted state.
6. When VMware capability is present, ftctl persists normalized VMware disk/CBT/VMDK contract metadata and checkpoint files, then returns accepted runtime JSON for Cloud projection polling.
7. For mixed ABLESTACK/VMware directions, ABLESTACK target preparation now runs only when ABLESTACK is the target side; VMware target/source contract ownership stays in the VMware driver.

Important boundary:

Step 5 provides VMware capability gating and concrete contract metadata for source CBT and target VMDK handling. It intentionally does not yet run continuous CBT snapshot creation, changed-block reads, delta transfer, or periodic durable checkpoint rotation. Those are the Step 6 scheduler and checkpoint-loop deliverables.

## Step 6 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| Common scheduler library | Added `lib/ftctl/dr_scheduler.sh` as the host-side FTCTL_DR replication worker. It owns cycle execution, worker PID/control state, restore point JSONL, checkpoint sequence, and runtime state updates. |
| Async worker start | `dr-sync-start --wait=false` now keeps the Agent/UI path non-blocking by returning after driver preflight/prepare and spawning a background scheduler worker when a data-plane profile is ready. Foreground mode remains available for deterministic smoke/selftest through `--wait=true` or `FTCTL_DR_SCHEDULER_FOREGROUND=1`. |
| Continuous cycle loop | Scheduler runs repeatable `full-seed` then `incremental` cycles with configurable `schedule.intervalSeconds`, `request.maxCycles`, `FTCTL_DR_SCHEDULER_INTERVAL_SEC`, and `FTCTL_DR_SCHEDULER_MAX_CYCLES`. Production default loops continuously; tests can cap cycles. |
| Restore point model | Each successful cycle appends a restore point record to `${FTCTL_RUN_DIR}/dr-runtime/plans/<plan>/restore-points.jsonl` with sequence, cycle type, manifest, checkpoint, source checkpoint time, target durable time, and RPO evidence. |
| Runtime projection fields | `dr-status --json` now exposes `scheduler_state`, `worker_pid`, `checkpoint_sequence`, and `restore_points_path` in addition to the existing checkpoint/status fields. `target_ready_rpo_seconds` is recalculated dynamically from `last_target_durable_at` so UI polling sees RPO age grow between checkpoints. |
| ABLESTACK data-plane cycle | Added `ftctl_dr_ablestack_replication_cycle`, reusing the existing qemu-img full seed primitive as a repeatable checkpoint cycle. The qemu-img copy path now uses `convert -n` after target preparation so prepared QCOW2/RBD/block targets can be refreshed rather than failing on an existing target. |
| VMware data-plane cycle contract | Added `ftctl_dr_vmware_replication_cycle`. It does not use V2K. It requires a configured VDDK mover through `FTCTL_DR_VMWARE_MOVER`; otherwise it fails explicitly with `DR_VMWARE_MOVER_UNAVAILABLE`. A test-only `FTCTL_DR_VMWARE_MOCK_CYCLE=1` mode validates CBT/VDDK checkpoint loop behavior without pretending production data was transferred. |
| Control actions | `dr-sync-pause`, `dr-sync-resume`, `dr-release`, and `dr-cancel` now write scheduler control state (`pause`, `run`, `stop`) so the background worker can pause, resume, or stop without blocking the UI/API action path. |
| Failure visibility | Scheduler failures update runtime state to `ERROR`, set `accepted=false`, preserve explicit error codes such as `DR_VMWARE_MOVER_UNAVAILABLE`, and copy the result to plan status for Cloud projection polling. |
| Selftest coverage | Added targeted scheduler tests for ABLESTACK checkpoint loop, VMware mock checkpoint loop, and VMware missing mover failure. |

## Step 6 Verification

Validation status: Passed

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| ftctl syntax smoke | `bash -n bin/ablestack_vm_ftctl.sh lib/ftctl/dr_runtime.sh lib/ftctl/dr_ablestack.sh lib/ftctl/dr_vmware.sh lib/ftctl/dr_scheduler.sh bin/ablestack_vm_ftctl_selftest.sh completions/ablestack_vm_ftctl` | Passed. |
| Changed driver/scheduler shellcheck | `shellcheck lib/ftctl/dr_ablestack.sh lib/ftctl/dr_vmware.sh lib/ftctl/dr_scheduler.sh lib/ftctl/dr_runtime.sh` from WSL | Passed. |
| Targeted FTCTL_DR runtime/driver/scheduler selftests | `FTCTL_SELFTEST_CASES=selftest_case_dr_runtime_profile_status_cancel,selftest_case_dr_runtime_control_actions,selftest_case_dr_ablestack_target_prepare,selftest_case_dr_ablestack_full_seed_once,selftest_case_dr_ablestack_missing_disk_map_waits,selftest_case_dr_vmware_preflight_missing_vddk,selftest_case_dr_vmware_contract_ready,selftest_case_dr_vmware_missing_vddk_blocks_sync,selftest_case_dr_scheduler_ablestack_checkpoint_loop,selftest_case_dr_scheduler_vmware_mock_checkpoint_loop,selftest_case_dr_scheduler_vmware_requires_mover bash bin/ablestack_vm_ftctl_selftest.sh` | Passed. |
| ABLESTACK scheduler smoke | Selftest verified two scheduler cycles, two restore point records, `checkpoint_sequence=2`, `scheduler_state=COMPLETED`, and two qemu-img refresh operations using `convert --force-share -p -n -S`. |
| VMware scheduler mock smoke | Selftest verified VDDK-ready VMware profile can run two mock cycles, create full/incremental restore point records, and persist `TARGET_READY` checkpoint metadata without invoking V2K. |
| VMware missing mover smoke | Selftest verified VMware/VDDK production path does not silently succeed without a configured mover. It returns runtime `ERROR`, `scheduler_state=ERROR`, and `DR_VMWARE_MOVER_UNAVAILABLE`. |

Note: as in previous steps, the full legacy `selftest_main` path was not used as the Step 6 gate because existing repository-wide shellcheck warnings still fail `selftest_run_lint`. The changed DR runtime, driver, and scheduler files pass shellcheck independently, and the targeted DR selftests pass.

## Flow Coverage After Step 6

Implemented end-to-end loop:

1. UI action buttons call Cloud async DR APIs.
2. Cloud backend creates a run and dispatches to the selected Agent through the FTCTL_DR command bridge.
3. Agent invokes `ablestack_vm_ftctl dr-sync-start --wait=false --json`.
4. ftctl validates/prepares ABLESTACK and/or VMware driver contracts, then starts a scheduler worker when the profile has a ready data plane.
5. The Agent receives an immediate accepted/error answer and is not blocked by long-running replication work.
6. The scheduler worker runs repeated full/incremental cycles, writes manifest/checkpoint files, appends restore point records, and updates runtime status.
7. Cloud projection polls `dr-status --json` and can surface scheduler state, latest checkpoint sequence, target durable timestamp, restore point path, and dynamically calculated RPO to the API/UI.
8. Pause/resume/release/cancel actions now reach the scheduler through control state while preserving the async UI/API behavior.

Important boundary:

Step 6 completes the common replication loop and ABLESTACK repeatable checkpoint path. VMware production data transfer is deliberately gated on a separate VDDK mover (`FTCTL_DR_VMWARE_MOVER`) and fails with `DR_VMWARE_MOVER_UNAVAILABLE` when that mover is absent. This keeps the implementation honest: V2K is not reused as a DR engine, and VMware paths do not report target-ready production checkpoints unless an actual VDDK mover or test-only mock cycle produced them.

## Step 7 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| Protection orchestrator | Added `DrProtectionOrchestrator` and `DrProtectionOrchestratorImpl` to materialize FTCTL_DR protection readiness before a sync run dispatches to Agent. |
| Worker binding validation | Validates coordinator/source/target worker host IDs through `HostDao`; defaults coordinator/source/target bindings for ABLESTACK participation when one usable worker was supplied. |
| Replica materialization | Creates or updates the active `dr_replica` row from plan mapping JSON, sets target hypervisor, target VM name/ref/id, target power state, active side, and runtime readiness JSON. |
| Replica disk materialization | Parses disk mappings from `mapping.disks`, `diskMappings`, `volumes`, or `volumeMappings`; creates or updates `dr_replica_disk` rows with source/target refs, volume IDs, format, size, and raw mapping evidence. |
| Failure visibility | Rejects missing worker bindings or missing/invalid disk mappings before Agent dispatch; records plan/replica error state and a `PROTECTION_PREPARED` error event instead of letting the run hang. |
| Run executor integration | `DrRunExecutorImpl` now calls protection preparation only for `FTCTL_DR` `SYNC` runs before adapter execution. Preparation failures mark the run failed with a specific Cloud error code. |
| Create API start handoff | `createDrPlan` now accepts `startsync=true`, creates the plan, starts an initial async `SYNC` run through `DrRunService`, and returns the refreshed plan response with current state/run linkage. |
| UI create flow | DR plan create modal now accepts mapping JSON, schedule JSON, policy JSON, quiesce policy JSON, and a `Start sync after create` switch. The UI still only calls the API and refreshes state; it does not block on sync completion. |
| Locale coverage | Added English and Korean UI labels/messages for the new create fields and initial sync accepted notification. |
| Spring wiring | Registered `drProtectionOrchestratorImpl` in the DR plugin context. |
| Unit tests | Added protection orchestrator tests for replica/disk materialization and missing disk mapping rejection; extended run executor tests to assert protection preparation precedes FTCTL_DR sync dispatch. |

## Step 7 Verification

Validation status: Passed

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| Static implementation marker scan | `rg "EVENT_PROTECTION_PREPARED|ERROR_WORKER_BINDING_INVALID|drProtectionOrchestrator|startsync|label.dr.mapping.json|message.dr.create.sync.accepted" plugins ui -n` | Passed. New constants, executor wiring, API parameter, UI fields, and locale markers are present. |
| Locale JSON parse | `python3 -m json.tool ui/public/locales/en.json` and `python3 -m json.tool ui/public/locales/ko_KR.json` from WSL ext4 clone | Passed. |
| Diff whitespace check | `git diff --check` on Step 7 changed files from WSL ext4 clone | Passed. |
| Targeted Maven test | `mvn -pl :cloud-plugin-integrations-disaster-recovery -am -Dtest=DrProtectionOrchestratorImplTest,DrRunExecutorImplTest -DfailIfNoTests=false test` from WSL ext4 clone | Passed. `DrRunExecutorImplTest`: 3 tests, 0 failures, 0 errors. `DrProtectionOrchestratorImplTest`: 2 tests, 0 failures, 0 errors. DR plugin reactor ended with `BUILD SUCCESS`. |

## Flow Coverage After Step 7

Implemented end-to-end loop:

1. UI creates a `FTCTL_DR` plan with direction, RPO/RTO, worker hosts, mapping JSON, policy/schedule JSON, and optional `startsync`.
2. `createDrPlan` stores the plan through `DrPlanServiceImpl`.
3. If `startsync=true`, API starts an initial `SYNC` run through `DrRunService`; the UI receives a plan response and remains free for other work.
4. `DrRunExecutorImpl` moves the run into dispatch/running state and invokes `DrProtectionOrchestratorImpl` before Agent dispatch.
5. Cloud validates worker hosts, materializes `dr_replica` and `dr_replica_disk` readiness state from the plan mapping JSON, records `PROTECTION_PREPARED`, and marks the plan `SYNCING`.
6. The existing Step 2 Agent adapter receives a prepared plan/profile and dispatches to the host Agent.
7. The Step 3-6 ftctl path applies the profile, starts the scheduler worker when ready, and reports runtime status through `dr-status`.
8. Cloud projection and UI refresh now have concrete DB rows for plan, replica, replica disks, run, steps, and events before the host data plane starts reporting checkpoints.

Important boundary:

Step 7 makes DR protection setup concrete at the Cloud DB/API/UI layer. It does not yet execute test failover, planned/disaster failover, failback, or reprotect semantics beyond the existing ftctl accepted/control envelopes. Those are Steps 8-10.

## Step 8 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| ftctl restore point selection | `dr-test-failover` now selects a restore point from scheduler `restore-points.jsonl` by Cloud-provided `restorePointRef`, CLI `--restore-point`, checkpoint sequence, manifest path, or latest target-ready checkpoint. |
| ftctl test session state | Added per-run test session JSON, active test session JSON, selected restore point ref/sequence, test manifest/checkpoint path, and `TESTING` runtime projection fields. |
| ABLESTACK test artifacts | For ABLESTACK targets, ftctl creates isolated qcow2 overlay artifacts from the selected target disk path using `qemu-img create -f qcow2 -F <format> -b <target>`. Durable checkpoint disks are not modified. |
| VMware boundary | VMware test failover records a metadata-only test session until the vCenter linked-clone executor is introduced; it does not reuse V2K or claim a powered-on VMware test VM. |
| Cleanup behavior | `dr-test-cleanup` marks the test session `CLEANED`, removes the active session pointer, and deletes only test artifact directories under the ftctl test-session directory. Restore point/checkpoint files remain intact. |
| Runtime JSON | `dr-status` and action responses now expose `test_session_id`, `test_session_state`, `test_restore_point_ref`, `test_restore_point_sequence`, `test_artifacts_state`, `test_artifacts_path`, and `test_artifact_count`. |
| Cloud restore point projection | `FtctlDrRuntimeProjectionAdapter` now upserts `dr_restore_point` rows from ftctl checkpoint status using stable refs such as `ftctl:<planUuid>:<checkpointSequence>`. |
| Restore point API refresh | `DrProjectionServiceImpl.listRestorePoints` refreshes ftctl projection before listing restore points, so UI/API can see newly produced scheduler checkpoints. |
| Test failover API request | `StartDrTestFailoverCmd` resolves the selected or latest target-ready restore point and adds `restorePointRef`/type/RPO evidence into the action request JSON sent through Agent. |
| UI action flow | The `startDrTestFailover` action now opens an action modal, fetches restore points, preselects the latest returned item, and sends `restorepointid` through the API. |
| Unit/selftest coverage | Added Cloud projection and action dispatch tests plus ftctl selftest for restore point selection, overlay artifact creation, status projection, and cleanup removal. |

## Step 8 Verification

Validation status: Passed

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| qemu syntax and shellcheck | `bash -n bin/ablestack_vm_ftctl.sh lib/ftctl/dr_runtime.sh lib/ftctl/dr_scheduler.sh bin/ablestack_vm_ftctl_selftest.sh && shellcheck lib/ftctl/dr_runtime.sh lib/ftctl/dr_scheduler.sh` from WSL ext4 clone | Passed. |
| qemu targeted selftest | `FTCTL_SELFTEST_CASES=selftest_case_dr_runtime_test_failover_cleanup bash bin/ablestack_vm_ftctl_selftest.sh` from WSL ext4 clone | Passed. Verified restore point ref selection, `TESTING` projection, qcow2 overlay creation, active session state, cleanup state, and artifact directory removal. |
| Cloud targeted Maven test | `mvn -pl :cloud-plugin-integrations-disaster-recovery,:cloud-plugin-hypervisor-kvm -am -Dtest=FtctlDrRuntimeProjectionAdapterTest,FtctlDrUnifiedActionAdapterTest,DrPlanServiceImplTest -DfailIfNoTests=false test` from WSL ext4 clone | Passed. 9 tests, 0 failures, 0 errors; reactor ended with `BUILD SUCCESS`. |
| UI lint | `NODE_OPTIONS=--openssl-legacy-provider npx vue-cli-service lint --no-fix src/views/infra/dr/DrPlanList.vue` from WSL ext4 clone | Passed. No lint errors; only existing Browserslist data-age warning was reported. |
| Diff whitespace check | `git diff --check` on Step 8 changed files | Passed. |

## Flow Coverage After Step 8

Implemented end-to-end loop:

1. UI user clicks `Test failover`; the action modal fetches restore points through `listDrRestorePoints`.
2. The restore point list API refreshes ftctl projection first, so scheduler checkpoint status can be imported into `dr_restore_point`.
3. UI submits `restorepointid`; `startDrTestFailover` creates an async `TEST_FAILOVER` run without blocking the browser.
4. API/backend resolves the selected restore point to an engine `restorePointRef` and stores it in the run request JSON.
5. `FtctlDrUnifiedActionAdapter` includes the request in the redacted FTCTL_DR profile and dispatches `dr-test-failover` to the coordinator Agent.
6. ftctl selects the target-ready restore point, creates a test session, materializes ABLESTACK qcow2 overlay artifacts when possible, returns `TESTING`, and exposes test-session/artifact status in `dr-status`.
7. Cloud projection maps runtime `TESTING` to plan state `TESTING`; UI refresh can show that test failover is active.
8. UI user clicks `Stop test failover`; API/backend dispatches `TEST_CLEANUP`.
9. ftctl marks the session cleaned, removes the active pointer, deletes only test artifacts, returns `READY`, and projection/UI can return the plan to ready state.

Important boundary:

Step 8 now creates and cleans isolated ABLESTACK test disk overlays, but it does not yet power on a promoted production DR target. Planned/disaster failover target promotion and power-on are Step 9. VMware linked-clone/power-on requires the dedicated vCenter executor and remains metadata-only until that executor is implemented; V2K remains out of the DR runtime path.

## Step 9 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| ftctl failover execution | `dr-failover` now runs a concrete failover worker instead of returning only the accepted envelope. The worker stops scheduler control state, records `pre-failover-check`, and executes foreground or background according to `--wait`/`FTCTL_DR_FAILOVER_FOREGROUND`. |
| Planned final checkpoint | Planned failover defaults to `request.finalSync=true` and runs one final scheduler cycle with `cycleType=failover-final` before promotion. Disaster failover skips final sync and uses the latest durable restore point already present. |
| Restore point lock | Failover selects a restore point by Cloud-provided `restorePointRef`, CLI selector, checkpoint sequence, manifest/checkpoint path, or latest durable restore point. The selected ref/sequence/manifest/checkpoint are written to runtime state and failover session JSON. |
| Target promotion boundary | ftctl records `FAILED_OVER`, `active_side=TARGET`, `target_promotion_state=PROMOTED`, and `target_power_state=POWER_ON_DELEGATED`. This explicitly keeps production target VM lifecycle/power-on under Cloud ownership instead of host-side ftctl owning Cloud VM lifecycle. |
| RTO evidence | Runtime state and status JSON now expose `failover_requested_at`, `restore_point_locked_at`, `target_promote_started_at`, `target_power_on_at`, `failover_completed_at`, and `rto_actual_seconds`. |
| Runtime JSON projection | `dr-status` and action responses now expose failover session id, mode, restore point ref/sequence, target promotion state, target power state, active side, and RTO evidence. |
| Agent selector fix | KVM Agent wrapper now passes engine `restorePointRef` to `ablestack_vm_ftctl --restore-point` when available, instead of incorrectly preferring the Cloud DB restore point id. |
| Failover API request | `startDrFailover` now resolves the selected or latest target-ready restore point, adds `mode=planned|disaster`, `finalSync`, restore point ref/type/RPO evidence, and source-fence options into async run request JSON. |
| Cloud projection | `FtctlDrRuntimeProjectionAdapter` maps ftctl `FAILED_OVER` or `active_side=TARGET` into `dr_plan.state=FAILED_OVER`, `dr_plan.active_side=TARGET`, and active `dr_replica` rows with `state=FAILED_OVER`, `active_side=TARGET`, `power_state=POWER_ON_DELEGATED`, and runtime evidence JSON. |
| UI action flow | The failover modal now fetches restore points like test failover, lets the user select a restore point, exposes a planned-only final sync switch, and sends `restorepointid`/`finalsync` through the async API call. |

## Step 9 Verification

Validation status: Passed

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| qemu syntax and shellcheck | `bash -n bin/ablestack_vm_ftctl.sh lib/ftctl/dr_runtime.sh lib/ftctl/dr_scheduler.sh lib/ftctl/dr_ablestack.sh bin/ablestack_vm_ftctl_selftest.sh && shellcheck lib/ftctl/dr_runtime.sh lib/ftctl/dr_scheduler.sh lib/ftctl/dr_ablestack.sh` from WSL ext4 clone | Passed. |
| qemu targeted selftest | `FTCTL_SELFTEST_CASES=selftest_case_dr_runtime_planned_failover_promotes_latest_checkpoint bash bin/ablestack_vm_ftctl_selftest.sh` from WSL ext4 clone | Passed. Verified final checkpoint creation, `failover-final` restore point append, `FAILED_OVER` status projection, `active_side=TARGET`, `POWER_ON_DELEGATED`, active failover session JSON, and qemu-img data-plane invocation. |
| Cloud targeted Maven test | `mvn -pl :cloud-plugin-integrations-disaster-recovery,:cloud-plugin-hypervisor-kvm -am -Dtest=FtctlDrRuntimeProjectionAdapterTest,FtctlDrUnifiedActionAdapterTest,DrPlanServiceImplTest -DfailIfNoTests=false test` from WSL ext4 clone | Passed. 11 tests, 0 failures, 0 errors; reactor ended with `BUILD SUCCESS`. |
| UI lint | `NODE_OPTIONS=--openssl-legacy-provider npx vue-cli-service lint --no-fix src/views/infra/dr/DrPlanList.vue` from WSL ext4 clone | Passed. No lint errors; only the existing Browserslist data-age warning was reported. |

## Flow Coverage After Step 9

Implemented end-to-end loop:

1. UI user clicks `Failover`; the action modal fetches restore points through `listDrRestorePoints`.
2. UI submits disaster/planned mode, optional final sync, source fence option, reason/acknowledgement, and selected `restorepointid`.
3. `startDrFailover` creates an async `FAILOVER` run and resolves `restorepointid` into an engine `restorePointRef`; the browser is not blocked by the failover runtime.
4. `FtctlDrUnifiedActionAdapter` places the request into the redacted FTCTL_DR profile and dispatches through Agent to the coordinator host.
5. The KVM Agent wrapper invokes `ablestack_vm_ftctl dr-failover --wait=false --restore-point <engine-ref> --json`; DB restore point ids no longer leak into the ftctl selector path.
6. ftctl starts the failover worker. Planned failover runs one final checkpoint cycle before promotion; disaster failover locks the latest durable restore point already available.
7. ftctl writes failover session JSON and runtime status with `FAILED_OVER`, target active side, target promotion evidence, delegated target power state, and RTO timestamps.
8. Cloud projection polls `dr-status`, updates `dr_plan` and `dr_replica`, persists projection events, and returns updated API state for UI refresh.
9. UI refresh sees the plan/replica as failed over to the target side while target VM power-on remains a Cloud-owned lifecycle step.

Important boundary:

Step 9 completes the planned/disaster failover data-plane and state-projection path for the current FTCTL_DR implementation. ftctl records target promotion and delegates target power-on to Cloud because Cloud owns VM inventory and lifecycle. VMware production target power-on still depends on the dedicated vCenter executor; V2K remains out of the DR runtime path.

## Step 10 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| ftctl reverse profile | Added reverse profile generation under `reverse-profiles/`. The engine swaps source/target endpoints, reverses all four supported directions, reverses disk mappings, removes source-side VMware CBT point-in-time hints, and records `reverseOf` metadata. |
| ftctl failback execution | `dr-failback` now validates that TARGET is active, stops scheduler control state, builds a failback reverse profile, runs one reverse checkpoint with `cycleType=failback-final`, writes failback session JSON, and returns `READY` with `active_side=SOURCE`. |
| ftctl reprotect execution | `dr-reprotect` now validates that TARGET is active, builds a reverse protection profile, runs one reverse checkpoint with `cycleType=reprotect-seed`, writes reprotect session JSON, promotes the reverse profile as the active plan profile, and returns `READY` with `active_side=TARGET`. |
| Reverse restore points | Added `reverse-restore-points.jsonl` and runtime fields for reverse manifest/checkpoint paths, sequence, reverse direction, reverse profile path, and reverse restore point path. |
| RTO evidence | Failback and reprotect status now expose requested/completed timestamps, reverse target-ready time, source promotion/power-on timestamps for failback, and `failback_rto_actual_seconds` / `reprotect_rto_actual_seconds`. |
| Runtime JSON | `dr-status` and action responses now expose failback/reprotect session ids, modes, restore point refs/sequences, manifest/checkpoint paths, active side, source/target power state, reverse direction, and worker pid fields. |
| Cloud projection | `FtctlDrRuntimeProjectionAdapter` now separates three states: failover `FAILED_OVER`, failback `READY + active_side=SOURCE`, and reprotect `READY + active_side=TARGET`. It updates `dr_plan`, `dr_replica`, runtime JSON, restore points, and projection details accordingly. |
| Cloud eligibility | `DrPlanServiceImpl` now keeps `failback` eligible when an FTCTL_DR plan is `READY` but `active_side=TARGET`, which is the expected state after reprotect. |
| Agent/API continuity | Existing `FtctlDrUnifiedActionAdapter` and KVM Agent wrapper already dispatch `FAILBACK`/`REPROTECT` as async `ablestack_vm_ftctl dr-failback` / `dr-reprotect --wait=false --json`; Step 10 completes the ftctl runtime and projection side of that path. |
| Unit/selftest coverage | Added qemu selftests for failback and reprotect reverse checkpoint execution, and Cloud unit tests for failback projection, reprotect projection, and reprotect-after-target-active failback eligibility. |

## Step 10 Verification

Validation status: Passed

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| qemu syntax and lib shellcheck | `bash -n bin/ablestack_vm_ftctl.sh lib/ftctl/dr_runtime.sh lib/ftctl/dr_scheduler.sh lib/ftctl/dr_ablestack.sh lib/ftctl/dr_vmware.sh bin/ablestack_vm_ftctl_selftest.sh && shellcheck lib/ftctl/dr_runtime.sh lib/ftctl/dr_scheduler.sh lib/ftctl/dr_ablestack.sh lib/ftctl/dr_vmware.sh` from WSL ext4 clone | Passed. |
| qemu targeted selftest | `FTCTL_SELFTEST_CASES=selftest_case_dr_runtime_failback_restores_source_after_reverse_checkpoint,selftest_case_dr_runtime_reprotect_starts_reverse_protection_checkpoint bash bin/ablestack_vm_ftctl_selftest.sh` from WSL ext4 clone | Passed. Verified reverse profile source/target disk swap, `failback-final` and `reprotect-seed` restore point append, SOURCE restore after failback, TARGET active after reprotect, active session JSON, active reverse profile update, and qemu-img data-plane invocation. |
| Cloud targeted Maven test | `mvn -pl :cloud-plugin-integrations-disaster-recovery,:cloud-plugin-hypervisor-kvm -am -Dtest=FtctlDrRuntimeProjectionAdapterTest,FtctlDrUnifiedActionAdapterTest,DrPlanServiceImplTest -DfailIfNoTests=false test` from WSL ext4 clone | Passed. 14 tests, 0 failures, 0 errors; reactor ended with `BUILD SUCCESS`. |
| Diff whitespace check | `git diff --check` on Step 10 changed files | Passed before documentation update; final check is repeated after documentation update. |

## Flow Coverage After Step 10

Implemented end-to-end loop:

1. UI user clicks `Failback` or `Reprotect`; the existing action buttons call async DR APIs and the browser remains free for other work.
2. API creates a `FAILBACK` or `REPROTECT` run; Cloud stores request context and dispatches through `FtctlDrUnifiedActionAdapter`.
3. The Agent wrapper invokes `ablestack_vm_ftctl dr-failback --wait=false --json` or `dr-reprotect --wait=false --json` on the coordinator host.
4. ftctl validates that the current active side is TARGET, builds a reverse profile for the current operation, and runs a concrete reverse checkpoint through the same scheduler driver path used by continuous replication.
5. Failback records `READY`, `active_side=SOURCE`, source promotion/power-on delegation, reverse restore point evidence, and failback RTO timestamps.
6. Reprotect records `READY`, `active_side=TARGET`, reverse protection profile activation, reverse restore point evidence, and reprotect RTO timestamps.
7. Cloud projection polls `dr-status`, imports runtime JSON into `dr_plan`, `dr_replica`, `dr_restore_point`, and projection events.
8. UI refresh sees failback as source-active ready state, or reprotect as target-active ready state with failback still eligible.

Important boundary:

Step 10 implements the failback/reprotect data-plane checkpoint and state-projection path without using V2K. VM lifecycle remains Cloud-owned: ftctl records delegated power/promotion evidence and does not directly power on Cloud or vCenter inventory. VMware reverse data movement continues through the FTCTL_DR VMware/VDDK mover contract introduced earlier, not through the migration-only V2K engine.

## Step 11 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| Run projection closure | `FtctlDrRuntimeProjectionAdapter` now reconciles the active accepted/running/dispatched `dr_run` from ftctl runtime status. Runtime success markers close the run as `SUCCEEDED`; runtime/status refresh failures close it as `FAILED`. |
| Run step/event evidence | Runtime projection now writes a `runtime-projection` `dr_run_step` and corresponding run event when it closes an accepted run, so the UI has a visible backend handoff record instead of a silent state jump. |
| Status failure handling | A failed `FtctlDrStatusAnswer` no longer leaves the accepted run open indefinitely. The active run is failed before the projection call returns a best-effort failure result. |
| Run API refresh loop | `listDrRuns` and `getDrRun` now trigger best-effort projection refresh before returning run responses, so UI polling imports ftctl state before rendering. |
| Plan detail runtime polling | `DrPlanList.vue` now starts silent runtime polling when the detail view has an active run or runtime-active plan state, and stops polling when leaving the detail context. |
| Run tab polling | `DrRunsTab.vue` now polls silently while any run is active and stops automatically once all runs are terminal. |
| Progress display | `DrRunProgress.vue` and the run table now show state-derived progress for `QUEUED`, `DISPATCHING`, `ACCEPTED`, `RUNNING`, terminal success/failure/cancel states, and error code/current step metadata. |
| Dark-mode continuity | The existing DR progress component dark-mode variables remain applied to the hardened runtime progress display. |

## Step 11 Verification

Validation status: Passed

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| Cloud targeted Maven test | `mvn -pl :cloud-plugin-integrations-disaster-recovery,:cloud-plugin-hypervisor-kvm -am -Dtest=FtctlDrRuntimeProjectionAdapterTest,FtctlDrUnifiedActionAdapterTest,DrPlanServiceImplTest -DfailIfNoTests=false test` from WSL ext4 clone | Passed. 16 tests, 0 failures, 0 errors; reactor ended with `BUILD SUCCESS`. |
| UI lint | `NODE_OPTIONS=--openssl-legacy-provider npx vue-cli-service lint --no-fix src/views/infra/dr/DrPlanList.vue src/views/infra/dr/DrRunsTab.vue src/components/dr/DrRunProgress.vue` from WSL ext4 UI clone | Passed. No lint errors; only the existing Browserslist data-age warning was reported. |
| Diff whitespace check | `git diff --check` plus trailing-whitespace scan on Step 11 changed files | Passed. No patch formatting or trailing whitespace issues. |

## Flow Coverage After Step 11

Implemented end-to-end loop:

1. UI user starts sync, test failover, failover, failback, reprotect, pause/resume, release, or cleanup through the async DR action API.
2. Cloud API/backend creates a `dr_run`, dispatches the command to Agent/ftctl, and returns immediately so the browser is not blocked.
3. UI inserts the accepted run into the detail view and starts silent polling for plan/run state.
4. `listDrRuns`, `getDrRun`, and plan detail refreshes trigger best-effort runtime projection before returning state to UI.
5. Projection polls ftctl status through Agent, imports plan/replica/restore point state, and now closes the active accepted run as `SUCCEEDED` or `FAILED` with a visible `runtime-projection` step.
6. UI polling receives the terminal run state, updates progress/error display, recalculates action eligibility through the refreshed plan/run response, and stops polling when the operation is no longer active.

Important boundary:

Step 11 hardens the asynchronous visibility and run-closure loop. It does not replace the runtime data-plane work already implemented in Steps 6-10, and it does not package/deploy artifacts. Full smoke validation, build gates, and packaging handoff remain Step 12.

## Step 12 Result

Status: Done

Implemented scope:

| Area | Implementation |
| --- | --- |
| Final ftctl smoke | Ran the full DR-focused ftctl selftest matrix covering remote key/remote-nbd helpers, runtime profile/status/cancel, control actions, ABLESTACK target prepare/full seed, VMware VDDK/preflight/mock scheduler, ABLESTACK scheduler checkpoint, test failover cleanup, planned failover, failback, reprotect, and VMware mover-required guard. |
| ftctl static gates | Ran shell syntax checks and `shellcheck` on the ftctl entrypoint and DR runtime libraries. |
| ftctl packaging handoff | Verified the RPM spec installs `lib/ftctl/*`, so `dr_ablestack.sh`, `dr_key.sh`, `dr_runtime.sh`, `dr_scheduler.sh`, and `dr_vmware.sh` are included in the packaging input. Verified DR subcommands are present in shell completion. |
| Cloud targeted tests | Re-ran the targeted DR/KVM Maven test gate after Step 11. |
| Cloud changed-module build | Ran targeted Maven `install` for the DR/KVM module set and dependencies from the WSL ext4 clone. |
| Cloud UI build | Ran the production UI build from the WSL ext4 clone and verified the built bundle contains the runtime polling/progress markers. |
| Artifact evidence | Recorded SHA256 values for the key Cloud JARs and UI app artifacts in the final handoff document. |
| Final handoff document | Added `526-cross-hypervisor-dr-final-smoke-build-handoff-20260701.md` as the deployment/retest handoff. |

## Step 12 Verification

Validation status: Passed for local smoke/build gates.

Executed checks:

| Check | Command | Result |
| --- | --- | --- |
| ftctl syntax and shellcheck | `bash -n bin/ablestack_vm_ftctl.sh bin/ablestack_vm_ftctl_selftest.sh lib/ftctl/dr_runtime.sh lib/ftctl/dr_scheduler.sh lib/ftctl/dr_ablestack.sh lib/ftctl/dr_vmware.sh lib/ftctl/libvirt_wrap.sh && shellcheck lib/ftctl/dr_runtime.sh lib/ftctl/dr_scheduler.sh lib/ftctl/dr_ablestack.sh lib/ftctl/dr_vmware.sh` from WSL ext4 qemu clone | Passed. |
| ftctl DR selftest matrix | `FTCTL_SELFTEST_CASES=<19 DR cases> bash bin/ablestack_vm_ftctl_selftest.sh` from WSL ext4 qemu clone | Passed. Exit code 0 after failback, reprotect, and VMware mover-required cases. |
| ftctl packaging input check | RPM spec grep and completion grep for `lib/ftctl/*` and `dr-*` commands | Passed. DR libraries and subcommands are included in package inputs. |
| Cloud targeted Maven test | `mvn -pl :cloud-plugin-integrations-disaster-recovery,:cloud-plugin-hypervisor-kvm -am -Dtest=FtctlDrRuntimeProjectionAdapterTest,FtctlDrUnifiedActionAdapterTest,DrPlanServiceImplTest -DfailIfNoTests=false test` from WSL ext4 Cloud clone | Passed. 16 tests, 0 failures, 0 errors; reactor ended with `BUILD SUCCESS`. |
| Cloud changed-module Maven install | `mvn -pl :cloud-plugin-integrations-disaster-recovery,:cloud-plugin-hypervisor-kvm -am -DskipTests -DfailIfNoTests=false install` from WSL ext4 Cloud clone | Passed. Reactor ended with `BUILD SUCCESS`. |
| Cloud UI lint | `NODE_OPTIONS=--openssl-legacy-provider npx vue-cli-service lint --no-fix src/views/infra/dr/DrPlanList.vue src/views/infra/dr/DrRunsTab.vue src/components/dr/DrRunProgress.vue` from WSL ext4 UI clone | Passed. |
| Cloud UI production build | `NODE_OPTIONS=--openssl-legacy-provider npm run build` from WSL ext4 UI clone | Passed. Build completed and `dist` is ready to deploy; existing asset-size and Browserslist warnings remain. |
| UI marker check | `grep -R -E 'scheduleRuntimePolling|pollRuns|progressValue|stateProgress' dist/js dist/css` | Passed. Runtime polling/progress markers are present in the built bundle. |
| Diff checks | `git diff --check` on Cloud and qemu changed source/doc files | Passed. |

## Flow Coverage After Step 12

The 12-step implementation plan is complete at the local implementation and build-gate level:

1. UI exposes DR plan creation, action buttons, restore point selection, run/event/progress views, and dark-mode-capable DR surfaces.
2. UI actions call Cloud APIs asynchronously and do not wait synchronously for DR runtime completion.
3. Cloud API creates `dr_run` records, validates eligibility, stores request context, and returns accepted run state.
4. Cloud backend dispatches FTCTL_DR actions through Agent commands to selected KVM worker/coordinator hosts.
5. Agent wrappers call `ablestack_vm_ftctl dr-* --wait=false --json` and return accepted/status/preflight/cancel answers.
6. ftctl implements continuous checkpoint scheduling, ABLESTACK drivers, VMware/VDDK driver contracts, test failover, planned/disaster failover, failback, reprotect, release, status, and cancellation paths without V2K in the production DR runtime.
7. Cloud projection imports ftctl status into plan, replica, restore point, run step, run event, and terminal run state.
8. UI silent polling receives the refreshed API state and updates button eligibility/progress without blocking the browser.
9. Local smoke/build gates pass for Cloud backend, Cloud UI, and ftctl runtime inputs.

Important boundary:

The local implementation and build gates are complete. qemu RPM artifact generation must be performed by GitHub Actions after the final commits are pushed, per repository policy. Live deployment and 4-direction environment retest are the next operational phase, not part of this local implementation gate.

## Final Handoff

Detailed final evidence is recorded in `526-cross-hypervisor-dr-final-smoke-build-handoff-20260701.md`.

Next stage: commit/push the Cloud and ftctl work, run the qemu GitHub Actions package build for the RPM artifact, deploy changed Cloud classes/UI and ftctl RPM to the target environment, then execute the live 4-direction DR retest matrix.

## 2026-07-07 Design Update: VMware Mover And Projection Convergence

Status: implemented and build-tested for the changed Cloud modules and FTCTL
runtime scripts.

Live test summary:

- Plan: `ba4f53f8-eb17-41cd-bbe6-7e746772f209`
- Run: `459cd2fa-59e4-4a59-9a4d-e1be62413390`
- FTCTL target disk mapping: passed
- Target RBD image: created
- VMware data mover: failed with `DR_VMWARE_MOVER_UNAVAILABLE`
- Cloud projection: stale, still exposed `SYNCING/ACCEPTED`

The implementation plan is now split into two linked fixes:

| Layer | Required implementation |
| --- | --- |
| UI | show runtime-effective failure and block next actions when latest run fails |
| API | expose mover capability and reload DB rows after projection refresh |
| Backend | monotonic terminal projection and Cloud-owned target materialization |
| Agent | parse final `dr-status` JSON and expose mover/status diagnostics |
| FTCTL | bundle VMware mover, preflight before target allocation, emit `moverReady`, `moverPath`, and `qemuImg` capability |
| DB | persist terminal failure into plan/run/step/replica/disk using existing columns |

Implementation notes:

- Added the bundled FTCTL mover script at `lib/ftctl/dr_vmware_mover.sh`.
- Added runtime error mappings for `DR_VMWARE_MOVER_UNAVAILABLE`,
  `DR_VMWARE_MOVER_FAILED`, and `DR_VMWARE_NBDKIT_FAILED`.
- Hardened Agent JSON parsing so progress/log lines before the final FTCTL JSON
  object do not hide terminal status projection.
- Added targeted Cloud tests for final JSON parsing and operator-readable VMware
  mover failure projection.

Detailed design:

- [542-cross-hypervisor-dr-vmware-mover-and-projection-convergence-design-20260707.md](542-cross-hypervisor-dr-vmware-mover-and-projection-convergence-design-20260707.md)

## 2026-07-07 Design Update: VMware VDDK Libdir Resolution

Status: design completed; implementation is the next step.

Live test summary:

- Plan: `8037c34e-5a50-4f4c-bc4e-16dfd54f00d1`
- FTCTL target disk mapping: passed
- Target RBD image: created
- VMware mover: reached
- Failure: `DR_VMWARE_NBDKIT_FAILED`
- Root cause: Cloud/Agent did not populate `credentials.source.vddkLibdir`,
  and FTCTL did not auto-discover the ABLESTACK compat VDDK path, so nbdkit
  attempted `/usr/lib64/vmware-vix-disklib`.

Required next implementation:

| Layer | Required implementation |
| --- | --- |
| UI | show VMware data-plane readiness separately from site connection health |
| API | expose optional `vddklibdir` override and `vmwareDataPlane` readiness |
| Backend | resolve worker VDDK capability and enrich FTCTL source credential |
| Agent | detect `/usr/share/ablestack/v2k/compat/*/vddk` and enrich missing profile hint |
| FTCTL | add VDDK libdir resolver, validate nbdkit loadability, and emit specific error codes |
| DB | store readiness/status evidence in existing JSON fields; no mandatory schema change |

Detailed design:

- [543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md](543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md)

## 2026-07-09 Design Update: Target VM Materialization After Durable Restore Point

Status: design completed; implementation is the next step.

Live test summary:

- Plan: `dd895181-7fff-43cc-bae6-24a5ab529db8`
- Run: `bd0972d7-b7a9-4f00-bc6c-e2994eb6a248`
- FTCTL status: `SYNCING`, `incremental-transfer`, `CHECKPOINT_READY`
- Restore points: present and `READY`
- Target storage: present
- Target VM/network: absent
- Cloud replica: `SKELETON_READY`, `target_vm_id=NULL`
- Cloud replica disk: `SKELETON_READY`, `target_volume_id=NULL`

The current blocker is no longer VMware source preflight, VDDK connect, CBT,
or full seed transfer. The blocker is the missing Cloud-owned target
materialization worker after a durable restore point exists.

Required next implementation:

| Layer | Required implementation |
| --- | --- |
| UI | show target materialization states separately from generic transfer progress; keep failover disabled until target materialized |
| API | expose `targetmaterializationstate`, target refs, and an async retry command |
| Backend | enqueue idempotent target materialization after restore point readiness; import/adopt target volume; deploy stopped target VM; update replica refs |
| Agent | pass Cloud-created target refs to FTCTL through a bounded async command |
| FTCTL | add `dr-target-materialized` state update command and heartbeat/RPO stale reporting |
| DB | use existing `dr_run_step`, `dr_replica`, and `dr_replica_disk` fields for materialization progress and target refs |

Detailed design:

- [547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md](547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md)

## 2026-07-14 진행 상태 보정: 대상 준비 완료 후 제어 경로 blocker

Plan `73d63741-7356-49cb-a3a6-f8a3b56597de` 검증으로 위 target
materialization blocker는 해소된 상태임을 확인했다. 대상 VM/볼륨/NIC와
Secure Boot, `io_uring`, iothreads 설정이 준비됐고 full seed 및 후속
incremental checkpoint가 목표 RPO 안에서 완료됐다.

현재 blocker는 continuous `dr-sync-start` background worker가 legacy global
FTCTL lock을 Scheduler 수명주기 전체에 걸쳐 보유하여 Test Failover,
pause, release와 같은 제어 명령이 진입하지 못하는 문제다. 따라서 전체
구현 완료 판정은 보류한다. retry 횟수 증가는 해결책이 아니며, Plan 단위
cycle/transition/checkpoint lease와 generation 기반 quiesce control 구현 후
Test Failover와 cleanup/resume까지 통과해야 한다.

상세 구현 설계:

- [553-cross-hypervisor-dr-continuous-sync-control-and-quiesce-lock-design-20260714.md](553-cross-hypervisor-dr-continuous-sync-control-and-quiesce-lock-design-20260714.md)

## 2026-07-14 Cutover Readiness Reassessment

위 global-lock blocker는 control protocol v2, Plan/cycle/transition lock,
checkpoint lease 구현과 배포로 해소됐다. 다음 blocker는 VMware checkpoint를
ABLESTACK에서 실제로 부팅 가능한 VM으로 전환하는 guest preparation path다.

현재 `dr-test-failover`는 writable artifact 생성까지만 수행하며 Linux
initramfs, Windows WinPE VirtIO, isolated test domain start, boot validation은
구현되지 않았다. 따라서 continuous sync는 검증됐지만 VMware to KVM Test
Failover와 real Failover는 아직 완료로 판정하지 않는다.

32.x preflight에서 V2K guest-preparation 자산, libguestfs, qcow2 overlay,
RBD snapshot/clone은 모두 실행 가능함을 확인했다. 구현 기준:

- [554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md](554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md)

## 2026-07-14 VMware-to-KVM Guest Preparation Implementation

Status: implementation, changed-module build, deployment, and retest cleanup
completed. Runtime acceptance is the next operator test.

- FTCTL commit `741d76a36d8f8d3aefd96bd6f7e69ae95bc8a277` implements
  V2K-compatible guest preparation, qcow2/RBD writable test layers, transient
  test domains, cleanup, and the `CUTOVER_READY` Cloud hand-off.
- Cloud implements typed UI/API fields, Agent status projection, cutover
  session/disk persistence, capability gating, and idempotent target VM power-on.
- Changed Cloud Maven modules, DR/KVM tests, and the UI production build passed.
- FTCTL RPM `ablestack_vm_ftctl-0.9.1-1.noarch` is deployed to all three 32.x
  hosts; changed Cloud classes and UI assets are deployed to management.
- Retest cleanup result: active Plans 0, replicas 0, Runs 0, cutover rows 0,
  transient test domains 0, and RBD test clones 0.
- The deployment-readiness gate is PASS. Test Failover, boot validation, Stop
  Test Failover cleanup, and planned Failover remain the required runtime
  acceptance sequence.

Detailed result and acceptance boundary:

- [554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md](554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md#21-implementation-and-deployment-result-2026-07-14)

## 2026-07-14 VMware CBT Incremental And Transfer Metrics Reassessment

Status: code-level design and read-only live preflight completed; implementation
has not started.

- Source VM `vm-4486` has VMware CBT enabled.
- Earlier S1/S2 live validation proved that an S1 changeId remains usable after
  S1 removal and returned two changed areas totaling 131072 bytes for S2.
- The deployed FTCTL mover still performs complete `qemu-img convert` copies
  and does not call `QueryChangedDiskAreas`.
- Cloud DB has no typed cycle/per-disk history tables and Run status has no
  transfer-byte proof.
- Repeated green RPO cycles therefore prove durable repeated replication, but
  do not yet prove true CBT incremental transfer.

Detailed implementation design and acceptance gates:

- [555-cross-hypervisor-dr-vmware-cbt-incremental-and-transfer-metrics-design-20260714.md](555-cross-hypervisor-dr-vmware-cbt-incremental-and-transfer-metrics-design-20260714.md)

## 2026-07-16 VMware CBT Live Acceptance Correction

Status: runtime acceptance FAIL; corrective design completed, implementation
pending.

- Sequence 1 copied the VMware disk to RBD, then failed in per-disk metrics
  serialization because jq reserved keyword `$label` was used.
- No baseline or restore point was committed; the physical RBD is uncommitted
  evidence and cannot be used for Test Failover.
- Full FTCTL status was persisted as an error string, causing invalid Plan API
  JSON for a UTF-8 datastore path and making UI data appear missing.
- The correction adds a Plan-scoped cycle journal, typed commit/error fields,
  concise Backend error persistence, valid API serialization tests, and UI
  last-good-state retention.

Detailed corrective design:

- [557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md](557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md)

## 2026-07-16 Cycle Commit And API Recovery Implementation

Status: implementation, changed-module build, deployment, and failed-Plan
cleanup complete. Fresh runtime acceptance is next.

- FTCTL commit `46ec5b9648fdd3d695201c37db65a09d192f7e48` replaces the jq result
  expression with a validated Python builder and adds atomic cycle journals.
- Typed commit/error fields now flow through Agent, Backend, API, DB
  projection, and UI without copying the complete status object into operator
  error text.
- FTCTL self-test, 10 KVM tests, 11 DR projection tests, changed Maven module
  packaging, and the UI production build passed.
- FTCTL RPM, Agent classes, management classes, and UI static assets are
  deployed; services and timers are active and `/client/` returns HTTP 200.
- Plan `538befc6-0efb-4304-ba1a-5243311de4fb` was released, soft-deleted, and
  its uncommitted RBD/runtime cleaned. The Plan-owned VMware snapshot is absent.
- The environment is clean for a new `FULL_SEED` and measured
  `CBT_INCREMENTAL` test. Test Failover remains blocked until those runtime
  acceptance gates pass.

Detailed implementation evidence:

- [557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md](557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md#16-implementation-build-deployment-and-cleanup-result)

## 2026-07-17 Repeated Full ReSeed Reassessment

Status: code-level corrective design complete; implementation pending.

- Linux and Windows both pass target materialization, full-copy durability,
  Scheduler continuity, source CBT enablement, and baseline changeId commit.
- They fail continuous incremental acceptance because every later sequence is
  promoted to `FULL_RESEED`.
- FTCTL drops `baselineState` and `baselineGeneration` while converting the
  committed disk map into mover rows, then mistakes the missing generation for
  an invalid baseline.
- Cloud can leave a completed Windows cycle in `TRANSFERRING` because it
  projects only the already-advanced current sequence.
- Normal Test Failover/planned Failover must remain blocked until a measured
  incremental or verified no-change checkpoint is persisted end to end.

The next implementation unit is the FTCTL row/mode contract, reseed circuit
breaker, Agent fields, dual cycle projection, DB decision fields, API reason
codes, and UI evidence display defined in:

- [559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md](559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md)

## 2026-07-19 Test Failover Ownership Design Status

The Cloud-managed Test Failover lifecycle is designed but not implemented.
Current FTCTL still creates an unmanaged `ftctl-dr-test-*` domain. The next
implementation unit must replace that path with FTCTL artifact preparation and
Cloud-managed temporary volume/VM/network lifecycle as defined in:

- [561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md](561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md)

## 2026-07-27 Failback Commit Convergence Corrective Design

Status: implementation and changed-module validation completed; deployment and
live failback retest are the remaining acceptance steps.

- Plan `2514a846-64a2-4bc7-ba88-38a874410782`은 TARGET VM이 실행 중인데
  Cloud Plan은 `READY/TARGET`, Replica는 `ERROR/TARGET`, FAILBACK Run과
  session은 실패, FTCTL scheduler는 generation 15로 실행 중인 모순 상태다.
- 직접 원인은 worker startup의 `scheduler-start` generation이 commit
  caller generation을 덮는 경합과 Agent `Script`의 drained stream 재읽기다.
- 기존 FTCTL failback selftest는 scheduler resume 함수를 stub 처리하므로
  실제 generation 경합을 검증하지 않는다. origin commit의 targeted test가
  PASS하는 것을 WSL ext4 preflight로 재확인했다.
- 다음 구현 단위는 single-generation scheduler bootstrap, commit journal,
  typed Agent outcome, Backend `COMMIT_VERIFYING`, scheduler-first rollback
  fence, DB CAS evidence, canonical eligibility/UI 상태다.

상세 설계:

- [575-cross-hypervisor-dr-failback-commit-convergence-and-rollback-fencing-design-20260727.md](575-cross-hypervisor-dr-failback-commit-convergence-and-rollback-fencing-design-20260727.md)
- qemu `215-dr-failback-commit-generation-and-rollback-fence-design-20260727.md`

## 2026-07-27 Failback Late ACK Corrective Design Status

상태: **설계 및 실환경 read-only Preflight 완료, 구현 대기**

Plan `2514a846-64a2-4bc7-ba88-38a874410782`에서 scheduler generation 21
ACK와 후속 증분 checkpoint 463은 확인됐지만 Cloud Plan/Run/Session/Replica와
cache가 terminal 상태로 수렴하지 않았다. 이는 데이터 복제 실패가 아니라
late ACK reconciliation과 projection transaction 부재다.

구현 대기 항목:

- FTCTL late ACK commit journal reconciliation
- FTCTL operation/authority status scope 및 checkpoint 단일 스냅샷
- Agent typed status scope
- Backend transition reconciler와 실제 power 재검증
- DB terminal transaction 및 probe lease/index
- API canonical lifecycle/authority/cycle/cache schema
- UI transition polling, stale cache 표시 및 terminal action 재평가

구현 완료로 간주하려면 문서 576의 단위/통합/실환경 PASS 조건을 모두 충족해야
한다. 이 항목은 현재 구현 완료 또는 배포 완료를 의미하지 않는다.

## 2026-07-28 Current Authority Projection Follow-up

문서 578의 구현은 아직 시작하지 않았다. 다음 범위를 하나의 단계로 추적한다.

- current cutover와 historical cutover DAO 분리
- Failback terminal 시 cutover `FAILED_BACK` 종결
- canonical authority resolver와 공통 eligibility evaluator
- Protection View snapshot version 4
- UI atomic projection/action 갱신
- Failback lifecycle Run Step 보강

이 단계는 Agent/FTCTL 신규 command를 포함하지 않는다. 구현 전 management
서버의 디스크 공간과 `mysqld.service`를 정상화해야 한다.

## 2026-07-31 Test Session Blocker Design Status

실환경 plan 38에서 과거 `FAILED/cleanup_required=0/removed=NULL` Test Session과
async job 2670/2672의 `DR_TEST_SESSION_ACTIVE` 실패를 확인했다. 보호 Plan,
scheduler, 증분 cycle은 정상이며 요청은 Agent/FTCTL에 도달하지 않았다.

문서 586에 UI async acceptance, 공통 lifecycle policy, open/history DAO,
terminal soft-close, cache version 8과 재테스트 기준을 설계했다. 이 절은 설계
완료 상태이며 코드 구현/빌드/배포/기존 session reconcile은 다음 구현 단계다.

## 2026-07-31 Test Session Blocker Implementation Status

문서 586의 우선 구현 범위를 Cloud UI/API/Backend/DB projection에 반영했다.

- 공통 Test Session 차단 정책과 terminal soft-close 구현
- removed 포함 historical Test Session 조회 구현
- orchestrator와 action availability 판정 통합
- UI async job 우선 확인과 typed backend 오류 보존 구현
- UI API 단위 테스트 5건 PASS
- DR Maven 대상 테스트 46건 PASS, Checkstyle 위반 0건

이번 결함은 Cloud 접수 전에 차단된 문제이므로 Agent/FTCTL 명령 계약은
변경하지 않았다. 변경 모듈·UI 배포와 세션 7 정합성 정리는 이 구현 단계의
배포 검증 항목으로 수행한다.

배포 및 재테스트 준비까지 완료했다.

- Cloud 변경 클래스와 UI 배포 완료
- 배포 백업: `/root/ftctl-dr-deploy-20260731-142534`
- Protection View cache version 8 적용
- 세션 7 conditional soft-close 완료
- soft-close된 세션의 Run 이력 조회 교정 완료
- Plan 38 `READY/SOURCE`, scheduler `RUNNING/HEALTHY`
- `testFailover=true`, 활성 Test Session/Run 0건
- `mold=active`, `/client/` HTTP 200, `WEB-INF` 보존
