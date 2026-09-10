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

# Cross Hypervisor DR FTCTL And V2K Integration Design

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [501-cross-hypervisor-dr-domain-schema-design-20260630.md](501-cross-hypervisor-dr-domain-schema-design-20260630.md)
- [502-cross-hypervisor-dr-adapter-contract-design-20260630.md](502-cross-hypervisor-dr-adapter-contract-design-20260630.md)
- [503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md](503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md)

## 1. 목적

이 문서는 기존 FTCTL과 V2K 자산을 `Cross Hypervisor DR` 공통 모델에 흡수하는 방식을 구체화한다.

2026-07-01 기준 9단계 구현에서는 기존 FTCTL/V2K 구현을 변경하지 않고, Cloud DR adapter가 기존 `import_vm_task`를 참조해 V2K phase1/phase2 상태를 `DrRunStep`, `DrReplica`, `DrPlan`으로 투영하는 wrapper 경로까지 반영했다.

## 2. 핵심 원칙

- 기존 `rbd -> rbd`, `rbd -> qcow2`, `qcow2 -> rbd`, `qcow2 -> qcow2` FT 보호 성공 경로는 변경하지 않는다.
- `DrPlan`은 FTCTL을 대체하지 않는다. FTCTL 보호를 감싸고 상태를 투영한다.
- V2K는 사용자가 직접 단계별로 다루는 import tool이 아니라 `VMWARE_TO_KVM` direction의 engine으로 감싼다.
- failback과 adopt/promote는 controller boundary를 흐리지 않게 분리한다.
- 기존 API는 호환 유지하고, 신규 DR API는 기존 API를 내부 adapter로 호출할 수 있다.

## 3. KVM to KVM: FTCTL adapter mapping

`KVM_TO_KVM`은 기존 FTCTL cloud-managed 보호 경로를 사용한다.

| Dr 모델 | FTCTL 모델 |
| --- | --- |
| `DrPlan` | `FtctlProtection` |
| `DrPlan.engine_binding_type` | `FTCTL` |
| `DrPlan.engine_binding_id` | `ftctl_protection.id` |
| `DrReplica` | standby VM/remote replica resources |
| `DrReplica.runtime_state_json` | FTCTL status/check/event projection |
| `DrRun(type=SYNC)` | register/protect/reconcile 또는 FTCTL protect-start |
| `DrRun(type=FAILOVER)` | `failoverFtctlProtection` |
| `DrRun(type=FAILBACK)` | `failbackFtctlProtection` 또는 `failbackFtctlDrReplica` |
| `DrRun(type=REPROTECT)` | FTCTL reprotect flow |

Adapter 역할:

- `KvmSourceAdapter`: Cloud VM, volume, network, host 상태 조회
- `FtctlReplicationEngine`: 기존 FTCTL profile/protect/check/status 호출
- `KvmTargetAdapter`: standby VM/volume projection 조회
- `FtctlFencingAdapter`: confirm/clear fence API 호출

## 4. FTCTL 상태 projection

FTCTL은 runtime source of truth를 유지한다. `DrReplica.runtime_state_json`은 projection이며 원본을 대체하지 않는다.

Projection 필드:

| field | source |
| --- | --- |
| `ftctlProtectionId` | `ftctl_protection.id` |
| `primaryVmId` | `ftctl_protection.primary_vm_id` |
| `secondaryVmId` | `ftctl_protection.secondary_vm_id` 또는 details |
| `protectionState` | FTCTL status |
| `transportState` | FTCTL status |
| `activeSide` | FTCTL status |
| `standbyState` | FTCTL status/check |
| `lastError` | FTCTL status |
| `lastEvent` | FTCTL events |
| `xcoloState` | FTCTL xcolo state |
| `fencingState` | FTCTL fencing state |

동기화 규칙:

- UI refresh는 FTCTL status/check/event를 읽고 projection을 갱신할 수 있다.
- projection 갱신 실패는 FTCTL 자체 실패로 간주하지 않는다.
- FTCTL profile/state 파일을 Cloud DB projection으로 덮어쓰지 않는다.
- stale `/run/ablestack-vm-ftctl` state가 의심될 때는 기존 FTCTL 진단 절차를 우선한다.

## 5. FTCTL action mapping

