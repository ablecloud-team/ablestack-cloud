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

우선순위: **P2** · 개발 순서: 4/8

선행 이슈: [ISO-01 #1666](https://github.com/ablecloud-team/ablestack-cloud/issues/1666), [ISO-02 #1667](https://github.com/ablecloud-team/ablestack-cloud/issues/1667)

## 문제와 근거

상세 x86_64와 달리 편집 arch 초기값이 비고 검색이 동작하지 않는다. updateIso의 bootable은 수정 UI에 없다. 공통 details API가 있다는 이유만으로 VM/템플릿 전용 설정을 ISO에 노출해서는 안 된다.

[실제 UI 검토·API 대조·화면 근거](https://github.com/dhslove/ablestack-cloud/blob/7385b9781d3319f5ba0ca1b651d41b7e9481e24b/docs/design/iso-ui-review-20261115/README.ko.md)

## 구현 설계

1. 현재 arch, boolean false, null/미지원 값을 명시적으로 초기화한다. 선택 검색은 label/value를 사용하고 option.children 내부 구조에 의존하지 않는다.
2. bootable 수정과 OS 유형 필수 조건을 연결한다. 비부팅 데이터 ISO→부팅 ISO 전환은 실제 OS 유형을 선택하게 하고, 영향 있는 OS 변경은 기존 forceupdateostype 안내·확인을 보존한다.
3. 메타데이터 갱신과 사용자 데이터 연결/해제/정책 갱신의 부분 성공을 구분하고 입력·기존 ID를 보존한 재시도를 제공한다. 무변경 값과 false를 정확히 보존한다.
4. 설정은 ISO에 유효한 운영 키·후보 조회·저장/읽기 전용 계약을 먼저 확정한다. 지원이 확인되면 Zone 뒤에 공통 설정 표를 쓰고 저장 값 전체/후보를 구분하며 관련 없는 키를 보존한다. TemplateSettingsTab의 listTemplates/updateTemplate 호출을 ISO에 그대로 사용하지 않는다.
5. VM/템플릿 전용 API 상속 필드만 보인다는 이유로 임의 키 입력이나 VNF/SHAREDFS 탭을 추가하지 않는다. ISO용 유효 설정이 없으면 조사 결과를 명시하고 허위 설정 화면을 만들지 않는다.

## 변경 대상

- `ui/src/views/image/UpdateISO.vue`
- `ui/src/config/section/image.js`
- `ui/src/views/image/TemplateSettingsTab.vue (표 구성 참고)`
- `ui/src/utils/templateSettings.js (맵 보존 규약 참고)`
- `api/src/main/java/org/apache/cloudstack/api/BaseUpdateTemplateOrIsoCmd.java`

## 완료 조건

- [ ] x86_64/aarch64/s390x 현재 값과 false 초기값이 보존되고 존재/부재 검색어가 올바르게 필터링된다.
- [ ] bootable/OS 변경의 필수·미지원·강제 변경 조건이 일치하고 무변경 저장으로 기존 VM 관련 속성이 손실되지 않는다.
- [ ] 사용자 데이터 변경·해제·정책 및 후속 실패의 부분 성공/재시도 결과를 실제 UI로 확인한다.
- [ ] 유효 설정 범위와 채택/미채택 결정을 근거로 기록하고 적용 시 전체 맵·읽기 전용·동시 변경을 보존한다.
- [ ] 기존 MoldDialog 구조와 키보드/초점 동작을 일반·다크에서 확인한다.

## 테스트 준비

- 계획: UpdateISO.spec.js — 초기값/필터/bootable↔OS/false 보존/부분 성공·재시도.
- 설정 UI를 적용하면 ISO 읽기/쓰기 API fixture와 전체 맵 보존·읽기 전용/경합 회귀를 추가한다.

## 배포 범위

UI 자산만. 별도의 설정 API/스키마 변경은 이 이슈의 자동 범위로 넣지 않는다.

## 공통 검증·배포·종료 조건

- 기준: 로컬/upstream ablestack-europa `1046a5dc3f21cb9cca0ab6881dd906d64b591e80`. 구현 착수 때 다시 fetch하고 로컬 기준 브랜치와 upstream의 `0 0`을 확인한다. 원격 upstream에 직접 push하지 않는다.
- UI 기능은 실제 Mold UI 조작으로 확인하고 API/저장 상태를 대조한다. 단위 테스트·소스 분석·job 접수만으로 실제 UI 성공을 대신하지 않는다.
- 가상머신의 공통 목록/breadcrumb/ResourceLayout/InfoCard/탭 콘텐츠·MoldDialog 및 테마 토큰을 재사용한다. 이름은 주요 정보로 우선 폭과 한 줄 ellipsis/전체 tooltip을 제공한다. 상시 설명 배너를 추가하지 않는다.
- 13번에는 필요한 변경 산출물만 배포하고 config.json·WEB-INF·운영 설정과 이전 자산/복구본을 보존한다. 이전 검토의 index hash를 현재 배포의 기준으로 재사용하지 않고 배포 직전 현재 상태를 기록한다.
- 전용 시험 자원은 ID로 추적하고 UI로 정상 정리한다. 인증값·API secret·서명·일회 URL은 파일/이슈/PR에 저장하지 않는다. 기존 자원을 변경하거나 전역 timeout/DB 상태 변경으로 검증을 대체하지 않는다.
- 변경 경로 lint/unit/build와 기능 UI 검증·정리 결과를 이슈/구현 PR에 갱신한 후 종료한다. 기존 전체 CI 부채 #926·#1044는 별도이며 신규 실패를 그 부채로 분류하지 않는다.
- 이번 문서는 구현 준비다. 아래 완료 조건은 미실행이며 기능 구현/제품 배포를 완료했다는 뜻이 아니다.

준비 문서 PR: https://github.com/ablecloud-team/ablestack-cloud/pull/1665
