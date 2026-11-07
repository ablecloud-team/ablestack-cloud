<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->

# #1573 Public IP 상세

배포 소스는 `4e50975df463464b361db780625a21e094cbc840`다. [화면 캡처](capture-matrix.ko.md) · [확인용 자원](resources.ko.md) · [검증 증적](evidence/)

| 화면 | 변경 및 검증 |
| --- | --- |
| 목록·상세 | 주소 상태, 오퍼링 서비스, NAT 유형, VPC와 API 권한으로 탭과 작업을 정한다. Free·Reserved·해제 중·시스템 IP는 규칙 작업을 열지 않는다. 미지원 탭 주소는 상세로 복귀한다. 실제 UI의 이벤트와 코멘트 탭을 열고 breadcrumb로 목록에 돌아왔다. |
| Static NAT | 선택한 계정·프로젝트와 네트워크의 VM을 조회하고 일치하는 NIC의 주·보조 주소만 허용한다. 양쪽에서 보조 IP로 활성화했다. 31번은 비활성화와 재활성화, 일반 시험 IP 해제까지 확인했다. Public IP 할당은 전용 시험 자원 준비 과정에서 API로 확인했다. |
| 방화벽 | 제출 전에 CIDR과 프로토콜 값을 검사한다. 기존 규칙 교체 중 새 생성이 실패하면 기존 값으로 복원할 수 있다. 양쪽에서 규칙을 추가했고, 32번에서 중복 포트 실패·이전 규칙 복원·충돌용 규칙 일괄 삭제를 실제 UI로 검증했다. |
| 포트 포워딩 | 망이나 VM을 바꾸면 이전 선택을 비우고 현재 NIC 후보에 있는 IP만 제출한다. 31번 주 IP 연결과 게스트 포트 2222 편집, 32번 보조 IP 연결을 API와 대조했다. |
| 부하 분산 | 생성과 편집·대상 선택에 표준 대화상자를 적용했다. 31번은 생성과 동시에 대상을 연결했고 32번은 규칙만 만든 뒤 대상을 연결했다. 31번 SourceBased 세션 고정의 10k/30m 값을 재조회했다. TCP 규칙의 SSL 메뉴 비활성화도 확인했다. |
| VPN | VirtualRouter가 VPN과 Source NAT를 제공하는 망에서 일반 IP는 Remote Access VPN을 열지 않는다. VPC 게이트웨이는 별도 경로다. 양쪽 Source NAT VPN의 생성·해제·재생성과 최종 Running을 확인했다. 공유 키는 상세에서 기본으로 가리고 성공 알림·캡처·증적에 기록하지 않았다. |

31·32번 Kubernetes 관리 IP에 연결된 방화벽·PF 목록의 규칙 수와 해시가 일치했다. 31번 전용 가상 라우터에서 방화벽과 PF DNAT, LB 포트 설정을 읽었다. 32번 라우터 직접 조회는 SSH 접근에서 차단되어 UI와 API 결과까지 확인했다.

VPC conserve mode, Free·Reserved, 시스템 IP와 API 권한 분기는 로컬 시험으로 검증했다. VPC 쓰기·예약/예약 해제·VPN 클라이언트 접속·실제 패킷·LB 백엔드 부하 시험, SSL 인증서 적용·AutoScale 전환·외부 제공자 헬스 모니터의 실환경 검증은 이번 결과에 포함하지 않았다. 통합 범위는 [#1569](https://github.com/ablecloud-team/ablestack-cloud/issues/1569)에서 추적한다.

가상머신 목록과 상세의 툴바, 탭, breadcrumb, 대화상자를 기준으로 적용했다. 390px 모바일, 768px 태블릿, 1280px 데스크톱에서 화면을 캡처했다. 라이트·다크 즉시 전환, 다크 상태의 화면 이동과 breadcrumb 복귀를 실제 UI에서 확인했다.

이전 #1574 시험망·VM·오퍼링의 활성 잔여는 양쪽 모두 0이다. 별도 요청으로 등록했던 Skudonet 템플릿과 소유 계정·도메인은 이후 재사용할 수 있도록 유지했다. 다음 구현은 [#1570](https://github.com/ablecloud-team/ablestack-cloud/issues/1570) Isolated 네트워크 전체 경로다.
