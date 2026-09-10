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

# 584. Cross Hypervisor DR Context Action Availability and Dark-Mode Design

> 2026-08-03 최신 후속 규약:
> [589-cross-hypervisor-dr-reprotect-preflight-and-release-terminal-convergence-design-20260803.md](589-cross-hypervisor-dr-reprotect-preflight-and-release-terminal-convergence-design-20260803.md)
> 는 `UNPROTECTED` 상태의 메뉴를 보호 다시 구성/삭제/이력으로 제한하고,
> 강제 Release를 정상 Release와 분리한다.

> 2026-07-31 latest correction: `confirmFenceClear` is not applicable to
> FTCTL_DR and is absent from user action catalogs. See document 587.

- 작성일: 2026-07-30
- 상태: 상세 설계 완료, 구현 대기
- 적용 범위: DR Plan 목록 우클릭 메뉴, 상세 화면 작업 메뉴
- 적용 레이어: UI, API, DR Backend, Protection View cache
- 비적용 레이어: Agent 명령 계약, FTCTL 실행 계약, 영구 DB schema
- 관련 설계:
  - [506-cross-hypervisor-dr-cloud-ui-design-20260630.md](506-cross-hypervisor-dr-cloud-ui-design-20260630.md)
  - [507-cross-hypervisor-dr-cloud-api-command-design-20260630.md](507-cross-hypervisor-dr-cloud-api-command-design-20260630.md)
  - [508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md](508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md)
  - [521-cross-hypervisor-dr-full-stack-implementation-design-20260701.md](521-cross-hypervisor-dr-full-stack-implementation-design-20260701.md)
  - [522-cross-hypervisor-dr-protection-failover-failback-sequence-design-20260701.md](522-cross-hypervisor-dr-protection-failover-failback-sequence-design-20260701.md)
  - [578-cross-hypervisor-dr-current-authority-and-ui-eligibility-projection-design-20260728.md](578-cross-hypervisor-dr-current-authority-and-ui-eligibility-projection-design-20260728.md)

## 1. 목적

DR Plan 작업 메뉴의 다음 문제를 구조적으로 해결한다.

1. 다크모드에서 비활성 항목이 활성 항목보다 밝은 회색으로 표시된다.
2. 현재 Plan 상태와 무관한 모든 작업이 한 메뉴에 나타난다.
3. 비활성 이유가 없어 사용자가 상태와 조치 순서를 이해하기 어렵다.
4. 목록 우클릭 메뉴와 상세 작업 메뉴가 같은 동작을 서로 다른 렌더링
   경로로 표시할 수 있다.
5. UI가 backend의 boolean eligibility를 다시 해석해 current authority와
   충돌할 가능성이 있다.

이번 설계는 작업의 실행 의미를 바꾸지 않는다. Cloud가 비동기 Run을 만들고,
Agent가 FTCTL 명령을 전달하며, FTCTL이 data-plane 작업을 수행하는 기존
경계를 유지한다. 변경 대상은 **어떤 작업을 현재 사용자에게 보여주고, 어떤
이유로 실행 가능 또는 불가능하다고 표시할지**에 대한 control-plane 계약이다.

## 2. Preflight 확인 결과

### 2.1 이미지와 렌더링 코드 대조

첨부 화면에서 비활성 작업은 `#555`에 가까운 밝은 배경으로 표시됐다.
소스에서는 다음 경로가 확인됐다.

```text
DrPlanList.vue
  -> DrResourceContextMenu.vue
  -> ActionButton.vue
  -> a-button type="text" disabled
```

현재 다크모드 전역 규칙:

```less
@disabled-bgColor: #555;

.ant-btn {
  &:disabled {
    background-color: @disabled-bgColor;
    color: @dark-text-color-3;
  }
}
```

DR 전용 비활성 보정은 `.ant-btn-dashed:disabled`만 대상으로 한다. 실제
작업 항목은 `.ant-btn-text`이므로 보정 규칙이 적용되지 않는다. 따라서 전역
`#555` 배경이 노출된다.

### 2.2 작업 노출 조건 확인

`DrPlanServiceImpl.getActionEligibility()`는 모든 작업 키를 boolean map에
항상 넣는다. `resourceActions.js`의 `show` 조건은 키가 존재하는지만 확인한다.

```javascript
show: resource => hasEligibilityEntry(resource, action.key)
```

결과적으로 boolean 값이 `false`인 상태 반대편 작업도 메뉴에 계속 표시된다.
예를 들어 `READY/SOURCE`에서는 다음 작업이 모두 보이지만 실행할 수 없다.

