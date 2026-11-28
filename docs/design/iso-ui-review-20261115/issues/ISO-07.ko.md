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

우선순위: **P2** · 개발 순서: 7/8

선행 이슈: [ISO-02 #1667](https://github.com/ablecloud-team/ablestack-cloud/issues/1667), [ISO-03 #1668](https://github.com/ablecloud-team/ablestack-cloud/issues/1668), [ISO-04 #1669](https://github.com/ablecloud-team/ablestack-cloud/issues/1669)

## 문제와 근거

추출 결과는 영문 원문 링크로 표시되고 비부팅 Ready ISO의 VM 생성 버튼은 이미지 유형이 잠긴 빈 목록으로 연결된다. 기존 VM ISO 공통 연결/해제와 목적에 맞는 미디어 선택을 일관되게 연결해야 한다.

[실제 UI 검토·API 대조·화면 근거](https://github.com/dhslove/ablestack-cloud/blob/7385b9781d3319f5ba0ca1b651d41b7e9481e24b/docs/design/iso-ui-review-20261115/README.ko.md)

## 구현 설계

1. 다운로드는 대상·진행·실패·다시 준비·파일 받기 버튼을 한국어로 표시하고 추출 완료와 실제 파일 수신을 구분한다. 일회 URL을 로그/문서에 남기지 않고 기존 인증서/브라우저 보호를 유지한다.
2. 부팅 가능한 ISO는 VM 생성 위저드의 Zone/arch/OS 조건과 일치하게 전달한다. 비부팅 데이터 ISO는 빈 OS 선택 위저드로 보내지 않고 지원되는 기존 VM 미디어 연결 경로 또는 불가 사유를 제공한다.
3. VM 관련 #1124/#1157의 공통 작업/폴링을 재사용한다. ISO 목록/상세와 VM ISO 탭·작업 메뉴에 별도 attach/detach 구현을 만들지 않는다.
4. 같은 ISO 이미 연결됨, 다른 미디어, 단일/다중 장치, host 한도·ConfigDrive·권한·VM 상태를 기존 계약대로 처리하고 기존 템플릿/볼륨/스냅샷 생성 흐름을 보존한다.

## 변경 대상

- `ui/src/config/section/image.js`
- `ui/src/views/image/IsoZones.vue`
- `ui/src/views/compute/AttachIso.vue`
- `ui/src/views/compute/VmIsoManager.vue`
- `ui/src/views/compute/VmIsoTab.vue`
- `ui/src/utils/vmIsoActions.js`
- `ui/src/utils/detachIsoBatch.js`
- `ui/src/views/compute/wizard/TemplateIsoSelection.vue`

## 완료 조건

- [ ] URL/로컬로 등록한 전용 ISO를 추출해 실제 파일 크기와 SHA-256을 비교한다. 서버 job 완료만으로 다운로드 성공을 표시하지 않는다.
- [ ] 비부팅 ISO의 VM 생성 막힌 경로가 없어지고 부팅/데이터 미디어 선택과 불가 사유가 명확하다.
- [ ] 전용 VM에서 ISO 연결/해제·이미 연결됨·장치 한도·권한/상태 오류와 목록/상세/VM 탭 결과를 실제 UI로 검증한다.
- [ ] 실제 설치/부팅 검증을 데이터 전용 ISO의 등록/연결 검증과 구분하고 기존 사용자 VM을 변경하지 않는다.
- [ ] 사용한 VM/볼륨/ISO·임시 다운로드 준비 자원을 정리하고 실제 파일 수신 근거를 이슈/PR에 남긴다.

## 테스트 준비

- 기존 vmIsoActions/detachIsoBatch/VmIsoManager/VmIsoTab/AdditionalIsoSelection 회귀 및 ISO→VM 진입 분기 테스트.
- 브라우저 자동화에서 파일 수신/키보드 기본 동작을 검증할 수 없으면 사용자의 수동 확인 결과를 명확히 구분해 기록한다.

## 배포 범위

원칙적으로 UI 자산만. VM 공통 소비 코드 변경이 있으면 관련 컴포넌트만 포함하고 실제 호스트/agent 코드가 달라지면 별도 원인·모듈·호환성 검증을 명시한다.

## 공통 검증·배포·종료 조건

- 기준: 로컬/upstream ablestack-europa `1046a5dc3f21cb9cca0ab6881dd906d64b591e80`. 구현 착수 때 다시 fetch하고 로컬 기준 브랜치와 upstream의 `0 0`을 확인한다. 원격 upstream에 직접 push하지 않는다.
- UI 기능은 실제 Mold UI 조작으로 확인하고 API/저장 상태를 대조한다. 단위 테스트·소스 분석·job 접수만으로 실제 UI 성공을 대신하지 않는다.
- 가상머신의 공통 목록/breadcrumb/ResourceLayout/InfoCard/탭 콘텐츠·MoldDialog 및 테마 토큰을 재사용한다. 이름은 주요 정보로 우선 폭과 한 줄 ellipsis/전체 tooltip을 제공한다. 상시 설명 배너를 추가하지 않는다.
- 13번에는 필요한 변경 산출물만 배포하고 config.json·WEB-INF·운영 설정과 이전 자산/복구본을 보존한다. 이전 검토의 index hash를 현재 배포의 기준으로 재사용하지 않고 배포 직전 현재 상태를 기록한다.
- 전용 시험 자원은 ID로 추적하고 UI로 정상 정리한다. 인증값·API secret·서명·일회 URL은 파일/이슈/PR에 저장하지 않는다. 기존 자원을 변경하거나 전역 timeout/DB 상태 변경으로 검증을 대체하지 않는다.
- 변경 경로 lint/unit/build와 기능 UI 검증·정리 결과를 이슈/구현 PR에 갱신한 후 종료한다. 기존 전체 CI 부채 #926·#1044는 별도이며 신규 실패를 그 부채로 분류하지 않는다.
- 이번 문서는 구현 준비다. 아래 완료 조건은 미실행이며 기능 구현/제품 배포를 완료했다는 뜻이 아니다.

준비 문서 PR: https://github.com/ablecloud-team/ablestack-cloud/pull/1665
