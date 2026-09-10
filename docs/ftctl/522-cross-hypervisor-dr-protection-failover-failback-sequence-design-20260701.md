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

# Cross Hypervisor DR Protection, Failover, Failback Sequence Design

> 2026-08-06 forward cutover terminal ordering: document
> [599](599-cross-hypervisor-dr-forward-cutover-commit-contract-and-authority-convergence-design-20260806.md)
> requires target power/boot evidence, typed FTCTL commit ACK, and only then the
> atomic Cloud TARGET terminal transaction.

> 2026-07-31 latest correction: Failback never clears SOURCE production fencing
> before TARGET stop. Reprotect preserves SOURCE isolation. See document 587.

> Real Failover projection convergence update (2026-07-27):
> [577-cross-hypervisor-dr-failover-projection-evidence-and-compensation-design-20260727.md](577-cross-hypervisor-dr-failover-projection-evidence-and-compensation-design-20260727.md)
> requires exact completed-cycle evidence, bounded retry for missing evidence,
> hard rejection for conflicting evidence, and atomic Run/Session/Plan/Replica
> convergence when Failover preparation is compensated.

> Normative Test Failover update (2026-07-19):
> [562-cross-hypervisor-dr-test-artifact-contract-and-projection-isolation-design-20260719.md](562-cross-hypervisor-dr-test-artifact-contract-and-projection-isolation-design-20260719.md)
> governs the Test Failover sequence. Cloud creates the durable Test Session
> before dispatch and owns all temporary Cloud resources. FTCTL owns only the
> checkpoint lease, writable artifacts, guest preparation, and artifact cleanup.
>
> Terminal convergence update (2026-07-20):
> [563-cross-hypervisor-dr-test-failover-terminal-convergence-design-20260720.md](563-cross-hypervisor-dr-test-failover-terminal-convergence-design-20260720.md)
> requires the finite Test Failover Run to become `SUCCEEDED` after Cloud boot
> validation while the Test Session remains `ACTIVE` until explicit cleanup.
>
> Real Failover correction (2026-07-22):
> [567-cross-hypervisor-dr-real-failover-cutover-manifest-and-rollback-design-20260722.md](567-cross-hypervisor-dr-real-failover-cutover-manifest-and-rollback-design-20260722.md)
> defines read-only cutover preflight, planned/disaster source isolation,
> `CUTOVER_READY`, Cloud target boot validation, promotion, and pre-promotion
> rollback. It supersedes earlier implicit manifest and promotion sequences.
>
> Reprotect authority preservation update (2026-07-23):
> [570-cross-hypervisor-dr-reprotect-canonical-authority-preservation-design-20260723.md](570-cross-hypervisor-dr-reprotect-canonical-authority-preservation-design-20260723.md)
> requires a committed TARGET authority snapshot, an Agent-confirmed target
> `PowerOn` preflight, and operation-only failure semantics. A delegated Run
> must not derive authority from mutable FTCTL `status.state`.

작성일: 2026-07-01

상위 계획: [520-cross-hypervisor-dr-full-implementation-work-plan-20260701.md](520-cross-hypervisor-dr-full-implementation-work-plan-20260701.md)

관련 설계:

- [521-cross-hypervisor-dr-full-stack-implementation-design-20260701.md](521-cross-hypervisor-dr-full-stack-implementation-design-20260701.md)
- [523-cross-hypervisor-dr-ftctl-engine-driver-design-20260701.md](523-cross-hypervisor-dr-ftctl-engine-driver-design-20260701.md)

## 1. 목적

이 문서는 DR 보호 설정, failover, failback 흐름을 UI/API/Backend/Agent/FTCTL 단위로 고정한다.

모든 방향은 같은 run model을 사용한다.

| 방향 | source | target |
|---|---|---|
| ABLESTACK -> VMware | KVM/QMP dirty bitmap | VMware VDDK/VMDK |
| VMware -> VMware | VMware CBT/VDDK | VMware VDDK/VMDK |
| ABLESTACK -> ABLESTACK | KVM/QMP 또는 기존 FTCTL/xcolo | ABLESTACK RBD/QCOW2 |
| VMware -> ABLESTACK | VMware CBT/VDDK | ABLESTACK RBD/QCOW2 |

