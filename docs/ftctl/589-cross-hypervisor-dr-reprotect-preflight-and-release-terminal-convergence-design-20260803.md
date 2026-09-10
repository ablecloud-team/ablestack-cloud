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

# 589. Cross-Hypervisor DR Reprotect Preflight And Release Terminal Convergence Design

- 작성일: 2026-08-03
- 상태: 구현 완료, 2026-08-21 profile-independent status 회귀 보강
- 검증 Plan: `2514a846-64a2-4bc7-ba88-38a874410782`
- 적용 범위: UI, API, Cloud Backend, Mold Agent, FTCTL 계약, Cloud DB
- FTCTL 부속 설계:
  `ablestack-qemu-exec-tools/docs/ftctl/446-ftctl-dr-transition-preflight-v2-and-release-tombstone-contract-design-20260803.md`
- 선행 설계: 570, 578, 584, 587, 588
- 후속 회귀 방지 설계: 614

> 2026-08-21 보강: Release가 profile을 삭제한 뒤에도 FTCTL tombstone만으로
> 상태를 재구성하고, Cloud는 성공한 Agent terminal 응답으로 즉시
> `UNPROTECTED / DISABLED`를 커밋한다. 공통 status 변경 시 Release를 포함한
> 전체 DR action contract suite를 배포 게이트로 실행한다.

## 1. 목적과 최종 결정

Failover 후 `현재 운영 사이트에서 재보호`와 `보호 해제`는 서로 반대되는
작업이다.

- Reprotect는 현재 TARGET 운영 권한을 유지하면서 TARGET -> SOURCE 역방향
  보호를 구성한다.
- Release는 복제와 자동 복구 관계를 종료하되 현재 운영 VM의 전원, 네트워크,
  디스크 및 운영 권한을 변경하지 않는다.

이번 설계는 다음 오류를 함께 해결한다.

1. Cloud가 `TRANSITION_PREFLIGHT`를 요청했지만 구형 Agent가 일반
   `PLAN_AUTHORITY` 응답을 반환해 정상 Reprotect가 실패한다.
2. Release 완료 후 Plan을 무조건 `NEW/SOURCE`로 바꿔 실제 TARGET 운영 권한과
   충돌한다.
3. FTCTL은 `RELEASED`인데 Cloud runtime, Protection View cache와 UI에는 과거
   `FAILED_OVER_UNPROTECTED/DEGRADED` 상태가 남는다.
4. 일반 Release 대화상자에서 강제 실행을 일반 옵션처럼 노출해 작업의 위험성과
   최종 결과가 불명확하다.

핵심 결정은 다음과 같다.

```text
Reprotect success:
  READY / ENABLED / TARGET / REVERSE_PROTECTED

Release success:
  UNPROTECTED / DISABLED / <authority before release> / STOPPED
```

Release는 VM 삭제나 VM 정지 작업이 아니다. 현재 authority는 Release 직전 값을
보존하며, 작업 이름과 UI 설명도 이를 명시한다.

## 2. 실환경 Preflight 증거

### 2.1 실행 이력

| 항목 | 확인 값 |
| --- | --- |
| Reprotect Run | id `126`, `FAILED`, `DR_TRANSITION_ENGINE_PREFLIGHT_FAILED` |
| Reprotect 완료 시각 | 2026-08-02 23:46:31 KST |
| Release Run | id `127`, `SUCCEEDED`, `completed` |
| Release 완료 시각 | 2026-08-02 23:48:18 KST |
| Release 요청 | `force=true` |
| Release FTCTL | `RELEASED / release-completed`, scheduler `STOPPED` |
| 대상 VM | id `256`, `w22-01-dr`, host id `2`, `Running` |

Reprotect 당시 안전 조건은 충족되어 있었다.

```text
Cloud active_side=TARGET
target_power_state=POWERED_ON
source_fence_state=ACKNOWLEDGED
cloud_authority_generation=2160
```

### 2.2 Agent 배포 불일치

