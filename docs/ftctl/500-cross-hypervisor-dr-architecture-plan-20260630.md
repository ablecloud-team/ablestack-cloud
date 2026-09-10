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

# Cross Hypervisor DR Architecture Plan

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

상세화 문서:

- [501-cross-hypervisor-dr-domain-schema-design-20260630.md](501-cross-hypervisor-dr-domain-schema-design-20260630.md)
- [502-cross-hypervisor-dr-adapter-contract-design-20260630.md](502-cross-hypervisor-dr-adapter-contract-design-20260630.md)
- [503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md](503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md)
- [504-cross-hypervisor-dr-phase1-vmware-target-scope-design-20260630.md](504-cross-hypervisor-dr-phase1-vmware-target-scope-design-20260630.md)
- [505-cross-hypervisor-dr-ftctl-v2k-integration-design-20260630.md](505-cross-hypervisor-dr-ftctl-v2k-integration-design-20260630.md)
- [506-cross-hypervisor-dr-cloud-ui-design-20260630.md](506-cross-hypervisor-dr-cloud-ui-design-20260630.md)
- [507-cross-hypervisor-dr-cloud-api-command-design-20260630.md](507-cross-hypervisor-dr-cloud-api-command-design-20260630.md)
- [508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md](508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md)
- [509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md](509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md)
- [510-cross-hypervisor-dr-ftctl-runtime-contract-design-20260630.md](510-cross-hypervisor-dr-ftctl-runtime-contract-design-20260630.md)
- [527-cross-hypervisor-dr-site-credential-management-design-20260702.md](527-cross-hypervisor-dr-site-credential-management-design-20260702.md)
- [528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md](528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md)
- [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)
- [542-cross-hypervisor-dr-vmware-mover-and-projection-convergence-design-20260707.md](542-cross-hypervisor-dr-vmware-mover-and-projection-convergence-design-20260707.md)
- [543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md](543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md)
- [546-cross-hypervisor-dr-initial-sync-pending-projection-contract-design-20260708.md](546-cross-hypervisor-dr-initial-sync-pending-projection-contract-design-20260708.md)
- [547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md](547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md)
- [548-cross-hypervisor-dr-agent-action-compatibility-and-state-convergence-design-20260709.md](548-cross-hypervisor-dr-agent-action-compatibility-and-state-convergence-design-20260709.md)

## 1. 목적

ABLESTACK Mold가 KVM 기반 ABLESTACK 클러스터와 VMware 클러스터를 함께 운영 관리하는 환경에서, 가상머신의 운영 위치와 DR 위치가 서로 다른 하이퍼바이저일 수 있는 `Cross Hypervisor DR` 아키텍처를 수립한다.

이 문서는 특정 방향 하나만을 위한 설계가 아니라, 아래 네 가지 조합을 모두 수용할 수 있는 공통 구조를 정의한다.

| Source 운영 클러스터 | Target DR 클러스터 | 주요 엔진 |
| --- | --- | --- |
| ABLESTACK/KVM | ABLESTACK/KVM | FTCTL, snapshot, RBD/qcow2 replication |
| ABLESTACK/KVM | VMware | snapshot/backup + VMDK materialization |
| VMware | VMware | VMware snapshot, CBT/VADP, VMDK replica |
| VMware | ABLESTACK/KVM | V2K/import workflow |

목표 RTO는 1시간 이내로 한다. RPO는 하이퍼바이저와 스토리지별로 가능한 최소 수준까지 줄이되, 초기 구현에서는 안정성과 운영 가능성을 우선한다.

## 2. 현재 시스템에서 확인한 기반 요소

### 2.1 기존 Disaster Recovery Cluster 플러그인

`plugins/integrations/disaster-recovery`는 Mold 간 DR 클러스터 등록, VM 매핑, promote/demote/resync, VM snapshot API를 제공한다.

현재 성격:

- DR 사이트/클러스터 페어링에 가까운 상위 모델이다.
- 기본 mirror interval은 `1h` 성격이므로, 수분 단위 RPO를 위해서는 별도 runtime state와 restore point 모델이 필요하다.
- GLUE/SCVM 및 Mold API 기반 절차가 강하게 결합되어 있다.

