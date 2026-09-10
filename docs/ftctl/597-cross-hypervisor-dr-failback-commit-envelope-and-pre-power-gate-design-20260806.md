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

# 597. Cross-Hypervisor DR Failback Commit Envelope And Pre-Power Gate Design

> 2026-08-06 normative follow-up: the commit handoff carries the original
> forward target-map generation/hash. Cloud marks protection ready only after
> a durable completed `VMWARE_TO_KVM` checkpoint reports the same identity.
> Document 598 defines the Cloud contract and FTCTL document 454 defines the
> canonical target locator reuse.

## Implementation And Compatibility Result

- Cloud persists `DR_FAILBACK_COMMIT_V1`, the commit attempt ID, the canonical envelope SHA-256,
  and dispatch/probe timestamps before a commit can be verified.
- The Agent command uses typed fields for checkpoint, baseline, authority generation, evidence run,
  power states, and boot validation. A partial tuple is rejected before the host script runs.
- `COMMIT_VERIFYING` sessions created by an older deployment and missing a durable dispatch envelope
  are not probed forever. Cloud records `FAILBACK_COMMIT_NOT_DISPATCHED`, powers the source off,
  restores the target serving VM, and preserves TARGET authority.
- The protection UI continues to show the canonical failback phase and authority projection. Internal
  commit attempt IDs and hashes remain operator evidence and are not exposed as normal user inputs.
- Changed-module Maven build and focused KVM/DR lifecycle tests are mandatory before deployment.

## 1. 목적

이 문서는 VMware에서 ABLESTACK으로 Failover 된 VM을 VMware로 Failback하는
과정에서 reverse data 전송은 완료되었지만 최종 FTCTL authority commit이
필수 식별자 누락으로 거부된 문제를 해결하기 위한 상세 코드 수준 설계이다.

적용 범위는 다음과 같다.

- UI: Failback 진행 상태와 최종 authority 일관성 표시
- API: 비동기 action 접수와 canonical lifecycle 상태 응답
- Cloud backend: commit envelope 구성, 전원 전환 전 gate, terminal reconcile
- Agent: typed command 전달과 deterministic/ambiguous 오류 분류
- FTCTL: durable commit journal, idempotent commit/status
- DB: commit envelope 및 dispatch 상태 영속화

이 문서는 다음 문서의 후속 보강 문서이다.

- `574-cross-hypervisor-dr-cloud-owned-failback-lifecycle-commit-design-20260726.md`
- `575-cross-hypervisor-dr-failback-commit-convergence-and-rollback-fencing-design-20260727.md`
- `576-cross-hypervisor-dr-failback-late-ack-and-projection-convergence-design-20260727.md`
- `588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md`
- `596-cross-hypervisor-dr-failback-durable-evidence-publication-contract-design-20260806.md`
- qemu document `452-ftctl-dr-failback-route-envelope-and-cloud-lifecycle-boundary-design-20260805.md`

## 2. 실환경 Preflight와 오류 원인

### 2.1 검증 대상

| 항목 | 값 |
|---|---|
| Plan UUID | `7889e625-371a-48f9-b553-54e311481170` |
| Failback Run ID / UUID | `141` / `202be3ef-3960-49b7-8634-919678f6a750` |
| Failback Session ID | `14` |
| 이전 committed cutover session | ID `11`, generation `10` |
| Reverse baseline/checkpoint generation | `16` |

### 2.2 레이어별 확인 결과

| 레이어 | 확인 결과 |
|---|---|
| FTCTL | reverse transfer `COMPLETE`, 약 833 MiB 전송, target write/verify 완료 |
| FTCTL authority | 아직 `TARGET`, commit journal 없음 |
| KVM target VM | `Stopped` |
| VMware source VM | `poweredOn`, VMware Tools running |
| Cloud Plan | `COMMIT_VERIFYING`, `active_side=SOURCE` |
| Cloud Run | `RUNNING`, stale step `runtime-transfer` |
| Failback Session | `checkpoint_sequence=NULL`, `authority_generation=NULL` |
| Commit command | exit 2, `DR_FAILBACK_COMMIT_INVALID` |
| Commit status | exit 44, `DR_FAILBACK_COMMIT_NOT_FOUND` |

데이터 전송과 실제 VM 전원 전환은 진행되었으나 FTCTL authority commit은
실행되지 않았다. 따라서 이 상태는 Failback 성공이 아니라 물리적 serving side와
engine authority가 갈라진 `AUTHORITY_COMMIT_REQUIRED` 상태이다.

