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

# 625. DR Cutover Authority Commit Serialization And Failback Readiness Convergence

- 작성일: 2026-09-01
- 상태: 구현·스모크 테스트·31번 테스트 배포 완료
- 적용 레이어: Cloud DR Backend, Cloud DB projection
- 비적용 레이어: UI component, API contract, Agent, FTCTL, scheduler, data transfer, VM lifecycle
- 관련 설계: 599, 617, 622, 624

## 1. 목적

Failover 데이터 전송, 대상 VM 기동, 부팅 검증이 모두 완료된 뒤 Cloud가 FTCTL에
TARGET authority를 commit하는 짧은 구간만 보강한다. 같은 Failover Run을 두 projection
worker가 동시에 처리해 서로 다른 commit attempt를 만들거나, 이미 ACK된 session을 늦은
실패 응답이 `RETRY_REQUIRED`로 되돌리는 것을 금지한다.

이 변경은 기존 VMware -> RBD, RBD -> RBD, SharedMountPoint qcow2 -> qcow2의 전송 및
VM lifecycle을 변경하지 않는다. Failback 실행 로직도 변경하지 않는다. Failback preflight가
사용하는 committed TARGET authority가 실제 FTCTL journal과 일치하도록 수렴시키는 것이
유일한 목적이다.

## 2. 관측된 오류

31번 클러스터의 Plan `80966d9e-5224-4b8d-bf93-42f94261d058`에서 다음 불일치가 확인됐다.

| 계층 | 상태 |
| --- | --- |
| 대상 VM/부팅/원본 격리 | `POWERED_ON / POWER_STATE_VALIDATED / ACKNOWLEDGED` |
| FTCTL PLAN_AUTHORITY | `FAILED_OVER / TARGET / PROMOTED / ACKNOWLEDGED`, generation `546` |
| Cloud cutover session | `ENGINE_COMMIT_PENDING / RETRY_REQUIRED / UNKNOWN` |
| UI Failback preflight | `Failback requires committed TARGET authority` |

FTCTL journal에는 같은 authority를 가리키는 정상 ACK가 존재하지만, 경쟁한 다른 envelope가
`DR_CUTOVER_COMMIT_CONFLICT`를 반환한 뒤 Cloud DB의 성공 상태를 실패 상태로 덮을 수 있었다.

## 3. 불변 조건

1. `(plan_id, run_id)`의 활성 cutover session은 하나의 `commit_attempt_id`만 사용한다.
2. attempt와 envelope는 DB 행 잠금 안에서 최초 한 번 확정하고 재시도 시 그대로 사용한다.
3. `ACKNOWLEDGED / PROMOTED`는 단조 증가 terminal 상태다.
4. 늦은 실패 응답은 다른 attempt 또는 terminal session을 변경할 수 없다.
5. FTCTL PLAN_AUTHORITY가 동일 Cloud session UUID와 generation에 대해
   `TARGET / PROMOTED / ACKNOWLEDGED`를 증명하면 Cloud는 session을 자동 복구한다.
6. session 복구는 Plan, Replica, Runtime을 `FAILED_OVER / TARGET`으로 수렴시키며 오류를 지운다.
7. Run 성공은 committed TARGET authority 이후에만 허용한다.
8. 세션 UUID 또는 generation이 다르면 자동 복구하지 않는다.

## 4. 구현

### 4.1 Prepare 단계

`commitCloudOwnedCutover()`는 `dr_cutover_session` 행을 `SELECT ... FOR UPDATE`로 다시 읽는다.
잠긴 행에 attempt가 없을 때만 UUID를 생성한다. 이미 존재하면 전달받은 detached 객체의
값을 사용하지 않고 DB 값을 재사용한다.

### 4.2 응답 적용 단계

Agent 응답을 적용할 때 세션 행을 다시 잠근다.

- 성공: 현재 attempt가 전송한 attempt와 같을 때만 ACK terminal로 승격한다.
- 실패: 현재 attempt가 같고 아직 ACK되지 않은 경우에만 `RETRY_REQUIRED`를 기록한다.
- 이미 ACK: 실패 응답을 폐기하고 성공 terminal projection을 계속한다.
- attempt 불일치: 기존 authority를 보존하고 해당 응답을 stale로 취급한다.

### 4.3 FTCTL ACK 재조정

PLAN_AUTHORITY status에서 아래 튜플이 모두 일치할 때만 Cloud session을 복구한다.

```text
active_side                 = TARGET
target_promotion_state      = PROMOTED
engine_ack_state            = ACKNOWLEDGED
cloud_cutover_session_id    = dr_cutover_session.uuid
cloud_authority_generation  = dr_cutover_session.cloud_authority_generation
```

복구는 DB 수동 변경 없이 projection refresh가 수행한다. 다른 session 또는 generation의 과거
journal은 절대 현재 authority로 승격하지 않는다.

## 5. 테스트

필수 단위·스모크 테스트:

