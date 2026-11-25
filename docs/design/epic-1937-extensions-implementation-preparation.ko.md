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

# Mold 확장 메뉴 Epic #1937 구현·검증 계획

[상위 Epic #1937](https://github.com/ablecloud-team/ablestack-cloud/issues/1937)

## 작업 기준과 순서

`ablestack-europa` 기준은 `7c9426a370eb2d3ab95ea2cbb2a8ce6d11a5772c`다. 작업 브랜치는 `dhslove/epic-1937-extensions`다. P1 생성 옵션과 목록의 총건수·페이지 계약을 먼저 반영한다. 작업 목록 갱신은 페이지 계약에 맞춘다. 대상·유형 계약을 정리한 뒤 목록·탭과 대화상자 표준을 적용한다. 파일 정리 결과 표현까지 반영되면 통합 검증을 시작한다.

| 순서 | 우선순위 | 하위 이슈 | 선행 이슈 | 13번 클러스터 UI 완료 기준 |
| --- | --- | --- | --- | --- |
| 1 | P1 | [#1938](https://github.com/ablecloud-team/ablestack-cloud/issues/1938) 확장 생성의 인스턴스 준비 옵션 저장 정합성 | 없음 | 인스턴스 준비 값의 생성 요청과 재조회 |
| 2 | P1 | [#1939](https://github.com/ablecloud-team/ablestack-cloud/issues/1939) 확장·사용자 정의 작업의 총건수 및 페이지 이동 수정 | 없음 | 21건에서 두 페이지, 총건수·크기와 마지막 행 |
| 3 | P1 | [#1940](https://github.com/ablecloud-team/ablestack-cloud/issues/1940) 사용자 정의 작업 변경 후 목록 갱신과 오류 복구 | [#1939](https://github.com/ablecloud-team/ablestack-cloud/issues/1939) | 추가·편집·활성 상태 변경·삭제 직후 목록과 늦은 응답 |
| 4 | P2 | [#1941](https://github.com/ablecloud-team/ablestack-cloud/issues/1941) 확장 유형별 등록 대상·연관 자원과 리소스 탭 진입 정리 | 없음 | 유형별 유효 후보·명시 선택·연관 자원·등록 진입 |
| 5 | P2 | [#1942](https://github.com/ablecloud-team/ablestack-cloud/issues/1942) 사용자 정의 작업 유형 편집 및 이름 입력 검증 | 없음 | Network → Vpc 편집과 이름 사전 검증 |
| 6 | P2 | [#1943](https://github.com/ablecloud-team/ablestack-cloud/issues/1943) 가상머신 기준 목록·검색·breadcrumb·단일 탭 정리 | [#1940](https://github.com/ablecloud-team/ablestack-cloud/issues/1940) · [#1941](https://github.com/ablecloud-team/ablestack-cloud/issues/1941) | 검색 조건·칩·breadcrumb 복귀·단일 탭·긴 값 |
| 7 | P2 | [#1944](https://github.com/ablecloud-team/ablestack-cloud/issues/1944) 확장·사용자 정의 작업 대화상자를 가상머신 표준으로 정리 | [#1938](https://github.com/ablecloud-team/ablestack-cloud/issues/1938) · [#1941](https://github.com/ablecloud-team/ablestack-cloud/issues/1941) · [#1942](https://github.com/ablecloud-team/ablestack-cloud/issues/1942) | 390·768·1680px에서 본문·하단 버튼·키보드 초점 |
| 8 | P2 | [#1945](https://github.com/ablecloud-team/ablestack-cloud/issues/1945) 확장 삭제의 파일 정리 선택·부분 실패 결과 안내 | [#1944](https://github.com/ablecloud-team/ablestack-cloud/issues/1944) | 정리 옵션 기본값·영향과 메타데이터/파일 부분 실패 |
| 9 | P2 | [#1946](https://github.com/ablecloud-team/ablestack-cloud/issues/1946) 13번 클러스터 확장 메뉴 변경분 통합 UI 검증 | [#1943](https://github.com/ablecloud-team/ablestack-cloud/issues/1943) · [#1945](https://github.com/ablecloud-team/ablestack-cloud/issues/1945) | 변경분 통합과 13번 UI 증거·시험 리소스 정리 |

## UI 적용 기준

- 컴퓨트 > 가상머신의 breadcrumb, 목록 툴바, 상세 요약, 탭 콘텐츠와 `MoldDialog`를 기준으로 사용한다. 확장 타입과 상태, 정리 명령이 적용되는 대상과 영향이 구분돼야 한다.
- 상세·리소스·사용자 정의 작업·이벤트를 한 층에 배치한다. 짧은 데이터를 행에서 펼치고 긴 설정은 작업 상세에서 확인한다. 부모 확장이나 목록 복귀 시 탭·조건·페이지를 복원한다.
- 390·768·1680px, 라이트·다크와 키보드 조작에서 탭과 필터 접근, 정리·취소·확인 버튼, 초점 복귀, 긴 ID·경로 표시를 확인한다.

## 13번에서 사용하는 검증 범위

- 현재 배포 번들과 검증한 브랜치 커밋을 기록한다. UI로 변경한 값은 화면, 요청·응답과 최종 재조회에서 대조한다.
- 13번의 식별 가능한 비활성 시험 확장과 작업을 사용한다. 후보나 권한 조합이 없는 조건과 실패·지연·부분 성공은 브라우저 제어 응답으로 확인하고 증거에 명시한다. 종료 시 생성한 시험 개체와 경로를 확인한다.
- 기본 확장 패키지·경로·인식은 [#1062](https://github.com/ablecloud-team/ablestack-cloud/issues/1062), 작업 상세·이벤트의 기존 이동은 [#1074](https://github.com/ablecloud-team/ablestack-cloud/issues/1074), Network Extension 통합 검증은 [#997](https://github.com/ablecloud-team/ablestack-cloud/issues/997) 결과를 인수한다. [#1937](https://github.com/ablecloud-team/ablestack-cloud/issues/1937)에서 확인한 기본 키워드·유형 조회, 기존 편집, 실제 스크립트 파일 삭제도 기존 증거를 사용한다. 이번 검증은 변경된 동작과 그 변경으로 영향을 받는 연결에 한정한다.
- 실제 외부 하이퍼바이저, 물리 네트워크 제공자 연결·해제, 트래픽과 스크립트 실행은 [#1025](https://github.com/ablecloud-team/ablestack-cloud/issues/1025)에 연계한다. 검증 결과에 신규 변경의 판정, 인수 증거와 미검증 조건을 기록한다.
