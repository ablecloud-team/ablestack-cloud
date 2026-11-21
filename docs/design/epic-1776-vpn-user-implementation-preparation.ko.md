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

# Epic #1776 VPN 사용자 구현 준비

2026-11-21 KST에 fork와 upstream의 `ablestack-europa`가 `94a247b08ed20387aeacd5ed5673af9dab70d733`로 일치하는 것을 확인했다. 통합 브랜치는 fork의 `dhslove/epic-1776-vpn-users`이고 PR 기준은 upstream의 `ablestack-europa`다. [Epic #1776과 31·32 초기 검증](https://github.com/ablecloud-team/ablestack-cloud/issues/1776)

## 구현 순서

| 순서 | 이슈 | 구현 경계 | 우선 확인할 결과 |
| --- | --- | --- | --- |
| 1 | [#1777](https://github.com/ablecloud-team/ablestack-cloud/issues/1777) 개별·일괄 삭제 소유 범위 | `network.js`의 VPN 사용자 액션과 `AutogenView.vue`의 단일·그룹 제출, `request.js` 프로젝트 인자 주입 | 기본/특정/모든 프로젝트에서 선택 행의 소유자만 전송. 계정과 프로젝트의 동명 사용자 및 복수 프로젝트 일괄 선택에서 선택 밖 행 유지. |
| 2 | [#1778](https://github.com/ablecloud-team/ablestack-cloud/issues/1778) 범위와 검색 | `network.js`의 필터, `AutogenView.vue`의 조회 범위·상세 이동, `SearchView.vue`의 공통 입력 | 기본/특정/모든 프로젝트의 현재 범위 표시. 키워드·정확한 이름·도메인/계정 검색과 페이지·상세 왕복. |
| 3 | [#1779](https://github.com/ablecloud-team/ablestack-cloud/issues/1779) 추가 폼 | VPN 사용자 추가 액션, 폼 검증 및 현지화, 비동기 결과 | 프로젝트와 계정/도메인 입력을 상호 배타적으로 구성. 확인창에 표시한 소유자와 실제 요청·응답의 일치. |
| 4 | [#1780](https://github.com/ablecloud-team/ablestack-cloud/issues/1780) 소유자·상태 | 목록 열, 상세 요약과 속성, 단일·일괄 삭제 확인 | #1777에서 확정한 사용자·소유 범위를 각 화면에 표시. `Add`와 `Active`를 구별하고 UUID 복사를 제공. |
| 5 | [#1781](https://github.com/ablecloud-team/ablestack-cloud/issues/1781) VM 표준 | breadcrumb, 목록·상세·탭 콘텐츠, 작업 배치, MoldDialog와 테마 | 선택 작업을 버튼과 키보드로 실행. 390/768/1280px의 두 테마 및 31·32 통합 인수. |

이슈 #1777의 삭제 요청과 결과를 먼저 안정화하고 #1780의 삭제 확인이 같은 대상 데이터를 사용하게 한다. #1778에서 합의한 조회 범위와 #1779의 최종 등록 범위는 사용자가 구별할 수 있게 표시한다. #1781의 화면 기준은 앞선 화면 변경에도 적용한다.

## API와 UI 연결 기준

- **삭제**는 개별·일괄 모두 확인 시점의 행 ID, 사용자 이름, 소유 범위를 고정한다. 프로젝트 행은 실 ID인 `projectid`, 계정 행은 `account`와 `domainid`로 호출한다. `projectid=-1`은 모든 프로젝트 조회에서만 사용한다. 공통 요청 처리기가 헤더의 프로젝트를 자동 주입하는 경로까지 대조한다. 기존 `removeVpnUser` 응답 계약과 역할 권한을 유지하고 접수 뒤 최종 작업 결과로 성공을 확정한다. [VPN 사용자 액션](https://github.com/ablecloud-team/ablestack-cloud/blob/94a247b08ed20387aeacd5ed5673af9dab70d733/ui/src/config/section/network.js#L1301-L1347) · [프로젝트 인자 주입](https://github.com/ablecloud-team/ablestack-cloud/blob/94a247b08ed20387aeacd5ed5673af9dab70d733/ui/src/utils/request.js#L231-L260)
- **조회**는 `keyword`에 이름 부분 일치와 UUID 검색을, `username`에 정확 일치를 사용한다. 도메인과 계정, 명시적인 프로젝트 범위를 API 계약대로 보낸다. 조건을 바꾸면 페이지와 선택을 갱신하고 이전 요청 결과의 뒤늦은 반영을 막는다. 상태 표시는 API의 상태 값에서 매핑한다. 상태 필터를 추가할 경우 먼저 API 지원을 설계한다. [목록 검색](https://github.com/ablecloud-team/ablestack-cloud/blob/94a247b08ed20387aeacd5ed5673af9dab70d733/server/src/main/java/com/cloud/network/vpn/RemoteAccessVpnManagerImpl.java#L708-L746) · [VM 목록·필터](https://github.com/ablecloud-team/ablestack-cloud/blob/94a247b08ed20387aeacd5ed5673af9dab70d733/ui/src/config/section/compute.js#L128-L195)
- **추가**는 현재 프로젝트·선택한 계정과 도메인·기본 계정 중 최종 등록 대상을 제출 전에 확정한다. 서버 규칙은 사용자 이름 3~64자, 비밀번호 3~32자, 두 값의 첫 문자는 영문 또는 숫자다. 나머지 허용 문자 집합은 서버 코드와 일치시킨다. 비밀번호 값은 결과·증적에서 제외한다. [API 소유 범위 인자](https://github.com/ablecloud-team/ablestack-cloud/blob/94a247b08ed20387aeacd5ed5673af9dab70d733/api/src/main/java/org/apache/cloudstack/api/command/user/vpn/AddVpnUserCmd.java#L39-L94) · [서버 입력 검증](https://github.com/ablecloud-team/ablestack-cloud/blob/94a247b08ed20387aeacd5ed5673af9dab70d733/server/src/main/java/com/cloud/network/vpn/RemoteAccessVpnManagerImpl.java#L460-L469)

## 검증 및 배포 기준

- WSL ext4 구현 작업 사본은 `/home/ablecloud/work/dhslove/cloud-vpn-users-1776`이다. UI 변경은 관련 단위 검사와 변경 파일 lint, WSL UI 빌드로 검증한다. 기존 회귀의 시작점은 `AutogenPublicIpListScope.spec.js`, `AutogenActionFocus.spec.js`, `MoldDialog.spec.js`, `S2sGatewayDisplay.spec.js`다. 새 회귀는 각 이슈에서 영향받는 요청·권한·화면 경로를 대상으로 추가한다.
- 31 GFS2·32 RBD에서 새 격리 프로젝트와 전용 사용자로 기본 보기, 특정 프로젝트, 모든 프로젝트 범위를 검사한다. 같은 이름을 가진 행과 서로 다른 프로젝트의 여러 행을 선택해 UI 표시와 요청 인자, 최종 작업 상태, 목록을 비교한다. 유효/무효 입력, 검색 결과/오류, 확정 실패 후 재시도와 선택 밖 행 보존을 확인한다.
- VM 목록·상세·NIC 탭·대화상자와 나란히 비교한다. 해상도·테마, 버튼과 키보드 접근, 포커스 복귀, 바뀐 요청과 작업 결과를 기록하고 시험 자원 정리 후 기존 인벤토리를 확인한다. 기존 VPN 접속·철회·복구와 네트워크 역할 행렬 증적은 [#1569](https://github.com/ablecloud-team/ablestack-cloud/issues/1569)를 사용한다.
- 구현 후 UI 배포 시 활성 `/usr/share/cloudstack-management/webapp`의 정적 자산만 갱신하고 전후 `WEB-INF`·존재하는 `META-INF`·`/client/` HTTP 200을 확인한다. Java 변경이 생길 경우 WSL ext4에서 실제 변경 Maven 모듈만 빌드한다. 전체 Cloud 빌드는 명시적 요청 시 GitHub Actions에서 시작한다.
