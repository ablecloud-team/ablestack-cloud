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

# Cross Hypervisor DR State Machine And Worker Design

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [501-cross-hypervisor-dr-domain-schema-design-20260630.md](501-cross-hypervisor-dr-domain-schema-design-20260630.md)
- [502-cross-hypervisor-dr-adapter-contract-design-20260630.md](502-cross-hypervisor-dr-adapter-contract-design-20260630.md)

## 1. 목적

이 문서는 `Cross Hypervisor DR`의 상태 전이, worker 실행 위치, lock/idempotency, progress 관측 방식을 구체화한다.

범위는 설계까지다. 이 문서는 실제 worker, timer, async job 코드를 만들지 않는다.

## 2. 상태 관리 원칙

- `DrPlan`은 사용자가 이해하는 보호 정책 상태를 나타낸다.
- `DrRestorePoint`는 source capture와 target materialization 상태를 나타낸다.
- `DrReplica`는 target site의 VM/disk readiness를 나타낸다.
- `DrRun`은 작업 실행 상태를 나타내며, 실패 원인과 rollback context를 보존한다.
- engine별 runtime detail은 `DrRunStep.details_json`과 `DrReplica.runtime_state_json`에만 격리한다.

## 3. `DrPlan` 상태 전이

```mermaid
stateDiagram-v2
  [*] --> CREATED
  CREATED --> ENABLED: enable
  ENABLED --> SYNCING: syncDrPlan
  SYNCING --> READY: target-ready restore point
  SYNCING --> ERROR: unrecoverable failure
  READY --> SYNCING: scheduled sync
  READY --> TESTING: testFailoverDrPlan
  TESTING --> READY: test cleanup ok
  READY --> FAILED_OVER: failover ok
  FAILED_OVER --> FAILBACK_READY: reverse sync prepared
  FAILBACK_READY --> REPROTECTING: reprotectDrPlan
  REPROTECTING --> READY: reverse target ready
  ENABLED --> PAUSED: pause
  READY --> PAUSED: pause
  PAUSED --> ENABLED: resume
  ERROR --> SYNCING: retry sync
  ERROR --> PAUSED: pause
  CREATED --> REMOVED: delete
  ENABLED --> REMOVED: delete
  READY --> REMOVED: delete
```

상태 규칙:

- `READY`는 최소 하나의 `DrRestorePoint.state=TARGET_READY`와 `DrReplica.state=TARGET_READY`가 있어야 한다.
- `FAILED_OVER`는 target VM이 service VM으로 승격된 상태다.
- `FAILBACK_READY`는 reverse direction의 준비가 끝났지만 source로 cutback되지는 않은 상태다.
- `ERROR`는 plan 삭제가 아니라 운영자 개입 또는 retry가 필요한 상태다.

## 4. `DrRestorePoint` 상태 전이

```mermaid
stateDiagram-v2
  [*] --> CREATING
  CREATING --> SOURCE_READY: source snapshot/checkpoint ready
  SOURCE_READY --> MATERIALIZING: target materialization start
  MATERIALIZING --> TARGET_READY: target disk ready
  CREATING --> FAILED: source capture failed
  SOURCE_READY --> FAILED: artifact sync failed
  MATERIALIZING --> FAILED: conversion/upload/attach failed
  TARGET_READY --> EXPIRED: retention
  FAILED --> REMOVED: cleanup
  EXPIRED --> REMOVED: cleanup
```

RPO 계산:

- `source_rpo_seconds = now - source_captured_at`
- `target_ready_rpo_seconds = now - target_ready_at`
- source capture가 되어도 target materialization이 끝나지 않았으면 장애 시 사용할 수 없으므로 `target_ready_rpo_seconds`는 null 또는 이전 target-ready restore point 기준으로 표시한다.

## 5. `DrReplica` 상태 전이

