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

# Epic #1914 Mold OAuth 구성 개선 구현 준비

2026-11-24 KST에 최신 upstream `ablestack-europa`를 기준으로 `dhslove/epic-1914-oauth-configuration`를 분기했다. 31번 GFS2와 32번 RBD의 관리자 UI 및 관리 API 증거는 상위 Epic에 있다. [기준 커밋 `fb40815b6dc`](https://github.com/ablecloud-team/ablestack-cloud/commit/fb40815b6dcb41d356332c75bb645eb48fffe6c4) · [Epic #1914](https://github.com/ablecloud-team/ablestack-cloud/issues/1914)

## 구현 순서

| 순서 | 우선순위 | 하위 이슈 | 구현 경계 | 선행 이슈 |
| --- | --- | --- | --- | --- |
| 1 | P1 | [#1919](https://github.com/ablecloud-team/ablestack-cloud/issues/1919) | 관리 목록의 전역·도메인 범위, 키워드와 페이지 계약을 정의한다. | 독립 |
| 2 | P1 | [#1921](https://github.com/ablecloud-team/ablestack-cloud/issues/1921) | 시크릿의 기본 표시와 재전송을 줄이고 명시적 변경을 연결한다. | [#1919](https://github.com/ablecloud-team/ablestack-cloud/issues/1919) |
| 3 | P1 | [#1922](https://github.com/ablecloud-team/ablestack-cloud/issues/1922) | 범위 전환과 저장 상태·실제 사용 가능 상태 및 작업 조건을 정리한다. | [#1919](https://github.com/ablecloud-team/ablestack-cloud/issues/1919) · [#1921](https://github.com/ablecloud-team/ablestack-cloud/issues/1921) |
| 4 | P1 | [#1924](https://github.com/ablecloud-team/ablestack-cloud/issues/1924) | 관리 목록 검색·필터를 URL에 연결하고 상세 왕복을 복원한다. | [#1919](https://github.com/ablecloud-team/ablestack-cloud/issues/1919) · [#1922](https://github.com/ablecloud-team/ablestack-cloud/issues/1922) |
| 5 | P2 | [#1925](https://github.com/ablecloud-team/ablestack-cloud/issues/1925) | Keycloak 조건부 입력과 범위별 등록 가능 여부를 검증한다. | [#1921](https://github.com/ablecloud-team/ablestack-cloud/issues/1921) · [#1922](https://github.com/ablecloud-team/ablestack-cloud/issues/1922) · [#1924](https://github.com/ablecloud-team/ablestack-cloud/issues/1924) |
| 6 | P2 | [#1926](https://github.com/ablecloud-team/ablestack-cloud/issues/1926) | 삭제 확인에서 제공자·범위·UUID를 표시하고 결과를 재조회한다. | [#1924](https://github.com/ablecloud-team/ablestack-cloud/issues/1924) |
| 7 | P2 | [#1927](https://github.com/ablecloud-team/ablestack-cloud/issues/1927) | 한영 안내, 상태·오류 문구 및 OAuth 도움말 링크를 적용한다. | [#1922](https://github.com/ablecloud-team/ablestack-cloud/issues/1922) · [#1924](https://github.com/ablecloud-team/ablestack-cloud/issues/1924) · [#1925](https://github.com/ablecloud-team/ablestack-cloud/issues/1925) · [#1926](https://github.com/ablecloud-team/ablestack-cloud/issues/1926) |
| 8 | P2 | [#1928](https://github.com/ablecloud-team/ablestack-cloud/issues/1928) | 가상머신 표준 및 두 테마의 대표 화면·접근성을 인수한다. | [#1921](https://github.com/ablecloud-team/ablestack-cloud/issues/1921) · [#1922](https://github.com/ablecloud-team/ablestack-cloud/issues/1922) · [#1924](https://github.com/ablecloud-team/ablestack-cloud/issues/1924) · [#1925](https://github.com/ablecloud-team/ablestack-cloud/issues/1925) · [#1926](https://github.com/ablecloud-team/ablestack-cloud/issues/1926) · [#1927](https://github.com/ablecloud-team/ablestack-cloud/issues/1927) |

## 인수 범위

- 변경 OAuth Maven 모듈은 WSL ext4에서 빌드하고, UI 변경은 대상 단위 테스트·ESLint·프로덕션 번들로 확인한다.
- 31번 GFS2와 32번 RBD에서 새 관리 검색·범위·상태·등록·편집·삭제 흐름을 임시 도메인과 합성 값으로 검증한다. 관리 목록과 기존 로그인 제공자 탐색을 별도로 확인하고 시험 데이터를 정리한다.
- 최종 UI 인수에서 같은 빌드의 가상머신 breadcrumb, 목록, 상세, 탭·탭 본문과 대화상자를 비교한다. 필요한 정보는 공통 도구 영역·요약·본문·모달 슬롯에 배치한다. 한국어·영어, 라이트·다크, 390·768·1536px에서 가독성·스크롤·키보드 포커스를 확인한다.
- #1067의 스키마 호환성은 회귀 기준으로 사용한다. 이번 Epic의 상세 구현 조건과 완료 판정은 위 하위 이슈에 기록한다. [기존 등록 창 검증 #1067](https://github.com/ablecloud-team/ablestack-cloud/issues/1067)
