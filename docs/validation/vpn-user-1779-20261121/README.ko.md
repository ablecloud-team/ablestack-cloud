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

# #1779 VPN 사용자 추가 폼 검증

2026-11-21 KST에 31번 GFS2와 32번 RBD의 실제 Mold UI에서 VPN 사용자 등록을 검증했다. [하위 이슈 #1779](https://github.com/ablecloud-team/ablestack-cloud/issues/1779) · [통합 PR #1782](https://github.com/ablecloud-team/ablestack-cloud/pull/1782)

## 구현

- VM 화면에서 사용하는 공통 대화상자 구조를 적용했다. 등록 대상은 계정과 도메인 또는 현재 선택한 프로젝트로 표시한다. 기본 보기와 모든 프로젝트 보기에서는 로그인 계정이 기본값이다. 역할과 API 권한이 허용하면 도메인과 계정을 선택할 수 있다.
- 사용자 이름은 3~64자, 비밀번호는 3~32자로 검사한다. 영문자·숫자로 시작하는 조건과 필드별 허용 문자를 한국어 도움말과 인라인 오류에 표시한다. 비밀번호 입력은 기본 마스킹한다.
- 등록 요청과 비동기 작업 추적 중에 제출을 잠근다. 성공 시 목록을 갱신한다. 확정 실패 후에는 필드를 편집할 수 있고, 서버 오류는 화면에 표시할 한국어 메시지로 변환한다.

## 실제 UI 결과

| 항목 | 양쪽 클러스터에서 확인한 결과 |
| --- | --- |
| 한국어 입력 검증 | 빈 값, 밑줄로 시작하는 값, 최소 길이 미만, 최대 길이 초과, 허용 외 문자에 필드별 오류가 표시됐다. 잘못된 입력에서는 등록 요청이 0건이었다. |
| 계정·도메인 종속성 | 도메인 선택 전에는 계정 입력이 비활성 상태였다. 도메인을 변경하자 이전 계정 선택이 해제됐다. 계정을 다시 선택하기 전에는 등록 요청이 0건이었다. |
| 다른 계정에 등록 | 표시된 계정과 도메인으로 `account + domainid`를 보냈다. 사용자 이름 64자와 비밀번호 32자로 등록에 성공했고, 응답·최종 작업·목록의 소유자 및 사용자 UUID가 일치했다. |
| 선택 프로젝트에 등록 | 헤더에서 프로젝트를 선택하면 해당 프로젝트를 등록 대상으로 표시했고 계정·도메인 입력을 숨겼다. 사용자 이름과 비밀번호 각각 3자로 등록에 성공했다. 요청에는 `projectid`만 포함됐고, 최종 작업과 목록의 프로젝트가 일치했다. |
| 접수·진행·완료와 중복 제출 | 실제 서버에 보내는 요청과 작업 조회의 타이밍을 브라우저에서 제어했다. 각각 제출 중과 접수 후 상태가 표시됐고, 등록 요청은 1건이었다. 성공 안내 및 목록 갱신을 확인했다. |
| 실제 서버의 중복 이름 거절 | 같은 계정에 같은 이름을 다시 제출하자 HTTP 431을 받았다. 한국어 오류가 사용자 이름 아래에 표시됐고 입력값과 등록 대상이 유지됐다. |
| 비동기 작업 실패 | 브라우저에서 최종 작업 실패 응답을 주입했다. 작업 실패를 안내했고 필드는 편집 가능 상태로 돌아왔다. 서버 응답의 원문과 비밀번호는 화면에 노출되지 않았다. |
| 기본 소유 범위와 일반 사용자 | 모든 프로젝트 보기의 기본 등록 대상은 로그인 계정으로 표시됐다. 일반 사용자는 자신의 계정만 사용했고 소유 인자 없이 제출한 요청이 해당 계정에 등록됐다. |

각 클러스터에서 시험으로 만든 VPN 사용자, 계정, 프로젝트, 도메인을 정리했다. 검증 전후 기본 보기와 모든 프로젝트의 기존 VPN 사용자 UUID 집합이 일치했다.

## 빌드와 배포

- WSL ext4에서 변경 파일 ESLint, 관련 단위 테스트 22개, 생산 UI 빌드가 통과했다. 변경 소스 커밋은 `ff42227dbbbb309882bc6b7aa34a47546599c68d`다.
- 양쪽에 동일한 정적 파일 723개를 배포했고 실제 브라우저가 `app.1b9309a1.js`를 로드했다. 패키지 SHA256은 `bed9fcf7e2d1b7bd23a438b5dc43ee4d741ffd2990adf5242672c963080ec2ca`다.
- 배포 전후 양쪽에서 `/client/` HTTP 200, `WEB-INF`, 런타임 설정, 관리 JAR과 Mold 서비스 PID를 확인했다.

다음 구현은 VPN 사용자 목록·상세·삭제 확인의 소유자와 상태 표시다. [#1780](https://github.com/ablecloud-team/ablestack-cloud/issues/1780)
