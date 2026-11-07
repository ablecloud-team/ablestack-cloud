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

# #1569 가상머신용 네트워크 통합 회귀

## 현재 판정 (2026-11-07 역할·조건부 후속)

아래 본문은 최초 실행의 역사적 기록이다. 최신 역할·조건부 회귀는 [후속 보고](../guest-network-1569-regression-20261107/README.ko.md)를 따른다. 양쪽 역할 API 254절차·실제 UI 수정 24건을 확인했으며 새 결함 #1590·#1591·#1592를 게시했다. 현재 63행은 각각 실패 2·부분 47·미실행 5·환경 차단 6·적용 제외 3이며 행 전체 성공은 0개다. #1569는 Open이고 확인용 자원은 유지한다.

2026-11-07 추가 검증에서 31·32 Shared와 32 no-SourceNat의 SSH 인증을 복구하고 DHCP·로컬 DNS·동일 호스트 양방향 통신을 확인했다. [추가 결과와 UI 캡처](../guest-network-1569-guest-20261107/README.ko.md)

31 GFS2와 32 Glue RBD에 UI 소스 [9cd37d7401f](https://github.com/dhslove/ablestack-cloud/commit/9cd37d7401f4556eebaba2d4c8eb1106a84db5fb)를 배포했다. WSL ext4에서 UI를 로컬 빌드했다. 활성 정적 파일 661개의 해시가 빌드와 일치했고 배포 전후 Mold HTTP 200, WEB-INF와 관리 JAR 해시를 확인했다. 후속 테스트 변경을 포함한 [c453d3ab157](https://github.com/dhslove/ablestack-cloud/commit/c453d3ab157e5b8288d305c9a1bede02d4738798)의 집중 테스트 28개 스위트·465개와 변경 파일 린트가 통과했다. 이 커밋에 등록된 [GitHub License Check](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/42193712631)는 실패했다.

[실제 UI 캡처 20장](capture-matrix.ko.md) · [63개 시나리오·15개 주요 탭·7개 하위 탭](coverage.ko.md) · [확인용 자원](resources.ko.md) · [원시 증적](evidence/)

## UI 수정과 표준 적용

32번에서 Isolated NAT의 네트워크 업데이트를 열고 Shared 상세로 이동하면 열린 대화상자의 대상이 Shared로 바뀌는 문제가 재현됐다. 가상머신용 네트워크 작업은 네트워크나 목록으로 이동하거나 프로젝트·인증 범위가 바뀌면 닫히도록 수정했다. 동일 네트워크에서 탭만 바꾸면 열린 대화상자와 입력을 유지한다. 두 클러스터의 실제 브라우저에서 동일 대상 탭 유지와 대상 변경 시 대화상자 닫힘을 확인했다. 프로젝트 변경의 가드는 집중 테스트에서, 인증 범위 변경은 소스에서 확인했다.

기존 컴퓨트 > 가상머신과 같은 목록 검색, 상세와 탭, breadcrumb, MoldDialog 본문과 하단 버튼, 모바일 테마를 사용했다. VM NIC의 작업 버튼과 업데이트·검색 위치를 네트워크 목록과 탭에 대조했다. 1280·768·390px에서 목록, 네트워크 상세와 탭, 생성·업데이트·재시작·송수신 규칙 대화상자를 확인했다. 32에서 라이트·다크 즉시 전환, 테마를 유지한 화면 이동, breadcrumb 복귀를 확인했다.

## 두 클러스터에서 검증한 경로

| 경로 | 이번 결과 |
| --- | --- |
| Isolated DNS와 오퍼링 | DNS 2만 바꾼 뒤 DNS 1과 함께 유지됐다. 32에서 브라우저 POST의 DNS 쌍, 작업 성공, API 저장값을 연결했다. 양쪽에서 현재·변경 후보 및 영향 확인 대화상자를 거쳐 시험 NAT 오퍼링을 바꿨다. |
| Isolated NAT 및 no-SourceNat | 생성 후 실제 NIC를 연결했다. 양쪽 no-SourceNat 범위 탭과 31에서 IPv4 범위 추가를 확인했다. 31에서 잘못된 CIDR 응답 뒤 입력을 유지하고 수정해 생성했다. |
| L2와 Shared | 기본 L2 VLAN 791을 생성하고 NAT VM의 기본 NIC를 유지하며 L2 NIC를 연결했다. VM NIC 링크로 L2 상세에 이동했고 L2에서 재시작이 숨겨졌다. 31에서 L2 설명 저장을 확인했다. Shared VLAN 792의 게스트 주소와 범위를 열었고 32에서 IPv4 범위를 추가했다. |
| 실제 송신·수신 | 송신 TCP 8080 허용→차단→복구에서 HTTP 200→timeout→HTTP 200. 수동 Public IP의 TCP 18069에서 게스트 SSH 배너→timeout→배너. 최종 포워딩을 높은 게스트 포트로 되돌리고 임시 HTTP 리스너를 종료했다. |
| 재시작 | UI 일반 Isolated 재시작은 양쪽에서 작업 코드 0과 완료 상태를 확인했다. 재시작 후 VM 게이트웨이 ping과 Mold HTTP 200이 응답했다. |
| 역할 | 소유자와 DomainAdmin이 API로 시험망을 조회했다. 같은 도메인 peer와 외부 사용자는 시험망을 조회할 수 없었고 쓰기 요청은 531로 거부됐다. 31의 소유자 UI와 프로젝트 전체 모드의 개인망 표시도 기록했다. |

이전 #1572 시험망 31번 9개·32번 7개와 각 VM 2대를 정리했다. 활성 DB 잔여 0건이며 기존 네트워크·VM·오퍼링과 Kubernetes Public IP 방화벽·포트 포워딩 해시를 대조했다. 이번 확인용 자원은 각 클러스터에 시험망 네 개와 VM 세 대를 남겼다.

## 2026-11-06 기준 이슈 상태와 후속 검증

#1569는 열어 둔다. 63개 전체 시나리오 중 부분 40, 미실행 14, 환경 차단 6, 적용 제외 3이다. NAT 게스트는 양쪽에서 자체 FQDN의 A 응답과 존재하지 않는 이름의 NXDOMAIN을 받았다. 31 no-SourceNat는 DHCP 경로와 자체 FQDN 응답을 확인했다. 해당 망의 외부 이름 조회와 기본 게이트웨이 ping은 응답하지 않았다. 32 no-SourceNat와 양쪽 Shared는 SSH 키 인증이 거부돼 게스트 내부 DHCP 갱신과 DNS 질의가 남았다. 특정 프로젝트 쓰기, L2/PVLAN 패킷, Static NAT, VPC ACL 및 VPN 실행도 남아 있다. IPv6 오퍼링 플래그는 양쪽에서 꺼져 있고 ASN/BGP 피어·DNS 서버·DNS 영역은 0개다. LB 백엔드 트래픽은 사용자가 지정한 범위에 따라 생략했다. 각 항목의 실행 조건과 담당 이슈는 검증표에 기록했다.

2026-11-07 추가 검증 후 다음은 특정 프로젝트 쓰기 권한과 L2/PVLAN·Static NAT·VPC ACL·VPN 경로다. 별도로 남은 구현 이슈는 [#1586 Glue RBD 라우터 볼륨 삭제·재시도](https://github.com/ablecloud-team/ablestack-cloud/issues/1586)다. [Epic #1554](https://github.com/ablecloud-team/ablestack-cloud/issues/1554)와 [Draft PR #1577](https://github.com/ablecloud-team/ablestack-cloud/pull/1577)은 열어 둔다.

## 2026-11-07 특정 프로젝트 권한 후속 결과

특정 프로젝트의 역할별 API·UI를 실행했고 #1587·#1588·#1589 세 결함을 발견했다. 소유자·일반 멤버의 L2 생성·수정·NIC 연결은 양쪽에서 완료했다. 최신 집계는 실패 1, 부분 39, 미실행 14, 환경 차단 6, 적용 제외 3이다. 우선 #1587 → #1588 → #1589를 해결한 뒤 역할 회귀와 기존 미완료 범위를 진행한다. 확인용 자원은 새 프로젝트 자원을 포함해 유지했다. [결과·범위](../guest-network-1569-project-20261107/README.ko.md) · [캡처](../guest-network-1569-project-20261107/capture-matrix.ko.md) · [현재 자원](../guest-network-1569-project-20261107/resources.ko.md)