- 동기화 복구
- 동기화 재개
- 테스트 정리
- Fence 해제 확인
- 페일백
- 재보호
- 복제본 채택
- 실행 취소

이는 backend 판정 오류가 아니라 **applicability와 enabled를 하나의 boolean으로
표현한 API 계약의 한계**다.

### 2.3 실제 환경 변경 여부

이번 Preflight는 첨부 이미지, UI 렌더링 코드, backend eligibility 코드,
기존 authority 설계를 읽기 전용으로 대조했다. API 호출, DB 변경, Agent 명령,
FTCTL 실행 또는 서비스 재시작은 수행하지 않았다. 현재 문제는 재현 이미지와
정적 선택자 경로가 일치하므로 destructive runtime Preflight는 필요하지 않다.

## 3. 오류 원인

### 3.1 메뉴가 아닌 공통 버튼 목록 사용

`DrResourceContextMenu.vue`와 `DrResourceActionMenu.vue`는
`ActionButton.vue`를 `dataView=true`로 재사용한다. 이 컴포넌트는 일반
리소스 작업 버튼을 세로 배치할 수는 있지만 다음 메뉴 의미를 제공하지 않는다.

- 작업 그룹
- 상태별 동적 노출
- 비활성 사유
- 메뉴 keyboard navigation
- `aria-disabled`
- 위험 작업 구분선

### 3.2 CSS selector와 실제 button type 불일치

다크모드 보정 대상은 `ant-btn-dashed`이고 실제 타입은 `ant-btn-text`다.
또한 `ActionButton.vue`의 scoped style에는 밝은 모드 hover 색상
`#e6f4ff`, `#0958d9`가 직접 들어 있다. 전역 다크모드 우선순위에 의존하므로
새 wrapper나 selector가 추가되면 다시 밝은 스타일이 노출될 수 있다.

### 3.3 boolean eligibility의 의미 과부하

현재 `actioneligibility[action]`은 다음 의미를 구분하지 않는다.

```text
지원하지 않는 작업
현재 단계와 관계없는 작업
현재 단계에서는 관계있지만 선행 조건이 부족한 작업
일시적으로 active Run 때문에 잠긴 작업
실행 가능한 작업
```

UI는 `false`를 모두 동일한 비활성 버튼으로 렌더링한다.

### 3.4 UI와 Backend에 authority guard 중복

Backend가 current authority를 포함해 eligibility를 계산한 뒤 UI의
`resourceActions.js`가 `hasDrSourceAuthority()`로 일부 작업을 다시 막는다.
서로 다른 snapshot을 사용하면 backend `true`, UI `false`가 될 수 있다.

### 3.5 작업 정의 중복

작업 label, icon, danger, command 정의가 `resourceActions.js`와
`DrActionToolbar.vue`에 중복된다. 한쪽만 변경하면 목록 우클릭과 상세 작업
메뉴가 달라질 수 있다.

## 4. 설계 원칙

### 4.1 세 가지 상태를 분리한다

모든 작업은 다음 세 값을 가진다.

| 필드 | 의미 |
| --- | --- |
| `applicable` | 현재 Plan 단계에서 사용자에게 의미가 있는 작업인가 |
| `enabled` | 지금 즉시 요청 가능한가 |
| `reasonCode` | applicable하지만 disabled인 이유 |

표시 규칙:

```text
applicable=false                  -> 숨김
applicable=true, enabled=false    -> 비활성 표시 + 사유
applicable=true, enabled=true     -> 활성 표시
availability entry 없음          -> fail-closed 숨김
```

### 4.2 Backend가 의미를 판정하고 UI가 표현한다

Backend는 current authority, active Run, scheduler, target readiness,
test session, NBD recovery, release readiness를 이용해 applicability와
enabled를 계산한다.

UI는 다음만 소유한다.

- label과 icon
- 작업 그룹과 순서
- light/dark visual token
- reasonCode의 locale 문장
- menu interaction

Backend가 UI 순서나 색상을 반환하지 않고, UI가 DR 상태를 다시 추론하지 않는다.

### 4.3 반대 상태 작업은 숨기고 선행 조건 부족은 설명한다

예:

- Scheduler가 RUNNING이면 `재개`는 숨긴다.
- Scheduler가 PAUSED이면 `일시 중지`는 숨긴다.
- 활성 테스트가 없으면 `테스트 정리`는 숨긴다.
- `SOURCE/READY`이면 `페일백`, `재보호`, `Fence 해제 확인`은 숨긴다.
- Failover가 현재 단계에 적합하지만 target readiness가 부족하면
  `페일오버`는 비활성으로 표시하고 이유를 제공한다.

