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

우선순위: **P2** · 개발 순서: 6/8

선행 이슈: [ISO-03 #1668](https://github.com/ablecloud-team/ablestack-cloud/issues/1668), [ISO-05 #1670](https://github.com/ablecloud-team/ablestack-cloud/issues/1670)

## 문제와 근거

Zone은 선택 시 전폭 빨간 버튼 때문에 표가 이동하고 다크 펼침 표에 흰 배경과 원시 상태가 나타난다. 상세 boolean/작은 용량/체크섬, 복사 대상 없음과 삭제 대상·영향 안내도 VM 표준과 차이가 크다.

[실제 UI 검토·API 대조·화면 근거](https://github.com/dhslove/ablestack-cloud/blob/7385b9781d3319f5ba0ca1b651d41b7e9481e24b/docs/design/iso-ui-review-20261115/README.ko.md)

## 구현 설계

1. ResourceLayout/InfoCard와 탭 콘텐츠 프레임을 유지하고 Zone 상단 좌측 작업·업데이트, 우측 검색, 아래 표/페이지를 배치한다. 선택 수/일괄 작업 영역 높이를 유지해 선택 전후 표 위치를 고정한다.
2. Primary/Secondary 저장소 섹션·진행률·Ready/실패/만료 상태를 ISO-01 모델로 표시하고 공통 테마 토큰을 사용한다. 불명확한 색상/원시 enum/아이콘명만의 버튼을 제거한다.
3. 복사 창은 이름·원본 Zone·다중 목적지와 대상 없음 사유를 표시한다. copyIso의 기존 copytemplateresponse 상속 계약을 이름만 보고 바꾸지 않고 접수·job 완료·Zone Ready 및 부분 실패를 분리한다.
4. 삭제는 ISO 이름/ID·선택 Zone·전체 삭제 영향과 개별 결과를 보여준다. 실패/부분 실패 시 현재 목록/선택을 안전하게 갱신하며 마지막 Zone 삭제 뒤 ISO 목록으로 돌아갈 경로를 보존한다.
5. 상세의 boolean을 현지화하고 작은 용량에 적절한 단위를 쓴다. ID/체크섬/URL은 전체 값과 정확한 복사를 제공한다. ISO-02/03/04에서 적용한 표준 대화상자를 다시 별도 스타일로 만들지 않는다.

## 변경 대상

- `ui/src/views/image/IsoZones.vue`
- `ui/src/components/view/DetailsTab.vue`
- `ui/src/components/view/MoldDialog.vue (기존 계약 사용)`
- `ui/src/components/view/MoldDialogActions.vue (기존 계약 사용)`
- `ui/src/components/view/BulkActionView.vue (기존 규약 사용)`
- `ui/src/utils/templateDetails.js (표현 참고)`
- `ui/src/views/image/TemplateZones.vue (최근 적용 참고)`

## 완료 조건

- [ ] 일반/다크에서 표·펼침·선택·hover·오류·disabled·loading의 배경/대비/경계가 일치한다.
- [ ] 선택 전후 표 위치·도구 영역과 페이지가 안정적이고 행/일괄 작업 권한이 일치한다.
- [ ] 1개 Zone의 대상 없음, 다중 Zone의 복사·중복 대상·부분 실패와 단일/일괄 삭제 결과를 구분한다. 실제 다중 Zone 시험은 추가 환경이 없으면 NOT_RUN으로 남기고 기능을 유지한다.
- [ ] 고정 Header/Footer·Body만 스크롤·중복 제출 방지·입력 유지·Enter/Esc/초점 복귀가 VM 표준과 일치한다.
- [ ] 작은 파일 용량, 긴 원문 값, 체크섬 클립보드 실제 붙여넣기와 키보드 이름/툴팁을 UI로 검증한다.

## 테스트 준비

- 계획: IsoZones 도구 영역/모달·상태 표현·삭제/복사 작업 결과 통합 테스트.
- 공통 DetailsTab/MoldDialog 변경이 있으면 VM·템플릿 표준 화면 회귀도 포함한다.

## 배포 범위

UI 정적 자산만. 13번 단일 Zone 검증과 추가 환경에서 필요한 다중 Zone 검증을 구분한다.

## 공통 검증·배포·종료 조건

- 기준: 로컬/upstream ablestack-europa `1046a5dc3f21cb9cca0ab6881dd906d64b591e80`. 구현 착수 때 다시 fetch하고 로컬 기준 브랜치와 upstream의 `0 0`을 확인한다. 원격 upstream에 직접 push하지 않는다.
- UI 기능은 실제 Mold UI 조작으로 확인하고 API/저장 상태를 대조한다. 단위 테스트·소스 분석·job 접수만으로 실제 UI 성공을 대신하지 않는다.
- 가상머신의 공통 목록/breadcrumb/ResourceLayout/InfoCard/탭 콘텐츠·MoldDialog 및 테마 토큰을 재사용한다. 이름은 주요 정보로 우선 폭과 한 줄 ellipsis/전체 tooltip을 제공한다. 상시 설명 배너를 추가하지 않는다.
- 13번에는 필요한 변경 산출물만 배포하고 config.json·WEB-INF·운영 설정과 이전 자산/복구본을 보존한다. 이전 검토의 index hash를 현재 배포의 기준으로 재사용하지 않고 배포 직전 현재 상태를 기록한다.
- 전용 시험 자원은 ID로 추적하고 UI로 정상 정리한다. 인증값·API secret·서명·일회 URL은 파일/이슈/PR에 저장하지 않는다. 기존 자원을 변경하거나 전역 timeout/DB 상태 변경으로 검증을 대체하지 않는다.
- 변경 경로 lint/unit/build와 기능 UI 검증·정리 결과를 이슈/구현 PR에 갱신한 후 종료한다. 기존 전체 CI 부채 #926·#1044는 별도이며 신규 실패를 그 부채로 분류하지 않는다.
- 이번 문서는 구현 준비다. 아래 완료 조건은 미실행이며 기능 구현/제품 배포를 완료했다는 뜻이 아니다.

준비 문서 PR: https://github.com/ablecloud-team/ablestack-cloud/pull/1665