1. 같은 session prepare를 반복해도 commit attempt가 바뀌지 않는다.
2. ACK된 session에 늦은 실패 응답을 적용해도 terminal 상태와 오류 없음이 유지된다.
3. Cloud가 `RETRY_REQUIRED`여도 동일 FTCTL session/generation ACK가 있으면 자동 수렴한다.
4. 다른 session UUID 또는 generation의 ACK는 무시한다.
5. 기존 정상 cutover 성공, 실제 commit 실패 시 SOURCE authority 유지 테스트가 통과한다.
6. Failback preflight는 복구 전 차단되고 복구 후 `ready=true`가 된다.

## 6. 배포 및 재검증

Cloud DR integration 모듈 테스트 후 테스트 릴리즈 패키지를 31번 관리 서버에 배포한다.
배포 후 서비스와 `/client/`를 확인하고 projection refresh로 현재 Plan이 다음 상태로 자동
수렴하는지만 확인한다.

```text
Plan              FAILED_OVER / TARGET
Cutover session   FAILED_OVER / PROMOTED / ACKNOWLEDGED
Replica           READY / TARGET / POWERED_ON
Failback preflight ready=true
```

실제 Failback 실행은 사용자가 UI에서 수행한다.

## 7. 구현 및 배포 증거

- Cloud 코드 커밋: `8bb5108c4c265b21986c0adbf0b7a4383f17cb43`
- GitHub Actions 테스트 릴리스: run `33449271367`, 전체 작업 `success`
- 배포 패키지:
  - `cloudstack-common-4.23.0.0-Mold.Europa.202608312307.1`
  - `cloudstack-management-4.23.0.0-Mold.Europa.202608312307.1`
- 비배포 범위: Cloud UI, usage, Agent, FTCTL, scheduler, 전송 경로, VM lifecycle
- 배포 후 관리 서비스: `mold=active`, `/client/=HTTP 200`, `WEB-INF=present`
- 자동 재투영 결과:
  - Plan `FAILED_OVER / TARGET`, 오류 필드 없음
  - Cutover session `FAILED_OVER / PROMOTED / ACKNOWLEDGED`, generation `546`, 오류 필드 없음
  - Replica `READY / TARGET / POWERED_ON`
  - Runtime `FAILED_OVER_UNPROTECTED`, 오류 필드 없음
- `getDrFailbackPreflight` 결과: `ready=true`; `AUTHORITY`, `SOURCE_RUNTIME`,
  `TARGET_RUNTIME`, `FTCTL_TRANSITION`, `REVERSE_DATA` 모두 `READY`
- 이번 Plan의 역방향 기준선은 아직 없으므로 preflight가 선택한 모드는
  `FULL_RESEED`, 예상 가상 용량은 `100 GiB`다. 이는 authority 수렴 결함과 별개인
  기존 Failback 데이터 경로의 정상 초기 시드 판정이다.

실제 Failback 실행과 완료 판정은 사용자 UI 재검증으로 남긴다.

## 8. 교차 사이트 빌드 계약 정렬 및 재테스트 준비

첫 UI 재검증에서 Failback Run `db90c2d4-5369-4d08-b0b2-d754153ec518`은
FTCTL 작업을 시작하기 전에 `Mold API returned HTTP 401`로 실패했다. 원본 13번
Mold 로그의 실제 판정은 API 키 오류가 아니라 다음 원격 명령이 런타임에 등록되지
않았다는 것이었다.

```text
executeFtctlDrSiteAgentCommand
The given command ... either does not exist, is not available for user,
or not available from ip address
```

일반 `listCapabilities`와 VM 조회는 같은 자격 증명으로 성공했으므로 사이트 연결
상태만으로는 이 계약 불일치를 검출할 수 없다. Failback은 계획 소유 Mold, 원본
Mold, 원본 Agent가 같은 원격 DR 명령 계약을 제공해야 한다. 따라서 재테스트 전
양 클러스터의 역할별 Cloud 패키지를 Actions run `33449271367`의 동일 산출물로
정렬했다.

- 공통 빌드: `4.23.0.0-Mold.Europa.202608312307.1`
- 관리 서버: `10.10.13.10`, `10.10.31.10`
  - `cloudstack-common`, `cloudstack-management`
- Agent: `10.10.13.1`, `10.10.13.2:10022`, `10.10.13.3`,
  `10.10.31.1`, `10.10.31.2`, `10.10.31.3`
  - `cloudstack-common`, `cloudstack-agent`
- FTCTL은 양측 모두 기존 `ablestack_vm_ftctl-0.9.5-1`을 유지했다.
- RPM SHA-256:
  - common: `ec540781ae71a0e868a124ea4d84eb7c445022bca3a4a301c0af959d61a250d2`
  - management: `1ffb1f2bbc2a25dfbd71ea6f3047c25df37e2589dab68f0e16875b4379a40d61`
  - agent: `038d14981bdb525ee82c5d032d498bebbaa508d4d709a471b66dfb733bbbb0b3`

