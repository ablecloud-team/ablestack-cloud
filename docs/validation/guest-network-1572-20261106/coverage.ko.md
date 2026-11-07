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

# Shared 유형·상세 탭 검증표

| 공유 범위·조건 | 확인 결과 |
| --- | --- |
| 모두 | 양쪽 관리자 UI 생성. API는 Domain ACL, ROOT, 하위 도메인 접근 true를 반환한다. 외부 도메인 계정도 목록에서 모두 망을 조회한다. |
| 도메인 및 하위 도메인 | 양쪽 관리자 UI 생성. 해당 도메인과 하위 계정에게 보이며 외부 도메인에는 보이지 않는다. 31번 하위 미허용 망은 같은 도메인에서 보이고 하위 계정에서 보이지 않았다. 32번 하위 계정의 실제 목록을 캡처했다. |
| 계정 | 양쪽 관리자 UI 생성. 소유자만 사용 가능하고 같은 도메인의 일반 동료에게는 표시되지 않았다. 관리자와 소유자의 화면 및 실제 VM DHCP·DNS를 확인했다. |
| 프로젝트 | 양쪽 관리자 UI 생성 및 프로젝트 UUID 확인. 소유자의 프로젝트 컨텍스트에서 보이고 미참여 계정은 API 432를 받았다. 32번 소유자 UI에서 본인 프로젝트 후보를 확인했다. 프로젝트 VM 배포는 미실행이다. |
| 연관망·역할 | VLAN 직접 지정 없는 오퍼링에서 같은 소유자의 L2를 선택했고 연관 UUID와 상속 VLAN 785를 확인했다. 31번 관리자, 32번 일반 소유자가 실제 생성했다. 일반 사용자에게 모두·도메인·VLAN·중복 우회를 표시하지 않았다. |
| 주소 유형·PVLAN | 31·32 듀얼 스택 저장 및 IPv4·IPv6 범위 확인. 32번 IPv6 DNS 누락 사전 차단. 31번 커뮤니티 PVLAN에서 동일 기본/보조 ID 차단, 유형 전환 후 보조 ID 초기화, 789/790 생성. IPv6 전용 VirtualRouter 조합은 서버 비지원으로 화면에서 막는다. |

| 상세 영역 | UI/API 및 적용 경계 |
| --- | --- |
| 상세·수정 | 실제 ACL, 오퍼링, 관련 망, CIDR, DNS 및 MTU, 게스트 IP 사용량 필드를 확인했다. 31번 설명 수정 및 API 저장값 확인. Source NAT 및 Isolated 오퍼링 변경은 미노출. 재시작은 이번 Shared 화면에서 실행하지 않았다. |
| 게스트 IP 주소 | `listPublicIpAddresses`에 Shared network ID, 비 Public 범위를 적용한다. 주소·VM 링크·Free를 확인했다. 31번 198.19.31.13, 32번 198.19.32.18을 UI NIC 연결 후 할당, 제거 후 반납했다. |
| 주소 범위 | 양쪽 조회·추가·수정. 32번 겹침 차단·정상 재시도·빈 범위 삭제·사용 중 범위 삭제 거부. 31번 확인용 보조 IPv4 범위 유지. IPv6 범위는 생성과 조회를 확인했고 범위 수정·삭제는 미실행이다. |
| 가상 라우터·게스트 | 32번 라우터 탭, 양쪽 가상 라우터 Running과 시험 VM MAC·DHCP IP를 API와 대조했다. 라우터와 같은 호스트의 두 게스트에서 DNS 및 ICMP 성공. 라우터 없는 외부 제공자와 교차 호스트 VLAN 실통신은 미실행이다. |
| 권한·이벤트·코멘트·태그 | 31번 네트워크 권한·이벤트·코멘트 탭 접근. 이번 작업에서 태그 쓰기, 개별 권한 부여·회수는 실행하지 않았다. 개별 공유 변경은 [#1575](https://github.com/ablecloud-team/ablestack-cloud/issues/1575)에서 다룬다. |
| VNF·Tungsten·사용자 지정 | 31번 VNF 탭 진입. 장비 연결 검증은 [#1574](https://github.com/ablecloud-team/ablestack-cloud/issues/1574) 범위다. Tungsten과 CustomAction 지원 오퍼링의 Shared 실환경은 미검증이다. |
| DNS 영역 | DNS 영역 연결 대화상자와 후보 없음·재조회 확인. 양쪽 DNS 서버 0개·DNS 영역 0개라 실제 연결·해제와 외부 DNS 레코드 질의는 미실행이다. |

PVLAN별 실제 전달 정책, IPv6 게스트의 주소·DNS·통신, 외부 DNS 영역과 미실행 소유권 조합은 [#1569](https://github.com/ablecloud-team/ablestack-cloud/issues/1569)로 넘긴다.
