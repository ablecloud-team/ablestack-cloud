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

# #1571 L2 네트워크

31번 GFS2와 32번 Glue RBD의 Mold UI에 `2f187d3f2f47fc81558c5d2ab5b470f823cb4176`를 반영했다. WSL ext4에서 UI 빌드·린트·집중 테스트 105개가 통과했다. 두 서버에서 정적 파일 661개의 해시가 빌드와 일치했고, 배포 전후 `/client/` HTTP 200과 `WEB-INF` 보존을 확인했다. [UI 캡처 11장](capture-matrix.ko.md) · [확인용 자원](resources.ko.md) · [상세 검증표](coverage.ko.md) · [검증 증적](evidence/)

| 화면 | UI 변경 및 검증 |
| --- | --- |
| 목록·breadcrumb·반응형 | 기존 VM 목록과 같은 공통 검색·툴바·상세 breadcrumb를 사용했다. 검색으로 시험망을 찾고 목록 → 네트워크 상세 → 관련 VM 화면을 열었다. 모바일 breadcrumb로 목록에 복귀했다. 390·768·1280px, 화면 이동 후 테마 유지와 실제 테마 설정에서의 즉시 전환을 확인했다. |
| 생성 대화상자 | VM 작업과 같은 MoldDialog의 스크롤 본문·하단 버튼을 사용하고 오퍼링, VLAN/VNI, PVLAN 선택을 위에서 아래로 배치했다. Zone·계정·프로젝트가 바뀌면 기존 선택과 늦게 도착하는 응답을 분리한다. VLAN 방식에만 PVLAN을 노출하고 타입 전환 시 남은 Secondary를 제거한다. VLAN 중복 검사 우회는 관리자만 선택할 수 있으며 기본 요청에서는 빠진다. |
| 상세·탭 | VM과 같은 공통 상세·탭을 유지했다. 기본 L2와 ConfigDrive의 IP 설정 안내를 구분하고, PVLAN 기본 ID·유형·보조 ID를 보여준다. DNS·라우터 등 L2에 적용되지 않는 L3 항목을 숨기고 미지원 탭 직접 주소에서는 안내와 함께 상세로 돌아간다. |
| 수정·삭제·VM NIC | L2 수정에서 Source NAT 입력을 숨겼다. 31번 설명 변경을 API에 대조했다. 32번에서 NIC가 연결된 망은 삭제가 차단됐고, 별도 빈 망은 삭제됐다. 두 클러스터의 가상머신 NIC 탭에서 L2 NIC 추가·제거와 MAC·연결 망을 대조했다. |

기본 VLAN·ConfigDrive·커뮤니티·격리·무차별 네트워크를 31·32에서 UI로 만들었다. 같은 호스트의 시험 VM 두 대에서 VLAN 772는 양방향 ping이 성공했고, 한 대를 VLAN 773으로 옮긴 뒤에는 양방향 모두 응답이 없었다. ConfigDrive VM 4대에서 마운트된 `config-2` ISO와 VM별 사용자 데이터 파일을 확인했다. 게스트의 cloud-init 실행·설정 지속성과 PVLAN의 실제 패킷 허용·차단, 호스트 간 VLAN은 이 환경에서 검증하지 못했다.

이전 #1570의 시험 자원과 이번 패킷 시험용 임시 자원은 정리하고 DB에서 활성 잔여 0건을 확인했다. 양쪽 기존 Kubernetes 관리 IP 규칙의 수와 해시를 보존했다. 31번에서 비교 기간 중 별도의 Kubernetes 규칙 1세트가 추가됐으며, 기존 7세트는 모두 일치했다. 32번의 기존 1세트는 그대로였다. 검토용 네트워크 5개와 VM 2개씩은 남겨뒀다.

Epic의 다음 구현은 [#1572 Shared 네트워크](https://github.com/ablecloud-team/ablestack-cloud/issues/1572)다. 실환경 추가 검증 조건은 [#1569 통합 검증](https://github.com/ablecloud-team/ablestack-cloud/issues/1569)에 연결한다.
