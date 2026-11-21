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

# #1777 VPN 사용자 삭제 소유 범위 검증

2026-11-21 KST에 31번 GFS2와 32번 RBD의 실제 Mold UI를 검증했다. 두 클러스터는 동일한 UI 소스 `265fe6495ae9930ff191c013a2037b8c5cf18fff`에서 빌드했다. [하위 이슈 #1777](https://github.com/ablecloud-team/ablestack-cloud/issues/1777) · [통합 PR #1782](https://github.com/ablecloud-team/ablestack-cloud/pull/1782)

## 구현

- 상세, 목록의 한 행, 일괄 삭제에서 선택한 행의 UUID·사용자 이름·소유자를 고정한다. 프로젝트는 `projectid`, 계정은 `account`·`domainid`로 요청한다. 프로젝트 행에는 계정 인자를 보내지 않고, 계정 행에는 현재 헤더 프로젝트가 주입되지 않도록 한다.
- 제출 전에 UUID와 소유 범위로 행을 다시 확인한다. 페이지, 세션, 프로젝트 범위 또는 권한이 달라지면 이전 확인을 취소한다. 목록 조회·삭제·작업 조회 권한이 있는 경우 해당 작업을 제공한다.
- 개별 작업은 서버 접수 후 최종 작업 상태를 확인한다. 일괄 작업의 확인창에서 사용자와 소유자별 결과를 볼 수 있다. 일부 실패는 창을 닫을 때까지 유지한다. 작업 ID가 있는 미확인 결과는 같은 작업을 조회할 수 있다.

## 양쪽 클러스터 UI 재현

| 시험 | 관측 결과 |
| --- | --- |
| 기본 보기의 계정 사용자 상세 삭제 | 브라우저의 `removeVpnUser` 요청에 `username + account + domainid`만 포함됐다. 양쪽 `jobstatus=1`과 대상 목록 부재를 확인했다. |
| 헤더에서 특정 프로젝트를 선택한 뒤 상세 삭제 | `username + projectid`만 전송했다. 양쪽 작업이 성공했고 대상이 목록에서 사라졌다. |
| 모든 프로젝트에서 개별 상세·목록 행 삭제 | VM 목록의 ‘프로젝트’ 범위를 켜고 VPN 사용자 화면에서 상세 및 목록 우클릭 삭제를 실행했다. 양쪽에서 각 행의 프로젝트 ID를 보냈고 최종 상태가 `jobstatus=1`이었다. 상세에서 삭제한 사용자와 이름이 같은 기본 계정의 사용자는 남아 있었다. |
| 복수 프로젝트의 동명 사용자 일괄 삭제 | 프로젝트 두 개의 행을 선택하고 목록 우클릭으로 삭제했다. 서로 다른 `projectid`로 두 개의 요청과 성공 작업을 확인했다. 세 번째 프로젝트의 동명 사용자는 남았고 목록이 갱신됐다. |
| 확인 뒤 대상 하나를 먼저 제거한 일괄 삭제 | 시험 제어에서 한 행만 먼저 정리한 뒤 UI의 기존 선택을 제출했다. 사라진 행은 재조회 실패로 표시되고 해당 행에 삭제 요청이 없었다. 다른 프로젝트 행은 정상 삭제됐고 확인창에서 각각 실패와 성공을 표시했다. |
| 작업 접수 후 확정 실패 | 확인한 행을 삭제 접수 직전에 별도 세션에서 제거한 통제 시험에서 원래 소유자로 보낸 작업은 `jobstatus=2 / 431`로 종료됐다. 사용자·프로젝트와 함께 실패가 표시됐으며 화면은 결과를 읽을 수 있도록 확인창을 유지했다. |
| 범위 변경과 읽기 전용 역할 | 상세 확인창에서 브라우저 뒤로 이동하면 창이 닫혔고 삭제 요청은 0건이었다. 기본 User 역할을 복제한 임시 역할에서 `removeVpnUser`만 거부하고 로그인했다. 양쪽 UI에서 소유 행 조회가 가능했고 목록과 상세에 삭제 작업이 표시되지 않았다. |

운영 자원과 구별되는 전용 프로젝트·계정·VPN 사용자를 사용했다. 각 시험에서 만든 자원과 역할을 정리했고, 검증 전후 기본 보기와 모든 프로젝트의 기존 VPN 사용자 UUID 목록이 일치했다. 제한 역할 시험은 기본 User 역할을 복제하여 삭제만 거부한 계정으로 별도 완료했다.

## 빌드와 배포 확인

- WSL ext4 작업 사본에서 변경 파일 ESLint와 단위 테스트 4개 스위트 **80개**를 통과했다. VPN 삭제 대상 매핑·재조회와 Public IP 범위, MoldDialog, S2S 표시 회귀를 포함했다.
- 생산 UI 빌드를 완료했고 양쪽 브라우저에서 `app.d01893fb.js`가 로드됐다. 동일한 정적 파일 721개를 배포했다. UI 패키지 SHA256은 `d849fdb35ef0974560fe1e7488058a300573bfa5a97c9663288aee46c16923c8`이다.
- 배포 전후 양쪽 `/client/` HTTP 200, 활성 `WEB-INF`, 서버 디렉터리 내용과 런타임 설정의 체크섬, Mold JAR 체크섬 및 서비스 PID를 확인했다.
- 다음 구현 항목은 프로젝트 범위 선택과 VM 표준 검색 필터다. [#1778](https://github.com/ablecloud-team/ablestack-cloud/issues/1778)
