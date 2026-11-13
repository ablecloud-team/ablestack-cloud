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

# VPC-03 / 이슈 #1653 구현 설계

기준은 Epic 브랜치 `dhslove/epic-1650-vpc`의 `0df0a392ac4a62705dac194501a3954956f456f6`이다. 생성·복제의 비동기 완료/실패/재조회·한국어 메시지만 다루며 서버 상속 결함이나 다른 VPC 기능은 확대 수정하지 않는다.

## 설계 전 실환경 실증 (2026-11-14 KST)

31(GFS2)·32(RBD) 각각 정상 생성/복제, 기존 Redundant 자동 상속 오류, 잘못된 원본의 즉시 거부를 대조했다. 생성/복제는 id+jobid 접수 후 jobstatus=1, Redundant 오류는 HTTP 접수 후 jobstatus=2·결과 오퍼링 없음, 잘못된 원본은 431 JSON 오류·job 없음이었다. 시험 성공 오퍼링은 바로 정리했다.

특히 clone 접수의 id는 새 UUID가 아닌 원본 내부 번호 1/3이었다. 최종 jobresult.vpcoffering.id만 새 복제본 식별에 사용해야 한다. 31 수정 전 UI에서도 Redundant 복제 접수 후 창이 닫혔지만 같은 job의 최종 상태는 2였고 복제본은 없었다. 짧은 성공 토스트는 캡처 전에 사라져 문자열 캡처로 주장하지 않으며, 접수 즉시 성공 처리·닫기는 실제 소스와 관찰을 함께 근거로 한다.

양쪽에서 생성·복제에 customjobid UUID를 명시하면 접수 jobid와 같고 동일 ID 조회로 최종 성공을 얻었다. 접수 응답 유실 시 해당 ID를 재조회할 수 있으며 같은 POST를 반복하는 근거로 사용하지 않는다. 설계 API는 총 12건(클러스터당 6건)이다.

## 상태와 중복 방지

| 상태 | 화면/다음 동작 |
|---|---|
| checking | 같은 이름의 기존 개체를 확인. 단일 제출 경로를 잠금 |
| submitting | 입력·원본·wire params·요청 UUID를 저장한 뒤 POST 1회 |
| running | 공통 createJobTracker로 jobstatus=0 추적. 성공 토스트/닫기/refresh 없음 |
| verifying | jobstatus=1의 새 UUID·이름을 listVPCOfferings에서 재확인 |
| succeeded | 확인 후에만 한국어 성공·목록 갱신·창 종료 |
| failed/rejected | 최종 job 실패와 HTTP 4xx 명시 거부를 구분. 오류/입력/원본/식별자 보존 |
| unknown | 접수/조회/결과 확인 실패. 새 POST 차단, 같은 job ID 재조회만 제공 |

실패 후 재제출 전에는 이전 제출 이름과 새 이름의 개체 존재를 다시 확인한다. job 실패 후 생성 잔여물이 있으면 자동 재시도/삭제하지 않고 미확정으로 보존한다. 같은 이름의 초기 충돌이나 명시 HTTP 거부는 입력을 편집해 다시 제출할 수 있다. 네트워크 실패를 미접수로 간주하지 않으며 customjobid로 기존 작업을 확인한다. 작업을 찾지 못하고 개체도 확정할 수 없으면 새 요청은 계속 차단한다.

## 수명과 보존

생성/복제 두 폼 전용 상태 관리에서 공통 createJobTracker를 재사용한다. 공통 tracker/API/인증 코드는 변경하지 않는다. sessionStorage에는 입력/선택·명령·원본·요청/job/result ID만 저장하며 토큰/세션키/비밀번호는 저장하지 않는다. 창 닫기·경로 이동·새로고침 후 같은 사용자/프로젝트/원본의 작업을 복원한다. 로그인/사용자/프로젝트 변경 때 진행 중 추적을 무효화하고 다른 소유자/프로젝트의 기록을 새 폼에 적용하지 않는다. 동일 소유자/프로젝트로 돌아오면 보존된 요청을 미확정 상태로 복원해 명시 재조회하며, 늦은 응답이 새 폼에 성공/실패/닫기/refresh를 실행하지 못하도록 scope와 폼 세대를 확인한다.

UI 검증 시작 시 확인한 원래 프로젝트 보기를 복원한다. 실제 401 정책을 우회하지 않는다. 접수 후 잘못된 job ID/command/user 및 최종 개체 불일치는 성공으로 처리하지 않는다. clone 접수 id를 새 개체로 조회하지 않는다.

## UI와 검증

생성/복제의 진행 상태와 오류는 VM 공통 MoldDialog·MoldDialogActions 프레임 안에 표시한다. 생성 창도 이 두 폼의 작업 중 닫기/중복 제출 방지를 위해 md 프레임과 고정 하단을 사용한다. 전체 VPC 목록/상세/탭 통합은 #1658로 남긴다. 입력을 잠그되 미확정 시 재조회와 창 닫기 경로를 제공하고 긴 UUID/서버 오류를 줄바꿈한다. Ctrl+Enter·폼 제출·버튼은 같은 검증/잠금 경로를 사용한다.

실환경 정상 생성/복제·실제 async 실패·HTTP 거부와 수정 재시도, CDP로 지연/응답 유실/조회 오류를 주입한 UI 상태·재조회·중복 방지를 검증한다. 실제 서버 응답과 주입한 전달/조회 실패를 구분한다. 원본/경로/프로젝트/로그인 전환의 늦은 응답은 집중 단위 테스트와 가능한 UI 경로로 확인한다. 31/32 light/dark × 360/768/1280px의 상태/오류/버튼을 점검한다. 기존 LB VM/트래픽/ACL/VPN 시험을 반복하지 않는다.

## 실환경 UI 실증에 따른 단축키 보완

첫 배포 `f698b019196`의 실제 Ctrl+Enter 시험에서 양쪽 복제 폼이 제출되지 않았고 POST는 0건이었다. 공통 directive의 keydown이 고정 푸터 버튼으로 포커스를 옮기지만 Teleport된 푸터의 keyup은 본문 루트로 전파되지 않는다. 두 폼의 MoldDialogActions에 keyup.ctrl.enter를 같은 handleSubmit으로 연결해 본문·푸터·폼 제출 모두 동일한 검증·잠금을 사용하도록 보완했다. 공통 directive는 수정하지 않는다.

보완 `5b25092d8ce`는 실제 directive와 Teleport/일반 푸터를 함께 마운트한 생성/복제 4개 집중 테스트를 포함해 7 suites / 115 tests를 통과했다. 이 보완 소스를 다시 빌드·배포한 뒤 실제 단축키를 재검증하며, 첫 배포와 최종 배포의 사례/증적을 구분한다.
