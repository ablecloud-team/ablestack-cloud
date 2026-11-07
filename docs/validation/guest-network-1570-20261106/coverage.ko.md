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

# Isolated 유형·탭 검증표

| 유형 | 생성 및 상세 | 수정·삭제·데이터 경로 |
| --- | --- | --- |
| NAT | 31·32 관리자 UI 생성, API와 owner·CIDR·Zone·오퍼링·NIC 대조 | 31 DNS/설명·오퍼링·재시작 및 별도 빈 망 삭제. 32 Public IP 해제와 송신 허용·차단. 양쪽 최종 NAT VM에서 게이트웨이·HTTP 확인. |
| no-SourceNat | 31·32 UI 생성, VLAN 650·651과 지정 범위 확인 | 31 게스트 SSH 인증 실패. 32 DHCP IPv4·기본 경로 확인, 게이트웨이 ICMP 미응답. 범위 수정·삭제는 [#1557](https://github.com/ablecloud-team/ablestack-cloud/issues/1557)의 결과를 재사용한다. |
| VPC tier | 31·32 UI 생성, 호환 오퍼링과 VPC 소속 확인. 31 비호환 사전 차단. | 32 ACL 교체와 저장 ID 확인. 양쪽 VM 게이트웨이 확인. ACL별 차단 패킷은 미실행. |
| Static Routed | 31·32 UI 생성, IPv4 라우팅 방화벽 노출 및 VM 게이트웨이 확인 | BGP 탭은 비적용. 규칙 쓰기는 [#1574](https://github.com/ablecloud-team/ablestack-cloud/issues/1574)의 양쪽 실환경 결과를 재사용한다. |
| Dynamic Routed | 31번 UI 생성은 할당 가능한 ASN이 없어 530. 양쪽 인벤토리의 가용 ASN 0. | BGP 세션·실제 트래픽은 미검증. |
| IPv6·DualStack | 양쪽 IPv6 지원 오퍼링 생성이 설정에서 거부됨. IPv4에서 미지원 IPv6 방화벽 직접 주소 복귀 확인. | IPv6 주소와 방화벽 패킷은 미검증. |

| 상세 영역 | 지원 조건·API | 이번 확인과 책임 경계 |
| --- | --- | --- |
| 상세·작업 | listNetworks, updateNetwork, restartNetwork, deleteNetwork, replaceNetworkACLList | 31·32의 저장값과 UI 대조. 31 수정·재시작·삭제, 32 ACL 변경. |
| 송신 규칙 | 독립 비 Routed Isolated에서 list/create/deleteEgressFirewallRule | 31 TCP·UDP·ICMP·ALL 및 일괄 삭제. 32 TCP 허용/차단/복원. VPC tier와 Static에서 탭 비노출. |
| Public IP 및 하위 화면 | 독립 Isolated의 list/associate/disassociateIpAddress, Source NAT는 updateNetwork | 31·32 실제 할당·Source NAT. 32 이전 IP 해제. Static NAT·FW·PF·LB·VPN 하위 검증은 [#1573](https://github.com/ablecloud-team/ablestack-cloud/issues/1573). |
| 가상 라우터 | 관리자 listRouters, 독립망 networkid 또는 VPC vpcid | 양쪽 상태·호스트·주소 확인, 31 상세 링크, 32 업데이트. 일반 소유자 비노출. |
| 게스트 IP 범위 | IP 지정 no-SourceNat 및 listVlanIpRanges | 양쪽 목록에서 생성 범위 확인. |
| BGP 피어 | Dynamic 비 VPC, 관리자 listBgpPeers | Static 직접 주소 복귀. Dynamic 가용 ASN 0. |
| IPv4 라우팅 방화벽 | Static/Dynamic 및 listRoutingFirewallRules | 양쪽 Static 조회. 규칙 실행은 #1574. |
| IPv6 방화벽 | IPv6·DualStack 및 listIpv6FirewallRules | IPv4 직접 주소 복귀. IPv6 엔진 설정 필요. |
| VNF 기기 | listVnfAppliances의 망 연관 ID | 31 새 NAT에서 연결 없음. Skudonet 연결 증적은 #1574. |
| 네트워크 정책 | Tungsten 연관 망과 listTungstenFabricPolicy | 32 VLAN NAT에서 비지원 및 직접 주소 복귀. |
| 논리 라우터 | Tungsten 연관 망과 listTungstenFabricLogicalRouter | 32 VLAN NAT에서 비지원 및 직접 주소 복귀. |
| 사용자 지정 작업 | CustomAction 서비스와 listCustomActions | 32 기본 NAT에서 비지원 및 직접 주소 복귀. |
| 네트워크 권한 | 독립 Account 소유 망의 listNetworkPermissions | 31 탭과 관련 VM 링크 확인. grant/revoke/reset은 [#1575](https://github.com/ablecloud-team/ablestack-cloud/issues/1575). |
| 이벤트 | listEvents와 현재 Network UUID | 32 전용 NAT 이벤트 탭. |
| 코멘트·태그 | list/addAnnotation, list/createTags와 현재 Network UUID | 32 관리자 쓰기와 일반 소유자 코멘트 비노출 확인. |

배포 및 서버 기능 통합 회귀의 환경 제약과 추가 시나리오는 [#1569](https://github.com/ablecloud-team/ablestack-cloud/issues/1569)에서 추적한다.
