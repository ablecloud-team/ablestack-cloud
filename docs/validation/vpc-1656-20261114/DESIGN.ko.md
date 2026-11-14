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

# VPC-06 / #1656 관리자 마이그레이션·Tier 매핑·실패 재개 설계

기준 소스는 Epic 브랜치 `dhslove/epic-1650-vpc`의 `4e3cbb01e3c6ee0bcc584c998c2abeab0461efc7`이다.
기존 #1563의 일반 네트워크 마이그레이션, 제공자 진단·pagination·job tracker와
VM 표준 MoldDialog/MoldDialogActions를 재사용한다. 일반 네트워크 구현은 변경하지 않는다.

## 실환경에서 확정한 계약

31 GFS2·32 RBD 모두 Root Admin API `migrateVPC`가 있고 UI 작업 메뉴에는 없다.
필수 인자는 `vpcid`, `vpcofferingid`다. Tier 매핑은
`tiernetworkofferings[i].networkid/networkofferingid`이며 `resume`의 기본값은 false다.

양쪽 전용 VPC와 두 Tier를 준비해 같은 물리망에서 VPC 오퍼링·모든 Tier 오퍼링 전환을 실제 실행했다.
별도 실행에서는 Running Linux VM을 Tier 1에 배치하고 전환 후 Running 상태와 NIC UUID·IP·MAC 보존을 확인했다.
단순 API 접수가 아닌 최종 job=1, VPC/Tier UUID·오퍼링·부모·related 및 라우터 상태를 대조했다.
패킷·호스트 간 trunk·게이트웨이 통신 회귀를 새로 PASS로 계산하지 않는다.

31은 두 번째 Tier에 목적 VPC가 지원하지 않는 Firewall 서비스를 매핑해 실제 job=2를 재현했다.
첫 Tier는 목적 VPC로 옮겨졌고, 두 번째는 숨겨진 이전 VPC에 남았다.
32의 같은 Firewall 시험은 물리망 제공자 검증에서 먼저 거부되어 복사본이 생기지 않았다.
32는 VPN 없는 목적 VPC·첫 Tier와 VPN을 요구하는 두 번째 Tier로 별도 실제 부분 실패를 재현했다.
이 서비스 축소 조합은 서버 재개 실증이며 채택한 UI의 동일 서비스 계약 후보와 구분한다.

두 부분 실패 모두 같은 목적지와 올바른 미완료 Tier 매핑으로 `resume=true` 재개가 성공했다.
31에서 다른 VPC 오퍼링 또는 이미 완료한 Tier의 다른 매핑으로 재개하면 실제 서버 job=2이며 자원은 유지됐다.
32의 실증용 서비스 축소 구성은 정상 표준 오퍼링으로 복원한 뒤 Running VM 전환을 검증했다.

서버는 새 VPC에 원 UUID를 넘기고 이전 VPC를 숨긴다. 현재 VPC의 `migration` 태그 값은
이전 VPC의 숫자 참조다. Root API `listVPCs(id=<참조>,fordisplay=false)`가 이전 UUID를 반환하는 것을 실증했다.
UI는 이 정상 인증 API 경로로 이전 VPC를 읽고 owner/project/Zone·이름·CIDR·domain·숨김 여부를 검증한다.
직접 DB 조회나 임의 서버 우회는 사용하지 않는다.

현재 migration job 응답에는 명령·호출 user와 job UUID는 있지만 자원 instance 정보가 생략될 수 있다.
제출 전에 보존한 customjobid와 호출 사용자·VPC 문맥을 묶고 명령/user/jobid를 검증한다.
제공되는 instance 정보도 일치해야 한다. 대상 UUID가 없는 진행 중 VPC 작업은 보수적으로 새 제출을 차단한다.

일반 User는 자기 VPC와 다른 소유 VPC에 대한 migrateVPC를 모두 job 접수 전에 거부당했다.
다른 소유 VPC 조회도 빈 응답이었다. 31 User 소유 VPC와 32 소속 프로젝트 VPC를 따로 준비해
Root 작업과 일반 User UI 비노출을 배포 후 검증한다.

## 채택 지원 범위

| 구성 | UI 정책·실증 수준 |
|---|---|
| Root Admin + 필수 읽기/쓰기 API | 상세 작업에 단일 VPC 마이그레이션. 일괄 작업은 제공하지 않음 |
| IPv4 / NATTED / 단일 VpcVirtualRouter | 같은 서비스·제공자·주소/주요 capability 계약의 오퍼링만 후보 |
| 같은 물리망의 VPC·Tier 오퍼링 전환 | 양쪽 실제 정상·Running VM 전환 성공. 단순 updateVPC 편집과 구분 |
| 다른 물리망 | 태그/Guest 트래픽으로 유일하게 결정되고 Enabled/VLAN·모든 제공자가 준비돼야 제출 가능 |
| 현재 별도 QA 물리망 | VpcVirtualRouter 비활성. 기존 구성을 바꾸지 않고 전용 태그 오퍼링의 진단/제출 차단만 검증 |
| 새 물리망·외부 장비·호스트 bridge/trunk | 이번에 생성하거나 운영 설정을 바꾸지 않음. 물리망 간 실이동 성공으로 보고하지 않음 |
| 이중화·분산/region VPC·IPv6·동적 라우팅·외부 제공자 | 현재 채택 UI 범위에서 제외. 서버 전체 지원과 UI의 보수적 지원을 구분 |
| 사설/VPN 게이트웨이·Static route가 구성된 VPC | 현재 UI 전환에서 제외하고 명확히 안내. 기존 규칙/통신 회귀를 반복하지 않음 |
| 부분 실패·resume | 서버 checkpoint·이전 VPC/Tier 관계를 검증. 목적 VPC와 반영된 Tier를 고정하고 미완료 매핑만 검토 |
| User/DomainAdmin/ReadOnlyAdmin·프로젝트 문맥 불일치 | 액션 비노출·제출 차단. Root의 명시적 User/프로젝트 대상은 실제 소유 범위로 읽음 |

