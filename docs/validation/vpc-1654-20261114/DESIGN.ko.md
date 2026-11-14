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

# VPC-04 / #1654 사설 게이트웨이 생성 설계

기준은 Epic 브랜치 `dhslove/epic-1650-vpc`의 `1368ff877f1b1b6e3f0948cb21d5124bf586fc53`이다. VpcTab의 사설 게이트웨이 생성 경로만 수정한다. 기본 ACL 규칙이나 네트워크, 다른 VPC 기능을 변경하지 않는다.

## 설계 전 실환경 실증

31(GFS2)·32(RBD)에 전용 VPC·L2 연결 네트워크와 일반 사용자·프로젝트 시험 자원을 준비했다. 기존 VPC/네트워크/ACL을 수정하지 않았다. 초기 fixture 생성 중 32 VPC가 중복 생성돼 같은 prefix의 두 UUID를 모두 소유 대장에 복구했고 최종 정리 대상으로 관리한다.

각 클러스터 API 13건에서 잘못된 IPv4·게이트웨이·비연속/앞자리 0 넷마스크·앞자리 0 IPv4·연결 방식 누락/중복·사설 대역 정책 위반·다른 owner 네트워크·중복 게이트웨이의 거부를 확인했다. 정상 생성에서 ACL 생략은 양쪽 모두 default_deny였다. customjobid 접수/최종 jobid가 일치했고 jobresult.privategateway에 최종 UUID/VPC/Zone/owner/ACL/상태가 있었다. 빌드 시작 후 추가 경계 실증에서 앞자리 0을 거부하는 API 계약을 확인해 첫 빌드를 중단하고 해당 IPv4 검증을 보완한 소스로 다시 빌드했다. 중단한 산출물은 배포하지 않는다.

IP가 게이트웨이 서브넷 밖인 구성과 VLAN 9999도 실제 서버가 수락했다. 따라서 일반 IPv4/연속 넷마스크/연결 방식은 사전 검증하되 IP·게이트웨이 동일 서브넷이나 VLAN 1–4094를 임의의 강제 제한으로 추가하지 않는다. 이 두 구성은 경고로 표시한다. RFC 1918/6598 계약은 서버 설정에 따라 달라지므로 정책 안내와 서버 오류를 유지한다. 연결 네트워크 CIDR과의 겹침 및 알려진 중복 게이트웨이는 서버 소스와 실제 거부에 따라 사전 차단한다.

일반 사용자·프로젝트 API는 각 7건으로 자기 VPC/L2 및 소속 프로젝트의 정상 연결, 중복 IP, 다른 owner VPC/네트워크 거부, 관리자 VLAN 인자 사용 거부, 다른 VPC ACL 연결 거부를 대조했다. 각 정상 결과와 ACL/owner/project를 확인했다. 실제 Zone은 양쪽 각각 1개이므로 다른 Zone 후보 차단은 현재 Zone으로 제한한 실제 요청과 혼합 Zone DTO 집중 테스트를 함께 근거로 한다. 가짜 Zone을 생성하지 않는다.

수정 전 UI도 양쪽에서 malformed IP의 실제 431 안내 뒤 창이 닫히고 재열기 시 입력과 최초 default_allow가 소실됨을 확인했다. 31 요청 event cursor는 만료돼 해당 POST 원문을 보존하지 못했으며 소스·UI·별도 API 재현을 함께 근거로 한다. 연속 전체 trace를 주장하지 않는다.

## 변경 구조

VpcTab의 게이트웨이 모달·후보 조회·finally 닫기 처리를 PrivateGatewayCreate로 분리한다. ACL 목록의 일반 CRUD/refresh는 유지한다. 새 컴포넌트는 VM 공통 MoldDialog(md)/MoldDialogActions, 고정 푸터, 테마 토큰을 사용한다. 전체 VPC 목록/상세/탭 통합은 #1658에 남긴다.