## 2. 공통 action lifecycle

```mermaid
stateDiagram-v2
  [*] --> NEW
  NEW --> ENABLED: createDrPlan + enableDrPlan
  ENABLED --> SYNCING: startDrSync
  SYNCING --> READY: target-ready checkpoint
  READY --> TESTING: startDrTestFailover
  TESTING --> READY: stopDrTestFailover
  READY --> FAILING_OVER: startDrFailover
  FAILING_OVER --> FAILED_OVER: target promoted
  FAILED_OVER --> FAILING_BACK: startDrFailback
  FAILING_BACK --> READY: original source promoted
  FAILED_OVER --> REPROTECTING: startDrReprotect
  REPROTECTING --> READY: reverse protection ready
  READY --> DISABLED: disable/release
  SYNCING --> ERROR: engine failure
  FAILING_OVER --> ERROR: promotion failure
  FAILING_BACK --> ERROR: reverse failure
```

## 3. DR 보호 설정 흐름

### 3.1 UI 흐름

1. 사용자가 direction을 선택한다.
2. source site와 VM을 선택한다.
3. target site와 storage/network mapping을 선택한다.
4. RPO/RTO target을 입력한다.
5. preflight를 실행한다.
6. plan을 생성한다.
7. plan을 enable한다.
8. sync를 시작한다.
9. target-ready restore point가 만들어질 때까지 진행률을 표시한다.

### 3.2 Sequence

```mermaid
sequenceDiagram
  participant U as User
  participant UI as Cloud UI
  participant API as Cloud API
  participant B as Backend
  participant A as Mold Agent
  participant F as FTCTL_DR
  participant S as Source Driver
  participant T as Target Driver

  U->>UI: 보호 설정 입력
  UI->>API: checkDrPlanPreflight
  API->>B: validate direction/mapping/worker
  B->>A: FtctlDrPreflightCommand
  A->>F: dr-plan-apply --dry-run
  F->>S: source capability check
  F->>T: target capability check
  F-->>A: preflight result
  A-->>B: answer
  B-->>API: preflight response
  API-->>UI: preflight result
  UI->>API: createDrPlan
  API->>B: persist DrPlan, mapping, policy
  UI->>API: enableDrPlan
  API->>B: mark ENABLED
  UI->>API: startDrSync
  API->>B: create DrRun QUEUED
  B-->>API: accepted run
  API-->>UI: run accepted
  B->>A: FtctlDrActionCommand action=SYNC
  A->>F: dr-sync-start
  F->>S: full seed / incremental source read
  F->>T: write target extents
  F->>F: validate + checkpoint
  F-->>A: status/events
  A-->>B: status report
  B->>B: enqueue target materialization when durable checkpoint exists
  B->>B: import/adopt target volumes and deploy stopped target VM
  B->>A: dr-target-materialized(target refs)
  A->>F: update target refs in runtime state
  B-->>UI: polling shows target-ready only after target VM refs exist
```

### 3.3 Target materialization gate

The protection flow must not treat a durable restore point as a usable DR
target by itself. VMware-to-ABLESTACK sync can finish disk transfer and still
have no Cloud-visible target VM, volume references, or network binding. In that
state UI/API must show `target-materializing`, not `target-ready`.

The Cloud backend owns this boundary:

1. Detect durable checkpoint plus missing target VM refs from
   `FtctlDrRuntimeProjectionAdapter`.
2. Queue `DrTargetMaterializationService` asynchronously.
3. Import or adopt the seeded target disk artifacts into managed Cloud volumes.
4. Deploy a stopped target VM from the imported root volume and selected
   compute/network/offering placement.
5. Persist `dr_replica.target_vm_id`, `dr_replica.target_external_ref`, and
   `dr_replica_disk.target_volume_id`.
6. Send `dr-target-materialized` through Agent to FTCTL so runtime
   `dr-status` exposes `target_vm_present=true`,
   `target_network_present=true`, and `target_materialized=true`.

