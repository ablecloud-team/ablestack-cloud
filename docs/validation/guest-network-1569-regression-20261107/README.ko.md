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

# #1569 역할 통합 회귀와 조건부 검증 (2026-11-07)

31 GFS2·32 Glue RBD의 실제 Mold UI·API·DB를 대조했다. **핵심 역할 API 절차 254건과 실제 UI 설명 수정 24건을 통과했으나 전체 완료가 아니다.** 새 결함 #1590·#1591·#1592를 Epic #1554 하위 이슈로 게시했으며 #1569와 Draft PR #1577을 유지한다.

제품 소스·UI/CSS·배포물 변경은 없다. 직전 #1589 소스 77782614bbc6c40e864ed890945946ea5be79819의 배포를 검증했다. 따라서 이번에 UI를 개선했다고 보고하지 않는다. UI 변경이 필요한 VM 요약 링크는 #1592, LB 누락 프로토콜 표시·오류 처리는 #1590의 설계에 포함했다.

[원본 캡처 147개](capture-matrix.ko.md) · [확인용 자원과 직접 링크](resources.ko.md) · [전체 63행 검토표](../guest-network-1569-integrated/coverage.ko.md)

## 역할 검증

| 범위 | 양쪽 실제 결과 | 증적 |
| --- | --- | --- |
| root / 프로젝트 owner / 일반 member / DomainAdmin | 각 Isolated/L2/Shared 생성·조회·설명 수정·NIC 연결/해제 성공. 임시 생성망은 정리하고 유형별 대표망 유지 | [역할 API](evidence/roles.json) |
| 계정 단위 제한 멤버 / 사용자 단위 제한 멤버 | 세 유형 조회 성공, create/update/addNic/removeNic 직접 API 거부. 원래 NIC 6개 유지. UI 수정 메뉴와 NIC 쓰기 버튼 제한 | [UI](evidence/ui-results.json) |
| 동일 도메인 비멤버 / 외부 도메인 | 대상 프로젝트 탐색·조회·쓰기 거부, UI 선택기에서 주 시험 프로젝트 없음 | [역할 API](evidence/roles.json) |
| 실제 UI 수정 | 4개 허용 역할 × 3개 유형 × 2개 클러스터 = 24개 설명 수정 작업이 status=1/code=0. 최종 설명도 API로 확인 | [비동기 작업](evidence/ui-async-jobs.json) |
| Shared 범위 | all/domain-only/subdomain/account × 8개 호출 주체 × 2개 클러스터 = 64개 조회 조합 일치. 프로젝트 Shared는 위 역할 절차에 포함 | [범위](evidence/shared-scopes.json) |
| 개인 L2 공유 수명주기 | 계정+프로젝트 부여, 중복 멱등성, 혼합 도메인 원자적 거부, 비소유자·프로젝트 외부 공유 거부, 회수·초기화·DomainAdmin 재부여. 44개 절차 통과 | [권한](evidence/permissions.json) |

역할 API 254건은 허용 154건·의도한 거부 100건이다. 생성 프로브의 정리·조회도 포함한 API 절차 수이며 254개의 독립 기능 전체를 뜻하지 않는다. `readonly` 시험 역할은 네 개 네트워크 API만 거부한다. 프로젝트 멤버십·10개 규칙과 순서·한도를 바꾸지 않았다.

회수 후 기존 NIC UUID/MAC/default/IP를 유지하고 신규 연결은 `permission denied`로 거부됐다. NIC 표는 링크를 숨겼으나 VM 요약은 접근 불가 링크를 유지하는 #1592를 발견했다. 확인용으로 프로젝트·peer 공유 2행을 복원했고 VM은 각각 7 NIC를 유지했다.

## 조건부 경로

