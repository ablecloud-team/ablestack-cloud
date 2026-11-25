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

# 클러스터 Epic #1888 구현·검증 계획

Mold 인프라스트럭처 클러스터의 등록·편집, DRS, 목록 API와 가상머신 공통 UI를 개선한다. [Epic #1888](https://github.com/ablecloud-team/ablestack-cloud/issues/1888)

## 작업 기준

- 기준 브랜치는 `ablecloud-team/ablestack-cloud:ablestack-europa`의 `f12fde5f1adb4748ec9727d34ddedbbcaa56dbba`다.
- 작업 브랜치는 `dhslove:dhslove/epic-1888-cluster`다.

## 구현 순서와 의존 관계

1~3번은 각각 착수할 수 있다. 아키텍처 저장과 DRS 계획 오류를 먼저 교정하고, 목록 API·복귀 규칙을 확정한다. 공통 UI는 등록·편집과 검색 계약을 적용한다. DRS 이력 배치에는 생성·실행 상태와 공통 대화상자 작업을 적용한다.

| 순서 | 하위 이슈 | 선행 이슈 | 13번 클러스터 검증 기준 |
| --- | --- | --- | --- |
| 1 | [#1889](https://github.com/ablecloud-team/ablestack-cloud/issues/1889) 아키텍처 저장·등록 옵션 | 없음 | 비활성 빈 더미에서 아키텍처 기본값·변경·재조회, 초기 비활성·스토리지 그룹 등록을 확인한다. 제어 응답으로 부분 저장과 재시도를 검증한다. |
| 2 | [#1890](https://github.com/ablecloud-team/ablestack-cloud/issues/1890) DRS 오류 복구·권한 | 없음 | 더미 응답으로 생성·실행의 성공·실패·지연·권한 제한과 중복 제출을 검증한다. 실패 후 계획 보존과 같은 화면에서의 재시도를 확인한다. |
| 3 | [#1891](https://github.com/ablecloud-team/ablestack-cloud/issues/1891) 관리 상태 필터·목록 복귀 | 없음 | 두 목록 API의 유형·관리 상태·스토리지 그룹 결과를 대조하고, 상세에서 복귀해도 검색·필터·정렬·페이지 상태가 복원되는지 확인한다. |
| 4 | [#1892](https://github.com/ablecloud-team/ablestack-cloud/issues/1892) 가상머신 공통 UI·리소스 표시 | [#1889](https://github.com/ablecloud-team/ablestack-cloud/issues/1889), [#1891](https://github.com/ablecloud-team/ablestack-cloud/issues/1891) | 가상머신 기준과 390·768·1280px 라이트·다크모드에서 화면 구성과 대화상자 초점·권한을 대조하고, 리소스 단위와 상태 표현을 확인한다. |
| 5 | [#1893](https://github.com/ablecloud-team/ablestack-cloud/issues/1893) DRS 계획 이력·이동 상세 | [#1890](https://github.com/ablecloud-team/ablestack-cloud/issues/1890), [#1892](https://github.com/ablecloud-team/ablestack-cloud/issues/1892) | 상단 DRS 도구·계획 표·공통 상세 창을 390·768·1280px와 키보드로 확인한다. 이력 0건, 여러 이동 항목, 페이지와 갱신 상태를 검증한다. |

## 13번 클러스터 검증 범위

13번의 기존 클러스터와 호스트는 조회만 한다. 상태 변경 시험은 호스트와 스토리지 풀이 없는 비활성 더미 클러스터 또는 브라우저 제어 응답에서 진행한다. DRS 실행·권한 제한·지연·교차 응답은 제어 응답으로 확인한다. 더미와 시험 그룹의 정리 상태를 기록한다.