### 4.4 위험 작업은 의미로 구분한다

`danger=true`는 일반 disabled 배경을 만들기 위한 속성이 아니다.
활성 상태에서만 위험 색상을 사용한다.

- `페일오버`
- `복제본 영구 채택`
- `보호 해제`
- `DR 계획 삭제`

비활성 danger 작업은 일반 비활성 색상으로 표시한다.

## 5. 작업별 가용성 계약

### 5.1 UI 작업 catalog

`ui/src/utils/dr/resourceActions.js`는 단일 catalog를 제공한다.

```javascript
const DR_PLAN_ACTION_CATALOG = [
  {
    key: 'update',
    command: 'updateDrPlan',
    group: 'PLAN',
    order: 10,
    label: 'label.dr.plan.edit',
    icon: 'edit-outlined'
  }
]
```

`DrActionToolbar.vue`, `DrResourceActionMenu.vue`,
`DrResourceContextMenu.vue`는 이 catalog를 직접 소비한다. 별도 action 배열을
유지하지 않는다.

### 5.2 상태별 의미

| 작업 | applicable 조건 | enabled 추가 조건 | 권장 표시명 |
| --- | --- | --- | --- |
| `update` | 항상 | active Run 없음 | DR 계획 수정 |
| `delete` | 항상 | active Run, 보호 관계, runtime 자원 없음 | DR 계획 삭제 |
| `sync` | SOURCE authority, 일반 복제 단계 | 실행 준비 완료, active Run 없음, 복구 불필요 | 지금 동기화 |
| `recoverSync` | recovery required | active Run 없음, FTCTL control 가능 | 복제 서비스 복구 |
| `pauseSync` | scheduler desired state가 RUNNING | pausable state, active Run 없음 | 동기화 일시 중지 |
| `resumeSync` | scheduler desired state가 PAUSED | control ready, active Run 없음 | 동기화 재개 |
| `testFailover` | SOURCE authority, 활성 테스트 없음 | target/cutover ready, active Run 없음 | 테스트 페일오버 |
| `stopTestFailover` | 활성 또는 cleanup-required test session 존재 | active Run 없음 | 테스트 정리 |
| `failover` | SOURCE authority | target/cutover ready, active Run 없음 | 페일오버 |
| `confirmFenceClear` | TARGET authority, manual fence recovery 단계 | active Run 없음 | 원본 사이트 격리 해제 확인 |
| `failback` | TARGET authority, 원본 복귀 경로 존재 | failback preflight ready, active Run 없음 | 페일백 |
| `reprotect` | TARGET authority, failed-over 상태 | committed target authority, target runtime ready | 현재 운영 사이트에서 재보호 |
| `adoptReplica` | replica-controller disaster adoption 지원 | adoption preflight ready | 복제본을 독립 운영 VM으로 전환 |
| `releaseProtection` | 보호/runtime 자원 존재 | release readiness 충족 | 보호 해제 |
| `cancelRun` | 취소 가능한 active Run 존재 | Run id와 cancel contract 존재 | `{현재 작업명} 실행 취소` |

### 5.3 메뉴 그룹

| 그룹 | 작업 |
| --- | --- |
| 현재 작업 | `cancelRun` |
| 계획 관리 | `update`, `delete` |
| 복제 | `sync`, `recoverSync`, `pauseSync`, `resumeSync` |
| 복구 테스트 | `testFailover`, `stopTestFailover` |
| 운영 전환 | `failover`, `confirmFenceClear`, `failback`, `reprotect` |
| 고급 복구 | `adoptReplica` |
| 보호 종료 | `releaseProtection` |

빈 그룹은 렌더링하지 않는다. 일반 안정 상태에서 메뉴 항목 수는 4~7개를
목표로 한다.

## 6. API 상세 설계

### 6.1 호환 필드 유지

기존 필드는 제거하거나 타입을 바꾸지 않는다.

```json
"actioneligibility": {
  "sync": true,
  "failback": false
}
```

이 필드는 구버전 UI 호환용이며 값은 새 availability의 `enabled`에서 파생한다.

### 6.2 typed availability 추가

`DrPlanResponse`와 Protection View `planProjection`에 다음 필드를 추가한다.

