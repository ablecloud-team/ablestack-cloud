# Diplo 테마 일관성 후속 반영 설계

## 1. 목적

`ablestack-europa`에 먼저 반영된 전역 UI 개선 중 `ablestack-diplo`에 누락된 항목을
Diplo의 API 호출 규칙과 화면 구성은 유지하면서 반영한다. 대상은 최근 이벤트/경고
표시 레이어, UI 표시 설정 진입점, 전역 레이아웃, 컨텍스트 메뉴와 콘솔 동작이다.

## 2. AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
|---|---|---|
| 최근 활동 | 우측 오버레이 또는 화면별 진입점에 의존 | 화면 하단의 크기 조절 가능한 이벤트/경고 패널로 통합 |
| 표시 설정 | 헤더의 독립 버튼으로 노출 | 사용자 메뉴의 표시 설정 항목으로 이동 |
| 전역 레이아웃 | 하단 활동 패널을 위한 높이/overflow 계약 없음 | 본문과 활동 패널을 flex column으로 구성하고 본문만 독립 스크롤 |
| 컨텍스트 메뉴 | 일부 액션 아이콘/상태/간격이 화면별로 다름 | 공통 리소스 액션 메뉴 토큰과 아이콘 정렬 규칙 사용 |
| 콘솔 URL | Cloud API로 생성한 endpoint만 처리 | 리소스에 외부 콘솔 URL이 있으면 이를 우선 사용하고 API 호출 생략 |
| 라우트 판별 | `$route.meta.name`에만 의존 | meta 값이 없으면 `$route.name`으로 안전하게 대체 |
| API 호출 | Europa의 `getAPI`/`postAPI`와 Diplo의 `api`가 혼재할 위험 | Diplo 기존 `api()` 호출 규칙으로 통일 |

## 3. 코드 반영 대상

| 영역 | 파일 | 변경 내용 |
|---|---|---|
| 전역 레이아웃 | `ui/src/components/page/GlobalLayout.vue` | 활동 패널을 본문 아래에 배치하고 높이/스크롤 상태를 관리 |
| 헤더 | `ui/src/components/page/GlobalHeader.vue`, `ui/src/components/header/HeaderNotice.vue` | 경고 표시와 헤더 액션 위치 정리 |
| 사용자 메뉴 | `ui/src/components/header/UserMenu.vue` | 표시 설정과 활동 패널 진입점을 사용자 메뉴에 배치 |
| 활동 패널 | `ui/src/components/view/EventSidebar.vue` | 이벤트/경고 탭, 주기 갱신, 접기, 높이 조절, 데이터 테이블 구현 |
| 액션 | `ui/src/components/view/ActionButton.vue`, `ui/src/components/view/ResourceActionMenu.vue` | 라우트 fallback, 외부 콘솔 URL, 공통 액션 표시 정합성 보완 |
| 드로어/로그인 | `ui/src/components/widgets/Drawer.vue`, `ui/src/layouts/UserLayout.vue` | 전역 레이아웃과 겹치지 않는 높이/표면 처리 |
| 스타일 | `ui/src/style/index.less`, `ui/src/style/components/view/resource-context-menu.less` | 좁은 스크롤바, 패널/컨텍스트 메뉴 토큰과 dark/light 일관성 적용 |
| 메뉴 구성 | `ui/src/config/section/compute.js`, `ui/src/config/section/network.js` | 기존 동작 위치와 중복되는 표시 항목 정리 |

## 4. 병합 원칙

1. Diplo의 `api()` 호출과 기존 메뉴 구조를 기준으로 유지한다.
2. Europa 변경에서 화면 동작과 스타일만 가져오며 `getAPI`/`postAPI`로 회귀하지 않는다.
3. 전역 스타일은 `--ui-*` 토큰을 사용하고 dark/light 테마별 하드코딩을 추가하지 않는다.
4. 사용자 작업 파일과 빌드 산출물은 커밋하지 않는다.

## 5. 검증 및 배포 게이트

1. 활동 패널, 헤더 진입점, 액션 버튼 단위 테스트를 실행한다.
2. UI production build를 완료하고 생성된 `index.html`의 JS/CSS asset이 실제 파일과 일치하는지 확인한다.
3. origin 전체 릴리즈 빌드는 `noredist` 기본값과 KVM SystemVM artifact 생성을 유지한다.
4. 테스트 서버에는 변경된 UI 패키지만 안전하게 배포한다. DB, 관리 서버 backend,
   agent, SystemVM 변경이 없으므로 해당 구성요소는 교체하지 않는다.
5. 배포 후 `/client/`와 index가 참조하는 모든 asset의 HTTP 200을 확인하고,
   배포 파일 해시가 빌드 artifact와 동일한지 검증한다.
6. 브라우저에서 로그인 화면, dark/light 전환, 이벤트/경고 패널, 사용자 메뉴의 표시
   설정, 리소스 컨텍스트 액션을 확인한다.
