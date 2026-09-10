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

# 577. Cross Hypervisor DR Failover Projection Evidence and Compensation Design

> 2026-08-06 normative ACK-ordering correction:
> [599-cross-hypervisor-dr-forward-cutover-commit-contract-and-authority-convergence-design-20260806.md](599-cross-hypervisor-dr-forward-cutover-commit-contract-and-authority-convergence-design-20260806.md)
> separates target power-on from authority commit, adds durable commit recovery,
> and replaces premature Plan/Replica TARGET projection.

> 2026-07-28 후속 규약:
> [579-cross-hypervisor-dr-action-intent-guest-identity-and-failed-test-terminal-convergence-design-20260728.md](579-cross-hypervisor-dr-action-intent-guest-identity-and-failed-test-terminal-convergence-design-20260728.md)
> 는 실제 Failover와 Test Failover의 요청 의도 및 guest identity parser를
> 공통 계약으로 묶되, 이 문서의 실제 Failover 보상 트랜잭션은 그대로 유지한다.

- 작성일: 2026-07-27
- 상태: 상세 설계 완료, 구현 대기
- 적용 방향: VMware -> ABLESTACK 실제 Failover
- 적용 레이어: UI, API, DR Backend, Agent, FTCTL, Cloud DB
- FTCTL 하위 설계:
  `ablestack-qemu-exec-tools/docs/ftctl/442-ftctl-dr-failover-authority-cycle-evidence-and-abort-contract-design-20260727.md`
- 관련 설계:
  - [560-cross-hypervisor-dr-cycle-snapshot-consistency-design-20260718.md](560-cross-hypervisor-dr-cycle-snapshot-consistency-design-20260718.md)
  - [567-cross-hypervisor-dr-real-failover-cutover-manifest-and-rollback-design-20260722.md](567-cross-hypervisor-dr-real-failover-cutover-manifest-and-rollback-design-20260722.md)
  - [569-cross-hypervisor-dr-nbd-deterministic-drain-and-cycle-observability-design-20260723.md](569-cross-hypervisor-dr-nbd-deterministic-drain-and-cycle-observability-design-20260723.md)

## 1. 목적

FTCTL Failover worker가 `CUTOVER_READY`에 정상 도달했는데 completed-cycle
projection의 NBD 필드가 누락되어 Cloud가 Run을 영구 실패로 종료하는 문제를
해결한다.

이번 설계는 세 가지 경계를 분리한다.

1. FTCTL은 동일 checkpoint의 완전한 data-plane 증거를 제공한다.
2. Agent와 Backend는 증거 누락, 증거 충돌, 실제 worker 실패를 서로 다른
   오류 클래스로 처리한다.
3. Cloud는 target promotion 전 실패를 제한 재시도한 뒤 안전하게 보상
   종료하며 Run, Cutover Session, Plan, Replica, cache를 한 상태로 수렴시킨다.

UI는 Cloud API/DB 상태만 표시하고 Agent나 FTCTL을 직접 호출하지 않는다.
모든 변경 작업은 기존과 동일하게 비동기 Run으로 수행한다.

## 2. 실환경 Read-only Preflight

### 2.1 대상

| 구분 | 값 |
| --- | --- |
| Plan UUID | `2514a846-64a2-4bc7-ba88-38a874410782` |
| Cloud Plan ID | `38` |
| Failover Run | `7900237f-a5b9-4c23-b536-89b66f67a7e4` |
| Cloud Run ID | `101` |
| Cutover Session ID | `3` |
| Protection producer Run | `bb094cdb-7515-49fa-9a6b-49965ea0289d` |

### 2.2 실제 상태

| 레이어 | 확인 결과 |
| --- | --- |
| FTCTL worker | `SUCCEEDED`, `CUTOVER_READY` |
| checkpoint | sequence `501`, `TARGET_READY`, `LOCAL_DURABLE` |
| transfer | `CBT_INCREMENTAL`, `9,306,112` bytes, 94 extents |
| NBD durable evidence | `DRAINED`, quarantined count `0` |
| target VM | Cloud VM `w22-01-dr`, `POWERED_OFF` |
| active side | `SOURCE` |
| scheduler | `STOPPED_PENDING_CUTOVER` |
| `dr_plan` | `ERROR / SOURCE` |
| `dr_run` | `FAILED / runtime-projection` |
| Run error | `DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT` |
| `dr_cutover_session` | `RUNNING`, completed 없음, cleanup 없음 |