현재 소스의
`LibvirtFtctlDrStatusCommandWrapper.execute()`는
`StatusScope.TRANSITION_PREFLIGHT`를 `dr-transition-preflight`로 분기하고,
`transitionPreflightAnswer()`에서 boolean `ready` 필드를 검증한다.

그러나 10.10.32.2에는 다음 Agent JAR이 실행 중이었다.

```text
cloud-plugin-hypervisor-kvm-4.22.0.0-Mold.Europa-202606280754.jar
```

설치 JAR에는 다음 최신 wrapper 식별 문자열이 없었다.

```text
FTCTL_DR transition preflight did not return a boolean ready field
```

같은 호스트에서 `ablestack_vm_ftctl dr-capabilities --json`을 직접 실행했을 때
strict capability JSON 대신 usage/help가 반환됐다. help에는 `dr-capabilities`와
`dr-transition-preflight`가 표시되므로, 설치 package 안에서도 CLI dispatcher와
capability 실행 계약의 provenance를 배포 후 반드시 검증해야 한다.

실제 Run evidence에는 전용 preflight가 아니라 다음 일반 응답이 저장됐다.

```json
{
  "command": "dr-status",
  "status_scope": "PLAN_AUTHORITY",
  "action": "dr-cutover-commit",
  "state": "FAILED_OVER"
}
```

따라서 실패 원인은 source isolation이 아니라 **Cloud/Agent 응답 계약 버전
불일치**다.

### 2.3 Release 후 projection 불일치

Release 후 실제 상태는 다음처럼 갈렸다.

| 저장소 | 상태 |
| --- | --- |
| FTCTL | `RELEASED`, scheduler `STOPPED` |
| `dr_run` | RELEASE `SUCCEEDED` |
| `dr_plan` | `NEW / ENABLED / SOURCE` |
| API projection | 과거 `FAILED_OVER_UNPROTECTED / DEGRADED` |
| `dr_plan_runtime` | 오래된 worker/checkpoint/protection projection 일부 잔존 |
| 대상 VM | TARGET에서 계속 `Running` |

이는 Release의 data-plane 정리 자체는 성공했으나 control-plane terminal commit이
원자적으로 완료되지 않았음을 의미한다.

## 3. 오류 원인

### 3.1 JSON 문자열을 다시 해석하는 느슨한 Agent 경계

`DrSourceIsolationPreflightServiceImpl.runPreflight()`는 Agent Answer가 성공인지
확인한 뒤에도 `statusJson`을 다시 파싱해 `ready`와 `active_side`를 찾는다.

구형 Agent가 일반 status를 성공 응답으로 반환하면 다음 문제가 생긴다.

- transport `result=true`와 transition readiness가 구분되지 않는다.
- `ready` 누락이 배포 불일치인지 실제 안전 조건 실패인지 구분되지 않는다.
- `engineProbe.getDetails()`가 `OK`이면 실패 메시지에도 `OK`가 저장된다.
- Cloud는 action을 시작하기 전 Agent wrapper 계약 버전을 검증하지 않는다.

### 3.2 Release terminal commit이 projection adapter에 분산

현재 `FtctlDrRuntimeProjectionAdapter.cleanupReleasedProjection()`은 다음을
수행한다.

```text
active replica removed 처리
restore point removed 처리
plan.state=NEW
plan.active_side=SOURCE
target readiness 제거
```

그러나 다음은 같은 terminal transaction에 포함되지 않는다.

- Release 직전 authority 보존
- `admin_state=DISABLED`
- `dr_plan_runtime`의 RELEASED/STOPPED 정규화
- Protection View cache 즉시 재생성
- latest operation을 RELEASE/SUCCEEDED로 고정
- 기존 TARGET VM을 변경하지 않았다는 audit evidence

### 3.3 `NEW` 상태가 Release 의미를 표현하지 못함

`NEW`는 아직 보호가 구성되지 않은 신규 Plan을 의미한다. 이미 Failover와
Release를 수행한 Plan에 `NEW`를 사용하면 다음 정보가 사라진다.

