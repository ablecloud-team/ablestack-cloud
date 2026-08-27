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

# 리소스 컨텍스트 메뉴 통합 설계

## 목적

목록, 상세 요약 카드, 트리 화면의 우클릭 메뉴를 하나의 표현·동작 계약으로 통합한다. DR Plan 메뉴에서 검증된 제목, 작업 그룹, 비활성 상태, 위험 작업 구분을 일반 리소스 메뉴에도 적용하되, 기존 API 권한과 액션 실행 규칙은 변경하지 않는다.

## AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
|---|---|---|
| 렌더링 | `ListView`, `InfoCard`, `TreeView`가 각각 `ActionButton`을 감싼 메뉴를 렌더링 | 세 화면 모두 `ResourceContextMenu` 사용 |
| 위치 처리 | 각 화면이 문서 클릭 리스너와 화면 경계 계산을 중복 구현 | Teleport 기반 공통 메뉴가 경계 보정, 외부 클릭, ESC, 리사이즈, 스크롤 종료 처리 |
| 정보 구조 | 제목 아래에 작업이 한 목록으로 이어짐 | 접근, 컴퓨트, 네트워크, 스토리지, 백업, 관리, 일반, 삭제 그룹으로 구분 |
| 비활성 작업 | 버튼 비활성화만 표시 | 비활성 상태를 유지하고 기존 `tooltip` 반환값을 사유로 표시 |
| 위험 작업 | 아이콘 종류에만 의존 | 명시적 `danger`, 삭제 아이콘, 파괴적 API 이름을 판정하여 마지막 삭제 그룹과 오류 색상 사용 |
| 테마 | 화면별 흰색·검은색 하드코딩 및 다크 모드 보정 중복 | `--ui-*` 의미 토큰만 사용하여 일반·다크 테마 동일 구조 유지 |
| 긴 메뉴 | 화면 밖으로 넘어갈 수 있음 | 최대 높이 `min(70vh, 640px)`, 내부 세로 스크롤, 가로 스크롤 금지 |
| 다중 선택 | 부모와 자식의 표시 조건이 달라 일부 그룹 액션이 누락될 수 있음 | 부모가 선별한 `groupAction`을 공통 메뉴가 그대로 표시·실행 |

## 구성요소 계약

### `ResourceContextMenu.vue`

- 입력: `actions`, `resource`, `position`, `selectedRowKeys`, `selectedItems`, `titleOverride`
- 출력: `close`, `exec-action`
- DOM은 `body`로 Teleport하여 테이블과 카드의 `overflow`에 잘리지 않게 한다.
- 최초 렌더 후 메뉴 크기를 측정하여 브라우저 가장자리 8px 안쪽으로 좌표를 보정한다.
- 외부 포인터 입력, ESC, 윈도우 리사이즈, 상위 스크롤 시 닫는다.
- 메뉴 내부 스크롤 이벤트는 닫기 조건에서 제외하여 긴 작업 목록을 끝까지 탐색할 수 있게 한다.

### `ResourceActionMenu.vue`

- 표현 전용 컴포넌트이며 API를 직접 호출하지 않는다.
- 제목은 한 줄 말줄임 처리하고 전체 문자열은 네이티브 툴팁으로 확인한다.
- 그룹 제목과 항목 순서는 고정하되 각 그룹 내부에서는 기존 액션 순서를 유지한다.
- 비활성 항목은 실행하지 않고, 사유가 있을 때 우측 툴팁으로 표시한다.

### `ActionButton.vue`

- 일반 툴바 모드는 기존 원형·텍스트 버튼 렌더링을 유지한다.
- `dataView` 모드만 `ResourceActionMenu`로 위임한다.
- 콘솔, 포털 등 내장 작업과 API 액션을 같은 엔트리 모델로 변환한다.
- API 권한, `show`, `groupShow`, `disabled`, `tooltip`, 배지 계산과 `exec-action` 이벤트 계약은 유지한다.

### `actionMenu.js`

- 액션이 `menuGroup`을 제공하면 이를 최우선 사용한다.
- 명시값이 없으면 API·레이블·아이콘 키를 이용해 보수적으로 그룹을 추론한다.
- 파괴적 작업은 항상 `DANGER` 그룹으로 보내며, `danger: false`로 명시하면 예외 처리할 수 있다.

## 스타일 기준

- 메뉴 폭 272px, 모서리 6px, 행 최소 높이 36px, 아이콘 16px.
- 배경, 경계선, 글자, hover, disabled, danger, shadow는 `ui/src/style/theme/tokens.less`의 의미 토큰만 사용한다.
- 일반·다크 테마에서 DOM 구조와 치수는 동일하고 색상 토큰만 달라진다.
- 스크롤바는 공통 6px 테마 규칙을 상속한다.

## 검증 기준

1. `ActionButton` 기존 단위 테스트와 액션 그룹 유틸리티 테스트 통과.
2. UI lint와 production build 통과.
3. 테스트 서버의 목록, 상세 카드, 트리 화면에서 우클릭 메뉴가 같은 구조로 표시.
4. 일반·다크 테마에서 제목, 그룹, hover, disabled, danger 대비 확인.
5. 화면 우측·하단에서 메뉴가 잘리지 않고 긴 메뉴는 내부 스크롤로 탐색 가능하며, 내부 스크롤 중 메뉴가 닫히지 않음.
6. 메뉴 실행 후 기존 액션 대화상자 또는 API 작업이 동일하게 시작되는지 확인.
