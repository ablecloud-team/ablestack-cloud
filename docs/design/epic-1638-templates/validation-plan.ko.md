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

# Epic #1638 검증·개발 준비 체크리스트

## 준비 산출물 점검

- [x] #1639–#1646 8개 하위 이슈 및 네이티브 parent 관계.
- [x] 필수 선행 관계를 네이티브 blocked_by로 연결.
- [x] P1 두 개, P2 여섯 개를 제목·본문·우선순위 label에 반영.
- [x] 로컬 base/origin/upstream 동일 SHA 및 깨끗한 작업 트리 확인 후 준비 브랜치 생성.
- [x] 코드별 변경 책임과 API/상태/권한/표시 계약 작성.
- [x] 목업 UI·테마·반응형·주 상호작용 검증 결과를 기록: 21 PASS.
- 준비 커밋·origin push·Draft PR의 최종 연결은 상위 Epic 개발 준비 절에서 확인한다.
- Epic/하위 이슈의 문서·목업·의존표는 최종 보고 시 네이티브 관계와 함께 재검증한다.

## 단계별 변경 검증

| 작업 | 자동 검증 목표 | UI 수용 판정 |
|---|---|---|
| T01 | enum 셀/유효 ID/관계 링크 요청 및 라우팅 | 유형 값이 상세 API를 호출하지 않음, 이름/관계 링크 유지 |
| T02 | 순수 상태 함수·다중 Zone/coverage·stale/조회 오류 | 실패/진행/접수/Ready/Unknown 일관성 |
| T03 | 초기 arch/false/빈 값/미변경·API payload·부분 성공 | URL/로컬/편집 옵션, 실패 복구·초점/ESC |
| T04 | route query/범위 변경/필터 매핑·미지원 API | 검색→상세→복귀/필터 초기화·현재 페이지/전역 정렬 구분 |
| T05 | 상세 필드 중복/boolean·날짜/긴 값/조건 탭 | 320/390/1024/1680px, 양 테마, 전체 값 확인·복사 |
| T06 | 원본/작업 상태 불변·선택/중복·개별 결과·prepare 검증 | 복사 0건·단일/복수/부분 실패·마지막 Zone·Primary 관측 |
| T07 | 허용 키·NIC draft/경계/cleanup·비밀 값 미변경 | 유효/무효 속성, NIC CRUD/이동·서버 거부/재시도 |
| T08 | 기존 공통 소비 컴포넌트 회귀 | 아래 실환경 행렬의 증거 연결 |

권장 단위 검증 명령(구현 단계, ui 디렉터리에서 실행):

~~~sh
npm run test:unit -- --runInBand --runTestsByPath tests/unit/views/image/TemplateVnfSettings.spec.js tests/unit/components/view/MoldDialog.spec.js
npm run test:unit -- --runInBand --runTestsByPath tests/unit/views/storage/VolumeTemplateAllocation.spec.js
npm run lint -- --no-fix
npm run build
~~~

새로 추가하는 테스트 경로는 구현 시 실제 파일명으로 확정한다. 파일이 없는데 위 명령이 전체 통과했다고 보고하지 않는다. 현재 준비 단계는 제품 코드를 바꾸지 않으므로 전체 Maven/UI build를 요구하지 않는다.

T06에서 Java를 바꾸면 API/server의 기존 테스트와 명시 pool 유효성/접수 후 미완료/백그라운드 실패 관측을 추가한다. 필요한 모듈만 `mvn -pl server -am ...`으로 검증한다. 운영 배포 범위를 별도로 기록한다.

## 실환경 수용 행렬

각 행은 PASS / FAIL / NOT_RUN / BLOCKED_ENV 중 하나로 판정한다. 아래는 **미실행 계획**이다. 초기 조사 결과를 새 구현의 PASS로 재사용하지 않는다.

