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

# #1095 ISO 분리 작업 추적 개선 및 검증

## 변경

기준 Europa `39f798757d5109e6717386680a75cb497b20a91b`에 UI만 수정했다. 서버의 ISO 분리 동작은 변경하지 않았다.

- `jobTracker.js`: jobId별 단일 폴링, terminal 결과 중복/회귀 방지, 15초 조회 제한과 최대 3회 backoff 재시도(3/6/12초), 재조회 및 권한 범위 변경 시 정리.
- `plugins.js`: 비동기 job 결과를 Promise로 반환하며 기존 success/error/catch callback을 유지한다. 무기한 loading을 terminal·오류·취소에서 제거하고, 완료 처리를 event/callback 오류와 분리한다. 라우트 이동으로 조회가 취소되면 재시도하고, 로그인/사용자/프로젝트 범위 변경 시 추적을 종료한다.
- 공통 HTTP 처리기의 네트워크 오류 자동 로그아웃을 background job 조회에 적용하지 않는다. 현재 세션의 실제 401만 기존 로그아웃 경로를 사용하고 이전 권한 범위의 늦은 401은 무시한다.
- 조회 실패는 서버 작업 실패와 구분하여 HeaderNotice와 알림에 결과 확인 필요 상태를 표시한다. 알림 클릭 또는 헤더의 결과 재조회 버튼으로 동일 jobId를 재조회한다. 서버 작업을 다시 실행하지 않는다.
- `DetachIso.vue`/`detachIsoBatch.js`: HTTP 접수 직후 다음 ISO를 제출하던 경로를 작업 완료 기준 순차 실행으로 변경했다. 성공/실패/결과 미확인/미실행을 집계하고 ISO별 결과를 표시한다. 불확실한 결과에서는 남은 ISO를 제출하지 않으며, 성공한 ISO를 재선택하지 않는다.
- 모든 분리 처리 후 VM 상세를 한 번 갱신한다. 개별 job 완료마다 전체 상세 갱신을 요청하지 않는다.
- HeaderNotice에 unknown 표시를 추가하고 기존 상태를 방어적으로 렌더링한다. 새 대화상자/하드코딩한 밝은 배경을 추가하지 않는다.

## 자동 검증

Rocky Linux 9.8 amd64 Docker에서 작업했다.

- UI 전체: 52 suites, 497 tests 통과. 새 job tracker 6개, 배치 4개, 공통 pollJob 알림 수명주기 2개, HTTP 오류/인증 범위 3개를 포함한다.
- 게이트웨이 응답 분류 및 background query의 통신 오류/인증 만료 처리 보완 후 전체 497개를 다시 통과했다.
- 기존 AutogenView 검증은 실제 종결 실패 시에도 상세 데이터를 갱신하도록 바뀐 동작 및 15초 조회 timeout을 반영했다.
- 변경 파일 ESLint 및 git diff --check 확인.
- 회귀 시나리오: 두 job 동시 진행·중복 호출, 조회 오류 후 복구, 재시도 소진 후 재조회, scope 취소 후 늦은 응답, callback 예외, 권한 오류, 완료 기준 순차 ISO 제출, 부분 실패, jobid 없는 응답 및 네트워크/게이트웨이 오류.

## 배포 구성

UI production build만 수행했다. 배포용 worktree는 13번에 있던 #1083/#1086/#1089/#1092 기능을 보존하고 이 PR의 UI 변경을 추가한다. PR 자체에는 #1095 관련 변경만 포함한다.

관리 서버 재시작이나 backend/agent JAR 교체는 하지 않는다. config.json과 WEB-INF 체크섬을 보존하고, 새 정적 파일을 먼저 배포한 뒤 index.html을 마지막에 교체한다. 기존 chunk와 백업을 보존한다.

## 실서버 검증 대상

사용자의 W2025-Base(165)는 변경하지 않는다. 별도로 생성한 `issue1095-iso-detach` (`cbcf646b-48a9-4691-a82e-e4e1eadb91f7`, i-2-166-VM, ablecube3)에 Windows 설치 ISO와 VirtIO ISO를 연결하여 검증한다.

### 정상 분리

브라우저에서 ISO 두 개를 선택해 분리했다. 상단 배치 메시지가 종료되고 `성공 2, 실패 0, 결과 확인 필요 0, 미실행 0`과 ISO별 성공을 표시했다. 상세의 연결 ISO 표시와 ISO 탭이 제거됐고, API `isos=[]` 및 libvirt 두 CD-ROM의 source file 없음과 일치했다. 원본 OS 이미지의 ISO 링크는 생성 원본 정보로 남으며 연결된 미디어를 뜻하지 않는다. 다크 테마에서 결과 알림의 배경과 텍스트 가독성을 확인했다.

### 조회 장애 및 복구

최종 배포 UI의 검증용 브라우저 탭에서 queryAsyncJobResult XHR에만 HTTP 503을 주입했다. 첫 ISO job 조회가 최초 요청과 3회 재시도 후 결과 미확인으로 종료됐고, 로그인 세션을 유지하면서 무한 진행 메시지를 제거했다. 집계는 성공 0 / 실패 0 / 결과 확인 필요 1 / 미실행 1이었다. 두 번째 ISO는 제출하지 않았고 대화상자도 닫혀 불확실한 ISO를 같은 화면에서 재제출할 수 없었다.

조회 장애 주입을 해제한 뒤 경고 알림을 클릭하자 같은 job의 성공을 확인했다. 기존 경고와 배치 집계 알림이 제거되고 VM 상세를 갱신했다. 다시 분리 화면을 열었을 때 실제 남은 VirtIO ISO 1개만 표시됐다. 해당 ISO를 정상 분리한 뒤 진행 메시지와 ISO 탭이 사라졌다. 최종 API는 isos=[]이며 libvirt의 두 CD-ROM 모두 source file이 없었다.

앞선 검증에서는 두 서버 job이 모두 성공했지만 두 번째 조회만 중단되는 상황에서도 무기한 진행 표시가 끝나고 명시적 재조회로 완료 상태가 복구되는 것을 확인했다. 테스트용 네트워크 가로채기는 모두 해제했다.

### 최종 배포 및 정리 (2026-09-15)

- 소스: `18408a38348eff18ceeaa6458b0337fce5ea8728` 및 위의 기존 배포 overlay.
- UI artifact SHA256: `e485e78c9d72727fbd780e43da7f139999f30313007b66cd00ed9154a30a04f9`.
- 서버 백업: `/var/tmp/issue1095-20260915-081054/ui-before.tar.gz`.
- config.json 및 WEB-INF 보존 확인. backend/agent 재시작 없음.
- 전용 검증 VM은 ISO를 모두 분리하고 Stopped 상태로 보존했다. 사용자 VM 165는 변경하지 않았다.
- 최종 UI HTTP 200 확인. 자동 테스트 전체 52 suites / 497 tests 통과.