Failover, test failover, and failback eligibility must require the target
materialization evidence above. This keeps the UI asynchronous while preventing
the 40 percent stuck state from being mistaken for a transfer still in
progress.

### 3.4 Backend step 설계

| step | `DrRunStep.name` | 설명 |
|---|---|---|
| 1 | `validate-plan` | direction, engine, state, mapping 검증 |
| 2 | `dispatch-agent` | `FtctlDrActionCommand` 전송 |
| 3 | `apply-profile` | FTCTL profile 저장 |
| 4 | `preflight-source` | source driver 검증 |
| 5 | `preflight-target` | target driver 검증 |
| 6 | `full-seed` | 최초 전체 데이터 전송 |
| 7 | `incremental-sync` | 변경 영역 전송 |
| 8 | `validate-target` | flush/checksum/manifest 검증 |
| 9 | `publish-restore-point` | target-ready restore point 생성 |
| 10 | `update-rpo` | RPO lag 계산 |

### 3.5 방향별 보호 설정 차이

| 방향 | source preflight | target preflight | full seed | incremental |
|---|---|---|---|---|
| ABLESTACK -> VMware | QMP access, disk map, bitmap support | vCenter, datastore, network, VDDK writer | KVM disk export to VMDK | QMP dirty extents |
| VMware -> VMware | vCenter, CBT, VDDK reader | vCenter, datastore, network, VDDK writer | VDDK read to VMDK | CBT changed extents |
| ABLESTACK -> ABLESTACK | QMP/FTCTL profile, storage map | RBD/QCOW2 target, standby VM | 기존 FTCTL seed 또는 QMP export | 기존 xcolo/dirty extents |
| VMware -> ABLESTACK | vCenter, CBT, VDDK reader | ABLESTACK storage/network/VM | VDDK read to RBD/QCOW2 | CBT changed extents |

## 4. Test Failover 흐름

test failover는 운영 checkpoint를 오염시키지 않는다. target snapshot/clone/overlay 기반으로 격리된 VM을 부팅한다.

```mermaid
sequenceDiagram
  participant UI as Cloud UI
  participant API as Cloud API
  participant B as Backend
  participant F as FTCTL_DR
  participant C as Cloud VM Manager
  participant T as Target Storage Driver

  UI->>API: startDrTestFailover(planId, restorePointId)
  API->>B: DrRun TEST_FAILOVER 생성
  B->>F: dr-test-prepare
  F->>T: create snapshot/clone/overlay
  F-->>B: TEST_ARTIFACTS_READY + manifest
  B->>C: import test volumes
  B->>C: create/start Cloud test VM on selected network
  B->>C: power/QGA validation
  B-->>UI: polling result
  UI->>API: stopDrTestFailover
  API->>B: DrRun TEST_CLEANUP 생성
  B->>C: stop/expunge test VM and delete test volumes
  B->>F: dr-test-artifact-cleanup
  F->>T: remove overlay/clone and release lease
  F-->>B: TEST_ARTIFACTS_CLEANED
```

방향별 target test 방식:

| target | test 방식 |
|---|---|
| VMware | snapshot 또는 linked clone + isolated portgroup |
| ABLESTACK RBD | RBD snapshot/clone + isolated network VM |
| ABLESTACK QCOW2 | qcow2 backing overlay + isolated network VM |

## 5. Planned Failover 흐름

planned failover는 source가 살아 있는 상태를 전제로 한다. final delta sync 후 source를 fence/stop하고 target을 promote한다.

```mermaid
sequenceDiagram
  participant UI as Cloud UI
  participant API as Cloud API
  participant B as Backend
  participant F as FTCTL_DR
  participant S as Source Driver
  participant T as Target Driver

  UI->>API: startDrFailover(mode=planned, finalSync=true)
  API->>B: DrRun FAILOVER 생성
  B->>F: dr-failover --mode planned
  F->>S: quiesce or snapshot source
  F->>S: collect final changed extents
  F->>T: apply final delta
  F->>T: flush and validate target
  F->>S: fence/stop source VM
  F->>T: promote target VM
  F->>T: power on and validate service
  F-->>B: FAILED_OVER report
  B->>B: active_side=TARGET
  B-->>UI: failover complete
```

