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

# #1588 도메인 관리자 프로젝트 탐색·네트워크 권한 정합성 개선

후속 #1589도 구현·31/32 배포·UI 검증을 완료했다. 현재 자원과 제한 역할의 결과는 [#1589 보고](../guest-network-1589-20261107/README.ko.md)를 따른다. 아래는 #1588 완료 당시 기록이다.

31 GFS2·32 Glue RBD에 백엔드와 UI를 반영하고 실제 도메인 관리자 브라우저에서 검증했다. 프로젝트의 직접 멤버가 아닌 도메인 관리자가 본인·하위 도메인 프로젝트를 선택하고 네트워크를 생성·수정하거나 VM NIC를 연결할 수 있다. 외부 도메인과 일반 비멤버 접근은 계속 차단된다.

[원본 캡처](capture-matrix.ko.md) · [확인용 자원](resources.ko.md) · [UI 실행 기록](evidence/browser-actions.json) · [소스·검사](evidence/source-build.json)

## 변경 내용과 UI 표준

ProjectRoleBasedApiAccessChecker의 API 탐색과 직접 실행이 동일한 관리자 범위 검사를 사용한다. 루트 관리자는 기존 권한을 유지하고 도메인 관리자는 프로젝트 도메인에 대한 AccountService.checkAccess를 통과해야 한다. 도메인이 없으면 거부한다. 일반 사용자의 멤버십·사용자 단위 역할·프로젝트 역할 필터는 유지했다. 지정 VLAN Shared 수정의 루트 관리자 제한도 유지된다.

실제 UI 검증에서 발견한 하위 프로젝트 목록 누락도 수정했다. QueryManagerImpl의 domainPath LIKE 조건에 계층 접두 검색을 적용한다. /1/105/%처럼 끝의 슬래시를 유지해 /1/1050/ 같은 인접 도메인이 섞이지 않는다. 일반 사용자 멤버십 필터와 명시한 외부 도메인의 접근 검사는 그대로 적용한다.

UI에서 달라진 부분은 헤더 프로젝트 선택과 그 뒤의 작업 경로다. 도메인 관리자의 프로젝트 전환 531이 해소되고, 하위 프로젝트가 선택 후보에 표시된다. 모바일 검색 팝업이 작은 헤더 입력 폭에 묶여 이름·검색 안내가 좁게 접히던 부분은 420px와 화면 폭-24px 중 작은 폭을 사용하도록 개선했다. 선택 입력 자체의 헤더 배치와 공통 테마 색상은 유지한다.

390px 화면에서 팝업 실제 폭은 366px, 긴 프로젝트 이름의 글자 영역 높이는 19px로 한 줄에 표시됐다. 검색 안내도 한 줄로 읽을 수 있다. [수정 전](screenshots/32-mobile-project-popup-before.jpg) · [수정 후 라이트](screenshots/31-ui1588-mobile-project-popup-light.jpg) · [수정 후 다크 검색](screenshots/32-ui1588-mobile-project-search-dark.jpg) · [실제 DOM 치수](evidence/popup-layout.json)

컴퓨트 > 가상머신과 같은 기존 공통 목록·상세·탭·breadcrumb·MoldDialog를 사용한다. 네트워크 생성·수정 폼과 VM NIC 연결 폼의 본문, 하단 취소/확인 버튼, 모바일 세로 배치와 라이트·다크 가독성을 실제 화면에서 확인했다. 별도 리본이나 새로운 버튼 배치를 만들지 않았다.

## 실제 검증

| 항목 | 31 | 32 |
| --- | --- | --- |
| 직접 프로젝트 멤버가 아닌 DomainAdmin | 유지 | 유지 |
| 본인·하위 도메인 프로젝트 listApis | 허용·네트워크 쓰기 API 포함 | 동일 |
| 본인·하위 프로젝트 목록 | 수정 전 본인 1개 → 수정 후 2개 | 동일 |
| UI 본인 프로젝트 L2 생성·설명 수정 | 완료·저장값 일치 | 동일 |
| UI 실행 중 VM에 L2 NIC 연결 | 완료·job 1/0·NIC 4개 | 동일 |
| API L2 생성·수정·NIC 추가/해제 | 완료·원래 NIC 복원 | 동일 |
| 외부 프로젝트 탐색·조회·생성·수정·NIC | 거부·외부 설명/VM NIC 유지 | 동일 |
| 일반 비멤버 peer/outsider | 탐색·생성·수정·NIC 거부 | 동일 |
| 지정 VLAN Shared | DomainAdmin UI 수정 비활성·API 거부 | 동일 |
| 프로젝트 즉시 전환 | 본인/하위/기본 보기·모바일 왕복 | 동일 |
| 화면 이동 | 목록·상세·이벤트 탭·breadcrumb·VM NIC | 동일 |
| 반응형·테마 | 1280px 목록·390px 라이트 상세/폼/NIC | 768px 목록·390px 다크 상세/폼/NIC |