### 2.3 직접 원인

`DrFailbackLifecycleServiceImpl.reconcile()`은 새 session 생성 시에만
`failback_restore_point_sequence`와 authority generation을 설정한다.
session이 reverse-final evidence보다 먼저 생성되면 두 값이 null로 남는다.
기존 session reconcile 경로는 runtime/data evidence만 갱신하고 이 두 값을
backfill하지 않는다.

또한 현재 `authorityGeneration(session, run)`은 checkpoint sequence가 있으면
그 값을, 없으면 Cloud `dr_run.id`를 반환한다. 하지만 authority generation은
직전 committed cutover의 `dr_cutover_session.cloud_authority_generation`이다.
reverse checkpoint sequence와 Cloud DB run ID는 대체 값이 될 수 없다.

### 2.4 구조적 원인

1. commit 필수 필드가 `FtctlDrActionCommand.context` 문자열 Map에만 존재한다.
2. commit envelope completeness를 전원 전환 전에 검증하지 않는다.
3. target stop/source start 뒤에 commit attempt ID와 commit context를 만든다.
4. Agent wrapper가 deterministic validation failure도 status probe로 바꾼다.
5. commit dispatch 사실과 단순한 status probe를 구분하는 DB 상태가 없다.
6. Cloud projection이 FTCTL authority 확인 전 `active_side=SOURCE`를 노출한다.

## 3. 설계 불변 조건

1. `checkpointSequence`와 `authorityGeneration`은 서로 다른 소유권과 의미를 가진다.
2. Cloud는 완전한 commit envelope를 DB에 저장하기 전 VM 전원을 변경하지 않는다.
3. reverse evidence의 Run UUID는 Failback Run UUID와 같아야 한다.
4. authority generation은 현재 active committed cutover session에서만 읽는다.
5. Agent는 commit 필드를 추론하거나 기본값으로 채우지 않는다.
6. FTCTL은 journal을 내구성 있게 기록한 뒤 authority 상태를 변경한다.
7. 동일 envelope 재전송은 성공한 동일 commit으로 수렴해야 한다.
8. 다른 envelope를 같은 attempt ID로 전송하면 conflict로 거부해야 한다.
9. deterministic reject는 `UNKNOWN`으로 승격하지 않는다.
10. UI는 physical serving side와 engine authority가 다르면 Ready로 표시하지 않는다.

## 4. Canonical `FailbackCommitEnvelopeV1`

```json
{
  "contractVersion": "DR_FAILBACK_COMMIT_V1",
  "planUuid": "7889e625-371a-48f9-b553-54e311481170",
  "runUuid": "202be3ef-3960-49b7-8634-919678f6a750",
  "failbackSessionId": "7889e625-371a-48f9-b553-54e311481170:202be3ef-3960-49b7-8634-919678f6a750",
  "checkpointSequence": 16,
  "authorityGeneration": 10,
  "baselineGeneration": 16,
  "evidenceRunUuid": "202be3ef-3960-49b7-8634-919678f6a750",
  "targetPowerState": "POWERED_OFF",
  "sourcePowerState": "POWERED_ON",
  "bootValidationState": "GUEST_HEARTBEAT_VALIDATED",
  "commitAttemptId": "<uuid>",
  "generatedAt": "<ISO-8601>",
  "sha256": "<canonical-json-sha256>"
}
```

### 4.1 필드 소유권

| 필드 | 소유자 | 원천 |
|---|---|---|
| plan/run/session ID | Cloud | `dr_plan`, `dr_run`, `dr_failback_session` |
| checkpoint/baseline | FTCTL | reverse-final durable evidence |
| authority generation | Cloud | active committed `dr_cutover_session` |
| target/source power | Cloud | Host Agent 및 vCenter 검증 결과 |
| boot validation | Cloud | 계획 정책에 따른 VMware 전원 또는 guest heartbeat 검증 |
| attempt ID | Cloud | DB에 먼저 생성하는 UUID |
| journal/outcome | FTCTL | durable failback commit journal |

### 4.2 Canonical JSON 규칙

- UTF-8, key lexicographic order, 공백 없는 JSON을 사용한다.
- 숫자는 JSON number로 직렬화하고 문자열 `"null"`을 허용하지 않는다.
- hash 대상에서 `sha256` 필드는 제외한다.
- Cloud와 FTCTL self-test가 동일 fixture hash를 검증한다.

