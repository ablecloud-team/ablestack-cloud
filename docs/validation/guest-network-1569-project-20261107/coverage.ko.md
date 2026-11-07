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

# 특정 프로젝트 권한 실행 행렬

고정 프로젝트는 qa-gn1569-31-project와 qa-gn1569-32-project다. owner는 프로젝트 Admin, member와 readonly는 Regular, 도메인 관리자는 해당 도메인에 속하나 프로젝트의 직접 멤버가 아니다.

## API 140건

각 표의 결과는 31·32에서 동일했다. 각 행은 역할 7개 × 두 클러스터로 실행했다.

| 작업 | root | domainadmin | owner | member | readonly | peer | outsider |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L2 생성 | 허용 | 허용 | 허용 | 허용 | 401 | 432 | 432 |
| 자동 VLAN Shared 생성 | 허용 | 허용 | 허용 | 허용 | 401 | 432 | 432 |
| NAT 설명 수정 | 허용 | 허용 | 허용 | 허용 | 401 | 432 | 432 |
| L2 설명 수정 | 허용 | 허용 | 허용 | 허용 | 401 | 432 | 432 |
| 지정 VLAN Shared 설명 수정 | 허용 | 531 | 531 | 531 | 401 | 432 | 432 |
| 자동 VLAN Shared 설명 수정 | 허용 | 허용 | 허용 | 허용 | 401 | 432 | 432 |
| NAT NIC 연결 | 허용 | 허용 | 허용 | 허용 | 401 | 432 | 432 |
| L2 NIC 연결 | 허용 | 허용 | 허용 | 허용 | 401 | 432 | 432 |
| 지정 VLAN Shared NIC 연결 | 허용 | 허용 | 허용 | 허용 | 401 | 432 | 432 |
| 자동 VLAN Shared NIC 연결 | 허용 | 허용 | 허용 | 허용 | 401 | 432 | 432 |

[31 기본 API와 job](evidence/31-api-roles.json) · [32 기본 API와 job](evidence/32-api-roles.json) · [31 자동 VLAN Shared](evidence/31-api-shared-auto.json) · [32 자동 VLAN Shared](evidence/32-api-shared-auto.json)

별도 교차 소유권 음성 사례는 owner/member가 개인 계정 소유 L2를 프로젝트 VM에 연결하는 네 건이며 모두 530과 NIC 미생성을 확인했다. [31](evidence/31-cross-owner-nic.json) · [32](evidence/32-cross-owner-nic.json)

## UI와 API 탐색

| 역할 | 실제 UI 동작 | 판정 |
| --- | --- | --- |
| owner, 양쪽 | 프로젝트 전환, L2 생성, NAT/L2/자동 VLAN Shared 수정과 NIC 연결 | 완료, 지정 VLAN Shared 수정은 비활성 |
| member, 양쪽 | 프로젝트 전환, L2 생성·수정·NIC 연결 | 완료 |
| admin 31 | 프로젝트 선택, listApis 응답, 저장 예외와 요청 실패 | 실패 #1587 |
| admin 32 | 프로젝트 전환, L2 생성·수정·NIC 연결, 지정 VLAN Shared 수정 | 완료 |
| domainadmin, 양쪽 | 프로젝트 목록의 대상 선택, listApis 531 | 실패 #1588, 실제 작업 API 허용과 불일치 |
| readonly, 양쪽 | 프로젝트 선택, 명시적 조회 허용에도 401 | 실패 #1589, 쓰기는 거부 |
| peer, 양쪽 | 프로젝트 목록 미노출, 대상 네트워크 직접 URL 접근 거부 | 권한 경계 유지 |
| outsider, 양쪽 | 프로젝트 목록 미노출 | 권한 경계 유지, 직접 상세 UI는 미실행 |

[작업 기록](evidence/browser-actions.json) · [31 API 탐색](evidence/31-api-discovery.json) · [32 API 탐색](evidence/32-api-discovery.json) · [31 최종 제한 역할 규칙](evidence/31-readonly-permissions.json) · [32 최종 제한 역할 규칙](evidence/32-readonly-permissions.json)

140건의 쓰기 허용/거부 결과가 기대한 접근 경계와 일치하더라도 조회 결함으로 제한 역할 전체가 정상이라는 판정을 내리지 않는다. 화면별 성공 범위와 실패 조건을 함께 유지한다. Isolated 생성 역할별 조합, 전체 Shared 공유 범위, 모바일·테마 전환·모든 역할의 모든 유형 UI CRUD는 이번 검증의 완료 범위가 아니다.
