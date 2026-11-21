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

우선순위: **P1** · 개발 순서: 2/8

선행 이슈: [ISO-01 #1666](https://github.com/ablecloud-team/ablestack-cloud/issues/1666)

## 문제와 근거

로컬 파일+사용자 데이터 APPEND 등록 시 getUploadParams 접수 뒤 잘못된 iso[0].id 접근으로 파일 업로드가 시작되지 않고 ISO가 남았다. MD5를 입력한 일반 로컬 업로드는 Ready지만 checksum이 상세에 없었고 서버 IsoUploadParams 전달에서도 누락 경로가 보인다. URL 필수 문구와 형식 검증도 잘못됐다.

[실제 UI 검토·API 대조·화면 근거](https://github.com/dhslove/ablestack-cloud/blob/7385b9781d3319f5ba0ca1b651d41b7e9481e24b/docs/design/iso-ui-review-20261115/README.ko.md)

## 구현 설계

1. 폼을 MoldDialog의 고정 Header/Footer·Body 스크롤 구조에 맞춘다. URL/로컬 모드의 실제 API 메타데이터와 지원 필드·소유 범위를 분리하고 URL·파일·체크섬 형식/필수 오류를 올바른 필드에 표시한다.
2. 로컬 응답은 postuploadisoresponse.getuploadparams에서 id/postURL/metadata/expires/signature를 읽는다. 접수 ID를 런타임 상태로 보존하고 파일 전송, 서버 Ready 확인, 사용자 데이터 연결 및 선택 정책 적용을 단계별로 완료한다. 단계 이름은 사용자 작업 중심으로 보여주고 서명/헤더/일회 URL을 저장하지 않는다.
3. 파일 업로드/연결 실패 후 같은 등록 ID를 기준으로 재시도·현재 상태 확인·정리를 안내한다. 후속 사용자 데이터 연결 실패는 ISO 성공과 구분하고 새 registerIso/getUploadParams 요청을 자동 반복하지 않는다. 중복 클릭/새로고침·모드 전환·오래된 응답을 차단한다.
4. getUploadParamsForIso가 지원하는 checksum을 IsoUploadParams/profile/SSVM metadata까지 전달·저장·검증한다. 로컬 arch 지원은 추가 API 파라미터 및 profile 전달을 기본 설계로 검토하고, 미지원 서버에서는 적용되는 값/제한을 명확히 안내한다. 기존 호출 호환성과 기본 arch를 유지한다.
5. NotUploaded/UploadInProgress의 취소는 기존 삭제를 강제로 우회하지 않는다. 먼저 안전한 서버 취소 전이 지원을 확인하고, 필요하면 소유자/실제 전송 상태/동시성 검증이 있는 명시적 취소 계약을 추가한다. ABANDONED/실패의 정상 삭제와 TTL 정리를 유지하며 전역 timeout 축소·DB 수동 상태 변경으로 테스트를 통과시키지 않는다.
6. 예상 DB 대상은 기존 vm_template.checksum/arch와 기존 업로드 상태/스토어 참조다. 신규 스키마 변경은 계획하지 않으며 추가가 필요해지면 정확한 테이블/열과 migration을 이슈·PR에 먼저 명시한다. 인증서 신뢰 확인과 기존 4.21 업로드 호환 흐름을 보존한다.

## 변경 대상

- `ui/src/views/image/RegisterOrUploadIso.vue`
- `ui/src/config/section/image.js`
- `api/src/main/java/org/apache/cloudstack/api/command/user/iso/GetUploadParamsForIsoCmd.java`
- `server/src/main/java/com/cloud/template/TemplateAdapterBase.java`
- `server/src/main/java/com/cloud/storage/upload/params/IsoUploadParams.java`
- `server/src/main/java/com/cloud/storage/upload/params/UploadParamsBase.java`
- `server/src/main/java/com/cloud/storage/ImageStoreUploadMonitorImpl.java (취소/만료 계약 확인)`

## 완료 조건

- [ ] URL 및 로컬에서 관리자·일반 소유자의 부팅/비부팅 ISO가 입력값대로 등록되고 실제 Ready까지 확인된다.
- [ ] 로컬+사용자 데이터 APPEND 등 선택 정책이 성공하고, 연결 실패 재시도 시 ISO가 추가 생성되지 않는다.
- [ ] MD5/SHA-256 정상 파일은 checksum 저장·UI 표시·다운로드 파일 크기/hash가 일치한다. 불일치는 Ready/성공이 되지 않으며 오류 및 정리가 가능하다.
- [ ] 지원 arch는 실제 저장 값과 일치하고 미지원 API 파라미터를 조용히 전송/무시하지 않는다.
- [ ] 전송 전 취소, 전송 중 실패, Ready 후 연결 실패, 서명 만료, 중복 제출, 페이지 재방문/새로고침을 검증한다. 시험 자원 잔여 0을 확인한다.
- [ ] 4.21 계열 SystemVM에서 실제 로컬 업로드 성공 후 4.23 계열로 재생성하여 다시 성공해야 이 이슈를 종료한다. 각각의 이미지 버전·검증 커밋·배포 자산·Ready·hash·정리 근거를 남긴다.
- [ ] 기능별 필수 테스트·모듈 빌드와 13번 UI 확인 후에만 종료하며 전체 CI 부채는 별도 유지한다.

## 테스트 준비

- 계획: RegisterOrUploadIso/isoModel UI 회귀 — 응답 id 추출, 후속 단계 실패/재시도/중복·정책 전달/필드 지원.
- 계획: API/server 집중 테스트 — checksum/arch가 cmd→IsoUploadParams→profile→upload metadata로 보존되고 불일치가 실패가 되는지 검증.
- 4.21/4.23 실환경 UI 결과는 ISO-08이 커밋/런타임 일치를 확인해 재사용하고 업로드 관련 후속 변경이 있으면 재검증한다.

## 배포 범위

UI와 실제 변경한 API/server 산출물만. 현재 13번 런타임과 클래스/API 호환성을 확인해 기존 JAR 백업 및 필요한 클래스/정렬된 변경 모듈만 배포한다. 전체 Cloud·호스트 agent 재배포를 하지 않는다. backend 재시작이 필요하면 영향/복구 절차를 결과에 남긴다.

## 공통 검증·배포·종료 조건

- 기준: 로컬/upstream ablestack-europa `1046a5dc3f21cb9cca0ab6881dd906d64b591e80`. 구현 착수 때 다시 fetch하고 로컬 기준 브랜치와 upstream의 `0 0`을 확인한다. 원격 upstream에 직접 push하지 않는다.
- UI 기능은 실제 Mold UI 조작으로 확인하고 API/저장 상태를 대조한다. 단위 테스트·소스 분석·job 접수만으로 실제 UI 성공을 대신하지 않는다.
- 가상머신의 공통 목록/breadcrumb/ResourceLayout/InfoCard/탭 콘텐츠·MoldDialog 및 테마 토큰을 재사용한다. 이름은 주요 정보로 우선 폭과 한 줄 ellipsis/전체 tooltip을 제공한다. 상시 설명 배너를 추가하지 않는다.
- 13번에는 필요한 변경 산출물만 배포하고 config.json·WEB-INF·운영 설정과 이전 자산/복구본을 보존한다. 이전 검토의 index hash를 현재 배포의 기준으로 재사용하지 않고 배포 직전 현재 상태를 기록한다.
- 전용 시험 자원은 ID로 추적하고 UI로 정상 정리한다. 인증값·API secret·서명·일회 URL은 파일/이슈/PR에 저장하지 않는다. 기존 자원을 변경하거나 전역 timeout/DB 상태 변경으로 검증을 대체하지 않는다.
- 변경 경로 lint/unit/build와 기능 UI 검증·정리 결과를 이슈/구현 PR에 갱신한 후 종료한다. 기존 전체 CI 부채 #926·#1044는 별도이며 신규 실패를 그 부채로 분류하지 않는다.
- 이번 문서는 구현 준비다. 아래 완료 조건은 미실행이며 기능 구현/제품 배포를 완료했다는 뜻이 아니다.

준비 문서 PR: https://github.com/ablecloud-team/ablestack-cloud/pull/1665