Run은 2026-07-27 16:02:10 KST에 실패로 종료됐지만 Cutover Session은 계속
`RUNNING`이다. target은 시작되지 않았고 active side는 SOURCE이므로
split-brain은 없지만, scheduler가 멈추고 lifecycle row가 열린 상태라 다음
작업을 안전하게 시작할 수 없다.

### 2.3 Status evidence simulation

현재 Agent 입력에 해당하는 projection:

```text
sequence=501
cycleToken=<plan>:501
baselineGeneration=501
effectiveMode=CBT_INCREMENTAL
incrementalVerified=true
nbdTeardownState=
```

immutable checkpoint와 cycle metrics:

```text
plan=<same plan>
producerRun=<same producer run>
sequence=501
cycleToken=<plan>:501
baselineGeneration=501
nbdTeardownState=DRAINED
nbdQuarantinedDeviceCount=0
```

동일 identity 검증 후 hydration 결과:

```text
sameCycleIdentity=true
coherentAfterExactIdentityHydration=true
runtimeStateMutated=false
```

Preflight 판정은 PASS이다. status evidence 전달 결함이며 데이터 복제 실패가
아니다.

## 3. 오류 원인

### 3.1 FTCTL operation 상태가 완료 cycle 증거를 잃음

`lib/ftctl/dr_runtime.sh`의
`ftctl_dr_runtime_capture_authority_context()`는 NBD 필드를 포함해 마지막
완료 cycle 전체를 승계할 수 있다. 그러나 background action 초기화에서
호출되는 action은 `dr-failback|dr-reprotect`뿐이고 `dr-failover`가 빠져 있다.

### 3.2 FTCTL fallback이 NBD 필드를 복원하지 않음

`ftctl_dr_runtime_emit_state_json()`의 restore-point fallback은 sequence,
mode, metrics, token, generation까지만 복원한다. NBD teardown field는
positional field 목록에 없어 incremental-verified snapshot이 불완전해진다.

### 3.3 Agent가 누락과 충돌을 같은 오류로 처리

파일:

```text
plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/
LibvirtFtctlDrStatusCommandWrapper.java
```

`isCoherentLatestCompletedCycle()`은
`incrementalVerified=true && effectiveMode=CBT_INCREMENTAL`이면
`nbdTeardownState=DRAINED`를 요구한다. 검증 자체는 맞지만 빈 필드와 서로
충돌하는 필드를 모두 `DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT`로 반환한다.

### 3.4 Backend가 boundary 오류를 즉시 terminal failure로 확정

