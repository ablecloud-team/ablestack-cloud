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

# #1780 VPN 사용자 소유자와 상태 표시 검증

2026-11-21 KST에 31번 GFS2와 32번 RBD의 실제 Mold UI에서 각 7개 시나리오를 검증했다. [하위 이슈 #1780](https://github.com/ablecloud-team/ablestack-cloud/issues/1780) · [통합 PR #1782](https://github.com/ablecloud-team/ablestack-cloud/pull/1782)

## 구현

- 목록·상세·삭제 확인은 계정명과 도메인 또는 프로젝트명으로 소유자를 표시한다. 목록과 상세에서 열 수 있는 소유자 관리 링크를 제공한다. 기존 열 설정의 계정·도메인·프로젝트 항목은 소유자 열로 읽는다.
- 상세의 요약에는 사용자 이름을 표시하고 본문에 상태·소유자·복사 가능한 UUID를 배치했다. 삭제 확인은 요청 대상을 고정하는 데 사용하는 같은 데이터에서 이름·소유자·상태를 읽는다. 일괄 확인에는 대상 수를 표시한다.
- API의 `Add`는 ‘적용 대기’, `Active`는 ‘적용됨’, `Revoke`는 ‘철회 대기’로 표시한다. 지원하지 않는 값은 ‘알 수 없음’으로 표시한다.

## 실제 UI 결과

| 시나리오 | 양쪽 클러스터에서 관측한 결과 |
| --- | --- |
| 실제 `Active` 사용자 | 기존 사용자 1명씩을 읽기 전용으로 확인했다. 목록과 상세에서 모두 ‘적용됨’으로 표시됐다. |
| 기본 보기와 계정 사용자 상세 | 같은 이름의 두 계정 소유 행을 계정과 도메인 표기로 구별했다. 관리 링크가 해당 계정명과 도메인 UUID를 가리켰다. 상세의 UUID는 한 곳에 표시됐으며 키보드로 복사한 뒤 붙여 넣은 값이 원본 UUID와 일치했다. |
| 일반 사용자 | 자신의 계정 소유 정보를 평문으로 확인했다. 계정 및 도메인 관리 링크가 표시되지 않았다. |
| 선택 프로젝트와 390px 다크 모드 | 선택 프로젝트의 소유자 링크가 올바른 프로젝트 UUID를 가리켰다. 좁은 목록에서 표 영역의 키보드 가로 스크롤로 소유자 열을 확인했다. 상세의 사용자 이름·소유자·상태·UUID와 삭제 확인 대화상자가 화면 안에 표시됐다. 다크 모드 상세에서 복사한 UUID를 붙여 넣어 값이 일치하는지 다시 확인했다. |
| 모든 프로젝트와 상태 매핑 | 프로젝트가 서로 다른 동명 행을 각 프로젝트명으로 구별했다. VPN을 구성하지 않은 시험 사용자 `Add`는 ‘적용 대기’였다. `Revoke`와 미지원 값은 브라우저 읽기 응답을 제어해 ‘철회 대기’와 ‘알 수 없음’을 확인했다. |
| 모든 프로젝트에서 일괄 삭제 | 확인창에 선택한 두 사용자와 각각의 프로젝트, ‘대상 2명’이 표시됐다. 두 요청의 `username + projectid` 및 작업 성공을 확인했다. 선택하지 않은 프로젝트 행과 동명 계정 행은 유지됐다. |
| 계정 사용자 개별 삭제 | 상세의 확인창에 표시한 대상과 `username + account + domainid`가 일치했다. 작업이 성공했고 목록으로 돌아온 뒤 다른 계정의 동명 행이 남았다. |

각 클러스터에서 격리 계정 2개, 프로젝트 2개, VPN 사용자 5개를 사용했다. 임시 자원을 모두 정리하고 기본 보기와 모든 프로젝트의 기존 사용자 UUID 집합이 검증 전과 일치하는 것을 확인했다.

390px에서 긴 사용자 이름이 breadcrumb 오른쪽 경계를 넘는 경우를 확인했다. breadcrumb 줄바꿈과 화면·작업 배치는 VM 표준 비교 항목에서 이어서 검증한다. [#1781](https://github.com/ablecloud-team/ablestack-cloud/issues/1781)

## 빌드와 배포

- WSL ext4에서 변경 파일 ESLint, 관련 UI 단위 테스트 25개, 생산 UI 빌드가 통과했다. 변경 소스는 `25dcabb5368352cf96e2c68b557d94226b571544`다.
- 양쪽에 같은 정적 파일 723개를 배포했다. 각 브라우저가 `app.1c5a0a49.js`를 로드했다. 배포 패키지 SHA256은 `7594342fd2ecdffc3b441e2abf897b4b456b8606261df4d36da0005b948bd6b6`이다.
- 양쪽에서 `/client/` HTTP 200, `WEB-INF`, 런타임 설정 및 관리 JAR 보존과 배포 전후 동일한 Mold 서비스 PID를 확인했다.
