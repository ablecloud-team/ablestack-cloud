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

# #1781 VPN 사용자 VM UI 표준 및 최종 인수 검증

2026-11-21 KST에 31번 GFS2와 32번 RBD의 실제 Mold UI에서 가상머신 목록·고급 검색·상세·NIC·편집 화면과 VPN 사용자 목록·고급 검색·상세·추가·삭제 확인을 비교했다. [하위 이슈 #1781](https://github.com/ablecloud-team/ablestack-cloud/issues/1781) · [Epic #1776](https://github.com/ablecloud-team/ablestack-cloud/issues/1776) · [통합 PR #1782](https://github.com/ablecloud-team/ablestack-cloud/pull/1782)

## 구현

- VM과 같은 breadcrumb와 공통 도구 모음 레이아웃을 적용했다. 프로젝트 범위는 기존 헤더 선택과 목록 스위치에서 확인한다. 기본 보기, 선택 프로젝트, 모든 프로젝트 및 상세의 breadcrumb에 범위 칩을 표시하지 않는다. 실제로 적용한 고급 검색 조건은 공통 검색 필터 영역에 표시된다.
- 상세의 긴 사용자 이름은 현재 경로 영역 안에서 줄바꿈된다. 상세 이름과 UUID를 읽을 수 있고, 소유자 링크와 상태 텍스트에 공통 라이트·다크 토큰을 적용했다.
- 선택 인원 수와 ‘선택 사용자 삭제’를 목록 작업 영역에 배치했다. 상세는 VM과 같은 작업 메뉴를 사용한다. 추가·삭제 창은 공통 MoldDialog를 사용한다. 닫으면 실행 버튼에 포커스를 돌려준다.

## 31·32 실제 UI 비교

| 화면 폭과 테마 | 31 GFS2 | 32 RBD | VM과 VPN 대화상자 폭 | 64자 breadcrumb 줄 수 |
| --- | --- | --- | --- | --- |
| 390px 라이트 | 통과 | 통과 | 366px | 5 / 5 |
| 390px 다크 | 통과 | 통과 | 366px | 5 / 5 |
| 768px 라이트 | 통과 | 통과 | 720px | 2 / 2 |
| 768px 다크 | 통과 | 통과 | 720px | 2 / 2 |
| 1280px 라이트 | 통과 | 통과 | 720px | 1 / 1 |
| 1280px 다크 | 통과 | 통과 | 720px | 1 / 1 |

breadcrumb 줄 수는 31번과 32번 순서다. 브라우저가 계산한 텍스트 대비의 최솟값은 라이트는 소유자 5.19:1, 상태 14.68:1, 다크는 소유자 6.61:1, 상태 13.35:1이었다.

- VM과 VPN 사용자에서 고급 검색 도구의 폭과 재설정·검색 버튼 구성을 확인했다. VPN 사용자의 정확한 사용자 이름 필터를 직접 적용했고 브라우저 요청과 필터 칩을 확인했다. 상세에서 상위 링크를 누르면 같은 조건의 목록으로 돌아왔다.
- 64자 사용자 이름은 390px에서 여러 줄로 표시됐으며 breadcrumb와 뷰포트 경계 안에 있었다. 상세의 UUID가 화면 안에 표시됐고 390px 다크에서 복사한 값을 붙여 넣어 원본과 대조했다.
- 추가창은 첫 입력에, 삭제 확인창은 취소 버튼에 포커스를 옮겼다. Tab 탐색은 창 안에 머물렀고 Escape 후 추가·상세 작업·선택 삭제의 실행 버튼으로 복귀했다.
- VM 편집과 VPN 추가·삭제창의 폭, 본문과 푸터 배경, 본문 스크롤 방식이 같은 화면 폭과 테마에서 일치했다. 390px에서 추가창은 높이를 460px로 줄여, 일괄 삭제 확인은 긴 대상 6개를 선택해 본문만 스크롤되고 푸터 위치가 유지되는지 확인했다. 최종 삭제 버튼의 명칭과 위험 스타일도 확인했다.
- 각 클러스터에서 선택 프로젝트에 실제 UI로 사용자 1명을 등록한 뒤 목록 작업 버튼으로 프로젝트 사용자 2명을 삭제했다. `projectid`와 최종 비동기 작업 성공을 확인했다. 선택하지 않은 동명 계정과 다른 프로젝트의 사용자는 유지됐다.
- 작업 후 양쪽의 시험 사용자·계정·프로젝트를 정리했고, 기본 보기 및 모든 프로젝트의 기존 VPN 사용자 UUID 집합이 검증 전과 일치했다. PPP 접속·철회 및 네트워크 통신은 완료된 통합 회귀 근거를 사용했다. [#1569](https://github.com/ablecloud-team/ablestack-cloud/issues/1569)

## 빌드와 배포

- WSL ext4에서 변경 파일 ESLint, 관련 UI 테스트 8개 스위트 61개와 생산 UI 빌드를 통과했다. 빌드 소스는 `bac1dc8cd6541392fe7dc6132d7f0051c862b58e`다.
- 두 클러스터에 같은 정적 파일 723개를 배포했다. 브라우저에서 `app.60248137.js`를 확인했다. 패키지 SHA256은 `4d6ace2d8c9a309cd3354c55926615772476f5a6fe40978fbbea6b89ced7e59b`다.
- 배포 전후 양쪽 `/client/` HTTP 200, `WEB-INF`, 런타임 설정, 관리 JAR 및 Mold PID를 확인했다.