개선 방향:

- 기존 `DisasterRecoveryCluster`는 `DrSite` 또는 `DrSitePair`의 상위 개념으로 재사용한다.
- VM 단위 보호 정책은 신규 `DrPlan` 모델로 분리한다.
- promote/demote/resync API는 새 orchestrator API로 흡수하거나 호환 wrapper로 유지한다.

### 2.2 FTCTL 통합 플러그인

`plugins/integrations/ftctl-service`는 Cloud-managed FTCTL 보호 등록, 원격 Mold 리소스 준비, runtime state sync, failover/failback action, fencing 처리를 제공한다.

현재 성격:

- ABLESTACK/KVM to ABLESTACK/KVM DR에는 가장 직접적으로 재사용 가능하다.
- remote-nbd, shared-blockcopy, standby VM, cloud-managed failover 흐름이 존재한다.
- VMware target에는 직접 대응하지 않는다.

개선 방향:

- FTCTL은 `KvmToKvmReplicationAdapter` 또는 `KvmRuntimeReplicationEngine`으로 위치시킨다.
- FTCTL의 상태 모델, check/status/event/fencing 패턴은 공통 DR orchestrator의 runtime state 모델에 반영한다.

### 2.3 VMware 통합 기능

Mold는 VMware datacenter 등록, VMware VM inventory 조회, out-of-band VM 조회/clone/export, VMware snapshot backup to secondary storage 경로를 이미 보유한다.

현재 성격:

- Mold-managed VMware VM을 DR source로 삼기 위한 inventory 기반은 존재한다.
- VMware snapshot을 NFS secondary storage로 backup하는 경로가 있다.
- VMware target site에 standby VM/VMDK replica를 지속적으로 materialize하는 공통 DR target adapter는 없다.

개선 방향:

- VMware source 전용 `VmwareSourceAdapter`를 만든다.
- VMware target 전용 `VmwareTargetAdapter`를 만든다.
- VMware native RPO 최소화를 위해 CBT/VADP 기반 changed-block path를 단계적으로 추가한다.

## 3. 공통 아키텍처

핵심은 source와 target을 고정하지 않는 것이다. DR 방향은 adapter 조합으로 결정한다.

```mermaid
flowchart LR
  SRC["Source Adapter<br/>KVM / VMware"]
  RP["Restore Point Catalog<br/>VM-consistent point"]
  REPL["Replication Engine<br/>snapshot / backup / CBT / FTCTL / V2K"]
  CONV["Conversion Engine<br/>qcow2/raw/rbd/vmdk"]
  TGT["Target Adapter<br/>KVM / VMware"]
  ORCH["Failover / Failback Orchestrator"]

  SRC --> RP
  RP --> REPL
  REPL --> CONV
  CONV --> TGT
  ORCH --> SRC
  ORCH --> RP
  ORCH --> REPL
  ORCH --> TGT
```

### 3.1 핵심 도메인 모델

`DrSite`

- Mold 또는 VMware endpoint를 나타낸다.
- 유형: `MOLD_KVM`, `MOLD_VMWARE`, `VMWARE_DIRECT`.
- endpoint, backend-managed credential, zone/cluster/datastore/network capability를 가진다.
- UI는 credential reference를 입력받지 않는다. 사용자는 Mold/vCenter 인증정보를 입력하고, Cloud backend가 암호화 저장 후 내부 `credential_id`로 관리한다.
- UI는 endpoint, hypervisor type, Zone, VMware datacenter 같은 내부/고급 필드를 site type별 필수 접속 정보와 섞어 기본 입력으로 노출하지 않는다. VMware Direct는 vCenter URL/username/password/TLS를 기본 입력으로 받고 endpoint와 hypervisor type은 자동 결정한다.
- UI는 DR Plan의 mapping/schedule/policy/quiesce JSON을 사용자가 직접 작성하게 하지 않는다. 사용자는 target resource, RPO/RTO, consistency, failover/test policy를 선택하고 Cloud backend가 canonical engine spec을 생성한다.
- 화면에는 `VMWARE_DIRECT`, `KVM_TO_VMWARE`, `FTCTL_DR` 같은 enum 원문 대신 i18n label을 표시하고, API payload에만 enum value를 사용한다.
- site 상태 점검은 Cloud backend가 수행한다. `checkDrSite`는 Mold/vCenter endpoint와 저장 credential을 실제로 검증하고, Agent/ftctl은 DR plan runtime action 단계에서만 사용한다.