```json
"actionavailability": {
  "sync": {
    "applicable": true,
    "enabled": true,
    "reasoncode": null,
    "reasonargs": {}
  },
  "failback": {
    "applicable": false,
    "enabled": false,
    "reasoncode": "DR_ACTION_SOURCE_AUTHORITY_ACTIVE",
    "reasonargs": {}
  },
  "delete": {
    "applicable": true,
    "enabled": false,
    "reasoncode": "DR_ACTION_PROTECTION_RELEASE_REQUIRED",
    "reasonargs": {
      "activeRuntimeResources": 3
    }
  }
}
```

신규 DTO:

```text
org.apache.cloudstack.api.response.dr.DrActionAvailabilityResponse
```

필드:

```java
Boolean applicable;
Boolean enabled;
String reasonCode;
Map<String, String> reasonArgs;
```

`reasonArgs`에는 secret, credential reference, 내부 파일 경로, 원격 명령 또는
raw engine 오류를 넣지 않는다.

### 6.3 reason code

최소 코드 집합:

```text
DR_ACTION_ACTIVE_RUN
DR_ACTION_PLAN_DISABLED
DR_ACTION_ENGINE_UNAVAILABLE
DR_ACTION_SOURCE_AUTHORITY_REQUIRED
DR_ACTION_TARGET_AUTHORITY_REQUIRED
DR_ACTION_TARGET_NOT_READY
DR_ACTION_CUTOVER_NOT_READY
DR_ACTION_SCHEDULER_RUNNING
DR_ACTION_SCHEDULER_PAUSED
DR_ACTION_RECOVERY_NOT_REQUIRED
DR_ACTION_RECOVERY_REQUIRED
DR_ACTION_TEST_SESSION_ACTIVE
DR_ACTION_TEST_SESSION_NOT_ACTIVE
DR_ACTION_FENCE_CONFIRM_REQUIRED
DR_ACTION_COMMITTED_TARGET_REQUIRED
DR_ACTION_PROTECTION_RELEASE_REQUIRED
DR_ACTION_RELEASE_NOT_READY
DR_ACTION_CANCEL_NOT_SUPPORTED
DR_ACTION_TRANSITION_IN_PROGRESS
DR_ACTION_PROJECTION_STALE
```

UI locale은 `message.dr.action.blocked.<reason-code-lowercase>` 형식을 사용한다.
알 수 없는 코드는 `message.dr.action.not.eligible`로 fallback한다.

## 7. Backend 상세 설계

### 7.1 단일 evaluator

신규 interface와 구현:

```text
com.cloud.dr.DrPlanActionAvailabilityEvaluator
com.cloud.dr.DrPlanActionAvailabilityEvaluatorImpl
com.cloud.dr.DrActionAvailability
```

interface:

```java
Map<String, DrActionAvailability> evaluate(long planId);
```

입력 snapshot:

```text
DrPlanVO
DrCurrentAuthorityProjection
active DrRun
DrPlanRuntimeVO
DrPlanReadiness execution/release
DrProtectionAuthoritySnapshot
DrTestSessionVO
current cutover/failback session
engine capability
```

모든 입력은 한 evaluator 호출에서 읽는다. 외부 Agent/FTCTL 호출은 하지 않는다.

### 7.2 기존 service 위임

`DrPlanServiceImpl.getActionEligibility()`는 자체 조건식을 제거하고 evaluator에
위임한다.

```java
public Map<String, Boolean> getActionEligibility(long planId) {
    return actionAvailabilityEvaluator.evaluate(planId).entrySet().stream()
        .collect(toMap(Map.Entry::getKey, entry -> entry.getValue().isEnabled()));
}
```

신규 method:

```java
Map<String, DrActionAvailability> getActionAvailability(long planId);
```

기존 action command의 서버 측 eligibility 검증도 같은 evaluator의 `enabled`를
사용한다. UI 표시와 API 실행 검증이 다른 조건식을 갖지 않게 한다.

### 7.3 Response와 cache

`DrResponseGenerator.createPlanResponse()`는 같은 evaluator 결과로 다음을 함께
설정한다.

```text
actioneligibility
actionavailability
```

`DrProtectionViewServiceImpl`의 snapshot version을 `6 -> 7`로 올린다.
version 6 cache는 조회 시 기존 rebuild 경로로 재생성한다. cache row의 컬럼
변경은 없다.

### 7.4 active Run 처리

active Run이 존재할 때 모든 작업을 단순 비활성으로 만들지 않는다.

- `cancelRun`: applicable/enabled
- 현재 Run과 충돌하는 operation: applicable=false
- `update`, `delete`: applicable=true, enabled=false,
  `DR_ACTION_ACTIVE_RUN`

