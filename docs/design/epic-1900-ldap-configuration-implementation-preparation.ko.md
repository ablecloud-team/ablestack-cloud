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

# Epic #1900 Mold LDAP 구성 개선 구현 준비

2026-11-23 KST에 upstream과 fork의 `ablestack-europa`가 같은 커밋을 가리키는 것을 확인하고 `dhslove/epic-1900-ldap-configuration`를 분기했다. 31번 GFS2와 32번 RBD의 기존 검증 결과와 재현 근거는 Epic에서 확인한다. [기준 커밋](https://github.com/ablecloud-team/ablestack-cloud/commit/c16cee4d2680c5d2b5fbe29a3ad1fa3a96152a76) · [Epic #1900](https://github.com/ablecloud-team/ablestack-cloud/issues/1900)

## 구현 순서

| 단계 | 하위 이슈 | 구현 경계 | 선행 이슈 |
| --- | --- | --- | --- |
| 1 | [#1906](https://github.com/ablecloud-team/ablestack-cloud/issues/1906) | 목록 API에 키워드·페이지를 적용하고 UUID 우선 계약을 수정한다. 공개 목록과 LDAP 인증용 DAO 호출의 범위를 구분한다. | 독립 |
| 1 | [#1908](https://github.com/ablecloud-team/ablestack-cloud/issues/1908) | 전역·도메인 등록 범위를 명시한다. 호스트·포트 입력, 바인드·중복 오류 식별자와 실패 후 폼 상태를 구현한다. | 독립 |
| 2 | [#1907](https://github.com/ablecloud-team/ablestack-cloud/issues/1907) | 전체·전역·도메인·UUID 조회와 도메인 이름/경로를 목록에 연결한다. URL·필터 칩·페이지·결과 상태를 동기화한다. | #1906 |
| 3 | [#1909](https://github.com/ablecloud-team/ablestack-cloud/issues/1909) | 삭제 확인에 호스트·포트·도메인 범위를 표시한다. UUID 단독 요청, 대상 전환, 취소·재시도, 목록 복귀를 처리한다. | #1907 |
| 4 | [#1910](https://github.com/ablecloud-team/ablestack-cloud/issues/1910) | 목록·폼 설명·검증·오류를 `en`과 `ko_KR` 카탈로그에 연결한다. 기존 글로벌 LDAP 설명은 공통 카탈로그를 사용한다. | #1907, #1908, #1909 |
| 5 | [#1911](https://github.com/ablecloud-team/ablestack-cloud/issues/1911) | 가상머신 UI 기준으로 breadcrumb, 목록, 상세, 탭과 본문, 대화상자를 통합하고 다크모드에서 인수한다. | #1907, #1908, #1909, #1910 |

#1906과 #1908을 병행할 수 있다. #1906의 조회 계약이 확정되면 #1907에서 요청과 화면 상태를 연결하고, #1909에서 그 상태로 안전하게 복귀한다. #1910은 확정된 화면과 오류 식별자를 번역한다. #1911은 배치·가독성·키보드 조작과 앞선 기능의 통합 인수를 담당한다.

## 구현 기준

- `LdapListConfigurationCmd`, `LdapManagerImpl`, `LdapConfigurationDaoImpl`을 함께 확인한다. 페이지 요청은 필터가 적용된 전체 건수와 안정적인 순서를 유지하고, UUID 조회는 다른 조회 조건을 적용하지 않는다. 기존 인증용 설정 조회 호출과 응답 스키마는 호환성을 검증한다. [목록 명령](https://github.com/ablecloud-team/ablestack-cloud/blob/c16cee4d2680c5d2b5fbe29a3ad1fa3a96152a76/plugins/user-authenticators/ldap/src/main/java/org/apache/cloudstack/api/command/LdapListConfigurationCmd.java#L38) · [DAO](https://github.com/ablecloud-team/ablestack-cloud/blob/c16cee4d2680c5d2b5fbe29a3ad1fa3a96152a76/plugins/user-authenticators/ldap/src/main/java/org/apache/cloudstack/ldap/dao/LdapConfigurationDaoImpl.java#L78)
- UI는 `ldapsetting`과 공통 목록·상세·검색 구성에서 시작한다. 전역과 ROOT 도메인을 구별하고, 등록 및 삭제는 현재 대상과 범위를 명시한다. 삭제 결과는 재조회하고 포트·범위 등 현재 필터를 보존한다. [LDAP 메뉴 정의](https://github.com/ablecloud-team/ablestack-cloud/blob/c16cee4d2680c5d2b5fbe29a3ad1fa3a96152a76/ui/src/config/section/config.js#L37)
- 가상머신과 같은 도구 영역, 표, 상세 속성 행, 탭 콘텐츠, 모달 푸터와 테마 토큰을 사용한다. 부가 배지·별도 도구 모음·빈 탭·중복 요약을 배치하지 않는다. 대상 설명은 기존 도움말·속성 행·공통 경고 영역에 배치한다. [VM 상세·탭 표준 #1201](https://github.com/ablecloud-team/ablestack-cloud/issues/1201) · [Mold 대화상자 표준 #1213](https://github.com/ablecloud-team/ablestack-cloud/issues/1213)

## 31·32번 인수 기준

| 검증 영역 | 확인할 결과 |
| --- | --- |
| 조회 API와 목록 | 일치하지 않는 키워드는 0건, 페이지 크기 1의 서로 다른 페이지는 서로 다른 UUID로 표시된다. 동일 UUID와 다른 포트를 함께 보내도 UUID 응답은 한 건이다. 전체·전역·도메인·UUID 조회의 API·URL·칩·행이 일치한다. |
| 등록·삭제 | 시험 범위에 같은 호스트의 다른 포트를 등록하고, 중복·바인드 실패·취소·중복 제출을 확인한다. 삭제 확인창에서 대상을 식별하고 한 행만 삭제된 뒤 기존 필터로 복귀하는지 확인한다. |
| 현지화와 가상머신 표준 | 한·영 전환으로 필드·도움말·오류 문구가 바뀌고 입력 상태가 유지된다. 같은 빌드의 VM과 LDAP 화면을 390·768·1536px의 라이트/다크에서 비교한다. 텍스트·필터 칩·상태·오류·포커스 명암비와 좁은 화면의 자름·스크롤을 확인한다. |
| 시험 정리 | 시험 범위와 LDAP 구성의 삭제를 재조회하고 임시 디렉터리 종료를 확인한다. 기존 검증이 다룬 도메인 연결·계정 가져오기·글로벌 설정은 해당 이슈의 근거를 사용한다. |

Cloud Java 변경은 WSL ext4 작업 트리에서 변경된 Maven 모듈만 빌드한다. UI 변경은 수정 경로의 테스트·린트와 UI 모듈 빌드로 확인한다. 실 AD·LDAPS 및 LDAP 사용자 로그인은 통합 환경에서 별도로 인수한다.
