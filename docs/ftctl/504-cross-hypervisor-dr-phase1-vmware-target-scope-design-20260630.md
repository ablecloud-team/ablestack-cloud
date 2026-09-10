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

# Cross Hypervisor DR Phase 1 VMware Target Scope Design

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [501-cross-hypervisor-dr-domain-schema-design-20260630.md](501-cross-hypervisor-dr-domain-schema-design-20260630.md)
- [502-cross-hypervisor-dr-adapter-contract-design-20260630.md](502-cross-hypervisor-dr-adapter-contract-design-20260630.md)
- [503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md](503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md)

## 1. 목적

이 문서는 `Cross Hypervisor DR`의 Phase 1 범위를 VMware target adapter 기반으로 구체화한다.

Phase 1은 전체 DR 완성이 아니라, 이후 replication/materialization을 얹을 수 있는 target-side 골격을 만드는 단계다.

2026-07-01 기준 8단계 구현에서는 실제 vCenter VM 생성 전 단계의 readiness/skeleton record 경로까지 반영했다. 즉, `VMWARE_PHASE1` engine은 target site와 mapping JSON을 검증하고 `DrReplica.state=SKELETON_READY` record를 남기지만, vCenter API를 통한 실제 powered-off VM 생성, VMDK 변환/upload, disk attach, 운영 failover는 아직 수행하지 않는다.

## 2. Phase 1 목표

Phase 1의 완료 목표:

- `DrSite`로 VMware target site를 등록할 수 있다.
- `DrPlan`이 VMware target mapping을 보유할 수 있다.
- `VmwareTargetAdapter`가 target vCenter에 powered-off standby VM skeleton을 만들 수 있다.
- 생성된 standby VM은 `DrReplica.state=SKELETON_READY`로 추적된다.
- 외부에서 준비된 disk materialization 결과가 주어지면 `TARGET_READY` 검증 경로로 연결할 수 있다.

Phase 1에서 RTO 1시간 목표를 완전히 보장하지 않는다. RTO 목표는 최소 `TARGET_READY` restore point가 반복 갱신되는 Phase 2/3 이후에 검증한다.

현재 구현된 Phase 1 완료 기준:

- `VMWARE_PHASE1/VMWARE_PHASE1` replication engine이 Spring registry에 등록된다.
- `KVM_TO_VMWARE` plan만 허용하고 source KVM, target VMware site를 검증한다.
- target site는 vCenter endpoint와 backend-managed vCenter credential 또는 `vmwareDatacenterId`를 가져야 한다.
- `mapping_json`에는 `targetDatastoreRef`, `resourcePoolRef` 또는 `clusterRef`, `targetFolderPath`, `targetNetworkRef`가 있어야 한다.
- `SYNC`는 `DrReplica.state=SKELETON_READY`, `power_state=POWERED_OFF`, `hypervisor_type=VMware` record를 생성/갱신한다.
- `runtime_state_json`은 ownership marker, target mapping, `vcenterOperation=NOT_STARTED`, `materializationState=NOT_STARTED`, `targetReady=false`를 보존한다.
- `TARGET_READY` 전의 `testFailover`, `failover`, `failback`, `reprotect`, `adoptReplica`는 action eligibility와 adapter 실행 경로에서 차단된다.

## 3. Phase 1 범위

포함:

- VMware target site preflight
- datastore, folder, cluster/resource pool, network mapping validation
- target VM name reservation
- powered-off standby VM skeleton 생성
- SCSI controller/NIC skeleton 구성
- ownership marker 기록
- `DrReplica`와 `DrRun` 상태 기록
- test API는 destructive action 없이 dry-run/preflight 중심으로 제공

제외:

- 실제 VMDK 변환
- 대용량 VMDK upload
- CBT/VADP
- KVM RBD/qcow2 snapshot copy
- 운영 failover
- failback/reprotect
- production network cutover
- guest driver injection

## 4. VMware target preflight

입력:

- target vCenter endpoint 또는 기존 VMware datacenter mapping
- 저장된 vCenter credential. UI는 credential reference를 받지 않고 vCenter URL, username, password를 write-only로 입력받는다.
- datacenter
- cluster 또는 resource pool
- datastore
- folder
- network/portgroup mapping
- target VM naming policy

검증 항목:

