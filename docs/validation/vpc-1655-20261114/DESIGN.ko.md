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

# VPC-05 / #1655 재시작 옵션·대상·부분 실패 설계

기준 소스는 Epic 브랜치 `dhslove/epic-1650-vpc`의 `9c62ea852a62139585133c8c557dcdc1e1d589c3`이다. VPC의 단일/일괄 재시작 경로만 변경한다. 일반 네트워크 #1559의 완료 구현과 공통 job tracker/대화상자를 재사용하되 기존 네트워크 동작은 변경하지 않는다. 전체 VPC 표준 화면 통합은 #1658에 남긴다.

## 실환경 실증과 채택 정책

31(GFS2)·32(RBD)에 각각 단일 VPC 2개와 이중화 VPC 1개를 시작하지 않은 상태로 준비했다. 단일 VPC마다 Tier 1개를 추가했다. 기존 자원·오퍼링은 수정하지 않았다. Tier는 VPC 재시작의 실제 Live Patch 경로를 실행하기 위한 fixture이며 ACL/LB/VPN/패킷 회귀를 반복하지 않는다.

수정 전 UI에서 단일 VPC 두 개를 선택하고 Live Patch를 켠 뒤 일괄 재시작했다. 양쪽 모두 보존한 2 POST에 ID만 있고 cleanup/makeredundant/livepatch가 없었다. 캡처 구간 gaps=false다. 31은 성공 1/실제 job 실패 1, 32는 성공 2였다. 31 실패는 신규 라우터 시작 시 `SHARED_RAW_BUSY: disk protection is being changed`였고, 일반 재시도는 성공했다. 서버/호스트 코드나 보호 설정을 변경하지 않았다.

각 클러스터에서 다음 6조합, 총 12개의 실제 restartVPC job을 추가 실행했고 모두 최종 성공을 확인했다. 모든 customjobid와 접수/조회 jobid가 일치했다.

| 조합 | 실증·채택 |
|---|---|
| cleanup/makeredundant/livepatch 명시 false | 기본 재시작, false를 그대로 전달 |
| 세 인자 생략 | 서버 기본값 false와 같은 동작. UI 기본값도 명시 false |
| cleanup=false, makeredundant=false, livepatch=true | 실행 중 라우터와 Tier에서 실제 패치 성공 로그 및 job 성공 |
| cleanup=true, livepatch=true | 서버가 정리 경로를 우선 실행해 라우터 교체. UI는 함께 선택하지 않도록 처리 |
| cleanup=false, makeredundant=true, livepatch=true | 서버가 기본 Redundant VPC offering으로 바꾸고 정리 재시작 강제. 실제 단일→이중화와 실행 중 라우터 2개 확인 |
| 이미 이중화된 VPC에 makeredundant=true | 서버가 추가 전환 없이 수락. UI의 신규 전환 선택은 단일 대상에만 제공 |

Live Patch는 요청 인자만 확인한 결과가 아니다. 31 r-1060-VM과 32 r-540-VM에 대해 관리 서버의 `Successfully patched system VM` 및 `Successfully patched router` 로그를 확인했다. 정리 후 라우터 교체와 전환 후 기본 오퍼링 ID/이중화 flag/Running 라우터 2개도 대조했다. 무중단 트래픽·페일오버/복구 시간 시험은 하지 않았다.

현재 VPC job 응답은 `jobinstancetype=Vpc`이나 `jobinstanceid`가 없고 resourceid+resourcetype의 완료 작업 조회도 빈 응답이었다. 서버 job view 보완으로 범위를 확대하지 않는다. 제출 전 보존한 customjobid를 사용자·선택 문맥과 묶어 추적하고, query 결과의 jobid/명령/userid 및 제공되는 instance 정보는 검증한다. 소유자 범위의 진행 중 VPC job에 대상 UUID가 없으면 보수적으로 새 제출을 차단하고 사유를 안내한다.