## 5. Cloud Backend 상세 설계

### 5.1 신규 value object

`plugins/integrations/disaster-recovery/.../DrFailbackCommitEnvelope.java`

```java
public final class DrFailbackCommitEnvelope {
    public static final String VERSION = "DR_FAILBACK_COMMIT_V1";
    private final String planUuid;
    private final String runUuid;
    private final String failbackSessionId;
    private final long checkpointSequence;
    private final long authorityGeneration;
    private final long baselineGeneration;
    private final String evidenceRunUuid;
    private final String targetPowerState;
    private final String sourcePowerState;
    private final String bootValidationState;
    private final String commitAttemptId;
    // canonicalJson(), sha256(), validate()
}
```

constructor는 null을 허용하지 않는다. `validate()`는 다음 오류를 구분한다.

- `DR_FAILBACK_COMMIT_SESSION_MISSING`
- `DR_FAILBACK_COMMIT_CHECKPOINT_MISSING`
- `DR_FAILBACK_AUTHORITY_GENERATION_MISSING`
- `DR_FAILBACK_EVIDENCE_RUN_MISMATCH`
- `DR_FAILBACK_COMMIT_POWER_STATE_INVALID`
- `DR_FAILBACK_BOOT_VALIDATION_INCOMPLETE`

### 5.1.1 페일백 부팅 검증 정책 정합성

Cloud는 원본 VM이 `POWERED_ON`에 도달한 뒤 계획 정책에 따라 부팅 검증 강도를
선택한다.

- `failbackBootValidationMode=POWER_STATE_ONLY`: vCenter 전원 상태 확인 결과를
  `POWER_STATE_VALIDATED`로 기록하고 guest identity API를 호출하지 않는다.
- `failbackBootValidationMode`가 없고 기존 계획의
  `testBootValidationMode=POWER_STATE_ONLY`인 경우에도 같은 동작을 적용한다.
- 그 외 값과 정책 누락은 기존 안전 기본값인 `GUEST_HEARTBEAT_REQUIRED`로
  처리하며 VMware Tools guest identity가 확인되어야 한다.

이 분기는 역방향 데이터 전송, target stop, source start 순서를 변경하지 않는다.
특히 HTTP 503을 일반 성공으로 간주하지 않고, 운영자가 명시적으로 선택한
`POWER_STATE_ONLY` 정책에서만 guest heartbeat 검증을 생략한다.

| 구분 | AS-IS | TO-BE |
|---|---|---|
| 정책 적용 | 계획의 `POWER_STATE_ONLY`를 페일백이 무시 | 페일백도 저장된 검증 강도를 적용 |
| VMware Tools 미가용 | guest identity HTTP 503으로 정상 전원 기동까지 롤백 | 명시적 power-only 계획은 전원 상태로 검증 |
| 기본 안전성 | 항상 guest identity 강제 | 정책 누락 및 강화 모드는 기존 guest heartbeat 유지 |

### 5.2 Session reconcile backfill

`DrFailbackLifecycleServiceImpl.reconcile()`의 신규/기존 session 분기 뒤에 공통 함수
`refreshCommitPrerequisites(plan, run, session, runtime)`를 호출한다.

```java
private void refreshCommitPrerequisites(DrPlanVO plan, DrRunVO run,
        DrFailbackSessionVO session, JsonObject runtime) {
    Long publishedCheckpoint = longValue(runtime, "failback_restore_point_sequence");
    if (session.getCheckpointSequence() == null && publishedCheckpoint != null) {
        session.setCheckpointSequence(publishedCheckpoint);
    } else if (publishedCheckpoint != null
            && !publishedCheckpoint.equals(session.getCheckpointSequence())) {
        throw contractConflict("checkpointSequence");
    }

    DrCutoverSessionVO cutover = requireCommittedTargetAuthority(plan.getId());
    Long generation = cutover.getCloudAuthorityGeneration();
    if (session.getAuthorityGeneration() == null) {
        session.setAuthorityGeneration(generation);
    } else if (!generation.equals(session.getAuthorityGeneration())) {
        throw contractConflict("authorityGeneration");
    }
}
```

`authorityGeneration(session, run)` fallback 함수는 삭제한다. run ID와 checkpoint를
authority generation으로 사용하는 코드는 금지한다.

### 5.3 Current authority DAO

