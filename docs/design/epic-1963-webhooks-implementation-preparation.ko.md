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

# Mold Webhooks 개선 구현 계획

13번 클러스터의 현행 UI에서 재현한 자동 시험 전송, 필터 페이징, 전송 이력 링크 결함을 먼저 수정한다. 후속 작업은 API와 UI의 기능 차이, 운영 작업의 결과 표시, 가상머신 화면과의 일관성을 다룬다. 세부 재현 기록은 Epic에서 확인한다. [Epic #1963](https://github.com/ablecloud-team/ablestack-cloud/issues/1963)

## 작업 단위와 순서

| 우선순위 | 하위 이슈 | 선행 이슈 | 13번 클러스터 완료 기준 |
| --- | --- | --- | --- |
| P1 | [#1965](https://github.com/ablecloud-team/ablestack-cloud/issues/1965) Webhooks 생성의 자동 시험 전송 제거 | 없음 | 입력·취소 요청 0건, 명시 시험 클릭 1회에 요청 1건 |
| P1 | [#1966](https://github.com/ablecloud-team/ablestack-cloud/issues/1966) Webhooks 이벤트 필터 전체 건수·빈 페이지·키워드 계약 정합화 | 없음 | 21건에서 각 페이지 총 21건, 행 20·1·0건 및 키워드 건수 |
| P1 | [#1967](https://github.com/ablecloud-team/ablestack-cloud/issues/1967) Webhooks 상세 조회와 전송 이력 검색 URL 분리 | 없음 | 부모 UUID 조회 격리, 기존 이력 URL·새로고침·뒤로가기 복원 |
| P2 | [#1968](https://github.com/ablecloud-team/ablestack-cloud/issues/1968) Webhooks 편집에 수신 범위 변경과 소유 문맥 표시 | 없음 | 비활성 웹훅의 범위 저장·재조회와 역할·소유 문맥 |
| P2 | [#1969](https://github.com/ablecloud-team/ablestack-cloud/issues/1969) Webhooks 서명 키 마스킹 및 미변경·교체·제거 처리 | [#1965](https://github.com/ablecloud-team/ablestack-cloud/issues/1965) · [#1968](https://github.com/ablecloud-team/ablestack-cloud/issues/1968) | 키 미변경·교체·제거, 상세 마스킹과 합성 키 시험 |
| P2 | [#1970](https://github.com/ablecloud-team/ablestack-cloud/issues/1970) Webhooks 이벤트 필터 검색·추가·일괄 삭제 운영 UI 개선 | [#1966](https://github.com/ablecloud-team/ablestack-cloud/issues/1966) | 필터 검색·추가·선택 및 전체 제거와 부분 실패 |
| P2 | [#1971](https://github.com/ablecloud-team/ablestack-cloud/issues/1971) Webhooks 전송 이력 기간 필터와 전송 상세 화면 정리 | [#1967](https://github.com/ablecloud-team/ablestack-cloud/issues/1967) | 기간 선택·취소·경계와 행 상세 복귀 시 문맥 복원 |
| P2 | [#1972](https://github.com/ablecloud-team/ablestack-cloud/issues/1972) Webhooks 재전송 결과와 이력 선택·전체 정리 범위 명확화 | [#1971](https://github.com/ablecloud-team/ablestack-cloud/issues/1971) | 수신 결과별 재전송 메시지와 삭제 대상·건수 검증 |
| P2 | [#1973](https://github.com/ablecloud-team/ablestack-cloud/issues/1973) Webhooks 목록·상세·대화상자를 가상머신 UI 표준에 맞춤 | [#1967](https://github.com/ablecloud-team/ablestack-cloud/issues/1967) · [#1969](https://github.com/ablecloud-team/ablestack-cloud/issues/1969) | 정확 이름·UUID, 목록 복귀, 390·768·1280px와 한영 도움말 |
| P2 | [#1974](https://github.com/ablecloud-team/ablestack-cloud/issues/1974) 13번 클러스터 Webhooks 개선분 통합 UI 검증 | [#1970](https://github.com/ablecloud-team/ablestack-cloud/issues/1970) · [#1972](https://github.com/ablecloud-team/ablestack-cloud/issues/1972) · [#1973](https://github.com/ablecloud-team/ablestack-cloud/issues/1973) | 배포 증거, 통합 브라우저 결과, 시험 개체 정리 |

- P1 세 건은 서로 독립적이다. 범위 편집도 별도로 시작할 수 있다. 동일한 생성·편집 폼을 변경하는 서명 키 작업은 자동 시험 전송과 범위 편집을 먼저 반영한다.
- 이벤트 필터 UI는 페이지·키워드 API 계약에 맞춘다. 이력 기간과 행 상세는 이력 조회 문맥을 사용하고, 정리·재전송은 그 기간 및 행 선택을 사용한다.
- 가상머신 UI 표준 적용은 목록·상세 골격과 공통 대화상자에 집중한다. 각 탭의 표와 작업은 해당 이슈에서 적용한다. 통합 검증 후 Epic과 PR에 실제 결과를 연결한다.

## 화면과 API 경계

- 상위 웹훅은 UUID와 현재 소유 범위로 조회한다. 전송 기간, 이력 검색어, 이벤트, 관리 서버, 페이지는 이력에 적용한다. 이전 버전에서 만들어진 이력 링크도 같은 조건으로 열려야 한다.
- 상세·필터·최근 전송 이력의 단일 탭을 사용한다. 필터 추가는 공통 대화상자, 한 건의 전송 상세는 해당 행에서 연다. 길이가 긴 URL, 헤더와 페이로드는 읽을 수 있게 표시하고 선택한 탭과 필터를 목록 복귀 시 복원한다.
- 정리 작업의 확인 범위는 요청이 지원하는 필드와 일치해야 한다. 키워드나 이벤트로 좁힌 이력의 삭제는 선택한 행을 대상으로 한다. 전체 정리는 현재 웹훅과 건수를 확인할 수 있을 때 제공한다.
- 범위 편집과 키 변경은 API가 허용하는 계정·도메인·프로젝트 문맥을 따르고, 사용자의 명시적인 변경 의도를 요청에 반영한다.

## 검증 증거

- 각 이슈의 브라우저 절차를 실행한 뒤 배포 커밋 또는 UI 산출물, 계정 역할·프로젝트 문맥, 화면 결과 및 관련 요청·응답을 기록한다. 비활성 시험 웹훅과 합성 키를 사용하고 실제 수신 결과는 승인된 시험 수신기에서 확인한다. 증거에 키 원문 및 세션 값은 포함하지 않는다.
- 가상머신 목록·상세·볼륨 탭·편집 창을 같은 브라우저의 비교 기준으로 사용한다. 대상 변경에 따라 좁은 화면, 라이트·다크 모드, 키보드 조작 및 한영 도움말을 확인한다.
- 기존 권한·URL 정책과 데이터 갱신 결과를 인수한다. 변경 기능의 통합 확인, 시험 데이터 삭제 및 재조회 결과는 통합 이슈에 기록한다. [기존 웹훅 검증 #991](https://github.com/ablecloud-team/ablestack-cloud/issues/991) · [목록 갱신 #1078](https://github.com/ablecloud-team/ablestack-cloud/issues/1078) · [통합 검증 #1974](https://github.com/ablecloud-team/ablestack-cloud/issues/1974)

## 브랜치와 기준 커밋

- 기준은 `ablecloud-team/ablestack-cloud:ablestack-europa`의 `d88cdbff7a1f18994a3d95f78b2933898521cdf6`이다. 준비 시점의 로컬 기준과 upstream 차이는 `0 0`이다.
- 통합 브랜치는 `dhslove/ablestack-cloud:dhslove/epic-1963-webhooks`다. 초안 PR에서 구현 커밋과 이슈별 검증 결과를 추적한다.