일반 사용자 API에는 restartVPC/listVPCs/listVPCOfferings/listNetworks/listAsyncJobs/queryAsyncJobResult가 있지만 listRouters는 노출되지 않았다. 기본 재시작은 허용하고, 라우터 상태를 확인할 수 없는 Live Patch는 비활성화한다. 31 일반 사용자 소유 VPC와 32 소속 프로젝트 VPC를 별도 준비해 배포 후 UI로 해당 경로를 검증한다. 다른 owner VPC의 조회/재시작 거부도 직접 API로 대조했다.

## 구현 구조

- `vpcRestart.js`에서 VPC 권한/상태·식별 지문·제공자/이중화/옵션 조건·명시 boolean wire 매핑·job 계약을 분리한다. VPC groupMap에도 동일 매핑을 적용해 livepatch 누락을 제거한다.
- `RestartVpc.vue`는 단일/일괄 공용의 전용 작업 대화상자다. Autogen에는 이 작업의 고정 대상 snapshot을 전달하는 분기만 추가한다. 일반 네트워크의 분기·유틸·컴포넌트는 그대로 유지한다.
- VM 공통 MoldDialog(lg)/MoldDialogActions·고정 header/footer·본문 스크롤·테마 토큰을 사용한다. 대상 이름/UUID/owner/프로젝트/Zone/제공자/단일·이중화 상태와 중단 영향을 표시한다. 실제 Ctrl+Enter도 같은 가드와 제출 경로를 사용한다.
- 최초 로드와 전체 preflight, 각 대상 실행 직전에 VPC/오퍼링/Tier/노출된 라우터/진행 작업을 다시 읽는다. 혼합 불가 상태·권한·조회 실패는 전체 최초 제출을 차단한다. 대상 사이에 조건이 바뀌면 해당 대상만 차단하고 이미 완료한 대상을 보존한다.
- 각 항목을 순서대로 1회 제출하고 최종 job+현재 VPC/노출된 라우터를 확인한다. 성공·실패·미실행·미확정을 구분하고 모든 시도의 ID와 오류를 유지한다. 성공한 대상은 재시도에 포함하지 않는다. 미확정이 있으면 새 제출을 중지하고 같은 작업만 재조회한다.
- 이중화 전환은 서버가 flag/오퍼링을 먼저 바꾼 뒤 라우터 시작에서 실패할 수 있다. 동일 owner/Zone/UUID와 정확한 기본 이중화 오퍼링으로의 예상 전환만 허용해 원 옵션의 정리 재시작을 마칠 수 있게 한다. 임의의 오퍼링·owner 변경은 허용하지 않는다.
- 인증정보 없는 최소 작업 기록을 user/role/project/선택 UUID 문맥별 sessionStorage에 저장한다. 불완전 작업의 재열기는 미확정 상태로 복원해 자동 재제출하지 않는다. 페이지/프로젝트/로그인 전환의 늦은 응답은 나머지 제출·갱신·다른 창 닫기를 수행하지 않는다. 기록 저장 실패는 새 쓰기를 차단한다.

## 검증·배포 계획

새 정책/컴포넌트 집중 테스트와 기존 RestartNetwork 테스트, 변경 파일 lint/codespell 및 UI production build를 WSL ext4에서 실행한다. 31/32 정적 UI만 allowlist 배포하고 WEB-INF/META-INF·설정·JAR/PID·FTCTL marker와 /client/ HTTP 200을 보존한다. 전체 Cloud/Maven/JAR/RPM 빌드는 하지 않는다.

실제 UI에서 단일/일괄 옵션 전송·최종 job/상태·부분 실패/실패 대상만 재시도·미확정 재조회·조회 실패·선택 문맥 변경·일반 사용자/프로젝트를 확인한다. 주입 응답/전송 변조는 실제 서버 성공·실패와 구분해 기록한다. 양쪽 light/dark × 360/768/1280px 12조합과 긴 UUID/오류·모바일 하단 접근·작업 중 가드를 검증한다.

전용 VPC/Tier/자동 생성 라우터/계정/프로젝트만 소유 UUID 대장으로 정리하고 기존 12종 UUID·주요 필드를 비교한다. 기존 VM·VPC·ACL·오퍼링과 별도 FTCTL 작업을 보존한다. 범위 밖 결함은 구현하지 않고 새 이슈는 승인 없이 생성하지 않는다.