| 경로 | 실행한 범위 | 남은 범위 |
| --- | --- | --- |
| Static NAT | 추가 IP 할당→활성→VM 매핑 재조회→PF 충돌 거부→해제·재조회. Source NAT IP에 Static NAT 거부 | 실제 Static NAT 패킷·여러 VM/주소 조합 |
| LB | protocol 생략 생성 후 수정 실패 재현. 별도 TCP 명시 규칙 생성·알고리즘 수정·백엔드 연결/해제/재연결·LbCookie 생성/삭제/재생성. 실제 UI 실패와 정책 조회 확인 | #1590 수정 후 재시험, 실패/재시도·TLS·health/autoscale. LB 데이터 평면은 사용자 지시로 제외 |
| 같은 Public IP의 공존 | conserve=true Isolated IP에서 FW 18570·PF 18571·LB 18572 공존, Static NAT 충돌 거부. 수동 IP의 VPN 탭 숨김과 Source NAT 변경 금지 안내 | VPC·conserve=false 등 나머지 조합 |
| VPC / ACL | 전용 VPC start=false, Tier 생성, ACL 교체·복원과 TCP 규칙 생성/수정/삭제/재생성. UI Tier→ACL 연결 및 규칙 탭 확인 | VPC 기동·통신·Live Patch·이중화·삭제 영향 |
| PVLAN / 중복 | Isolated PVLAN 2460/2470(31), 2461/2471(32) 생성·관리자 상세 대조. primary=secondary 거부, 중복 VLAN 거부, 일반 사용자 bypass 거부 | Community/Promiscuous 전환·root bypass 허용·실제 격리 패킷 |
| 메타데이터 | Network/IP/FW/PF/LB 태그 추가/필터/삭제, Network/IP 코멘트 생성·관리자 가시성·비소유자 거부·삭제, 이벤트 API 페이지 분리. UI 한글 태그·HTML 리터럴·이벤트 검색 0건과 복구 | 다른 역할·자원·장문·읽기 오류 주입 조합 |
| 환경 조건 | IPv6 플래그 false, AS/ASN 범위/BGP peer 0, DNS Zone/Server 0, Tungsten 제공자 0 재확인. Skudonet 템플릿은 양쪽 isready=true | 실제 환경 활성화가 필요한 IPv6/BGP/외부 DNS/TF 기능. VNF 새 배포·링크 회귀는 준비 가능하지만 이번 미실행 |

[조건부 API 128건](evidence/conditional.json)은 성공 126건·LB 수정 실패 2건이다. [가용성·VPC·PVLAN](evidence/gates-vpc-pvlan.json)은 마지막 실행을 시나리오별로 정리한 58건 중 56건 통과·Tungsten 조회 실패 2건이다. 준비 중의 잘못된 ACL UUID/API 이름 2건은 [하네스 입력 오류](evidence/excluded-harness-inputs.json)로 구분했으며 실제 UUID와 listApis의 API 이름으로 재실행해 통과했다.

L2 패킷은 기존 SSH 계정에 sudo 권한이 없고 QGA guest-exec가 비활성이라 인터페이스 설정을 수행하지 않았다. [진단](evidence/l2-guest-privilege.json). 기존 Shared/32 no-SourceNat의 DHCP·DNS·동일 호스트 통신 성공은 [직전 검증](../guest-network-1569-guest-20261107/README.ko.md)의 결과이며 이번 실행으로 중복 계산하지 않는다.

## VM UI 표준과 비교

| 표준 | 이번 확인 |
| --- | --- |
| 목록·상세·breadcrumb | VM과 네트워크의 공통 목록/요약/상세 구조, 프로젝트 경로 유지와 상세 복귀. 실제 UI 수정 24건 저장·재조회 |
| 탭·도구 모음 | VM NIC와 Isolated 송신 규칙·Public IP·LB, Shared IP, VPC ACL 탭을 대조. 송신 규칙에서 작업 버튼이 안내 앞에 배치되는 기존 표준 확인 |
| 대화상자 | VM NIC 연결과 네트워크 업데이트, 송신 규칙·LB 정책의 세로/의미 그룹 배치. CIDR 431 오류 때 입력 유지 후 올바른 CIDR 재시도 성공 |
| 즉시 프로젝트 전환 | 사용자 단위 제한 역할의 기본 보기→프로젝트에서 생성 권한 즉시 갱신. 390px 기본/프로젝트 목록 분리, 선택 창 366px·우측 366px로 화면 내 표시 |
| 반응형 | 1280×900 / 768×1024 / 390×844. 문서 가로폭이 viewport를 넘지 않음. 표의 지역 스크롤과 모바일 NIC 카드, 모달 하단 버튼 도달 확인 |
| 다크·라이트 | 31 라이트→다크→라이트, 32 다크→라이트→다크. 입력·레이블·경고·상태와 읽기 제한 화면 대조. 원래 테마·뷰포트 복원 |
| 불일치 | #1592의 VM 요약 링크는 표준과 불일치. #1590은 실제 UI 수정 실패. 모든 역할×탭×뷰포트 조합의 전수 통과를 뜻하지 않음 |

