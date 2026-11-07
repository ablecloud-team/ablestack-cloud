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

# L2 유형·탭 검증표

| 유형·조건 | UI ↔ API 및 실행 결과 |
| --- | --- |
| 기본 L2 | 양쪽 VLAN 772 UI 생성. Zone·소유 계정·오퍼링·broadcast URI 대조. UI로 NIC를 붙여 같은 호스트의 두 VM 간 왕복 3/3 성공. |
| ConfigDrive | 양쪽 VLAN 773 UI 생성. UserData 제공자 ConfigDrive, 기본 NIC, VM별 ISO 사용자 데이터 일치. 772 ↔ 773은 양방향 0/3. 실제 cloud-init 적용과 IP 유지 미확인. |
| 커뮤니티 | 양쪽 Primary 774·Secondary 775 UI 생성과 broadcast URI 대조. 31번에서 Primary와 Secondary 동일값, 32번에서 Secondary 누락을 API 호출 전 차단했다. |
| 격리·무차별 | 양쪽 격리 776/777, 무차별 778 UI 생성과 URI 대조. 커뮤니티에서 무차별로 전환할 때 Secondary가 지워지고 요청에서 제외됨을 확인했다. 테스트 호스트의 LinuxBridge 환경에서 PVLAN 트래픽 정책은 검증하지 않았다. |
| 범위·실패·권한 | 32번 VLAN 4095는 요청 전 차단. 이미 사용 중인 VLAN 772는 서버 431을 화면에 표시했고, 입력을 보존해 779로 재시도했다. 32번 일반 소유자에게 VLAN 및 중복 우회가 보이지 않았다. 우회 기본값은 요청에 포함하지 않았다. |
| 다른 제공자·소유 범위 | VNI 범위와 미지원 방식은 소스·단위 테스트의 계약이다. 이 클러스터에서 실제 VXLAN·NSX·Tungsten 및 프로젝트 소유 생성은 미실행. |

| 상세 영역 | 확인 결과 |
| --- | --- |
| 상세·수정·삭제 | `listNetworks`와 PVLAN 상세 비교, 31번 `updateNetwork`로 설명 변경, 32번 연결 중 삭제 차단 및 빈 망 삭제. Source NAT와 L3 입력·필드를 숨겼다. L2 재시작은 노출 조건을 점검했고 실환경 재시작은 미실행. |
| VM 연결·가상머신 보기 | 양쪽 기존 VM NIC 화면에서 `addNicToVirtualMachine`·`removeNicFromVirtualMachine` 경로를 실행하고 기본·보조 NIC, MAC, broadcast URI 대조. VM 생성 마법사에서의 전체 배포 절차, MTU 변경은 이번 UI 검증에 포함하지 않았다. |
| Public IP·가상 라우터·게스트 IP 범위·송신 | 일반 L2에서 적용되지 않는다. 31번 Public IP, 32번 가상 라우터 직접 주소가 상세로 돌아오는 것을 확인했다. 다른 구형 탭 키와 접근 조건은 코드·집중 테스트로 확인했다. |
| IPv6·라우팅 방화벽·BGP | 일반 L2에서 적용되지 않는다. 라우팅 계열 탭은 공통 기능별 조건과 직접 주소 복귀를 재사용한다. |
| 네트워크 권한·이벤트·코멘트·태그 | 31번 권한·이벤트·코멘트 조회 화면을 방문했다. CRUD 및 부여·회수는 [#1575](https://github.com/ablecloud-team/ablestack-cloud/issues/1575) 책임으로 이번 L2에서 재실행하지 않았다. 태그 쓰기도 미실행이다. |
| VNF·Tungsten·사용자 지정 작업 | 31번 VNF 탭에서 연관 없음 표시 확인. 장비 등록·연결은 [#1574](https://github.com/ablecloud-team/ablestack-cloud/issues/1574) 범위다. Tungsten 및 CustomAction을 지원하는 오퍼링의 실환경 실행은 미실행이다. |
| VM 스타일·테마 | 390·768·1280px 실제 뷰포트에서 캡처 검증. 32번 라이트 ↔ 다크 즉시 변경, 화면 이동, 모바일 breadcrumb 복귀를 확인했다. 캡처의 문서·대화상자 가로 넘침을 검사했다. |

동일 호스트의 VLAN 분리만 실패킷으로 검증했다. 호스트 간 경로, PVLAN별 전달 정책, 게스트 cloud-init 재부팅 후 결과와 프로젝트·공유 권한 시나리오는 [#1569](https://github.com/ablecloud-team/ablestack-cloud/issues/1569)에서 추가 검증할 범위다.