### 5.1 planned failover step

| step | 설명 |
|---|---|
| `pre-failover-check` | latest target-ready, no active test, capacity, network |
| `source-quiesce` | guest freeze/snapshot or crash-consistent marker |
| `final-delta-capture` | QMP dirty bitmap or VMware CBT final extent |
| `final-delta-apply` | target write and flush |
| `target-validation` | manifest/checksum/boot metadata |
| `source-fence` | source stop/network isolate/operator fence |
| `target-promote` | target VM attach/network/power-on |
| `service-validation` | guest agent/network check |
| `active-side-switch` | Cloud DB active side update |

## 6. Disaster Failover 흐름

disaster failover는 source가 불능일 수 있다. latest durable restore point를 사용한다.

```mermaid
sequenceDiagram
  participant UI as Cloud UI
  participant API as Cloud API
  participant B as Backend
  participant F as FTCTL_DR
  participant T as Target Driver

  UI->>API: startDrFailover(mode=disaster, acknowledgement)
  API->>B: validate acknowledgement and target-ready point
  B->>F: dr-failover --mode disaster --restore-point latest
  F->>T: lock selected restore point
  F->>T: promote target VM from durable checkpoint
  F->>T: power on and validate
  F-->>B: target promoted
  B->>B: active_side=TARGET, source_state=UNKNOWN_OR_FAILED
  B-->>UI: failover complete with RPO evidence
```

disaster failover에서는 final delta가 없을 수 있으므로 UI와 event에 실제 사용한 restore point와 RPO lag를 반드시 표시한다.

## 7. Failback 흐름

failback은 단순히 원래 VM을 켜는 작업이 아니다. target에서 운영된 변경 데이터를 original source 쪽으로 reverse sync한 뒤 planned cutback을 수행한다.

```mermaid
sequenceDiagram
  participant UI as Cloud UI
  participant API as Cloud API
  participant B as Backend
  participant F as FTCTL_DR
  participant ACTIVE as Active Target Driver
  participant ORIG as Original Source Driver

  UI->>API: startDrFailback
  API->>B: DrRun FAILBACK 생성
  B->>F: dr-failback
  F->>ACTIVE: create reverse checkpoint source
  F->>ORIG: prepare original site target
  F->>ACTIVE: full seed or reverse incremental
  F->>ORIG: apply reverse extents
  F->>F: create reverse target-ready point
  F->>ACTIVE: final delta + fence active target
  F->>ORIG: promote original source VM
  F-->>B: failback complete
  B->>B: active_side=SOURCE
  B-->>UI: plan READY or REPROTECT_REQUIRED
```

### 7.1 방향별 failback source/target reversal

| original direction | failback data direction |
|---|---|
| ABLESTACK -> VMware | VMware -> ABLESTACK |
| VMware -> VMware | VMware DR -> VMware original |
| ABLESTACK -> ABLESTACK | ABLESTACK DR -> ABLESTACK original |
| VMware -> ABLESTACK | ABLESTACK -> VMware |

즉, failback은 네 방향 driver 조합을 모두 구현해야 완성된다. 한 방향만 구현하면 failback은 반쪽이 된다.

## 8. Reprotect 흐름

reprotect는 현재 active side를 source로 삼아 반대 방향 보호를 새로 구성한다.

```mermaid
flowchart LR
  FAILED["FAILED_OVER<br/>active_side=TARGET"]
  PRE["preflight reverse direction"]
  PROFILE["create/update reverse FTCTL_DR profile"]
  SEED["reverse full seed or checkpoint reuse"]
  SYNC["reverse incremental sync"]
  READY["READY<br/>active side protected"]

  FAILED --> PRE --> PROFILE --> SEED --> SYNC --> READY
```

reprotect 완료 후 사용자는 새 active source에서 다시 failover/failback/test failover를 수행할 수 있어야 한다.

## 9. Release/Cleanup 흐름