| ID | 실행 내용 | 필수 증거 |
|---|---|---|
| RT01 | URL 등록 → 다운로드 → Ready, 원인 있는 실패 | UI 입력/상태별 화면, 원문 상태/오류, 최종 ID |
| RT02 | 로컬 업로드 → checksum 일치/불일치 → Ready/실패 | 파일 hash, SSVM 전송, 등록/다운로드 최종 상태 |
| RT03 | 속성/arch/false/해제·userdata link 부분 성공/복구 | 요청 payload, 변경 전후 값과 source 내용 보존 |
| RT04 | admin/user/DomainAdmin/project 허용 및 거부 | 각 계정 UI·대상 소유 범위·서버 ACL 결과 |
| RT05 | 다중 Zone 복사·지연·부분 실패·중복 재시도 | source/destination ID, 항목별 최종 결과 |
| RT06 | 단일/복수 Zone 삭제·마지막 Zone·실패 후 결과 보존 | 원본 관측과 operationState 분리, 완료 요약 |
| RT07 | 명시한 Primary 사전 준비·Down/다른 Zone/없음/미지원 | server 거부, 접수와 완료 구분, 선택 Primary DOWNLOADED |
| RT08 | VNF 속성/NIC 추가·편집·이동·마지막 삭제·비밀 값 | 유효성/거부/초안 보존, 마스킹된 증거 |
| RT09 | 볼륨/스냅샷 생성 → 실제 OS 이미지 Ready → VM 생성/게스트 부팅 | UI 생성, 호스트/게스트 실제 부팅, 원본 데이터 보존 |
| RT10 | 추출 → 실제 파일 수신·checksum 대조·만료 | 파일 bytes/hash, API 추출 성공과 파일 수신 구분 |
| RT11 | VM/ISO/볼륨 공통 목록·상세·대화상자 회귀 | 링크/필터/순서/권한·긴 값·접근성 |
| RT12 | 키보드/초점/ESC·320/390/1024/1680px·라이트/다크 | 화면·focus/role·가로 넘침 검사 |
| RT13 | 테스트 개체 정리와 기존 자원 보존 | 생성/삭제 UUID, job 최종 상태, UI/활성 자원 잔여 |

13번에 없는 다중 Zone/비KVM/다른 arch는 적절한 환경을 확보한 후 수행한다. 브라우저 정책으로 실제 파일 수신이 막히면 정책을 우회하지 않고 운영자 검증 또는 허용된 별도 검증 환경으로 기록한다.

## 준비 PR 검증과 제품 검증의 구분

이번 PR에서 실행하는 것은 문서/이슈 관계/목업 로컬 상호작용·레이아웃 검증이다. 운영 Mold의 결함 수정·부팅/스토리지 준비·역할 행렬이 통과했다는 뜻이 아니다.

개발 완료 PR에는 변경 SHA·배포 대상·모듈·스키마 유무·UI 단계·실제 상태·실패/미검증·정리 결과가 있어야 한다. 기반 CI 부채를 이 Epic 기능 검증과 혼합하지 않는다.

## T08 최종 수용 판정 — 2026-11-14

위 행렬은 준비 시점의 계획이다. [실행 결과와 UUID·UI 증거·정리](../../validation/epic-1638-t08-20261114/README.ko.md)를 최종 판정으로 사용한다. 사용자 승인으로 실제 다중 Zone/비KVM/다른 아키텍처는 NOT_RUN_SCOPE_ADJUSTED이며 기능은 유지했다. 실제 계정 8개와 Root의 ACL, 4개 OS 부팅 경로, 파일 수신, 양 테마·4개 너비·native 키보드, 공통 UI 및 전체 시험 자원 정리를 완료했다. 미해결 필수 항목은 없다. 선행 T03의 4.21→4.23 재생성·업로드 검증과 T06/T07 계약 검사는 해당 완료 보고서와 구분해 연결한다. 기존 CI 부채 #926/#1044는 OPEN 유지한다.