- 보호 관계가 의도적으로 종료됐다는 사실
- 마지막 운영 authority
- 다시 보호하려면 full seed가 필요하다는 사실
- Sync/Failover/Failback이 비활성인 이유

### 3.4 강제 Release UX가 정상 Release와 혼합

`DrPlanList.vue`는 Failover, Failback, Release 모두에 같은 `force` switch를
표시한다. Release에서는 force가 다음 조건에서만 필요하다.

- 정상 release preflight가 실패했지만 잔여 runtime 정리가 반드시 필요함
- 사용자가 현재 복제 보장을 포기한다는 점을 명시적으로 승인함

일반 Release에서 force를 먼저 노출하면 정상 종료와 강제 복구의 차이를 알 수
없다.

## 4. 상태와 권한 불변조건

1. UI는 Agent 또는 FTCTL을 직접 호출하지 않는다.
2. Reprotect/Release API는 Run을 만든 뒤 즉시 반환한다.
3. Cloud는 Plan, Run, authority, VM lifecycle과 최종 projection을 소유한다.
4. Agent는 typed command/answer를 전달하고 의미를 추론하지 않는다.
5. FTCTL은 scheduler, lock, checkpoint와 release tombstone을 소유한다.
6. Reprotect는 성공 전후 모두 TARGET authority를 유지한다.
7. Reprotect 실패는 정상 serving TARGET을 `ERROR`로 바꾸지 않는다.
8. Release는 current VM power/network/storage를 변경하지 않는다.
9. Release는 직전 authority를 보존한다.
10. Release 후 scheduler와 worker는 반드시 STOPPED/IDLE이다.
11. Release 후 active replica/restore point는 보호 자원으로 조회되지 않는다.
12. Release 후 Plan은 `UNPROTECTED/DISABLED`이며 자동 RPO 보장이 없다.
13. View cache의 latest operation은 성공한 RELEASE Run이어야 한다.
14. Agent/FTCTL 계약 불일치는 action 실행 전에 차단한다.
15. force는 일반 경로가 아니라 별도 복구 경로다.

## 5. 상태 전이 설계

### 5.1 Reprotect

```text
FAILED_OVER_UNPROTECTED / TARGET
  -> REPROTECT_PREFLIGHT / TARGET
  -> REPROTECTING / TARGET
  -> reverse full seed or valid reverse baseline
  -> reverse scheduler RUNNING
  -> READY / ENABLED / TARGET / REVERSE_PROTECTED
```

실패 시:

```text
FAILED_OVER_UNPROTECTED / TARGET
  -> preflight failure
  -> FAILED_OVER_UNPROTECTED / TARGET
```

Plan과 serving replica는 실패 상태로 바꾸지 않고 Run만 FAILED로 종결한다.

### 5.2 Release

```text
<protected state> / <current authority>
  -> RELEASING / <same authority>
  -> FTCTL RELEASED tombstone
  -> Cloud terminal transaction
  -> UNPROTECTED / DISABLED / <same authority>
```

Release 실패 시 Plan은 작업 전 상태와 authority를 유지한다. 부분 정리가 감지되면
`RELEASE_RECOVERY_REQUIRED`로 표시하고 force recovery만 허용한다.

### 5.3 Release 후 허용 작업

| 작업 | 적용 | 설명 |
| --- | --- | --- |
| 이력/이벤트 조회 | 허용 | Release와 이전 보호 이력 조회 |
| 보호 다시 구성 | 허용 | current authority에서 FULL_RESEED로 새 보호 구성 |
| DR 계획 삭제 | 허용 | Cloud 관계 정보만 삭제, VM은 유지 |
| 수정 | 제한 허용 | 보호 재구성에 필요한 대상/정책만 수정 |
| Sync/Pause/Resume | 숨김 | 활성 보호 scheduler 없음 |
| Test Failover/Failover/Failback/Reprotect | 숨김 | 보호 관계 없음 |
| 보호 해제 | 숨김 | 이미 해제됨 |

## 6. UI 상세 설계

### 6.1 대상 파일