release는 운영 VM 삭제가 아니라 보호 관계와 DR runtime 자원을 정리하는 action이다.

정리 대상:

- FTCTL_DR profile
- session lock
- temporary export/socket
- test failover clone/overlay
- stale checkpoint under retention policy
- Cloud `DrRun` active lock
- target replica, 단 force release가 아니면 latest target-ready checkpoint는 보존 가능

forced release는 별도 acknowledgement가 필요하다.

## 10. UI/API/Backend/Agent/FTCTL 책임 분리

| 계층 | 책임 |
|---|---|
| UI | action 요청, 상태 표시, acknowledgement 수집 |
| API | parameter validation, async run 접수 |
| Backend | state machine, lock, worker selection, DB projection |
| Agent | host command 실행, status polling/report |
| FTCTL | source/target driver 실행, 데이터 전송, checkpoint, failover |

UI/API는 long-running 작업을 동기식으로 처리하지 않는다.

## 11. 완료 기준

각 방향마다 아래 evidence가 있어야 완료로 본다.

| evidence | 설명 |
|---|---|
| protection setup | plan, profile, preflight, initial sync 성공 |
| incremental sync | 변경 데이터만 전송된 checkpoint 2개 이상 |
| RPO | target-ready RPO lag 계산 및 UI 표시 |
| test failover | isolated target VM boot + cleanup 성공 |
| planned failover | final delta + target promote 성공 |
| disaster failover | latest durable checkpoint promote 성공 |
| failback | reverse sync + original site promote 성공 |
| reprotect | active side 기준 보호 재구성 성공 |

한 방향이라도 위 항목이 빠지면 4개 방향 동일 수준 구현 완료로 보지 않는다.

## 12. 2026-07-14 보강: 지속 동기화 중 전환 순서

Test Failover와 planned failover는 지속 Scheduler를 강제 종료하거나
global lock이 풀리기를 재시도하는 방식으로 시작하지 않는다.

```text
queued Run
  -> transition lock
  -> quiesce request generation
  -> current cycle commit
  -> PAUSED acknowledgment
  -> latest completed checkpoint lease
  -> test/failover materialization
```

Test cleanup은 test resource와 checkpoint lease를 제거한 뒤 Scheduler를
resume하고 다음 incremental checkpoint 완료까지 확인한다. UI의 "복제본"
표현은 임의 과거 시점 복구를 의미하지 않으며, Test Failover는 가장 최근에
완료된 내구성 복제본을 사용한다.

상세 순서와 timeout/failure 계약:
`553-cross-hypervisor-dr-continuous-sync-control-and-quiesce-lock-design-20260714.md`.

## 13. 2026-07-14 보강: Guest Preparation과 VM Boot Gate

`test/failover materialization`은 다음 하위 단계로 분해한다.

```text
checkpoint lease
  -> writable layer
  -> guest inspection
  -> VirtIO preparation
  -> domain preparation
  -> VM start
  -> boot validation
```

Test Failover는 격리된 transient domain이 `TEST_RUNNING`에 도달해야 성공이다.
Stop Test Failover는 domain, writable layer, snapshot, lease를 제거하고 복제를
재개한다.

실제 Failover는 FTCTL의 `CUTOVER_READY` 이후 Cloud backend가 target VM을
시작한다. 부팅 검증 전에는 `activeSide=TARGET`으로 전환하지 않는다. Linux,
Windows, Secure Boot 및 storage별 상세 순서는 다음 문서를 따른다.

- `554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md`

### 2026-07-14 Latest-Cycle Readiness Addendum

Test Failover and Failover may lease only the latest Cloud-committed durable
cycle. A VMware incremental cycle is eligible only when its aggregate and all
disk rows have `incremental_verified=true`; see
`555-cross-hypervisor-dr-vmware-cbt-incremental-and-transfer-metrics-design-20260714.md`.

### 2026-07-19 Test Failover Sequence Ownership Addendum

