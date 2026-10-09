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

# 볼륨·스냅샷 기반 가상머신 생성 개선 설계

- 작성일: 2026-10-09 (한국 시간)
- 분석 기준: 최신 `upstream/ablestack-europa`, `2871963cd43aeb592f619c1b67d8a5974846409d`
- 소스/설계 작업: `dhslove/ablestack-cloud`, 별도 `codex/vm-volume-snapshot-design` 브랜치
- 이슈 관리: 기존 이슈 저장소 `ablecloud-team/ablestack-cloud` (`dhslove`는 이슈 기능 비활성)
- 현재 단계: 코드·화면 조사, 개선 설계 및 31/32 개발·검증 기반 준비 완료. 실제 생성·부팅 E2E는 미실행. 환경 준비 결과는 [별도 보고서](environment-20261009.ko.md)를 참조한다.
- 실 화면 조사: 기존 로그인 세션의 31번 클러스터. 이번 단계는 생성 방식/후보 선택만 수행했으며 생성 요청을 제출하지 않았다.
- Epic: [#1335](https://github.com/ablecloud-team/ablestack-cloud/issues/1335), 하위 이슈 8개 실제 연결 확인. 현재 모두 OPEN이다.

## 1. 핵심 결론

볼륨과 스냅샷 생성은 같은 기능처럼 보이지만 원본 처리 방식이 다르다.

| 방식 | 현재 서버 동작 | 개선 UI에서 알려야 할 의미 |
|---|---|---|
| 볼륨 | 기존 볼륨의 `deviceId`, `volumeType`, `instanceId`를 갱신하여 새 VM에 연결 | 원본 볼륨을 그대로 루트 디스크로 사용한다. 복사본을 만드는 기능이 아니다. 하나의 볼륨으로 동시에 여러 VM을 생성할 수 없다. |
| 스냅샷 | 새 볼륨 행을 할당하고 `createVolumeFromSnapshot`으로 복구 | 스냅샷 시점의 루트 디스크로 새 VM을 만든다. 원본 스냅샷과 기존 VM을 유지한다. 데이터 디스크/메모리는 자동 복구하지 않는다. |

현재 API는 KVM을 지원 대상으로 검사하며, 기존 볼륨 사용은 **Zone 범위 스토리지**를 요구한다. 공유 스토리지라는 이유만으로 CLUSTER 범위 SharedMountPoint를 지원한다고 표시하면 안 된다. 스냅샷 경로에는 같은 Zone 범위 조건을 그대로 적용하지 않는다. 사용자가 지정한 31번 GFS2와 32번 Ceph krbd는 모두 CLUSTER 범위이므로, 이 제한을 현재 결함으로 추적하고 #1337에서 CLUSTER ROOT 편입 지원을 P0 선행 구현한다. pool의 scope를 Zone으로 변경하여 우회하지 않는다.

이 Epic의 기본 동작은 위 의미를 유지한다. 기존 볼륨 복제, 다중 디스크 전체 VM 복구, VM 메모리 스냅샷 복구, 비KVM 지원 확대는 별도 기능으로 분리한다.

## 2. 확인 결과와 근거

### 2.1 실제 코드 경로

`DeployVM.vue` → `deployVirtualMachine(volumeid | snapshotid)` → `DeployVMCmd.create` → `UserVmManagerImpl.createVirtualMachine/getVolume` → `VirtualMachineManagerImpl.allocateRootVolume` → `VolumeOrchestrator.allocateTemplatedVolume`.

`deployVirtualMachineForVolume`라는 다른 API는 `values.volumeId`(대문자 I)를 사용하는 별도 RBD 이미지 경로이다. 이번 `volumeid`(소문자 i)의 일반 볼륨 생성과 혼동하지 않는다.

| 근거 파일 | 확인 내용 |
|---|---|
| [DeployVM.vue:2635](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/ui/src/views/compute/DeployVM.vue#L2635) | 볼륨/스냅샷 선택 시 원본 ID는 바꾸지만 BIOS/UEFI·bootmode의 원본 기반 상속은 하지 않는다. |
| [DeployVM.vue:2847](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/ui/src/views/compute/DeployVM.vue#L2847) | 방식 변경은 imageType와 추가 ISO 상태만 초기화한다. 이전 원본 및 요약의 완전 초기화가 아니다. |
| [DeployVM.vue:2963](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/ui/src/views/compute/DeployVM.vue#L2963) | 두 방식 모두 표준 deployVirtualMachine으로 전달한다. |
| [DeployVM.vue:3469](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/ui/src/views/compute/DeployVM.vue#L3469) | 볼륨 후보는 Ready + 미연결만 검사한다. 부팅 출처·템플릿·scope 정보로 생성 가능 여부를 표시하지 않는다. |
| [DeployVM.vue:3511](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/ui/src/views/compute/DeployVM.vue#L3511) | 스냅샷은 ROOT만 필터링한다. 상태 필터와 route snapshotid의 요청 반영이 없다. |
| [DeployVMCmd.java:261](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/api/src/main/java/org/apache/cloudstack/api/command/user/vm/DeployVMCmd.java#L261) | templateid/volumeid/snapshotid는 정확히 하나만 허용한다. |
| [UserVmManagerImpl.java:7164](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/server/src/main/java/com/cloud/vm/UserVmManagerImpl.java#L7164) | 원본 접근 권한 검사와 템플릿 추론, KVM 제한이 존재한다. template null 검사 이전 접근 가능성은 명시적 오류로 보완할 대상이다. |
| [UserVmManagerImpl.java:7510](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/server/src/main/java/com/cloud/vm/UserVmManagerImpl.java#L7510) | 기존 볼륨은 Zone 범위·미연결·원본 template 연결 정보를 요구한다. |
| [VolumeOrchestrator.java:1265](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/engine/orchestration/src/main/java/org/apache/cloudstack/engine/orchestration/VolumeOrchestrator.java#L1265) | 기존 볼륨 재사용, 스냅샷 원본 볼륨 크기 조회. |
| [VolumeOrchestrator.java:1344](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/engine/orchestration/src/main/java/org/apache/cloudstack/engine/orchestration/VolumeOrchestrator.java#L1344) | 스냅샷 복구를 통한 새 루트 볼륨 생성. |
| [VolumeOrchestrator.java:1369](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/engine/orchestration/src/main/java/org/apache/cloudstack/engine/orchestration/VolumeOrchestrator.java#L1369) | 선택한 기존 볼륨을 새 VM의 ROOT/device 0에 편입. |
| [VmStorageSelectionManager.java:258](https://github.com/ablecloud-team/ablestack-cloud/blob/2871963cd43aeb592f619c1b67d8a5974846409d/server/src/main/java/com/cloud/storage/VmStorageSelectionManager.java#L258) | 볼륨/스냅샷에 직접 스토리지 선택은 현재 거절된다. UI 선택창만 열어서는 지원할 수 없다. |

### 2.2 재현 범위

`audit-current.cjs`는 위 commit의 실제 목록 메서드를 추출하여 로컬 가짜 API 응답으로 실행했다. 네트워크/DB 변경은 없다. 결과: `evidence/source-observations.json`.

1. 후보 25개, page=2/pageSize=10이면 10개 대신 15개를 반환한다. `pageEnd = pageSize * (pageStart + 1)` 계산 오류이다. 후보 30개 이상에서는 다음 페이지와 중복될 수 있다.
2. 미연결 DATADISK가 부팅 출처 검사 없이 후보로 남는다. ROOT만 무조건 허용하도록 변경하는 것도 기존 복구 볼륨 호환성을 훼손할 수 있어, 실제 루트 출처를 검사해야 한다.
3. API가 count=0이고 배열을 생략하면 볼륨/스냅샷 모두 `undefined.forEach`로 실패한다.
4. Creating 상태 ROOT 스냅샷도 후보에 남는다.
5. `querySnapshotId`가 있어도 listSnapshots에 id가 전달되지 않는다.

실제 31번에서는 볼륨 1개(`test`), ROOT 스냅샷 14개가 표시됐다. 이는 생성 가능한 원본의 수라는 뜻이 아니다. 목록에서 상태·크기·스토리지 scope·원본 VM·복구 시점이 보이지 않았다. 템플릿 → 볼륨/스냅샷 전환 직후에는 이전 템플릿 요약과 부팅 설정이 남았고, 스냅샷 선택 후에도 원본 이름/복구 시점이 요약에 나타나지 않았다. `current-snapshot.txt/png`는 로컬 체크아웃에 보존한 읽기 전용 관측 증거이다. 배포 UI의 bundle hash와 서버 JAR 버전은 이번 조사에서 확인하지 않았으므로 최신 source와 동일한 배포라는 주장은 하지 않는다.

기존 [#1085](https://github.com/ablecloud-team/ablestack-cloud/issues/1085)는 OPEN이고, 볼륨/스냅샷 startvm=true/false 생성·부팅이 체크되지 않았다. 이번 Epic은 그 비ISO 항목을 구체화하며, Windows 설치/ISO 후속 항목은 #1085에서 계속 관리한다. [#1211](https://github.com/ablecloud-team/ablestack-cloud/issues/1211)과 [PR #1215](https://github.com/ablecloud-team/ablestack-cloud/pull/1215)의 스토리지 선택 개선은 두 원본 경로의 E2E 완료 증거가 아니다.

## 3. 기능 설계

### 3.1 서버가 판정하는 생성 원본

읽기 전용 `listVirtualMachineCreationSources`와 `validateVirtualMachineCreation` API를 신설하는 안을 채택한다. 두 API와 실제 deploy는 공통 `VmCreationSourceValidator`를 사용한다. 사전 조회는 용량 예약이나 생성 작업을 수행하지 않으며, deploy 시 반드시 다시 검증한다. 단일 원본 검사인 `validateVirtualMachineCreation`도 Cloud 목록 명령 처리 계약에 맞춰 `count: 1, creationsource: [원본]` envelope로 응답한다. UI는 정확히 한 개의 검사 결과가 있어야 제출을 허용한다.

목록 입력: sourcekind(volume/snapshot), zoneid, account/domainid/projectid, arch, keyword, state, id, page/pagesize, includeunavailable. 서비스 오퍼링 등 배치 조건은 validate API에서 검사한다. 기존 API/SDK의 `volumeid`/`snapshotid` 계약은 유지한다. 새 UI가 새 API를 사용할 수 없는 서버에서는 보수적 조회/선택 재조회 및 미지원 안내를 제공하고, 복구되지 않은 정보로 생성 가능 상태를 만들지 않는다.

응답은 허용된 원본만 포함하고 다음 정보를 제공한다.

```
id, sourcekind, name, volumetype, state, sizebytes,
zoneid, arch, hypervisor, sourcevm { id, displayname, name },
sourcevolumeid, snapshotcreated, storage { id, name, type, scope },
sourceusage (adopt-existing | restore-new),
bootprofile { ostypeid, boottype, bootmode, rootbus, provenance, completeness },
eligibility { allowed, reasoncodes[], message, checkedat, revision }
```

`sourcevm`는 현재 연결 VM과 과거 출처를 구분한다. 과거 VM을 알 수 없으면 '출처 정보 없음'으로 표시한다. 권한이 없는 VM/볼륨의 이름이나 상세 사유를 응답하지 않는다. 이는 기존 권한 모델을 재사용하는 신규 API 요구사항이며, 이번 조사로 권한 유출을 재현했다는 뜻은 아니다.

대표 사유: SOURCE_ATTACHED, SOURCE_NOT_READY, ROOT_PROVENANCE_UNKNOWN, SOURCE_TEMPLATE_MISSING, SNAPSHOT_NOT_RESTORABLE, STORAGE_SCOPE_UNSUPPORTED, SOURCE_ZONE_MISMATCH, ARCH_MISMATCH, BOOT_PROFILE_INCOMPLETE, STORAGE_UNAVAILABLE, CAPACITY_UNAVAILABLE, SOURCE_CHANGED. 상세 스토리지 정보는 기존 RBAC의 노출 범위를 유지한다.

### 3.2 볼륨 사용

- Ready/미연결/실제 데이터 경로/Zone·소유자 일치/KVM·arch·스토리지 scope·부팅 출처·원본 메타데이터를 검사한다. 단순 type=DATADISK를 부팅 가능으로 간주하지 않는다.
- 부팅 출처가 확인된 기존 복구 볼륨을 지원하려면 별도의 부팅 원본 메타데이터를 이용한다. 현재 ROOT와 복구된 ROOT 출처 볼륨이 1차 대상이다. 임의 데이터 디스크·업로드 이미지의 지원 확대는 이 Epic의 기본값이 아니다.
- CLUSTER SharedMountPoint(GFS2)/RBD(krbd)는 원본 pool을 유지하고 원본 cluster에 새 VM 배치를 고정한다. 다른 cluster/접근 불가능한 host 선택을 allocation 전에 거절하며, 적격성 API와 deploy가 같은 계약을 사용한다. HOST 및 CLVM 계열 확대는 이번 두 환경 필수 범위 밖이다.
- 사용 방식은 '기존 볼륨 사용' 하나로 고정한다. 원본 저장소/크기를 읽기 전용으로 표시하고 복사·이동·자동 크기 변경 옵션을 제공하지 않는다.
- VM 생성 개수는 1로 고정한다. 표준 deploy API는 서버가 단일 VM을 생성하므로, 실제 다중 호출/동시 사용자에 대해서도 원본 볼륨 잠금과 재검증을 적용하여 하나만 편입되게 한다.
- source 검사 → VM 할당 → 원본 편입 단계의 트랜잭션·락 경계를 명확히 한다. 원격 agent/storage 작업 동안 DB 락을 장시간 보유하지 않는다. 이미 연결된 동일 VM에 대한 재진입과 다른 VM의 탈취를 구분한다.
- 실패 시 원본 볼륨을 expunge/delete하지 않는다. VM 할당 전 실패는 원본 연결/타입/device 값과 사용량을 보존한다. 할당 후 실패는 안전한 복구 경계를 확인하고 '생성된 VM 보존/시작 재시도' 또는 '원본 편입 롤백 필요'를 표시한다. 불명 상태에서 자동 분리하지 않는다.
- 생성 후 일반 VM 삭제 정책이 이 볼륨에도 적용될 수 있음을 확인 화면에 알린다. 실패 정리 코드가 원본을 새로 만든 임시 볼륨으로 오인하지 않도록 소유권/출처를 저장한다.

### 3.3 스냅샷 복구

- 대상은 **볼륨 스냅샷 중 ROOT 출처**이다. VM 스냅샷의 디스크/메모리 전체 복원과 구분한다. VM 스냅샷에서 추출해 이미 ROOT 볼륨 스냅샷으로 등록된 항목은 해당 출처로 검사한다.
- BackedUp 등 provider별 실제 복구 가능한 상태와 저장소의 restore capability를 함께 판정한다. 모든 provider에 단일 상태 문자열을 강제하지 않는다. Creating/BackingUp/Error/Destroyed는 배포 가능한 것으로 표시하지 않는다.
- 원본 스냅샷은 유지하고 새로운 ROOT/device 0 볼륨을 생성한다. 복구된 디스크는 최소 snapshot의 논리 크기를 유지하며, snapshot 물리 사용량을 root logical size로 표시하지 않는다.
- 기존 VM이 삭제/expunge됐어도 원본 메타데이터와 스냅샷 저장 데이터가 유효하면 복구할 수 있게 한다. `findByIdIncludingRemoved` 의존성과 null dereference를 제거/방어하고 snapshot metadata로 size/template/OS/boot 정보를 확보한다.
- 현재 원본 볼륨 누락 오류는 templateId 입력을 요구하지만 DeployVMCmd는 templateId와 snapshotId 동시 입력을 거절한다. 사용자에게 불가능한 조합을 안내하지 않는다. 새 버전은 snapshot metadata로 자동 해결하고, 구 데이터가 불충분하면 명시적 재등록/메타데이터 보완 절차를 제시한다. API 파라미터 배타성은 유지한다.
- snapshot에서 inherited boot 정보는 **스냅샷 생성 시점**을 기준으로 한다. 현재 원본 VM 설정을 과거 snapshot에 덮어씌우지 않는다. 과거 snapshot에는 provenance/completeness를 표시한다.
- VM·새 볼륨·복구 작업의 소유권과 job/entity ID를 연결한다. 실패 정리는 이 요청이 만든 객체만 대상으로 하며 스냅샷 및 원본 VM/볼륨은 제외한다.

### 3.4 부팅 및 게스트 정합성

- OS/arch/hypervisor와 BIOS/UEFI·LEGACY/Secure Boot·루트 디스크 버스 정보를 원본 기반으로 초기화한다. 이전 템플릿/ISO의 설정이 남지 않게 한다.
- 일반 디스크 부팅을 기본으로 하고 추가 ISO/설치 ISO 부팅 marker를 요청에 넣지 않는다. 원본 UEFI NVRAM, Secure Boot 키, vTPM state 의존성이 있으면 복구 지원 범위를 capability로 판정한다. 기록이 없다는 이유로 복원 성공을 보장하지 않는다.
- guest OS가 disk 안에 가진 고정 IP·hostname·SID·기존 암호/SSH 키는 자동 초기화되는 것으로 안내하지 않는다. Cloud NIC의 새 MAC/IP와 게스트 설정을 구분한다. 중복 네트워크 identity 테스트는 격리 네트워크에서 수행한다.
- snapshot은 루트 디스크 복구이므로 별도 데이터 디스크 의존 서비스와 메모리 상태는 자동 복구되지 않는다. 화면에 이 범위를 표시하고 필요 데이터 볼륨은 생성 후 별도 연결 절차를 제시한다.

### 3.5 스토리지 및 용량

- 볼륨은 원본 저장소 사용을 표시한다. 컴퓨트 오퍼링의 tags/local storage/IOPS/encryption/placement가 실제 원본 디스크와 모순이면 사전에 거절한다.
- 스냅샷은 기존 allocator의 자동 배치를 1차 지원으로 유지한다. 후보 저장소/필요 용량/배치 제약을 읽기 전용으로 제시한다. 확인 못한 capacity는 0이나 충분으로 표시하지 않는다.
- 수동 target storage 선택은 현 `VmStorageSelectionManager`의 거절 계약과 snapshot restore allocator까지 확장·검증한 경우에만 활성화한다. 지원 확대는 P2 하위 범위로 별도 추적한다.
- 새 루트/데이터 디스크의 logical bytes, 예약/allocated bytes, 물리 여유 및 overprovisioning을 분리한다. 신규 추가 디스크가 지원되는 조합이면 전체 필요한 자원을 합산한다.

### 3.6 작업·실패·재시도

- 접수(jobid 반환), 루트 준비/편입, VM 할당, 시작, 완료의 단계와 생성 VM/볼륨을 표시한다. API jobstatus=1만으로 guest boot 검증 완료를 주장하지 않는다.
- startvm=false: ROOT 준비/편입 완료 후 Stopped 및 디스크 매핑을 확인한다. startvm=true: Cloud/host Running + 실제 guest boot를 후속 검증한다.
- 진행률은 실제 backend가 제공한 값만 표시한다. 제공되지 않으면 '스냅샷 복구 중'처럼 단계 상태를 표시하며 가짜 백분율을 만들지 않는다.
- 화면 이탈/재진입 시 jobid/entityid로 작업을 이어 조회한다. UI 오류/timeout/응답 유실은 확인 필요 상태이고 실패 확정과 구분한다.
- 제출 중 중복 클릭을 막고, 응답 유실 시 deploy 요청을 자동 재전송하지 않는다. source revision 재검증과 소유권 확인 없이 시작 재시도·정리·재생성을 실행하지 않는다.

## 4. UI 설계

기존 `DeployVM`의 좌측 단계 폼 + 우측 요약 구조, Mold 표/상태 태그/피드백/스크롤 규칙을 유지한다. 공통 `CreationSourceSelection` 컴포넌트와 정규화한 `selectedCreationSource` 모델을 추가하며 템플릿/ISO UI를 별도로 재설계하지 않는다.

### 4.1 생성 원본 선택

- 상단 라디오: 템플릿 / ISO / 볼륨 / 스냅샷. 하단 설명은 볼륨='기존 볼륨을 루트 디스크로 사용', 스냅샷='루트 디스크 스냅샷에서 새 디스크 복구'.
- 템플릿의 추천/공개/커뮤니티 분류는 두 방식에서 제거한다. 이름·UUID·원본 VM 검색, 사용 가능만 보기, 원본 상태 필터를 제공한다. 조회 실패/빈 목록/권한 제한/불일치를 각각 다르게 표시한다.
- 볼륨 열: 선택, 이름/UUID, 출처/OS, 상태·연결, 논리 용량, 저장소·scope, 사용 가능 여부/차단 사유.
- 스냅샷 열: 선택, 이름/UUID, 원본 VM·볼륨, 생성 시각, 상태, 루트 크기, 복구 가능 여부/차단 사유.
- 불가 항목은 선택 불가로 두고 행 안에 사유를 표시한다. 부적격한 ROOT나 DATADISK를 숨김만으로 처리하지 않아 원인을 알 수 있게 한다. 권한 밖 객체는 반환하지 않는다.
- backend pagination/count를 사용하고 UUID 기반 선택을 유지한다. 조건 변경 시 최신 적격성 재조회, 응답 generation 검사, 선택 및 관련 설정 초기화로 오래된 응답을 차단한다.

### 4.2 선택한 원본 및 부팅 환경

선택 직후 원본 이름/UUID·사용 방식·복구 시점·root 크기·저장소·OS·BIOS/UEFI·확인 시각을 표시한다. volume adoption 설명과 snapshot root-only 설명은 기본 노출한다. 메타데이터 불충분 시 '확인 필요'와 다음 조치를 보여준다.

부팅 유형은 원본에서 상속하고 기본 잠금 상태로 한다. 검증된 변경만 고급 옵션으로 허용한다. 암호/SSH 키/guest customization의 지원 여부를 표시하고, 미지원이면 입력 옵션을 활성화하지 않는다.

### 4.3 요약 및 제출

우측 요약 순서는 생성 방식 → 원본/시점 → 원본 사용 영향 → ROOT/스토리지 → 컴퓨트 → 네트워크 → OS/부팅 → 시작 옵션이다. 볼륨은 생성 개수 1, snapshot은 검증된 복구 개수/용량 계약에 따라 표시한다. 1차 검증에서는 두 방식 모두 단일 VM 생성으로 시작한다.

원본 미선택·적격성 조회 중·차단·조회 실패·조건 변경 상태에서는 버튼을 비활성화한다. '왜 생성할 수 없는지'를 버튼 가까이에 표시한다. 이름/네트워크 등 일반 필수 입력도 기존 검증을 유지한다.

최종 확인 대화상자(MoldDialog)는 원본/VM 이름·디스크 범위·사용 방식·시작 여부·guest identity/기존 자격증명 영향과 원본 보존/편입을 요약한다. 볼륨은 '기존 볼륨을 이 VM의 루트 디스크로 사용하는 것을 확인했습니다' 체크가 필요하다. snapshot은 루트 디스크만 복구한다는 정보를 표시한다.

### 4.4 반응형·접근성

- desktop: 현재와 같은 단계 폼/요약 2열. 긴 폼만 스크롤하고 요약/생성 버튼을 접근 가능하게 유지한다.
- tablet/mobile: 요약을 폼 아래로 이동하고 상단에 선택 원본 핵심 정보 표시. 표는 이름·시간·상태를 유지하며 보조 항목을 행 상세로 이동한다.
- 라이트/다크 테마, 상태 텍스트+색상, 키보드 선택/포커스, 차단 사유 읽기, 390px/1366px/1680px를 확인한다.

`mockup.html`은 예시 데이터로 UI 상태를 탐색하는 오프라인 설계안이다. 실제 API 호출이나 VM 생성 기능을 포함하지 않는다. 볼륨/스냅샷, 사용 불가 항목, 조회 실패, 선택 초기화, 최종 확인과 단계별 진행/부분 실패를 제공한다.

## 5. 구현 순서 및 완료 정의

1. P0: 후보/적격성 공통 계약 및 목록 결함 수정, 지정 CLUSTER GFS2/RBD 볼륨 편입 지원·안전성.
2. P1: snapshot provenance/복구, 원본 부팅 환경, UI/요약, async 실패·재시도.
3. P2: snapshot target storage 수동 지정 지원 확장(기본 자동 배치와 구분).
4. P1 release gate: UI 중심 실제 생성·부팅·원본 무결성·실패 복구 E2E와 template/ISO 회귀.

세부 케이스와 증거 요구사항은 [validation.ko.md](validation.ko.md), 이슈 번호·우선순위·의존성은 `issues.json`에 기록한다.

| 작업 | 우선순위 | 실제 하위 이슈 | 선행 작업 |
|---|---|---|---|
| 원본 적격성 API·목록 결함 | P0 | [#1336](https://github.com/ablecloud-team/ablestack-cloud/issues/1336) | 없음 |
| CLUSTER 기존 볼륨 편입·동시성·보존 | P0 | [#1337](https://github.com/ablecloud-team/ablestack-cloud/issues/1337) | #1336 |
| ROOT snapshot 복구·출처·보존 | P1 | [#1338](https://github.com/ablecloud-team/ablestack-cloud/issues/1338) | #1336 |
| OS·BIOS·UEFI·root bus 상속 | P1 | [#1339](https://github.com/ablecloud-team/ablestack-cloud/issues/1339) | #1336, #1338 |
| 후보 표·요약·최종 확인 UI | P1 | [#1340](https://github.com/ablecloud-team/ablestack-cloud/issues/1340) | #1336~#1339 |
| async 작업·부분 실패·재시도 | P1 | [#1341](https://github.com/ablecloud-team/ablestack-cloud/issues/1341) | #1337, #1338, #1340 |
| snapshot target storage 확장 | P2 | [#1342](https://github.com/ablecloud-team/ablestack-cloud/issues/1342) | #1336, #1338 |
| 실제 생성·guest boot·원본 E2E | P1 | [#1343](https://github.com/ablecloud-team/ablestack-cloud/issues/1343) | #1336~#1341 |

설계안 상호작용/반응형 확인 14개는 통과했다. 이는 UI 목업 검증이며 제품 코드/실 VM 검증이 아니다. 화면: [스냅샷 다크](evidence/proposed-snapshot-dark.jpg), [스냅샷 라이트](evidence/proposed-snapshot-light.jpg), [볼륨 다크](evidence/proposed-volume-dark.jpg), [볼륨 확인](evidence/proposed-volume-confirm-dark.jpg), [모바일 확인](evidence/proposed-mobile-confirm-light.jpg), [부분 실패](evidence/proposed-failure-dark.jpg). 긴 전체 페이지 캡처 2회가 timeout되어 안정적인 viewport 캡처를 사용했다.

Cloud 서버 변경은 WSL ext4 clone에서 변경 Maven 모듈과 필요한 의존 모듈만 빌드한다. 전체 Cloud 빌드나 GitHub Actions full build는 사용자 명시 요청이 있을 때만 실행한다. qemu/ftctl 변경이 필요해지는 경우 해당 산출물은 GitHub Actions로 빌드한다. UI 배포는 WEB-INF/META-INF 보존, /client/ 200, 활성 bundle hash/marker 확인을 포함한다.

필수 성공 범위는 31번 GFS2와 32번 Ceph krbd 각각의 볼륨/스냅샷 × Linux BIOS/Windows UEFI × startvm=true/false 8개, 총 16개이다. Epic 완료는 코드/설계/단위 검증만으로 처리하지 않는다. 지정 fixture에서 생성·게스트 부팅·데이터 확인·원본 무결성·실패 후 리소스 상태를 모두 연결한 실제 증거가 필요하다.

2026-10-09 지정 환경 반영: CLUSTER GFS2/RBD 원본 선택과 다른 cluster 차단 예시를 시안에 반영했다. [추가 확인 4개](evidence/environment-mockup-verification.json), [CLUSTER 볼륨 화면](evidence/proposed-volume-cluster-dark.jpg), [확인 화면](evidence/proposed-volume-cluster-confirm-dark.jpg). 기존 14개는 초기 시안 검증 기록이며 실제 제품 E2E와 구분한다.
