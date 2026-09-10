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

# Cross Hypervisor DR 보호 정보 캐시 구현 및 배포 결과

작성일: 2026-07-10  
기준 설계: `550-cross-hypervisor-dr-protection-view-cache-and-completed-checkpoint-design-20260710.md`

## 1. 구현 목표

- DR 상세 화면의 상태, 실행 진행, 토폴로지, 완료 복제 정보, 복제본을 `보호 정보` 탭 하나로 통합한다.
- 일반 조회는 DB 캐시만 읽고, Agent/FTCTL 상태 갱신은 Scheduler 또는 명시적 비동기 API에서만 수행한다.
- FTCTL의 진행 중 체크포인트와 대상에 영속화가 끝난 체크포인트를 분리한다.
- 이벤트 조회는 최근 20건을 기본 범위로 제한하고 조회 자체가 이벤트를 추가하지 않게 한다.
- 보호 해제와 계획 삭제 후 DB, 대상 VM/볼륨, FTCTL 런타임이 재테스트에 영향을 주지 않게 정리한다.

## 2. 구성요소별 구현 결과

### 2.1 UI

- 상세 탭을 `상세 | 보호 정보 | 이력 | 이벤트`로 정리했다.
- `보호 정보`는 단일 캐시 revision에서 상태/RPO, 최신 실행, 토폴로지, 마지막 완료 복제, 복제본을 표시한다.
- 탭 query 변경 시 전체 상세 데이터를 다시 읽지 않는다.
- 활성 작업이 있는 동안만 빠른 polling을 수행하고, 평상시에는 캐시를 사용한다.
- 이벤트 API에는 `page=1`, `pagesize=20`을 함께 전달하고 실패 알림을 처리한다.

### 2.2 API

- `getDrProtectionView`: DB 캐시 전용 동기 조회 API를 추가했다.
- `refreshDrProtectionView`: Agent 투영과 캐시 재생성을 수행하는 비동기 API를 추가했다.
- 두 명령을 `DisasterRecoveryClusterServiceImpl.getCommands()`에 등록했다.
- `DrProtectionViewResponse`의 object name을 `drprotectionview`로 고정해 UI extractor와 응답 계약을 일치시켰다.

### 2.3 Backend

- `DrProjectionScheduler`가 10초 주기, global lock, batch size 25로 활성 계획을 투영한다.
- 상세/목록/실행/복제본/체크포인트/이벤트 read API에서 Agent 동기 호출을 제거했다.
- 캐시는 DAO VO 프록시를 직접 Gson 직렬화하지 않는다.
- 계획은 명시 타입으로, 사이트는 비민감 최소 필드로, 실행/복제본/완료 체크포인트/이벤트는 기존 응답 DTO로 직렬화한다.
- 조회성 투영에서 `PROJECTION_REFRESH` 이벤트를 반복 저장하지 않는다.
- 보호 해제 투영은 replica, replica disk, restore point를 soft-delete한다.
- 계획 삭제 시 `dr_plan_view_cache` 행도 함께 제거한다.

### 2.4 Agent

- `FtctlDrStatusAnswer`에 current checkpoint와 latest completed checkpoint 필드를 분리했다.
- KVM wrapper가 FTCTL JSON의 typed 필드를 Agent 응답에 매핑한다.
- 세 컴퓨트 호스트의 `cloud-core` 및 KVM plugin JAR에는 변경 클래스만 갱신했다.

### 2.5 FTCTL

- cycle 시작 시 current sequence/ref/state를 기록한다.
- 실패한 cycle은 current state만 `FAILED`로 만들고 마지막 완료 체크포인트는 유지한다.
- `restore-points.jsonl` 기록이 끝난 뒤에만 latest completed sequence/ref/time/path를 갱신한다.
- `dr-status`는 current와 latest completed 필드를 동시에 반환한다.
- state 파일의 완료 필드가 없는 경우 마지막 정상 JSONL 레코드를 사용한다.

### 2.6 DB