- `ui/src/utils/dr/resourceActions.js`
- `ui/src/utils/dr/actionAvailability.js`
- `ui/src/utils/dr/planState.js`
- `ui/src/components/dr/DrStatusPill.vue`
- `ui/src/views/infra/dr/DrPlanList.vue`
- `ui/src/locales/ko_KR.json`
- `ui/src/locales/en.json`

### 6.2 Reprotect 대화상자

`DrPlanList.vue`에 다음 computed/method를 추가한다.

```javascript
isReprotectAction ()
loadTransitionPreflight(plan, 'REPROTECT')
validateTransitionPreflightBeforeSubmit()
```

대화상자는 편집 입력 대신 다음 read-only snapshot을 표시한다.

```text
현재 운영 사이트
현재 authority / generation
대상 VM power state
원본 사이트 isolation/fence state
reverse path readiness
Agent contract version
FTCTL contract version
checkedAt / expiresAt
```

`ready=false`이면 확인 버튼을 비활성화하고 typed reason을 사용자 문장으로
변환한다. 대화상자가 30초 이상 열린 경우 submit 직전에 다시 조회한다.

### 6.3 Release 대화상자

기본 화면:

```text
현재 운영 VM은 정지하거나 삭제하지 않습니다.
복제와 자동 복구, RPO 보장만 종료됩니다.
Release 후 상태: 보호 해제됨
Release 후 운영 사이트: <current authority site>
```

정상 preflight가 READY이면 force switch를 표시하지 않는다. 정상 preflight가
실패하고 backend가 `forceApplicable=true`를 반환한 경우에만 “강제 보호 해제”
확장 영역을 표시한다.

강제 경로 필수 입력:

```text
force=true
acknowledgement=true
reason=<non-empty>
```

### 6.4 Release 후 표시

- 상태 pill: `보호 해제됨`
- 보조 문구: `현재 운영 사이트: <SOURCE|TARGET>`
- scheduler: `중지됨`
- RPO: `보장 안 함`
- 최근 작업: `보호 해제 / 성공`
- 대상 VM link는 VM 자체가 존재하는 동안 유지한다.

UI는 성공 notification 직후 목록을 낙관적으로 바꾸지 않는다. Run acceptance 후
Protection View의 snapshot version이 증가하고 latest operation이 RELEASE로
수렴할 때 화면을 교체한다.

## 7. API 상세 설계

### 7.1 Transition preflight 조회

신규 API:

```text
getDrTransitionPreflight
  planid=<uuid>
  operation=REPROTECT|FAILBACK
```

응답 DTO `DrTransitionPreflightResponse`:

```json
{
  "ready": true,
  "operation": "REPROTECT",
  "contractversion": "dr-transition-preflight-v2",
  "statusscope": "TRANSITION_PREFLIGHT",
  "activeSide": "TARGET",
  "expectedGeneration": 2160,
  "authorityGeneration": 2160,
  "targetPowerState": "POWERED_ON",
  "sourceFenceState": "ACKNOWLEDGED",
  "sourcePowerState": "UNKNOWN",
  "retryable": false,
  "reasonCode": "",
  "checkedAt": "...",
  "expiresAt": "..."
}
```

이 API는 read-only이며 Run을 만들지 않는다.

### 7.2 Release preflight 조회

신규 API:

```text
getDrReleasePreflight planid=<uuid>
```

응답은 current authority, serving VM, active worker, scheduler, runtime resource,
normalReady, forceApplicable과 blocking reason을 반환한다.

### 7.3 Action API

`startDrReprotect`와 `releaseDrProtection`은 기존 비동기 계약을 유지한다.

`releaseDrProtection` 검증:

```text
force=false -> normal release readiness 필수
force=true  -> acknowledgement=true, reason non-empty, forceApplicable=true 필수
```

API 응답은 accepted Run UUID만 반환한다. terminal 결과는 Run/Protection View로
조회한다.

### 7.4 보호 다시 구성 API

신규 사용자 API:

