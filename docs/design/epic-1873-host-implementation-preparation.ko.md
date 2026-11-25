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

# 호스트 Epic #1873 구현·검증 계획

Mold 인프라스트럭처의 호스트 화면에서 장치 조회의 안전성, 등록·편집 결과, 목록의 API 정합성과 공통 UI를 개선한다. [Epic #1873](https://github.com/ablecloud-team/ablestack-cloud/issues/1873)

## 작업 기준

- 기준 브랜치는 `ablecloud-team/ablestack-cloud:ablestack-europa`의 `31ccb55df5a3717c5dbdc1df7e5b58a3b566bc31`다.
- 작업 브랜치는 `dhslove:dhslove/epic-1873-host`다.

## 구현 순서와 의존 관계

1~3번은 독립적으로 착수할 수 있다. 조회 중 변경 요청을 제거하는 1번과 등록·편집 부분 저장을 다루는 2번부터 처리한다. 4번은 2번의 저장 흐름과 3번의 검색·복귀를 적용한다. 5번은 조회 안전성과 화면 표준을 선행 조건으로 한다.

| 순서 | 하위 이슈 | 선행 이슈 | 13번 클러스터 검증 기준 |
| --- | --- | --- | --- |
| 1 | [#1875](https://github.com/ablecloud-team/ablestack-cloud/issues/1875) 장치 조회에서 자동 해제 제거 | 없음 | 기존 KVM 호스트의 장치 진입·갱신·유형 전환에서 변경 API 0건을 확인한다. 합성 할당으로 Expunging·VM 미확인·403/503을 구분하고 명시적 정리 흐름을 검증한다. |
| 2 | [#1876](https://github.com/ablecloud-team/ablestack-cloud/issues/1876) 호스트 등록·편집과 부분 저장 | 없음 | 더미 등록에서 초기 할당·그룹 요청을 대조하고, 제어 응답으로 그룹 작업 지연·실패·성공과 실패 단계 재시도를 검증한다. |
| 3 | [#1877](https://github.com/ablecloud-team/ablestack-cloud/issues/1877) 고급 검색과 목록 복귀 | 없음 | 기본·메트릭 목록의 OOBM 등 API 필터와 결과를 대조하고, 상세에서 복귀해도 검색·정렬·페이지 상태가 복원되는지 확인한다. |
| 4 | [#1878](https://github.com/ablecloud-team/ablestack-cloud/issues/1878) 가상머신 공통 UI 적용 | [#1876](https://github.com/ablecloud-team/ablestack-cloud/issues/1876), [#1877](https://github.com/ablecloud-team/ablestack-cloud/issues/1877) | 390·768·1280px와 라이트·다크에서 VM 기준을 대조하고, 호스트 작업창의 하단 버튼·초점·권한·영향 안내를 검증한다. |
| 5 | [#1879](https://github.com/ablecloud-team/ablestack-cloud/issues/1879) 장치·Redfish·GPU 중첩 탭 정리 | [#1875](https://github.com/ablecloud-team/ablestack-cloud/issues/1875), [#1878](https://github.com/ablecloud-team/ablestack-cloud/issues/1878) | 장치 5종·Redfish 카테고리·GPU 표를 확인하고 새로고침·공유·뒤로 가기, 빈 결과·503·교차 응답을 검증한다. |

## 검증 범위

13번 클러스터의 기존 호스트는 조회만 한다. 변경이 필요한 경우 브라우저 제어 응답을 우선 사용하며 실제 등록은 격리된 더미 호스트로 한정하고 검증 후 삭제한다. Redfish, 권한 제한, 지연·오류·교차 응답은 제어 응답으로 검증한다. 이전에 검증한 기능은 관련 탐색·화면 계약을 확인하는 범위로 제한하고 실환경 결과와 제어 응답 결과를 구분해 기록한다.