`DrPlan`

- VM 보호 정책의 중심 모델이다.
- source VM, source hypervisor, target site, RPO/RTO policy, storage/network mapping, fencing policy를 가진다.
- 기존 FTCTL의 `FtctlProtection`보다 일반화된 모델이다.

`DrRestorePoint`

- 특정 시점의 VM-consistent 복구 지점이다.
- 여러 volume snapshot/backup artifact를 하나의 VM 복구 단위로 묶는다.
- 상태: `CREATING`, `SOURCE_READY`, `MATERIALIZING`, `TARGET_READY`, `FAILED`, `EXPIRED`.

`DrReplica`

- target site에 준비된 대기 VM과 디스크 materialization 상태를 나타낸다.
- VMware target이면 vCenter MoRef, datastore path, VMDK path, NIC mapping을 가진다.
- KVM target이면 Cloud VM id, volume id, libvirt/FTCTL state를 가진다.

`DrRun`

- sync, test failover, failover, failback, reprotect 실행 이력이다.
- step, started/finished, actor, error, rollback context를 기록한다.

## 4. 전체 데이터 흐름

```mermaid
flowchart TB
  subgraph PROD["운영 Mold"]
    KVM["ABLESTACK KVM VM"]
    VMW["Mold-managed VMware VM"]
    PLAN["DrPlan"]
    CAT["DrRestorePoint Catalog"]
  end

  subgraph ENGINES["Replication / Conversion Engines"]
    KVMENG["KVM Engine<br/>snapshot, backup, RBD diff, FTCTL"]
    VMWENG["VMware Engine<br/>snapshot, export, CBT/VADP"]
    CVT["Format Materializer<br/>VMDK / qcow2 / raw"]
  end

  subgraph TARGET["DR Site"]
    TGTKVM["ABLESTACK/KVM Target"]
    TGTVMW["VMware Target"]
  end

  KVM --> PLAN
  VMW --> PLAN
  PLAN --> CAT
  PLAN --> KVMENG
  PLAN --> VMWENG
  KVMENG --> CVT
  VMWENG --> CVT
  CVT --> TGTKVM
  CVT --> TGTVMW
  CAT --> TGTKVM
  CAT --> TGTVMW
```

## 5. Source/Target 조합별 설계

### 5.1 ABLESTACK/KVM to ABLESTACK/KVM

재사용 우선순위가 가장 높다.

사용 기술:

- FTCTL remote-nbd 또는 shared-blockcopy
- RBD/qcow2 snapshot
- Cloud-managed standby VM
- FTCTL status/check/events/fencing

개선 방향:

- 기존 `FtctlProtection`을 `DrPlan`에 연결한다.
- FTCTL state를 `DrReplica` runtime state로 투영한다.
- Cloud HA lifecycle guard와 fencing 결과를 공통 failover orchestrator로 옮긴다.

RPO/RTO:

- RPO: remote-nbd mirroring 상태에서는 수분 이내 가능.
- RTO: standby VM 준비 상태에 따라 수분에서 수십 분.

### 5.2 ABLESTACK/KVM to VMware

새 구현 비중이 가장 크다.

사용 기술:

- VM-consistent snapshot 또는 volume snapshot set
- secondary/object/NFS backup
- RBD/qcow2/raw to VMDK 변환
- vCenter datastore upload
- powered-off VMware standby VM

흐름:

```mermaid
sequenceDiagram
  participant Mold
  participant KVM as KVM Cluster
  participant SS as Secondary Storage
  participant Worker as Data Mover
  participant VC as vCenter

  Mold->>KVM: create VM-consistent restore point
  KVM->>SS: backup snapshots
  Mold->>Worker: materialize latest restore point
  Worker->>Worker: convert to VMDK
  Worker->>VC: upload VMDK to datastore
  Mold->>VC: create/update standby VM
  Mold->>Mold: mark restore point TARGET_READY
```

개선 방향:

- `KvmToVmwareReplicationAdapter` 추가.
- `VmwareTargetAdapter` 추가.
- VMDK materialization worker 추가.
- RBD diff 또는 qcow2 backing chain 기반 증분 materialization을 2단계로 추가.

RPO/RTO:

- 초기 RPO: 15-30분 목표.
- 증분 materialization 후 RPO: 5-15분 목표.
- RTO: standby VM이 `TARGET_READY`이면 1시간 이내 가능.

### 5.3 VMware to VMware

가장 자연스러운 VMware-native DR 경로다.

사용 기술:

- VMware snapshot
- NFS secondary backup existing path
- CBT/VADP changed block replication
- vCenter clone/register/power control

흐름:

```mermaid
sequenceDiagram
  participant Mold
  participant SrcVC as Source vCenter
  participant DRVC as DR vCenter
  participant DS as DR Datastore

  Mold->>SrcVC: discover Mold-managed VMware VM
  Mold->>SrcVC: snapshot or CBT checkpoint
  SrcVC->>DS: replicate changed blocks / VMDK
  Mold->>DRVC: create or update standby VM
  Mold->>DRVC: validate boot readiness metadata
  Mold->>Mold: update DrRestorePoint TARGET_READY
```

개선 방향:

- `VmwareSourceAdapter` 추가.
- `VmwareToVmwareReplicationAdapter` 추가.
- 초기에는 VMware snapshot/export 기반으로 시작한다.
- 이후 CBT/VADP adapter를 추가해 RPO를 줄인다.

RPO/RTO:

- 초기 RPO: 15-30분 목표.
- CBT/VADP 적용 후 RPO: 5분 내외 목표.
- RTO: standby VM 준비 상태에서는 1시간 이내 가능.

### 5.4 VMware to ABLESTACK/KVM

기존 V2K/import workflow를 재사용한다.

사용 기술:

- `importUnmanagedInstanceForAblestackV2K`
- V2K phase1/phase2
- target Cloud VM/volume materialization

개선 방향:

- V2K import task를 사용자가 직접 다루지 않도록 `DrPlan` 아래에 감싼다.
- phase1을 DR sync, phase2를 failover cutover로 모델링한다.
- failback은 VMware target adapter 또는 V2K reverse path 필요 여부를 별도로 정의한다.

RPO/RTO:

- RTO: phase1 선행 완료 상태에서 phase2 중심으로 줄일 수 있다.
- RPO: V2K 증분/반복 sync 능력 검증이 필요하다.

## 6. RPO/RTO 정책

RPO는 두 단계로 나누어 관리한다.

`source_rpo`

- source snapshot 또는 changed-block capture가 완료된 시점 기준이다.
- 운영 VM에서 데이터가 안전하게 복구 지점으로 잡힌 시간을 의미한다.

`target_ready_rpo`

- target site에서 실제 부팅 가능한 형태로 materialization된 시점 기준이다.
- 장애 시 실제 사용할 수 있는 RPO다.

RTO는 target readiness에 의존한다.

| Target readiness | 장애 시 작업 | 예상 RTO |
| --- | --- | --- |
| VM skeleton 없음 | VM 생성, disk 변환, upload, boot | 1시간 초과 가능 |
| VM skeleton 있음, disk 미반영 | 최신 disk materialize 후 boot | 수십 분 이상 |
| VM skeleton + latest disk ready | fencing, attach 검증, boot | 1시간 이내 가능 |
| periodic test boot 완료 | fencing, power on, network cutover | 수분에서 수십 분 |

따라서 RTO 1시간 이내를 제품 목표로 삼으려면 모든 보호 VM은 최소한 `VM skeleton + latest disk ready` 상태를 유지해야 한다.

## 7. API 개선 계획

신규 API 후보:

- `createDrSite`
- `listDrSites`
- `updateDrSite`
- `deleteDrSite`
- `createDrPlan`
- `listDrPlans`
- `updateDrPlan`
- `deleteDrPlan`
- `syncDrPlan`
- `listDrRestorePoints`
- `testFailoverDrPlan`
- `failoverDrPlan`
- `failbackDrPlan`
- `reprotectDrPlan`

기존 API 호환:

