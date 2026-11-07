<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->

# #1574 가상머신용 네트워크 조건부 탭

배포 소스: `efdb872c8dfdba16190587deea93c70dc88bdd2b`. [화면 캡처](capture-matrix.ko.md) · [확인용 자원](resources.ko.md) · [검증 증적](evidence/)

| 탭 | 표시와 API 계약 | 31·32 검증 |
| --- | --- | --- |
| BGP 피어 | 비 VPC Isolated Dynamic, Admin, `listBgpPeers`. 연결 목록은 현재 망 기준이며 변경 후보는 Zone과 계정 또는 프로젝트로 조회한다. `changeBgpPeersForNetwork`는 현재 선택과 다른 경우만 실행한다. VPC 피어 설정은 별도 경로다. | Dynamic 망은 할당 가능한 ASN이 없어 생성하지 못했다. Static에서 탭이 노출되지 않는 것과 직접 URL 안내를 확인했다. 실제 BGP 세션에는 ASN 풀과 피어가 필요하다. |
| IPv4 라우팅 방화벽 | 비 VPC Isolated Static/Dynamic, 소유 범위, `listRoutingFirewallRules` 및 쓰기별 `create/deleteRoutingFirewallRule`. CIDR·방향·포트/ICMP를 점검하고 입력과 실패 상태를 보존한다. | 양쪽 Static 망에 실제 VM과 가상 라우터를 올려 UI에서 규칙 추가·삭제와 API 재조회를 검증했다. 31번 가상 라우터의 nftables에 남긴 TCP 49175 수신 규칙을 대조했다. 32번 라우터 직접 대조와 양쪽 실제 허용·차단 트래픽은 확인하지 않았다. |
| IPv6 방화벽 | IPv6/DualStack 비 VPC Isolated, IPv6 방화벽이 명시적으로 꺼져 있지 않은 경우, `listIpv6FirewallRules` 및 `create/deleteIpv6FirewallRule`. Shared IPv6 주소 범위와 분리한다. | `ipv6.offering.enabled`가 꺼져 있어 IPv6 오퍼링 생성이 거부됐다. IPv4 화면에서 탭 미노출과 직접 URL 안내, IPv6 필드 검증은 회귀 시험으로 확인했다. |
| Tungsten 네트워크 정책 | Isolated/L2와 Tungsten 방송 URI, `listTungstenFabricPolicy`의 망·Zone 조회. Zone 단위 후보 조회를 쓰는 `apply/removeTungstenFabricPolicy`는 관리자 범위에서만 열며, 현재 목록에 연결된 후보를 제외한다. | 제공자 0개. VLAN 환경에서는 탭이 숨겨졌다. 정책 적용·순서 충돌은 실제 Tungsten 환경이 필요하다. |
| Tungsten 논리 라우터 | Isolated/L2와 Tungsten 방송 URI, `listTungstenFabricLogicalRouter`의 망·Zone 조회. `addTungstenFabricNetworkGatewayToLogicalRouter` 후보는 관리자·Zone으로 제한한다. | 제공자 0개. VLAN 환경에서 탭 미노출 확인. 연결과 실제 라우팅은 Tungsten 환경이 필요하다. |
| VNF 기기 | Isolated/L2/Shared에서 `listVnfAppliances(networkid)`로 실제 연결만 조회한다. 빈 목록과 실패를 구분하고, IP는 해당 망 NIC에서 표시한다. 상세 링크에는 각 API 권한을 적용한다. | Skudonet 7.2.1 비공개 VNF 템플릿 등록 후 양쪽 Isolated에 기기를 배포했다. Running 상태와 망별 NIC IP를 확인했고 Shared·L2는 빈 목록이었다. LB 백엔드 트래픽은 제외했다. 정상 업데이트를 UI에서 확인했다. 브라우저 요청을 차단하는 시험은 로그인으로 전환되어 개별 조회 실패 복구의 통과로 집계하지 않았다. |
| 사용자 지정 작업 | 오퍼링의 CustomAction 서비스와 소유 범위, `listCustomActions(resourceid,resourcetype=Network,enabled=true)`로 후보를 조회한다. `runCustomAction` 권한과 후보가 모두 있을 때만 실행한다. 민감 필드는 마스킹하고 민감 입력이 있는 작업의 원문 결과는 표시하지 않는다. | 양쪽 시험 망에 CustomAction 제공자가 없어 탭 미노출과 서버 거부를 확인했다. 전용 네트워크 확장과 안전한 테스트 작업이 필요하다. |

화면은 VM과 같은 목록·상세·탭 배치, 툴바(주 버튼 다음에 업데이트), breadcrumb와 MoldDialog를 사용한다. 390/768/1280 뷰포트의 반응형 화면, 다크모드 가독성, 즉시 테마 전환과 화면 이동을 확인했다.

기존 #1576 네트워크·NIC·VM·오퍼링·계정·도메인 잔여는 모두 0이다. Java 변경은 없으며 WSL ext4의 UI 빌드와 집중 회귀 시험을 사용했다. 제공자별 실환경 범위는 [#1569](https://github.com/ablecloud-team/ablestack-cloud/issues/1569) 통합 검증에서 추적한다. 다음 구현은 [#1573](https://github.com/ablecloud-team/ablestack-cloud/issues/1573) Public IP 상세의 NAT·방화벽·PF·LB·VPN 하위 화면이다.