`DrCutoverSessionDao`에 다음 조회를 추가한다.

```java
DrCutoverSessionVO findCommittedTargetAuthorityByPlanId(long planId);
```

유효 조건:

- `plan_id` 일치
- `removed IS NULL`
- `authority_ended_at IS NULL`
- state가 `FAILED_OVER` 또는 동등한 committed target-authority 상태
- `engine_ack_state=ACKNOWLEDGED`
- `cloud_authority_generation IS NOT NULL`

중복 row가 있으면 최신 값을 임의 선택하지 않고
`DR_FAILBACK_MULTIPLE_ACTIVE_AUTHORITIES`로 차단한다.

### 5.4 전원 전환 전 gate

`executeLifecycle()` 순서를 다음과 같이 바꾼다.

```text
durable data gate
  -> source isolation gate
  -> resolve/backfill commit prerequisites
  -> build FailbackCommitEnvelopeV1 PREPARED form
  -> validate envelope completeness
  -> persist envelope + hash + attempt id atomically
  -> target stop
  -> source start
  -> guest heartbeat validation
  -> finalize power/boot fields and re-hash atomically
  -> dispatch FAILBACK_COMMIT
```

전원 전환 전에는 power/boot 필드를 기대 상태로 기록한 PREPARED envelope를
검증한다. 실제 전원 전환 후 관측값으로 final envelope를 다시 확정한다. 두 hash를
구분하여 저장하거나 final hash만 commit에 사용한다.

필수 값이 없으면 `failBeforeAuthorityTransition()`으로 종료한다. 이 경로에서는
KVM target을 정지하거나 VMware source를 시작하지 않는다.

### 5.5 Commit dispatch 상태 기계

```text
NOT_PREPARED
  -> PREPARED
  -> DISPATCHING
  -> DISPATCHED
  -> ACKNOWLEDGED

DISPATCHING/DISPATCHED
  -> OUTCOME_UNKNOWN
  -> ACKNOWLEDGED | REJECTED | DEADLINE_EXCEEDED
```

- Agent 호출 직전 CAS로 `DISPATCHING`을 기록한다.
- Agent가 command acceptance를 반환하면 `DISPATCHED`를 기록한다.
- transport timeout일 때만 `OUTCOME_UNKNOWN`으로 전환한다.
- validation reject는 `REJECTED`이며 status probe 대상이 아니다.
- `NOT_FOUND`는 `DISPATCHED` 증거가 없으면 `COMMIT_NOT_SUBMITTED`이다.

### 5.6 Terminal reconcile

`verifyCommitOutcome()`는 다음 tuple로 status를 조회한다.

```text
planUuid + runUuid + failbackSessionId + commitAttemptId + envelopeSha256
```

`COMMIT_VERIFYING`은 무기한 상태가 아니다.

- 최초 5초 간격 6회
- 이후 15초 간격
- 총 5분 deadline
- deadline 뒤 `DR_FAILBACK_COMMIT_VERIFY_DEADLINE_EXCEEDED`

다만 실제 source ON/target OFF이고 reverse evidence가 complete인 경우 자동 rollback은
하지 않는다. 운영 중인 source를 다시 끄기 전에 별도 compensation 판정이 필요하다.

## 6. Typed Agent Command 설계

`core/.../FtctlDrActionCommand.java`에 generic context와 별도로 다음 필드를 추가한다.

```java
private String failbackCommitContractVersion;
private String failbackSessionId;
private Long failbackCheckpointSequence;
private Long failbackAuthorityGeneration;
private Long failbackBaselineGeneration;
private String failbackEvidenceRunUuid;
private String failbackCommitAttemptId;
private String failbackCommitEnvelopeSha256;
private String failbackTargetPowerState;
private String failbackSourcePowerState;
private String failbackBootValidationState;
```

`FAILBACK_COMMIT`과 `FAILBACK_COMMIT_STATUS`는 위 typed 필드만 사용한다.
기존 context Map은 이전 action 호환용으로 남기되 commit 필드 fallback에 사용하지 않는다.

`ACTION_CONTRACT_VERSION`을 올리고 rolling upgrade 중 구버전 Agent가 command를 받을
경우 `DR_AGENT_FAILBACK_COMMIT_CONTRACT_UNSUPPORTED`로 전원 전환 전에 차단한다.

## 7. Agent Wrapper 상세 설계

`LibvirtFtctlDrActionCommandWrapper`에 다음을 추가한다.