- 기존 `createDisasterRecoveryCluster`는 `createDrSitePair`로 내부 매핑한다.
- 기존 FTCTL API는 KVM-to-KVM adapter의 구현 세부로 유지한다.
- 기존 UI의 DR 탭은 `DrPlan` 중심으로 재구성한다.

## 8. 구성요소별 개선 방향

| 구성요소 | 현재 상태 | 개선 방향 |
| --- | --- | --- |
| DisasterRecoveryCluster | Mold-to-Mold/GLUE 중심 | `DrSite`, `DrSitePair`로 일반화 |
| FtctlProtection | KVM/libvirt 보호 중심 | `DrPlan`의 KVM-to-KVM adapter로 연결 |
| Snapshot/Backup | volume 중심 API | VM 단위 restore point catalog 추가 |
| VMware plugin | inventory, snapshot backup, import 지원 | source/target adapter 계층 추가 |
| Data mover | 분산된 import/backup command 중심 | format materializer worker 추가 |
| UI | 기존 DR와 FTCTL 흐름 분리 | 단일 Cross Hypervisor DR wizard 제공 |
| Monitoring | FTCTL state sync 중심 | RPO lag, target readiness, materialization status 추가 |
| Fencing | FTCTL/IPMI 중심 | source type별 fencing policy로 일반화 |

## 9. 단계별 구현 계획

### Phase 1: 공통 모델과 VMware target 기반

목표:

- `DrSite`, `DrPlan`, `DrRestorePoint`, `DrReplica`, `DrRun` schema/API 추가.
- VMware target adapter 구현.
- standby VM 생성, datastore/network mapping, power control 구현.

검증:

- VMware target site에 powered-off standby VM이 생성된다.
- DR Plan 상태가 `TARGET_READY`까지 전이된다.

### Phase 2: VMware source to VMware target

목표:

- Mold-managed VMware VM을 source로 등록한다.
- VMware snapshot/export 기반 restore point를 만든다.
- DR VMware site에 VMDK replica를 준비한다.

검증:

- 15-30분 주기로 target-ready restore point가 갱신된다.
- test failover가 운영 VM에 영향 없이 수행된다.

### Phase 3: KVM source to VMware target

목표:

- ABLESTACK/KVM VM snapshot set을 restore point로 묶는다.
- RBD/qcow2/raw를 VMDK로 materialize한다.
- VMware standby VM에 최신 VMDK를 반영한다.

검증:

- KVM source VM이 VMware DR site에서 부팅된다.
- target-ready RPO가 15-30분 범위에 들어온다.

### Phase 4: 증분 최적화

목표:

- VMware CBT/VADP changed block path 추가.
- KVM RBD diff 또는 qcow2 backing-chain 기반 증분 materialization 추가.
- target-ready RPO를 5-15분 수준으로 단축한다.

검증:

- 대용량 VM에서 full conversion 없이 반복 sync가 완료된다.
- sync lag와 materialization lag가 UI/API에 표시된다.

### Phase 5: Failback/Reprotect

목표:

- VMware to KVM은 V2K phase1/phase2 기반으로 failback한다.
- VMware to VMware는 reverse replication 또는 clone-back을 제공한다.
- KVM to KVM은 FTCTL failback/reprotect를 사용한다.

검증:

- failover 이후 source/target 역할 전환이 `DrPlan`에 반영된다.
- reprotect 후 다시 정상 sync가 시작된다.

## 10. 우선순위 권고

1. 공통 `DrPlan`/`DrRestorePoint` 모델을 먼저 만든다.
2. Target이 VMware인 경로를 우선 완성한다. 현재 요구의 핵심이 VMware DR site이기 때문이다.
3. Source는 VMware부터 처리한다. 같은 VMDK 계열이라 RPO/RTO 목표 달성이 빠르다.
4. 다음으로 KVM source to VMware target을 구현한다. 변환/증분 최적화가 핵심 난제다.
5. ABLESTACK target 경로는 기존 FTCTL/V2K 자산을 adapter로 묶어 흡수한다.

## 11. 주요 리스크와 확인 과제

