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

# Epic #1638 템플릿 개선 구현 설계

상위 Epic: [#1638](https://github.com/ablecloud-team/ablestack-cloud/issues/1638)
준비 기준: 2026-11-13 KST / Europa / base `8ad2d1ecd47092fd3bba8d445fd2c639c7f4be7c`
준비 브랜치: `dhslove/epic-1638-template-ui`

이 문서는 개발 전 계약과 작업 경계를 고정한다. 제품 구현/배포 완료를 뜻하지 않는다. 기존 검토의 재현 근거와 미검증 범위는 Epic 본문을 보존한다.

## 1. 개발 순서와 의존 관계

| 순서 | 작업 | 우선순위 | 필수 선행 | 구현 책임 |
|---|---|---|---|---|
| 1A | [T01 #1639](https://github.com/ablecloud-team/ablestack-cloud/issues/1639) | P1 | 없음 | enum 링크와 유효 상세 라우팅 |
| 1B | [T02 #1640](https://github.com/ablecloud-team/ablestack-cloud/issues/1640) | P1 | 없음 | 상태 정규화/관측 계약 |
| 2A | [T03 #1641](https://github.com/ablecloud-team/ablestack-cloud/issues/1641) | P2 | T02 | 등록·업로드·편집·지원 속성 |
| 2B | [T04 #1642](https://github.com/ablecloud-team/ablestack-cloud/issues/1642) | P2 | T01, T02 | 목록·검색·breadcrumb |
| 3 | [T05 #1643](https://github.com/ablecloud-team/ablestack-cloud/issues/1643) | P2 | T02, T03, T04 | 상세·탭·긴 값·현지화 |
| 4A | [T06 #1644](https://github.com/ablecloud-team/ablestack-cloud/issues/1644) | P2 | T02, T05 | Zone 작업과 사전 준비 |
| 4B | [T07 #1645](https://github.com/ablecloud-team/ablestack-cloud/issues/1645) | P2 | T03, T05 | VNF 검증·NIC·비밀 값 |
| 5 | [T08 #1646](https://github.com/ablecloud-team/ablestack-cloud/issues/1646) | P2 | T01–T07 | 통합 UI/실환경 수용 판정 |

1A/1B, 2A/2B, 4A/4B는 각각 독립 구현 가능한 묶음이다. 이는 개발 배치이며 작업자/에이전트가 실제 병렬 실행 중이라는 뜻이 아니다. 단일 작업자는 T01 → T02 → T03 → T04 → T05 → T06 → T07 → T08 순서로 진행한다. 단위 검증을 T08까지 미루지 않는다.

공통 컴포넌트 충돌 순서는 ListView의 T01 → T04, image.js의 T02 → T03/T04 → T05, TemplateZones의 T02 → T06이다. 동일 파일 수정은 이 순서로 통합한다.

## 2. 현재 소스와 재사용 경계

| 현재 파일 | 역할 / 변경 주의 |
|---|---|
| ui/src/config/section/image.js | 템플릿 columns/details/searchFilters/actions/tabs. 기본 wrapper와 API 권한은 유지 |
| ui/src/components/view/ListView.vue | 유형 값을 상세 경로로 잘못 연결하는 공통 셀. 관계 링크와 enum 구분 |
| ui/src/layouts/ResourceLayout.vue | 공통 breadcrumb·상세 진입/새로고침·작업 표시 |
| ui/src/components/view/SearchFilter.vue | 실제 고급 검색 컴포넌트. 등록한 필터의 데이터 조회·요청 매핑을 확장 |
| ui/src/components/view/InfoCard.vue | VM과 공유하는 요약 카드. 템플릿에 한정된 상태/지원 기능 입력 사용 |
| ui/src/components/view/DetailsTab.vue | 긴 텍스트·불리언·날짜 표시. VM/ISO/볼륨 회귀 필수 |
| ui/src/views/image/RegisterOrUploadTemplate.vue | URL/로컬 등록을 공유. SSVM 인증서·업로드 실패 복구 보존 |
| ui/src/views/image/UpdateTemplate.vue | 이미 MoldDialog size=md 사용. architecture 오타 수정, userdata 부분 성공 유지 |
| ui/src/views/image/TemplateZones.vue | 기존 listRefreshMixin, Zone 선택/복사/삭제. 원본 상태 mutation 제거 |
| ui/src/views/image/TemplateVnfSettings.vue | 이미 NIC draft/busy/focus·stale 응답 보호 존재. 중복 프레임워크를 만들지 않음 |
| ui/src/components/view/MoldDialog.vue | sm 480 / md 720 / lg 960 / xl 1120. formActions/focusGuard/busy 계약 재사용 |
| ui/src/style/theme/tokens.less | 현재 운영 테마의 light/dark 의미 토큰 |
| ui/public/locales/ko_KR.json | 실제 한국어 번역 파일 |
| api/.../PrepareTemplateCmd.java / server/.../TemplateManagerImpl.java | 동기 접수 응답 + 내부 background preload. 완료 job 계약 없음 |

신규 제안 파일은 `ui/src/utils/templateAvailability.js` 하나로 시작한다. 공통 Status 컴포넌트에 템플릿 전용 예외를 계속 추가하지 않는다. 별도 템플릿 요약/Zone 표시가 필요하면 기존 공통 컴포넌트의 slot/표시 입력을 우선 사용하고 영향 범위를 제한한다.

## 3. 상태 모델: 준비 여부와 전송 상태를 분리

### 입력과 출력

제안 함수:
`resolveTemplateAvailability({ record, zoneRows, coverage, targetZoneId })`.

- record: listTemplates가 실제 반환한 필드만 사용.
- zoneRows: templateid별/Zone별 관측 목록. 페이지 일부 또는 권한에 따라 불완전할 수 있음.
- coverage: none / partial / complete. 화면이 전체 Zone을 확인했다는 증거가 있을 때만 complete.
- 출력: availability(ready/unready/unknown), transfer(queued/progress/failed/complete/unknown), presentationKey, reasons, readyZoneIds, scopeLabel.
- 원문 status, downloaddetails.downloadState/downloadPercent/datastoreRole/datastoreId는 보존한다. 메시지에서 URL 자격 증명·쿼리 토큰·VNF 비밀 값은 가린다.
- 요청 자체의 loading/error/stale 상태는 자원 availability와 별개다.

### 서버 enum 기반 해석

현재 `VMTemplateStorageResourceAssoc.Status`:
UNKNOWN, DOWNLOAD_ERROR, NOT_DOWNLOADED, DOWNLOAD_IN_PROGRESS, DOWNLOADED, ABANDONED, LIMIT_REACHED, UPLOADED, NOT_UPLOADED, UPLOAD_ERROR, UPLOAD_IN_PROGRESS, CREATING, CREATED, BYPASSED.

| 입력/관측 | 표시 규칙 |
|---|---|
| isready=true, 관련 저장소 오류 없음 | 사용 가능. 해당 Zone/조회 범위를 표시 |
| isready=false + DOWNLOAD_ERROR/UPLOAD_ERROR/ABANDONED/LIMIT_REACHED | 실패 + 원인. 대기 중으로 축약하지 않음 |
| DOWNLOAD_IN_PROGRESS/UPLOAD_IN_PROGRESS/CREATING | 전송/생성 중. 신뢰 가능한 진행률만 표시 |
| NOT_DOWNLOADED/NOT_UPLOADED | 전송 대기. 요청 접수와 구분 |
| DOWNLOADED/UPLOADED/CREATED인데 isready=false | 전송 완료와 배포 준비 완료를 구분. 준비 상태 확인 중/확인 필요 |
| UNKNOWN 또는 상태 누락 | 상태 확인 필요. 진행/실패/성공을 임의로 추정하지 않음 |
| BYPASSED/directdownload | 직접 다운로드 경로. isready와 지원 가능 조건을 따르고 일반 다운로드 실패로 처리하지 않음 |
| isready=true이면서 다른 저장소 오류 | 사용 가능 + 일부 저장소 오류 경고. 성공/실패 중 하나로 숨기지 않음 |
| 전체 Zone 관측 완료, 일부만 ready | 일부 Zone 사용 가능 + ready/total. 실패 Zone과 배포 가능한 Zone을 구분 |
| 일부 페이지/권한 범위만 관측 | 조회 범위 결과. 전체 Zone 가용성 숫자를 추정하지 않음 |

server가 UNKNOWN을 ERROR_DOWNLOAD_STATES에 포함하더라도 UI에서 확정 원인 없이 다운로드 실패라고 단정하지 않는다. 대표 record의 isready 하나로 모든 Zone의 성공/실패를 판정하지 않는다.

목록에는 N+1 Zone 조회를 추가하지 않는다. 현재 응답이 대표 Zone 데이터만 제공하면 그 범위를 명시하고 상세에서 추가 관측한다. 서버의 집계 확장이 필요하면 버전/지원 계약을 T02에 명시하고 미지원 서버의 fallback을 유지한다.

### 비동기 UI 수명주기

- request generation은 templateid + account/project + zone/filter + 화면 생명주기를 포함한다.
- fetch를 시작할 때 원본 데이터는 보존하고, stale 응답은 무시한다. 조회 실패는 기존 값 + 갱신 실패 안내이며 자원 실패로 변환하지 않는다.
- 등록 POST 성공: 접수됨. 다음 listTemplates 관측: 전송 대기/진행/실패/Ready.
- 새로고침은 조회만 한다. 실패 후 registerTemplate을 자동 재전송하지 않는다.
- 재시도는 실제 서버 경로를 먼저 확인한다. 이 설계의 MVP는 원인 보기/다시 조회이며 임의 retryTemplate API를 추가한 것으로 가정하지 않는다.

## 4. API–폼 계약

| 화면 | API | 필드·의미 |
|---|---|---|
| URL 등록 | registerTemplate | 기존 필드 + sshkeyenabled. checksum/forcks는 기존 조건 유지 |
| 로컬 업로드 | getUploadParamsForTemplate + SSVM 업로드 | checksum/forcks/sshkeyenabled 추가, 파일 선택/전송 오류 복구 유지 |
| 편집 | updateTemplate | arch 초기값, sshkeyenabled/requireshvm 고급 속성. 변경되지 않은 값은 보존 |
| 사용자 데이터 | linkUserDataToTemplate | 기존 선택/정책·부분 성공 분기를 보존. updateTemplate 성공 뒤 link 실패 시 첫 요청을 반복하지 않음 |
| 운영 검색 | listTemplates | isready, hypervisor, forcks, userdataid. 실제 지원 스키마와 UUID 매핑 |
| 복사 | copyTemplate | templateid별 sourcezoneid/destzoneids. 개별 목적지의 최종 관측/오류 보존 |
| 삭제 | deleteTemplate | id + 명시 zoneid + forced. 단일/선택 Zone 작업 단위 |
| 사전 준비 | prepareTemplate | templateid + zoneid + 명시 storageid만 허용 |
| VNF | updateVnfTemplate | 현재 vnfnics/vnfdetails, cleanupvnfnics 등 기존 계약을 재사용 |

`undefined`는 미변경/미지원, `false`는 명시 해제다. 공통 `if(value)`로 false를 누락시키지 않는다. 빈 문자열은 API가 해제를 지원하는 필드만 전송한다. 기존 미노출 필드는 폼 저장으로 지우지 않는다.

체크섬은 MD5 원문 또는 서버가 지원하는 `{SHA-256}`, `{SHA-512}` 등 정확한 알고리즘 표기를 안내한다. UI의 길이/문자 검사는 서버 검증을 대체하지 않는다. checksum을 로그로 임의 비밀 취급할 필요는 없지만 원본 URL 내 자격 증명은 저장하지 않는다.

CKS 선택은 실제 이미지 내용의 적합성을 보장하지 않는다. UI 옵션만으로 CKS 게스트 검증 완료를 표시하지 않는다. 권한은 root admin/owner/project/API capability를 교차 확인하고, server ACL을 최종 권한 검사로 유지한다.

검색의 isready=false는 실패만이 아니라 준비되지 않은 모든 상태다. userdataid는 실제 연결된 UUID 조회를 의미한다. 현재 API에 없는 '연결 없음' 서버 필터는 별도 지원 없이 추가하지 않는다. 정확한 이름 필터와 키워드 검색을 명확히 구분한다.

## 5. 화면·대화상자 명세

### 유지할 운영 UI

- 공통 홈/목록/자원 이름/도움말/업데이트 breadcrumb, 우측 작업 메뉴.
- 좌측 요약 카드, 우측 세로 탭. 모바일에서는 카드/컨텐츠를 세로로 배치하고 탭 접근을 보장.
- 기존 폰트·32px 컨트롤·주 동작 파란색·상태 점·테이블 구분선. 새로운 브랜드/대시보드 디자인으로 바꾸지 않음.
- source token 기준: light page #f5f7fa/surface #fff, dark page #161b22/surface #22282f, border #d9dfe7/#3e4650, link #0969da/#69b1ff. 실제 제품 코드는 하드코딩 복제 대신 `--ui-*` 사용.

### 목록·breadcrumb

- 유형은 USER/VNF/SYSTEM 등의 비탐색 배지. 이름만 자원 상세 진입.
- URL/로컬 등록은 의미가 보이는 레이블. 아이콘만으로 노출되는 부가 동작은 accessible name과 tooltip.
- 필터 변경 시 page=1. 검색/페이지/크기를 검증된 route query로 보존하고 상세에서 목록 복귀 시 복원.
- 소유자·프로젝트 전환 시 이전 selection·지연 응답을 버린다.
- 순서 변경은 sortkey 영속 동작이며 현재 페이지 이름 정렬과 구분한다. 경계/권한/작업 중 비활성 상태를 명시.
- 전체 템플릿 일괄 삭제는 이번 MVP에 추가하지 않는다. 기존 Zone 선택 삭제를 먼저 명확히 한다.

### 상세·탭

- 템플릿 유형 한 번 표시, 이미지 형식(QCOW2)과 구분.
- 해시/UUID/URL은 min-width:0 + overflow-wrap:anywhere. 생략만 하는 툴팁에 전체 값 접근을 맡기지 않음.
- boolean은 예/아니오, 날짜는 기존 locale formatter. source raw 상태는 진단 정보에서 보존.
- 일반 USER/VNF 화면의 전역 런타임 카탈로그는 기본 탭에서 제외하고 도구 링크로 안내한다. Storage Service 용도는 `SHAREDFS` 같은 실제 타입/프로필 근거와 기존 정책을 대조한다. SYSTEM이면 모두 Storage Service라고 추정하지 않음.
- 이벤트/코멘트/설정의 기존 API·권한·로딩/빈 상태를 보존한다.

### MoldDialog

| 용도 | size | 주요 동작 |
|---|---|---|
| URL/로컬 등록, 편집, VNF NIC | md=720 | 본문만 스크롤, footer 고정, busy 시 중복/닫기 방지 |
| 공유/권한/복사/사전 준비 | sm=480 또는 md=720 | 실제 대상/범위 및 빈 대상 이유 |
| 선택 Zone 삭제·결과 | lg=960 | 원본 상태와 작업 상태 분리, 항목별 결과 유지 |
| 해시/URL 전체 보기 | md=720 | 읽기 전용 전체 값·복사 및 키보드 접근 |

formActions/focusGuard는 기존 공통 컴포넌트 계약을 사용한다. 별도 focus trap/중복 busy 상태 프레임워크를 만들지 않는다. 오류는 입력을 유지하고 첫 오류/오류 요약에 접근 가능하게 한다. ESC는 미전송 취소이며 전송 중 자동 취소로 해석하지 않는다.

## 6. Zone 작업과 사전 준비의 특별 계약

### 삭제/복사

원본 관측 `sourceStatus/isready/created`와 `operationStatus(queued/running/succeeded/failed)`를 분리한다. selectedItems에 status='InProgress'를 덮어쓰지 않는다. 작업 모델은 (templateid, zoneid, operationId)로 고정하고 마지막 Zone 제거로 자원 화면이 사라져도 결과를 유지한다.

삭제 확인에는 이름·UUID·선택 Zone·준비·다운로드 상태·강제 삭제 의미가 있어야 한다. 선택이 0개이면 제출 불가. 제출 후 부분 실패는 성공 항목을 재요청하지 않는다. 새로고침으로 결과를 잃지 않도록 최소한 완료 요약/작업 알림과 목록 복귀를 보존한다.

### prepareTemplate의 실제 소스

- PrepareTemplateCmd는 BaseCmd이며 즉시 템플릿 목록 응답을 반환한다. AsyncJobId가 없다.
- TemplateManagerImpl.prepareTemplate은 존재하지 않는 풀/다른 Zone/Down을 엄격히 거부하지 않고 건너뛸 수 있다.
- prepareTemplateInOneStoragePoolInternal은 `_preloadExecutor.execute`로 실제 preloading을 백그라운드에 예약한다. HTTP 성공은 실제 저장소 준비 완료가 아니다.
- storageid 생략 시 여러 풀로 확산되고 DatastoreCluster는 child datastore로 확산된다.

**선택한 MVP 정책**

1. root admin + prepareTemplate/listStoragePools 권한, Ready template, 명시 target Zone/pool을 요구한다.
2. storageid는 필수로 만들고 Up·같은 Zone·지원 하이퍼바이저/풀을 확인한다. DatastoreCluster와 전체 풀은 MVP에서 제외한다.
3. 백엔드에 명시 pool의 존재/Zone/Up/유형 검증을 보완하고 잘못된 명시 대상은 명확한 오류로 거절한다. 레거시 storageid 생략 호출의 범위를 이 UI 변경으로 바꾸지 않는다.
4. 접수 응답 이후 listTemplates의 해당 Zone/downloaddetails에서 **Primary + 선택 datastoreId + DOWNLOADED**를 확인한다. Secondary의 완료를 Primary 완료로 사용하지 않는다.
5. 2초 간격 관측 후 5초 간격으로 완화하고 전체 관측 상한은 120초로 제안한다. 상한은 실패가 아닌 '상태 확인 필요'; 화면 진입/다시 조회로 관측을 재개하며 prepare POST는 자동 재전송하지 않는다.
6. 현재 조회 계약으로 선택 Primary의 상태·실패 원인이 안 보이면 T06에서 서버 관측 보완을 먼저 구현한다. 미관측 상태를 완료로 간주하지 않는다.

이 보완은 API/server 모듈 범위다. 새 DB 테이블은 현 설계에서 요구하지 않는다. 구현 중 스키마 확장이 필요하면 migration/롤백 설계를 먼저 업데이트한다. 저장소 잠금 결함 #1232를 사전 준비 UI로 해결했다고 보고하지 않는다.

## 7. VNF 계약

서버 허용 키는 VERSION/VENDOR/MAINTAINER/ACCESS_METHODS/USERNAME/PASSWORD/SSH_USER/SSH_PASSWORD/SSH_PORT/WEB_USER/WEB_PASSWORD/HTTP_PATH/HTTP_PORT/HTTPS_PATH/HTTPS_PORT를 소스/배포 API와 대조한다. 알 수 없는 서버 확장은 일반 텍스트를 바로 POST하는 우회로 처리하지 않는다.

값 유형·포트 범위·대소문자 정규화를 검증한다. 비밀 값은 기존 값 재표시 없이 '변경 없음'과 '명시 변경'을 구분하고 성공/오류/스크린샷에 노출하지 않는다. 누락된 비밀 값을 빈 값으로 저장하지 않는다.

NIC는 음수/중복 ID/빈 이름, 경계 이동, 마지막 NIC 제거, 관리 NIC/필수 제약을 검증한다. 현재 구현에 이미 있는 draft/busy/focus/수명주기 가드를 유지하며 서버 거부 시 원본과 초안을 보존한다.

## 8. 준비 완료와 후속 착수

- 설계용 목업은 샘플 데이터로만 작동한다. API 연결/제품 배포가 아니다.
- 하위 이슈의 수용 조건과 T08 검증 행렬을 실제 구현 PR의 체크리스트로 사용한다.
- 착수 전 최신 upstream → local Europa 동기화 후 Epic 작업 브랜치를 rebase한다. direct upstream push는 하지 않는다.
- 개발 PR은 필요한 UI/Java 모듈만 변경한다. 초기 준비 PR은 문서·목업·검증 기록으로 한정한다.

## T08 실계정 검증으로 확정한 권한 계약

개인 이미지의 launch grant는 읽기/사용만 허용하며 타인 Operate 권한을 부여하지 않는다. Root·소유자·허용된 프로젝트 소유 자원과 API 권한을 교차 검사한다. DomainAdmin은 허용된 하위 범위를 조회할 수 있어도 타인의 개인 VNF를 수정할 수 없다. 도메인 경로로 수정 권한을 추정했던 T07 주입 문맥 결과는 실제 서버 정책으로 교정한다.

프로젝트에 공유한 개인 이미지는 선택된 프로젝트의 실제 멤버십과 launch grant가 모두 있어야 읽고 사용할 수 있다. 개인 보기·다른 프로젝트·비멤버로 권한이 새지 않는다. 비동기 엔터티 검증 전에 명시한 프로젝트 문맥을 복원하고, 유효하지 않은 프로젝트는 이전 문맥을 재사용하지 않는다. 실제 멤버가 UI에서 개인/프로젝트 VM을 각각 생성하고 공유 제거·reset 후 접근 철회도 확인했다.

일반 사용자의 상세 조회는 관리자용 all 대신 executable/shared를 사용하며 프로젝트·페이지·이전 요청 폐기 계약을 유지한다. 공유 대상은 프로젝트 UUID로 식별한다. [최종 실환경 증거](../../validation/epic-1638-t08-20261114/README.ko.md)를 따른다.