```java
private void validateFailbackCommitCommand(FtctlDrActionCommand command);
private CommitFailureClass classifyCommitFailure(int exitCode, JsonObject payload,
        String result, String output);
```

### 7.1 CLI 매핑

```text
--contract-version
--session-id
--checkpoint-sequence
--authority-generation
--baseline-generation
--evidence-run-uuid
--commit-attempt-id
--envelope-sha256
--target-power-state
--source-power-state
--boot-validation-state
```

### 7.2 오류 분류

| 종류 | 예 | 처리 |
|---|---|---|
| DETERMINISTIC | exit 2, `*_INVALID`, missing argument | 즉시 reject, status probe 금지 |
| CONFLICT | journal hash/generation mismatch | 즉시 reject, 수동 조사 필요 |
| AMBIGUOUS | timeout, stream closed, transport reset | commit-status probe |
| ACKNOWLEDGED | exit 0 + matching journal | 성공 |

현재처럼 모든 nonzero `FAILBACK_COMMIT`에서 status를 조회하는 코드는 제거하고,
`classifyCommitFailure()==AMBIGUOUS`일 때만 probe한다.

## 8. FTCTL 상세 설계

FTCTL 구현 상세는 qemu 문서 453을 따른다. Cloud 관점의 요구사항은 다음과 같다.

1. `dr-failback-commit`은 모든 typed argument를 검증한다.
2. journal은 authority 변경보다 먼저 temp+fsync+rename으로 기록한다.
3. idempotency key는 `plan/run/session/attempt/hash`이다.
4. 같은 key 재호출은 기존 ACK를 반환한다.
5. 같은 attempt ID와 다른 hash는 `DR_FAILBACK_COMMIT_CONFLICT`이다.
6. status는 `NOT_SUBMITTED`, `PREPARED`, `ACKNOWLEDGED`, `REJECTED`를 구분한다.
7. journal이 없다는 사실을 단순 `NOT_FOUND`로만 표현하지 않는다.

## 9. DB 상세 설계

기존 `checkpoint_sequence`, `authority_generation`, `commit_attempt_id`를 재사용하고
다음 열을 추가한다.

```sql
ALTER TABLE cloud.dr_failback_session
  ADD COLUMN commit_contract_version varchar(32) NULL,
  ADD COLUMN commit_envelope_sha256 varchar(64) NULL,
  ADD COLUMN commit_dispatch_state varchar(32) NULL,
  ADD COLUMN commit_dispatched_at datetime NULL,
  ADD COLUMN commit_probe_count int unsigned NOT NULL DEFAULT 0,
  ADD COLUMN commit_probe_deadline_at datetime NULL;
```

Cloud schema 규칙에 따라 versioned migration에는 plain `ADD COLUMN`, 최종
`schema-Europa-After.sql`에는 idempotent helper를 사용한다.

application-level CAS 조건은 다음과 같다.

```text
WHERE id=? AND lifecycle_version=? AND removed IS NULL
```

`checkpoint_sequence`와 `authority_generation`은 commit PREPARED 이후 null일 수 없다.
MySQL CHECK에만 의존하지 않고 service validation과 focused migration audit query를
같이 제공한다.

## 10. API 설계

`DrPlanResponse`와 protection view에 다음 필드를 추가한다.

```json
{
  "failbackLifecycleState": "AUTHORITY_COMMIT_REQUIRED",
  "commitDispatchState": "NOT_SUBMITTED",
  "physicalServingSide": "SOURCE",
  "engineAuthoritySide": "TARGET",
  "authorityConsistent": false,
  "operatorSummaryCode": "DR_FAILBACK_FINAL_COMMIT_REQUIRED"
}
```

API action은 계속 비동기이다. submit 응답은 job/run UUID만 반환하고 VM power/engine
작업 완료를 기다리지 않는다. 상세 조회와 protection cache가 lifecycle reconciler의
상태를 읽는다.

`activeSide`는 committed authority만 의미한다. 물리적으로 source가 켜졌다는 이유로
commit ACK 전에 `SOURCE`로 확정하지 않는다.

## 11. UI 설계

### 11.1 표시 상태

현재와 같은 분리 상태는 일반 `오류`나 `READY`가 아니라 다음으로 표시한다.

