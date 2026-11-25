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

# 시스템 오퍼링 Epic #1863 작업 및 검증 계획

Mold **서비스 오퍼링 → 시스템 오퍼링**에서 유형에 맞는 생성·복제, API와 일치하는 검색, 안전한 상태·순서 변경을 제공한다. 같은 클러스터의 **컴퓨트 → 가상머신**을 목록·상세·탭·대화상자 기준으로 삼는다. 최초 재현과 환경 정보는 Epic에 있다. [#1863 시스템 오퍼링 Epic](https://github.com/ablecloud-team/ablestack-cloud/issues/1863)

## 구현 순서와 완료 기준

| 우선순위 | 하위 이슈 | 구현 완료 기준 | 31번(GFS2)·32번(RBD) 검증 |
| --- | --- | --- | --- |
| P1 | [#1864 비라우터 생성](https://github.com/ablecloud-team/ablestack-cloud/issues/1864) | 라우터에서만 네트워크 속도를 검증하고 전송한다. | 라우터·콘솔 프록시·2차 스토리지 VM 생성 요청과 저장 유형을 대조한다. |
| P1 | [#1865 복제 유형 보존](https://github.com/ablecloud-team/ablestack-cloud/issues/1865) | 원본 유형을 보존하고 폼의 선택 가능 범위를 서버 계약과 맞춘다. | 지원 유형의 응답·UUID 재조회·유형 검색을 대조하고 유형 미지정 원본을 점검한다. |
| P2 | [#1866 유형·스토리지 필터](https://github.com/ablecloud-team/ablestack-cloud/issues/1866) | 스토리지 조회 API를 수정하고 VM 공통 고급 검색으로 노출한다. | 공유·로컬 반환값과 유형·상태·Zone 조합, 페이지 이동·초기화·상세 복귀를 확인한다. |
| P2 | [#1867 내부 LB·접근 범위](https://github.com/ablecloud-team/ablestack-cloud/issues/1867) | 지원하는 내부 LB 유형과 Zone·도메인 범위를 추가·상세에서 명시한다. | 특정/전체 Zone 및 공개/도메인 조합을 확인하고 UI·API·상태 변경 뒤의 범위를 비교한다. |
| P2 | [#1868 기본·사용 중 오퍼링 상태](https://github.com/ablecloud-team/ablestack-cloud/issues/1868) | 단건과 일괄의 상태 전환 조건과 영향 안내를 일치시킨다. | 기본 상태를 보존하며 전용 시험 자원으로 허용·차단·혼합 일괄 결과를 확인한다. |
| P2 | [#1869 전역 순서](https://github.com/ablecloud-team/ablestack-cloud/issues/1869) | 검색·필터·페이지와 전역 순서 편집의 동작 계약을 정의한다. | 격리한 목록에서 요청 대상과 재조회 순서를 확인하고 실패·부분 처리 응답을 비교한다. |
| P3 | [#1870 로컬·HA 검증](https://github.com/ablecloud-team/ablestack-cloud/issues/1870) | 로컬 스토리지와 HA의 금지 조합을 필드에서 안내한다. | 두 선택 순서에서 요청 차단과 값 보존을 검증하고 허용 조합을 저장한다. |
| P3 | [#1871 VM 표준 UI](https://github.com/ablecloud-team/ablestack-cloud/issues/1871) | Breadcrumb·목록·상세·탭·작업 모달과 값 표기를 공통 UI에 맞춘다. | 라이트/다크와 좁은 화면에서 긴 이름·빈 상태·오류·키보드 조작을 VM과 비교한다. |

생성·복제 결함을 먼저 처리한다. 스토리지 조회 수정 후 검색 선택지를 연결하고, 유형 추가와 범위·상태 변경 정책을 확정한 뒤 목록과 상세에 반영한다. 순서 편집은 표시 행과 저장 대상의 관계를 먼저 정의한다. 마지막으로 각 기능이 연결된 화면을 VM UI와 비교해 라이트·다크 및 좁은 화면에서 확인한다.

## 공통 추가 모달과 서로 연결되는 작업

시스템 오퍼링 **추가** 창의 고정 Header/Footer, 전체 화면 중앙 정렬, 본문 스크롤, 공통 다크 표면은 기존 #1212에서 추적한다. 생성의 유형별 입력·범위·저장과 조합 검증은 #1864·#1867·#1870에, 목록·상세·탭 및 편집·복제·상태 변경 창은 #1871에 기록했다. [#1212 서비스 오퍼링 추가 공통 모달](https://github.com/ablecloud-team/ablestack-cloud/issues/1212)

## 클러스터 검증 기록

31번과 32번에서 구분 가능한 시험 오퍼링을 사용한다. UI 경로와 API 요청, 반환 UUID, 저장 값, 상태, 테마를 기록하고 검증 뒤 시험 오퍼링의 활성 개수가 0인지 확인한다. 기본 오퍼링의 현재 상태는 검증 전후에 비교한다.

영향이 큰 기본 오퍼링 상태 변경과 전역 순서 변경은 전용 시험 오퍼링 또는 격리 목록에서 검증한다. 실패·지연 응답을 제어하는 시험은 실제 저장 결과를 확인하는 시험과 구분해 기록한다. 공통 모달은 #1212 검증 결과를 참조하고, 이번 작업에서 바뀐 필드 및 요청에 회귀 범위를 집중한다.

작업 기준은 upstream `ablestack-europa`의 `9a6072c27fe5c7230974dd89cd485e2f954f24c7`이며 작업 브랜치는 `dhslove:dhslove/epic-1863-system-offering`다.
