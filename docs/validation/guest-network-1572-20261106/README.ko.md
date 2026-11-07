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

# #1572 Shared 네트워크

31번 GFS2와 32번 Glue RBD에 UI 소스 `a2cd9f8f366cb9015a4a488b48b3af1f0c991bae`를 적용했다. WSL ext4에서 UI 빌드, 린트, 집중 테스트 175개가 통과했다. 두 클러스터의 활성 정적 파일 661개의 해시가 빌드와 일치했고, 배포 전후 HTTP 200과 `WEB-INF` 보존을 확인했다.

[UI 캡처 19장](capture-matrix.ko.md) · [확인용 자원](resources.ko.md) · [유형·탭별 검증표](coverage.ko.md) · [배포·UI·API·게스트 증적](evidence/)

| 화면 | UI 변경과 확인 결과 |
| --- | --- |
| 목록·탭·breadcrumb·테마 | 컴퓨트 > 가상머신과 같은 공통 목록 검색, 탭, breadcrumb, 모바일 테마를 사용했다. 390·768·1280px과 실제 테마 설정의 라이트↔다크 즉시 전환, 화면 이동 후 테마 유지, 모바일 breadcrumb 복귀를 확인했다. 목록 갱신에서 선택 중인 생성 탭이 초기화되지 않게 했다. |
| 생성 대화상자 | 가상머신과 같은 MoldDialog의 스크롤 본문과 하단 버튼을 적용했다. Zone·물리망·범위·오퍼링은 UUID로 선택하고 범위 변경 때 이전 응답과 선택을 버린다. 오퍼링 서비스·제공자, IPv4·IPv6·듀얼 스택 입력, VLAN/VNI와 PVLAN 조건을 표시한다. 양쪽에서 모두·도메인·계정·프로젝트·연관망·듀얼 스택을 생성했다. 31번은 하위 도메인 미허용과 커뮤니티 PVLAN을 추가로 확인했다. |
| 상세 및 주소 탭 | 모두(ROOT와 하위 도메인), 도메인, 계정, 프로젝트를 구분했다. Shared의 IP 주소는 게스트 NIC 주소임을 안내하고 일반 Shared에서 Public IP 작업을 표시하지 않는다. 양쪽에서 보조 NIC를 연결하고 할당 주소와 VM 링크를 확인한 뒤, NIC를 제거해 주소가 다시 가능 상태로 돌아오는 것을 확인했다. |
| 주소 범위·수정 | 관리자에게 권한 있는 다른 계정의 망도 정확한 UUID로 다시 확인한다. 겹치는 범위는 제출 전에 막는다. 31번 추가·수정, 32번 추가·수정·삭제와 사용 중 삭제 거부를 확인했다. 수정의 주소 유형은 읽기 전용 텍스트로 표시해 다크 모드에서도 읽을 수 있다. Shared 수정에서는 무관한 Source NAT와 Isolated 오퍼링 변경을 숨겼다. |
| DNS 영역·라우터 | DNS 영역 연결 대화상자는 후보를 명시적으로 선택한다. 후보 없음·재조회·확인 비활성화를 확인했다. 양쪽에 등록된 DNS 서버와 DNS 영역은 각각 0개다. 공유망의 가상 라우터, 실제 DHCP와 가상 라우터 DNS는 아래와 같이 별도로 검증했다. |

각 클러스터에서 시험 VM 두 대의 NIC MAC·DHCP 주소를 Mold API 및 게스트에 대조했다. 같은 호스트에서 양방향 ICMP 3/3과 상대 VM 이름의 DNS 응답을 확인했다. 32번 첫 VM은 라우터와 다른 호스트에서 기동했을 때 주소를 받지 못했다. 시험 VM을 라우터 호스트로 옮기고 재부팅한 뒤 양방향 통신을 확인했다. 스위치 설정은 변경하지 않았다.

이전 #1571 자원은 양쪽에서 제거했고 DB 활성 잔여 0건이다. 이번 확인용 자원은 31번 Shared 8개·VM 2대, 32번 Shared 6개·VM 2대이며 연관 L2 부모 망을 각 1개 남겼다. 실제 IPv6 게스트 통신·PVLAN별 패킷 전달·호스트 간 VLAN·외부 DNS 영역 연결/해제 및 DNS 질의는 미검증이다.

다음은 [#1569 31·32 API·UI·통신·권한 통합 회귀](https://github.com/ablecloud-team/ablestack-cloud/issues/1569)다.
