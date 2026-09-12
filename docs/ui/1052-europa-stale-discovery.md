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

# Europa 이전 세션 선택 요청의 오류 격리 (#1052)

## 코드 설계

- 기준 `93a19d8f09d`의 최신 병합 Europa에서 독립 브랜치를 생성했다.
- `ui/src/utils/request.js` request interceptor가 선택 요청에 store.user.discoveryGeneration을 기록한다.
- 로컬 Axios config에만 기록하며 GET params/POST body에는 추가하지 않는다.
- error interceptor는 cancel 처리 다음, 전역 알림과 Logout보다 먼저 이전 generation을 검사한다.
- 이전 generation의 오류는 401을 포함해 reject만 수행한다. 호출자의 기존 fallback이 처리한다.
- 현재 generation 및 metadata 없는 요청은 기존 401 처리를 유지한다. 일반 API의 정책은 변경하지 않는다.
- store의 기존 세대 증가와 성공 응답 검사(#1050)는 유지한다. Diplo 병합 구현을 참고했으며 API 호출 규약을 가져오지 않는다.

## 회귀 검증

수정 전 추가한 테스트 4건이 실패했다. 이전 세대 0/1의 401과 실제 Axios adapter에서 지연 전달한 401이 Logout을 호출했고 요청 metadata도 없었다.
수정 후 요청·API metadata·선택 탐색 3 suites 29 tests PASS. 현재 401/일반 401 및 취소 동작 보존도 포함한다.
실제 Axios 체인 테스트는 adapter를 제어하는 단위 시험이며 실물 네트워크 경합 재현으로 표현하지 않는다.

## 통합 배포 계획

사용자 요청에 따라 PR 병합 후 WSL ext4에서 UI 및 기존 누적 변경 모듈을 빌드한다. GitHub Actions 빌드를 수동 실행하거나 배포 산출물로 사용하지 않는다.
Cloud 누적 기준은 PR #1022 병합 + #1050 + 본 변경이다. core/schema/KVM/DR/FTCTL/server 변경 클래스와 리소스를 대조한다.
qemu 기준은 main의 PR #56 병합 소스이며 쉘/Python 파일을 직접 배포한다.
13/22/31/32 관리 서버와 12 worker의 설치 hash·DB 요구 테이블·서비스 상태를 확인한다.
백업과 동시 변경 검사를 거쳐 변경 클래스만 교체하며 서버 설정, DB 자료, VM/볼륨과 WEB-INF/META-INF를 보존한다.
클러스터별 UI 로그인/목록/새로고침 및 DR API 가용성을 확인한다. 전체 재해 전환 체인 시험과 구분한다.
배포 결과와 복구 위치는 이슈/PR에 추가한다.