```text
reestablishDrProtection
  planid=<uuid>
  acknowledgement=true
  reason=<non-empty>
```

구현 대상:

```text
ReestablishDrProtectionCmd
DrConstants.RUN_TYPE_REESTABLISH_PROTECTION
DrProtectionOrchestrator.reestablishProtection()
DrRunExecutorImpl REESTABLISH_PROTECTION 분기
resourceActions.js key=reestablishprotection
```

이 API는 `enablePlan`과 `startDrSync`를 UI에서 연속 호출하지 않는다. Backend가
하나의 비동기 Run에서 다음을 원자적으로 조정한다.

```text
UNPROTECTED/DISABLED/<current authority>
  -> runtime/profile 재생성
  -> FULL_RESEED 접수
  -> engine accepted
  -> SYNCING/ENABLED/<same authority>
```

engine acceptance 전 실패하면 Plan은 `UNPROTECTED/DISABLED`에 남는다. FTCTL에는
새 action을 만들지 않고 기존 `dr-plan-apply`와 `dr-sync-start`의 FULL_RESEED
경로를 사용한다.

## 8. Backend 상세 설계

### 8.1 typed transition answer

대상:

- `core/.../FtctlDrStatusCommand.java`
- `core/.../FtctlDrStatusAnswer.java`
- `DrSourceIsolationPreflightServiceImpl.java`

`FtctlDrStatusCommand`에 다음 계약 상수를 추가한다.

```java
TRANSITION_PREFLIGHT_CONTRACT_VERSION = "2";
```

`FtctlDrStatusAnswer`에 다음 typed 필드를 추가한다.

```java
Boolean transitionReady;
String transitionContractVersion;
String transitionOperation;
String activeSide;
Long expectedAuthorityGeneration;
Long authorityGeneration;
String targetPowerState;
String sourceFenceState;
String sourcePowerState;
Boolean retryable;
String transitionReasonCode;
```

`DrSourceIsolationPreflightServiceImpl`은 `statusJson`을 재파싱하지 않는다.

```java
FtctlDrStatusAnswer answer = requireTransitionAnswer(...);
validateScope(answer, TRANSITION_PREFLIGHT);
validateContract(answer, "2");
validateIdentity(answer, planUuid, operation);
validateAuthority(answer, TARGET, generation);
return answer.getTransitionReady() ? success(...) : typedFailure(...);
```

Answer type/scope/version이 맞지 않으면 실제 isolation 실패와 구분해 다음 코드를
반환한다.

```text
DR_AGENT_TRANSITION_PREFLIGHT_CONTRACT_MISMATCH
```

`answer.getDetails()`가 `OK`인 경우 failure message로 사용하지 않는다.

### 8.2 capability gate

Reprotect worker는 transition preflight 전에 `FtctlDrCapabilitiesCommand`로 다음을
요구한다.

```text
required command: dr-transition-preflight
required feature: dr-transition-preflight-v2
required Agent wrapper contract: 2
```

구형 Agent이면 FTCTL action을 시작하지 않고 Run step을 다음처럼 종결한다.

```text
step=agent-contract-preflight
error=DR_AGENT_TRANSITION_PREFLIGHT_CONTRACT_MISMATCH
retryable=false
```

### 8.3 Release lifecycle service

`cleanupReleasedProjection()`의 책임을 신규
`DrReleaseLifecycleService.commitReleasedState()`로 이동한다.

입력:

```java
planId
releaseRunId
authorityBeforeRelease
FtctlDrReleaseTombstone
forceContext
```

한 transaction에서 다음을 수행한다.

1. Release Run이 SUCCEEDED이고 tombstone identity가 일치하는지 확인
2. active replica/disk/restore point를 removed 처리
3. Plan을 `UNPROTECTED/DISABLED/<preserved authority>`로 갱신
4. target readiness/RPO timestamp와 오류를 정리
5. `dr_plan_runtime`을 STOPPED/IDLE/UNPROTECTED로 upsert
6. runtime worker, owner, current cycle, recovery error를 null/0으로 정리
7. Release Run step에 preserved authority와 `vmMutated=false` 저장
8. commit 후 Protection View를 즉시 rebuild

