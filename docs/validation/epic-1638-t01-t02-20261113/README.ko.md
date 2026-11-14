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

# Epic #1638 — T01/T02 구현·13번 배포 검증 결과

2026-11-13 KST · [#1639](https://github.com/ablecloud-team/ablestack-cloud/issues/1639) / [#1640](https://github.com/ablecloud-team/ablestack-cloud/issues/1640) · [PR #1647](https://github.com/ablecloud-team/ablestack-cloud/pull/1647)

T01/T02의 제품 코드를 구현하고 13번 클러스터에 UI 변경분만 배포했다. 실제 브라우저 조작으로 검증했으며 UI에서 발생한 API 응답을 대조했다. T03–T08과 전체 Epic 수용 검증은 이번 완료 범위가 아니다.

## 변경 결과

| 이슈 | 문제와 결과 | 구현 커밋 |
|---|---|---|
| T01 #1639 | USER/SYSTEM 등의 유형은 비탐색 배지로 표시한다. 잘못된 UUID는 상세 API를 호출하지 않으며 삭제·권한·조회 실패는 목록 복귀와 다시 조회 경로를 제공한다. 유효 이름·계정·Zone 관계 링크는 보존한다. | `14a7a03812bf5fe3b54de11191e1340d07d6409e` |
| T02 #1640 | 준비·전송 대기/진행·실패·준비 미확인 상태를 정규화하여 목록/요약/Zone에서 같은 의미로 표시한다. 실패 원인을 본문에 표시하고 관측한 Zone 범위를 명시한다. 등록 접수와 파일 전송 완료 알림은 사용 가능 완료를 의미하지 않는다. | `170a591f8c861dd937ee60462cc6985105dae5dc` |
| T02 보강 | 동일 Zone의 중복 저장소 관측을 합칠 때 준비와 오류를 모두 보존한다. 원인 보기에서 Zone 탭으로 이동하며 세션·역할·프로젝트·자원·요청 세대가 바뀌면 이전 관측을 무효화한다. | `460b33770cb071d869f61c2aac0d81773890ebc3` |

사용 가능한 Zone이 있어도 다른 저장소 오류를 숨기지 않으며, 일부 관측만으로 전체 Zone이 실패했다고 단정하지 않는다. 오류 원문의 URL 인증 정보/쿼리 및 명시적 비밀 값을 제거한다. 실제 서버에 없는 다운로드 재시도 API나 버튼은 추가하지 않았다.

주요 파일: `ListView.vue`, `AutogenView.vue`, `templateAvailability.js`, `TemplateAvailability.vue`, `InfoCard.vue`, `ResourceView.vue`, `TemplateZones.vue`, `RegisterOrUploadTemplate.vue`, `image.js`, en/ko 번역.

## 기능별 검증

| 검증 | 결과와 증거 |
|---|---|
| 유형 배지 | 목록 USER 클릭 후 URL 불변, 유형 문자열 ID로 API를 호출하지 않음. 단위 테스트는 USER/SYSTEM/VNF/SHAREDFS/BUILTIN을 템플릿·VM·VNF 목록에서 검증한다. |
| 잘못된 상세 주소 | `#/template/USER`에서 UUID 유효성 안내·목록 복귀를 표시하고 API 호출 0건. [화면](screenshots/invalid-type-address.jpg) |
| 삭제/권한 범위의 상세 | 유효 형식의 존재하지 않는 UUID에서 로컬 오류와 목록 복귀. 대시보드로 이탈하지 않음. 실제 별도 권한 계정의 403 행렬은 단위 테스트 범위다. [화면](screenshots/missing-template-recovery.jpg) |
| 정상 참조·목록 | Rocky9 이름 상세, admin 관계의 계정 필터 이동, VM 이름 상세와 breadcrumb 복귀, 키워드 검색, 2페이지 21–40건, 이름 열 정렬 후 행 순서 변경 확인. 전역 서버 정렬의 영속 계약까지 검증한 것은 아님. [VM 회귀](screenshots/vm-name-regression.jpg) |
| 동일 실패 개체의 전후 비교 | UUID `437b838b-5543-48e5-8367-899ef4c802da`: 배포 전 목록/요약은 대기 중이고 Zone은 404 오류. 배포 후 목록/요약/Zone 모두 실패와 `HTTP Server returned 404 (expected 200 OK)` 표시. ‘Zone에서 원인 확인’ 선택 시 Zone 탭 활성화. [배포 전](screenshots/before-failed-list.jpg), [배포 후 목록](screenshots/after-failed-list.jpg), [최종 Zone](screenshots/final-failed-zone.jpg) |
| 실제 로컬 업로드 | 16 MiB 빈 QCOW2를 파일 선택기로 전송. 목록·상세·Zone에서 사용 가능 확인. UI의 listTemplates 응답은 `isready=true`, `Download Complete`, 저장소 `DOWNLOADED`, 진행률 100. [응답](ready-api.json), [화면](screenshots/ready-zone.jpg) |
| URL 접수·대기·실패 | 새 URL 등록은 파란 정보 알림 ‘등록 요청을 접수했습니다…사용 가능 여부…확인’ 표시. 직후 이미지 전송 대기이며 Ready가 아님. 이후 SSVM→시험 포트 네트워크 제한으로 `No route to host`/DOWNLOAD_ERROR, 목록·요약·Zone에 실패 원인 표시. [접수 알림](screenshots/registration-accepted.jpg) |
| 조회 실패와 재시도 | listTemplates XHR에만 ConnectionReset을 주입하여 로컬 오류·목록 복귀·업데이트 버튼 확인. 주입 해제 후 업데이트로 기존 정상 상세 복원. [조회 오류](screenshots/scoped-read-failure.jpg), [복구](screenshots/read-retry-recovered.jpg) |
| 지연 응답 | 브라우저 요청 지연 1,200ms 상태에서 실패 개체→정상 개체로 이동. 최종 이름/UUID가 정상 개체와 일치. 임시 네트워크 지연/차단/가로채기는 모두 해제. |
| stale 요청·상태 행렬 | 세션/권한/API capability/프로젝트/자원 전환, 이전 실패 응답, Zone loading 소유권, 중복 Zone 저장소 혼합 상태와 UNKNOWN을 단위 테스트로 검증. 다중 Zone 실환경 검증과 구분한다. |

[UI 검증 기록](ui-verification.json), [UI 발생 API의 선별 기록](api-evidence.json), [시험 개체 목록](test-resources.json).

## 자동 검증과 빌드

- 기능 및 공통 컴포넌트 회귀 **9 suites / 93 tests PASS**. T01 개별 단계는 4 suites / 40 tests PASS. 마지막 실행은 세 제품 코드 커밋을 포함한다.
- 변경된 JS/Vue 파일 ESLint PASS. 새 테스트 파일의 lint 오류는 수정하고 다시 통과했다.
- Node `20.20.2`; `NODE_OPTIONS="--openssl-legacy-provider --max_old_space_size=4096" npm run build` 성공. `DONE Build complete`, postbuild 성공을 확인했다. 기존 공유 Vue 테스트 경고와 webpack 크기 경고를 기능 실패로 계산하지 않는다.
- 제품 코드가 UI에 한정되어 Maven/JAR/DB 빌드·배포는 수행하지 않았다.

재현한 최종 테스트 명령은 `ui` 디렉터리에서 다음과 같다.

~~~sh
npm run test:unit -- --runInBand --silent --coverage=false --forceExit --runTestsByPath tests/unit/utils/templateAvailability.spec.js tests/unit/components/widgets/TemplateAvailability.spec.js tests/unit/views/image/TemplateAvailabilityRead.spec.js tests/unit/views/AutogenTemplateRead.spec.js tests/unit/components/view/ListViewTemplateType.spec.js tests/unit/components/view/ListViewProjectReference.spec.js tests/unit/components/view/ListViewAccounts.spec.js tests/unit/components/view/InfoCardIconLifecycle.spec.js tests/unit/components/view/InfoCardSSHKeyPairVmQuery.spec.js
~~~

## 배포 범위와 보호 확인

- 대상: Mold `10.10.13.10`, `/usr/share/cloudstack-management/webapp`.
- 빌드/배포 코드 SHA: `460b33770cb071d869f61c2aac0d81773890ebc3`. 이후 커밋은 검증 문서·증거 및 기존 준비 문서 정리만 포함한다.
- 정적 파일 **27개** 적용: 새 해시 자산 23개, 기존 공개 파일 4개(`index.html`, `color.less`, `locales/en.json`, `locales/ko_KR.json`). [파일별 SHA-256](deploy-files.json)
- 이전 index SHA-256 `341781ca4f01426f7419c5b0cca8a23990f1149384064b6458f7994009210ff6`가 라이브와 일치함을 먼저 확인했다. index를 마지막에 원자적으로 교체하고 HTTP 200 및 새 SHA-256 `a0ce5b28478232e8521c3236e114efd1dbc09daaead459c69f899c75ccc4734f`를 검증했다.
- live `color.less`가 로컬 이전 산출물과 달라 첫 시도는 **변경 전 중단**했다. 운영 파일과 새 빌드의 변수 767개를 비교하여 전체 동일·팔레트 변경 없음 확인 후 이 파일의 검증 기준만 갱신했다. [테마 비교](theme-review.json)
- `config.json`, `WEB-INF/web.xml` 해시는 배포 전후 동일. 관리 Java PID `2234509` 유지. 백엔드 JAR, DB, Host agent, 방화벽 변경·서비스 재시작 없음.
- 롤백 백업 `/var/tmp/epic1638-ui-backup-je7xlsay` 보존. 기존 해시 자산도 유지했다. 되돌릴 경우 백업의 4개 공개 파일을 복원하고 index는 마지막에 교체한다. 배포 후 다른 변경이 있으면 먼저 현재 해시와 manifest를 대조한다.
- [배포 결과](deployment.json), [최종 27개 일치·보호 파일·PID·HTTP 확인](cleanup-final.json).

## 시험 개체 정리

| 이름 | UUID | UI 최종 확인 |
|---|---|---|
| qa-p1-failed-20261113 | `437b838b-5543-48e5-8367-899ef4c802da` | 일반 삭제 성공 알림, 전체 48→47. 해당 첫 삭제의 네트워크 캡처는 세션 복구 후 재활성화 전이라 job 원문을 저장하지 못함. |
| qa-p1-local-ready-20261113 | `113a7b18-5863-49f5-8bad-9b3e68cff550` | 삭제 job `2b9eee9a-2cc3-4605-b160-dcbab724bf33`: jobstatus=1, success=true |
| qa-p1-slow-20261113 | `98f39a29-deb0-4815-951d-642ef84a541e` | 삭제 job `db918225-c918-415e-8151-a488c4d9f037`: jobstatus=1, success=true |

모두 Zone 탭의 일반 삭제 UI를 사용했다. 강제 삭제·DB 삭제는 사용하지 않았다. 최종 전체 **45→45**, `qa-p1-` 검색 **0건**. [45개 원복](screenshots/cleanup-45-templates.jpg), [시험 잔여 0개](screenshots/cleanup-no-test-resources.jpg).

관리 호스트의 전용 시험 파일 한 개와 디렉터리를 제거했다. PID `2306170`의 cmdline이 해당 전용 경로를 참조하는지 확인한 후 그 프로세스만 종료했고 포트 `18087` 닫힘을 확인했다. 기존 VM·볼륨·나머지 45개 템플릿은 변경하지 않았다. 2차 저장소 GC의 즉시 물리 공간 회수까지 주장하지 않는다. 운영 로그인 비밀 정보는 증거 파일·문서·커밋에 저장하지 않았다.

## 검증 한계와 후속 순서

- 실환경은 admin 및 단일 Zone이다. 일반 사용자/DomainAdmin/프로젝트 ACL과 다중 Zone 혼합·부분 실패는 자동 테스트 계약을 확인했으며 실제 역할/다중 Zone 수용은 T08에 남는다.
- 원격 다운로드 진행률 변화는 시험 포트 접근 제한으로 관측하지 못했다. 실제 접수/대기/실패와 로컬 업로드 Ready를 확인했고 진행률·업로드·누락/상충 상태 행렬은 단위 테스트로 검증했다. 제품의 전송/방화벽을 수정하지 않았다.
- 빈 QCOW2는 OS 이미지가 아니므로 VM 생성·부팅 검증을 의미하지 않는다. UI 공통 관계는 회귀했으며 ISO/볼륨의 전체 기능 행렬·라이트/모바일 통합 수용은 T08 범위다.
- 전역 offline 실험에서는 기존 공통 인증 복구가 로그인으로 이동했다. 다시 로그인한 후 최종 재시도 시험은 템플릿 XHR에만 장애를 주입하여 인증/다른 API와 분리했다.
- 다음 개발은 계획대로 **T03 #1641 → T04 #1642 → T05 #1643 → T06 #1644/T07 #1645 → T08 #1646**. 이번 두 이슈 완료가 Epic 전체 완료는 아니다.

## 기존 CI 부채 분리

- 준비 커밋 `fc392317`의 [License Check](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/43261924100)는 저장소 전체 RAT 미승인 4,079건으로 실패했다. 당시 추가 16개 파일 중 텍스트 5개 AL, 이미지 8개 B, JSON 3개 제외 대상이며 미승인 파일은 그 바깥이다. 기준선은 [#926](https://github.com/ablecloud-team/ablestack-cloud/issues/926)에서 유지한다.
- 준비 커밋의 [Lint](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/43261924305)는 전체 저장소 기존 license·링크·권한·EOF·codespell·flake8·Markdown 문제로 실패했다. 그중 이번 준비 문서 3개의 헤더 및 JSON 2개의 EOF도 지적되어 **우리 파일 5개는 수정**했다. 나머지는 [#1044](https://github.com/ablecloud-team/ablestack-cloud/issues/1044), #926과 분리한다.
- 제품 코드 SHA `460b337`의 [Merge Conflict Check](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/43347334300)는 SUCCESS. 해당 SHA의 전체 License/Lint 재실행 결과는 등록되어 있지 않다. scoped ESLint/93개 단위 테스트/production build 성공을 전체 CI 통과로 표현하지 않는다.
- CI workflow, 저장소 전역 부채 파일은 변경하지 않았다. 이 부채를 T01/T02의 추가 blocked_by로 연결하지 않는다.

## Git 상태

시작 시 fetch upstream → fetch origin → 로컬 `ablestack-europa` 동기화 → 기능 브랜치 재확인을 수행했다. local/origin/upstream base는 `8ad2d1ecd47092fd3bba8d445fd2c639c7f4be7c`, local base↔upstream `0 0`이었다. 기능 브랜치 `dhslove/epic-1638-template-ui`는 origin으로만 push하며 upstream 변경은 PR #1647을 통한다. 이번 요청은 구현·검증·배포·이슈 종료까지이므로 PR 병합은 하지 않는다.
