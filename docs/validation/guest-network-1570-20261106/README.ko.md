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

# #1570 Isolated 네트워크

31 GFS2와 32 Glue RBD의 활성 UI에 소스 92f291fbdd749aabf145c9cacbf66a6ea0747fe5를 반영했다. 활성 정적 파일 661개씩의 해시를 대조했고 배포 전후 /client/ HTTP 200과 WEB-INF를 확인했다. WSL ext4에서 UI 빌드, 린트, 집중 테스트 132개를 통과했다. [UI 캡처](capture-matrix.ko.md) · [확인용 자원](resources.ko.md) · [검증 증적](evidence/)

| 화면 | 변경과 확인 결과 |
| --- | --- |
| 목록·상세·공통 UI | 가상머신 화면의 검색 툴바, 상세 탭·breadcrumb, MoldDialog의 본문과 고정 푸터를 기준으로 삼았다. 31번은 검색으로 시험망 4개를 찾고 가상머신 보기 → VM 상세 NIC → 같은 네트워크 → breadcrumb로 돌아왔다. 32번은 390px에서 breadcrumb로 복귀했다. 실제 설정에서 라이트·다크를 즉시 전환하고 화면을 이동했다. 390·768·1280px에서 캡처했다. |
| 생성 | Zone·계정·프로젝트의 최신 범위에 해당하는 오퍼링·VPC·ASN만 사용한다. 목록 순번 대신 ID로 선택을 연결하고, 늦게 도착한 조회를 버리며 실패하면 다시 조회할 수 있다. 양쪽에서 NAT, no-SourceNat, VPC tier, Static Routed를 실제 UI로 생성했다. 오퍼링을 바꾸면 무관한 입력은 요청에서 제외된다. |
| VPC tier | 물리망 진단은 통과했지만 VPC가 Firewall/VpcVirtualRouter를 지원하지 않는 오퍼링이 서버에서 431로 거부됐다. 선택한 오퍼링과 VPC의 서비스·제공자를 대조해 제출 전에 안내한다. 31번에서 차단과 호환 오퍼링 전환을 확인하고, 32번은 전용 ACL로 교체해 API의 ACL ID를 확인했다. |
| Public IP | Source NAT 주소와 사용 불가 주소는 해제 대상에서 제외한다. Source NAT 변경은 대상 주소·망을 표시하고 작업 ID와 대상 리소스 ID로 비동기 결과를 확인한다. 양쪽에서 새 Source NAT를 API에 대조했다. 32번은 최종 UI에서 할당 → 전환 → 이전 IP 해제까지 수행했다. |
| 가상 라우터 | 게스트와 Public IP를 나눠 표시하고 VM 탭과 같은 위치에 업데이트 버튼을 둔다. 조회 실패는 빈 목록과 구분하고 기존 조회가 있다면 마지막 정상 목록을 표시한다. 31번은 라우터 상세 링크, 32번은 모바일 업데이트와 양쪽 라우터 상태를 확인했다. |
| 수정·재시작·삭제 | 31번에서 설명·DNS 1, 호환 NAT 오퍼링 변경과 일반 재시작을 실행했다. VM UUID와 NIC 주소를 대조했다. 별도 빈 시험망은 삭제 작업과 목록 부재를 확인했다. |
| 송신 규칙·실제 통신 | 양쪽 TCP 8080, 31번 UDP·ICMP·ALL을 실제 UI로 추가했고 31번에서 보조 규칙 3개를 일괄 삭제했다. 32번은 같은 게스트와 목적지에서 TCP 규칙 적용 시 HTTP 200, 제거 시 시간 초과를 확인했다. 규칙을 복원했다. Source NAT 전환 뒤 전용 라우터와 VM을 같은 호스트에 맞추고, 양쪽 최종 NAT VM에서 NIC·기본 경로·게이트웨이 ICMP 및 Mold HTTP 200을 확인했다. |
| 권한·코멘트·태그 | 양쪽 일반 소유자로 가상 라우터 탭과 직접 주소를 확인했다. 32번 관리자로 코멘트와 태그를 저장했고 관리자 전용 코멘트가 일반 소유자에게 표시되지 않았다. |

#1573의 시험망·VM·NIC·오퍼링·계정·도메인과 이전 망의 Public IP 연결 잔여는 양쪽 0이다. Kubernetes 관리 IP의 방화벽·포트 포워딩 규칙 수와 해시가 검증 전후 일치했다. 새 시험망과 VM은 각 4개씩 남겨뒀다.

Dynamic Routed는 사용 가능한 ASN이 없어 31번 실제 생성에서 530으로 거부됐다. 양쪽 DualStack 오퍼링 생성은 ipv6.offering.enabled 설정으로 차단됐다. 31번 no-SourceNat 게스트 SSH는 인증에서 차단됐고 32번은 NIC·기본 경로가 일치했으나 게이트웨이 ICMP 응답을 받지 못했다. 프로젝트 소유 실제 생성, Live Patch·이중화 전환, DNS 응답과 호스트 간 VLAN 트래픽은 실환경에서 확인 범위에 포함하지 않았다. [유형·탭 검증표](coverage.ko.md) · [통합 회귀 #1569](https://github.com/ablecloud-team/ablestack-cloud/issues/1569)

다음은 [#1571 L2 네트워크 VLAN·PVLAN·ConfigDrive·VM 접속 및 상세 탭](https://github.com/ablecloud-team/ablestack-cloud/issues/1571)이다.