파일:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/adapter/ftctl/
FtctlDrRuntimeProjectionAdapter.java
```

`isStatusBoundaryFailure()`에는 다음 오류만 포함된다.

```text
DR_STATUS_INVALID_JSON
DR_STATUS_JSON_INVALID
DR_STATUS_IDENTITY_MISMATCH
DR_STATUS_PAYLOAD_TOO_LARGE
DR_STATUS_TYPE_MISMATCH
```

cycle evidence 오류는 이 분기를 통과하지 못해 `isRuntimeError()`와
`failRunFromProjection()`으로 전달된다. Run은 즉시 `FAILED`,
`retryable=false`, `completed=now`가 된다.

### 3.5 Failover lifecycle이 projection adapter에 결합됨

현재 `FtctlDrRuntimeProjectionAdapter.updatePlanFromStatus()`가 다음을 모두
수행한다.

- Cutover Session 생성/갱신
- target power-on
- boot validation
- Plan/Replica authority 변경
- FTCTL cutover commit
- Run terminal projection

status transport 오류와 Cloud-owned target lifecycle이 하나의 메서드 흐름에
결합되어 있어, status validation에서 먼저 실패하면 session 보상 종료가
실행되지 않는다.

### 3.6 DB와 UI cache terminal convergence 누락

Run 실패와 함께 Session, Plan, Replica, cache를 한 transaction으로 갱신하지
않는다. 그 결과:

- Run은 `FAILED`
- Session은 `RUNNING`
- Plan은 `ERROR`
- scheduler는 `STOPPED_PENDING_CUTOVER`
- protection cache는 이전 진행 상태

가 동시에 존재한다.

## 4. 설계 원칙

1. FTCTL completed checkpoint가 data-plane authority이다.
2. Cloud Plan과 Cutover Session이 control-plane authority이다.
3. Agent는 typed validation/transport만 수행하며 lifecycle을 결정하지 않는다.
4. 증거 누락은 제한 재시도 대상이고, 증거 충돌은 hard gate이다.
5. target power-on 전 실패는 자동 보상 가능하지만 target power-on 후 실패는
   자동 rollback하지 않는다.
6. source VM은 자동 power-on하지 않는다.
7. Plan, Run, Session, Replica의 terminal 갱신은 하나의 DB transaction이다.
8. cache는 terminal transaction 직후 무효화하고 최신 DB 상태로 재생성한다.

## 5. 공통 상태 모델

### 5.1 Evidence 상태

```text
COMPLETE | INCOMPLETE | CONFLICT
```

### 5.2 Cutover Session 상태

```text
PREPARING
EVIDENCE_VERIFYING
CUTOVER_READY
CLOUD_PROMOTING
CLOUD_PROMOTED
ENGINE_ACK_VERIFYING
FAILED_OVER
ABORTING
ABORTED
ABORT_REQUIRED
FAILED
```

terminal:

```text
FAILED_OVER | ABORTED | FAILED
```

`ABORT_REQUIRED`는 operator action을 기다리는 비terminal recovery 상태이다.

### 5.3 상태 전이

정상:

```text
PREPARING
-> EVIDENCE_VERIFYING
-> CUTOVER_READY
-> CLOUD_PROMOTING
-> CLOUD_PROMOTED
-> ENGINE_ACK_VERIFYING
-> FAILED_OVER
```

target power-on 전 projection 증거 누락:

```text
EVIDENCE_VERIFYING
-> RETRYING
-> COMPLETE면 CUTOVER_READY
-> retry budget 초과 시 ABORTING
-> ABORTED
```

target power-on 이후 오류:

```text
CLOUD_PROMOTED
-> ENGINE_ACK_VERIFYING
-> ACK 성공 시 FAILED_OVER
-> timeout이면 ABORT_REQUIRED
```

target가 켜진 뒤에는 Cloud가 자동으로 source를 복원하거나 target을 끄지 않는다.

## 6. UI 설계

대상 파일:

```text
ui/src/views/infra/dr/DrPlanList.vue
ui/src/views/infra/dr/DrProtectionInfoTab.vue
ui/src/components/dr/DrStatusPill.vue
ui/src/api/dr.js
ui/public/locales/ko_KR.json
ui/public/locales/en.json
```

### 6.1 표시 모델

UI는 하나의 `state` 문자열로 모든 의미를 합치지 않는다.

```javascript
const failoverView = {
  lifecycleState: plan.cutoversessionstate,
  runState: plan.latestrunstate,
  evidenceState: plan.cutoverevidencestate,
  activeSide: plan.activeside,
  targetPowerState: plan.cutoverTargetPowerState,
  recoveryRequired: plan.cutoverrecoveryrequired,
  cacheState: protectionView.projectionstate
}
```

표시 우선순위:

1. split-brain 또는 target promoted recovery
2. `ABORT_REQUIRED`
3. Failover 준비/증거 확인
4. Plan protection health
5. cache freshness

### 6.2 사용자 메시지

| 내부 상태 | 한글 표시 |
| --- | --- |
| `EVIDENCE_VERIFYING` | `페일오버 준비 정보를 확인하는 중` |
| retry 중 | `페일오버 준비 상태를 다시 확인하는 중` |
| `ABORTING` | `페일오버 준비를 안전하게 정리하는 중` |
| `ABORTED`, source online | `페일오버 준비가 정리되어 복제를 재개했습니다` |
| `ABORTED`, source offline | `페일오버 준비가 정리되었습니다. 원본을 확인한 뒤 복제를 재개하세요` |
| `ABORT_REQUIRED` | `페일오버 복구 작업이 필요합니다` |
| `CONFLICT` | `복제 체크포인트 정보가 일치하지 않아 승격을 중단했습니다` |

`DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT` 원문과 내부 retry 횟수는 기본 화면에
노출하지 않고 이벤트 상세에만 보존한다.

### 6.3 Action gating

| 상태 | 허용 action |
| --- | --- |
| `EVIDENCE_VERIFYING`, retry | 조회만 허용 |
| `ABORTING` | 조회만 허용 |
| `ABORT_REQUIRED`, target OFF/SOURCE | `페일오버 준비 정리` |
| `ABORT_REQUIRED`, target ON 또는 TARGET authority | 운영자 복구 안내만 |
| `ABORTED`, source online | 동기화 시작/자동 scheduler |
| `ABORTED`, source offline | source 확인 후 동기화 재개 |

### 6.4 Polling과 cache

- active lifecycle은 5초 poll
- stable `READY`는 30초 poll
- poll은 화면 전체 skeleton을 다시 만들지 않음
- API 최신 Plan/Run/Session이 cache보다 우선
- cache가 stale이면 마지막 정상 snapshot과 `현재 상태 확인 중`을 함께 표시

## 7. API 설계

### 7.1 기존 비동기 API 유지

`startDrFailover`는 즉시 Run/job ID를 반환한다. status validation과 target
promotion을 API request thread에서 기다리지 않는다.

### 7.2 응답 필드

`DrPlanResponse`와 protection view snapshot에 다음 typed field를 추가한다.

```json
{
  "cutoversessionstate": "EVIDENCE_VERIFYING",
  "cutoverevidencestate": "INCOMPLETE",
  "cutovercheckpointsequence": 501,
  "cutoverrecoveryrequired": false,
  "cutoverrecoveryaction": null,
  "cutovertargetpowerstate": "POWERED_OFF",
  "cutoveractiveauthority": "SOURCE",
  "projectionretryable": true,
  "projectionretryafterseconds": 5
}
```

### 7.3 Recovery API

자동 보상이 `ABORT_REQUIRED`에 도달한 경우에만 다음 async API를 제공한다.

```text
recoverDrFailoverPreparation
  id=<plan uuid>
  cutoverSessionId=<session uuid>
