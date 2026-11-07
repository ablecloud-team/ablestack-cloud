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

# #1563 관리자 물리망 이동·실패 재개 설계

소스 기준: `15e73de0834b61b29b79c376fecf1e744f783b78`. Epic #1554 / Draft PR #1577 / `dhslove/epic-1554-guest-networks`.

## 쓰기·권한 계약

`migrateNetwork`는 일반 `updateNetwork` 오퍼링 변경과 구분한다. 필수 파라미터는 `networkid`, `networkofferingid`이며 일반 시작은 `resume=false`, 서버에 검증된 이전 체크포인트가 있을 때만 `resume=true`이다. UI의 `ignoreproject`는 HTTP 요청 전 공통 API 계층에서 제거하는 조회 범위 제어 값이며 Cloud 쓰기 파라미터가 아니다.

Root Admin과 필수 API 권한을 모두 갖춘 Isolated/Guest 네트워크에만 상세 작업 메뉴를 제공한다. 서버에서도 Root Admin, 존재·비시스템 자원, Enabled 오퍼링·물리망, Isolated, 비 VPC, 지원 상태를 최종 검증한다. 같은 물리망 변경은 updateNetwork 사용을 안내한다. 현재 UI는 NATTED/VirtualRouter 및 동일 서비스·제공자 집합과 주소 옵션의 보수적인 후보만 제공한다. 오퍼링 태그와 Guest 트래픽으로 목적 물리망을 유일하게 결정하며 updateNetwork의 fornetworkid 후보를 migration 자격으로 재사용하지 않는다.

## 유형·구성별 실행 수준

| 구성 | 구현·검토 및 실제 검증 범위 |
|---|---|
| Isolated / NAT / IPv4 / VLAN / VirtualRouter | 31 GFS2, 32 Glue RBD의 전용 물리망 A/B, Running VM, 같은 호스트 라우터로 실제 이동·실패·재개 및 게이트웨이 통신 검증 |
| Isolated / no-SourceNat | 동일 VR 서비스 집합이면 보수적 후보 검토 경로 적용. 이번 no-SourceNat 실환경 migration은 미실행 |
| Isolated / VPC tier | 이 명령의 UI에서 제외하고 서버는 migrateVpc 경로 안내. VPC migration 미실행 |
| Isolated / Routed, 외부 제공자, IPv6·VXLAN, redundant VR | 현재 UI 구현 범위 밖 또는 이번 환경에서 미구성·미실행. 엔진 지원 전체와 UI 지원 범위를 동일시하지 않음 |
| L2 | Root UI 읽기에서 전용 액션 비노출, 단위 시험에서 직접 요청 거부. 운영 L2는 수정하지 않음 |
| Shared | 서버·UI 유형 가드 및 단위 시험에서 제외. 기존 운영 Shared 망이 없어 실제 Shared UI/직접 쓰기 migration은 미실행 |
| User / DomainAdmin / ReadOnlyAdmin | Root 전용 UI 가드·단위 시험. 일반 User는 실제 UI 비노출·직접 migrateNetwork 거부·Root 작업 조회 권한 검증. 전체 사용자 역할·프로젝트 조합은 미실행 |
| Stopped VM / 다중 NIC·secondary IP·Static NAT·방화벽·LB·VPN 서비스 전달 | 상태 가드와 기존 엔진 경로 검토. 이번 실제 이동은 단일 NIC Running Linux VM이며 수정 후 부분 NIC 재연결 실패 주입·서비스 규칙 전수 전달·패킷 시험은 미실행 |

미검증 조합에 대한 무중단·운영 적합성 보장은 하지 않는다. 후속 유형별 전수 회귀는 #1570~#1576 및 공통 상태·삭제 계약 #1568에서 관리한다.

## 체크포인트·작업 복구

