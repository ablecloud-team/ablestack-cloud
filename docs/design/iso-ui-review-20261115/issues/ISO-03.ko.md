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

우선순위: **P1** · 개발 순서: 3/8

선행 이슈: [ISO-01 #1666](https://github.com/ablecloud-team/ablestack-cloud/issues/1666)

## 문제와 근거

실제 일반 공유 수신자에게 ISO 목록·상세는 보이지만 Zone은 총 1개/행 0개였다. 공유를 제거할 때는 실제 허용한 계정이 삭제 모드 선택 목록에 나타나지 않아 전용 ISO에서 재설정으로만 정리했다.

[실제 UI 검토·API 대조·화면 근거](https://github.com/dhslove/ablestack-cloud/blob/7385b9781d3319f5ba0ca1b651d41b7e9481e24b/docs/design/iso-ui-review-20261115/README.ko.md)

## 구현 설계

1. Zone 조회는 실제 ISO 읽기 권한 범위와 프로젝트를 보존한다. 제한 응답의 downloaddetails/저장소 필드가 없어도 Zone 기본 행과 Ready가 렌더링되도록 ISO-01 모델을 적용한다. 기본 행 가시성과 저장소 세부 정보 권한을 분리한다.
2. listIsoPermissions/listTemplatePermissions의 실제 단일 응답 루트와 검증된 wrapper 형태를 명시적으로 정규화한다. API 오류·잘못된 payload를 정상 빈 허용 목록으로 취급하지 않는다. 허용 계정/프로젝트를 각각 ID/이름 계약대로 처리한다.
3. 현재 공유 현황을 보여주고 추가/제거/초기화를 실제 허용 목록 기준으로 구성한다. 특정 계정 제거가 가능해야 하며 기존 공유를 전체 재설정하는 우회는 완료 기준으로 인정하지 않는다. 초기화는 대상·영향을 확인하고 결과 재조회로 검증한다.
4. 관리자/도메인 관리자/소유자/공유 수신자/미공유/프로젝트/읽기 전용/API 일부 허용에서 상세·행·선택·일괄/제출 조건을 일치시킨다. 시스템 ISO 변경을 실제 시험 대상으로 삼지 않고 판단/서버 거절 회귀는 전용 자원에서 수행한다.
5. 일반 ISO 소유자의 extractable 변경 허용 예외를 보존한다. 공통 컴포넌트 변경은 템플릿의 계정·프로젝트 추가/제거/초기화와 제한 권한 회귀를 포함한다.

## 변경 대상

- `ui/src/views/image/IsoZones.vue`
- `ui/src/views/image/UpdateTemplateIsoPermissions.vue`
- `ui/src/utils/isoModel.js (ISO-01)`
- `api/src/main/java/org/apache/cloudstack/api/BaseListTemplateOrIsoPermissionsCmd.java (응답 대조)`
- `api/src/main/java/org/apache/cloudstack/api/response/TemplatePermissionsResponse.java (응답 대조)`
- `ui/tests/unit/views/image/TemplatePermissions.spec.js`

## 완료 조건

- [ ] 일반 공유 수신자의 Zone 총 건수와 표시 행이 일치하고 저장소 상세 미제공으로 렌더 예외가 발생하지 않는다.
- [ ] 공유 추가 후 수신자가 조회 가능하고 특정 계정/프로젝트만 제거하면 해당 대상의 목록/직접 상세 접근이 차단된다. 다른 허용 대상은 유지된다.
- [ ] 권한 조회 실패/일부 필드 누락 시 명확한 오류·재시도가 있고 오해를 유발하는 빈 목록이나 초기화 성공을 표시하지 않는다.
- [ ] 시스템·소유자·프로젝트·읽기 전용의 상세/행/일괄 작업과 제출 결과가 API 계약과 일치한다.
- [ ] 템플릿 공유 관리 회귀와 실제 13번 계정 UI 검증·생성 자원 정리를 완료한 뒤 종료한다.

## 테스트 준비

- 계획: IsoZones/ISO 권한 통합 회귀 — 저장소 세부 필드 없는 한 행·페이지 경계·늦은 응답·권한 변경.
- 기존 TemplatePermissions.spec.js를 실제 직렬화 계약 fixture로 보강하고 ISO/템플릿 양쪽 추가·특정 제거·초기화를 검증한다.

## 배포 범위

원칙적으로 UI 자산만. 서버 ACL 변경이 필요한 것으로 확인되면 원인·범위·권한 회귀를 별도로 명시하고 필요한 변경 산출물만 포함한다.

## 공통 검증·배포·종료 조건

- 기준: 로컬/upstream ablestack-europa `1046a5dc3f21cb9cca0ab6881dd906d64b591e80`. 구현 착수 때 다시 fetch하고 로컬 기준 브랜치와 upstream의 `0 0`을 확인한다. 원격 upstream에 직접 push하지 않는다.
- UI 기능은 실제 Mold UI 조작으로 확인하고 API/저장 상태를 대조한다. 단위 테스트·소스 분석·job 접수만으로 실제 UI 성공을 대신하지 않는다.
- 가상머신의 공통 목록/breadcrumb/ResourceLayout/InfoCard/탭 콘텐츠·MoldDialog 및 테마 토큰을 재사용한다. 이름은 주요 정보로 우선 폭과 한 줄 ellipsis/전체 tooltip을 제공한다. 상시 설명 배너를 추가하지 않는다.
- 13번에는 필요한 변경 산출물만 배포하고 config.json·WEB-INF·운영 설정과 이전 자산/복구본을 보존한다. 이전 검토의 index hash를 현재 배포의 기준으로 재사용하지 않고 배포 직전 현재 상태를 기록한다.
- 전용 시험 자원은 ID로 추적하고 UI로 정상 정리한다. 인증값·API secret·서명·일회 URL은 파일/이슈/PR에 저장하지 않는다. 기존 자원을 변경하거나 전역 timeout/DB 상태 변경으로 검증을 대체하지 않는다.
- 변경 경로 lint/unit/build와 기능 UI 검증·정리 결과를 이슈/구현 PR에 갱신한 후 종료한다. 기존 전체 CI 부채 #926·#1044는 별도이며 신규 실패를 그 부채로 분류하지 않는다.
- 이번 문서는 구현 준비다. 아래 완료 조건은 미실행이며 기능 구현/제품 배포를 완료했다는 뜻이 아니다.

준비 문서 PR: https://github.com/ablecloud-team/ablestack-cloud/pull/1665