- VLAN과 연결 네트워크를 명시적인 배타 방식으로 선택한다. 관리자 전용 인자는 루트 관리자이고 API params가 노출된 경우만 전송한다. 일반 사용자는 연결 네트워크 방식만 사용한다.
- VPC의 현재 Enabled/owner/project/Zone을 재확인한다. 네트워크는 owner/project·Zone·Isolated/L2·삭제 상태를 검사한다. ACL은 전역 기본 ACL 또는 같은 VPC만 허용한다. 페이지 단위 후보 조회를 끝까지 읽고 조회 실패/권한 미노출/빈 후보를 구분한다.
- 새 폼 ACL은 서버 생략 기본값과 같은 default_deny를 명시 선택한다. 명시한 선택은 재열기·새로고침·후보 갱신에서 유지하며, 사라진 선택을 다른 ACL로 자동 대체하지 않는다. 후보를 조회하지 못하면 제출을 차단하고 입력을 유지한 새로고침을 제공한다.
- 제출은 fresh 후보/중복 검사 후 customjobid 1회 POST. 공통 createJobTracker를 재사용하며 HTTP 접수와 최종 성공을 구분한다. 진행 중 버튼/폼/단축키/닫기를 잠근다.
- job 성공은 jobresult와 listPrivateGateways의 UUID·VPC·Zone·owner·주소/마스크·ACL·NAT·연결·Ready 상태를 확인한 뒤에만 한국어 성공·목록 갱신·닫기를 수행한다.
- HTTP 4xx 명시 거부와 terminal job 실패는 입력·선택·오류·식별자를 유지해 수정 재시도한다. 결과 조회/접수 응답 유실은 같은 job 재조회만 허용한다. 실패 뒤 잔여 게이트웨이가 있으면 중복 사전 검사로 새 요청을 막고 자동 삭제하지 않는다.
- 인증정보 없는 sessionStorage 기록을 user/role/project/VPC/owner별로 보존한다. 원본/경로/프로젝트/로그인 변경은 현재 폼을 무효화하고 늦은 응답의 닫기/갱신을 막는다. 같은 문맥으로 다시 연 작업은 명시적으로 재조회한다.

## 검증 계획과 범위

IPv4·넷마스크·배타 wire params·후보 scope/페이지네이션·ACL 기본값/명시값·중복·HTTP/job/query/result 실패·재열기·문맥 전환·실제 키 이벤트를 집중 테스트한다. 실제 브라우저에서 양쪽 malformed IP 사전 차단, 실제 서버 431의 입력 보존·수정 성공, pending 중 이중 제출 차단, 조회/결과 응답 실패와 동일 작업 복구, 후보 조회 실패·복구 및 사용자/프로젝트의 연결 조건을 확인한다.

terminal job 실패는 서버의 공통 비동기 계약을 집중 테스트하고 실제 브라우저 query 응답 fault 주입으로 확인한다. 기본 시험 VPC에 라우터/VM/Tier를 새로 배포해 데이터 경로 장애를 만들거나 기존 라우터를 손상시키는 시험으로 확대하지 않는다. 브라우저 주입 응답과 실제 서버 결과/정리는 따로 기록하며 실제 서버 job 실패로 계산하지 않는다.

스타일은 양쪽 light/dark × 360/768/1280px 12조합과 작은 화면의 본문 스크롤·하단 버튼·popup·긴 UUID/오류 줄바꿈을 확인한다. 이전 #1601 ACL 규칙/읽기 전용 회귀, LB 트래픽/ACL/VPN 통신 시험은 재사용하며 반복하지 않는다. 모든 시험 전용 게이트웨이·ACL·네트워크·VPC·프로젝트·계정을 소유 대장으로 정리하고 기존 자원 UUID/주요 필드를 비교한다. 호스트·DB·JAR·Maven·전체 Cloud 빌드는 변경/실행하지 않는 계획이다.

최종 정리 중 예외: VLAN 9999 시험 게이트웨이 2개가 API 삭제 성공 뒤에도 남아 부모 VPC 삭제 후 목록 조회 오류를 일으켰다. 기존 자원과 참조가 없음을 확인한 시험 잔여 게이트웨이/전용 내부 네트워크 행만 로컬 DB에서 soft remove했다. 이 정리 예외와 전후 증빙은 README의 정리 절에 기록했으며 서버 삭제 구현은 수정하지 않았다.