| 항목 | 검증 |
| --- | --- |
| vCenter connectivity | `DrSiteCredentialService`가 resolve한 저장 credential로 session 생성 가능 |
| datacenter | 지정 datacenter 존재 |
| cluster/resource pool | VM 생성 권한과 resource pool 존재 |
| datastore | VMX/VMDK 생성 가능, free space 조회 가능 |
| folder | target folder 존재 또는 생성 가능 |
| network | portgroup 존재, target VM NIC 연결 가능 |
| privilege | VM create, reconfigure, power, datastore file 작업 권한 |
| naming | target name 충돌 여부와 ownership marker 확인 |

실패 처리:

- mapping 오류는 `DR_TARGET_MAPPING_INVALID`
- credential 오류는 `DR_TARGET_UNAVAILABLE` 또는 `DR_CREDENTIAL_INVALID`
- 권한 오류는 `DR_TARGET_PERMISSION_DENIED`
- 이름 충돌은 owned resource이면 재사용, 아니면 `DR_TARGET_OWNERSHIP_CONFLICT`

## 5. Target VM skeleton spec

`VmwareTargetAdapter.ensureReplicaSkeleton`은 source VM metadata를 target VM skeleton으로 변환한다.

필수 VM spec:

| source | target |
| --- | --- |
| VM display name | target naming policy 적용 |
| guest OS type | VMware guestId 매핑 |
| CPU count | 동일 또는 policy override |
| memory | 동일 또는 policy override |
| firmware | BIOS/UEFI 매핑 |
| secure boot | 지원 시 매핑, 미지원 시 compatibility warning |
| disk controller | VMware SCSI controller로 매핑 |
| NIC count | mapping된 portgroup에 NIC 생성 |
| MAC policy | preserve/remap/disconnected 중 선택 |

권장 기본값:

- target VM은 `poweredOff`로 생성한다.
- NIC은 기본적으로 disconnected 또는 isolated network에 연결한다.
- production portgroup 연결은 failover/manual-confirm 이후로 제한한다.
- disk는 Phase 1에서 placeholder 없이 skeleton만 만들 수 있다.
- disk controller는 `LSI Logic SAS` 또는 `VMware Paravirtual` 중 guest compatibility에 따라 선택한다.

## 6. Ownership marker

VMware target resource에는 ownership marker를 남겨야 한다.

권장 marker:

- VM annotation
- custom field
- tag
- VM name suffix

필수 marker 내용:

- `drPlanUuid`
- `drReplicaUuid`
- `sourceVmRef`
- `sourceSiteUuid`
- `targetSiteUuid`
- `createdBy=MoldCrossHypervisorDR`

ownership marker는 cleanup과 idempotent 재실행의 기준이다.

## 7. Network mapping 정책

`network_mapping_json`은 아래 정책을 표현해야 한다.

| field | 의미 |
| --- | --- |
| `sourceNetworkId` | Mold source network id |
| `sourceNetworkRef` | VMware source portgroup 등 |
| `targetNetworkRef` | target portgroup |
| `connectMode` | `DISCONNECTED`, `ISOLATED`, `PRODUCTION_ON_FAILOVER` |
| `macPolicy` | `PRESERVE`, `REMAP`, `GENERATE` |
| `ipPolicy` | `PRESERVE`, `REMAP`, `DHCP`, `MANUAL` |

Phase 1 기본:

- `connectMode=DISCONNECTED` 또는 `ISOLATED`
- production network 연결 금지
- test boot는 isolated network만 허용

## 8. Storage/datastore mapping 정책

`storage_mapping_json`은 아래 정책을 표현해야 한다.

| field | 의미 |
| --- | --- |
| `targetDatastoreRef` | datastore MoRef 또는 name |
| `targetFolderPath` | VM folder/datastore folder |
| `diskProvisioning` | `THIN`, `THICK_LAZY_ZEROED`, `THICK_EAGER_ZEROED` |
| `vmdkPathPolicy` | path 생성 규칙 |
| `freeSpacePolicy` | 최소 여유 공간 |
| `dedupeExisting` | 같은 restore point disk 재사용 여부 |

Phase 1에서는 datastore 존재와 권한, free space 조회까지만 필수로 한다.

## 9. API/Run 흐름

Phase 1의 `createDrPlan` 흐름:

