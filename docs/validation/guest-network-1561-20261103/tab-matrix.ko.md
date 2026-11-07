<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# #1561 탭 조건·URL 계약 검토

이 표는 탭의 표시·선택·경로 계약을 검토한다. 각 탭의 생성·수정·삭제, 게스트 통신, 제공자 엔진 동작은 #1570~#1576 및 통합 회귀 #1569에서 추적한다.

## 네트워크 상세의 15개 고정 키

| 고정 키 | 조건 및 경계 | 검증 수준 |
|---|---|---|
| details | 모든 유형의 기본 탭 | 31/32 실제 UI |
| egress.rules | Isolated, IPv4 routing 없음, VPC 아님, API 및 Admin/DomainAdmin/owner/project | 실제 NAT·31 noSNAT UI, 역할/필드 경계 단위 시험 |
| bgp.peers | Isolated, Dynamic, VPC 아님, Admin, listBgpPeers | 중복 정의 제거, 단위 시험 및 합성 UI; 실제 BGP 세션 미검증 |
| routing.firewall | Isolated, Static/Dynamic, VPC 아님, API 및 소유 조건 | 단위 시험 및 합성 UI; 실제 라우팅/패킷 미검증 |
| ip.v6.firewall | Isolated IPv6/DualStack, VPC 아님, API 및 소유 조건 | 단위 시험 및 합성 UI; 실제 IPv6 제공망 미검증 |
| public.ip.addresses | Shared 또는 non-routed/non-VPC Isolated와 API/소유 조건 | 실제 Isolated/Shared, Shared 표시명 `IP Addresses`와 고정 URL 분리 |
| virtual.routers | Isolated/Shared, listRouters, Admin | 실제 UI; 이번 검증에서 라우터 생성 없음 |
| vnf.appliances | listVnfAppliances | 실제 빈 목록 UI; 장비 배포 미실행 |
| guest.ip.range | listVlanIpRanges 및 기존 Shared/noSNAT 판정 | Shared 실제 UI. noSNAT `.count` 판정 오류는 #1557 소유로 보존 |
| network.policy | Tungsten API, tf://tf, Shared 제외 | 단위 시험. Tungsten 비활성, 실제 적용 미검증 |
| tungsten.logical.router | Tungsten API, tf://tf, Shared 제외 | 단위 시험. Tungsten 비활성, 실제 연결 미검증 |
| network.permissions | API, Account ACL, VPC·project 제외, 관리자/owner | 실제 빈 목록 UI, 소유·API·VPC 경계 단위 시험. 권한 CRUD 미실행 |
| custom.actions | listCustomActions 및 runCustomAction, CustomAction 서비스 | 서비스/API 경계 단위 시험. 실제 action 실행 미실행 |
| events | listEvents, Network UUID | 실제 UI 읽기. 이벤트 쓰기 없음 |
| comments | 고정 키, 해당 자원의 annotation 화면 | 실제 UI 읽기. 코멘트 CRUD 미실행 |

`vpcid`/`vpcname`이 누락·null·빈 문자열이면 non-VPC로 판단한다. 둘 중 실제 값이 있으면 VPC 조건을 적용한다. L2는 일반 L3 탭을 표시하지 않는다. 기존 static 문자열 키와 번역 라벨은 유지한다.

## Public IP 상세의 7개 고정 키

| 키 | 표시 조건 |
|---|---|
| details | 기본 |
| firewall | Firewall 서비스 및 listFirewallRules; VPC firewall capability 분기는 유지 |
| portforwarding | PortForwarding 서비스 및 listPortForwardingRules |
| loadbalancing | Lb 서비스 및 listLoadBalancerRules |
| vpn | 기존 SourceNat/DomainRouter/hasrules 조건 및 listRemoteAccessVpns |
| events | listEvents |
| comments | 기존 annotation 화면 |

Free/Reserved는 기본 탭, StaticNat/SourceNat·VPC·conserve/PF/LB 필터는 기존 계약을 유지한다. 탭 구성 조회는 자원 스냅샷과 요청 세대로 보호한다. associated network 응답도 원래 IP/network ID와 대조하며, 서비스 조건과 API 조건을 함께 적용한다.

## URL·전환 계약

- 클릭은 Vue Router의 push, 잘못된/숨겨진/함수 본문 키 정규화는 replace를 사용한다. URL 문자열을 실행하지 않는다.
- Shared의 기존 `ip.addresses` 북마크는 `public.ip.addresses`로 정규화한다. 과거 함수 문자열은 details로 안전하게 대체한다.
- 최초 로딩 및 자원 전환 중에는 이전 record의 조건으로 목적지 URL을 고치지 않는다.
- 탭만 바뀌면 Autogen 상세 조회를 반복하지 않는다. 실제 필터·자원·project 변경은 조회 범위에 남긴다.
- 같은 자원의 진행 중인 탭 조회는 tab query 변경 때문에 폐기하지 않는다. 자원·역할/API scope 변경은 컴포넌트 인스턴스를 분리한다.
- VM/스토리지/오퍼링의 정적 이름 탭은 기존 URL·탭 배치를 유지한다. 바뀐 공통 함수는 회귀 시험과 실환경 이동으로 확인한다.

## 실환경 범위와 차단 조건

31 GFS2와 32 Glue(Ceph) RBD에서 전용 NAT Isolated/L2/Domain Shared를 생성했다. 31은 기본 no-SourceNat 망도 생성했다. 32의 같은 기본 오퍼링은 `VpcVirtualRouter ... Firewall ... physical network id=200`으로 다시 거부됐으며 제공자·운영망 설정을 변경하지 않았다. 이 구성 문제는 #1562에 남긴다.

두 클러스터의 inventory에는 ip4routing이 설정된 오퍼링이나 IPv6/DualStack 오퍼링이 없다. 따라서 Dynamic/Static/IPv6 UI는 전용 NAT 망의 읽기 응답을 제한적으로 변형한 합성 검증으로 구분한다. 실제 BGP/IPv6 엔진, 외부 NSX/Netris/Tungsten 및 VM 종단 통신의 성공을 뜻하지 않는다. Root Admin의 실제 UI와 역할/API/프로젝트 경계의 단위 검증도 구분한다.
