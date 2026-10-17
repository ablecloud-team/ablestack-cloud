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

# Epic #898 upstream 반영 및 #1269 착수 준비 — 2026-10-11

## 코드 동기화

- origin/upstream을 fetch하고 로컬 ablestack-europa를 먼저 56커밋 fast-forward했다.
- 로컬 기준 브랜치·origin 기준 브랜치·upstream 기준 브랜치는 b274443dadefd9d802f42d136ef04fc85b1c1a25이다.
- Epic epic/898-sharedfs의 520개 커밋을 갱신된 로컬 기준 브랜치 위에 rebase했다. 검증 소스는 c3c07589a0b03ee3fb4634a5d67bf6c1f21b8ea0이다.
- 로컬 기준 브랜치와 upstream 차이0 0, 작업 브랜치에 기준 커밋 포함, 520개 커밋 제목·순서 보존을 확인했다.
- 명시적 충돌은 en/ko 번역 파일의 인접 추가였다. 최종 키·값을 원 기준/기존 기능/upstream의 의미상 3-way 합집합과 비교해 동일함을 확인했다. 다른 공통 파일4개는 자동 병합 결과를 검토했다.
- 변경 충돌이 없던15975개 tree 항목은 기존 작업 내용과 upstream 변경의 합성 결과와 정확히 일치했다. 새 작업 트리 변경341경로는 모두 upstream 변경 경로에 속한다.
- 기존 SharedFSTab.vue·요청 처리·로딩/조회 테스트 소스는 그대로 보존했다.
- 기존 작업 브랜치와 미커밋 변경이 있던 별도 기준 worktree는 각각 로컬 백업 브랜치로 보존했다. 별도 worktree의 HEAD·staged tree·status·unstaged diff·untracked names가 브랜치 이동 전후 동일함을 확인했다.
- 동기화로 추가한 whitespace 진단은 없다. 기존 기능의 테스트파일 EOF 빈 줄1건과 upstream 문서의 EOF 빈 줄1건은 원 기록과 같은 상태로 보존했다.

## 준비 검증

SharedFSInitialLoading, SharedFSReadTransport, SharedFSTab, SharedFSLocale, API optionalRequest/request의6개 suite·123개 테스트가 모두 통과했다. 실패·skip0이다. 관련11파일의 lint --no-fix도 통과했다.

정상 npm run build의 prebuild/build/postbuild 전체를 완료했고 850개 파일을 생성했다. 빌드 대상 소스는 c3c07589a0b03ee3fb4634a5d67bf6c1f21b8ea0, index SHA-256은 613f9429d9716deec7fd1713d1af4325933110d5cc417a1f7b407460c23fa0cf이다. 원 public/config.json을 정확히 보존했고 빌드 종료 후 tracked source clean을 확인했다. 최초 Node20/Webpack ERR_OSSL_EVP_UNSUPPORTED 실패는 따로 보존했으며 재시도에는 프로세스 한정 --openssl-legacy-provider --max-old-space-size=4096를 사용했다. 저장소 의존성이나 Node 설정을 변경하지 않았다.

## 첫 작업 #1269

원487a nfs-test의 기존 세션·목록/직접 URL·새로고침·빠른 탭 전환·정상 로그인 복귀는 이전 실제 검증 기록을 재사용한다. 현재 source rebase와 unit 통과를 실제 UI 인수 완료로 확대하지 않는다.

남은 작업:
1. 실제 부분 조회 실패의 유한한 로딩 종료·공개 원인·정상 정보 유지.
2. 경고/업데이트의 실제 수동 재시도와 정상 복구.
3. 실제 요청 수 계측 및 중복 조회 병합·오래된 응답 격리.
4. 현재 배포 자산·실제 API/UI·light/dark의 최종 재확인.
5. 통합 PR의 리뷰/병합·local base 재동기화는 이슈 닫기 조건으로 별도 기록.

이번 작업은 upstream 반영과 착수 준비다. 새 클러스터 배포·실제 브라우저 인수는0이며 기존 실행 자원과 DATA는 변경하지 않았다. #1269는 OPEN, PR1271은 Draft로 유지한다. 다른 이슈나 최종 UI #1275는 착수하지 않는다. 첫 이슈 처리 결과를 보고한 뒤 다음 #892를 제시하는 순서를 유지한다.
