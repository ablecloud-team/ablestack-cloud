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

# #1589 사용자 정의 프로젝트 역할의 조회 허용 복구

31 GFS2·32 Glue RBD에서 수정 구현·배포·실제 UI 검증을 완료했다. 제한 멤버가 특정 프로젝트에 진입해 네트워크와 VM/NIC를 조회하고, 정책으로 제한한 네트워크 생성·수정·NIC 연결·해제는 계속 거부된다.

[원본 캡처 48장](capture-matrix.ko.md) · [현재 시험 자원](resources.ko.md) · [실제 UI 실행 기록](evidence/browser-actions.json) · [소스·집중 검사](evidence/source-build.json)

## 원인과 구현

ProjectRoleBasedApiAccessChecker와 DomainChecker는 일반 멤버의 API/리소스 권한을 판정하면서 ProjectRoleManagerImpl.findProjectRole 및 findAllProjectRolePermissions를 호출했다. 두 메서드는 역할 관리용 관리자 권한을 요구했다. 이 때문에 Regular 멤버는 저장된 allow/deny 규칙을 읽기 전에 거부됐고, listApis·listNetworks·listVirtualMachines가 401로 실패했다.

ProjectRoleService에 현재 사용자의 유효한 멤버십에 한정된 내부 권한 조회를 추가했다. 사용자 단위 멤버십을 계정 멤버십보다 먼저 선택하고, 할당 역할이 같은 프로젝트에 속하는지 확인한다. 비멤버와 없어진/외부 프로젝트 역할은 거부한다. 기존 역할 관리 API의 관리자 검사는 그대로 유지한다.

API 탐색과 리소스 접근 판정이 이 내부 경로를 공유한다. 탐색은 규칙을 한 번 읽어 전체 API 목록에 적용한다. 정확한 이름·대소문자 무시·와일드카드·첫 일치 규칙 순서와 기존 미일치 API 기본 동작을 유지했다. 역할 규칙은 각 요청에서 다시 조회해 변경이 기존 세션과 새 로그인에 반영된다.

QA의 readonly 계정은 네트워크 생성·수정·NIC 연결·해제 **4개 API를 deny**하는 정책이다. 미일치 API에는 기존 기본 허용 계약이 적용되고, 최종 API 집합은 계정 역할과 교집합이다. 따라서 이 시험을 모든 쓰기 API의 일괄 차단으로 해석하지 않는다. 원래 10개 규칙의 내용과 순서를 시험 후 복원했다.

## UI 변경과 VM 표준

UI의 동작 변화는 제한 멤버의 프로젝트 선택 성공, 네트워크·VM/NIC 조회 복구, 제한된 작업 버튼의 표시 권한 정합성이다. 네트워크 목록의 네트워크 추가, 상세의 네트워크 업데이트, VM NIC의 생성 및 연결·기존 네트워크 연결·연결 해제 버튼이 제한 역할에서 표시되지 않는다. 소유자·일반 멤버·관리자·도메인 관리자는 기존 작업을 사용할 수 있다.

기존 VM 공통 목록·상세·탭·breadcrumb·MoldDialog·모바일/테마 컴포넌트를 그대로 사용했다. 새 UI 소스나 CSS를 추가하지 않았으며 UI 번들은 재빌드하지 않았다. 소유자의 실제 UI 생성·수정·NIC 연결을 대조군으로 실행해 대화상자의 입력 배치·고정 하단 버튼과 390px 다크 가독성을 확인했다. 31 라이트 1280/390px, 32 다크 768/390px 목록·상세·탭·프로젝트 왕복과 최종 NIC 화면을 확인했다.

## 실제 검증

| 검증 | 31 | 32 |
| --- | --- | --- |
| 수정 전 제한 멤버 UI/API 프로젝트 조회 | listApis/Networks/VM 401 | 동일 |
| 수정 후 제한 멤버 프로젝트 listApis | 461개·조회 4개 포함·차단 4개 제외 | 동일 |
| 제한 계정 멤버와 사용자 단위 멤버 | UI/API 프로젝트 조회·쓰기 제한 일치 | 동일 |
| 제한 4개 API 직접 실행 | 거부·대상 망/기존 NIC 미변경 | 동일 |
| 역할 관리 조회 API | Regular 멤버 계속 거부 | 동일 |
| root/owner/member/DomainAdmin | 조회·L2 생성/수정·NIC 추가/해제 API 완료 | 동일 |
| 소유자 UI 대조 | 새 L2 생성·설명 수정·NIC 연결 완료 | 동일 |
| 비멤버 peer/outsider | 프로젝트 API 거부·peer UI 상세 미노출 | 동일 |
| 규칙 변경 직후·재로그인 | listNetworks 허용/거부와 탐색 결과 일치 | 동일 |
| 정확한 deny를 먼저 배치 | 조회 거부 | 동일 |
| list* allow를 먼저 배치 | 조회 허용 | 동일 |
| 규칙 복원 | 원래 10개 규칙 내용·순서 일치 | 동일 |
| 프로젝트/기본 보기 즉시 왕복 | 모바일 포함 완료 | 동일 |
| Isolated/L2/Shared 읽기 | 상세·Isolated Public IP·L2 이벤트·VM NIC 확인 | 동일 |