```

API는 site credential이나 hypervisor 정보를 다시 받지 않는다. Plan과 DR Site에
저장된 정보를 사용한다.

응답:

```json
{
  "jobid": "...",
  "runid": "...",
  "accepted": true
}
```

## 8. Agent/Core DTO 설계

대상 파일:

```text
core/src/main/java/com/cloud/agent/api/FtctlDrCycleSnapshot.java
core/src/main/java/com/cloud/agent/api/FtctlDrStatusAnswer.java
core/src/main/java/com/cloud/agent/api/FtctlDrActionCommand.java
plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/
LibvirtFtctlDrStatusCommandWrapper.java
LibvirtFtctlDrActionCommandWrapper.java
LibvirtFtctlDrCapabilitiesCommandWrapper.java
```

### 8.1 DTO 필드

`FtctlDrCycleSnapshot`:

```java
private String evidenceState;
private String evidenceSource;
private String evidenceErrorCode;
private String evidenceErrorMessage;
```

`FtctlDrStatusAnswer`:

```java
private String validationClass;   // COMPLETE, INCOMPLETE, CONFLICT
private boolean validationRetryable;
```

### 8.2 Validator 분리

기존 boolean 메서드를 다음 형태로 변경한다.

```java
private CycleValidationResult validateLatestCompletedCycle(
        FtctlDrCycleSnapshot snapshot) {
    // COMPLETE
    // INCOMPLETE: identity는 유효하지만 필수 증거 누락
    // CONFLICT: token/generation/NBD 값 충돌
}
```

반환 규칙:

| 결과 | error code | retryable |
| --- | --- | --- |
| `COMPLETE` | 없음 | false |
| `INCOMPLETE` | `DR_STATUS_CYCLE_EVIDENCE_INCOMPLETE` | true |
| `CONFLICT` | `DR_STATUS_CYCLE_EVIDENCE_CONFLICT` | false |

기존 `DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT`는 rolling upgrade 기간에
`INCOMPLETE` 또는 `CONFLICT`로 재분류하되, field가 단순 누락인지 명시적
불일치인지 검사한다.

### 8.3 Action

`FtctlDrActionCommand.Action`에 다음을 추가한다.

```java
FAILOVER_ABORT
```

wrapper mapping:

```text
FAILOVER_ABORT -> dr-failover-abort
```

Cloud가 전달하는 session/checkpoint/generation identity를 context parameter로
전달한다.

## 9. Backend 설계

### 9.1 Cutover lifecycle 분리

신규 서비스:

```text
com.cloud.dr.DrCutoverLifecycleService
com.cloud.dr.DrCutoverLifecycleServiceImpl
```

주요 메서드:

```java
DrCutoverReconcileResult reconcile(
    DrPlanVO plan,
    DrRunVO run,
    FtctlDrStatusAnswer status);