| 리스크 | 설명 | 대응 |
| --- | --- | --- |
| snapshot consistency | 다중 디스크 VM의 application consistency | QGA, VMware tools, quiesce policy 도입 |
| target-ready RPO 지연 | 변환/업로드 시간이 source RPO보다 길어질 수 있음 | source RPO와 target-ready RPO를 분리 표시 |
| KVM to VMware driver 문제 | VirtIO 기반 VM이 VMware에서 바로 부팅되지 않을 수 있음 | guest driver injection 또는 compatibility check |
| VMware credential 관리 | vCenter credential/secret 저장 위험 | UI/API는 write-only 인증정보를 받고 Cloud backend가 `dr_site_credential`에 암호화 저장 |
| DR Site/Plan 입력 UX | 내부 endpoint, hypervisor, mapping id, raw JSON과 인증정보가 한 form에 섞일 위험 | 사이트/계획 유형별 필수 정보만 기본 노출하고 engine spec은 backend-generated guided 설정으로 처리 |
| network identity | MAC/IP 보존과 충돌 위험 | failover policy로 preserve/remap 선택 |
| fencing | source VM 미정지 상태에서 dual-active 위험 | source type별 fencing adapter와 manual-confirm 단계 |
| large VM conversion | full conversion은 RTO/RPO 목표를 깨뜨릴 수 있음 | full seed 후 incremental materialization 구현 |

## 12. 결론

`Cross Hypervisor DR`은 특정 방향의 기능이 아니라 source/target adapter 조합으로 동작하는 DR 플랫폼으로 설계해야 한다.

현재 코드베이스 기준으로는 다음 배치가 가장 자연스럽다.

- 기존 `DisasterRecoveryCluster`: site/pairing 상위 모델로 재사용.
- 기존 `ftctl-service`: KVM-to-KVM replication engine으로 재사용.
- VMware plugin: VMware source/target adapter의 기반으로 재사용.
- 신규 `DrPlan` 계층: 사용자에게 보이는 공통 보호 정책과 failover/failback orchestration을 담당.

이 구조를 적용하면 DR 사이트가 VMware든 ABLESTACK/KVM이든 동일한 UX/API로 대응할 수 있고, 각 하이퍼바이저 조합의 기술 차이는 adapter 내부로 격리된다.

## 13. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| DR 모델 | 기존 `DisasterRecoveryCluster`와 FTCTL 기능이 기능별로 분리 | `DrSite`, `DrPlan`, `DrRun`, `DrReplica`, `DrRestorePoint` 중심의 공통 DR 플랫폼 |
| 지원 방향 | KVM-to-KVM FTCTL과 기존 VMware DR 기능 중심 | KVM/VMware source와 target 조합을 adapter로 확장 |
| Site 인증정보 | UI에서 내부 credential reference 성격의 문자열 입력 | UI는 Mold/vCenter 인증정보를 입력하고 backend가 암호화 저장, 응답은 credential 상태만 표시 |
| Site/Plan 대화상자 | 긴 form 전체가 화면 밖으로 밀리거나 enum 원문이 노출될 수 있음 | 공통 DR form modal에서 header/footer 고정, 본문만 스크롤, i18n label 표시 |
| 사용자 경험 | 기능별 화면과 API가 서로 다른 추상도를 가짐 | 동일한 DR plan/action/progress UX로 통합 |
| 런타임 통합 | FTCTL, VMware, V2K 흐름이 개별 경로로 노출 | Cloud orchestrator가 공통 상태를 관리하고 engine adapter가 실제 작업 수행 |
| 구현 순서 | 기능별 보강 중심 | 공통 domain/API/worker를 먼저 만들고 FTCTL, VMware target, V2K 순서로 연결 |

## 2026-07-07 Update: VMware To KVM Readiness And Projection Contract

The architecture now treats `VMWARE_TO_KVM` sync readiness as a hard pre-dispatch
contract, not as a best-effort runtime correction. A plan can be accepted by the
agent transport while still failing in the FTCTL worker; therefore `ACCEPTED`
is never considered a healthy sync result by itself.

Architecture rules:

- UI guides resource selection and shows readiness, but backend validation is
  authoritative.
- VMware source disk inventory is not complete until Cloud backend enriches
  vCenter disk list entries with per-disk detail endpoint data. A vCenter disk
  key such as `2000` is an identifier, not a size.