- 상태: `페일백 최종 확인 필요`
- 설명: `원본 VM은 실행 중이지만 복제 엔진 권한 전환이 완료되지 않았습니다.`
- severity: warning
- action: 일반 작업 버튼 비활성, 관리자 복구 작업만 backend eligibility가 허용

raw session ID, generation, journal 경로는 기본 화면에 표시하지 않는다. 실행 이력의
진단 상세에서만 support code와 correlation ID를 제공한다.

### 11.2 자동 갱신

- active operation 동안 5초 poll
- terminal/steady state에서 30초 poll
- 기존 화면을 비우지 않고 protection cache를 원자 교체
- `authorityConsistent=false`이면 녹색 Ready badge 금지

## 12. 현재 실패 Session 복구 설계

현재 Run 141은 데이터 전송을 재실행하면 안 된다. 다음 조건을 모두 다시 검증한 뒤
forward commit으로 수렴시킨다.

1. FTCTL reverse evidence state `COMPLETE`
2. evidence Run UUID가 Run 141 UUID와 일치
3. checkpoint/baseline generation `16`
4. active cutover authority generation `10`
5. KVM target `POWERED_OFF`
6. VMware source `POWERED_ON`
7. guest heartbeat validated
8. FTCTL commit journal이 아직 없음

복구 service는 DB 값을 임의 update하지 않는다.

```text
re-read DB + FTCTL + both VM power states
  -> reconstruct envelope(checkpoint=16, authority=10)
  -> persist PREPARED + attempt ID + hash
  -> dispatch idempotent FAILBACK_COMMIT only
  -> verify journal ACK
  -> terminal transaction
  -> resume original-direction scheduler
  -> require first post-failback checkpoint > 16
```

어느 조건이든 불일치하면 자동 복구를 중단한다. 특히 target이 다시 켜졌거나 source가
꺼졌다면 power transition을 재실행하지 않는다.

terminal transaction은 Plan, Run, failback session, cutover session, Replica,
protection cache를 한 transaction으로 다음 상태에 수렴시킨다.

| 객체 | 최종 상태 |
|---|---|
| Plan | `READY / SOURCE` |
| Run 141 | `SUCCEEDED / completed` |
| Failback Session | `COMPLETED / ACKNOWLEDGED` |
| Cutover Session | `FAILED_BACK`, authority ended |
| KVM target | `Stopped` |
| VMware source | `poweredOn` |
| FTCTL scheduler | `RUNNING / HEALTHY` |
| checkpoint | first resumed sequence `> 16` |

## 13. 테스트 설계

### 13.1 Cloud unit test

- session 생성 후 evidence가 늦게 도착하면 checkpoint가 backfill된다.
- authority generation은 active cutover generation만 사용한다.
- active cutover가 없거나 둘 이상이면 전원 작업 전에 실패한다.
- missing checkpoint/authority이면 `ensureTargetPowerState()`가 호출되지 않는다.
- attempt ID와 envelope hash가 DB에 먼저 저장된다.
- deterministic INVALID는 `COMMIT_VERIFYING`으로 바뀌지 않는다.
- timeout만 `OUTCOME_UNKNOWN`과 status probe로 전환한다.
- terminal transaction이 모든 projection을 원자적으로 수렴시킨다.

### 13.2 Agent test

- typed 필드가 정확한 CLI option으로 매핑된다.
- null typed 필드는 script 실행 전에 거부된다.
- exit 2 invalid에서 status command가 호출되지 않는다.
- timeout/stream closed에서만 status가 호출된다.
- status가 다른 attempt/hash를 반환하면 ACK로 인정하지 않는다.

### 13.3 FTCTL self-test

- journal write-before-authority ordering
- crash after journal prepare
- crash after authority update before response
- duplicate same envelope idempotency
- duplicate attempt with different hash conflict
- status `NOT_SUBMITTED`/`ACKNOWLEDGED` distinction

### 13.4 Live acceptance

1. 현재 session은 reverse transfer 재실행 없이 forward commit으로 복구한다.
2. UI에서 authority warning이 사라지고 `READY/SOURCE`가 된다.
3. Run 141이 terminal success가 된다.
4. FTCTL journal의 checkpoint 16, authority 10, attempt/hash가 DB와 일치한다.
5. scheduler가 RUNNING이고 첫 post-failback checkpoint가 17 이상이다.
6. 이후 증분 cycle이 Full seed가 아닌 정상 incremental로 동작한다.

## 14. 권장 구현 우선순위

