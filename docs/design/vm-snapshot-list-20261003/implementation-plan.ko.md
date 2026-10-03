<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# 컴퓨트 VM 스냅샷 목록 개선 계획

> 구현 단계 후속 확정: 대화상자 요약은 VM 이름·스냅샷 이름·생성일·유형·현재 여부·상위의 6개 항목만 표시하고 ID는 내부 대상 컨텍스트와 기존 상세 페이지에서 사용한다. 요약 다음 입력 영역은 24px, 트리 label/기준점 tag는 8px 간격을 사용한다. 컨텍스트 메뉴의 중복 ‘상세’와 별도 상세 대화상자를 제거한다. 이 지시는 아래 초기 목업의 UUID 표시·상세 메뉴 계획보다 우선한다. 최종 제품/검증은 [운영 기록](../../operations/vm-snapshot-epic-1216.md)을 따른다.

> 목록 후속 확정: 기본 상태는 ‘모두’이며 상태 이름은 기존 상태 번역을 사용한다. 기본 열은 계정·존을 표시하고 도메인을 표시하지 않는다. current=true에는 ‘현재 기준점’, false에는 ‘—’만 표시하며 불필요한 설명 툴팁을 제거한다. 이 지시는 아래 초기 목업의 도메인 열·current 도움말 계획보다 우선한다.

> 검색 조건 태그는 중복 내부 `filter` 값만 제외하고 ‘상태: 사용 가능’ 등 번역된 상태 태그는 유지한다. 상태 태그를 지우면 상태 선택기도 ‘모두’로 전환한다. 다른 검색·권한 조건은 보존한다.

## 기준과 목적

- 검토일: 2026-10-03 (Asia/Seoul).
- 화면: 31번 클러스터, 컴퓨트 > VM 스냅샷, `http://10.10.31.10:8080/client/#/vmsnapshot`.
- 소스: upstream `ablestack-europa`, `b31026f919856a1b61c1f86dca450e16ac0673e1`로 동기화한 dhslove 작업 트리.
- 작업 브랜치: `codex/vm-snapshot-list-design-20261003`.
- 이번 결과는 Epic, 구현 계획, 예시 데이터로 동작하는 UI 목업이다. 실제 Vue/Java 구현 및 테스트 클러스터 배포 완료를 뜻하지 않는다.
- 설계·목업은 dhslove/ablestack-cloud에 보관한다. 해당 fork의 이슈 기능이 비활성화되어, 기존 Cloud UI 이슈 관리 위치인 ablecloud-team/ablestack-cloud에서 Epic과 하위 이슈를 추적한다. 로컬 ablecloud-team 저장소는 변경하지 않는다.

## 확인한 문제와 증거 수준

| ID | 문제 | 근거 | 우선순위 |
|---|---|---|---|
| F1 | 선택된 행이 우클릭한 다른 행보다 작업 대상으로 우선됨 | 실화면에서 Windows11 스냅샷 선택 → Debian13 행 우클릭 → Windows11 메뉴 표시. ListView.vue의 getFirstSelectedItem 경로 | P1 |
| F2 | 전체 목록의 복원 확인창이 대상 및 영향 설명을 생략함 | 실화면의 제목·본문은 VM 스냅샷 복원 문구뿐. compute.js가 label.action.vmsnapshot.revert를 message로 사용 | P1 |
| F3 | VM 표시 이름으로 검색할 수 없음 | 실화면 Windows11-Process 검색 0건. 서버 keyword는 snapshot name/displayName/description/uuid만 비교 | P1 |
| F4 | 현재 여부가 색 점뿐이고 의미 설명이 부족함 | 실화면과 ListView.vue current 렌더러, displayText 미지정 | P2 |
| F5 | DiskAndMemory, Selected N items 등 영문과 긴 자동 이름 중심의 표시 | 실화면 및 공통 목록 렌더링 | P2 |
| F6 | VM별 스냅샷 관계·생성 순서와 현재 기준점을 파악하기 어려움 | W2025-GFS2-Sparse의 3개 항목이 평면 목록으로 표시됨 | P2 |
| F7 | 선택 후 다중 작업 안내가 약함 | 실화면 체크 후 선택 개수만 나타나고 명시적 작업 버튼은 없음 | P2 |
| F8 | 서버 페이징과 현재 페이지 내부 정렬이 분리됨 | 코드 분석: 서버 created DESC, UI genericCompare. 31번 다중 페이지 정렬 E2E는 미수행 | P2 |
| F9 | 전체 목록에서 현재 VM 상태·백업 제한의 사전 안내가 부족함 | 코드 분석: snapshotActionReason에 vm=null 전달. 서버 검증은 유지됨. 상태별 실제 복원 실행은 미수행 | P1 |
| F10 | 내부 VM 스냅샷마다 원본 볼륨 전체 크기를 1차 스토리지 할당량에 중복 합산 | 최신 소스 DefaultVMSnapshotStrategy.updateVolumePath → volumes.vm_snapshot_chain_size → CapacityManagerImpl. 31번 Primary 상세는 사용 9.06%, 할당 75.66%; 전체 차이의 원인별 비중과 생성 전후 증분은 미측정 | P1 |