void reconcilePendingSessions();

void requestPreparationAbort(
    long planId,
    long runId,
    long sessionId,
    String reasonCode);
```

`FtctlDrRuntimeProjectionAdapter`는 status를 typed DTO로 투영한 뒤 lifecycle
service에 위임한다. target power-on, commit, abort, terminal DB transaction은
adapter에서 제거한다.

### 9.2 Boundary classification

`isStatusBoundaryFailure()`에 다음을 추가한다.

```text
DR_STATUS_CYCLE_EVIDENCE_INCOMPLETE
DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT  # rolling compatibility
```

단, `DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT`는 payload 검사 결과 명시적 token,
generation, producer 충돌이 있으면 retry하지 않는다.

### 9.3 제한 재시도

ConfigKey:

```text
dr.failover.projection.retry.interval.seconds = 5
dr.failover.projection.retry.max = 6
dr.failover.projection.retry.window.seconds = 60
```

재시도 시:

```text
Run.state = RETRYING
Run.retryable = true
Run.retry_count += 1
Run.retry_after_seconds = 5
Run.next_retry_at = now + 5s
Run.completed = null
Session.state = EVIDENCE_VERIFYING
Plan.state = FAILOVER_PREPARING
Replica 기존 정상 projection 유지
```

last-good completed checkpoint와 protection cache는 삭제하지 않는다.

### 9.4 Hard conflict

다음은 재시도하지 않는다.

- Plan UUID mismatch
- producer Run mismatch
- sequence/token mismatch
- baseline generation mismatch
- checkpoint `DRAINED`와 metrics `QUARANTINED` 충돌
- target가 이미 ON인데 authority가 SOURCE로 남은 상태

target가 OFF이고 SOURCE authority이면 보상 종료를 시도한다. target가 ON이거나
TARGET authority이면 `ABORT_REQUIRED`로 전환하고 자동 power action을 하지
않는다.

### 9.5 보상 종료

안전 조건:

```text
activeSide == SOURCE
targetPowerState == POWERED_OFF
cloudPromotionState is null
engineAckState is null or PENDING
session/run/checkpoint identity matches
```

순서:

```text
Session ABORTING
-> Agent FAILOVER_ABORT
-> FTCTL ACK
-> terminal DB transaction
-> cache invalidate
-> source online이면 scheduler resume
-> source offline이면 PAUSED_SOURCE_OFFLINE
```

### 9.6 Pending-session reconciler

`DrProjectionScheduler`가 lifecycle service의 candidate scan을 호출한다. UI
refresh는 즉시 reconcile을 유도할 수 있지만 진행을 유지하는 유일한 주체가
아니다.

DAO:

```java
List<DrCutoverSessionVO> listReconcileCandidates(
    Date updatedBefore,
    int limit);

DrCutoverSessionVO lockRow(long id);
```

candidate:

```text
state in (
  PREPARING,
  EVIDENCE_VERIFYING,
  CUTOVER_READY,
  CLOUD_PROMOTING,
  CLOUD_PROMOTED,
  ENGINE_ACK_VERIFYING,
  ABORTING
)
and removed is null
```

## 10. DB 설계

### 10.1 Schema 변경 여부

신규 DDL은 필요하지 않다.

기존 컬럼을 사용한다.

| 테이블 | 컬럼 |
| --- | --- |
| `dr_run` | `state`, `retry_count`, `retryable`, `retry_after_seconds`, `next_retry_at`, `completed`, `error_code` |
| `dr_cutover_session` | `state`, `checkpoint_sequence`, `cloud_promotion_state`, `target_power_state`, `engine_ack_state`, `cleanup_required`, `error_code`, `details_json`, `completed_at`, `removed` |
| `dr_plan_runtime` | projection integrity 상태/코드/sequence |
| `dr_plan_view_cache` | projection state, last refresh error, generated |

`details_json`에는 retry 횟수를 넣지 않는다. Run typed column이 유일한 retry
authority이다.

### 10.2 Terminal transaction

안전한 자동 abort 성공:

```text
dr_run.state = FAILED
dr_run.completed = now
dr_run.error_code = DR_FAILOVER_PREPARATION_ABORTED
dr_run.retryable = false

