<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->

# #1576 가상머신용 네트워크 오퍼링

Epic #1554의 `dhslove/epic-1554-guest-networks` 및 Draft PR #1577에서 진행했다. 실행 소스는 `bd3ea8fd3bc6b188be167e6cacd0f3082a3b5aaf`다. [확인용 자원](resources.ko.md) · [화면 캡처](capture-matrix.ko.md) · [검증 증적](evidence/)

| 화면 | 반영 내용 |
| --- | --- |
| 오퍼링 생성 | 유형, 모드, 서비스 및 제공자 선택에 맞는 필드만 제출한다. L2는 선택한 경우 ConfigDrive UserData만 전달한다. NAT 서비스가 사라지면 관련 capability도 제거하며 유효한 `false`와 0을 보존한다. |
| 오퍼링 복제 | 기본 복제는 서버에서 원본 설정을 이어받는다. 같은 유형의 세부 값을 바꾸는 폼을 선택할 수 있고 원본 도메인과 Zone을 미리 표시한다. |
| 오퍼링 상세·탭 | 서비스 및 제공자 탭에 API가 반환한 제공자와 capability를 보여준다. 사용 네트워크 탭은 비활성화와 삭제의 영향을 안내하고, 사용 망으로 이동한다. |
| 공통 UI | 가상머신 목록·상세와 같은 공통 탐색 및 대화상자 구성을 사용한다. 목록, 상세, 탭, breadcrumb, 반응형 및 즉시 테마 전환을 확인했다. |

| 유형 | 31·32 확인 조합 | 경계 |
| --- | --- | --- |
| Isolated | VirtualRouter·IPv4·Source NAT, 기본 복제의 서비스·도메인·Zone과 `false`, 사용 망 및 비활성화·재활성화 | Routed와 Source NAT 조합은 API가 거부했다. no-SourceNat, Dynamic/BGP, IPv6, VPC의 실제 전달은 별도 검증이다. |
| L2 | 유형 전환 후 ConfigDrive UserData, 지정 VLAN, NIC 연결 및 사용 망 | L2 Source NAT 요청을 서버가 접수할 수 있으므로 UI가 해당 값을 보내지 않도록 한다. 외부 스위치 및 다른 VLAN/VNI 모드는 이번 범위 밖이다. |
| Shared | VirtualRouter의 DNS·DHCP·UserData 조합, 복제, 지정 VLAN/IP 범위, 사용 망 | Shared 영구 설정은 API가 거부했다. 외부 장비 기반 EIP·ELB 및 stretched subnet의 실제 전달은 별도 검증이다. |
| 권한·보호 | 허용 도메인·Zone 조회, 비허용 사용자 직접 조회 차단, 사용 중 삭제 거부, 기존 망·오퍼링·VM 인벤토리 보존 | NSX·Netris·Tungsten·NetScaler 제공자 데이터 경로는 이번 검증에 포함하지 않았다. |

검증용 게스트망과 VM은 API로 준비했다. 실제 UI에서 유형 전환, 생성·복제, 상태 변경과 사용 망 이동을 확인하고 서버 재조회와 대조했다. Shared 복제는 원본의 도메인·Zone·서비스를 보존하고 속도만 321 Mb/s로 바꿨다. 31번에서는 상세의 설명 편집도 저장 후 재확인했다. 기본/시스템 오퍼링의 삭제 동작은 UI에서 노출하지 않는다. 순서·가용성 등 공통 필드는 전체 저장 조합을 반복 검증하지 않았다.

오퍼링 비활성화 후 신규 선택 후보에서 제외되고 재활성화 후 다시 나타났다. 31번은 VM 실행 중 UI에서, 32번은 UI에서 상태 변경을 먼저 확인한 뒤 VM 실행 중 API로 추가 확인했다. 두 곳 모두 재시작 없이 같은 VM과 NIC, 기존 Isolated 서비스 구성이 유지됐다. LB 백엔드 트래픽은 이번 검증 범위에서 제외했다.

WSL ext4에서 lint, 집중 UI 시험 **497건**, production build를 통과했다. 31·32 정적 파일 661개의 해시가 일치했고 `WEB-INF` 및 운영 설정, `/client/` HTTP 200을 확인했다. 기존 #1575 자원 잔여는 양쪽 모두 0이었다. [31번 배포](evidence/31-deployment.json) · [32번 배포](evidence/32-deployment.json)

Epic 순서 18번 [#1574](https://github.com/ablecloud-team/ablestack-cloud/issues/1574)는 BGP·라우팅·IPv6·Tungsten·VNF·Custom Actions 조건부 탭을 보강한다.