| 신규 action | 기존 FTCTL API | 비고 |
| --- | --- | --- |
| `createDrPlan` | optional `registerFtctlProtection` | KVM-to-KVM plan 생성 시 내부 호출 가능 |
| `syncDrPlan` | `getFtctlCheck`, `getFtctlProtection`, `protect-start` 계열 | 기존 보호 시작 경로 보존 |
| `testFailoverDrPlan` | Phase 1에서는 미지원 | FTCTL test mode 별도 설계 필요 |
| `failoverDrPlan` | `failoverFtctlProtection` | fencing/manual-block 유지 |
| `failbackDrPlan` | `failbackFtctlProtection`, `failbackFtctlDrReplica` | controller boundary에 따라 선택 |
| `reprotectDrPlan` | 기존 FTCTL reprotect flow | replica readiness guard 유지 |
| `deleteDrPlan` | `releaseFtctlProtection`, `releaseFtctlDrReplicaProtection` | forced cleanup은 명시적 승인 필요 |

## 6. Controller boundary

기존 DR 작업에서 확인된 중요한 운영 구분을 유지한다.

Source-controller failback:

- 원래 source Mold가 보호 관계를 알고 있다.
- 원래 source로 되돌리는 절차다.
- FTCTL failback/reprotect action이 중심이다.

Replica-controller disaster recovery:

- source site가 완전히 사라졌거나 제어 불가능한 상태다.
- replica site가 VM을 adopt/promote한다.
- `adoptFtctlDrReplica`는 failback과 다른 action으로 유지한다.

공통 DR API 표현:

| 상황 | API | 내부 adapter |
| --- | --- | --- |
| source와 target 모두 제어 가능 | `failbackDrPlan` | FTCTL failback |
| source 제어 불가, replica를 운영 VM으로 채택 | `adoptDrReplica` 또는 `failoverDrPlan(disaster=true)` | FTCTL adopt |
| 역할 전환 후 보호 재구성 | `reprotectDrPlan` | FTCTL reprotect |

## 7. V2K integration mapping

`VMWARE_TO_KVM`은 기존 V2K/import workflow를 `DrPlan` 아래로 감싼다.

| Dr 모델 | V2K/import 모델 |
| --- | --- |
| `DrPlan` | V2K migration/import intent |
| `DrRestorePoint` | phase1 sync checkpoint |
| `DrReplica` | target Cloud VM/volume |
| `DrRun(type=SYNC)` | V2K phase1 |
| `DrRun(type=FAILOVER)` | V2K phase2/cutover |
| `DrRun(type=FAILBACK)` | reverse path 미정, 별도 설계 필요 |

Adapter 역할:

- `VmwareSourceAdapter`: VMware VM inventory, snapshot, disk metadata 조회
- `V2kReplicationEngine`: phase1 반복 sync 실행
- `V2kToKvmMaterializer`: target Cloud volume/VM 준비
- `KvmTargetAdapter`: Cloud VM power/network validation

## 8. V2K 단계 모델링

`SYNC` run:

1. VMware source inventory 조회
2. compatibility check
3. phase1 sync 실행
4. target Cloud volume/VM skeleton 상태 기록
5. `DrRestorePoint.state=SOURCE_READY` 또는 `TARGET_READY` 기록

`FAILOVER` run:

1. source fencing/manual confirm
2. 마지막 phase1 checkpoint 선택
3. phase2 cutover 실행
4. target Cloud VM boot
5. `DrPlan.state=FAILED_OVER`

`REPROTECT`:

- 초기 범위에서는 미지원으로 둔다.
- VMware target adapter 또는 reverse V2K path가 정의된 뒤 지원한다.

## 9. 기존 API 호환 전략

FTCTL API:

- 기존 UI/운영자가 쓰는 FTCTL API는 유지한다.
- 신규 DR UI는 KVM-to-KVM plan에서 FTCTL API 대신 `DrPlan` API를 호출할 수 있다.
- 두 UI가 동시에 같은 VM을 조작하지 않도록 plan 생성 시 기존 `ftctl_protection` active row를 확인한다.

DisasterRecoveryCluster API:

- 기존 cluster/pairing 생성은 `DrSitePair`로 투영 가능해야 한다.
- 기존 promote/demote/resync API는 신규 orchestrator API wrapper가 될 수 있다.
- 단, 기존 API semantic이 cluster 단위이고 신규 모델이 VM plan 단위임을 UI에서 명확히 해야 한다.