- API preview/create/update must reject executable sync when any selected VMware
  source disk has missing or zero `sizeBytes`.
- Backend orchestration must not create `SKELETON_READY` replicas for invalid
  disk maps.
- Agent status probes must keep returning terminal worker state by `planUuid`
  and `runUuid`.
- FTCTL must keep the final guard and emit terminal JSON when target disk
  materialization cannot be performed.
- DB/API/UI projection must converge accepted runs to terminal `ERROR` or
  `READY` based on FTCTL runtime evidence.

Detailed code-level design:
`538-cross-hypervisor-dr-vmware-to-kvm-disk-size-and-projection-hardening-design-20260707.md`.

Related UI storage/default layout design:
`539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md`.

## 2026-07-08 Update: Initial Sync Pending Is Not Failure

VMware to ABLESTACK validation for plan
`9bb2739b-597c-4c9a-a603-f3edf5abfd60` proved that Cloud projection must
distinguish initial full-seed progress from terminal target materialization
failure.

During `SYNCING/full-seed-transfer`, FTCTL can validly report:

- `target_storage_present=true`
- `target_vm_present=false`
- `restore_point_present=false`
- empty `error_code`

This means target disk transfer is in progress and the target VM is not
expected yet. Cloud must not persist `DR_TARGET_VM_NOT_FOUND` into
`dr_run.error_code` for this state. The full code-level contract is defined in
`546-cross-hypervisor-dr-initial-sync-pending-projection-contract-design-20260708.md`.

## 2026-07-09 Update: Custom Compute Sizing Before Target VM Materialization

Plan `b2a649b7-8313-4bd4-be49-5dda67993e06` advanced beyond the initial seed
boundary: FTCTL created a durable restore point and Cloud imported the target
volume, but stopped target VM creation failed with
`Invalid CPU cores value, specify a value between 1 and 64`.

The selected target service offering was custom/dynamic
(`1C1GB-TO-64C96GB-FR`), so CloudStack requires explicit VM detail parameters:

- `cpuNumber`
- `cpuSpeed`
- `memory`

Architectural rule:

- UI/API must collect or derive target compute sizing whenever a custom target
  offering is selected.
- Backend preflight must block immediate sync if those values cannot be
  resolved.
- Backend materialization must pass those values via
  `VmDetailConstants.CPU_NUMBER`, `VmDetailConstants.CPU_SPEED`, and
  `VmDetailConstants.MEMORY` during `deployVirtualMachineForVolume`.
- Terminal `DR_TARGET_VM_MATERIALIZE_FAILED` must not be overwritten by later
  healthy FTCTL `SYNCING` status polling.

The detailed code-level design and AS-IS/TO-BE summary are maintained in
`547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md`.

2026-07-09 follow-up: after target VM creation succeeded, a new failure was
observed at the Cloud-to-Agent `TARGET_MATERIALIZED` notification boundary:
`Missing FTCTL_DR action`. The architecture now requires Agent/FTCTL
capability preflight, action string fallback, notification retry/recovery, and a
single UI primary state resolver. The detailed code-level design and
AS-IS/TO-BE summary are maintained in
`548-cross-hypervisor-dr-agent-action-compatibility-and-state-convergence-design-20260709.md`.

## 2026-07-10 Normative Protection View And Read-Projection Separation

DR Plan detail reads must no longer invoke Agent or FTCTL projection. Runtime
projection is owned by a background scheduler or an explicit asynchronous
refresh job, and UI/API reads consume a versioned DB JSON snapshot. Details,
topology, and replica readiness are rendered from one cache revision in the
`Protection Information` tab.

The runtime contract also separates the current transfer checkpoint from the
latest completed checkpoint. Only the latest completed reference is eligible
for Cloud checkpoint projection and failover locking.

The normative architecture, layer boundaries, live evidence, and acceptance
criteria are defined in
`550-cross-hypervisor-dr-protection-view-cache-and-completed-checkpoint-design-20260710.md`.

## 2026-07-14 Normative Async Read Consistency Update

Resource creation completion and DR data-copy completion are separate
boundaries. UI create/update flows follow the Cloud async job until the typed
resource response is committed, then reconcile the list. They do not wait for
full seed, incremental copy, Agent, or FTCTL completion.

