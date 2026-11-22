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

# Zone Epic #1855 구현·검증 계획

Mold **인프라스트럭처 > Zone**의 제공자 상태, 편집 작업 결과, 목록·상세의 API 정합성과 화면 배치를 개선한다. 초안은 하위 이슈와 검증 조건을 추적하는 기준으로 사용한다. [Epic #1855](https://github.com/ablecloud-team/ablestack-cloud/issues/1855)

## 기준 브랜치

- Upstream 기준은 `ablecloud-team/ablestack-cloud:ablestack-europa`의 `cbf25b124d7ef97ed99a50c0a7ac8fb8e3330a79`이며 로컬 기준과 일치한다.
- 작업 브랜치는 `dhslove:dhslove/epic-1855-zone`다.

## 구현 순서와 의존 관계

[#1856](https://github.com/ablecloud-team/ablestack-cloud/issues/1856)과 [#1857](https://github.com/ablecloud-team/ablestack-cloud/issues/1857)은 독립적으로 착수할 수 있다. 고객 화면의 제공자 상태 오표시를 먼저 처리한다. 그룹 저장 계약을 확정한 뒤 같은 편집 화면에 DNS 필드를 연결한다. 제공자 상태와 Zone 주소·목록 복귀 계약을 물리 네트워크 탐색에 적용한 다음 공통 UI를 정리하고 최종 레이아웃의 수치와 상태를 확인한다.

| 순서 | 하위 이슈 | 선행 이슈 | 13번 클러스터 검증 기준 |
| --- | --- | --- | --- |
| 1 | [#1856 제공자 조회와 상태](https://github.com/ablecloud-team/ablestack-cloud/issues/1856) | 없음 | 기존 물리 네트워크에서 전체·개별 조회와 VirtualRouter·VpcVirtualRouter 표시를 대조한다. 제어 응답으로 NSX 미지원·403·503과 재시도를 확인한다. |
| 2 | [#1857 스토리지 액세스 그룹 저장](https://github.com/ablecloud-team/ablestack-cloud/issues/1857) | 없음 | 비활성 더미에서 그룹 저장·변경 없음·제거를 검증한다. 제어 응답으로 작업 지연·부분 실패·기존 작업 재조회·실패 단계 재시도를 확인한다. |
| 3 | [#1858 목록 필터·복귀와 DNS](https://github.com/ablecloud-team/ablestack-cloud/issues/1858) | [#1857](https://github.com/ablecloud-team/ablestack-cloud/issues/1857) | 네트워크 유형·그룹의 API 필터와 목록 복귀를 대조한다. IPv6 DNS·네트워크 도메인 표시와 검색 순서 저장·순서 변경·초기화를 확인한다. |
| 4 | [#1859 물리 네트워크 이중 탭](https://github.com/ablecloud-team/ablestack-cloud/issues/1859) | [#1856](https://github.com/ablecloud-team/ablestack-cloud/issues/1856), [#1858](https://github.com/ablecloud-team/ablestack-cloud/issues/1858) | 트래픽 유형 4개와 제공자 선택, 북마크 호환, 새로고침·뒤로 가기·상위 Zone 복귀를 확인한다. 390·1280px 및 키보드로 조작한다. |
| 5 | [#1860 가상머신 공통 UI](https://github.com/ablecloud-team/ablestack-cloud/issues/1860) | [#1859](https://github.com/ablecloud-team/ablestack-cloud/issues/1859) | 가상머신 화면과 390·768·1280px × 밝은·어두운 모드에서 비교한다. 대화상자 초점, 권한, 삭제 대상·영향·최종 상태를 검증한다. |
| 6 | [#1861 용량·상태 표시](https://github.com/ablecloud-team/ablestack-cloud/issues/1861) | [#1860](https://github.com/ablecloud-team/ablestack-cloud/issues/1860) | 동일 API 값의 단위·사용/할당 라벨·반올림과 현지화 상태를 대조한다. 0·미지원·조회 실패를 구분한다. |

## 13번 클러스터 검증 범위

13번의 기존 Zone은 읽기 전용으로 확인한다. 실제 변경과 삭제는 하위 리소스가 없는 비활성 더미 Zone에서 검증한다. 기존 IPv4·ASN·BGP 작업은 화면 진입과 조건부 노출만 확인하며, Zone 마법사는 화면 흐름만 확인한다. Basic·Edge·비라우팅 조건과 장애·권한 응답은 독립 시험 데이터 또는 제어 응답으로 확인한다. 실측과 제어 응답 결과를 구분해 기록하고, 검증 뒤 UI와 API에서 더미와 시험용 그룹이 정리됐는지 확인한다.