1. `DrSite` source/target 조회
2. direction 계산
3. mapping JSON validation
4. `DrPlan.state=CREATED`
5. 선택적으로 `enableDrPlan` 수행

Phase 1의 `syncDrPlan` 흐름:

1. `DrRun(type=SYNC,state=QUEUED)` 생성
2. `VmwareTargetAdapter.validateTarget`
3. `SourceAdapter.describeSourceVm`
4. `VmwareTargetAdapter.ensureReplicaSkeleton`
5. `DrReplica.state=SKELETON_READY`
6. `DrRestorePoint`가 없는 경우 `DrPlan.state=ENABLED`
7. 외부 materialized restore point가 연결된 경우 `verifyTargetReady`

Phase 1의 `testFailoverDrPlan` 흐름:

- `DrReplica.state=TARGET_READY`가 아니면 실행하지 않는다.
- `SKELETON_READY`만 있는 plan은 preflight 결과로 `TARGET_NOT_READY`를 반환한다.

## 10. 상태 수용 기준

`SKELETON_READY` 수용 기준:

- target VM이 vCenter에 존재한다.
- target VM이 powered off 상태다.
- ownership marker가 존재한다.
- target folder/resource pool/datastore/network mapping이 DB에 기록되어 있다.
- `DrReplica.target_external_ref`가 MoRef 등 안정 식별자를 가진다.

`TARGET_READY` 수용 기준:

- 최신 `DrRestorePoint.state=TARGET_READY`가 존재한다.
- 모든 `DrReplicaDisk.state=READY`다.
- target VM에 disk attach mapping이 있다.
- target VM boot metadata가 검증됐다.
- network policy가 failover 시 적용 가능한 상태다.

## 11. UI 표시 기준

Phase 1 UI는 과장된 RTO/RPO를 표시하지 않는다.

표시해야 할 값:

- VMware target site connection
- target mapping validation result
- replica skeleton state
- target VM name
- target VM power state
- target MoRef
- latest target-ready restore point가 없으면 `Not ready for failover`

숨기거나 비활성화할 action:

- `Failover`는 `TARGET_READY` 전까지 disabled
- `Test failover`는 `TARGET_READY` 전까지 disabled
- `Failback/Reprotect`는 Phase 1에서 disabled

## 12. 검증 계획

문서 기준 검증 항목:

1. 유효한 VMware target site로 preflight가 성공한다.
2. 잘못된 datastore mapping이면 `DR_TARGET_MAPPING_INVALID`가 반환된다.
3. target name이 다른 소유 VM과 충돌하면 생성하지 않는다.
4. 같은 plan으로 `syncDrPlan`을 두 번 실행하면 같은 skeleton VM을 재사용한다.
5. skeleton VM은 powered off 상태다.
6. production network에는 자동 연결하지 않는다.
7. `DrReplica.state=SKELETON_READY`와 target MoRef가 저장된다.

## 13. 구현 전 확인 과제

1. VMware custom field/tag를 쓸 수 있는지 target vCenter 권한을 확인해야 한다.
2. Mold VMware datacenter mapping과 direct vCenter credential model 중 Phase 1의 기본 경로를 선택해야 한다.
3. target VM folder/resource pool을 UI에서 선택하게 할지 정책 기본값을 둘지 결정해야 한다.
4. source VM이 KVM일 때 VMware guestId/controller mapping table이 필요하다.
5. source VM이 VMware일 때 기존 MoRef와 guestId를 그대로 재사용할 수 있는지 확인해야 한다.

## 14. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| Phase 1 범위 | VMware target readiness가 공통 DR 모델에 없음 | target skeleton VM과 datastore/network mapping을 `DrReplica`로 표현 |
| VM 생성 | 엔진 또는 운영자가 개별적으로 처리 | Cloud adapter가 skeleton VM 생성, ownership marker, MoRef 저장을 담당 |
| failover 준비성 | 작업 로그 또는 외부 상태로 판단 | `TARGET_READY` restore point와 replica disk readiness로 판단 |
| 네트워크 | production network 연결 위험이 흐름별로 다름 | Phase 1 skeleton은 기본적으로 production network 미연결 |
| UI 제어 | VMware target 준비 전 action gating이 불명확 | target-ready 전 failover/test failover 비활성화 |
