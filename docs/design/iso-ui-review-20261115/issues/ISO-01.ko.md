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

우선순위: **P1** · 개발 순서: 1/8

선행 이슈: 없음 — 첫 구현 대상

## 문제와 근거

ISO 목록/상세는 ABANDONED를 대기 중/Processing으로 표시하고 시스템 ISO의 상세·행·일괄 작업 조건이 서로 다르다. 소스상 로컬 업로드 노출은 URL 등록 권한에도 종속되고 제한 응답의 선택적 필드를 전제로 하는 계약도 정리되어 있지 않다.

[실제 UI 검토·API 대조·화면 근거](https://github.com/dhslove/ablestack-cloud/blob/7385b9781d3319f5ba0ca1b651d41b7e9481e24b/docs/design/iso-ui-review-20261115/README.ko.md)

## 구현 설계

1. 작은 ISO adapter를 정의한다. URL registerIso와 로컬 getUploadParamsForIso의 파라미터 지원 여부를 API 메타데이터에서 각각 판단하며 서버 버전 문자열만으로 기능을 추정하지 않는다. 구형 서버의 arch 미지원은 명시적으로 표현한다.
2. 서버 원문과 정규화 표시 모델을 구분한다. downloaddetails 누락/비배열, boolean false/문자열 false/미관측 값, 대표 Zone과 전체 Zone 관측 범위를 다룬다. ABANDONED·UPLOAD_ERROR·DOWNLOAD_ERROR는 실패, 진행률 100만으로 Ready를 만들지 않는다.
3. 등록/로컬 업로드/수정/공유/복사/삭제/VM 생성에 API 권한·소유 범위·프로젝트·시스템 ISO·Ready·bootable 조건을 적용할 공통 판단을 정의한다. domainid를 숫자 1과 비교하지 않는다. 판단에 필요한 범위 정보가 없으면 권한을 확대하지 않는다.
4. 상태 모델을 ISO 목록·InfoCard에 연결하고 시스템/소유자 작업 판단을 상세·행·선택에 연결한다. 등록 폼 재작성은 ISO-02, 공유 Zone의 데이터 렌더링/권한 응답 처리는 ISO-03에서 수행한다.
5. 템플릿의 상태/조회 helper는 의미가 공통인 부분만 재사용한다. listTemplates, templatefilter, template 전용 label/설정을 ISO에 그대로 호출하지 않는다. 새로운 전역 UI 프레임워크는 만들지 않는다.

## 변경 대상

- `ui/src/config/section/image.js`
- `ui/src/utils/isoModel.js (신규 예정)`
- `ui/src/components/view/InfoCard.vue`
- `ui/src/components/view/ListView.vue`
- `ui/src/views/image/IsoZones.vue (작업 노출 조건만)`
- `ui/src/utils/templateAvailability.js (기존 동작 재사용 검토)`

## 완료 조건

- [ ] API 지원/미지원과 읽기 전용·소유자·프로젝트·시스템 리소스의 작업 조건 표가 코드와 테스트로 일치한다.
- [ ] Ready=false+ABANDONED는 실패로, Ready=true+선택적 저장소 정보 누락은 Ready로 표시하며 전체 Zone 검증으로 과장하지 않는다.
- [ ] 이름·ID·원래 서버 값과 관련 없는 필드를 변경하지 않고 목록/상세/Zone 작업 판단이 일치한다.
- [ ] API 일부 허용/거부 및 구형 메타데이터에서 예외 없이 동작하고 오류 응답을 정상 빈 데이터로 바꾸지 않는다.
- [ ] 13번 UI에서 만료/실패 및 Ready 상태와 관리자·일반 소유자·읽기 전용 작업 노출을 확인한 결과를 이슈/구현 PR에 남긴다.

## 테스트 준비

- 계획: isoModel.spec.js — 선택적 필드/boolean/권한/capability/ABANDONED/다중 Zone 불완전 관측의 표 기반 회귀.
- 기존 templateAvailability/templateSearch/TemplatePermissions 및 변경 공통 컴포넌트 회귀를 함께 확인한다.

## 배포 범위

UI 정적 자산만 대상. 운영 API/DB 계약을 바꾸지 않는다. 필요한 공통 계약이 서버에서 보완돼야 하면 ISO-02/03의 해당 범위로 명시한다.

## 공통 검증·배포·종료 조건

- 기준: 로컬/upstream ablestack-europa `1046a5dc3f21cb9cca0ab6881dd906d64b591e80`. 구현 착수 때 다시 fetch하고 로컬 기준 브랜치와 upstream의 `0 0`을 확인한다. 원격 upstream에 직접 push하지 않는다.
- UI 기능은 실제 Mold UI 조작으로 확인하고 API/저장 상태를 대조한다. 단위 테스트·소스 분석·job 접수만으로 실제 UI 성공을 대신하지 않는다.
- 가상머신의 공통 목록/breadcrumb/ResourceLayout/InfoCard/탭 콘텐츠·MoldDialog 및 테마 토큰을 재사용한다. 이름은 주요 정보로 우선 폭과 한 줄 ellipsis/전체 tooltip을 제공한다. 상시 설명 배너를 추가하지 않는다.
- 13번에는 필요한 변경 산출물만 배포하고 config.json·WEB-INF·운영 설정과 이전 자산/복구본을 보존한다. 이전 검토의 index hash를 현재 배포의 기준으로 재사용하지 않고 배포 직전 현재 상태를 기록한다.
- 전용 시험 자원은 ID로 추적하고 UI로 정상 정리한다. 인증값·API secret·서명·일회 URL은 파일/이슈/PR에 저장하지 않는다. 기존 자원을 변경하거나 전역 timeout/DB 상태 변경으로 검증을 대체하지 않는다.
- 변경 경로 lint/unit/build와 기능 UI 검증·정리 결과를 이슈/구현 PR에 갱신한 후 종료한다. 기존 전체 CI 부채 #926·#1044는 별도이며 신규 실패를 그 부채로 분류하지 않는다.
- 이번 문서는 구현 준비다. 아래 완료 조건은 미실행이며 기능 구현/제품 배포를 완료했다는 뜻이 아니다.

준비 문서 PR: https://github.com/ablecloud-team/ablestack-cloud/pull/1665