```mermaid
stateDiagram-v2
  [*] --> NONE
  NONE --> SKELETON_CREATING: ensureReplicaSkeleton
  SKELETON_CREATING --> SKELETON_READY: target VM created powered off
  SKELETON_READY --> DISK_MATERIALIZING: materialize latest restore point
  DISK_MATERIALIZING --> TARGET_READY: disk attach and verify ok
  TARGET_READY --> TEST_RUNNING: test boot
  TEST_RUNNING --> TARGET_READY: cleanup ok
  TARGET_READY --> FAILED_OVER: failover power on
  TARGET_READY --> STALE: newer restore point source-ready
  STALE --> DISK_MATERIALIZING: refresh disks
  SKELETON_CREATING --> ERROR: target create failed
  DISK_MATERIALIZING --> ERROR: materialization failed
  TEST_RUNNING --> ERROR: test cleanup failed
  ERROR --> SKELETON_READY: retry if skeleton exists
  ERROR --> REMOVED: delete
```

상태 규칙:

- `SKELETON_READY`는 RTO 1시간 보장을 의미하지 않는다.
- `TARGET_READY`는 최소 최신 target disk set이 attach 가능하고, target VM metadata가 boot 가능한 상태임을 의미한다.
- `TEST_RUNNING`은 운영 network와 격리되어야 한다.

## 6. `DrRun` 상태 전이

```mermaid
stateDiagram-v2
  [*] --> QUEUED
  QUEUED --> RUNNING: worker picked
  RUNNING --> WAITING_MANUAL_CONFIRM: fencing/manual gate
  WAITING_MANUAL_CONFIRM --> RUNNING: confirm
  RUNNING --> SUCCEEDED: all steps ok
  RUNNING --> FAILED: terminal error
  RUNNING --> ROLLBACK_REQUIRED: partial destructive failure
  ROLLBACK_REQUIRED --> ROLLED_BACK: rollback ok
  QUEUED --> CANCELLED: cancel before start
  WAITING_MANUAL_CONFIRM --> CANCELLED: operator abort
```

## 7. Worker 실행 위치

Phase 1 기준 worker 정책:

- Orchestrator worker는 management server에서 실행한다.
- VMware target skeleton 생성은 management server가 VMware plugin/service를 통해 vCenter API로 수행한다.
- heavy data movement는 Phase 1 범위에 넣지 않는다.
- format conversion, VMDK upload, CBT/VADP, RBD diff는 이후 Phase에서 별도 data mover worker로 분리한다.

장기 구조:

| 작업 | 권장 실행 위치 | 이유 |
| --- | --- | --- |
| API orchestration | management server | Cloud async job, DB transaction과 가까움 |
| VMware inventory/VM create | management server 또는 VMware plugin service | vCenter API 호출 중심 |
| KVM snapshot/RBD diff | KVM host 또는 storage-aware worker | storage locality 필요 |
| qcow2/raw to VMDK conversion | data mover worker | CPU/I/O heavy |
| VMDK datastore upload | data mover worker 또는 vCenter datastore path worker | 대용량 upload |
| FTCTL xcolo/remote-nbd | KVM host FTCTL | 기존 성공 로직 보존 |
| V2K phase execution | V2K worker/current import path | 기존 V2K 흐름 재사용 |

## 8. Lock 정책

Plan 단위 lock:

- 동일 `DrPlan`에는 동시에 하나의 destructive run만 허용한다.
- `SYNC`는 이전 `SYNC`와 중복 실행하지 않는다.
- `TEST_FAILOVER`는 `SYNC`와 병행하지 않는다.
- `FAILOVER`, `FAILBACK`, `REPROTECT`, `DELETE`는 모든 다른 run과 배타적이다.

Resource 단위 lock:

- target VM 생성 시 `target_site_id + target_name` lock을 잡는다.
- datastore/VMDK path 생성 시 `target_site_id + datastore + path` lock을 잡는다.
- FTCTL adapter 호출 시 기존 FTCTL lock/profile state와 충돌하지 않게 Cloud side run lock을 먼저 잡는다.

## 9. Idempotency 정책

API retry:

- public action API는 `idempotencyKey`를 선택 입력으로 받는다.
- key가 없으면 서버가 run UUID를 생성한다.
- 동일 plan, 동일 key, 동일 run type이면 기존 run을 반환한다.

Adapter retry:

- `ensureReplicaSkeleton`은 같은 target name의 owned VM이 있으면 재사용한다.
- `materialize`는 같은 restore point와 disk path가 이미 ready이면 skip한다.
- `powerOnForFailover`는 이미 powered on이고 ownership marker가 맞으면 success로 처리한다.
- cleanup은 ownership marker와 run context가 맞을 때만 수행한다.

## 10. Progress 계산

`DrRun.progress_percent`는 step weight 기반으로 계산한다.

`SYNC` 기본 weight:

| step | weight |
| --- | --- |
| validate source/target | 5 |
| source consistency | 10 |
| create restore point | 20 |
| sync artifacts | 20 |
| materialize artifacts | 25 |
| ensure target replica | 10 |
| verify target ready | 10 |

`FAILOVER` 기본 weight:

| step | weight |
| --- | --- |
| preflight | 10 |
| fence request/verify | 35 |
| target power on | 30 |
| service validation | 15 |
| state finalize | 10 |

## 11. Failure handling

Retryable:

- temporary vCenter connection failure
- storage upload timeout
- source snapshot busy
- Cloud async job still running

Non-retryable:

- invalid datastore mapping
- unsupported guest/controller conversion
- missing or invalid backend-managed site credential
- ownership conflict on target VM/path

Manual gate:

- source VM still running when failover would create dual-active
- fencing result unknown
- target test boot uses production network

Rollback required:

- target VM was created but disk attach failed after partial attach
- failover power-on succeeded but service validation failed
- cleanup failed and target may conflict with next run

## 12. Monitoring projection

API/UI should display:

- plan state
- latest source RPO
- latest target-ready RPO
- target readiness
- current run and current step
- last successful restore point
- last target-ready restore point
- last error and retryability
- fencing/manual action required

FTCTL adapter 추가 projection:

- FTCTL protection state
- transport state
- active side
- standby state
- last event summary
- QMP/COLO state when available

VMware adapter 추가 projection:

- vCenter connection state
- target VM MoRef
- datastore path
- power state
- VMware tools status
- last task ref

## 13. 구현 전 확인 과제

1. Cloud async job table과 `dr_run`의 중복 역할을 어떻게 분리할지 결정한다.
2. Scheduler/timer가 management cluster active node에서 한 번만 돌도록 보장해야 한다.
3. Long-running worker timeout과 cancellation semantics를 정의해야 한다.
4. `WAITING_MANUAL_CONFIRM` run이 재시작 후에도 복구되도록 DB에 충분한 context를 저장해야 한다.
5. 기존 FTCTL timer/reconcile과 신규 DrRun scheduler가 같은 VM을 동시에 만지지 않도록 adapter boundary를 명확히 해야 한다.

## 14. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| 실행 상태 | Cloud async job, FTCTL state, 외부 task가 분리 | `DrRun`과 `DrRunStep`이 공통 실행 상태를 표현 |
| 장기 작업 | 기능별 polling/timeout 처리 | `DrRunExecutor`가 step 단위 timeout, retry, cancellation 처리 |
| 수동 조치 | 기능별 화면/로그에서 확인 | `WAITING_MANUAL_CONFIRM` 상태와 event/action gate로 표준화 |
| progress 표시 | FTCTL 또는 VMware task별 별도 표시 | plan/run/step projection으로 UI가 동일 방식 표시 |
| 충돌 방지 | 엔진별 lock에 의존 | plan lock과 adapter lock을 함께 사용해 중복 작업 방지 |

## 2026-07-07 Update: Accepted Run Is Not A Terminal State

For DR sync actions, `ACCEPTED` means the command was accepted for asynchronous
worker execution only. It must not be promoted to a successful plan state unless
the runtime projection shows target materialization, durable checkpoint, and
restore point evidence.

Additional state-machine constraints:

- `START_REQUESTED` or `ACCEPTED` with unresolved disk readiness remains
  pre-execution and must be blocked before Agent dispatch.
- Runtime terminal errors such as `DR_TARGET_DISK_SIZE_UNRESOLVED` move
  `dr_run.state` to `FAILED` and `dr_plan.state` to `ERROR`.
- `dr_replica.state=SKELETON_READY` is valid only after a positive-size disk map
  was materialized or explicitly reserved with target references.