For an enabled plan, the UI reads `getDrProtectionView` every 10 seconds even
after the latest operator run is terminal. This is a DB-cache read and does not
increase Agent/FTCTL polling. Explicit operator Update remains the asynchronous
`refreshDrProtectionView` path.

Detailed design:
`552-cross-hypervisor-dr-async-read-consistency-and-live-cache-ui-design-20260714.md`.

## 2026-07-14 Normative Continuous Sync Control-Plane Update

Continuous replication and foreground DR transitions use separate ownership
boundaries. A long-lived `dr-sync-start` scheduler must not own the legacy
global FTCTL lock for its lifetime. DR uses plan-scoped cycle, transition, and
checkpoint-lease locks. Pause, stop, test failover, and planned failover are
delivered through an atomic control request/acknowledgment protocol.

The UI/API remains asynchronous: it creates and observes a `DrRun`; Agent and
FTCTL coordinate scheduler quiesce in the background. The normative lock,
state, API, cache, and acceptance contract is defined in
`553-cross-hypervisor-dr-continuous-sync-control-and-quiesce-lock-design-20260714.md`.

## 2026-07-14 Normative VMware To KVM Cutover Update

VMware to ABLESTACK 보호는 durable checkpoint와 powered-off target VM을
만드는 것만으로 완료되지 않는다. Test Failover는 checkpoint-derived writable
layer에서 VirtIO guest preparation을 수행하고 격리된 test domain을 실제로
기동한 뒤 boot validation을 통과해야 한다.

실제 Failover에서는 FTCTL이 최종 복제 디스크를 `CUTOVER_READY` 상태로
준비하고, Cloud backend가 기존 target VM을 정상 Cloud VM lifecycle로
기동한다. `activeSide=TARGET`은 boot validation 이후에만 commit한다.

V2K는 DR 복제 엔진으로 호출하지 않는다. 검증된 Linux initramfs, Windows
WinPE/VirtIO, Secure Boot 처리 primitive만 공통 라이브러리로 분리하여
재사용한다. 상세 규범은 다음 문서에 정의한다.

- `554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md`

### 2026-07-14 VMware CBT Replication Addendum

For VMware-source continuous replication, a sequence name is not proof of
incremental execution. Per-disk committed changeIds, extent-only transfer,
cycle commit acknowledgement, and transfer metrics follow the normative design
in `555-cross-hypervisor-dr-vmware-cbt-incremental-and-transfer-metrics-design-20260714.md`.

### 2026-07-16 Cycle Commit And Read Contract Addendum

Data transfer, local checkpoint commit, and Cloud projection are separate
monotonic phases. Copied-but-uncommitted target data is not a recovery point,
but it remains visible as an explicit degraded state. Complete runtime JSON is
diagnostic data and cannot cross the UI/API boundary as an error-message
string. The normative flow is defined in
`557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md`.

### 2026-07-17 Normative Incremental Decision And Projection Update

Continuous VMware protection separates Scheduler intent from data-plane
execution. A Scheduler-requested incremental cycle remains
`requestedMode=CBT_INCREMENTAL`; FTCTL records any effective reseed as a
separate typed decision and prevents an identical automatic reseed from
repeating indefinitely.

The committed per-disk changeId, baseline state, generation, and disk identity
cross every internal planning boundary. Cloud independently projects the
current cycle and latest completed cycle, even when they have different
sequences. Normal Test Failover and planned Failover require at least one
verified incremental or valid no-change checkpoint. Emergency Failover remains
an explicit degraded operation against the last durable checkpoint.

Normative design:
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.

## 2026-07-19 Normative Cloud-Managed Test Failover Ownership Update

Cloud is the only lifecycle authority for the permanent recovery VM and the
temporary Test Failover VM. FTCTL owns checkpoint leases, test disk artifacts,
and guest preparation, but must not define, start, stop, or undefine the
customer test VM. Agent remains a transport/probe boundary. `ISOLATED` must
resolve to a Cloud network, while `NO_NIC` is an explicit separate mode.

The previous engine-owned transient-domain description is superseded by
`561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md`.
