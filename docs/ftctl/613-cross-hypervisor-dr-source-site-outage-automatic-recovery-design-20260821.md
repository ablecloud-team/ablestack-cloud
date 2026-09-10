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

# Cross-Hypervisor DR Source Site Outage Automatic Recovery Design

## 1. 범위

VMware 원본 사이트 전체 전원 장애 후 복구 시 Cloud UI, API, Backend, Agent, FTCTL, DB가 마지막 durable 기준선을 보존하고 VMware -> ABLESTACK(RBD) 지속 보호를 자동 재개하는 계약을 정의한다.

## 2. 계층별 설계

### UI

- terminal `오류` 대신 `원본 사이트 복구 대기`를 경고 색상으로 표시한다.
- 마지막 durable 시각과 RPO 초과는 계속 표시하되 Full Reseed 실행을 유도하지 않는다.
- 자동 복구는 비동기이므로 화면을 차단하지 않고 기존 polling/cache 갱신으로 상태를 반영한다.
- 과거 CBT epoch가 무효화돼 기준선을 다시 만드는 동안에는 terminal `RECOVERY_FAILED`가
  아니라 `복제 기준선 재구성 중(RECOVERING_BASELINE)`을 표시한다.

### API / Backend

- ERROR/DEGRADED 계획도 source authority, desired RUNNING, scheduler DEAD 조건이면 `recoverSync` 대상이 될 수 있다.
- 자동 복구 Controller는 원본 사이트가 `CONNECTED`이고 최신 health check가 180초 이내이며, 최신 연속 3개 health check가 모두 정상일 때만 `RECOVER_SYNC` Run을 제출한다. 사이트 점검 주기보다 짧은 freshness 창을 과거 연속성 이력 전체에 적용하지 않는다.
- idempotency key는 Plan UUID와 authority sequence를 사용해 중복 복구를 막는다.
- 복구 요청은 `forceFullReseed=false`이며 Agent/FTCTL이 기존 baseline을 검증한다.
- Cloud 자동 Controller는 원본 연결 복구 Run만 소유한다. `DR_CBT_*` 오류,
  `RECOVERING_BASELINE`, `RESEEDING`은 FTCTL 실행이 소유하므로 추가 `RECOVER_SYNC`를
  제출하지 않는다.

### Agent / FTCTL

- Agent는 기존 비동기 start/status 계약을 유지한다.
- FTCTL은 `DR_SOURCE_SITE_UNAVAILABLE`을 retryable로 반환하고 같은 Cycle sequence로 backoff 재시도한다.
- vCenter UI가 먼저 복구되고 SOAP SDK `/sdk`만 `503 Service Unavailable` 또는
  `no healthy upstream`인 부분 복구 상태도 `DR_SOURCE_SITE_UNAVAILABLE`로 분류한다.
  이 상태는 VDDK 인자 오류가 아니며 Cloud가 새 Run을 반복 생성하지 않는다.
- 과거 changeId 조회가 실패하지만 현재 changeId preflight가 성공하면 같은 sequence와
  owner Run에서 `SOURCE_CBT_EPOCH_RESET` 사유의 Full Reseed를 한 번만 수행한다.
- 자동 재시드를 시도한 baseline generation을 영구 가드로 남겨 systemd 재시작 뒤에도
  같은 기준선 전체 복사가 반복되지 않게 한다. 새 기준선 durable commit 후에만 해제한다.
- 복구 성공 시 오류/대기 메타데이터를 지우고 최신 durable 증거를 투영한다.

### DB

- 스키마 추가는 없다. 기존 `dr_plan_runtime`, `dr_run`, `dr_sync_cycle`, 사이트 헬스 이력을 사용한다.
- `dr.scheduler.recovery.enabled=true`로 자동 Controller를 활성화한다.
- 실패 Cycle은 durable 완료 Cycle을 대체하지 않으며 자동 복구 성공 Cycle이 최신 canonical Cycle이 된다.

## 3. AS-IS / TO-BE

| 계층 | AS-IS | TO-BE |
|---|---|---|
| UI | VDDK/CBT terminal 오류 | 원본 사이트 복구 대기 또는 복제 기준선 재구성 중 |
| API | ERROR 계획 recoverSync 비활성 | 장애 복구 목적의 제한적 활성 |
| Backend | 복구 중 CBT 오류도 새 RECOVER_SYNC로 반복 제출 | 사이트 안정성 확인 후 한 번 제출하고 CBT 재시드는 FTCTL에 위임 |
| Agent | terminal VDDK 오류 전달 | retryable source outage 전달 |
| FTCTL | 빠른 실패와 StartLimit | 동일 Cycle backoff, baseline 보존, epoch 변경 시 1회 제한 재시드 |
| DB | 과거 durable와 terminal 오류 혼재 | 최신 durable 유지 후 복구 Cycle로 원자적 전환 |

## 4. 운영 판정

