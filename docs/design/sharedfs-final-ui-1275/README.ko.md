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

# SharedFS 최종 UI 정리 목업 — #1275

이 목업은 Epic #898의 기능 완료 후 적용할 UI 기준을 보여준다. 모든 데이터는 예시이며 운영 API를 호출하지 않는다.

- Vue3 / Ant Design Vue: 저장소 ui/node_modules의 실제 버전 사용.
- ResourceLayout.vue / Status.vue / MoldDialog.vue와 Mold 테마 CSS 직접 import.
- VM InstanceTab.vue / DetailsTab.vue의 세로 탭·속성 목록·표·툴바 패턴 준수.
- 상세/NFS/SMB/iSCSI/NVMe-oF/네트워크/메트릭/이벤트 8개 탭.
- 기존 서비스별 섹션 순서·표 컬럼과 대화상자의 수직 폼·POSIX 2열 구조 유지.
- 대화상자 제목/버튼 고정과 본문 스크롤은 실제 mold-dialog.less로 처리.

빌드: `node build.cjs`. 미리보기: 폴더를 HTTP 정적 서버로 열고 mockup.html을 방문한다.
`?tab=nfs&theme=dark&dialog=nfs`로 시연 상태를 선택한다.


## 재사용한 실제 Mold 코드

- `ui/src/layouts/ResourceLayout.vue`: VM 상세와 동일한 좌측 요약 / 우측 콘텐츠 그리드.
- `ui/src/components/widgets/Status.vue`: 기존 상태 표시.
- `ui/src/components/view/MoldDialog.vue` 및 `ui/src/style/components/view/mold-dialog.less`: 제목 헤더 / 본문 / 확인·취소 푸터, 본문 전용 스크롤.
- `ui/src/style/vars.less`, `dark-mode.less`, `theme/*.less`: 현재 light/dark token과 컴포넌트 스타일.
- `ui/src/views/compute/InstanceTab.vue`와 `DetailsTab.vue`: 세로 탭, 속성 목록, 툴바·표의 기준 패턴.

## 검증

- 실제 Vue 3.2.37, compiler-sfc 3.2.37, Ant Design Vue 3.2.20.
- 1440×960에서 전체 8개 탭 × light/dark = 16개 렌더링, 세로 탭 및 페이지 가로 overflow 없음.
- 긴 NFS 대화상자: 헤더 y=24 / 푸터 y=883, 본문 scrollTop 0→370, 헤더·푸터 이동 0px.
- 390×844: 헤더 y=12 / 푸터 bottom=832, 본문 scrollTop 0→670, 제목·확인·취소가 화면 안에 유지됨.
- 대표 NFS/SMB/iSCSI/NVMe-oF/ACL/확장/삭제 대화상자 이미지와 총 37개 JPEG.
- 브라우저 오류 0. `browser-verification.json`에 실제 DOM 좌표와 검증 결과 기록.
- 모달 애니메이션이 종료된 후 좌표를 측정했다.

## 범위

최종 UI 구현이 아닌 레이아웃 검토용 목업이다. 예시 데이터와 로컬 시연 동작만 포함하고 운영 API/VM/볼륨을 변경하지 않는다. 선행 기능 완료 후 실제 API 필드·권한·입력 검증·상태와 기존 레이아웃을 다시 대조해야 한다.


## 사용자 검토 반영 — 탭 툴바

- 탭 라벨이 제목이므로 별도 탭 제목을 제거한다. 콘텐츠 섹션 제목은 유지한다.
- 주요 버튼은 좌측 정렬하고 첫 번째 메인 액션은 파란 primary 버튼이다.
- 업데이트 버튼은 ReloadOutlined 아이콘 + `업데이트`로 통일한다.
- NFS/SMB/iSCSI/NVMe-oF에서 넓게 보기는 **좌측 버튼 그룹의 마지막**이다. 화면 우측 끝 정렬이 아니다.
- 버튼 순서: 주요 생성 작업 → 프로토콜 활성화 → 업데이트 → 넓게 보기.
- ResourceLayout의 실제 wide 속성을 사용하여 자원 요약 패널을 숨기고 탭 영역을 확장한다. 기본 보기로 되돌릴 수 있다.
- 16개 탭/테마에서 제목·첫 버튼·업데이트를 검증하고 8개 프로토콜/테마에서 확장·복원을 검증했다.