사용자는 현재 실행 중인 작업과 취소 가능 여부를 먼저 볼 수 있다.

### 7.5 stale projection 처리

Protection View cache가 stale이면 위험 action은 활성화하지 않는다.

```text
applicable=true
enabled=false
reasonCode=DR_ACTION_PROJECTION_STALE
```

단, 새 `getDrPlan` canonical projection이 더 최신이면 그 객체의 availability를
사용한다. UI가 오래된 cache availability로 최신 Plan을 덮지 않는다.

## 8. UI 상세 설계

### 8.1 availability normalizer

신규 파일:

```text
ui/src/utils/dr/actionAvailability.js
```

함수:

```javascript
normalizeActionAvailability(resource)
resolveDrPlanActions(resource, currentRun, apiMap)
reasonMessageKey(reasonCode)
```

호환 순서:

```text
1. actionavailability가 있으면 typed 계약 사용
2. 없으면 actioneligibility boolean을 compatibility mode로 사용
3. 둘 다 없으면 fail-closed
```

compatibility mode에서는 기존 source-authority guard를 유지한다. typed
availability가 있으면 UI의 `hasDrSourceAuthority()` 재판정을 적용하지 않는다.

### 8.2 전용 메뉴 컴포넌트

`DrResourceContextMenu.vue`는 `ActionButton.vue`를 제거하고 Ant Design Vue
menu semantic을 사용한다.

```text
a-menu
  a-menu-item-group
    a-menu-item
```

요구사항:

- 목록 우클릭과 상세 작업 버튼이 같은 resolved action 배열 사용
- group divider와 label 제공
- `role=menu`, keyboard arrow/enter/escape 지원
- disabled 항목에 `aria-disabled=true`
- disabled 항목 wrapper에서 tooltip 표시
- action 실행 후 menu close
- 화면 경계 보정 로직 유지
- 최대 높이와 내부 스크롤 제공

### 8.3 목록과 상세 동기화

`DrPlanList.vue`의 `planActions` computed는 다음 입력만 사용한다.

```text
현재 화면이 보유한 canonical Plan projection
currentRun
store API capability map
```

목록 row와 상세 `detailPlan` 모두 `resolveDrPlanActions()`를 호출한다.
action catalog, filtering, group, label을 별도로 만들지 않는다.

### 8.4 용어 변경

| 기존 | 변경 |
| --- | --- |
| 동기화 복구 | 복제 서비스 복구 |
| Fence 해제 확인 | 원본 사이트 격리 해제 확인 |
| 재보호 | 현재 운영 사이트에서 재보호 |
| 복제본 채택 | 복제본을 독립 운영 VM으로 전환 |
| 실행 취소 | `{현재 작업명} 실행 취소` |

영문도 같은 의미로 변경한다.

```text
Recover replication service
Confirm source-site isolation release
Reprotect from current active site
Convert replica to independent workload
Cancel {operation}
```

### 8.5 다크모드 token

`cross-dr.less`의 `.cross-dr-page`와 `.cross-dr-modal` token에 메뉴 값을
추가한다.

```css
--cross-dr-menu-bg
--cross-dr-menu-border
--cross-dr-menu-text
--cross-dr-menu-text-muted
--cross-dr-menu-disabled-text
--cross-dr-menu-hover-bg
--cross-dr-menu-focus
--cross-dr-menu-danger-text
--cross-dr-menu-danger-hover-bg
```

다크모드 권장값:

```css
--cross-dr-menu-bg: #1f252b;
--cross-dr-menu-border: rgba(255, 255, 255, 0.12);
--cross-dr-menu-text: rgba(255, 255, 255, 0.85);
--cross-dr-menu-text-muted: rgba(255, 255, 255, 0.55);
--cross-dr-menu-disabled-text: rgba(255, 255, 255, 0.28);
--cross-dr-menu-hover-bg: rgba(255, 255, 255, 0.08);
--cross-dr-menu-focus: #40a9ff;
--cross-dr-menu-danger-text: #ff7875;
--cross-dr-menu-danger-hover-bg: rgba(255, 77, 79, 0.12);
```

핵심 selector:

```less
body.dark-mode .cross-dr-action-menu {
  background: var(--cross-dr-menu-bg);
  border-color: var(--cross-dr-menu-border);
}

body.dark-mode .cross-dr-action-menu .ant-menu-item-disabled,
body.dark-mode .cross-dr-action-menu .ant-menu-item-disabled:hover {
  color: var(--cross-dr-menu-disabled-text) !important;
  background: transparent !important;
}

body.dark-mode .cross-dr-action-menu
  .ant-menu-item:not(.ant-menu-item-disabled):hover {
  color: var(--cross-dr-menu-text);
  background: var(--cross-dr-menu-hover-bg);
}
```

전역 `@disabled-bgColor`는 변경하지 않는다. 다른 Cloud 화면의 버튼이 함께
바뀌는 회귀를 방지하기 위해 `.cross-dr-action-menu` 범위에서만 덮어쓴다.

### 8.6 시각·접근성 기준

- 활성 일반 text contrast: 최소 4.5:1
- disabled는 활성 항목보다 밝거나 넓은 배경을 갖지 않음
- disabled hover 시 배경 변화 없음
- 아이콘은 `color: inherit`
- focus-visible은 2px outline 또는 동등한 focus indicator
- 최소 행 높이 32px
- 아이콘 열 16px, label 간격 8px 고정
- 메뉴 폭 240~320px, 긴 번역은 줄바꿈
- 위험 작업은 색상만이 아니라 구분선과 확인 modal로 식별

## 9. Agent 및 FTCTL 영향

### 9.1 변경 없음

이번 변경은 기존 action command를 그대로 사용한다.

```text
SYNC
RECOVER_SYNC
PAUSE_SYNC
RESUME_SYNC
TEST_FAILOVER
TEST_CLEANUP
FAILOVER
FENCE_CONFIRM
FAILBACK
REPROTECT
ADOPT
RELEASE
```

Agent command DTO, wrapper, FTCTL CLI, profile, lock, state file, runtime
status 형식은 변경하지 않는다.

### 9.2 경계 조건

Backend evaluator는 cached Cloud projection만 읽는다. 메뉴를 열거나 목록을
조회하는 요청에서 Agent 또는 FTCTL을 동기 호출하지 않는다.

실행 시에는 기존 비동기 경로를 유지한다.

```text
UI -> Cloud API -> DrRun -> Backend executor -> Agent -> FTCTL
FTCTL status -> Agent -> Backend projection/cache -> API -> UI
```

## 10. DB 영향

### 10.1 schema 변경 없음

`actionavailability`는 current Plan, Run, Runtime, Session, Readiness에서 계산한
projection이다. 새로운 영구 테이블이나 컬럼을 추가하지 않는다.

### 10.2 cache 갱신

`dr_plan_view_cache.snapshot_json`에 typed availability가 포함되고
`snapshot_version=7`을 사용한다. 기존 version 6 row는 자동 rebuild한다.

다음 데이터는 저장하지 않는다.

- UI locale 문장
- 아이콘, 그룹, 순서
- hover/disabled 색상
- tooltip 완성 문장

DB에는 기존 Run/Event 감사 이력만 남는다.

## 11. 테스트 설계

### 11.1 Backend unit test

`DrPlanActionAvailabilityEvaluatorImplTest`에 다음 matrix를 추가한다.

| 상태 | 보여야 하는 핵심 작업 | 숨겨야 하는 핵심 작업 |
| --- | --- | --- |
| `READY/SOURCE/RUNNING` | sync, pause, test failover, failover, release | resume, cleanup, fence, failback, reprotect |
| `PAUSED/SOURCE` | resume, release | pause, failback, reprotect |
| active test | cleanup | test failover, failover |
| NBD quarantined | recover sync | sync, failover, test failover |
| `FAILED_OVER/TARGET` | fence/failback/reprotect 중 readiness 충족 항목 | sync, pause, test failover |
| active Run | cancel, disabled update/delete | 충돌 operation |
| released draft | update, delete | runtime action |

각 disabled applicable action의 reasonCode도 검증한다.

### 11.2 API test

- `actioneligibility` boolean compatibility 유지
- `actionavailability` typed field serialization
- 두 필드의 enabled 값 일치
- Protection View version 7 재생성
- raw secret과 내부 경로가 reasonArgs에 없는지 검증

### 11.3 UI unit test

```text
ui/tests/unit/utils/dr/actionAvailability.spec.js
ui/tests/unit/components/dr/DrResourceContextMenu.spec.js
ui/tests/unit/components/dr/DrResourceActionMenu.spec.js
```

검증 항목:

- typed 계약 우선
- boolean fallback
- availability 없음 fail-closed
- 상태 반대편 작업 숨김
- disabled reason locale fallback
- 목록/상세 resolved action 동일
- danger disabled가 위험 강조색을 사용하지 않음