1. 쓰기 전 네트워크·현재 오퍼링·목적 물리망·VM/NIC·라우터를 다시 읽고 검토 시점의 값과 비교한다. 목적지 비활성화, 서비스 변경, VM 상태 변화, 역할/범위 변경은 새 POST를 막는다. 중복 클릭은 사전 확인 단계부터 단일 실행으로 묶는다.
2. 서버의 `related`가 자기 자신이면 일반 시작이다. 다른 값이면 숨겨진 이전 네트워크의 reciprocal related, 계정·도메인·프로젝트·Zone을 검증한다. 재개는 현재 서버에 기록된 같은 offering/physical/network에 고정하며 브라우저 저장소를 사용하지 않는다.
3. job은 `Network` 자원 ID와 함께 기록한다. async_job_view에 Network UUID 매핑을 추가한다. `listAsyncJobs(includecompleted=true)`는 정확한 resourceid/resourcetype을 필수로 요구하고 기존 ACL 필터를 유지한 채 완료 이력까지 읽는다. 기본 동작은 기존과 같다.
4. job ID·유형·network UUID가 모두 일치해야 성공/실패로 처리한다. 네트워크 정리 중 일시적인 UUID 전환 또는 조회 실패 시 이 읽기만 최대 3회 자동 재시도한다. 계속 실패하면 상태 확인 필요와 수동 재조회를 제공하며 새 쓰기는 보내지 않는다.
5. 성공 응답 뒤 실제 offering/physical/state/related 및 VM/NIC를 다시 확인한다. 실패 시 설정상 목적 망과 이전 망에 남은 VM/NIC·라우터·공인 IP를 함께 표시한다. 화면을 닫아도 서버 job은 계속되며 재진입해 복구한다. 자동 역이동·강제 전환은 하지 않는다.
6. VM 링크 또는 route 이동 때 대화상자를 즉시 닫고 이전 조회를 무효화한다. 다른 자원이나 역할로 바뀌면 잘못된 network 조회·POST를 실행하지 않는다. 자원 조회는 원래 network의 실제 projectid를 사용하고 인프라·Root job 조회는 선택된 프로젝트의 자동 필터를 상속하지 않는다.

## 실제 검증에서 확인한 엔진 결함 보완

- KVM 재연결 뒤 libvirt가 새 tap 이름을 부여하면 이전 이름으로 link up을 갱신하던 명령이 실패했다. 실제 새 이름을 다시 읽어 적용한다. 실패한 ReplugNicAnswer를 무시하고 이전 NIC/네트워크를 삭제하던 서버 경로는 예외로 중단하고 체크포인트를 보존한다. NIC UUID·서비스 전달은 재연결 성공 후 수행한다.
- 이전 네트워크 복사본에 VLAN reservation ID를 보존해 정상 cleanup이 원래 예약을 해제한다. 최초 시험에서 이미 남은 전용 QA 예약 1개씩은 활성 네트워크 참조 0을 확인한 조건부 DB 복구로만 해제했다. 이 복구를 정상 이동 PASS로 계산하지 않는다.
- 목적 라우터 구현이 빈 결과를 반환했을 때 NULL 역참조가 발생하던 경로를 차단하고 목적 자원·호스트 전제 조건 확인 및 같은 목적지 재개 안내를 반환한다. 구현이 안 된 상태에서 NIC 이동·이전 망 정리로 진행하지 않는다.

## UI 기준

VM 목록·상세·NIC 탭·편집 대화상자와 breadcrumb를 실제 화면에서 먼저 확인했다. 네트워크의 기존 목록·상세·탭 구조를 유지하고 상세 작업 메뉴에 전용 액션을 추가했다. 새 대화상자는 공통 MoldDialog lg, 14px 문자, 테마 토큰, 고정 제목·푸터와 내부 본문 스크롤을 사용한다. 현재 서비스, 대상 선택, VM/NIC, 영향·확인을 의미별 세로 구획으로 배치한다. 오퍼링 새로고침은 드롭다운 바로 오른쪽 아이콘이며 상태/업데이트는 같은 도구 모음 행에 배치한다. 모바일 표는 표 내부에서 가로 스크롤하고 대화상자 전체는 가로 넘침을 만들지 않는다.

시험 자원은 사용자 확인을 위해 유지한다. VLAN 스위치 구성이 없으므로 QA VM과 라우터를 31-2 / 32-3 동일 호스트에 고정했다. 이 검증은 VM NIC 이동과 해당 게이트웨이 연결을 확인하며 호스트 간 trunk 및 백엔드 부하 분산 시험을 포함하지 않는다.
