<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# 목업 검증 기록

검증일: 2026-10-03. 사용자 피드백을 반영하여 기존 일반 HTML/DOM 목업을 **Vue 3 SFC + Ant Design Vue 3.2.20 앱**으로 교체했다. 이 문서는 교체한 목업의 검증 기록이다.
최종 동기화 기준은 upstream Europa b31026f919856a1b61c1f86dca450e16ac0673e1이다. 작업 중 새로 반영된 #1215와 초기 기준 사이에서 분석 대상 VM 스냅샷 목록·strategy·capacity 계산 파일은 변경되지 않았다. 새 스토리지 선택 API의 동일 계산 사용도 확인했다.
설치된 UI 의존성 Vue/compiler-sfc 3.5.41, Ant Design Vue 3.2.20과 공유 Status/TooltipButton/dark-mode/theme 소스를 사용했다. 목업 webpack 번들 빌드를 완료했다. 제품 Vue/Java 기능 구현 또는 31번 배포 환경의 E2E 결과는 아니다. Chromium/Codex in-app 브라우저에서 예시 16개 데이터를 사용했다.

## 확인 결과

| 항목 | 관찰 결과 |
|---|---|
| 표준 형식 | 31번 가상머신 목록과 같은 브레드크럼/컨트롤·우측 작업/검색·단일 행 표·표 헤더 열 설정·우측 페이지 구성. 표 헤더 47px, 스냅샷 행 48px (31번 기존 스냅샷 목록과 동일), 선택 열 30px, 기본 20개/쪽 |
| 기술 | 실제 Vue SFC 컴파일 + AntD 컴포넌트 렌더링. 기존 plain HTML renderer/stylesheet 제거, 외부 CDN/API 없음 |
| Windows11-Process 검색 | 조회 결과 1개. 예시 배열 전체의 VM 이름 검색 |
| A 선택 후 B 우클릭 | Windows11 체크 유지 중 Debian13 행 우클릭 → Debian13 스냅샷 제목, 삭제 확인창 → Debian13 VM/내부 이름/UUID 표시. Windows11+Debian12 다중 체크 중에도 Debian13 단일 대상 유지 |
| 기존 컨텍스트 형식 | ResourceActionMenu.vue/actionMenu.js/resource-context-menu.less 직접 재사용. 자원 제목 1줄, 폭 272px, 그룹 제목·28px 항목·아이콘·삭제 강조·제한 툴팁. 행별 더보기 버튼 0개 |
| 메뉴 닫힘·경계 배치 | 외부 클릭/Escape/PageUp 목록 스크롤/viewport 변경 후 메뉴 0개. 우측·하단 경계에서 실제 여백 각 8px. 다른 행 재우클릭 시 제목·대상 교체 |
| 유형·VM 상태 제약 | 정지된 Debian13의 메모리 포함 복원 메뉴가 비활성화되고 기존 비활성 항목 툴팁으로 시작 필요 사유 표시 |
| 복원 확인 | Windows11/UUID/시각/유형/상태/영향 표시. 확인 체크 전 최종 버튼 비활성, 체크 후 활성 |
| 선택 삭제 | Windows11 + Debian13 2개 대상이 대화상자에 일치. 원본 VM 삭제가 아닌 복원 지점 삭제와 순차 처리 영향 안내 |
| 선택 삭제 차단 | Windows11 + 생성 중 Windows25 선택 → Windows25 진행 중 사유 표시, 전체 제출 비활성 |
| 정렬·페이징 | AntD 표 머리글 정렬과 표 하단 Pagination. 기본 20개/쪽으로 16개 예시를 표시. 서버 정렬 및 2페이지 이상 검증은 S3/S6에서 수행 |
| 검색 조건·열 설정 | 기존 검색 입력의 필터 팝오버와 표 헤더 우측 열 선택기를 사용 |
| VM 관계 | 행 우클릭 컨텍스트 메뉴/VM 링크에서 중앙 대화상자. W2025 예시 3개/현재 기준점/부모 상세 표시. 목록 필터와 별도의 VM 전체 조회 범위 명시 |
| 부분 관계 | 일부 데이터 조회 안내와 확인된 관계만 표시. 목록 자체 배치 유지 |
| 로딩·빈 목록 | 각각 별도 문구. 조회 실패는 이전 결과를 유지하며 다시 시도 제공 |
| 테마 | 다크/라이트 목록과 대화상자를 화면에서 검토하고 캡처 |
| 모달 스크롤 | 1366×768 실제 적용. 복원 창 중앙 Y=384. 헤더 Y=24, 푸터 Y=687 유지. body clientHeight=608 / scrollHeight=816, scrollTop 0→208. 화면 전체 scrollTop=0. 확인 체크 전 제출 비활성·후 활성 |
| 취소·포커스 | AntD Modal의 취소/Escape와 기존 목록 복귀 확인 |
| 빌드·콘솔 | 설계 앱 webpack production 번들 빌드 성공. in-app 브라우저 warn/error 로그 0건. Full Cloud 또는 제품 전체 UI 빌드와 구분 |

화면 이미지: 01 다크 목록, 02 우클릭 행 대상 메뉴, 03 복원 확인창, 04 선택 삭제, 05 VM 관계, 06 라이트 목록, 07 선택 삭제 차단, 08 모달 콘텐츠 스크롤, 09 기존 페이지 형식, 10 검색 조건 팝오버.
모두 예시 데이터의 실제 브라우저 캡처이다. 현재 클러스터 상태나 작업 성공 증거로 해석하지 않는다.

## 제한 및 이후 검증

이전 HTML 목업에서는 Chrome viewport 요청이 반영되지 않았으나 이번에는 in-app 브라우저의 1366×768 실제 viewport를 확인하고 모달 중앙 배치/내용 스크롤을 검증했다. 모바일 전체 기능·접근성·텍스트 대비 검증은 이 목업 검토만으로 통과를 주장하지 않는다.

정식 제품의 API/ACL/async job/최신 상태 재검증/실제 복원·삭제, Maven/UI production build, 배포, provider 물리 통계와 DB 용량 전환은 미수행이다.
실제 서버 검색·정렬, 누락 부모/순환/대규모 관계, 25개 이상 데이터, 모든 권한·테마·반응형·텍스트 대비 및 회귀는 하위 이슈 #1223에서 검증한다.

## 31번 읽기 검토

2026-10-03 재확인에서도 31번 VM 스냅샷 16개 행에 버튼이 없으며, 우클릭으로 기존 ResourceContextMenu/ResourceActionMenu 형식의 메뉴가 열렸다. 메뉴를 Escape로 닫았으며 작업을 실행하지 않았다.

기존 목록의 잘못된 우클릭 대상, 대상 없는 복원 확인창, VM 이름 검색 실패를 관찰했다. 대화상자를 취소했으며 복원·삭제를 실행하지 않았다.
1차 Primary 상세의 사용 9.06%/할당 75.66%와 최신 소스 계산 경로를 확인했다. 생성 전후 증분/배포 JAR 동일성/DB chain 합산/실제 풀 통계 검증은 아직 하지 않았다. [용량 분석](storage-accounting.ko.md)에 근거와 범위를 기록했다.