배포 후 13번 Mold는 `/client/` HTTP 200, `WEB-INF` 보존, `mold=active`를
충족했다. 모든 Agent는 재시작 후 `active`이고 배포 전후 실행 VM 목록에 차이가
없었다. 원본 Mold 로그에서 `executeFtctlDrSiteAgentCommand`가 API 키 인증 후
정상 처리되며 기존 command-unavailable 판정이 재발하지 않는 것을 확인했다.

31번 UI에서 계획 `80966d9e-5224-4b8d-bf93-42f94261d058`을 직접 열어 확인한
재테스트 준비 상태는 다음과 같다.

```text
Plan status       FAILED_OVER_UNPROTECTED
Readiness         TARGET_READY / 사용 가능
Source site       CONNECTED / CONFIGURED
Target site       CONNECTED / CONFIGURED
Failback dialog   준비 완료
Confirm button    enabled
```

실패한 과거 Run과 세션은 증거 보존을 위해 DB에서 수동 변경하지 않았다. 새
Failback 실행은 새 Run으로 시작하며, 실제 완료 판정은 사용자의 UI 재테스트에서
검증한다.

## 9. 교차 포맷 재보호 후 TARGET 보호 상태 수렴 계약

RBD 원본을 SharedMountPoint qcow2 대상에서 페일오버한 뒤 재보호하면, FTCTL은 대상
사이트에서 역방향 증분 스케줄러를 시작한다. Cloud는 과거 cutover session이
`PROMOTED / ACKNOWLEDGED`로 남아 있다는 이유만으로 현재 상태를
`FAILED_OVER_UNPROTECTED`로 되돌리면 안 된다. 아래 런타임 증거가 모두 충족되면 현재
권위 상태는 `TARGET_PROTECTED`다.

```text
active_side                         TARGET
protection_state                    READY
scheduler_state                     RUNNING
scheduler_health                    HEALTHY
scheduler_pid_alive                 true
owner_matched                       true
baseline_state                      LOCAL_DURABLE | DURABLE | COMMITTED
latest_completed_checkpoint_sequence > 0
error_code                          empty
```

이 판정은 대상 스토리지의 물리 포맷과 무관하다. RBD, qcow2 및 교차 포맷 경로가 같은
authority 계약을 사용한다. `target_materialized=false`는 복제 대상이 Cloud VM으로 아직
구체화되지 않았다는 뜻일 수 있으므로, 이미 커밋된 TARGET 권위의 역방향 보호 상태를
무효화하는 조건으로 사용하지 않는다.

### 9.1 배포 원자성

`FtctlDrStatusAnswer` ABI, KVM Agent wrapper, disaster-recovery projection 클래스는 하나의
호환 단위다. 이 중 일부만 교체하면 최신 FTCTL 응답 JSON은 DB에 저장되더라도 구조화된
runtime 컬럼이 과거 `STOPPED / SUPPRESSED` 값으로 남아 UI가 재보호 필요 상태를 표시할
수 있다. 수정 모듈 배포 시 다음을 하나의 검증 단위로 취급한다.

1. 31번과 32번 관리 서버에 동일한 disaster-recovery 클래스 해시를 배포한다.
2. 서비스 재시작 후 `/client/` HTTP 200과 `WEB-INF` 보존을 확인한다.
3. DB 수동 수정 없이 projection refresh가 runtime 컬럼을
   `READY / RUNNING / HEALTHY / pid-alive / owner-matched`로 수렴시킨다.
4. UI 상세 화면에서 `TARGET_PROTECTED`와 사용 가능 상태를 확인한 뒤에만 Failback
   재테스트를 시작한다.

`preserveFailedOverTargetAuthority()`는 오류 보상이나 늦은 cutover ACK를 처리하기 위한
보존 경로이지 상태 강등 경로가 아니다. 구조화 runtime이 위 TARGET 보호 조건과 최신
완료 Cycle을 이미 충족하면 plan을 `READY / TARGET`으로 유지하고 runtime scheduler
필드를 변경하지 않는다. 따라서 재보호 terminal과 다음 증분 Cycle 중 어느 투영이 먼저
도착해도 결과는 단조롭게 `TARGET_PROTECTED`로 수렴한다.

### 9.2 회귀 범위

실환경과 동일하게 구조화 runtime이 `FAILED_OVER_UNPROTECTED / STOPPED / SUPPRESSED`인
상태에서 건강한 다음 증분 Cycle을 투영하는 테스트를 유지한다. 투영 후 plan, runtime,
replica가 각각 `READY / TARGET`, `READY / RUNNING / HEALTHY`, `READY / TARGET`으로 함께
수렴해야 한다. 기존 VMware-to-RBD, RBD-to-RBD, qcow2-to-qcow2 경로는 동일 테스트 묶음의
회귀 대상으로 유지한다.