The Test Failover sequence in section 4 is superseded for ABLESTACK targets.
FTCTL creates and prepares checkpoint-derived test disk artifacts only. Cloud
imports those artifacts as temporary volumes, creates and starts the temporary
test VM on a selected Cloud network, and performs VM-state validation through
Cloud/Agent. Stop Test Failover expunges the Cloud VM/volumes before FTCTL
removes artifacts and releases the checkpoint lease.

Normative sequence and failure compensation:
`561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md`.

## 2026-07-20 Continuous Protection Session Gate

Before Test Failover, Failover, Failback, or Reprotect, Cloud must verify that
the Plan has one scheduler session, no duplicate live workers, matching lease
epoch/owner identity, a fresh worker heartbeat, and matching control request
and acknowledgment generations. A terminal pause/resume `DrRun` does not own
the scheduler and cannot by itself prove continuous protection health.

A durable target with a dead, stale, or mismatched scheduler is `DEGRADED`, not
permanently `SYNCING`. Normal cutover is blocked until singleton authority is
reconciled. The normative sequence is defined in
`564-cross-hypervisor-dr-plan-scheduler-singleton-authority-design-20260720.md`.

## 2026-07-21 Test Cleanup Resume Confirmation Addendum

The Test Cleanup sequence does not end at cleanup Run success. After Cloud test
resources and FTCTL artifacts are removed, the sequence must observe matching
RUNNING control/ACK, a released checkpoint lease, and a durable checkpoint
newer than the checkpoint leased by Test Failover. That checkpoint must be
projected with the scheduler producer Run identity before normal Test Failover
or planned Failover eligibility returns.

Detailed ordering and failure semantics:
`565-cross-hypervisor-dr-post-test-cleanup-protection-projection-convergence-design-20260721.md`.
Section 19 is the current eligibility boundary: a successful cleanup operation
does not enable Test Failover until Plan-scoped scheduler authority, completed
cycle verification, DB projection, cache, and UI all converge.

## 2026-07-22 Cloud Promotion Commit Addendum

For actual Failover, FTCTL `CUTOVER_READY` is a preparation boundary and not the
terminal Run result. Cloud must start and validate the existing target VM,
durably commit `FAILED_OVER/TARGET`, acknowledge that decision back to FTCTL,
and only then complete the Failover Run. Forward sync, pause, and resume are
forbidden while TARGET owns a failed-over Plan. Post-failover protection is
reported as `FAILED_OVER_UNPROTECTED` until reverse protection is established.

The normative code-level ordering, DB fields, action truth table, and
Cloud-to-FTCTL acknowledgement contract are defined in section 13 of
`567-cross-hypervisor-dr-real-failover-cutover-manifest-and-rollback-design-20260722.md`.

## 2026-07-23 Reprotect Canonical Authority Addendum

Reprotect starts from `FAILED_OVER/TARGET`; it does not restore SOURCE
authority. The sequence is:

1. Cloud creates a `REPROTECT` Run asynchronously.
2. Backend validates the committed cutover session and target identity.
3. Agent confirms that the target VM is actually `PowerOn` on its KVM host.
4. Cloud sends one immutable authority specification to FTCTL.
5. FTCTL preserves that authority while it performs reverse-provider preflight.
6. A durable reverse seed completes before the reverse profile and scheduler
   become active.
7. Success yields TARGET-side protected READY state.

Any failure before the reverse seed is operation-only. The Run fails, but the
Plan remains `FAILED_OVER/TARGET` and is shown as
`FAILED_OVER_UNPROTECTED`. Detailed fields, errors, and retry behavior are
defined in document 570.

## 2026-07-22 Scheduler Recovery Before Transition Addendum

Failover, test failover, test cleanup, failback 같은 transition을 시작하기 전에 Cloud는
Scheduler desired state와 active side를 먼저 확인한다. Scheduler가 DEAD인
SOURCE/RUNNING Plan은 일반 sync start가 아니라 `RECOVER_SYNC`를 수행하고, 새로운
identity ACK, heartbeat, durable Cycle commit이 확인된 뒤 transition eligibility를
다시 계산한다.