DB commit 후 cache rebuild가 실패하면 Plan terminal commit은 되돌리지 않는다.
cache를 `STALE`로 표시하고 background rebuild를 재시도한다.

active replica를 removed 처리하기 전에 다음 audit snapshot을
`dr_replica.runtime_state_json`과 Release final step에 저장한다.

```text
releasedAuthoritySide
releasedAuthoritySiteId
targetVmId/targetExternalRef
targetVmName
targetPowerState
vmMutated=false
releasedAt
```

`DrReplicaDao.findLatestByPlanIdIncludingRemoved()`를 추가해 Protection View가
Release 후 informational VM link를 만들 수 있게 한다. 이 removed replica는
runtime resource나 action eligibility 계산에는 사용하지 않는다.

### 8.4 projection 우선순위

Protection View current 상태는 다음 순서로 결정한다.

1. active Run
2. Plan terminal state
3. normalized `dr_plan_runtime`
4. latest completed Run은 이력으로만 사용
5. historical cutover/failback session은 current state를 덮어쓰지 않음

`plan.state=UNPROTECTED`이면 과거 `FAILED_OVER` session이나 실패한 Reprotect
Run으로 current protection state를 계산하지 않는다.

`RELEASED/release-completed`는 일반 복제 projection보다 먼저 처리한다. Plan UUID와
status boundary를 검증한 직후 `cleanupReleasedProjection()`으로 진입하며, 프로필
삭제로 비어 있는 source hardware, 현재 cycle, restore point 정보는 다시 검증하지
않는다. 따라서 Release terminal commit은 다음 순서를 따른다.

```text
PLAN_AUTHORITY 조회
  -> plan UUID/status boundary 검증
  -> RELEASED 또는 release-completed 판정
  -> replica/restore-point 논리 제거
  -> Plan UNPROTECTED/DISABLED + 직전 authority 보존
  -> runtime STOPPED/IDLE/UNPROTECTED
  -> 즉시 반환
```

이 빠른 경로는 Release가 성공한 뒤 과거 checkpoint나 hardware projection이
`FAILED_OVER_UNPROTECTED`를 다시 덮어쓰는 것을 막는다.

## 9. Agent 상세 설계

대상:

- `LibvirtFtctlDrStatusCommandWrapper.java`
- `LibvirtFtctlDrCapabilitiesCommandWrapper.java`
- 관련 wrapper unit test

### 9.1 strict envelope

`transitionPreflightAnswer()`는 다음 필드를 필수 검증한다.

```text
command=dr-transition-preflight
schema_version=2
contract_version=dr-transition-preflight-v2
status_scope=TRANSITION_PREFLIGHT
plan_uuid=request.planUuid
operation=request.transitionOperation
ready=boolean
active_side=TARGET
```

누락, 타입 오류, identity 불일치는 generic status로 fallback하지 않는다.

### 9.2 typed Answer 변환

wrapper는 JSON 전체를 증거로 보존하되 Cloud 판단에 필요한 필드는 typed Answer에
복사한다. 일반 `dr-status` parser와 transition parser는 서로 호출하지 않는다.

### 9.3 배포 provenance

Capabilities Answer에 다음을 포함한다.

```text
statusWrapperContractVersion=2
actionCommandCodeSource
statusCommandCodeSource
wrapperCodeSource
ftctlVersion
runtimeSchemaVersion
```

Cloud는 wrapper code source가 현재 KVM plugin JAR이고 contract version이 2인지
확인한다.

## 10. FTCTL 상세 계약

FTCTL 변경은 부속 문서 446을 따른다.

필수 변경:

1. `dr-transition-preflight` JSON에 schema/contract/status scope 추가
2. `dr-capabilities`에 `dr-transition-preflight-v2` 추가
3. preflight는 profile/status/VM을 변경하지 않는 read-only 유지
4. `dr-release` terminal JSON에 release tombstone 필드 추가
5. release tombstone은 profile 삭제 후에도 plan-scoped status 조회로 반환
6. release는 VM 전원, 디스크, 네트워크를 변경하지 않음