자동 증분 복구 PASS는 다음을 모두 만족해야 한다.

1. vCenter와 원본 VM CBT가 정상이다.
2. 원본 사이트의 최신 헬스가 freshness 범위 안에 있고, 최신 3회가 연속 `CONNECTED`다. 5분 점검 주기에서도 최신 점검의 신선도만 180초로 판정하고 과거 두 점검은 연속성 증거로 사용한다.
3. RECOVER_SYNC는 하나만 생성되고 비동기로 수락된다.
4. 과거 CBT epoch가 유효하면 첫 완료 Cycle은 `CBT_INCREMENTAL` 또는 `NO_CHANGE`다.
5. 과거 CBT epoch가 무효하면 한 번의 `FULL_RESEED`가 완료되고, 다음 주기가
   `CBT_INCREMENTAL` 또는 `NO_CHANGE`로 완료된다.
6. latest durable sequence가 증가하고 재시드 전 마지막 정상 기준선은 commit 전까지 보존된다.
7. Scheduler는 RUNNING/HEALTHY, Plan은 READY 또는 RPO 평가에 따른 DEGRADED다.

## 5. 2026-08-21 전원 복구 실환경 보강

- `/ui/`: HTTP 200
- VAPI: 인증 challenge/세션 생성 응답
- `/sdk`: HTTP 503, `no healthy upstream`
- `govc snapshot.create`: `POST "/sdk": 503 Service Unavailable`

따라서 자동 증분 복구는 `/ui` 접근 가능 여부가 아니라 SOAP SDK와 snapshot.create
성공을 source-ready 기준으로 사용한다. SDK 503 동안 FTCTL은 `WAITING_SOURCE`에서
동일 Cycle과 마지막 durable 기준선을 유지한다. SDK가 정상화되면 같은 실행이 CBT
epoch를 검증하고, 기존 epoch가 무효한 경우에만 제한된 Full Reseed를 수행한다.

## 6. 2026-08-21 양 클러스터 배포 검증

| 항목 | 32번 클러스터 | 22번 클러스터 |
|---|---|---|
| Cloud 복구 클래스 SHA256 | `40eb8aafba57f0af2fe467cd3afe4b616480d6dedb9dfa47f090886645cfdd01` | 동일 |
| 관리 서비스 | `mold=active`, `/client/` HTTP 200 | `mold=active`, `/client/` HTTP 200 |
| UI | `WEB-INF` 보존, 한/영 복구 상태 marker 확인 | 동일 |
| FTCTL RPM | `0.9.5-1`, Run `32458925978` | 동일 |
| mover SHA256 | `0cffb6987835adaa6796c309898181ece8bd90bb3b3faf5ff3a34f570ed25d13` | 동일 |

32번에서는 세 계획이 `DR_SOURCE_SITE_UNAVAILABLE / WAITING_SOURCE`로 Cloud DB에
투영됐다. 동일 sequence를 재사용하면서 지수 backoff가 증가했고, 자동 Controller를
다시 활성화한 뒤 70초 관찰에서 전체 `RECOVER_SYNC` Run 수는 `30 -> 30`으로
유지됐다. 따라서 source SDK 장애 중 중복 Cloud Run을 생성하지 않고 FTCTL
scheduler가 재시도 소유권을 유지한다.

현재 `/sdk` HTTP 503은 외부 vCenter 서비스 복구가 필요한 상태다. 이 상태에서는
실제 Full Reseed와 후속 증분 완료를 PASS로 판정하지 않는다. SOAP SDK가 정상화되면
사용자 수동 전체 동기화 없이 같은 대기 Run이 자동으로 source preflight를 재개하고,
필요한 경우 한 번의 Full Reseed 후 다음 Cycle의 `CBT_INCREMENTAL` 또는
`NO_CHANGE`까지 검증한다.

## 7. 2026-09-01 장기 원본 장애 중 재해 페일오버 계약

### 오류 원인

기존 Action Availability는 `normalCutoverReady=false`를 계획된 페일오버와 재해
페일오버에 동일하게 적용했다. 이 때문에 마지막 target-ready durable checkpoint가
존재해도 RPO 초과 또는 원본 복구 대기 상태에서는 사용자가 재해 모드를 선택할
대화상자에 진입할 수 없었고, API도 Run 생성 전에 같은 이유로 요청을 거부했다.

### AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
|---|---|---|
| 계획된 페일오버 | `normalCutoverReady` 필수 | 기존 조건을 그대로 유지한다. |
| 재해 페일오버 | 정상 전환 조건 미충족 시 UI/API 공통 차단 | 차단 사유가 `DR_ACTION_CUTOVER_NOT_READY` 하나인 경우에만 재해 모드 진입을 허용한다. |
| 사용자 확인 | 비활성 메뉴라 확인 불가 | 재해 모드를 고정하고 원본 격리 확인과 근거 입력을 필수로 한다. |
| 체크포인트 | 최신 checkpoint 안내만 제공 | 기존 latest target-ready durable checkpoint 자동 선택 계약을 유지한다. |
| UI 정보 | RPO 상태와 내부 판정 혼재 | 확인 가능한 상태와 마지막 durable checkpoint 사용 사실만 표시한다. 추정 데이터 손실량은 표시하지 않는다. |
| 보호 그룹 | 단일 Plan과 같은 우회 가능성 | 기존 정상 전환 자격을 유지하며 이번 예외를 적용하지 않는다. |