기록한 API 호출은 31번 70건·32번 74건이다. 합계 144건 중 허용 87건, 정책 거부 56건, 자원 한도 535 거부 1건이다. 32의 한도 조정 후 재개 과정에서 조회 3건을 반복했다. [31 API](evidence/31-api-results.json) · [32 API](evidence/32-api-results.json) · [집계](evidence/api-summary.json)

소유자 UI 생성에서도 31의 네트워크 한도 535를 확인했다. API 32/ UI 31의 한도 차단은 권한 결함과 구분해 QA 프로젝트 한도를 조정하고 재실행했다. 관리 서비스 재시작 후의 이전 세션 만료와 브라우저 자동화의 팝업 애니메이션/연결 시간 초과는 현재 DOM을 다시 읽어 복구했다. 비동기 수정 결과가 대기 상태일 때 중복 전송하지 않고 완료 화면과 저장값을 대조했다.

## 빌드·배포 증적

WSL ext4의 변경 Maven 모듈 api·server·plugins/acl/project-role-based만 로컬 빌드했다. ACL 17개, 기존 역할 관리 12개, 새 내부 판정 8개, DomainChecker 2개의 **39개 테스트**가 실패 없이 통과했다. API 인터페이스 모듈은 테스트를 생략하고 install해 하위 변경 모듈이 새 계약으로 컴파일되도록 했다.

변경 Java 7개는 동일 cloud-style.xml 검사 위반 0건이며 ACL 모듈 검사도 통과했다. API 모듈 전역 검사는 기존 테스트 파일의 스타일 오류 6건으로 실패했다. server는 이전 #1588에서 확인한 기존 62건 때문에 전역 검사를 이번 집중 빌드에서 제외했다. 이 결과를 전체 검사/CI 성공으로 보고하지 않는다. 전체 Cloud 빌드와 Actions는 시작하지 않았다.

직전 #1588 배포 JAR 785c9c48…과 기존 WSL 컴파일 클래스 4개의 해시가 일치하는 것을 먼저 확인했다. 신규 JAR 990d6adf…는 클래스 4개만 변경했고 다른 204,653개 ZIP 항목은 동일하다. 원본 JAR은 WSL /home/ablecloud/work/guest-network-1589-validation의 31/32-original.jar에 보관했다. 관리 서비스만 재시작했고 VM·호스트 재시작은 없었다.

활성 webapp의 정적 자산 661개, WEB-INF/META-INF·런타임 설정을 포함한 보호 파일 664개가 보존됐다. /client/ HTTP 200과 Mold active, 실제 클래스 해시 및 기존 FTCTL·모바일 팝업 표식을 확인했다. 검증 후 관리 로그 마지막 15,000행에 AbstractMethod/NoSuchMethod/ClassNotFound 등 연결 오류가 없었다. [31 배포](evidence/31-deployment.json) · [32 배포](evidence/32-deployment.json) · [31 런타임](evidence/31-runtime-final.json) · [32 런타임](evidence/32-runtime-final.json)

## 자원 보존과 후속

기존 프로젝트망 18/19개와 NIC 4개의 UUID·IP·MAC·기본 여부, 원래 멤버십 3개 및 규칙 내용을 보존했다. API 역할별 L2 4개와 UI 확인용 L2 1개를 각각 추가해 최종 프로젝트망은 **23/24개**, VM 각 1대·NIC **6개**다. 사용자 단위 제한 멤버 1개를 추가해 프로젝트 멤버십은 각 4개다. 시험 자원은 삭제 지시를 기다린다.

QA 프로젝트의 네트워크 한도는 양쪽 모두 **20 → 30**으로 조정했다. 확인용 자원을 정리한 뒤 20으로 복구할 대상이다. [31 한도](evidence/31-network-quota.json) · [32 한도](evidence/32-network-quota.json)

운영 네트워크 28/11개·VM 121/58대·오퍼링 30/21개는 누락/변경 0이다. VPC 각 1개와 Kubernetes 규칙 집합 8/1개의 해시도 유지했다. [31 최종 상태·job](evidence/31-final.json) · [32 최종 상태·job](evidence/32-final.json)

Epic 브랜치 dhslove/epic-1554-guest-networks와 Draft PR #1577을 유지한다. #1589를 완료하며 다음은 **#1569 역할 통합 회귀와 남은 조건부 검증**이다. #1586은 별도 Open이다. 기존 전체 63행을 일괄 PASS 처리하지 않는다.
