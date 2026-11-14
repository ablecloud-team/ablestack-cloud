<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE for copyright ownership.
The ASF licenses this file under the Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
-->

# #1657 VPC 생성 조건·후보와 API 노출 정책

## 근거와 범위

기준 소스는 Epic HEAD `330ced29fc019185b09d25cf316f54175067adba`다.
31 GFS2/32 RBD의 새로운 inventory와 현재 `listApis` 계약을 수집했다.
양쪽 Zone은 Advanced/Enabled, NSX false, Routed true이며 BGP peer와 AS pool은 각각 0개다.
Netscaler 제공자는 기본 물리망 목록에 없고 Netris의 서비스 제공자 상태만 Enabled다.
외부 제어기/세션/트래픽 준비를 이 상태만으로 판정하지 않는다.

각 클러스터에서 기본 IPv4 및 No-DNS VPC 생성 2건 성공, 비활성 오퍼링,
No-DNS+DNS, IPv4+IPv6 DNS, NATTED+cidrsize, 미구성 BGP 참조,
L2 게이트웨이 오퍼링 6건은 HTTP431로 생성 전에 거부됐다. 총 16건이다.
`start=false` 전용 정의만 만들고 BGP peer/AS/물리망/외부 장비 설정은 변경하지 않았다.
브라우저의 오퍼링 배열 미응답 주입에서 기존 창의 TypeError와 안내/재시도 누락도 재현했다.
32번에서는 DNS 입력 후 실제 No-DNS 오퍼링으로 전환한 UI 요청에 DNS가 남아
서버가 실제 HTTP431로 거부한 것도 확인했다.

## 인자별 채택 정책

| 인자/기능 | 결정 | 근거·검증 |
|---|---|---|
| `createVPC.bgppeerids` | 명시 바인딩은 관리자 API 전용 유지. 생성 UI는 미전송 | Admin 하위 명령만 인자를 선언한다. 생성 후 Dynamic VPC의 기존 BGP 탭은 별도 변경 업무다. 실제 peer/AS가 없어 새 선택 UI를 검증할 근거가 없다 |
| `createPrivateGateway.networkofferingid` | 연결 방식별 시스템 기본값 유지. 고급 값은 API 전용 | 서버는 VLAN 유무로 시스템 기본 오퍼링을 고른다. 일반 오퍼링 목록에 두 시스템 기본값이 보이지 않으며 Guest/Isolated 유형만으로 Gateway 호환성을 보증할 수 없다. 기존 #1654 요청에 인자를 새로 붙이지 않는다 |
| VPC 오퍼링 | Zone·대상 소유 도메인·Enabled·NSX/ROUTED 조건과 제공자 진단 | 첫 원소 접근을 제거하고 선택/필터 사유를 분리한다. 선택 직전과 제출 직전 동일 범위로 확인한다 |
| DNS/IPv6 DNS/AS/MTU/라우터 IP | 기존 조건부 옵션 유지·실제 조건에만 전송 | No-DNS 전환 시 DNS 제거, IPv4에서 IPv6 DNS 제거, NATTED에서 cidrsize/AS 제거. NSX/Netris가 관리하는 Source NAT IP를 직접 보내지 않는다 |
| 외부 제공자/IPv6/Dynamic | 기존 조건부 경로를 보존하되 추가 환경 확인 표시 | 제공자 설정 조회와 외부 장비/세션 동작을 구분한다. 미구성 BGP/IPv6/NSX/Netris/Netscaler 동작 성공을 발표하지 않는다 |

## 구현 계약

- CreateVpc를 VM 공통 MoldDialog/MoldDialogActions 안에 배치한다. 새 디자인 시스템이나
  공통 Autogen/소유자 선택 로직을 만들지 않는다. 안전한 기존 OwnershipSelection을 재사용한다.
- 필수 API 권한 없음, Zone 없음, 오퍼링 배열 미응답/빈 목록, 조건 필터 후0건,
  제공자 부적합, 조회 실패를 구분하고 같은 문맥에서 재시도를 제공한다.
- 빈 후보/조회 중/실패/부적합/불완전 소유자에서는 제출하지 않는다.
  조회 권한이 없는 일반 사용자에게 관리자 물리망 API를 강제하지 않고
  구성 진단 미확인·서버 최종 검증으로 명확히 표시한다.
- Zone/소유자 변경 시 후보와 조건부 값을 즉시 초기화한다. 오퍼링/모드 변경 시
  더 이상 허용되지 않는 입력을 제거하고 payload도 명시 whitelist로 만든다.
- 요청 epoch와 로그인/역할/프로젝트/route 문맥을 검증해 늦은 응답을 무효화한다.
  제출 전 최신 Zone/오퍼링/제공자 상태를 다시 확인해 변경되면 재검토를 요구한다.
- 기존 createVPC의 접수 후 공통 job 알림 패턴은 재사용한다. 전송 거부 시 입력을 유지한다.
  응답 유실 시 중복 생성하지 않도록 customjobid와 자격 증명 없는 최소 요청을
  현재 사용자·역할·프로젝트별 sessionStorage에 기록하고 같은 job만 재조회한다.
  생성 비동기 전반의 공통 프레임이나 서버/호스트 구현으로 범위를 확대하지 않는다.
- 실제 Source NAT 미확보 요청은 Mold의 명시적인 HTTP530/createvpcresponse 거부이며
  해당 customjobid와 VPC가 생성되지 않았다. 이 접수 전 거부는 입력을 보존하고
  수정 재시도를 허용한다. 일반 proxy503/응답 유실과 구분해 중복 제출을 막는다.
  접수 표시 중 폼을 재생성하면 소유자 초기 조회가 조건부 입력을 지우므로,
  폼은 마운트를 유지하고 숨김만 전환한다. 실제 소유자 수명에 대응한 회귀를 추가한다.

## 검증 계획

실제 API16건과 기존 UI 오류를 기준으로 후보/권한/조건부 wire/문맥 경쟁을 집중 단위 검증한다.
31/32에 UI 정적 자산만 배포하고, 기본/No-DNS/사용자·프로젝트 생성 결과와
오퍼링 배열 미응답/빈 목록/필터0건/조회 실패/늦은 응답·실제 HTTP 거부를 UI로 검증한다.
VM 기준 light/dark·360/768/1280 및 popup/본문 하단/고정 액션을 시각 확인한다.
전용 VPC/오퍼링/사용자/프로젝트는 정리하고 기존 inventory/사용량/FTCTL을 보존한다.
일반 네트워크·Tier·ACL 회귀를 반복하거나 새 BGP/외부 인프라를 구성하지 않는다.