VPC 오퍼링 응답에는 이중화 여부가 없어 이름으로 후보를 추정할 수 없었다.
이번 후보 안전 검증에 필요한 읽기 전용 `redundantvpcrouter` boolean만 응답에 추가한다.
기존 VO/DB 필드를 API response/DAO에서 직렬화하며 엔진·DB·일반 오퍼링 관리 구현은 변경하지 않는다.
필드가 없는 배포는 후보를 추정하지 않고 제출을 막는다. API/server 변경 모듈만 WSL ext4에서 빌드한다.

## UI·제출·복구 구조

1. `vpcMigration.js`가 Root/API/소유 범위·VPC/Tier 상태·checkpoint·후보·매핑·최종 보존 조건을 담당한다.
   물리망 해석과 제공자 진단은 #1563의 함수를 재사용한다. 기존 migrateNetwork 동작을 변경하지 않는다.
2. VPC와 이전 VPC의 모든 Tier, 오퍼링/물리망/Guest VLAN, VM/NIC·라우터·공인 IP 및 구성된 고급 자원을 읽는다.
   Tier의 reciprocal related 복사본도 검증한다. 조회 누락/권한/서버 오류는 빈 정상 결과로 취급하지 않는다.
3. 모든 Tier를 명시 매핑해야 전체 전환을 제출한다. 미완료 Tier에는 다른 오퍼링이 필요하다.
   같은 오퍼링을 유지하면 엔진이 Tier를 옮기지 않는 경로가 있어 이를 후보로 제공하지 않는다.
4. 검토 뒤 실제 제출 직전에 전체 상태와 선택 오퍼링/물리망의 지문·제공자를 다시 확인한다.
   VM/라우터 전환 상태·미해결 VPC job·대상 변경은 전체 새 POST를 막는다.
5. 사용자 확인 후 customjobid와 최소 intent를 인증정보 없이 sessionStorage에 먼저 기록하고 1회 제출한다.
   Map 전체와 `resume=false/true`를 명시한다. HTTP 거부와 접수 미확정은 구분한다.
6. 공통 createJobTracker를 사용하고 진행 중 실제 Tier 위치/상태를 재조회한다.
   최종 job 이후 VPC/모든 Tier의 목적지·related/체크포인트 정리와 VM NIC·IP 보존을 확인해야 성공이다.
7. 부분 실패는 실제 현재 자원과 각 시도 ID/오류를 유지한다. 서버 checkpoint가 있으면 재개로 전환하고
   같은 VPC 오퍼링·반영된 Tier 매핑을 고정한다. 미확정은 기존 ID 재조회만 제공한다.
8. 페이지·프로젝트·로그인·역할 변경은 이전 비동기 콜백을 무효화한다.
   재열기/새로고침은 미확정 기록을 복원하고 자동 재제출하지 않는다. 저장 실패는 새 쓰기를 막는다.
9. VM 표준 lg 대화상자·고정 제목/푸터·본문 스크롤·테마 토큰·14px·Ctrl+Enter 단일 제출 경로를 사용한다.
   현재 VPC/owner/project/Zone·서비스·각 Tier 매핑·물리망 차이·연결 자원·영향/확인·작업 이력을 구획한다.

## 검증·배포·정리 계획

새 정책/컴포넌트 집중 테스트와 기존 NetworkMigration/RestartVpc 회귀, 변경 파일 lint/codespell,
Java 새 직렬화 4개·기존 migration request 17개 검사 및 변경 모듈 빌드, UI production build를 실행한다.
두 관리 서버의 API/server JAR에서 변경한 두 class만 반영하고 다른 entry·설정/FTCTL·호스트를 보존한다.
정적 UI는 활성 webapp allowlist로 배포하고 WEB-INF/META-INF·설정/JAR·HTTP200을 검증한다.
전체 Cloud·qemu/RPM 빌드와 호스트 배포는 하지 않는다.

배포 후 실제 Root 정상/부분 실패/재개, HTTP 거부·미확정 재조회/복원·늦은 응답 보호,
일반 User UI 비노출·Root User/프로젝트 작업·미구성 물리망 제공자 차단을 확인한다.
주입 응답/전송 변조와 실제 서버 실패를 구분하고 미실행 조합을 명시한다.
양쪽 light/dark × 360/768/1280 및 하단·select popup을 캡처하고 실제 이미지까지 검수한다.

전용 VPC/Tier/오퍼링/VM·계정/프로젝트만 UUID 대장으로 정리한다.
기존 12종 UUID/주요 필드와 별도 FTCTL 변경을 보존한다.
범위 밖 구현이나 승인 없는 별도 이슈 생성은 하지 않는다.
