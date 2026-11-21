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

# #1778 VPN 사용자 조회 범위 및 고급 검색 검증

2026-11-21 KST에 31번 GFS2와 32번 RBD의 실제 Mold UI에서 조회 범위, 검색, 목록·상세 탐색 및 권한 전환을 검증했다. 브라우저의 `listVpnUsers` 요청과 화면에 표시된 행을 함께 확인했다. [하위 이슈 #1778](https://github.com/ablecloud-team/ablestack-cloud/issues/1778) · [통합 PR #1782](https://github.com/ablecloud-team/ablestack-cloud/pull/1782)

## 구현

- 현재 조회 범위를 기본 보기·선택 프로젝트·모든 프로젝트로 표시한다. 화면의 프로젝트 스위치와 헤더 프로젝트 선택을 연결했다.
- VM과 같은 고급 검색 방식으로 사용자 이름 정확 일치, 도메인 및 해당 도메인의 계정을 제공한다. 일반 검색창에는 이름 일부 또는 UUID를 입력할 수 있다고 안내한다. 프로젝트 범위와 역할 권한에 따라 선택 가능한 필터가 달라진다.
- 범위나 검색 기준을 변경하면 첫 페이지로 돌아간다. 상세 링크와 breadcrumb는 목록의 범위, 조건, 페이지를 복원한다. 사용자와 역할이 바뀌면 이전 선택과 조회 상태를 지운다.
- 서버의 `listVpnUsers`에서 이름과 UUID 키워드 조건을 묶어 소유 범위와 사용자 상태 조건이 함께 적용되도록 수정했다. 지연된 응답을 무시하고 정상 빈 결과와 조회 오류를 구분한다.

## 실제 UI 결과

| 시험 | 양쪽 클러스터의 관측 결과 |
| --- | --- |
| 부분 검색 및 사용자 이름 정확 일치 | 계정 두 개와 프로젝트 두 개에 동일하거나 유사한 이름을 만들었다. 기본 보기에서 부분 검색은 계정 사용자 3건, 정확 일치는 2건이었다. 같은 이름의 프로젝트 사용자는 모든 프로젝트 또는 해당 프로젝트에서 표시됐다. |
| 도메인·계정 필터와 초기화 | 도메인을 선택한 뒤 계정 선택이 활성화됐다. 키워드·정확한 이름·도메인·계정을 조합하면 해당 계정의 1건이 표시됐다. 재설정하면 검색어와 필터가 지워지고 첫 페이지가 다시 조회됐다. |
| UUID 및 빈 결과 | UUID로 해당 사용자 1건을 찾았다. 일치 대상이 없으면 ‘데이터 없음’이 표시됐다. |
| 모든 프로젝트 및 특정 프로젝트 | 기본 보기에서 행을 선택한 뒤 모든 프로젝트로 전환하자 선택이 해제됐다. UI 요청은 `projectid=-1`과 `listall=false`를 보냈다. 테스트 접두어를 검색하면 프로젝트 소유 3건이 표시됐다. 헤더로 특정 프로젝트를 선택하면 그 프로젝트 2건, 이름 정확 일치를 적용하면 1건이 표시됐다. |
| 페이지와 상세 왕복 | 모든 프로젝트의 검색 결과에서 2쪽으로 이동했다. 상세는 범위와 UUID로 조회했고 breadcrumb를 누르면 검색어·범위·2쪽이 복원됐다. 고급 필터를 변경한 뒤에는 1쪽이 조회됐다. |
| 지연 응답과 오류 처리 | 브라우저에서 이전 요청 응답을 지연시켜 최신 결과가 유지되는 것을 확인했다. 조회 한 건에 HTTP 503을 주입하자 오류와 재시도 버튼이 표시됐고, 재시도는 실제 서버의 결과 3건을 복원했다. |
| 세션 및 권한 전환 | 목록에서 행을 선택한 뒤 같은 브라우저에서 조회 권한을 제한한 사용자로 재로그인했다. 이전 계정·도메인 조건과 선택이 사라졌다. 자신의 행만 조회됐고, 사용자 이름 필터는 남았다. |

클러스터별로 만든 VPN 사용자 6개, 프로젝트 2개, 계정 2개, 역할 1개를 모두 정리했다. 전후 기본 보기와 모든 프로젝트의 기존 VPN 사용자 UUID 집합이 일치했다.

## 빌드와 배포

- WSL ext4에서 변경 UI 파일 ESLint, 관련 UI 단위 테스트 44개와 생산 UI 빌드가 통과했다. Maven `server` 모듈을 빌드했고 `RemoteAccessVpnManagerImplTest` 16개가 통과했다.
- UI 소스는 `6e45d7a1f17efe7c824e244851ca21fde49ac4df`다. 양쪽 브라우저에서 `app.57664c6e.js`를 로드했다. 같은 정적 파일 721개를 배포했다. 패키지 SHA256은 `e26d2eb5e97204e9fc38f55d0b00f7f3cbbc9268acbefc819e60f69fd37446ca`다.
- 서버 소스는 `0783ae20c60573573a2b755f10ac2c2f88e992fc`다. 관리 JAR의 VPN 조회 구현 클래스 한 개를 교체했고 나머지 JAR 항목의 내용이 일치함을 확인했다. 양쪽에 같은 클래스 SHA256 `30a7731ef1ef8152b7fe0ba8235fab576bd2861b95811f2e2afbce7c35475835`가 적용됐다.
- 양쪽의 `/client/` HTTP 200, 활성 `WEB-INF` 및 런타임 설정 보존을 확인했다.

다음 구현 항목은 VPN 사용자 추가 폼의 소유 범위와 한국어 인라인 검증이다. [#1779](https://github.com/ablecloud-team/ablestack-cloud/issues/1779)