1. Cloud pre-power envelope gate와 authority resolver
2. typed Agent command와 deterministic error classification
3. FTCTL journal/idempotency/status contract
4. DB migration과 VO/DAO CAS
5. lifecycle terminal reconciler와 bounded deadline
6. API projection 및 UI authority warning
7. 현재 Run 141 forward-recovery dry-run
8. changed-module build, FTCTL GitHub Actions build, 배포
9. 현재 Run recovery 및 post-failback incremental 검증

Cloud와 FTCTL 계약이 동시에 배포되기 전에는 현재 session 복구 명령을 실행하지 않는다.

## 15. AS-IS / TO-BE 요약

| 영역 | AS-IS | TO-BE |
|---|---|---|
| Session reconcile | 초기 생성 시 null이면 계속 null | terminal evidence 도착 시 conflict-safe backfill |
| Authority generation | checkpoint 또는 run ID로 대체 | active committed cutover generation만 사용 |
| 전원 순서 | target stop/source start 뒤 commit 필드 구성 | 완전한 envelope 영속 후에만 전원 전환 |
| Command 계약 | 문자열 context Map | versioned typed fields |
| Agent 오류 | 모든 commit 실패 후 status probe | ambiguous transport failure만 probe |
| FTCTL journal | 잘못된 tuple이면 journal 없음/NOT_FOUND | attempt/hash 기반 durable idempotent journal |
| DB | dispatch 여부와 probe를 구분 못함 | PREPARED/DISPATCHED/UNKNOWN/ACK 상태 영속 |
| Projection | source ON이면 SOURCE로 조기 표시 | physical side와 engine authority를 분리 |
| UI | Ready 또는 일반 오류로 오인 | `페일백 최종 확인 필요` 경고 |
| Recovery | 전체 Failback 재실행 위험 | data 재전송 없이 검증된 forward commit |

## 16. 완료 기준

- commit 필수 tuple이 null인 상태에서 VM 전원 API가 호출되지 않는다.
- `checkpointSequence != authorityGeneration`인 정상 케이스가 지원된다.
- deterministic invalid가 무한 `COMMIT_VERIFYING`으로 남지 않는다.
- Cloud DB envelope hash와 FTCTL journal hash가 일치한다.
- physical serving side와 engine authority가 일치하기 전 Ready가 노출되지 않는다.
- 현재 Run 141이 data retransmission 없이 terminal success로 수렴한다.
- first post-failback checkpoint가 reverse baseline보다 증가한다.

## 17. Windows 페일백 정상 부팅 검증 보강 (2026-08-22)

### 17.1 원인과 원칙

계획 생성 UI의 `testBootValidationMode=POWER_STATE_ONLY`는 격리된 테스트
페일오버 옵션이며 Windows 페일백의 source authority 확정 기준으로 재사용하지
않는다. VMware 원본 `guestId`가 Windows인 계획은 Cloud가 자동으로
`failbackBootValidationMode=GUEST_HEARTBEAT_REQUIRED`를 생성하고, 기존 계획도
mapping의 guestId를 읽어 같은 규칙을 적용한다.

### 17.2 Cloud 처리

1. 계획 생성 시 source hardware의 `guestId`를 판독한다.
2. Windows이면 failback policy를 `GUEST_HEARTBEAT_REQUIRED`로 고정한다.
3. source VM을 시작한 뒤 vCenter guest identity가 실제로 조회될 때까지 bounded
   poll한다.
4. 검증 성공 시에만 `GUEST_HEARTBEAT_VALIDATED`를 commit envelope에 넣는다.
5. 검증 실패 시 target authority를 유지하고 기존 rollback 경로를 수행한다.

기존 Linux 및 명시적 비 Windows power-only 경로는 유지하여 검증된 동작을
회귀시키지 않는다. UI에는 내부 정책 선택을 추가하지 않고 시스템이 guest OS에
맞는 안전한 기준을 적용한다.

### 17.3 FTCTL 상호 검증

FTCTL은 reverse profile의 original VMware guestId를 독립적으로 확인한다.
Windows인데 Cloud가 power-only 증거를 보내면 commit 전에 거부한다. 따라서 Cloud
버전 혼재 또는 오래된 계획 정책이 있어도 Windows source authority가 전원 상태만으로
확정되지 않는다.

### 17.4 테스트