API 52회 관찰은 허용 20회·거부 32회다. 이 수에는 미해결 #1589의 제한 역할 탐색 401 재현과 쓰기 거부 관찰 각 2회도 포함하므로 52개 전체 기능 PASS로 해석하지 않는다. DomainAdmin 본인/하위 범위, 외부 도메인과 비멤버 거부, 소유자/일반 멤버 탐색을 별도로 대조했다. 서버 배포 후 소유자·일반 멤버의 프로젝트 목록은 자기 멤버십 프로젝트로 제한됐다. [31 API](evidence/31-api-results.json) · [32 API](evidence/32-api-results.json)

재로그인 후 하위 프로젝트의 UI 선택·목록, 외부 네트워크 직접 URL 거부, 정상 범위 복귀를 확인했다. 예상 권한 오류 알림은 닫은 뒤 다음 화면을 검증했다. 자동화 클릭의 좌표/팝업 애니메이션 문제는 현재 DOM을 다시 읽고 키보드 Enter/Space/방향키로 결과를 확인했다. 시도만 한 상태를 완료로 세지 않았다. 모바일 팝업 가독성 문제는 캡처 확인 후 수정·재배포했다.

## 로컬 빌드·배포

- WSL ext4의 변경 Maven 모듈만 빌드했다. ACL 12개와 server 5개 집중 테스트가 실패 없이 통과했다.
- ACL 모듈 Checkstyle 및 변경 Java 4개 파일의 동일 cloud-style.xml 검사 위반은 0건이다. server 모듈 전역 Checkstyle은 기존 파일 62건으로 차단돼 집중 server package에서는 checkstyle.skip=true를 사용했다. 전역 검사 성공으로 보고하지 않는다.
- 변경 UI 파일 ESLint, 기존 프로젝트 탐색/캐시 집중 테스트 27개와 로컬 UI production 빌드가 통과했다. 전체 Cloud 빌드나 GitHub Actions를 시작하지 않았다.
- 런타임 Mold JAR의 클래스 2개만 교체했다. 최초 JAR과 비교해 나머지 204,655개 ZIP 항목은 동일하다. 원본 JAR과 단계별 JAR을 WSL ext4에 백업했다.
- 백엔드 배포 동안 현재 UI/런타임/서버 파일 664개를 보존했다. 후속 UI 배포는 활성 /usr/share/cloudstack-management/webapp의 정적 파일만 업데이트하고 WEB-INF/META-INF·런타임 설정·Mold PID를 보존했다. HTTP 200, 새 UI 자산 해시와 기존 FTCTL 표식을 확인했다.

[31 런타임](evidence/31-runtime-final.json) · [32 런타임](evidence/32-runtime-final.json) · [31 UI 배포](evidence/31-ui-deployment.json) · [32 UI 배포](evidence/32-ui-deployment.json)

runtime-final은 백엔드 배포 직후 기록으로 이후 UI 배포 이전 index 해시를 포함한다. 최종 index와 661개 정적 자산의 해시는 UI 배포 증적을 따른다. [31 최종 UI 표식·JAR 보존](evidence/31-ui-active-markers.json) · [32 최종 UI 표식·JAR 보존](evidence/32-ui-active-markers.json)

32번은 원자적 JAR 교체에 필요한 여유 공간이 부족해, 현재 UI manifest에 포함되지 않는 과거 JS/CSS 자산 12개(217,907,916 bytes)를 WSL에 백업하고 각 파일 해시를 검증한 뒤 정리했다. 현재 자산·서버 디렉터리는 유지했다. [정리 증적과 백업 SHA256](evidence/32-retired-assets-receipt.json)

## 유지 자원과 후속

기존 프로젝트망 16/17개와 원래 NIC 3개의 UUID·IP·MAC·기본 여부를 보존했다. 본인 프로젝트에 API 시험 L2와 UI 확인용 L2 각 1개가 추가돼 최종 네트워크는 18/19개, VM은 각각 1대·NIC 4개다. 하위·외부 도메인의 범위 검증용 프로젝트/네트워크도 클러스터별로 2세트 유지한다. 시험 API NIC는 추가 후 해제했고 UI에서 연결한 보조 NIC는 남겼다. [최종 31 상태·job](evidence/31-final.json) · [최종 32 상태·job](evidence/32-final.json)

운영 네트워크 28/11개, VM 121/58대, 오퍼링 30/21개의 누락·변경은 0이다. VPC 각 1개와 Kubernetes 규칙 집합 8/1개의 해시도 유지했다. 관리 서비스 재시작만 수행했으며 VM/호스트를 재시작하지 않았다. 시험 자원은 사용자 확인 후 별도 삭제 지시를 기다린다.

기존 Epic 브랜치 dhslove/epic-1554-guest-networks와 Draft PR #1577을 유지한다. #1588만 완료 처리하고 다음 순서는 **#1589 제한 역할 조회 권한 → #1569 역할 회귀 및 남은 조건부 검증**이다. #1586은 별도 Open이다. 기존 #1569 전체 행렬의 미검증/부분 검증 항목을 이번 결과로 일괄 PASS 처리하지 않는다.