## 11. DB 설계

### 11.1 스키마 결정

신규 테이블이나 신규 컬럼은 추가하지 않는다.

- `dr_plan.state`, `admin_state`, `active_side`는 varchar이므로
  `UNPROTECTED/DISABLED/<side>`를 저장할 수 있다.
- `dr_plan_runtime.protection_state`에 `UNPROTECTED`를 사용한다.
- audit는 기존 Run/Step/Event와 removed replica/restore point 이력에 저장한다.
- Release 후 VM link는 latest removed replica의 release audit snapshot에서
  읽으며 active protection resource로 취급하지 않는다.

### 11.2 기존 데이터 보정

DB upgrade 스크립트는 무조건 UPDATE하지 않는다. 다음 조건을 모두 만족하는
Plan만 후보로 조회해 backend repair service가 보정한다.

```text
latest RELEASE Run == SUCCEEDED
active RELEASE Run 없음
active replica 없음
active restore point 없음
FTCTL tombstone == RELEASED
```

authority 결정 순서:

```text
Release Run step authorityBeforeRelease
-> latest acknowledged cutover/failback session
-> existing plan.active_side
-> 결정 불가 시 repair 중단
```

보정 결과:

```text
dr_plan.state=UNPROTECTED
dr_plan.admin_state=DISABLED
dr_plan.active_side=<resolved authority>
dr_plan_runtime.scheduler_state=STOPPED
dr_plan_runtime.scheduler_desired_state=STOPPED
dr_plan_runtime.replication_activity_state=IDLE
dr_plan_runtime.protection_state=UNPROTECTED
dr_plan_runtime.error_code/error_message=NULL
dr_plan_view_cache 재생성
```

### 11.3 create/upgrade 파일

- `setup/db/create-schema.sql`: state 주석/초기값 계약만 동기화
- `setup/db/22beta4to22GA.sql`: idempotent repair marker와 필요한 index만 반영
- 실제 상태 보정은 management repair service를 통해 Agent tombstone을 검증한 후
  수행

## 12. 테스트 설계

### 12.1 Cloud unit tests

1. typed transition Answer READY
2. scope mismatch
3. contract version mismatch
4. missing `ready`
5. generation mismatch
6. `details=OK`를 failure message로 사용하지 않음
7. Reprotect failure가 TARGET authority/replica를 보존
8. Release가 authority를 SOURCE/TARGET 각각 보존
9. Release가 VM lifecycle을 호출하지 않음
10. Release가 Plan/runtime/cache를 UNPROTECTED로 수렴
11. historical cutover가 UNPROTECTED current state를 덮어쓰지 않음
12. force validation

### 12.2 Agent wrapper tests

1. v2 JSON -> typed Answer round trip
2. 일반 PLAN_AUTHORITY JSON 거부
3. wrong plan/operation 거부
4. old schema 거부
5. malformed boolean 거부
6. capability provenance 반환

### 12.3 UI tests

1. Reprotect modal preflight 표시/만료 재조회
2. normal Release에서 force 숨김
3. force applicable일 때만 확장 영역 표시
4. acknowledgement/reason 검증
5. UNPROTECTED action catalog
6. Release 후 latest operation과 상태 자동 갱신
7. 다크모드 alert, disabled action 대비

### 12.4 E2E Preflight

배포 전후 다음을 비교한다.

```text
Agent JAR marker and checksum on 10.10.32.1/2/3
dr-capabilities contains dr-transition-preflight-v2
direct preflight returns v2 TRANSITION_PREFLIGHT envelope
preflight before/after status checksum equal
Reprotect accepted only after preflight READY
Release leaves target VM id/state unchanged
Release removes active protection artifacts
Plan/API/cache all UNPROTECTED/DISABLED/<preserved side>
```

## 13. 빌드와 배포 순서