TARGET/FAILED_OVER Plan에는 forward Scheduler를 자동 시작하지 않는다. 해당 상태는
오류가 아니라 failback 또는 reprotection이 필요한 권한 상태다. 상세 상태 전이와
오류 코드는 문서 568을 따른다.

## 2026-07-25 Site-Derived Failback Sequence Addendum

일반 source-controller failback은 `active_side=TARGET`인 Plan에서 시작하며
복귀 destination은 `source_site_id`로 고정한다. UI가 `current`,
`original-primary`, `new` 중 하나를 선택하지 않는다.

순서:

1. UI가 read-only failback Preflight를 조회한다.
2. Backend가 Plan state, active side, source authority, durable checkpoint,
   양쪽 Site health와 저장 credential 상태를 확인한다.
3. UI는 route summary와 blocking reason을 표시한다.
4. 사용자가 reason/acknowledgement를 제출한다.
5. API가 secret 없는 `dr_run`을 enqueue하고 즉시 반환한다.
6. dispatch 직전에 Backend가 같은 Preflight를 재실행하고 양쪽 credential을
   `DrSiteCredentialService`로 resolve한다.
7. Cloud가 VM/Site lifecycle을 제어하고 Agent/FTCTL에 data-plane action을
   전달한다.
8. FTCTL의 reverse copy/finalize 결과를 DB projection으로 UI에 표시한다.

원본 Site가 없거나 source authority가 유실된 경우 이 sequence를 강행하지
않는다. registered replacement Site를 사용하는 replica-controller recovery
또는 adopt/reprotect로 분기한다.

세부 클래스, API, DB 보정과 AS-IS/TO-BE는
[571-cross-hypervisor-dr-site-derived-failback-contract-design-20260725.md](571-cross-hypervisor-dr-site-derived-failback-contract-design-20260725.md)를
따른다.

## 2026-07-26 Cloud-Owned Failback Lifecycle Sequence Correction

위 순서의 8번은 FTCTL data-plane 완료까지만 의미한다. 전체 failback은 다음
단계를 추가로 통과해야 한다.

9. FTCTL은 `FAILBACK_DATA_READY/TARGET`을 보고한다.
10. Cloud가 TARGET VM을 정지하고 실제 `POWERED_OFF`를 확인한다.
11. Cloud가 SOURCE VM을 기동하고 boot validation `READY`를 확인한다.
12. Cloud가 Agent를 통해 `dr-failback-commit`을 전달한다.
13. FTCTL authority ACK 후 Cloud가 하나의 transaction으로 Plan, Replica,
    Run, Failback session을 `SOURCE/COMPLETED`로 확정한다.

하나라도 확인되지 않으면 Run은 성공이 아니다. 상세 계약은
[574-cross-hypervisor-dr-cloud-owned-failback-lifecycle-commit-design-20260726.md](574-cross-hypervisor-dr-cloud-owned-failback-lifecycle-commit-design-20260726.md)를
따른다.

## 2026-07-27 Failback Commit Outcome and Fenced Rollback Correction

13번 FTCTL 응답이 timeout, 빈 출력, parse 실패, `Stream closed`인 경우 이를
commit 거부로 간주하지 않는다. Cloud는 `COMMIT_VERIFYING`에서 session,
authority, scheduler generation/ACK를 조회한다.

- `ACKNOWLEDGED`: SOURCE protection resume를 계속한다.
- `REJECTED`: scheduler를 먼저 fence하고 TARGET service로 rollback한다.
- `UNKNOWN`: 자동 VM 전환을 금지하고 `COMMIT_UNCERTAIN`으로 잠근다.

rollback 완료 상태는 `READY/TARGET`이 아니라
`FAILED_OVER_UNPROTECTED/TARGET`이다. 전체 sequence와 오류 우선순위는
[575-cross-hypervisor-dr-failback-commit-convergence-and-rollback-fencing-design-20260727.md](575-cross-hypervisor-dr-failback-commit-convergence-and-rollback-fencing-design-20260727.md)를
따른다.

## 2026-07-27 Late ACK Terminal Convergence Sequence

