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

상위 Epic: https://github.com/ablecloud-team/ablestack-cloud/issues/1664

우선순위: **P2** · 개발 순서: 5/8

선행 이슈: [ISO-01 #1666](https://github.com/ablecloud-team/ablestack-cloud/issues/1666)

## 문제와 근거

검색/공유 필터에서 상세로 갔다가 breadcrumb로 복귀하면 조건이 사라진다. 필터 칩이 내부 번역 키/값으로 중복 표시되고 이름보다 부가 열이 과도하게 넓어 긴 이름·한 줄 정보가 읽기 어렵다. API의 유용한 검색 조건도 빠져 있다.

[실제 UI 검토·API 대조·화면 근거](https://github.com/dhslove/ablestack-cloud/blob/7385b9781d3319f5ba0ca1b651d41b7e9481e24b/docs/design/iso-ui-review-20261115/README.ko.md)

## 구현 설계

1. ISO 전용 allowlist로 keyword/q, isofilter/filter, 고급 검색·tags, page/pagesize·허용된 정렬을 URL 상태로 직렬화한다. 소유자/역할/프로젝트 경계가 바뀌면 이전 목록 상태를 재사용하지 않는다.
2. bootable/isready/ispublic/hypervisor/userdataid를 실제 API 메타데이터로 허용되는 범위에 추가한다. 관리자 storageid/imagestoreid와 기존 검색 의미를 유지하고 미지원 값·잘못된 UUID/boolean을 검증한다.
3. ISO 이름을 주요 열로 넓게 배정하고 이름·상태·OS·arch 등 한 줄 정보는 nowrap/ellipsis/전체 tooltip으로 표시한다. 긴 이름으로 표 전체가 확장되지 않게 하며 열 선택·페이지·서버 count를 보존한다.
4. 필터 칩은 하나의 한국어 표현만 표시한다. 키워드 부분 검색/고급 이름 정확 일치와 현재 페이지 정렬의 의미는 필요한 필드 도움말에서만 안내하고 breadcrumb 아래 상시 설명 배너를 추가하지 않는다.
5. 공통 ListView/ResourceView 변경은 ISO 경로에 한정된 분기로 적용하고 템플릿·VM·최근 VPC/offering/privategw 경로의 복원/탭 처리를 회귀 확인한다.

## 변경 대상

- `ui/src/config/section/image.js`
- `ui/src/components/view/ListView.vue`
- `ui/src/components/view/ResourceView.vue`
- `ui/src/components/view/InfoCard.vue`
- `ui/src/components/view/SearchForm.vue`
- `ui/src/utils/templateSearch.js (범위/복원 규약 참고)`
- `ui/src/utils/isoModel.js 또는 ISO 목록 전용 helper`

## 완료 조건

- [ ] 키워드·공유·고급 검색·페이지 크기를 바꿔 상세/Zone에 갔다가 breadcrumb·뒤로가기에서 정확히 복원된다.
- [ ] 계정·권한·프로젝트 변경 후 다른 소유자의 조회 상태가 재사용되지 않는다.
- [ ] 추가 검색 조건이 서버 API/count/page와 일치하고 내부 번역 키·중복 필터가 표시되지 않는다.
- [ ] 긴 이름과 부가 정보가 한 줄 ellipsis로 보이며 hover/키보드로 전체 값을 확인할 수 있다.
- [ ] 일반·다크/넓은·좁은 화면 및 템플릿·VM·VPC 공통 목록 회귀를 완료한다.

## 테스트 준비

- 계획: ISO 목록 query/return helper 단위·ListView/SearchForm/ResourceView 통합 회귀.
- 현재 페이지와 서버 전체 결과를 혼동하는 정렬/건수 회귀를 추가하고 고급 이름/keyword 차이를 검증한다.

## 배포 범위

UI 정적 자산만. 서버가 지원하지 않는 전체 정렬을 UI가 구현한 것처럼 표시하지 않는다.

## 공통 검증·배포·종료 조건

- 기준: 로컬/upstream ablestack-europa `1046a5dc3f21cb9cca0ab6881dd906d64b591e80`. 구현 착수 때 다시 fetch하고 로컬 기준 브랜치와 upstream의 `0 0`을 확인한다. 원격 upstream에 직접 push하지 않는다.
- UI 기능은 실제 Mold UI 조작으로 확인하고 API/저장 상태를 대조한다. 단위 테스트·소스 분석·job 접수만으로 실제 UI 성공을 대신하지 않는다.
- 가상머신의 공통 목록/breadcrumb/ResourceLayout/InfoCard/탭 콘텐츠·MoldDialog 및 테마 토큰을 재사용한다. 이름은 주요 정보로 우선 폭과 한 줄 ellipsis/전체 tooltip을 제공한다. 상시 설명 배너를 추가하지 않는다.
- 13번에는 필요한 변경 산출물만 배포하고 config.json·WEB-INF·운영 설정과 이전 자산/복구본을 보존한다. 이전 검토의 index hash를 현재 배포의 기준으로 재사용하지 않고 배포 직전 현재 상태를 기록한다.
- 전용 시험 자원은 ID로 추적하고 UI로 정상 정리한다. 인증값·API secret·서명·일회 URL은 파일/이슈/PR에 저장하지 않는다. 기존 자원을 변경하거나 전역 timeout/DB 상태 변경으로 검증을 대체하지 않는다.
- 변경 경로 lint/unit/build와 기능 UI 검증·정리 결과를 이슈/구현 PR에 갱신한 후 종료한다. 기존 전체 CI 부채 #926·#1044는 별도이며 신규 실패를 그 부채로 분류하지 않는다.
- 이번 문서는 구현 준비다. 아래 완료 조건은 미실행이며 기능 구현/제품 배포를 완료했다는 뜻이 아니다.

준비 문서 PR: https://github.com/ablecloud-team/ablestack-cloud/pull/1665