F1/F2 확인을 위해 메뉴·대화상자를 열고 취소했다. 복원·삭제를 실행하지 않았다.
현재 VM 상세 VmSnapshotsTab.vue에는 이미 유형 번역, 대상 정보가 있는 확인창, 최신 VM/스냅샷 재조회가 있다. 이 구현과 공통 vmSnapshotActions.js의 계약을 재사용하며, 이미 처리된 상세 탭 개선을 중복 개발하지 않는다.

## 범위

필수 범위는 전체 VM 스냅샷 목록의 검색·필터·정렬·페이징, 작업 대상 계약, 확인창, 자격 검증, 상태 갱신, 읽기 전용 VM별 관계 보기, 다크/라이트 테마 및 접근성과 KVM 내부 VM 스냅샷의 1차 스토리지 할당량 중복 계산 개선이다. 백엔드 상세 방향과 수용 기준은 [storage-accounting.ko.md](storage-accounting.ko.md)를 따른다.

아래 항목은 이 Epic의 구현 범위에서 제외한다.

- 백업과 VM 스냅샷의 공존 정책 변경 (#1149), 스냅샷 저장 형식·복원 엔진 변경, 스냅샷 자동 삭제/보존 정책.
- 개별 스냅샷의 정확한 물리 사용량·삭제 시 회수 가능 공간을 표시하는 기능. 할당량 계산 개선은 포함하되 현재 VMSnapshotResponse에 없는 수치를 임의로 표시하지 않는다. 실제 풀 사용량과 추가 예약 정책은 분리하여 백엔드에서 검증한다.
- 단순 나이만으로 안전한 삭제를 추천하거나, parent 관계를 실제 저장 파일의 직접 종속 관계로 단정하는 기능.
- 전 자원 목록의 우클릭 동작을 일괄 변경하는 것. VM 스냅샷에 먼저 대상 계약을 적용하고 공통 변경이 필요하면 관련 목록 회귀를 수행한다.
- qemu/ftctl 소스 변경, 무승인 Full Cloud build, 광범위 클러스터 배포.

## 화면과 작업 계약

### 목록

1. 가상머신 목록을 형식 표준으로 삼는다. Mold의 사이드바·상단 메뉴, AutogenView의 브레드크럼/컨트롤 행, ListView의 표, 하단 우측 페이지 구성을 유지한다. 별도 큰 제목·소개문·통계 카드·상시 필터 바·보기 전환 도구 모음을 추가하지 않는다.
2. 브레드크럼 옆 업데이트/상태 선택, 우측 텍스트 포함 기본 작업 버튼과 검색 입력을 기존 위치에 둔다. 선택 시 같은 작업 위치에 선택 삭제를 표시한다. 기본 작업 버튼은 검색의 필터/검색 아이콘보다 앞에 배치한다. 생성은 지원 VM을 선택하고 기존 VM 상세 생성 흐름에 연결한다.
3. 검색은 VM 표시 이름/내부 이름, 스냅샷 표시 이름/내부 이름/UUID/설명을 포함한다. 서버 검색으로 전체 권한 범위를 조회한다.
4. 검색 입력의 기존 필터 팝오버 형식으로 VM, 유형, 현재 기준점, 도메인/계정/태그 조건을 제공한다. 상태 선택은 브레드크럼 옆 기존 소형 선택기로 제공한다. VM 선택기는 ACL 범위의 listVirtualMachines를 검색·페이징하며 중복 표시 이름은 UUID/계정으로 구별한다.
5. 기존 목록 열을 보존하면서 상태·생성일을 보완한다. 이름·VM·상태·유형·현재·부모 스냅샷·생성일·계정·존을 단일 행으로 표시한다. 도메인은 목록 열에 표시하지 않는다. 설명/UUID/내부 이름은 상세에서 제공하며 기본 열에 카드식 두 줄 정보를 넣지 않는다. 표 헤더 우측 열 설정과 size=middle, 30px 선택 열, 기본 20개/쪽을 유지한다. 행별 더보기(ellipsis) 버튼을 추가하지 않고 기존 행 우클릭 컨텍스트 메뉴를 유지한다.
6. Disk는 디스크, DiskAndMemory는 디스크 + 메모리로 표시한다. 알 수 없는 유형은 번역 키 대신 원문과 제한 사유를 표시한다.
7. 사용자가 지정한 이름을 우선 표시하되 내부 이름/UUID는 상세 및 복사 기능으로 보존한다. 생성일은 Asia/Seoul (UTC+09:00)로 명확히 표시한다. 자동 이름의 시각을 생성일로 해석하지 않는다.
8. current=true에는 ‘현재 기준점’, false에는 ‘—’를 표시한다. 관계 트리에는 current=true인 항목에만 기준점 태그를 표시한다. 작업에 필요하지 않은 설명 툴팁은 제공하지 않는다.
9. 상태는 사용 가능/생성 중/복원 중/삭제 중/오류 등 색과 텍스트를 같이 사용한다. 상태 개수나 VM 개수는 서버에서 전체 집계하지 않는 한 현재 페이지의 값으로 전체 통계를 표시하지 않는다.
10. 서버 정렬 + 서버 페이징을 사용한다. 기본 created DESC, id DESC로 동률을 고정한다. 지원하지 않는 열에는 정렬 화살표를 표시하지 않는다.
11. 최초 로딩, 빈 목록, 검색 결과 없음, 조회 실패와 마지막 결과 유지, 작업 진행 중 상태를 구별한다. 작업 완료 후 현재 필터·정렬·페이지를 유지하고 마지막 행 삭제 시 유효한 페이지로 보정한다.

### 단일 및 다중 작업

- 행 우클릭 컨텍스트 메뉴는 언제나 우클릭한 행의 ID를 대상으로 한다. 별도 행 작업 버튼을 추가하지 않는다. 다중 체크 상태에서도 행 메뉴에 선택 항목의 작업을 섞지 않는다. 기존 체크 상태로 대상이 바뀌지 않는다.
- 컨텍스트 메뉴 본문은 기존 ResourceActionMenu와 actionMenu 그룹 규칙, resource-context-menu.less를 재사용한다. 자원 제목 1줄, 기존 그룹/아이콘/삭제 강조/비활성 툴팁/272px 폭을 유지한다. 신규 메뉴 항목은 기존 그룹에 넣으며 별도 메뉴 형식을 만들지 않는다.
- 컨텍스트 메뉴는 실제 크기로 화면 경계 안에 배치한다. 외부 클릭·Escape·목록 스크롤·화면 크기 변경 시 닫고, 다른 행을 다시 우클릭하면 해당 행의 메뉴로 바꾼다. 기존 ResourceContextMenu의 동작을 따른다.
- 다중 작업은 선택 도구 모음의 선택 N개 삭제에서 시작한다. 복원은 일괄 작업으로 제공하지 않는다.
- 목록이나 메뉴에서 선택한 대상은 별도 불변 작업 컨텍스트에 캡처한다. 자동 갱신이나 선택 변경으로 대화상자의 대상 ID를 교체하지 않는다.
- 모든 대화상자는 기존 Ant Design Vue Modal/Descriptions/Form/Alert 형식을 따른다. 화면 가운데 정렬(centered), 헤더에는 제목, 푸터에는 버튼을 고정하고 콘텐츠 body만 세로 스크롤한다. 뷰포트 높이를 제한하여 작은 화면에서도 제목·버튼을 항상 볼 수 있게 한다.
- 복원 확인창은 VM 표시 이름/내부 이름, 스냅샷 표시 이름/UUID/시각/유형, 현재 VM 상태, 영향 설명, 차단 사유를 표시한다.
- 메모리 포함 복원은 디스크와 저장된 메모리 상태의 복원임을 설명하고, 디스크 전용 복원은 현재 정책에 따라 Running VM의 정지가 필요함을 알린다. 제품의 현재 start-first/stop-first 검증 계약을 따른다.
- 최종 버튼은 확인 대신 이 스냅샷으로 복원/스냅샷 삭제를 사용한다. 복원·삭제의 영향 확인 체크는 명시적 추가 안전장치이며 서버 자격 검증을 대체하지 않는다.
- 복원/삭제 제출 직전에 대상 스냅샷, VM, API 권한 및 진행 중 작업을 다시 조회·검증한다. 서버는 ACL과 백업·스냅샷/스토리지 정책을 계속 강제한다.
- 다중 삭제는 모든 항목의 상태/권한/VM 제약을 검증하여 항목별 사유를 표시한다. 실행 불가 항목이 있으면 해당 선택 묶음을 시작하지 않고 선택 수정으로 유도한다.
- 같은 VM의 스냅샷 삭제는 서버 체인 처리에 충돌하지 않게 직렬화하고, 다른 VM도 제한된 동시성으로 처리한다. 중간 실패 시 영향받는 VM의 후속 삭제를 중단하고 성공/실패/미실행을 분리한다.
- async job은 기존 pollJob 경로로 추적한다. 성공/실패/추적 불명/화면 이동을 구별하고 알 수 없는 결과를 성공으로 표시하지 않는다. 중복 제출을 막고 작업별 진행 상태를 표시한다.
- 생성 흐름은 기존 지원 하이퍼바이저/스토리지 및 VM 상태 조건을 유지한다. UI에서 호스트 libvirt/qemu를 직접 호출하지 않는다.

### VM별 관계 보기

- 행 우클릭 컨텍스트 메뉴의 VM별 관계 보기 또는 VM 링크에서 기존 형식의 중앙 대화상자를 연다. 목록의 배치를 별도 관계 페이지로 바꾸지 않는다. 전체 목록의 현재 페이지 항목을 완전한 관계 트리로 표시하지 않는다.
- 관계 보기는 같은 권한 범위를 유지하고 선택 VM의 모든 필요한 ID/parent/current/type/state/created를 별도 읽기 경로로 조회한다. 목록의 검색어·상태·유형·current 필터는 관계의 일부를 숨기지 않도록 적용하지 않으며, 이 차이를 화면에 명시한다. 목록으로 돌아오면 기존 조건을 복원한다.
- 규모가 큰 경우 제한을 명시하고 추가 조회를 제공한다. 조회 중·부분 로드·누락된 부모·순환·삭제된 항목은 각각 표현하며 임의의 연결선을 만들지 않는다.
- 관계 보기에서는 display name보다 ID로 연결한다. current를 생성 순서로 계산하지 않고 서버 필드를 사용한다.
- 트리/타임라인 노드마다 이름·시각·유형·상태·현재 기준점·작업 메뉴를 제공한다. 연결선은 Cloud 메타데이터상 부모 관계라는 의미로 한정한다.

## 구현 구조 및 API 계획

### UI

- Vue 3 + 현재 ant-design-vue 3.2.20 계열을 사용한다. 제품 구현은 compute.js의 vmsnapshot 경로와 AutogenView/ListView/SearchView의 기존 레이아웃을 유지하고 작업 컨텍스트/확인창을 확장한다. 목록 전체를 다른 레이아웃의 전용 페이지로 대체하지 않는다.
- 설계 목업은 Vue SFC와 동일 버전 AntD Table/InputSearch/Popover/Pagination/Modal로 구현한다. 기존 Status/TooltipButton 및 theme 토큰·스타일을 직접 가져오고, 일반 HTML/DOM 문자열로 목록·대화상자를 렌더링하지 않는다. 배포용 UI 소스는 설계 단계에서 수정하지 않는다.
- VmSnapshotsTab.vue의 확인창·fresh 조회·pollJob 로직과 vmSnapshotActions.js를 재사용 가능한 공통 동작으로 정리한다.
- ListView.vue의 공통 변경은 최소화한다. VM 스냅샷 전용 작업 컨텍스트를 우선 사용하고, 전역 계약을 바꿀 때 VM/볼륨/백업/다중 선택 회귀를 포함한다.
- 기존 테마 토큰, 번역 키, project/listall/domain/role 범위, 링크 및 사용자 열 설정을 보존한다.
- 권한 없는 작업 숨김과 상태 때문에 사용할 수 없는 작업의 비활성 사유를 구분한다.

### Cloud API / server

- listVMSnapshot에 keyword VM 이름 검색과 검증된 sortkey/sortorder, 유형/current 필터를 추가하는 방향으로 구현한다. 상태/virtualmachineid/도메인/계정/태그의 기존 계약을 유지한다.
- sortkey는 화이트리스트로 제한하고 사용자 입력을 SQL 컬럼명에 직접 연결하지 않는다. 조인 이후 ID와 count의 중복을 방지하고 모든 ACL 조건을 필터와 집계에 동일 적용한다.
- VM 정보는 권한 범위 내 일괄 조회로 보완하여 N+1 조회를 피한다. 최신 자격 판단은 제출 직전 fresh VM/스냅샷 조회와 서버의 최종 검사로 수행한다.
- 관계 조회는 선택 VM의 최소 메타데이터 전용 응답 또는 명시적인 전체 페이지 수집을 검토하여 별도 작은 계약으로 확정한다. 불완전한 데이터는 완전한 트리로 표시하지 않는다.
- 수정 예상 위치: API ListVMSnapshotCmd/VMSnapshotResponse, server VMSnapshotManagerImpl/응답 생성 경로, 필요 시 engine/schema DAO. 변경 확정 후 해당 Maven 모듈만 WSL ext4 clone에서 빌드한다.

## 작업 패키지와 의존성

| 코드 | 우선순위 | 작업 | 선행 | 완료 기준 |
|---|---|---|---|---|
| S1 | P1 | 행/선택 작업 대상 계약과 복원·삭제 확인창 | 없음 | A 선택 후 B 행 작업은 B, bulk는 선택 ID 집합; 대상·영향·차단 사유 표시; 새로고침 중 대상 불변 |
| S2 | P1 | 현재 VM 상태·백업 제약 사전 검증과 비동기 작업 안전성 | S1 | Running/Stopped/전이 상태/백업/권한/삭제 경쟁 재검증; 중복 제출 차단; 같은 VM 다중 삭제 직렬화; 실패/불명 결과 표시 |
| S3 | P1 | VM 이름 검색·필터·전체 정렬·서버 페이징 | 없음 | 2페이지 이상에서 전역 정렬, 동률 안정성; VM 표시·내부 이름 검색; ACL별 count와 행 일치; 기존 API 호환 |
| S4 | P2 | 목록 열·한글 표시·기존 우클릭 작업 메뉴·조회 상태 UX | S1, S3 | 유형/current/상태 텍스트, 목적·설명/시각, 선택 도구 모음, 소유자/Zone 선택 열, 새로고침/실패/빈 결과 |
| S5 | P2 | 선택 VM의 완전성 확인 가능한 스냅샷 관계 보기 | S3, S4 | 페이지 분절 없음; ID 기반 관계; missing parent/부분 로드/분기/current 구분; 접근성 있는 노드 작업 |
| S7 | P1 | KVM 내부 VM 스냅샷의 1차 스토리지 할당량 중복 계산 개선 | 없음 | 논리 할당/물리 사용/추가 예약 분리, 기존 데이터 재계산, 생성·삭제·복원·용량 재계산·배치 판단 일관성, 실제 여유 공간 보호 및 provider 회귀 |
| S6 | P1 | 31번 클러스터 통합·테마·접근성·용량 회귀 검증 | S1-S5, S7 | 아래 수용 기준 모두 통과, 실제 API/async job/화면 상태 일치, VM 상세 탭·관련 목록·1차 스토리지 계산 회귀 |

실행 단계: 1단계 S1+S3+S7 → 2단계 S2+S4 → 3단계 S5 → 4단계 S6. S1/S3/S7은 서로 독립적이며 각각 검토 가능한 변경으로 진행한다. S7은 계산 계약과 기존 데이터 전환 계획을 먼저 확정한다.

추적 Epic: [#1216](https://github.com/ablecloud-team/ablestack-cloud/issues/1216).
등록된 하위 이슈: S1 [#1217](https://github.com/ablecloud-team/ablestack-cloud/issues/1217), S3 [#1218](https://github.com/ablecloud-team/ablestack-cloud/issues/1218), S7 [#1219](https://github.com/ablecloud-team/ablestack-cloud/issues/1219), S2 [#1220](https://github.com/ablecloud-team/ablestack-cloud/issues/1220), S4 [#1221](https://github.com/ablecloud-team/ablestack-cloud/issues/1221), S5 [#1222](https://github.com/ablecloud-team/ablestack-cloud/issues/1222), S6 [#1223](https://github.com/ablecloud-team/ablestack-cloud/issues/1223).

## 수용 기준과 31번 검증 계획

### 안전성

- [ ] A 체크 후 B 행 우클릭은 B의 이름·UUID를 표시하고 B만 API 대상으로 전송한다.
- [ ] 여러 체크 상태에서 단일 복원은 행 대상으로만 수행하며 다중 복원 메뉴가 없다.
- [ ] 다중 삭제 대상은 선택 ID 집합과 일치하고 실행 전 모든 항목을 검증한다.
- [ ] 대화상자 열린 뒤 자동 갱신/행 순서 변경/대상 삭제에도 대상이 조용히 교체되지 않는다.
- [ ] 백업 제한, Running/Stopped/전이 상태, 권한 회수, host 상태, 스냅샷 상태 변화에서 UI와 서버가 함께 차단한다.
- [ ] 같은 VM 다중 삭제는 순차 처리하고, 실패·추적 불명 이후 해당 VM의 남은 작업을 자동 실행하지 않는다.
- [ ] 복원·삭제 제출은 사용자 승인된 시험 VM과 복구 지점을 준비한 후 실행한다. 기존 31번 사용자 VM을 임의로 복원/삭제하지 않는다.

### 조회 정합성

- [ ] 최소 25개 스냅샷/중복 VM 이름/같은 생성 시각 데이터를 사용해 서버 검색·정렬·페이징·count를 확인한다.
- [ ] User/DomainAdmin/Admin 및 project/listall 조합에서 다른 계정·도메인 자원을 노출하지 않는다.
- [ ] VM 이름/내부 이름/스냅샷 UUID/설명, 상태·유형·current·태그 조건 조합을 검증한다.
- [ ] 오류 후 기존 결과/선택 유지, 재시도, 빈 목록/검색 결과 없음, 마지막 페이지 삭제 보정, URL 복귀를 검증한다.
- [ ] 타임라인은 필요한 전체 메타데이터를 조회하고 부모 누락/분기/순환/부분 로딩을 명시한다.

### 화면·회귀

- [ ] 31번 가상머신 목록과 브레드크럼/컨트롤·작업/검색·단일 행 표·열 설정·페이지 위치, 기본 20개/쪽, size=middle를 비교한다. 추가 기능이 기존 목록 형식을 바꾸지 않는다.
- [ ] 행별 더보기 버튼 없이 우클릭으로 작업 메뉴를 연다. 선택 상태와 무관한 행 대상, 외부 클릭/Escape/스크롤/resize 닫힘, 화면 가장자리 배치를 확인한다.
- [ ] 라이트/다크, 1920×1080·1366×768·모바일에서 열·메뉴·모달·스크롤이 잘리지 않는다.
- [ ] 모달 중앙 배치, 내용만 스크롤, 헤더/푸터 고정, Escape, 포커스 순환/복귀, 키보드 작업 메뉴를 확인한다.
- [ ] 색만으로 상태를 전달하지 않고 한국어 문구·스크린리더 이름을 제공한다. 일반 텍스트 대비 4.5:1, 컨트롤/포커스 3:1 이상을 목표로 계산한다.
- [ ] 기존 VM 상세 스냅샷 탭의 생성/복원/삭제/볼륨 스냅샷 추출, 볼륨 및 백업 목록, 프로젝트 전환을 회귀 검증한다.
- [ ] VM 스냅샷 도움말을 VM 스냅샷 문서로 연결한다. 기존 볼륨 스냅샷 도움말 연결을 바로잡는다.

### 1차 스토리지 용량

- [ ] S7의 provider별 계산 계약을 적용하고, 내부 COW 스냅샷 생성 횟수만으로 원본 볼륨 전체 크기가 반복 가산되지 않는다.
- [ ] 실제 풀 사용량은 COW 변경 데이터·메모리 저장·메타데이터를 반영하며 스냅샷을 0바이트로 가정하지 않는다.
- [ ] 스토리지 목록·상세·API·capacity 재계산·경보·VM 배치 판단이 같은 계산을 사용한다.
- [ ] 기존 vm_snapshot_chain_size 전환은 dry-run 차이와 출처를 기록하고 재실행/재시작에도 중복 반영되지 않는다.
- [ ] 생성/삭제/복원 실패, 동시 작업, 볼륨 resize/migration, 다중 풀, 실제 공간 부족 및 managed storage 회귀를 검증한다.

UI lint/필요한 단위·통합 테스트와 UI production build를 수행한다. Cloud 변경은 WSL ext4의 변경 Maven 모듈만 빌드한다. Full Cloud build는 별도 사용자 요청 시 GitHub Actions로만 수행한다.
31번 UI 배포는 /usr/share/cloudstack-management/webapp의 static assets만 갱신하고 WEB-INF/META-INF/config.json을 보존한다. 배포 전후 WEB-INF 존재, /client/ HTTP 200, 활성 asset hash/기능 표시, 실제 API·job 결과를 검증한다.

## 관련 선행 작업

- #1100: VM 상세 스냅샷 탭 작업/상태 갱신 (재사용 기준).
- #1120: VM 상세 스냅샷 탭 페이징 다크모드 개선 (회귀 기준).
- #1150: 백업·스냅샷 상호 금지와 사유 안내 (현재 정책 준수).
- #1132: 스냅샷 존재 시 볼륨 구성 변경 제한 (회귀 기준).
- #1149: 백업·스냅샷 공존 장기 개선 (정책 변경은 이 Epic에서 제외).

이슈 번호는 모두 ablecloud-team/ablestack-cloud 기준이다.
