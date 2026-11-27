# Mold 보안 그룹 개선 작업 계획

[상위 Epic #1996](https://github.com/ablecloud-team/ablestack-cloud/issues/1996)

이 계획은 Europa 기준 커밋 `ee6c5da5f1f686c6f0726b628ee873fe44b4cbb6`에서 시작한다. 31번 GFS2와 32번 RBD의 Native KVM Shared Network 및 Mold UI를 대상으로 한다.

## 실검증에서 확인한 범위

- 31번 VLAN 160, 32번 VLAN 161과 두 클러스터의 untagged에서 총 12대의 시험 VM을 사용했다. 같은 호스트의 실제 IPv4 TCP·UDP 시험에서 다른 계정에서 시작한 48개 연결에 수신 서비스가 모두 응답했다.
- 관리 서버에 수신 규칙이 저장되고 대표 호스트의 VM 규칙이 생성됐지만 수신 실측은 허용 범위와 달랐다. 확인한 호스트의 설치 스크립트는 이미 병합된 수정 직전 버전이었다. 두 관찰의 인과는 일치하는 설치본에서 다시 검증한다. [기존 호스트 수정 PR #1515](https://github.com/ablecloud-team/ablestack-cloud/pull/1515)
- 시험망을 삭제한 후 호스트에 시험 브리지 전용 IPv4·IPv6 규칙이 남았다. 해당 규칙을 정리한 후 지정 호스트의 FORWARD 규칙은 시험 전과 일치했다. 조회 기준 기존 VM 221대의 상태·호스트·NIC·보안 그룹 연결은 전후 동일했다.
- VM 보안 그룹 창의 소유자 조회는 브라우저의 연결 상태를 모의하고 실제 API 요청으로 확인했다. 라우터 없는 오퍼링의 필수 검사와 31번 시작 작업의 ConfigDrive 오류는 실제 UI 실행에서 확인했다.

## 하위 이슈와 작업 순서

| 순서 | 우선순위 | 이슈 | 작업 | 선행 이슈 |
| --- | --- | --- | --- | --- |
| 01 | P1 | [#1997](https://github.com/ablecloud-team/ablestack-cloud/issues/1997) | KVM 패키지와 설치본 검증 | — |
| 02 | P1 | [#1998](https://github.com/ablecloud-team/ablestack-cloud/issues/1998) | 수신 정책과 계정 격리 | [#1997](https://github.com/ablecloud-team/ablestack-cloud/issues/1997) |
| 03 | P1 | [#1999](https://github.com/ablecloud-team/ablestack-cloud/issues/1999) | 마지막 NIC 및 브리지 규칙 정리 | [#1997](https://github.com/ablecloud-team/ablestack-cloud/issues/1997) |
| 04 | P1 | [#2000](https://github.com/ablecloud-team/ablestack-cloud/issues/2000) | VM 소유 범위에 맞는 연결 | — |
| 05 | P2 | [#2001](https://github.com/ablecloud-team/ablestack-cloud/issues/2001) | 라우터 없는 Shared 오퍼링 UI | — |
| 06 | P2 | [#2002](https://github.com/ablecloud-team/ablestack-cloud/issues/2002) | 규칙 검색과 입력 검증 | — |
| 07 | P2 | [#2003](https://github.com/ablecloud-team/ablestack-cloud/issues/2003) | 관리자 그룹 생성의 소유 범위 | — |
| 08 | P2 | [#2004](https://github.com/ablecloud-team/ablestack-cloud/issues/2004) | 보안 그룹 메뉴 갱신 | — |
| 09 | P2 | [#2005](https://github.com/ablecloud-team/ablestack-cloud/issues/2005) | 31번 ConfigDrive 시작 실패 | [#2001](https://github.com/ablecloud-team/ablestack-cloud/issues/2001) |

패키지의 실제 설치본을 확인한 후 수신 정책과 브리지 정리를 같은 기준의 Agent에서 검증한다. VM 소유 범위 수정은 독립적으로 진행할 수 있다. 라우터 없는 오퍼링을 UI에서 생성할 수 있게 한 뒤 ConfigDrive 시작·실패·재시도를 UI에서 연결해 검증한다. 세부 구현 방향과 완료 기준은 각 하위 이슈에 기록했다.

## 검증 기준

| 조건 | 기대 결과 |
| --- | --- |
| 수신 규칙이 없는 새 연결 | 같은 그룹 및 다른 계정에서 모두 기본 차단 |
| 그룹 또는 CIDR 기준 제한 수신 | 소스와 프로토콜·포트가 허용된 경우에만 성공 |
| 발신 허용, 수신 차단 | 상대 VM의 수신 정책을 그대로 적용 |
| 규칙 회수·마지막 NIC 제거 | 새 연결은 기본 정책으로 돌아가고 시험 브리지 참조가 회수됨 |
| 비대상 제어 VM | 정책 적용 전·중·후 기존 연결과 신규 서비스 연결 유지 |

실제 UI 작업 ID, API 상태, 호스트 규칙 또는 계수, 게스트 서비스 응답을 같은 시험 결과에 기록한다. 같은 호스트의 태그·untagged부터 확인하고 준비된 전용 환경에서 호스트 간 및 제공 범위의 IPv6·ICMP로 확대한다. 미실행 조합은 환경 조건과 함께 남긴다.

UI는 계정·도메인·프로젝트와 읽기 전용 역할을 검사한다. VM 메뉴의 공통 컴포넌트와 동작을 기준으로 소유자 선택, 작업 버튼, 오류와 재시도, 한국어와 라이트·다크 화면을 확인한다.

작업 브랜치는 `dhslove/epic-1996-security-groups`다. 검증을 마친 하위 작업을 구현 커밋으로 순서대로 추가한다.