### 11.4 시각 회귀 test

Playwright 또는 동등한 브라우저 test로 다음을 캡처한다.

```text
light/dark
목록 우클릭/상세 작업 메뉴
READY/PAUSED/TEST_ACTIVE/FAILED_OVER/ACTIVE_RUN
100%/125% display scale
1280x720/1920x1080
```

computed style 검증:

```text
disabled background == transparent
disabled text != active text
disabled luminance prominence < active hover prominence
focus-visible 존재
```

## 12. 권장 구현 순서

1. `DrActionAvailability` domain object와 evaluator를 추가한다.
2. 기존 `getActionEligibility()`를 evaluator 기반 compatibility map으로 바꾼다.
3. `DrActionAvailabilityResponse`와 `actionavailability` API 필드를 추가한다.
4. Protection View snapshot을 version 7로 올리고 typed field를 포함한다.
5. UI `actionAvailability.js`와 단일 action catalog를 구현한다.
6. `DrActionToolbar.vue`의 중복 action 배열을 제거한다.
7. `DrResourceContextMenu.vue`와 `DrResourceActionMenu.vue`를 menu semantic으로
   전환한다.
8. 한글/영문 label과 reason locale을 추가한다.
9. DR 전용 light/dark token과 scoped selector를 적용한다.
10. Backend/API/UI unit test를 실행한다.
11. Cloud 변경 Maven module과 UI를 빌드한다.
12. 배포 후 active webapp, `WEB-INF`, `/client/`, bundle marker를 확인한다.
13. 상태 matrix별 실환경 메뉴와 비동기 action 수락 경로를 smoke test한다.

Agent와 FTCTL package는 소스 변경 대상이 아니므로 이번 개선만으로 재빌드하지
않는다. Cloud API/backend/UI가 같은 배포 단위로 반영돼야 typed 계약과
fallback 경로를 함께 검증할 수 있다.

## 13. AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| 메뉴 구현 | 공통 `ActionButton`의 세로 text button 목록 | DR 전용 semantic menu |
| 비활성 배경 | 전역 `#555`, 활성보다 밝음 | 투명 배경, 낮은 명도 text |
| 작업 노출 | 모든 eligibility 키를 항상 표시 | applicable 작업만 표시 |
| 비활성 이유 | 없음 | typed reasonCode와 locale tooltip |
| pause/resume | 둘 다 표시, 하나 비활성 | 현재 상태에 맞는 하나만 표시 |
| test/cleanup | 둘 다 표시 | 활성 test session에 맞게 교체 |
| failover/failback | 동시에 메뉴에 존재 | current authority에 맞게 노출 |
| adopt replica | FTCTL_DR에서도 영구 비활성 노출 | 지원되는 replica-controller 상태에서만 노출 |
| cancel | 실행이 없어도 비활성 노출 | 취소 가능한 active Run에서만 노출 |
| authority 판정 | Backend와 UI가 각각 판정 | Backend evaluator 단일 판정 |
| action 정의 | Toolbar와 resource action에 중복 | 단일 catalog |
| API | boolean enabled만 제공 | boolean 호환 + typed availability |
| cache | snapshot version 6 | version 7, typed availability 포함 |
| Agent/FTCTL | 메뉴와 무관한 기존 실행 계약 | 변경 없음 |
| DB | boolean projection 결과만 응답 | schema 변경 없이 cache JSON 확장 |

## 14. 완료 판정 기준

다음 조건을 모두 만족하면 구현 PASS로 판정한다.

1. 다크모드 비활성 항목이 활성 항목보다 밝은 배경을 갖지 않는다.
2. `READY/SOURCE` 메뉴에 failback, reprotect, test cleanup, resume,
   cancel이 나타나지 않는다.
3. 현재 단계에 의미가 있으나 선행 조건이 부족한 작업은 비활성 사유를 제공한다.
4. 목록 우클릭과 상세 작업 메뉴의 작업 집합과 순서가 같다.
5. backend API 실행 검증과 UI enabled 판정이 같은 evaluator 결과를 사용한다.
6. 기존 `actioneligibility` 소비자는 계속 동작한다.
7. stale 또는 availability 누락 시 위험 작업이 활성화되지 않는다.
8. 메뉴 조회가 Agent/FTCTL 동기 호출을 발생시키지 않는다.
9. Protection View version 6 cache가 version 7로 자동 재생성된다.
10. light/dark, keyboard, 화면 경계, 긴 한글/영문 label 시각 검증을 통과한다.