dr_cutover_session.state = ABORTED
dr_cutover_session.completed_at = now
dr_cutover_session.cleanup_required = false
dr_cutover_session.error_code = original boundary error

dr_plan.state = READY or PAUSED
dr_plan.active_side = SOURCE
dr_plan.last_error_code = null or SOURCE_OFFLINE

dr_replica.state = READY
dr_replica.power_state = POWERED_OFF
dr_replica.active_side = SOURCE
```

abort 실패:

```text
dr_cutover_session.state = ABORT_REQUIRED
dr_cutover_session.cleanup_required = true
dr_run.state = FAILED
dr_plan.state = ERROR
active_side는 변경하지 않음
```

모든 row는 `SELECT ... FOR UPDATE`로 잠그고 같은 transaction에서 갱신한다.
cache row는 transaction commit 뒤 invalidation한다.

## 11. Protection View Cache 설계

`DrProtectionViewService`에 다음을 추가한다.

```java
void invalidate(long planId, String reason);
```

호출 지점:

- Run retry 진입
- Session state 변경
- target power 상태 변경
- abort terminal transaction
- successful cutover terminal transaction

cache snapshot version을 증가시키고 다음 정보를 포함한다.

```json
{
  "cutoverSession": {
    "state": "EVIDENCE_VERIFYING",
    "evidenceState": "INCOMPLETE",
    "targetPowerState": "POWERED_OFF",
    "recoveryRequired": false
  }
}
```

API의 최신 Plan/Run/Session row가 cache보다 우선하며 stale cache가 terminal
Run을 다시 진행 중으로 보이게 해서는 안 된다.

## 12. 테스트 설계

### 12.1 Agent unit test

1. 완전한 CBT/DRAINED snapshot 성공
2. NBD field 누락은 INCOMPLETE/retryable
3. token mismatch는 CONFLICT/nonretryable
4. baseline mismatch는 CONFLICT
5. DRAINED/QUARANTINED 충돌은 CONFLICT
6. FAILOVER_ABORT command mapping

### 12.2 Backend unit test

1. evidence incomplete가 Run을 FAILED로 만들지 않음
2. retry count와 next retry 갱신
3. retry 중 last-good Replica/checkpoint 유지
4. retry budget 내 complete status가 CUTOVER_READY로 진행
5. budget 초과 + target OFF + SOURCE면 abort
6. target ON이면 자동 abort 금지와 ABORT_REQUIRED
7. abort 성공 transaction이 Run/Session/Plan/Replica를 함께 갱신
8. cache invalidation
9. pending-session reconciler가 UI 없이 진행
10. 동일 abort ACK 재처리 멱등성

### 12.3 UI test

1. evidence retry 중 generic `오류` 미표시
2. 준비 확인/정리 상태 표시
3. recovery action gating
4. stale cache가 최신 terminal DB 상태를 덮지 않음
5. dark mode 상태 pill/안내문 가독성

### 12.4 실환경 재테스트 gate

1. FTCTL capability 두 개 확인
2. cycle `501`과 같은 completed snapshot에 NBD field 존재
3. 기존 orphan Session을 recovery API로 정리
4. source VM 상태를 운영자가 확인
5. source online이면 scheduler RUNNING/HEALTHY 확인
6. 새 Failover Run 생성
7. `CUTOVER_READY` 이전 target OFF 확인
8. Cloud target power-on 및 boot validation
9. FTCTL cutover commit ACK
10. Plan/Run/Session/Replica/cache terminal 일치

## 13. 구현 및 배포 순서

1. FTCTL canonical snapshot/authority 승계
2. FTCTL failover abort/capability/self-test
3. GitHub Actions RPM build
4. FTCTL을 coordinator host에 선배포
5. Agent DTO와 wrapper
6. Backend cutover lifecycle/retry/transaction
7. API response/recovery command
8. UI 표시/action/cache 처리
9. 변경 Maven module build
10. UI build
11. Cloud 변경 class/JAR와 UI 정적 자산 배포
12. service와 active webapp marker 확인
13. orphan Run 101/Session 3 정리
14. 재테스트

Cloud가 새 capability를 확인하기 전에는 새 Failover를 허용하지 않는다. 이렇게
하면 Cloud가 먼저 배포되어 구버전 FTCTL과 잘못 조합되는 것을 막을 수 있다.

## 14. 현재 실패 세션 정리 원칙

현재 Session 3은 DB 값을 직접 수정해 `COMPLETED`로 만들지 않는다.

패치 배포 후:

1. FTCTL 실제 state와 target power를 다시 조회
2. `recoverDrFailoverPreparation` 실행
3. FTCTL `dr-failover-abort` ACK 확인
4. Cloud terminal transaction 확인
5. source가 OFF이면 자동 power-on하지 않고 Plan을 `PAUSED_SOURCE_OFFLINE`으로
   표시
6. 운영자가 source를 정상화한 뒤 sync resume

## 15. AS-IS / TO-BE

| 레이어 | AS-IS | TO-BE |
| --- | --- | --- |
| UI | generic 성능 저하/오류, stale cache | 준비 확인/정리/복구 필요를 분리 표시 |
| API | cutover evidence와 recovery 정보 부족 | typed evidence/session/recovery field |
| Backend | projection 오류를 즉시 Run 실패 | 제한 재시도 후 안전 조건별 보상 종료 |
| Backend 구조 | adapter가 target lifecycle까지 소유 | 전용 `DrCutoverLifecycleService`가 소유 |
| Agent | 누락과 충돌을 동일 incoherent로 처리 | INCOMPLETE와 CONFLICT 분리 |
| FTCTL | Failover가 완료 cycle 증거를 잃음 | authority 승계와 exact checkpoint hydration |
| FTCTL 복구 | generic cancel만 존재 | 멱등 `dr-failover-abort` |
| DB | Run FAILED, Session RUNNING 불일치 | 하나의 terminal transaction으로 수렴 |
| Cache | 이전 진행 상태가 남을 수 있음 | lifecycle 변경마다 invalidate/rebuild |
| Source 전원 | 보상 동작 불명확 | 자동 power-on 금지, offline은 명시적 pause |

## 16. Cloud target power authority guard

FTCTL의 `target_power_state`는 엔진이 관찰한 cutover 상태이며 Cloud가
관리하는 대상 VM의 실제 전원 상태를 대체하지 않는다. Failover 준비 보상은
다음 두 시점에 `UserVmDao`로 모든 활성 replica의 대상 VM을 확인한다.

1. `FAILOVER_ABORT`를 Agent로 보내기 직전
2. FTCTL abort ACK를 받은 직후, source authority를 복원하기 직전

대상 VM이 `Stopped`가 아니면 자동 보상을 중단하고 cutover session을
`ABORT_FAILED`, `cleanup_required=1`로 유지한다. 오류 코드는 최초 검사에서
`DR_FAILOVER_ABORT_UNSAFE`, ACK 이후 상태 변화에는
`DR_FAILOVER_ABORT_CLOUD_STATE_CHANGED`를 사용한다.

이 검사는 Cloud가 VM lifecycle authority라는 원칙을 지킨다. Backend는
불확실한 대상 VM을 직접 강제 종료하지 않으며, 운영자 또는 명시적인 Cloud
lifecycle 작업이 대상 VM을 정지한 뒤 보상을 재시도한다.

## 17. Failover History Ownership After Failback - 2026-07-28

성공한 Failover session은 target authority가 유지되는 동안에만 current
cutover이다. 후속 Failback이 완료되면 session row를 삭제하지 않고
`FAILED_BACK`으로 종결해 감사 이력으로 보존한다.

Plan response와 보호 정보 화면은 `active_side=TARGET`이고 acknowledged
authority-bearing session이 있을 때만 현재 페일오버 권한을 표시한다.
과거 `PROMOTED` 값은 SOURCE 권한이나 action eligibility를 변경하지 않는다.
상세 상태 모델은 문서 578을 따른다.