- Windows mapping은 기존 POWER_STATE_ONLY 계획에서도 heartbeat-required로 해석
- 새 Windows 계획 policy에 failback 전용 안전 기본값 저장
- Linux 명시적 power-only 호환성 유지
- vCenter guest identity 성공 전 commit 미호출
- FTCTL Windows power-only envelope 거부 및 heartbeat envelope 승인
- 기존 sync, test failover/cleanup, failover, failback, release 계약 회귀 실행

### 17.5 AS-IS / TO-BE

| 영역 | AS-IS | TO-BE |
| --- | --- | --- |
| 정책 | 테스트 부팅 정책을 페일백에 재사용 | Windows failback 전용 heartbeat 정책 |
| Cloud 관측 | poweredOn만으로 성공 가능 | vCenter guest identity 확인 필수 |
| Engine commit | power-only envelope 수용 | Windows power-only envelope 거부 |
| UI | 사용자가 내부 검증 방식을 결정 | OS 기반 안전 정책을 자동 적용 |
| 전송/기준선 | 검증된 기존 경로 | 변경 없음 |

## 18. 호환성 사전 조건과 실제 부팅 증거 분리 (2026-08-22)

최종 UI 회귀에서 역방향 전송과 기준선은 정상 완료됐지만 FTCTL이 검증된
`VMWARE -> ABLESTACK -> VMWARE` 계보까지 `VALIDATION_REQUIRED`로 초기화하여
Cloud 데이터 게이트가 source 전원 기동 전에 차단했다. 이는 Windows heartbeat
강화와 무관한 기존 성공 경로 회귀다.

- 동일한 VMware 원본 VM 및 디스크 계보로 되돌리는 경로는
  `ORIGINAL_VMWARE_COMPATIBILITY_PRESERVED`를 유지한다.
- 호환성 보존은 전송/컨트롤러 사전 조건일 뿐 정상 부팅 성공 증거가 아니다.
- Windows source authority commit은 보존 상태와 별개로 반드시
  `GUEST_HEARTBEAT_VALIDATED`를 요구한다.
- 기타 provider 조합은 `VALIDATION_REQUIRED`를 유지한다.
- Cloud 데이터 게이트는 호환성 보존 상태에서 lifecycle을 계속 진행하고,
  source 기동 후 vCenter guest identity 검증이 실패하면 target authority를 유지한다.

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| 호환성 게이트 | 모든 역방향 경로를 검증 대기로 초기화 | VMware 원본 계보는 기존 보존 상태 유지 |
| 부팅 성공 | 호환성과 부팅 증거가 혼재 | Windows는 별도 guest heartbeat 필수 |
| 회귀 영향 | 기존 성공 페일백이 source 기동 전 차단 | 검증된 전송 경로 유지 후 강화된 부팅 검증 수행 |

## 19. 계획 단위 SOURCE 권한 읽기 수렴 (2026-08-22)

Windows 실부팅, 페일백 commit, vCenter guest heartbeat, 후속 증분 Cycle이 모두
성공해도 FTCTL의 계획 단위 `status.state`가 이전 페일오버의
`FAILED_OVER / TARGET`을 유지할 수 있다. 원인은 scheduler recovery가 failback
sidecar의 최종 `COMPLETED`보다 먼저 실행된 뒤 다시 호출되지 않는 순서 경쟁이다.

FTCTL 계획 상태 조회는 완료 sidecar와 commit journal, authority generation,
양측 전원, 후속 체크포인트 및 failover/failback 완료 시각을 모두 검증한 뒤에만
`READY / SOURCE`를 영속 수렴한다. 더 최신 페일오버가 있으면 TARGET을 유지한다.
Cloud는 이 수렴 결과를 기존 비동기 projection 경로로 소비하며 별도 DB 보정이나
VM 제어를 수행하지 않는다.

| 영역 | AS-IS | TO-BE |
| --- | --- | --- |
| FTCTL 계획 상태 | 성공한 페일백 뒤 과거 TARGET이 남을 수 있음 | 엄격한 durable 증거로 SOURCE read repair |
| 신규 페일오버 보호 | 모든 TARGET을 단순 보존 | 완료 시각과 generation이 더 최신인 TARGET만 보존 |
| Cloud | Run/DB는 SOURCE이나 plan status가 뒤처질 수 있음 | 동일한 SOURCE 권한을 비동기 투영 |
| 성공 경로 영향 | 상태 발행 공용 경로의 순서 경쟁 | 전송, VM 전원, librbd/krbd 경로는 변경 없음 |