계약 요구 Cloud를 먼저 배포하면 구형 Agent가 모든 전환 작업을 막으므로 다음
순서를 고정한다.

1. FTCTL GitHub Actions package build/deploy
2. 10.10.32.1/2/3 Mold Agent changed JAR 동시 배포
3. Agent restart 후 v2 capability/marker 검증
4. Cloud changed Maven module build 및 변경 클래스/JAR 배포
5. UI build/static asset 배포
6. management/Agent/FTCTL 서비스 및 `/client/` 검증
7. DB repair dry-run
8. 승인된 Plan만 repair 적용
9. Reprotect -> Release E2E 재테스트

Cloud 예상 변경 모듈:

```text
core
plugins/hypervisors/kvm
plugins/integrations/disaster-recovery
ui
setup/db
```

Cloud Maven 빌드는 WSL ext4 clone에서 변경 모듈만 수행한다. FTCTL package는
GitHub Actions로 빌드한다.

## 14. 구현 우선순위

| 우선순위 | 구현 |
| --- | --- |
| P0 | Agent v2 strict envelope와 capability/provenance gate |
| P0 | Backend typed preflight, generic PLAN_AUTHORITY 응답 거부 |
| P0 | Release authority 보존 및 UNPROTECTED terminal transaction |
| P0 | runtime/cache 즉시 수렴과 stale historical projection 차단 |
| P1 | Reprotect/Release preflight API와 UI 모달 개선 |
| P1 | force 별도 복구 흐름과 acknowledgement/reason |
| P1 | 기존 released Plan repair service |
| P2 | 운영 telemetry와 mixed-version dashboard |

## 15. AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| Reprotect preflight | JSON 문자열에서 `ready` 재파싱 | Agent typed Answer와 v2 계약 검증 |
| Agent 버전 | action 시점에 확인하지 않음 | capability, wrapper version, code source gate |
| 구형 Agent 응답 | 일반 PLAN_AUTHORITY를 성공 transport로 수용 | scope/version mismatch로 action 전 차단 |
| 실패 메시지 | `OK`가 실패 원인으로 저장 가능 | typed reason 또는 contract mismatch 코드 |
| Reprotect 실패 | Plan/replica 오류로 확장 가능 | Run만 실패, TARGET service/authority 보존 |
| Release Plan | `NEW/ENABLED/SOURCE` 강제 | `UNPROTECTED/DISABLED/<기존 authority>` |
| Release VM | 의미가 UI에 불명확 | VM 무변경을 modal/audit에 명시 |
| Release runtime | 과거 worker/checkpoint 상태 잔존 | STOPPED/IDLE/UNPROTECTED 정규화 |
| Release cache | 과거 failover/reprotect가 current를 오염 | terminal commit 후 즉시 rebuild |
| Force | 일반 switch로 노출 | normal 실패 시에만 별도 복구 영역 |
| Release 후 메뉴 | 보호 작업이 혼재 | 보호 다시 구성/삭제/이력만 제공 |
| DB 보정 | 수동 상태 변경 위험 | Agent tombstone 검증 기반 idempotent repair |

## 16. 완료 기준

1. 10.10.32.1/2/3 Agent가 같은 v2 wrapper 계약을 제공한다.
2. Reprotect preflight가 일반 status 응답을 절대 수용하지 않는다.
3. 정상 TARGET authority 조건에서 Reprotect가 역방향 보호를 시작한다.
4. Reprotect 실패가 serving TARGET 상태를 손상하지 않는다.
5. Release 후 VM id/power/network/storage가 변경되지 않는다.
6. Plan, runtime, API, cache, UI가 모두 UNPROTECTED/DISABLED와 동일 authority를
   표시한다.
7. Release 후 자동 sync/RPO 보장이 재개되지 않는다.
8. 보호 다시 구성은 명시적 FULL_RESEED 작업으로만 시작된다.
9. 모든 action은 비동기 Run으로 처리되며 UI를 차단하지 않는다.
10. 기존 released Plan repair dry-run과 적용 결과가 감사 이력에 남는다.
