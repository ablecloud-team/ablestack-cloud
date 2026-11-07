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

# #1569 Shared·no-SourceNat 게스트 통신 추가 검증

2026-11-07, 31 GFS2의 Shared와 32 Glue RBD의 Shared·no-SourceNat를 검증했다. 기존 세 시험 VM을 UI에서 정상 재시작한 뒤 SSH 인증이 복구됐다. 세 네트워크 모두 라우터와 같은 호스트에 통신 상대 VM을 배치했고 DHCP ACK, 게스트의 DHCP 설정, 자체·상대 이름의 DNS 응답, 양방향 ICMP와 TCP 22 응답을 확인했다.

[실제 Mold UI 캡처 10장](capture-matrix.ko.md) · [확인용 자원](resources.ko.md) · [게스트 통신 증적](evidence/guest-traffic.json) · [DHCP 패킷](evidence/dhcp-packets.json)

| 시험망 | 라우터·VM 공통 호스트 | 기존 VM / 상대 VM | DHCP | DNS | 양방향 ICMP | 양방향 TCP |
| --- | --- | --- | --- | --- | --- | --- |
| 31 Shared | ablecube31-3 | 10.178.31.25 / 10.178.31.16 | ACK 및 IP·MAC·서버 일치 | 자체·상대 FQDN 주소 일치 | 각 3/3 | 각 SSH 배너 응답 |
| 32 Shared | ablecube32-2 | 10.178.32.20 / 10.178.32.21 | ACK 및 IP·MAC·서버 일치 | 자체·상대 FQDN 주소 일치 | 각 3/3 | 각 SSH 배너 응답 |
| 32 no-SourceNat | ablecube32-2 | 10.177.32.20 / 10.177.32.21 | ACK 및 IP·MAC·서버 일치 | 자체·상대 FQDN 주소 일치 | 각 3/3 | 각 SSH 배너 응답 |

## SSH 인증 복구

복구 전의 QGA 조회에서 세 시험 VM에 qa-net 사용자가 없었다. VM은 앞선 시험에서 라우터 호스트로 이동한 상태였다. UI에서 강제 옵션을 끈 정상 재시작을 수행한 뒤 CloudStack 데이터 소스가 사용자 데이터를 적용했고 원래 시험 키로 SSH 접속이 성공했다. 초기 부팅의 사용자 데이터 적용 시점이 이전 호스트 간 VLAN 제약의 영향을 받았을 가능성이 있다.

cloud-init 결과의 errors는 비어 있다. 상세 상태에는 Empty network config found 경고와 degraded done이 남아 있어 해당 상태도 증적에 기록했다. 첫 사용자 데이터 적용 때 SSH 호스트 키가 새로 생성됐다. API의 VM UUID와 QGA의 호스트 이름·MAC·IP를 대조하고 이번 실행 전용 known_hosts 파일에서 키를 확인했다.

QGA의 guest-exec와 guest-file-open은 최종 확인에서도 비활성 상태였다. 인증 복구는 정상 부팅과 기존 사용자 데이터 적용으로 이루어졌다. 실제 검증 명령은 SSH로 실행했다.

## DHCP·DNS·통신 판정

기존 VM과 새 상대 VM 모두 NetworkManager DHCP 값의 IP, MAC, 서버 식별자, DNS, 기본 경로를 Cloud NIC와 대조했다. 전용 라우터의 eth0에서 상대 VM 부팅에 따른 DHCP 교환을 캡처했고 ACK와 해당 VM의 MAC·임대 IP를 확인했다. 모든 게스트의 자체·상대 FQDN에 대해 네트워크 라우터 DNS가 기대한 IPv4 주소를 응답했다. 각 방향에서 ping 3/3과 TCP 22의 OpenSSH 배너를 받았다.

Shared와 no-SourceNat의 DHCP 기본 게이트웨이는 각 서브넷의 .1이며 가상 라우터의 DHCP·DNS 주소는 .10이다. .10과 상대 VM은 응답했지만 설정된 외부 게이트웨이 .1은 세 시험망 모두 응답하지 않았다. 이번 성공 범위는 동일 호스트의 DHCP·로컬 DNS·VM 간 통신이며 외부 라우팅·인터넷 통신은 검증 대기다.

## UI와 자원 보존

실제 Mold에서 기존 VM 세 대를 재시작하고 NIC 정보, Shared 게스트 IP, 네트워크별 VM 쌍, 가상 라우터 호스트를 확인했다. 31은 라이트, 32는 다크 테마로 캡처했다. VM 목록에서 두 시험 VM의 같은 호스트와 주소를 확인할 수 있다.

확인용 자원은 모두 남겼다. 31은 시험망 4개·VM 4대, 32는 시험망 4개·VM 5대다. 기존 시험 VM의 UUID·MAC·IP와 네트워크의 오퍼링·DNS·CIDR·게이트웨이를 보존했다. Cloud API와 libvirt에서 시험 VM 쌍과 라우터가 같은 호스트에서 Running임을 대조했다. 기존 운영 네트워크·VM·오퍼링과 Kubernetes IP 규칙의 보존 비교도 일치했다.

## #1569 잔여 범위

이번 세 네트워크의 SSH 인증·DHCP·로컬 DNS·동일 호스트 통신 차단은 해소됐다. 특정 프로젝트 쓰기 권한, L2/PVLAN 실제 패킷, Static NAT·VPC ACL·VPN, 조건부 IPv6/BGP/외부 DNS, 외부 게이트웨이 경로 등은 #1569 검증표에 남아 있다. 이슈와 Epic, Draft PR은 진행 상태로 유지한다.

[이전 63개 시나리오와 탭별 결과](../guest-network-1569-integrated/coverage.ko.md) · [Epic #1554](https://github.com/ablecloud-team/ablestack-cloud/issues/1554) · [PR #1577](https://github.com/ablecloud-team/ablestack-cloud/pull/1577)