V2K API:

- `importUnmanagedInstanceForAblestackV2K`는 내부 worker action으로 감싼다.
- 사용자는 `DrPlan` 기준으로 sync/failover를 실행한다.
- V2K 상세 로그는 `DrRunStep.details_json`과 event로 노출한다.

현재 구현 상태:

- `V2kDrMigrationAdapter`는 기존 V2K command를 직접 조립하지 않는다.
- `DrPlan.engineBindingId` 또는 run/mapping JSON의 `importVmTaskId`/`importVmTaskUuid`로 기존 `import_vm_task`를 찾는다.
- `SYNC`는 task의 `Phase1_Completed` 또는 동등한 `currentPhase/migrationState`를 확인해 `v2k-phase1` step을 기록하고 `DrPlan.state=PHASE1_READY`로 표시한다.
- `FAILOVER`는 task의 `Phase2_Completed` 또는 동등한 상태를 확인해 `v2k-phase2` step을 기록하고, 완료 전에는 `DR_V2K_PHASE1_REQUIRED`, `DR_V2K_PHASE2_REQUIRED`, `DR_ENGINE_BUSY`로 차단한다.
- 실제 V2K phase1/phase2 실행 소유권은 기존 `importUnmanagedInstanceForAblestackV2K`/`import_vm_task` 경로에 둔다.

## 10. Lock과 충돌 방지

KVM-to-KVM:

- `DrRun` lock을 먼저 잡고 FTCTL action을 호출한다.
- FTCTL lock/profile conflict가 발생하면 `DR_ENGINE_BUSY`로 변환한다.
- Cloud projection 업데이트 실패가 FTCTL runtime cleanup을 유발하면 안 된다.

VMware-to-KVM:

- V2K phase1과 phase2는 같은 plan에서 병렬 실행하지 않는다.
- target VM name/volume ownership marker를 확인한다.
- 기존 import task가 running이면 새 `DrRun`은 기존 task를 추적하거나 busy로 실패한다.

## 11. 수용 기준

FTCTL adapter 수용 기준:

- 기존 FTCTL API 호출 없이도 `DrPlan`에서 FTCTL 상태를 볼 수 있다.
- 기존 FTCTL API로 만든 보호도 `DrPlan`에 연결하거나 import할 수 있는 경로가 있다.
- KVM-to-KVM failover/failback은 기존 성공 로직과 동일한 결과를 낸다.
- forced release/adopt는 명시적 사용자 승인 없이 실행되지 않는다.

V2K adapter 수용 기준:

- VMware source VM을 `DrPlan` source로 등록할 수 있다.
- phase1 상태가 `DrRunStep`에 표시된다.
- phase2 완료 전 failover는 phase-specific error로 차단된다.
- reverse failback 미지원 상태는 API/UI에서 명확히 표시된다.

## 12. 구현 전 확인 과제

1. 기존 FTCTL 보호를 신규 `DrPlan`으로 자동 import할지, 수동 연결할지 결정해야 한다.
2. FTCTL projection 갱신 주기를 기존 FTCTL UI refresh와 공유할지 별도 scheduler로 둘지 정해야 한다.
3. V2K phase1이 반복 sync로 안정 동작하는지 실측해야 한다.
4. V2K phase2와 DR failover의 source fencing 순서를 명확히 해야 한다.
5. VMware-to-KVM failback은 별도 제품 범위로 분리할지 Phase 5에 포함할지 결정해야 한다.

## 13. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| FTCTL 통합 | FTCTL API/UI가 KVM-to-KVM 보호를 직접 관리 | FTCTL을 `DrPlan`의 KVM-to-KVM replication engine으로 감싼다 |
| V2K 통합 | V2K import 흐름이 DR plan과 분리 | V2K phase1/phase2를 `DrRunStep`과 event로 추적 |
| 기존 성공 경로 | FTCTL 성공 로직이 독립적으로 동작 | rbd/qcow2 성공 경로는 유지하고 Cloud projection만 추가 |
| failback/adopt | FTCTL action별 의미가 화면에 직접 노출 | source-controller failback과 replica-controller adopt를 API/UI에서 분리 |
| 충돌 관리 | FTCTL lock과 V2K task가 개별 관리 | DrRun lock, FTCTL lock, V2K ownership marker를 함께 검증 |