```mermaid
sequenceDiagram
    participant R as "Lifecycle Reconciler"
    participant A as "Mold Agent"
    participant F as "FTCTL"
    participant D as "Cloud DB"
    participant U as "UI"

    R->>A: commit-status(OPERATION, failbackRun)
    A->>F: reconcile commit journal with control/ACK
    F-->>A: ACKNOWLEDGED(gen, owner, session)
    R->>A: dr-status(PLAN_AUTHORITY, plan)
    A->>F: read status plus one completed checkpoint
    F-->>A: SOURCE, RUNNING, checkpoint sequence
    R->>R: verify SOURCE ON and TARGET OFF
    R->>D: terminal transaction
    Note over D: Plan READY/SOURCE<br/>Run SUCCEEDED<br/>Session COMPLETED<br/>Replica READY/OFF/SOURCE
    R->>D: invalidate protection cache
    U->>D: poll canonical snapshot
    D-->>U: terminal state and actions
```

`COMMIT_VERIFYING`에서는 다음 사용자 action을 열지 않는다. terminal transaction
완료 뒤 eligibility를 다시 계산한다. 세부 예외와 timeout 정책은 문서 576을
따른다.

## 2026-07-28 Failback Current Authority Closure

Failback terminal transaction에는 과거 Failover의 current authority 종료가
포함된다.

```text
Cutover Session FAILED_OVER -> FAILED_BACK
Plan active side TARGET -> SOURCE
Replica active side TARGET -> SOURCE
Target VM POWERED_ON -> POWERED_OFF
Source VM POWERED_OFF -> POWERED_ON
Scheduler -> RUNNING
post-failback checkpoint > failback checkpoint
```

이후 과거 cutover session은 이력으로만 표시한다. Protection View version 4와
Plan API는 같은 current authority 및 action eligibility를 반환해야 하며,
UI는 강제 reload 없이 terminal projection을 적용한다. 상세 설계는 문서
578을 따른다.

## 2026-07-30 Failover Success Commit Addendum

대상 VM power-on과 boot validation 후 Cloud는 먼저
`CLOUD_PROMOTED/ACK_PENDING`을 저장하고, Agent의 `CUTOVER_COMMIT` ACK 이후
`FAILED_OVER/TARGET/FAILED_OVER_UNPROTECTED`를 원자적으로 커밋한다. 이때
forward scheduler desired state는 `STOPPED`이며 RPO 표시는 Failover 시점에
고정된다. 상세 순서와 오류 복구는 문서 581을 따른다.
## 2026-07-30 Failback Post-Commit Sequence Addendum

Failback reverse-final checkpoint `N`은 원본 방향 보호 재개의 baseline이다. 첫
정방향 cycle은 반드시 `N+1` 이상이어야 하며, 정규 RPO timer를 기다리지 않는
immediate validation cycle로 생성한다. UI 액션 수락부터 terminal transaction까지
전체 보강 시퀀스는 문서 583을 따른다.

## 2026-08-01 Reprotect-Before-Failback Sequence Addendum

After Failover, TARGET is authoritative but not yet reverse-protected. The
valid sequence is `Failover -> Reprotect reverse seed -> reverse incremental
protection -> final reverse delta -> Failback`. Direct Failback from
`FAILED_OVER_UNPROTECTED` is blocked.

Cloud commits SOURCE authority only after the FTCTL checkpoint proves a real
`KVM_TO_VMWARE` write and reverse guest boot readiness. Details are normative
in
[588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md](588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md).

## 2026-07-31 Test Failover Pre-Acceptance Addendum

Test Failover는 Run 생성 전에 Plan lock 안에서 이전 Test Session을
reconcile하고 blocking 여부를 재검증한다. async job이 이 단계에서 실패하면
Run/Session/Agent command는 생성하지 않는다. UI는 job 실패 원인을 표시하며
accepted Run 복구 조회를 수행하지 않는다.

terminal `FAILED + cleanup_required=false` 이력은 soft-close 후 새 테스트를
허용하고, cleanup obligation 또는 `CLEANUP_FAILED`는 새 테스트를 차단한다.
상세 상태표와 시퀀스는 문서 586을 따른다.