### 코드 경계

이 계약은 저장 형식이 아니라 `FTCTL_DR` 제어 계약에 적용한다. 따라서
`VMWARE_TO_KVM`의 VMDK→RBD, `KVM_TO_KVM`의 RBD→RBD와 qcow2→qcow2가 동일한
판정 경로를 사용하며 provider pair나 디스크 형식으로 예외를 분기하지 않는다.

1. Backend의 일반 `failover` eligibility와 계획된 페일오버 검증은 변경하지 않는다.
2. `StartDrFailoverCmd`는 `disaster=true`, 기존 차단 사유가 정확히
   `DR_ACTION_CUTOVER_NOT_READY`, 전용 `disasterFailover=true`인 경우에만 Action
   Availability 차단을 제한적으로 해제한다. 전용 적격성에는 활성 Run 없음, Source
   권한, FTCTL 제어 준비, target-ready durable checkpoint가 모두 포함된다.
3. UI도 같은 typed reason, `normalcutoverready=false`, `disasterFailover=true`를 모두
   확인한 경우에만 메뉴를 활성화하고 재해 모드를 자동 선택·고정한다.
4. 재해 모드는 기존 계약대로 `finalsync=false`이며, 원본 격리 체크와 근거가 없으면
   UI와 API 모두 제출을 거부한다.
5. `force`는 체크포인트 또는 대상 준비 검증을 우회하는 수단으로 사용하지 않는다.

### 회귀 판정

- 정상 READY 계획의 계획된 페일오버는 기존과 동일하게 활성화된다.
- RPO 초과 계획의 계획된 페일오버는 계속 차단된다.
- RPO 초과 계획은 재해 모드와 원본 격리 확인을 통해서만 제출할 수 있다.
- `DR_ACTION_TARGET_NOT_READY` 등 다른 차단 사유는 재해 모드에서도 차단된다.
- 보호 그룹 페일오버는 기존 정상 전환 조건을 유지한다.

## 8. 2026-09-01 구현·빌드·배포 검증

### 테스트와 빌드

| 항목 | 결과 |
|---|---|
| Cloud Checkstyle | PASS, 위반 0건 |
| Cloud 핵심 단위 테스트 | PASS, 11건 성공 |
| 경로·Action Availability 확장 회귀 | PASS, 27건 성공 |
| UI action availability 테스트 | PASS, 8건 성공 |
| UI locale JSON | PASS, 영어·한국어 파싱 성공 |
| Cloud DR plugin package | PASS, SHA-256 `e41517a5f4cd3cbf9ab65f8ec810a23220e96ddf0bf1d45ce99454bcb3fa4a81` |
| UI production build | PASS, `index.html` SHA-256 `196a92ed8f68d411cbe460c9268d6bbdcfc5922e817c9583cdaa95c7e96f4ec5` |

UI 테스트 runner는 PASS 결과를 출력한 뒤 기존 프로젝트의 열린 handle 때문에 자동 종료하지
않아 종료했으며, 같은 소스의 production build는 정상 종료했다. 이는 테스트 assertion 실패가
아니며 UI 판정 8건은 모두 성공했다.

### 테스트 관리 서버 배포

| 관리 서버 | 클래스 일치 | UI marker | 서비스 |
|---|---|---|---|
| `10.10.13.10` | PASS | PASS | `mold=active`, `/client/=200`, `WEB-INF=present` |
| `10.10.31.10` | PASS | PASS | `mold=active`, `/client/=200`, `WEB-INF=present` |
| `10.10.32.10` | PASS | PASS | `mold=active`, `/client/=200`, `WEB-INF=present` |

배포는 active aggregate Cloud JAR 안의 변경 클래스 네 개만 갱신하고 UI 정적 자산을
overlay했다. 각 서버의 기존 aggregate JAR과 UI 정적 자산은 각각
`/root/dr-disaster-failover-v2-20260901-135413`,
`/root/dr-disaster-failover-v2-20260901-135507`,
`/root/dr-disaster-failover-v2-20260901-135547`에 백업했으며 `WEB-INF`는 교체하지 않았다.
31번 계획 조회에서 정상 `READY / WITHIN_RPO / normalcutoverready=true` 계획의
기존 페일오버 eligibility가 계속 `enabled=true`이고, 전용
`actioneligibility.disasterFailover=true`가 API에 투영됨을 확인했다.
