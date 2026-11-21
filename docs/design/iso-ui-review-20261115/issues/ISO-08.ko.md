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

우선순위: **P1 (최종 완료 게이트)** · 개발 순서: 8/8

선행 이슈: [ISO-01 #1666](https://github.com/ablecloud-team/ablestack-cloud/issues/1666), [ISO-02 #1667](https://github.com/ablecloud-team/ablestack-cloud/issues/1667), [ISO-03 #1668](https://github.com/ablecloud-team/ablestack-cloud/issues/1668), [ISO-04 #1669](https://github.com/ablecloud-team/ablestack-cloud/issues/1669), [ISO-05 #1670](https://github.com/ablecloud-team/ablestack-cloud/issues/1670), [ISO-06 #1671](https://github.com/ablecloud-team/ablestack-cloud/issues/1671), [ISO-07 #1672](https://github.com/ablecloud-team/ablestack-cloud/issues/1672)

## 문제와 근거

개별 수정의 결과를 통합하고 관리자/일반 소유/공유/미공유 외에 도메인·프로젝트·읽기 전용·API 제한을 포함한 실제 계정 행렬을 검증해야 한다. 이번 검토에서 실행하지 않은 환경을 통과로 합산해서는 안 된다.

[실제 UI 검토·API 대조·화면 근거](https://github.com/dhslove/ablestack-cloud/blob/7385b9781d3319f5ba0ca1b651d41b7e9481e24b/docs/design/iso-ui-review-20261115/README.ko.md)

## 구현 설계

1. 우선순위 P1은 Epic 종료 게이트의 중요도다. ISO-01~07 검증 완료 뒤 실행하고 첫 개발 작업으로 착수하지 않는다.
2. 관리자, 일반 소유자, 일반 공유 수신자, 미공유 사용자, 도메인 관리자, 프로젝트 소유/구성원/비구성원, 읽기 전용, API별 일부 허용/거부 실제 계정을 만든다. 목록/직접 URL/Zone/선택/작업/제출/비동기 완료와 권한 회수를 대조한다.
3. 일반/다크, 1440×1000·1280×720·390×844·390×480의 실제 viewport와 긴 값, loading/empty/permission/error/stale·페이지 경계·키보드를 확인한다. 네이티브 창 크기를 정확한 viewport 검증으로 대체하지 않는다.
4. ISO-02의 4.21 및 4.23 실제 업로드/Ready/hash/정리 근거를 해당 커밋/런타임에 연결한다. 이후 업로드 경로 변경·실패가 없으면 불필요하게 SystemVM 재생성을 반복하지 않고, 변경이 있으면 영향 범위를 다시 검증한다.
5. 단일 13-Zone·KVM에서 가능한 결과와 추가 환경이 필요한 다중 Zone·비KVM을 PASS/FAIL/NOT_RUN으로 구분한다. 추가 환경이 없다고 기능을 제거하지 않으며 조정된 완료 범위를 Epic에 명시한다.
6. 생성 자원을 ID로 추적하고 UI 정상 경로로 정리한다. 실패/만료 정리를 위해 전역 timeout 축소·DB 수동 상태 변경을 사용하지 않는다. 기존 사용자 ISO/VM/네트워크/스토리지와 설정을 보존한다.

## 변경 대상

- `docs/validation/iso-1664/ (구현 단계 결과 문서 예정)`
- `ui/tests/unit/views/image/ (관련 회귀)`
- `ui/tests/e2e/ (기존 도구를 사용할 경우)`
- `이슈별 실제 변경 UI/API/server 산출물 목록`

## 완료 조건

- [ ] 실계정 ACL 전체 행렬의 기대/실제와 근거가 있으며 일부 역할 미검증을 전체 통과로 표시하지 않는다.
- [ ] 각 하위 이슈의 기능 검증·배포 자산/커밋·실제 상태가 일치하고 추가 변경의 영향 범위만 재검증했다.
- [ ] 4.21/4.23 양 계열의 업로드 결과가 유효하며 실제 다운로드/클립보드/키보드 확인의 자동/사용자 수동 근거를 구분했다.
- [ ] UI 표준·테마·반응형·접근성과 기존 템플릿/VM/VPC 공통 경로 회귀를 확인했다.
- [ ] 전용 자원 잔여 0 및 기존 자원 ID 보존, 제품/설정 보호 파일·서비스 상태를 확인했다.
- [ ] CI #926/#1044와 신규 실패를 구분하고 하위 이슈/구현 PR/Epic에 결과를 갱신한 뒤에만 이 이슈 및 Epic 종료를 판단한다.

## 테스트 준비

- 통합 행렬/시나리오 명세는 구현 준비 문서에 제공하며 현재 검증 결과는 NOT_RUN이다.
- 변경 경로 lint/unit/build가 먼저 통과해야 하고 13번에는 해당 변경 산출물만 배포한다. 전체 CI의 기존 부채는 별도 추적한다.

## 배포 범위

기존 하위 이슈에서 이미 배포·검증한 동일 산출물은 반복 배포하지 않는다. 통합 과정에서 발견한 필요한 보정만 배포하고 UI에서 최종 확인한다.

## 공통 검증·배포·종료 조건

- 기준: 로컬/upstream ablestack-europa `1046a5dc3f21cb9cca0ab6881dd906d64b591e80`. 구현 착수 때 다시 fetch하고 로컬 기준 브랜치와 upstream의 `0 0`을 확인한다. 원격 upstream에 직접 push하지 않는다.
- UI 기능은 실제 Mold UI 조작으로 확인하고 API/저장 상태를 대조한다. 단위 테스트·소스 분석·job 접수만으로 실제 UI 성공을 대신하지 않는다.
- 가상머신의 공통 목록/breadcrumb/ResourceLayout/InfoCard/탭 콘텐츠·MoldDialog 및 테마 토큰을 재사용한다. 이름은 주요 정보로 우선 폭과 한 줄 ellipsis/전체 tooltip을 제공한다. 상시 설명 배너를 추가하지 않는다.
- 13번에는 필요한 변경 산출물만 배포하고 config.json·WEB-INF·운영 설정과 이전 자산/복구본을 보존한다. 이전 검토의 index hash를 현재 배포의 기준으로 재사용하지 않고 배포 직전 현재 상태를 기록한다.
- 전용 시험 자원은 ID로 추적하고 UI로 정상 정리한다. 인증값·API secret·서명·일회 URL은 파일/이슈/PR에 저장하지 않는다. 기존 자원을 변경하거나 전역 timeout/DB 상태 변경으로 검증을 대체하지 않는다.
- 변경 경로 lint/unit/build와 기능 UI 검증·정리 결과를 이슈/구현 PR에 갱신한 후 종료한다. 기존 전체 CI 부채 #926·#1044는 별도이며 신규 실패를 그 부채로 분류하지 않는다.
- 이번 문서는 구현 준비다. 아래 완료 조건은 미실행이며 기능 구현/제품 배포를 완료했다는 뜻이 아니다.

준비 문서 PR: https://github.com/ablecloud-team/ablestack-cloud/pull/1665