캡처는 원본 JPEG 147개이며 서명·디코딩 크기·문서/뷰포트 크기·SHA256을 확인했다. UI 소스나 스타일을 변경하지 않았고 스크린샷을 편집하지 않았다.

## 발견 결함과 해결 순서

1. **[#1590 P1](https://github.com/ablecloud-team/ablestack-cloud/issues/1590)** — protocol 생략 LB 생성은 성공하나 API/UI 수정이 NPE로 실패한다. 최초 API 실패 후 leastconn/Add, UI 재현 후 roundrobin/Add가 남았다. 프로토콜 기본값·null 안전성·실패 시 저장/제공자 상태와 UI 표시를 함께 수정한 뒤 회귀한다.
2. **[#1592 P2](https://github.com/ablecloud-team/ablestack-cloud/issues/1592)** — 공유 회수 후 VM 요약 링크와 NIC 표의 접근 가능 여부를 일치시킨 뒤 역할·전환을 재시험한다.
3. **[#1591 P2](https://github.com/ablecloud-team/ablestack-cloud/issues/1591)** — 제공자 없는 정상 Zone의 Tungsten 조회를 빈 결과로 처리한다. 실제 TF 기능은 미구성으로 적용 제외다.
4. #1569에서 남은 L2/PVLAN/ConfigDrive 패킷, Shared 변경·범위 회수·DHCP 갱신을 권한을 갖춘 전용 VM으로 같은 호스트에서 검증한다. 기존 게스트 보안 정책을 완화할 필요는 없다.
5. LB TLS/health/autoscale와 오퍼링 maxconnections/keepalive 실제 설정, VPC/보존 모드 조합, Skudonet VNF 새 배포·링크·역할 UI 생성/NIC의 나머지 조합을 진행한다. LB 데이터 평면은 범위에서 계속 제외한다.
6. IPv6/BGP/외부 DNS/TF·Custom Action은 환경 또는 기능 구성이 있을 때 조건부로 재검증한다. #1586 RBD 정리 결함은 별도 병렬 후속이며 정상 재시도 재현이 필요하다. 전체 필수 행을 판정한 후 #1569를 닫고 Epic/PR 종결 여부를 검토한다.

각 새 이슈에 재현·원인 후보·개선 설계·완료 기준을 기재했다. 원자료는 [결함 대조](evidence/defects.json), [UI 동작](evidence/ui-results.json), [실제 비동기 결과](evidence/ui-async-jobs.json)다. 실패 LB와 확인용 자원을 보존했다.

## 런타임·운영 보존

양쪽 JAR SHA256은 `990d6adf0b6b4e3fcef85527414aad465be3f9642dbed02f908e6e0568def860`이며 #1589 배포 클래스 4개 해시가 일치한다. 서비스 active·HTTP 200·WEB-INF·UI 661개 파일·FTCTL 표식·모바일 선택 폭 표식을 보존했다. 최근 15,000줄 로그의 linkage 오류는 0건이다. [런타임](evidence/runtime.json)

운영망/VM/오퍼링은 31번 28/121/30, 32번 11/58/21개에서 누락·변경 0건이다. 원래 운영 VPC 각 1개와 Kubernetes 규칙 세트 8/1개의 해시가 동일하다. 추가한 QA VPC는 운영 개수에서 분리했다. [최종 상태](evidence/final-state.json)

제품 변경이 없어 Maven·UI 재빌드·GitHub Actions 전체 빌드는 수행하지 않았다. 이번 실제 검증을 이전 단위 테스트·전체 CI 성공으로 대체하지 않는다. 기존 Epic 브랜치 `dhslove/epic-1554-guest-networks`와 Draft PR #1577에 문서·증적만 추가한다.
