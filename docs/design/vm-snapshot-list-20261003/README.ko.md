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

# 컴퓨트 VM 스냅샷 목록 UI 목업

31번 클러스터에서 확인한 전체 VM 스냅샷 목록 문제를 개선하는 설계 자료다.
기준 소스는 upstream Europa `b31026f919856a1b61c1f86dca450e16ac0673e1`이며 [개선 계획](implementation-plan.ko.md)에 관찰 근거, 우선순위, API 계획, 수용 기준을 기록했다.
추적 Epic: [ablecloud-team/ablestack-cloud#1216](https://github.com/ablecloud-team/ablestack-cloud/issues/1216). [1차 스토리지 할당량 분석](storage-accounting.ko.md)도 개선 범위에 포함한다.

## 보기

[mockup.html](mockup.html)을 브라우저에서 연다. Vue 3 + 현재 Mold의 **Ant Design Vue 3.2.20**으로 구현한 SFC 앱이며, HTML은 앱을 마운트하는 진입점만 제공한다. Vue/AntD와 스타일은 로컬 번들에 포함되어 외부 CDN이나 네트워크 API 없이 예시 데이터만으로 동작한다.
한 번에 받으려면 [mockup.zip](mockup.zip)을 다운로드하고 압축을 풀어 mockup.html을 연다.
우측 하단의 작은 `목업` 버튼에서 상태 시나리오와 테마를 바꾼다. 이 버튼은 시연 도구이며 제품 화면에 추가할 요소가 아니다.

## 기존 UI 형식 준수

31번 가상머신 목록을 표준으로 삼는다. 브레드크럼 옆 업데이트·상태 선택, 우측 기본 작업·검색, 단일 행 표, 표 헤더 열 설정, 하단 우측 페이지를 같은 위치에 배치한다. 기본 20개/쪽, AntD Table size=middle과 30px 선택 열을 유지한다. 큰 제목/소개문/통계/상시 필터 바/보기 전환 등 별도 목록 배치를 사용하지 않는다.

추가 조건은 기존 SearchView처럼 검색 입력의 필터 팝오버에서 제공한다. 기존 목록처럼 행을 마우스 오른쪽 버튼으로 클릭하여 복원·삭제·상세·관계 보기 컨텍스트 메뉴를 연다. 행별 더보기(ellipsis) 버튼을 추가하지 않는다. 관계 보기는 중앙 대화상자로 제공하며 목록을 다른 페이지 형식으로 바꾸지 않는다.

대화상자는 기존 AntD Modal/Descriptions/Form/Alert 형식이다. 화면 중앙에 정렬하고 헤더 제목·푸터 버튼을 고정하며 콘텐츠만 스크롤한다. 상세·복원·삭제·다중 삭제·생성·관계 보기에 동일 규칙을 적용한다.

컨텍스트 메뉴는 기존 `ResourceActionMenu.vue`와 `utils/actionMenu.js`, `style/components/view/resource-context-menu.less`를 직접 재사용한다. 단일 행 자원 제목, 272px 폭, 기존 그룹 제목과 28px 항목, 아이콘·삭제 강조·비활성 항목의 사유 툴팁을 그대로 따른다. 별도 메뉴 디자인을 만들지 않는다. 닫힘과 경계 배치는 기존 `ResourceContextMenu.vue`의 계약을 따른다.

현재 소스의 [Status.vue](../../../ui/src/components/widgets/Status.vue), [TooltipButton.vue](../../../ui/src/components/widgets/TooltipButton.vue)와 `ui/src/style/theme/*.less`를 직접 사용한다. 목록/검색 레이아웃은 AutogenView/ListView/SearchView와 같은 AntD 컴포넌트 계약으로 구성한다. 제품 구현 시 해당 공통 뷰의 기존 형식을 유지하고 기능만 확장한다.

- 라이트/다크 테마를 전환한다.
- VM/스냅샷 이름 및 설명으로 검색하고 VM·상태·유형·현재 기준점 필터를 조합한다.
- 열 머리글로 전체 예시 데이터를 정렬한 후 페이지를 이동한다.
- 행 우클릭 컨텍스트 메뉴는 그 행을 대상으로 한다. 다른 행의 체크 여부와 무관하다.
- 여러 항목을 선택하면 기존 작업 버튼 위치에 선택 삭제가 나타난다. 실행 불가 항목이 있으면 사유를 표시한다.
- 복원/삭제 확인창의 대상 정보·영향·조건과 확인 체크를 살펴본다. 최종 동작은 시연 안내만 표시한다.
- W2025-GFS2-Sparse 행의 VM 링크 또는 행 우클릭 컨텍스트 메뉴에서 관계 대화상자를 열고 3개 예시 복원 지점과 현재 기준점을 확인한다.
- 최초 로딩, 빈 목록, 검색 결과 없음, 조회 실패, 부분 관계 조회 상태를 전환한다.
- 표 헤더 우측 필터 아이콘으로 표시 열을 바꾸고 상세 창에서 UUID/내부 이름/설명을 확인한다.

## 소스와 재빌드

화면은 [src/App.vue](src/App.vue), 진입점은 [src/main.js](src/main.js), 예시는 [src/mock-data.js](src/mock-data.js)이다. [build.cjs](build.cjs)는 이 설계 앱만 번들링한다. Cloud/운영 UI 전체 빌드를 수행하지 않는다. 설치된 `ui/node_modules`를 사용하며 별도 설치나 CDN이 필요하지 않다.

```powershell
$env:NODE_OPTIONS = '--openssl-legacy-provider'
# ui/node_modules가 다른 dhslove 작업 트리에 있으면 그 절대 경로를 지정한다.
# $env:MOLD_UI_NODE_MODULES = 'C:\path\to\dhslove\ablestack-cloud\ui\node_modules'
node docs/design/vm-snapshot-list-20261003/build.cjs
```

번들에 사용된 실제 버전은 [assets/build-info.json](assets/build-info.json)에 기록한다. 이번 빌드의 Vue 및 compiler-sfc는 설치된 UI 의존성 3.5.41이며 UI package.json의 Vue 3 허용 범위 안이다. Ant Design Vue는 현재 UI와 동일한 3.2.20이다. 압축 파일에는 완성된 번들과 SFC 소스를 모두 포함한다. 소스 재빌드는 저장소의 공유 컴포넌트/테마와 UI 의존성이 필요하다.

## 예시 데이터와 실제 구현의 차이

목업의 이름·상태·VM 상태·설명은 설계 시나리오를 위해 가공한 예시다. 실제 31번 클러스터의 현재 상태/작업 결과를 뜻하지 않는다.
목업은 전체 예시 배열을 대상으로 검색·정렬·페이징한다. 제품 구현은 서버에서 ACL을 적용한 전체 검색·정렬·페이징을 수행해야 한다.
관계 보기 역시 완전한 예시 데이터를 사용한다. 제품에서는 선택 VM 전체 관계 데이터의 완전성을 확인해야 한다.
저장 공간 사용량·회수 가능 공간·복원 소요 시간은 현재 API 근거가 없어 표시하지 않는다.
목업은 API, SSH, libvirt, qemu, Cloud DB를 호출하지 않는다. 실제 삭제/복원이나 제품 배포가 수행되지 않는다.

## 화면

이미지는 images/에 저장한다. 목록 양 테마, 행 우클릭 컨텍스트 메뉴, 복원 확인창, 선택 삭제 확인창, VM 관계 보기를 포함한다.
[목업 검증 기록](verification.ko.md)에 실제로 확인한 상호작용 및 범위 한계를 남긴다.