- `dr_plan_view_cache`를 추가했다.
- `snapshot_json`은 `MEDIUMTEXT`이며 VO 길이도 `16777215`로 선언해 Generic DAO의 255자 절단을 방지했다.
- 기존 캐시 갱신은 `createForUpdate()`로 dirty field를 기록하고 갱신 후 재조회한다.
- 역할 권한에 `getDrProtectionView`, `refreshDrProtectionView`를 추가했다.

## 3. 실환경에서 추가로 발견하고 보정한 결함

| 결함 | 원인 | 보정 |
| --- | --- | --- |
| 신규 캐시 API가 Unknown API command | 명령 클래스는 존재하지만 plugin command 목록에 미등록 | `getCommands()`에 get/refresh 명령 등록 |
| 응답이 `null` object 아래에 배치 | response object name 누락 | `drprotectionview` object name 지정 |
| Scheduler Gson 예외 | DAO 프록시 VO의 런타임 타입을 직렬화하며 Java 보안 내부 필드 접근 | 명시 타입 및 응답 DTO 직렬화로 변경 |
| 캐시 JSON이 255자로 절단 | JPA `@Column` 기본 length가 Generic DAO 바인딩에 적용 | MEDIUMTEXT 실제 최대 길이 선언 |
| 명시적 refresh 결과만 정상이고 DB 캐시는 이전 값 | 로드한 VO 직접 갱신이 DAO dirty tracking에 잡히지 않음 | `createForUpdate()` 기반 갱신 |
| 보호 해제 후 restore point가 남음 | `markRemoved()+update()`가 soft-delete를 기록하지 못함 | `drRestorePointDao.remove()` 사용 |
| 계획 삭제 후 캐시가 남을 수 있음 | 캐시는 removed 컬럼이 없고 계획은 soft-delete | 계획 삭제 전에 캐시 행 제거 |

## 4. 빌드 및 테스트

| 대상 | 결과 |
| --- | --- |
| Cloud core 변경 모듈 Maven | PASS, Checkstyle 0 |
| Cloud KVM 변경 모듈 Maven | PASS, Checkstyle 0 |
| Disaster Recovery plugin Maven package | PASS, Checkstyle 0 |
| KVM Agent wrapper test | PASS, 10/10 |
| `FtctlDrRuntimeProjectionAdapterTest` | PASS, 10/10 |
| UI production build | PASS |
| FTCTL self-test | PASS |
| FTCTL GitHub Actions RPM | PASS, run `29083036636` |

DR plugin 전체 테스트는 46건 중 기존 동작 변경을 반영하지 않은 테스트 기대값 때문에 8 failure, 1 error가 남아 있다. 이번 변경의 핵심 투영 테스트 10건과 변경 모듈 package는 통과했으며, 전체 테스트의 실패 항목은 실행 단계 수, 강화된 plan validation, capability validation에 대한 기존 fixture 불일치다.

## 5. 배포 결과

### 5.1 관리 서버

- Cloud 전체 재빌드 없이 변경 클래스만 monolithic JAR에 반영했다.
- UI는 정적 파일만 갱신했고 `WEB-INF`를 보존했다.
- `mold=active`, `/client/=HTTP 200`을 확인했다.
- 활성 bundle에서 `getDrProtectionView`, `label.dr.protection.info` marker를 확인했다.

### 5.2 컴퓨트 호스트

- 10.10.32.1/2/3의 Agent core/KVM JAR에 변경 클래스만 반영했다.
- GitHub Actions RPM `ablestack_vm_ftctl-0.9.1-1.noarch`를 세 호스트에 재설치했다.
- 세 호스트 모두 `mold-agent=active`, `ablestack-vm-ftctl.timer=active`다.

### 5.3 배포 산출물 SHA256

| 산출물 | SHA256 |
| --- | --- |
| Cloud management changed classes | `8582b575850baf829ae1157e5a0fabbe32354f6af98cdd15062288e44bcbbcb7` |
| Agent core changed classes | `98d31122357f3151a7214255eec0dc3ea42b29d4af579cea01b2453283233080` |
| Agent KVM changed classes | `e2faca2578b56715c8e92dae62c1b21ac64c9054b33f6c042c4c3e2eab5eceaf` |
| UI dist | `80266e676f9278b045e7c7bb225da66659b22bcb3f511ffb0eac396cce4602d0` |
| FTCTL RPM | `0c402ef06e984d939a4986ed381d40aab065d1cd5b5e592f6340a11f8bce4e0a` |

