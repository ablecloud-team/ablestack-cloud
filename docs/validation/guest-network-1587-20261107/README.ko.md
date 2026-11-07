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

# #1587 프로젝트 전환의 API 캐시 용량과 상태 정합성 개선

31 GFS2·32 Glue RBD에 수정 UI를 배포하고 실제 브라우저에서 검증했다. 31의 관리자 프로젝트 전환 QuotaExceededError가 재발하지 않았다. 관리자·프로젝트 소유자·일반 멤버 모두 양쪽에서 특정 프로젝트 진입과 기본 보기 복귀를 완료했다.

[원본 캡처](capture-matrix.ko.md) · [현재 확인용 자원](resources.ko.md) · [UI 실행 기록](evidence/browser-actions.json) · [소스·빌드·집중 검사](evidence/source-build.json)

## 변경한 UI 동작과 표준

헤더 프로젝트 선택의 전환 동작을 개선했다. 로그인·프로젝트 전환은 공통 API 메타데이터 형식을 사용하며, 큰 응답 스키마는 메모리에 유지하고 브라우저 캐시에서 제외한다. API 문서와 이벤트 내보내기에서 참조하는 응답 스키마를 메모리에서 제거하지 않는다. params·isasync·since·description도 유지한다.

저장 용량 초과 시 이전 범위의 API 캐시를 제거하고 서버에서 새로 얻은 권한만 메모리에 적용한다. 새로고침은 캐시를 권한의 근거로 사용하지 않고 서버의 현재 권한을 다시 확인한다. 이 때문에 유효한 캐시가 있어도 새로고침 때 listApis 요청이 한 번 추가된다. 프로젝트 전환은 이전 탐색 응답을 무효화하고 라우트 생성 완료를 기다린다. 기본 보기 복귀는 이전 프로젝트 ID의 자동 주입을 명시적으로 차단한다.

프로젝트 저장 실패는 이전 API·메뉴를 복원한다. 프로젝트 적용 후 화면 이동이 실패한 경우에는 실제 적용된 프로젝트를 선택란에 표시해 이전 이름과 새 권한이 섞이지 않게 한다. 인증 세대가 바뀐 후에는 이전 권한을 복원하지 않는다.

새 목록·상세·탭·대화상자 스타일을 만들지 않았다. 기존 컴퓨트 > 가상머신의 공통 헤더, 목록 검색, 상세·탭과 breadcrumb, MoldDialog 본문·고정 하단 버튼, 모바일 배치를 그대로 사용했다. 변경 UI는 프로젝트 선택의 로딩·성공·실패 처리이며 CSS 변경은 없다. 실제 네트워크 생성·수정과 VM NIC 연결을 통해 동일한 표준 배치를 확인했다.

## 실제 UI 검증

| 검증 | 31 | 32 |
| --- | --- | --- |
| 관리자 프로젝트 → 기본 보기 → 프로젝트 | 완료 | 완료 |
| 소유자 프로젝트 진입·기본 보기 복귀 | 완료 | 완료 |
| 일반 멤버 프로젝트 진입·기본 보기 복귀 | 완료 | 완료 |
| 새 관리자 로그인 후 프로젝트 전환 | 완료 | 완료 |
| 프로젝트를 유지한 새로고침 | 프로젝트망 16개·VM 1대 | 프로젝트망 17개·VM 1대 |
| 프로젝트 L2 생성·설명 수정 | UI 완료 및 저장값 일치 | 동일 |
| 프로젝트 VM에 새 L2 NIC 연결 | UI 작업 완료·NIC 3개·기본 NIC 유지 | 동일 |
| 네트워크 상세·탭·breadcrumb·VM 화면 이동 | 완료 | 완료 |
| 테마 즉시 전환 | 라이트 → 다크 → 라이트 | 다크 → 라이트 → 다크 |
| 반응형 | 1280px 목록, 390px 상세·수정·프로젝트 왕복 | 768px·390px VM NIC, 390px 연결 대화상자 |
| 최종 확인 화면 | 관리자·프로젝트 VM NIC·라이트 | 관리자·프로젝트 VM NIC·다크 |

VM 목록의 상세 링크는 키보드 Enter로도 검증했다. 다크 전환 뒤 관련 네트워크로 이동해 테마 유지와 텍스트·입력·버튼의 가독성을 확인했다. 모바일 수정 대화상자는 라이트·다크 양쪽에서 본문과 하단 취소·확인 버튼을 확인했다. 임시 뷰포트 재정의는 종료 전에 해제했다.

