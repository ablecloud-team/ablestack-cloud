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

# #1568 상태·권한·실패 설계

상위 Epic #1554, 동일 브랜치 `dhslove/epic-1554-guest-networks`, Draft PR #1577.

## 상태와 실행 조건

| 서버 상태/조건 | 표시와 허용 계약 |
|---|---|
| Allocated / Setup / Implemented | 할당됨 / 구성 완료 / 구현 완료. 서비스 유형과 API 권한별로 후보를 분리한다. |
| Implementing / Shutdown / Destroy / 진행 중 Network job | 새 쓰기를 보수적으로 막고 접수된 job을 조회한다. 오래된 목록 상태만으로 쓰지 않는다. |
| RestartRequired | 현재 구성과 재시작 필요를 구분한다. 실행 직전 서버 자원과 권한을 다시 읽는다. |
| POST 접수 + job ID | 진행 중이며 성공이 아니다. job·자원·명령 identity를 검사한다. 관리자 IP 획득의 실제 AssociateIPAddrCmdByAdmin도 명시적으로 허용한다. |
| jobstatus=1 | 규칙은 완료 콜백 후 목록을 갱신한다. 삭제는 같은 UUID의 망 부재까지 대조한다. |
| jobstatus=2 / 확정 API 오류 | 오류 코드·원인·작업 번호와 입력을 유지한다. 이미 성공한 단계/대상은 재전송하지 않는다. |
| job 읽기 실패 | 최대3회 읽기만 재시도 후 결과 확인 필요. 같은 job 재조회가 가능하며 POST를 반복하지 않는다. |
| POST 수신 불명 / job 바인딩 불일치 | 결과 불명으로 새 쓰기를 차단한다. 자동 재전송·강제 삭제를 하지 않는다. |
| 자원/프로젝트/역할/로그인/route 변경 | 작업 token을 무효화하고 늦은 응답·콜백이 새 화면을 바꾸지 못하게 한다. |

## 유형·역할 행렬

| 유형 | 삭제 전 진단 | 서비스/탭 | 권한 계약 |
|---|---|---|---|
| Isolated | VM/NIC·VR·Public IP·related/checkpoint·Network job | 상세·송신·IP·라우터·VNF·권한·이벤트·코멘트 및 IP 하위7탭 | 소유 User/해당 프로젝트/관리 범위만 쓰기. 이동은 Root Admin 전용. |
| L2 | VM/NIC·job·소유 범위 | L3 서비스 탭과 재시작은 적용 제외. 상세·VNF·권한·이벤트·코멘트 | 관리/소유 범위를 검사하며 명시 VLAN이면 공식 카운터 제외 조건을 따른다. |
| Domain Shared | 연결 VM/NIC·DHCP/DNS VR·IP/범위·Domain ACL | 상세·IP 주소·VR·VNF·게스트 범위·이벤트·코멘트 | 명시 VLAN Shared의 운영은 Root Admin. 일반 User는 표시 범위를 조회하고 쓰기는 차단한다. |
| 조회 권한 없음 | 의존성을 0개로 오인하지 않고 조회 권한 없음/미확인 | 권한 없는 서비스 탭 비표시 | Root Admin과 User는 양쪽 실환경 확인. DomainAdmin/ReadOnlyAdmin/프로젝트 교차는 로컬 계약 검증이며 모든 역할의 실환경 회귀는 #1575가 담당한다. |

## 삭제와 부분 성공

DeleteNetwork 전용 MoldDialog에서 선택 항목을 고정 snapshot으로 보존한다. 매 대상 직전 서버의 망·VM/NIC·IP/라우터·진행 job을 다시 읽는다. 한 대상의 job 완료와 망 부재를 확인한 후 다음 POST를 보낸다. 사용 중 항목은 진단과 다음 조치를 표시하고 건너뛴다. 완료 대상을 목록/선택에서 제거하고 실패·미실행 항목을 유지한다. 재시도는 그 항목만 다시 검사한다. 서버 pending 및 최신 failed/completed 삭제 이력은 자원 UUID·Network 타입으로 복구한다.

VM 링크는 팝업을 닫고 해당 VM으로 이동한다. 관련 VM 조회는 새 query로 목록을 열고 breadcrumb는 원래 검색·페이지 조건으로 돌아간다. 입력/조회 오류로 기존 데이터·선택·스크롤을 초기화하지 않는다.

## 규칙/IP 공통 작업

Egress/방화벽/포트 포워딩/IP 획득은 NetworkMutationStatus를 공유한다. 방화벽은 Public IP ID와 연결 Network ID를 분리한다. 규칙 교체의 delete→create 중 create만 실패하면 삭제 완료 단계는 유지하고 create만 재시도한다. LB의 기존 다단계 완료 콜백에도 자원·권한 범위를 바인딩한다. 백엔드 부하 분산 트래픽 시험은 범위에 포함하지 않는다.

## 서버 정합성

계정 네트워크 증감은 공식 DAO의 Account ACL·display·비시스템·!specifyVlan 조건과 일치시켰다. Domain Shared 재집계는 망 transaction 밖에서 수행하며 커밋된 쓰기 후 집계 경고로 쓰기 자체를 실패로 오인하지 않게 했다. 물리망 이동의 숨긴 이전 망 복사본은 quota를 증가시키지 않는다. 삭제 실패/재시도에서 카운터를 직접 수정하지 않는다.

망 제거에 성공한 transaction 안에서 해당 망의 ownerless PlaceHolder NIC만 제거한다. 다른 망·VM NIC는 대상이 아니다. VMInstanceDao로 라우터 포함 VM 타입을 찾고 라우터 ROOT VolumeApiResult의 실패/예외를 상위 삭제에 전달한다. 라우터 Expunging 상태와 망을 보존하며 정상 재시도를 허용한다. 다른 VM의 기존 볼륨 결과 계약은 바꾸지 않는다.

## UI 기준

실제 컴퓨트 VM 목록·상세·NIC 탭·편집 팝업을 먼저 캡처했다. 네트워크 목록/상세는 공통 도구 모음·작업 메뉴·breadcrumb·고정 탭 key를 사용하고, 일괄 삭제/재시작에는 텍스트 버튼 라벨을 추가했다. 삭제·방화벽·포트 포워딩·IP 획득은 VM의 MoldDialog와 테마 토큰·14px 본문·세로/의미별 입력·독립 스크롤·고정 footer·키보드 포커스 기준을 사용한다. light/dark 즉시 전환과390/768/1280 너비를 확인한다.

## 남은 범위

이번 가시성/오류 계약 검증은 IPv4 VR 기반 Isolated, 명시 VLAN L2, Domain Shared DHCP/DNS를 실제 실행했다. no-SourceNat·VPC·외부 제공자·IPv6 전 서비스 실행과 모든 역할/프로젝트의 실환경 교차는 미구성/미실행으로 구분한다. 전체 기능 PASS로 확대하지 않는다. Glue RBD libvirt 정리의 별도 결함 #1586은 운영자 정리 없이 정상 재시도가 가능해질 때까지 Open이다.
