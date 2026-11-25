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

# 2차 스토리지 Epic #1915 구현·검증 계획

[상위 Epic #1915](https://github.com/ablecloud-team/ablestack-cloud/issues/1915)

## 작업 기준과 순서

`ablestack-europa` 기준 커밋은 `fb40815b6dcb41d356332c75bb645eb48fffe6c4`다. 아래 순서대로 구현하고 각 하위 이슈의 상세 기준을 검증한다.

| 순서 | 우선순위 | 하위 이슈 | 선행 이슈 | 13번 클러스터 검증 |
| --- | --- | --- | --- | --- |
| 1 | P1 | [#1916](https://github.com/ablecloud-team/ablestack-cloud/issues/1916) 2차 스토리지 등록·스테이징의 부분 실패 복구와 관리 UI | 독립 검증 | S3 등록 후 스테이징 실패와 단계 재시도, 스테이징 조회·삭제 권한, 편집·삭제 인자를 더미에서 검증한다. |
| 2 | P1 | [#1917](https://github.com/ablecloud-team/ablestack-cloud/issues/1917) 2차 스토리지 전체·개별 마이그레이션 후보와 비동기 결과 정합성 | 독립 검증 | 0건과 후보 자격, 이동 정책, 가상 비동기 작업 및 개별 행의 요청 인자를 검증한다. |
| 3 | P1 | [#1918](https://github.com/ablecloud-team/ablestack-cloud/issues/1918) 2차 스토리지 브라우저 탐색·갱신·API 권한 안정화 | [#1917](https://github.com/ablecloud-team/ablestack-cloud/issues/1917) | 경로 중복, 늦은 응답, 실패와 0건을 구분하고 기존 브라우저 배치를 대조한다. |
| 4 | P2 | [#1920](https://github.com/ablecloud-team/ablestack-cloud/issues/1920) 2차 스토리지 목록·상세의 제공자·필터·조회 상태 정합성 | 독립 검증 | 실제 조회로 제공자·프로토콜·읽기 전용 필터와 상세 복귀·관련 목록의 범위를 대조한다. |
| 5 | P2 | [#1923](https://github.com/ablecloud-team/ablestack-cloud/issues/1923) 2차 스토리지의 브라우저 외 화면을 가상머신 표준 UI로 정렬 | [#1916](https://github.com/ablecloud-team/ablestack-cloud/issues/1916) · [#1917](https://github.com/ablecloud-team/ablestack-cloud/issues/1917) · [#1918](https://github.com/ablecloud-team/ablestack-cloud/issues/1918) · [#1920](https://github.com/ablecloud-team/ablestack-cloud/issues/1920) | 가상머신과 breadcrumb·목록·상세·탭·탭 콘텐츠·대화상자를 라이트·다크 모드에서 비교한다. |

## 공통 UI 적용 범위

- 브라우저 내부의 경로 카드, 업데이트, 폴더·파일 링크, 표, 페이지 이동, 행 액션 배치를 현재 스타일로 유지한다.
- 브라우저 밖의 breadcrumb, 목록·필터, 작업 버튼, 상세, 탭·콘텐츠와 대화상자는 컴퓨트 > 가상머신의 공통 구성과 배치에 맞춘다.
- 한 단계의 상세 탭과 기존 관련 목록 링크를 사용한다. 스테이징 목록은 상단 작업 영역에서 열리는 관리 대화상자에 배치한다.
- 390·768·1280px의 라이트·다크 모드에서 텍스트와 상태 가독성, 필터와 버튼 배치, 대화상자의 키보드 포커스를 대조한다.

## 13번 검증 범위

- 기존 2차 스토리지와 스테이징은 조회만 한다. 변경은 식별 가능한 더미로 제한하고 검증을 마친 뒤 더미와 생성 데이터를 정리한다.
- 지원 공급자·권한·오류·비동기 조합은 가상 응답으로 확인한다. 이미 검증한 공통 기능은 각 변경과 관련된 회귀만 확인한다.
- 브라우저 로딩·중복 경로 재현은 [#1163](https://github.com/ablecloud-team/ablestack-cloud/issues/1163)과 연계한다.

## 2차 스토리지 브라우저 API 범위

- NFS의 `listImageStoreObjects`는 경로와 페이지를 적용한다. 관리 서버가 전달한 `keyword`는 현재 SSVM의 NFS 파일 열거에서 사용되지 않는다. 2차 스토리지 브라우저는 기존 경로 탐색·페이지 이동으로 조회하며, 키워드 검색을 표시하지 않는다.