인증 직후의 임시 DOM 분리, 팝업 애니메이션 및 자동화 클릭 무효는 현재 화면을 다시 읽고 재실행했다. 중간 상태를 성공으로 세지 않고 최종 화면과 API 저장 상태를 대조했다. 31에는 동일 설명값으로 완료된 추가 업데이트 작업 1건도 기록돼 있다. 별도 수정 성공 수에 중복 포함하지 않았고, 최종 값과 기존 자원의 보존을 확인했다.

## API·비동기 작업·배포 근거

| 항목 | 31 | 32 |
| --- | --- | --- |
| 프로젝트 listApis 개수 | 1110 | 1070 |
| 종전 프로젝트 메타데이터 UTF-16 직렬화 | 9,211,162 bytes | 8,564,188 bytes |
| 새 캐시 형식 UTF-16 직렬화 | 2,671,052 bytes | 2,558,482 bytes |
| 새 프로젝트망 | qa-gn1569-31-prj-1587-l2 | qa-gn1569-32-prj-1587-l2 |
| 최종 설명 | UI1587 saved 31 | UI1587 saved 32 |
| NIC 연결 job | 415dbab5-9c24-4290-bdb8-3ef3a5bac25b | a06c4735-ffdf-4f54-9298-e1e1deef0557 |
| 수정·NIC job 상태와 결과 코드 | 1 / 0 | 1 / 0 |
| 배포 정적 파일 | 661개, 해시 불일치 0 | 동일 |

직렬화 크기는 실제 API 응답으로 형식을 재구성해 계산한 값이며 브라우저 저장소 래퍼와 실제 저장 한도 측정값은 아니다. [31 메타데이터·job](evidence/31-api-metadata-jobs.json) · [32 메타데이터·job](evidence/32-api-metadata-jobs.json)

소스 기준은 ff201db10bd6cda39e0cda01107bac43eeb92390이다. WSL ext4에서 UI 로컬 빌드를 수행했다. 활성 webapp 정적 파일만 업데이트했고 WEB-INF·META-INF·관리 JAR·런타임 설정과 Mold PID는 배포 전후 동일하다. HTTP는 양쪽 모두 200이다. 신규 전환 표식과 기존 FTCTL blockingLoadingState·fetchSyncProgress·extractJobId를 배포된 자산에서 확인했다. [31 배포](evidence/31-deployment.json) · [32 배포](evidence/32-deployment.json)

두 집중 스위트의 27개 테스트와 변경 파일 린트가 통과했다. 1110개 API 형식, quota 실패와 이전 캐시 제거, 서버 거부, 로그아웃 후 늦은 응답, 라우트 완료 대기, 캐시 재검증, 프로젝트 저장 실패 복원, 화면 이동 실패 정합성과 기본 보기의 이전 ID 주입 차단을 검사했다. 집중 테스트·UI 로컬 빌드·실환경 UI 결과이며 전체 Cloud 빌드나 전체 CI 성공을 뜻하지 않는다.

## 보존과 후속 이슈

기존 프로젝트망 31의 15개·32의 16개와 기존 NIC UUID·MAC·IP·기본 여부를 보존했다. 각 프로젝트에 새 네트워크 한 개와 보조 NIC 한 개를 추가해 최종 네트워크는 16/17개, VM은 각 1대·NIC 각 3개다. 기존 개인 계정 시험 자원도 유지했다. 운영 네트워크 28/11개, VM 121/58대, 오퍼링 30/21개의 비교 결과에서 누락·변경 0이다. VPC 각 1개와 Kubernetes 규칙 집합 8/1개 해시도 일치했다.

[기존 결함 재현 기록](../guest-network-1569-project-20261107/README.ko.md)의 #1588 도메인 관리자 API 탐색 531과 #1589 제한 역할 조회 401은 배포 후 API에서도 계속 확인된다. #1587의 캐시 실패를 우회하면서 이 제한을 넓히지 않았다. 다음 순서는 #1588 → #1589 → #1569 역할 회귀다. #1586과 L2/PVLAN·Static NAT·VPC ACL·VPN 등 나머지 검증 범위도 남아 있다. #1569·Epic #1554·Draft PR #1577은 열어 두며 #1587만 완료 처리한다.
