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

# #1658 VPC·VPC 오퍼링 VM 표준 UI 통합 설계

상위 Epic #1650, 누적 브랜치 `dhslove/epic-1650-vpc`, 기준 `e0d33060bcbc8e639791bdcee1dbf4732fa1a690`.
표준은 컴퓨트 > 가상머신과 MOLD-DIALOG-1.0(#1213 MD-01~12)이다. #1212의 콘텐츠 보존 원칙을 따른다.
이 문서는 구현 전 실환경 조사와 채택 범위를 기록한다. 최종 실행 결과와 미검증 범위는 README에서 구분한다.

## 실환경에서 확인한 설계 근거

- 2026-11-14, 31 GFS2 / 32 RBD의 12종 자원 UUID·설정 기준선을 새로 저장했다. 기존 VPC는 2/1, 게이트웨이·정적 경로는 양쪽0이다.
- 양쪽 실제 API에서 전용 긴 한국어 이름의 VPC(start=false)와 고유 IP/VLAN 게이트웨이를 생성하고 jobstatus=1을 확인했다. 충돌 IP·잘못된 VLAN 시험은 반복하지 않는다.
- 배포 전 실제 VPC 정적 경로 탭의 CIDR/via/next-hop 인라인 폼을 확인했다. UI 값·API 의미를 보존하면서 버튼→공통 대화상자로 옮긴다.
- 실제 게이트웨이 요약 카드에는 VPC 링크가 이미 있다. breadcrumb에는 상위 VPC가 없고 요약 링크의 복귀 탭도 없으므로 breadcrumb에 부모 VPC와 게이트웨이 탭 복귀를 명시한다.
- VPC/오퍼링은 이미 공통 Breadcrumb/ListView/InfoCard/DetailsTab/ResourceView를 사용한다. 이를 다른 레이아웃으로 복제하지 않는다.
- 공통 MoldDialog/MoldDialogActions와 표준 토큰이 현재 소스에 있다. #1212/1213을 닫거나 공통 쉘을 새로 구현하지 않는다.

## 여섯 UI 영역과 적용 결정

| 영역 | 현재 기반/차이 | 채택 변경 | 검증 |
| --- | --- | --- | --- |
| breadcrumb | VM 공통 컴포넌트. gateway 부모는 요약 카드에만 존재 | privategw에 부모 VPC·pgw 복귀 query 추가 | 실제 gateway→부모 탭→뒤로 가기, 긴 이름/UUID |
| 목록 | VM 공통 검색/열/선택/페이지 기반 | VPC 전용 목록을 기존 themed toolbar에 연결 | 검색/열/선택·상태/빈·오류·갱신 비교 |
| 상세 | 공통 요약/ID/owner/Zone/오퍼링/관련 링크 | refresh 중 공통 카드 유지, 작업 대상 ID/owner 문맥 명시 | 실제 API 값/관련 링크/작업 대상 일치 |
| 탭 | VPC 수동 history와 shared offering/gateway tabs | router push·URL/뒤로 가기·조건부 탭 fallback | URL query 보존, 늦은 조회/다른 자원 차단 |
| 콘텐츠 | 전폭 dashed 액션·조밀한 표·stale 문구 | VPC 전용 toolbar/내부 가로 scroll/명시 재시도 | 390/768/1280, 긴 IP·넷마스크, 기존 행 보존 |
| 대화상자 | 선행 self-managed는 공통. 일부 Autogen/raw modal/inline 남음 | VPC/오퍼링/gateway만 공통 frame opt-in, route/tag/ACL 연결 | 고정 Header/Footer·Body scroll·키보드·값 보존 |

## 업무 대화상자 추적과 보존 계약

| 경로 | 적용 컴포넌트 | 채택/보존 |
| --- | --- | --- |
| VPC 생성 | CreateVpc + MoldDialog/Actions | #1657 입력·후보·조건·receipt 정책 보존 |
| VPC 편집/삭제 | AutogenView + MoldDialog + VpcActionContext | API args/그룹 매핑 보존, 대상 정보·명시 오류·고정 Footer |
| VPC 단일/일괄 재시작 | RestartVpc | #1655 boolean/job/부분 실패·미확정 정책 재사용 |
| VPC 마이그레이션 | MigrateVpc | #1656 후보/매핑/체크포인트 재사용. 기존 자원 이동은 수행하지 않음 |
| 게이트웨이 생성 | PrivateGatewayCreate | #1654/1657 ACL·연결·고급 오퍼링 정책 보존 |
| 게이트웨이 ACL/삭제 | AutogenView + MoldDialog | 기존 API/선택 의미 유지, 부모/대상 문맥 |
| 정적 경로 추가 | StaticRoutesTab + MoldDialog/Actions | vpcid+nexthop / gatewayid 분기 보존. 거부·실패 때 폼 유지 |
| 정적 경로 태그 | StaticRoutesTab + MoldDialog/Actions | 기존 key/value 행 액션 유지, 닫기 Footer·busy·오류 보존 |
| VPC ACL 목록 생성 | VpcTab + MoldDialog/Actions | 공통 ACL 규칙 시험 제외. 이 VPC 생성 모달의 단일 제출·값 보존만 검증 |
| 오퍼링 생성/복제 | AddVpcOffering/CloneVpcOffering | #1651~53 서비스/다중 제공자/상속/상태/receipt 보존, 기존 필드 순서 유지 |
| 오퍼링 편집/상태/삭제 | AutogenView + MoldDialog | API args와 groupMap 유지, 대상 ID·고정 Footer |
| 오퍼링 접근 | UpdateOfferingAccess + MoldDialog/Actions | 기존 도메인/Zone/공개 여부 유지, 별도 공통 프레임 구현 없음 |

오퍼링 추가는 기존 지원 서비스 행을 보존하며 공통 lg(960px)를 채택한다. 다른 간단 폼은 md(720px),
선행 재시작/마이그레이션은 기존 lg를 유지한다. 전체 viewport 중앙·공통 Body 스크롤·Header/Footer를 재사용한다.

## 실행·보존 범위

- 31/32 UI→실제 POST/job→최종 자원 대조. 합성 성공 응답은 실제 성공으로 세지 않는다.
- 390×844 / 768×900 / 1280×900, light/dark 및 즉시 테마 전환. 폼/목록/상세/탭별 실제 증거 행렬을 남긴다.
- 긴 한국어·UUID·복수 제공자, 정상/빈/오류/비활성/조회·제출 로딩, popup·포커스/Escape/Tab/Ctrl+Enter를 구분한다.
- 전용 자원만 API로 정리하고 기존12종 UUID/선택 설정/기본 오퍼링/정책/계정 사용량과 FTCTL 변경 보존을 대조한다.
- 활성 webapp의 정적 자산만 배포한다. WEB-INF/META-INF/설정/JAR/PID를 보존하고 전후HTTP200과 전체 파일 해시를 확인한다.
- 외부 제공자/실제 BGP peer·IPv6·라우팅 풀은 미구성이다. UI 옵션·fixture를 실제 실행 지원 완료로 바꾸지 않는다.
- #1659의 next-hop 경로 privategw/undefined 링크 수정은 제외한다. 기존 ACL/NAT/PF/LB/VPN/일반 네트워크 회귀를 다시 합산하지 않는다.
- 서버/DB/호스트 소스·JAR·Maven·전체 Cloud 빌드·qemu/RPM 변경과 별도 이슈 생성은 없다.

## 1차 배포 실증 뒤의 보완

1차 제품0d6c74에서 실제 게이트웨이 breadcrumb 복귀/브라우저 앞뒤 이동, 실제 CIDR 431 거부와 값 보존,
수정 뒤 Ctrl+Enter의 단일 POST·정적 경로 생성 및 태그의 단일 POST·실제 생성 결과를 확인했다.
네트워크 캡처는 reload 뒤 Network domain을 다시 켜야 하므로 캡처가 없는 최초431 한 번은 POST 횟수 집계에서 제외한다.

정적 경로 폼을 즉시 unmount하면 닫기 후 포커스가 BODY로 돌아가고, 태그의 최초 조회 중 비활성인 입력은
조회 뒤 포커스를 얻지 못하는 것을 관찰했다. 공통 Modal의 visible/after-close 수명을 유지하고 태그 조회 완료·검증 오류 뒤
첫 유효/오류 필드로 이동한다. 공통 프레임 자체를 수정하지 않는다. 이전 generic 작업의 늦은 오류는 같은 action·조회 문맥일 때만
해당 대화상자에 표시한다. 최종 배포 뒤 이 보완과 UI 스타일을 다시 검증한다.

1차 오퍼링 생성창에서는 선택 팝업에서 Escape가 상위 창까지 닫는 것과, 목록 첫 항목의 ID가 새 생성 대상처럼
표시되는 것을 실증했다. 대상 요약은 편집/복제/상태/접근/삭제에만 표시한다. VPC 전용 VpcDialog는 MoldDialog의
속성·slot·Footer 주입을 그대로 전달하는 얇은 어댑터이며, Select가 Escape를 처리한 뒤 상위 모달로 같은 키가 전달되지
않게 한다. 기본 Escape/취소와 원래 after-close 콜백은 유지하고 현재 route의 유효한 trigger로만 포커스를 반환한다.
공통 전역 쉘/테마/API는 변경하지 않고 VPC 전용 생성·재시작·전환·게이트웨이·Tier/VPN 진입점에 같은 어댑터를 사용한다.

오퍼링 추가의 실측은 Body 710/1234px와 서비스 목록250/563px로 두 영역이 동시에 스크롤됐다. VPC 어댑터 안에서만
서비스 목록의 고정 높이를 해제해 Body가 기본 세로 스크롤을 소유하게 한다. 기존 체크박스/공급자 행·필드 순서와 업무별
조건은 재배치하지 않는다. 사설 게이트웨이/정적 경로 표의 수평 스크롤과 대화상자 popup은 이 조정과 별도다.