## 6. 실환경 검증 결과

- `getDrProtectionView`와 `refreshDrProtectionView`가 API 목록에 등록됐다.
- cache snapshot 길이: 32,446 bytes.
- cache projection state: `READY`.
- latest completed checkpoint: sequence 19, state `READY`.
- FTCTL current checkpoint: sequence 20.
- FTCTL latest completed checkpoint: sequence 19, state `TARGET_READY`.
- cache event count: 6, 최대값 20 이내.
- Scheduler 두 주기 사이 `PROJECTION_REFRESH` event count 증가: 0.
- cache 민감 키 scan: PASS.
- 브라우저 탭: `상세 | 보호 정보 | 이력 | 이벤트`.
- 이벤트 UI 6건 표시, 브라우저 console error 0.

## 7. 재테스트 클린업

정리 대상 plan UUID: `211c5a64-1d5b-4621-a752-f457e2437095`

- `releaseDrProtection`: SUCCEEDED.
- `deleteDrPlan`: SUCCEEDED.
- 대상 VM/ROOT volume: expunge 처리 및 removed 기록 완료.
- active plan/run/replica/replica disk/restore point/cache: 모두 0.
- 세 호스트의 대상 domain/RBD image/plan process: 모두 0.
- 세 호스트의 계획 runtime/profile path: 모두 0.
- vCenter 원본 VM의 FTCTL 임시 snapshot: 0.

## 8. AS-IS / TO-BE

| 구성요소 | AS-IS | TO-BE |
| --- | --- | --- |
| UI | 상태, 토폴로지, 복제본이 분산되고 상세 진입마다 여러 API 호출 | 하나의 `보호 정보` 탭이 단일 cache snapshot 사용 |
| API | read가 Agent 투영을 유발 | read는 DB/cache 전용, refresh는 비동기 API |
| Backend | polling마다 Agent 호출 및 이벤트 write 가능 | 단일 Scheduler가 lock 아래 투영하고 의미 있는 이벤트만 저장 |
| Cache | 분산 테이블을 매 요청 조립 | versioned, redacted MEDIUMTEXT snapshot |
| Agent | current와 완료 체크포인트 구분 부족 | 두 checkpoint 계열을 typed answer로 전달 |
| FTCTL | 진행 sequence가 완료 복제로 오인될 수 있음 | current N과 latest completed N-1 분리 |
| DB | 진행 checkpoint가 READY로 기록될 가능성 | latest completed checkpoint만 READY upsert |
| Release | restore point/cache/runtime 잔여 가능 | restore point soft-delete, cache 삭제, 재테스트 cleanup 검증 |

## 9. 다음 테스트

새 계획을 생성하고 최초 동기화 완료 후 다음 순서로 검증한다.

1. current sequence와 latest completed sequence가 transfer 중 분리되는지 확인한다.
2. cycle 완료 후 FTCTL JSONL, DB checkpoint, cache snapshot의 sequence/ref/time이 동일한지 확인한다.
3. 일반 상세 조회 중 Agent command가 발생하지 않는지 확인한다.
4. `보호 정보` cache 시각이 Scheduler 주기에 맞춰 갱신되는지 확인한다.
5. 완료 체크포인트를 기준으로 Test Failover를 수행한다.

## 10. 2026-07-14 배포 후 발견된 소비 계층 설계 공백

캐시 생성, Scheduler, Agent/FTCTL 투영은 정상이나 UI가 terminal operator run에서
polling을 중단해 continuous protection의 최신 cache를 표시하지 못하는 문제가
확인됐다. 또한 async Plan 생성 완료 전에 목록을 조회하는 read-after-write 경쟁과
Ant Design descriptions의 light 기본색이 dark mode에서 남는 문제가 확인됐다.

이 항목은 2026-07-10 구현 결과를 무효화하지 않는다. 캐시 생산 계층은 유지하고
UI/API 소비 계약만 보강한다. 구현 전 상세 설계와 수용 기준은
`552-cross-hypervisor-dr-async-read-consistency-and-live-cache-ui-design-20260714.md`를
따른다.
