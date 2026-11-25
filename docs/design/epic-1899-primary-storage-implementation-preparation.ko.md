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

# 기본 스토리지 Epic #1899 구현·검증 계획

Mold의 **인프라스트럭처 > 기본 스토리지** 등록·편집, 목록과 브라우저를 개선한다. [Epic #1899](https://github.com/ablecloud-team/ablestack-cloud/issues/1899)

## 작업 기준과 순서

기준 브랜치는 `ablecloud-team/ablestack-cloud:ablestack-europa`의 `c16cee4d2680c5d2b5fbe29a3ad1fa3a96152a76`이고, 작업 브랜치는 `dhslove:dhslove/epic-1899-primary-storage`다.

| 순서 | 우선순위 | 하위 이슈 | 선행 이슈 | 13번 클러스터 검증 |
| --- | --- | --- | --- | --- |
| 1 | P1 | [#1901](https://github.com/ablecloud-team/ablestack-cloud/issues/1901) 등록 범위·액세스 그룹·태그 규칙과 요청 로그 | 없음 | 지원 범위와 등록 인자, 가짜 연결 자격정보의 콘솔 미출력을 확인한다. |
| 2 | P1 | [#1902](https://github.com/ablecloud-team/ablestack-cloud/issues/1902) 편집 권한과 그룹 저장·부분 실패 복구 | 없음 | 제어 응답으로 권한 제한, 기본·그룹 저장, 작업 지연과 실패 단계 재시도를 검증한다. |
| 3 | P2 | [#1903](https://github.com/ablecloud-team/ablestack-cloud/issues/1903) 기본·메트릭 목록 필터와 상세 복귀 | 없음 | 실제 조회에서 필터와 복귀 상태, 관련 볼륨·템플릿·ISO의 스토리지 범위를 대조한다. |
| 4 | P2 | [#1904](https://github.com/ablecloud-team/ablestack-cloud/issues/1904) 브라우저 검색·페이지 복원과 RBD 권한 | [#1903](https://github.com/ablecloud-team/ablestack-cloud/issues/1903) | 기존 풀을 조회하고, 가상 RBD에서 페이지·오류·생성·가져오기·삭제 권한을 검증한다. |
| 5 | P2 | [#1905](https://github.com/ablecloud-team/ablestack-cloud/issues/1905) 가상머신 공통 UI와 범위 변경 | [#1901](https://github.com/ablecloud-team/ablestack-cloud/issues/1901) · [#1902](https://github.com/ablecloud-team/ablestack-cloud/issues/1902) · [#1903](https://github.com/ablecloud-team/ablestack-cloud/issues/1903) · [#1904](https://github.com/ablecloud-team/ablestack-cloud/issues/1904) | 390·768·1280px 라이트·다크에서 배치·가독성과 대화상자의 초점, 범위 변경 대상 0건을 검증한다. |

## 공통 UI 적용 기준

마지막 하위 이슈에서 가상머신의 목록·상세·볼륨 탭·편집 창을 기준으로 breadcrumb, 목록, 상세, 탭, 탭 콘텐츠와 대화상자를 맞춘다. 기존 단일 탭 단계와 도구 영역을 활용한다. 브라우저에서는 경로·검색·갱신을 상단에 정렬하고 아래에 표를 둔다. 행 작업은 한 메뉴에 모은다. 대화상자의 취소·확인은 공통 하단 영역에 배치하고, 닫을 때 초점을 원래 작업으로 복원한다. 기존 배치를 깨는 제목·카드·배지·탭 또는 중복 버튼은 추가하지 않는다.

## 13번 검증 범위

기존 기본 스토리지는 조회만 한다. 변경 시험은 격리 더미 또는 브라우저 제어 응답에서 수행하고, 시험 후 더미와 관련 그룹의 정리를 확인한다. Zone·호스트·클러스터와 인프라 설정 탭의 공통 검증은 선행 이슈를 참조한다. [설정 #1896](https://github.com/ablecloud-team/ablestack-cloud/issues/1896)