## 15. 구현 및 배포 결과

### 15.1 구현 범위

- Cloud backend에 `DrPlanActionAvailabilityEvaluator`와 typed action availability
  응답 모델을 추가했다.
- 기존 `actioneligibility` boolean map은 호환성을 위해 유지하고, 같은 evaluator
  결과에서 생성하도록 단일화했다.
- `listDrPlans`, `getDrPlan`, plan 생성/수정/활성/비활성 응답과 Protection View
  snapshot version 7에 `actionavailability`를 포함했다.
- UI는 `resourceActions.js`의 단일 action catalog와
  `actionAvailability.js` resolver를 사용한다.
- 목록 우클릭 메뉴, 상세 작업 메뉴, toolbar가 같은 작업 집합과 상태 판정을
  공유한다.
- 메뉴는 계획 관리, 복제, 복구 테스트, 운영 사이트 전환, 보호 종료 그룹으로
  구분한다.
- 현재 상태와 반대되는 작업은 숨기고, 현재 의미가 있지만 선행 조건이 부족한
  작업만 비활성 사유와 함께 표시한다.
- 다크모드 비활성 항목은 투명 배경과 낮은 명도의 텍스트를 사용하며, 실제 실행
  가능한 위험 작업에만 danger 색상을 적용한다.
- Agent, FTCTL, DB schema에는 이번 UI action availability 개선을 위한 변경이
  필요하지 않아 기존 실행 계약을 유지했다.

### 15.2 빌드 및 자동 검증

| 항목 | 결과 |
| --- | --- |
| Cloud DR Maven module compile/checkstyle | PASS |
| Backend targeted unit test | 21 tests, 0 failures, 0 errors |
| UI targeted unit test | 2 suites, 10 tests, PASS |
| 변경 UI 파일 ESLint | PASS |
| DR plugin Maven package | PASS |
| UI production build | PASS |

Cloud Maven 빌드와 UI production build는 WSL ext4 clone
`/home/ablecloud/work/dhslove/ablestack-cloud-dr-action-20260730`에서 수행했다.

### 15.3 배포 결과

- 변경된 16개 Cloud class만 활성 Cloud JAR에 반영했다.
- UI static artifact는 `/usr/share/cloudstack-management/webapp`에 덮어썼으며
  webapp root 삭제나 `rsync --delete`는 사용하지 않았다.
- 배포 전 JAR과 UI static 파일은
  `/root/dr-action-availability-20260730-1635/backup`에 백업했다.
- 배포 후 `WEB-INF` 존재, `/client/` HTTP 200, `mold.service` active를
  확인했다.
- 활성 JAR에서 신규 availability class를, 활성 UI bundle에서
  `actionavailability`와 `cross-dr-action-menu` marker를 확인했다.

### 15.4 런타임 검증

- `listDrPlans` 응답에서 legacy `actioneligibility`와 typed
  `actionavailability`가 함께 반환되는 것을 확인했다.
- Protection View cache 3건 모두 snapshot version 7로 재생성됐다.
- READY/SOURCE 계획에서 `sync`, `pauseSync`, `testFailover`, `failover`,
  `releaseProtection`은 표시되고 반대 상태 작업은 숨겨졌다.
- 목록 우클릭 메뉴에서 계획 관리, 복제, 복구 테스트, 운영 사이트 전환,
  보호 종료 그룹과 작업 순서를 확인했다.
- 다크모드 computed style 검증 결과 활성 항목은 투명 배경과
  `rgba(255,255,255,0.78)`, 비활성 항목은 투명 배경과
  `rgba(255,255,255,0.25)`를 사용했다.
- 10.10.32.1/2/3의 `mold-agent.service`와
  `ablestack-vm-ftctl.timer`는 모두 active이며 FTCTL 설치 경로가 유지됐다.

### 15.5 재테스트 기준

1. READY/SOURCE 계획의 목록 우클릭과 상세 작업 메뉴가 동일한지 확인한다.
2. PAUSED, TEST_ACTIVE, FAILED_OVER, active Run 상태에서 현재 단계에 필요한
   작업만 교체되어 표시되는지 확인한다.
3. 비활성 작업을 hover했을 때 backend reason code에 대응하는 한글 사유가
   표시되는지 확인한다.
4. 활성 작업 하나를 실행하고 기존 비동기 API, Agent, FTCTL 경로가 그대로
   동작하며 메뉴 상태가 Protection View 갱신 후 수렴하는지 확인한다.