- Background projection reconciliation must revisit active runs until a terminal
  run state or a target-ready state is recorded.

The full layer-by-layer design is maintained in
`538-cross-hypervisor-dr-vmware-to-kvm-disk-size-and-projection-hardening-design-20260707.md`.

## 2026-07-08 Update: Active Initial Seed Pending State

An accepted sync run can remain healthy while the target VM is absent. For
VMware to ABLESTACK, initial full-seed first creates or opens target storage,
then copies source data, then creates a durable restore point. Only after that
does target VM materialization become required.

State-machine addition:

```mermaid
stateDiagram-v2
  [*] --> ACCEPTED
  ACCEPTED --> INITIAL_SEEDING: FTCTL state=SYNCING step=full-seed-transfer
  INITIAL_SEEDING --> TARGET_MATERIALIZING: last_target_durable_at present
  TARGET_MATERIALIZING --> TARGET_READY: target VM/reference and restore point present
  INITIAL_SEEDING --> FAILED: runtime ERROR/FAILED or worker FAILED
  TARGET_MATERIALIZING --> FAILED: target VM missing after readiness grace
```

Projection rule:

- `target_vm_present=false` is not a failure while `state=SYNCING`,
  `step=full-seed-transfer`, `worker_state=RUNNING`, and runtime `error_code`
  is empty.
- `DR_TARGET_VM_NOT_FOUND` is terminal only when the runtime has reached
  `READY`/`TARGET_READY`, or when a durable restore point exists and target VM
  materialization has exceeded the backend grace window.
- See
  `546-cross-hypervisor-dr-initial-sync-pending-projection-contract-design-20260708.md`.

## 2026-07-09 Update: Target Materialization Failure Is Terminal

Validation for plan `b2a649b7-8313-4bd4-be49-5dda67993e06` proved a separate
state boundary after durable restore point creation:

- FTCTL can finish source snapshot, VDDK open, CBT check, RBD full seed, and
  restore point creation successfully.
- Cloud can import/adopt the target volume successfully.
- Target VM deployment can still fail because the selected target compute
  offering is custom and the plan does not carry `cpuNumber`, `cpuSpeed`, and
  `memory`.

State-machine addition:

```mermaid
stateDiagram-v2
  TARGET_MATERIALIZING --> TARGET_VOLUME_IMPORTED: root/data volumes imported or adopted
  TARGET_VOLUME_IMPORTED --> TARGET_VM_DEPLOYING: custom compute params resolved
  TARGET_VM_DEPLOYING --> TARGET_REF_SYNC: stopped target VM created
  TARGET_REF_SYNC --> TARGET_READY: Cloud refs synced to FTCTL
  TARGET_VM_DEPLOYING --> FAILED: DR_TARGET_VM_MATERIALIZE_FAILED
```

Projection rule:

- If the latest run has terminal `target-materialization` failure, later
  runtime polling must not demote the plan back to `SYNCING`.
- If the terminal failure is notification-only and Cloud DB target references
  plus FTCTL runtime target references later converge, projection may recover
  the run through a `target-materialization-recovered` step and
  `TARGET_MATERIALIZATION_RECOVERED` event.
- `SYNCING` remains healthy only before terminal materialization failure.
- Custom compute offering validation is part of executable preflight, not a
  best-effort target VM deploy detail.
- The full code-level contract is maintained in
  `547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md`.
- Agent action compatibility, capability preflight, state convergence, and UI
  primary-state consistency are maintained in
  `548-cross-hypervisor-dr-agent-action-compatibility-and-state-convergence-design-20260709.md`.

## 2026-07-10 Corrective Update: Correlated And Monotonic State Authority

The failed plan `0de0b865-8642-4cac-a5d7-fc864633d062` showed that a terminal
`target-materialization` failure can coexist with stale RUNNING projection
steps and an FTCTL runtime that still says `SYNCING`. The state contract is
therefore tightened:

- runtime status is applicable only when both `plan_uuid` and `run_uuid` match
  the Plan's current run;
- an active current run is shown as queued/preparing/syncing without flashing a
  previous Plan error;
- a terminal failed current run wins over any correlated runtime `SYNCING`;
- terminal runs are immutable; recovery creates a new reconcile/retry run;
- terminalizing a run closes every remaining RUNNING step;
- list and detail primary status both use backend `effectivestate`.

The authoritative transition policy, DAO changes, and AS-IS/TO-BE matrix are in
section 16 of
`548-cross-hypervisor-dr-agent-action-compatibility-and-state-convergence-design-20260709.md`.

## 2026-07-10 Normative Projection And Checkpoint State Axes

Background protection state, foreground operation state, cache freshness, and
checkpoint activity are independent axes:

```text
protectionState: READY | DEGRADED | FAILED_OVER | ERROR
operationState:  QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELED
projectionState: CURRENT | STALE | FAILED
checkpointState: IDLE | TRANSFERRING | COMPLETED | FAILED
```

A READY Plan may have checkpoint activity `TRANSFERRING`. During that window
current sequence N is not a completed synchronization checkpoint; the latest
completed sequence remains N-1. Cached/stale reads do not cause state-machine
transitions.

Detailed transitions and hard gates:
`550-cross-hypervisor-dr-protection-view-cache-and-completed-checkpoint-design-20260710.md`.

## 2026-07-14 Normative Scheduler Quiesce State Update

Background Scheduler control is an independent state axis:

```text
schedulerState: RUNNING | QUIESCE_REQUESTED | QUIESCING | PAUSED | STOPPING | STOPPED | ERROR
cycleState:     IDLE | RUNNING | COMMITTING | FAILED
transitionState: IDLE | PREPARING | TESTING | FAILING_OVER | FAILING_BACK | REPROTECTING | RELEASING
```

`RUNNING` with a completed durable checkpoint is eligible for Test Failover
when control protocol v2 is available. The foreground Run first requests
quiesce, waits for the current cycle to commit, and leases the latest completed
checkpoint. A test/control Run failure does not demote healthy protection to
`ERROR`; only a terminal data-plane failure may do so.

Detailed transitions and lock ordering:
`553-cross-hypervisor-dr-continuous-sync-control-and-quiesce-lock-design-20260714.md`.

## 2026-07-16 Replication Commit State Update

The cycle state machine is refined to:

```text
PREPARING -> SNAPSHOT_CREATED -> CBT_QUERIED -> TRANSFERRING
          -> DATA_COPIED -> METADATA_PREPARED -> LOCAL_DURABLE
          -> CLOUD_PROJECTED -> COMPLETED
```

`DATA_COPIED_METADATA_FAILED`, `LOCAL_COMMIT_FAILED`,
`CLOUD_PROJECTION_PENDING`, and `RESEED_REQUIRED` are explicit recovery states.
Only `LOCAL_DURABLE` may advance the committed CBT baseline, and only
`CLOUD_PROJECTED` may publish the user-visible completed checkpoint. Worker
retry selection is derived from journal evidence rather than the previous
percentage or generic process exit code.

Detailed transitions and recovery gates:
`557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md`.

### 2026-07-17 Incremental Decision State Addendum

Before `CBT_QUERIED`, the worker records immutable requested mode and a typed
effective-mode decision. One automatic reseed may repair a validly diagnosed
baseline defect; a repeated identical decision enters
`RESEED_LOOP_DETECTED` and stops before data copy. A new current sequence does
not erase the latest completed sequence. Detailed transitions are normative in
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.

### 2026-07-19 Cloud-Managed Test Session State Addendum

`TEST_FAILOVER` is split into engine artifact preparation and Cloud resource
materialization. The authoritative sequence is `ENGINE_PREPARING ->
ARTIFACTS_READY -> VOLUMES_IMPORTING -> VM_CREATING -> VM_STARTING ->
VM_VALIDATING -> ACTIVE`. Cleanup is `VM_STOPPING -> VM_EXPUNGING ->
VOLUME_DELETING -> ENGINE_ARTIFACT_CLEANUP -> CLEANED`. A Running raw libvirt
domain without a session-owned Cloud VM id is `DR_TEST_OWNERSHIP_INCONSISTENT`.

Detailed restart, retry, and compensation rules:
`561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md`.
