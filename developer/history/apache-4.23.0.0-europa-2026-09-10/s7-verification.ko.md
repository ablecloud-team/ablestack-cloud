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

# S7 UI·테마 로그인·한국어·릴리즈 문서 통합

부모 Epic #987, 기능 #998, 코드 PR [#1039](https://github.com/ablecloud-team/ablestack-cloud/pull/1039).
시작 SHA f1b520f8f4b8c7496c2d9a8ac3d1cf2e008b672d, 기존 DB 출발점 014895d8f3dc2f062f379b51ee62d36a0adae88a를 유지한다.
Apache 범위는 3166e64891fc75d4d32b66d874cff3f613b09b52..463f8d0294702a920e8020b62ae3b67f52ae1473 (4.23.0.0)다.

## 원본 판정

직접 8개는 Applied4 / Adapted4다. [s7-review.tsv](s7-review.tsv)에 최초 적용 SHA와 최종 코드 검토 SHA를 함께 기록한다.
공유 merge fb5e24fa0868 / b3b9caddc191은 마지막 side source와 독립 충돌 해결을 모두 검토해 Already Satisfied로 확정했다.
[s7-merge-review.tsv](s7-merge-review.tsv)의 SHA256은 S1 고정 remerge 원문과 일치한다.
Rule wildcard/case 정규화, MinIO·OpenTelemetry 공존, API tracing, host 응답과 CLVM/VMware volume 테스트의 합성을 유지한다.

고정 299개 최종 판정은 Applied101 / Adapted106 / Already Satisfied62 / Excluded28 / Pending2다.
Pending은 S2의 realhostip 제거 a7f9756d6267과 최종 버전463f8d029470이며 S8 #999 인수 대상으로 남긴다.
S7 테마의 ACS 로그인 도메인과 SystemVM 인증서 DNS 설정은 별도 계약이다. 실물 미실행을 이유로 이 두 코드 원본을 완료 처리하지 않는다.

## Europa 적응과 회귀 방지

- 프로젝트 삭제는 이름을 입력해야 제출할 수 있다. 키보드 제출에도 같은 검사를 적용하고 닫기/새 작업에서 입력을 초기화한다.
  Europa의 invokedAsGroupAction을 사용해 이전 선택 행이 남아 있는 개별 삭제가 검사를 우회하지 않게 한다.
  실제 일괄 실행은 기존 일괄 확인 흐름을 사용한다. CSV 다운로드 모달·상세 작업 메뉴·자동 새로고침을 보존한다.
- 준비 완료 template 또는 bootable ISO에만 배포 버튼을 표시한다. 미준비 이미지의 zone 조회를 시작하지 않는다.
- 계정 ID 링크, 관리자 name/domain 검색 fallback, Quota 경로를 통합한다. upstream 변경에서 사라진 프로젝트 계정명은 일반 목록에서도 유지한다.
  일반 사용자의 ID 없는 계정은 기존 텍스트 표시를 유지한다.
- SAML 로그인 후 managementserverid cookie가 없거나 비어 있으면 기존 MS ID 상태를 덮어쓰지 않는다.
- GUI theme loginbasedomain API/VO/view를 연결한다. 기존 Europa JSON validator를 보존한다.
  config.json 기본 도메인을 테마 미지정 시 유지하고, 테마 변경 시 정적 설정을 다시 읽는다. 명시적 빈 값은 기본값을 지운다.
  OAuth 공급자 조회·로그인에 기본 도메인을 적용하며 비밀번호/OAuth의 입력 필드를 구분한다.
- 포르투갈어 원본의 변경 값1232개를 적용하고 기존 Europa/S6 키를 삭제하지 않는다. 번역 과정에서 바뀐 ABLESTACK 제품명13개를 복원했다.
  삭제 확인 한국어1개에 더해 DNS·KMS·OAuth 안내75개를 추가했다. 기존 브랜드 리소스·설정·제품 확장 메뉴를 보존한다.
- API key/KMS/Quota/DNS 화면의 API32개를 실제 listApis와 대조했다. keypairid/rules, kmskeyid/volumeids/hsmprofileid,
  Quota start/end/activationrule/account, DNS provider/zone/record 파라미터가 최종 백엔드 계약에 존재한다.
  Quota 비활성 시 API가 숨겨지는 기본 동작을 유지하고, 별도 fixture에서 활성화 후 대조했다.

## 동일 버전 DB

S7은 europa-4.23-s7-v1 단계에서 gui_themes.login_base_domain(TEXT, NULL 기본값)을 추가하고 view를 갱신한다.
기존 Complete S4/S5A/S5B/S5C/S6 단계는 변경하지 않는다. 앞선 단계가 view를 먼저 갱신할 때는 pre-s7 view를 사용한다.

| 실제 MySQL8 경로 | table/view | version 행 | journal 행 | 반복 실행 |
| --- | ---: | ---: | ---: | --- |
| 신규 설치 | 473 | 52 | 15 | 전체 DDL/데이터/시간 동일 |
| 014895d8f3 DB | 473 | 51 | 6 | 전체 DDL/데이터/시간 동일 |
| S6 완료 DB | 473 | 52 | 15 | 전체 DDL/데이터/시간 동일 |

S6→S7은 theme table/view와 migration journal 3개만 바뀐다. 기존 journal 행과 시간은 보존하고 S7 하나만 추가한다.
실제 DDL 커밋 직후 중단을 주입해 Pending→재시도→Complete→반복 적용과 기존 테마·기본 도메인 보존을 확인했다.
[DB 결과/해시](s7-db-results.tsv), [재현 fixture](s7-fixtures/README.ko.md)를 참고한다.
이 DB는 전체 구조와 합성 데이터를 갖춘 fixture이며 실제 운영 데이터 dump를 검증한 것은 아니다.

## 소프트웨어 검증 근거

모든 소스/Git/설치/검증은 Rocky9.8 amd64 Docker에서 JDK17·Maven3.9.10·Node14/npm6·Python3.10·MySQL8로 실행한다.
기존 개발 DB/볼륨은 초기화하지 않는다. 로컬 developer/systemvm/simulator/noredist 전체 install은 통과했다.
UI 전체36 suite / 382 tests, 전체 lint와 최종 변경 경로 lint가 통과했다.
실제 관리 서버 theme API8개 계약(생성·commonname 조회·수정·생략값 보존·빈 값·commonname 필수·일반 사용자 변경 거부·삭제)이 통과했다.

Chromium 연속 화면 이동에서 Axios 0.31의 취소 오류가 네트워크 장애로 처리돼 로그아웃되는 회귀를 발견했다.
request interceptor의 오류 처리에서 명시적 요청 취소를 구분하고 호출자에게 그대로 반환한다.
실제 Axios 취소 객체의 기존 실패를 재현했으며, 취소 시 세션 유지와 실제 네트워크 오류 시 기존 로그아웃 동작을 단위 테스트2개로 검증한다.

추가 단위 회귀는 AutogenView, GuiTheme, ImageDeployInstanceButton, ListViewAccounts, Permission에 있다.
최종 Java 전체 회귀와 Chromium의 실제 관리자/일반 사용자 로그인·역할 메뉴·삭제 확인·한국어/테마 결과는 PR #1039의 최종 검증 기록에 연결한다.
최종 head의 Build/UI Build/License Check/Rocky9.8 RPM/Rocky9.7 RPM Actions 및 산출물 메타데이터도 같은 기록을 기준으로 한다.
로컬 개발 UI 또는 소스맵 제외 빌드를 공식 production UI Build 성공으로 대체하지 않는다.
기존 전체 Lint 실패가 있는 경우 baseline 대비 실패 종류와 이번 변경 경로 진단을 구분해 PR에 기록한다.

## 릴리즈 및 실물 인수

[릴리즈 노트](europa-release-notes.ko.md)에 지원 기능·설정·동일 버전 업그레이드·제외28개·미반영2개·복구 절차 확정 범위를 기록했다.
S7은 릴리즈 태그나 배포를 수행하지 않으며, 현재 Snapshot과 기존 제품 buildVersion을 유지한다.
[s7-dependencies.tsv](s7-dependencies.tsv)와 [#1025](https://github.com/ablecloud-team/ablestack-cloud/issues/1025)에 최종 실물 시험을 인수한다.
실물 호스트/HSM/IdP/DNS/백업 provider 및 운영 규모 복구는 NOT_RUN이며 코드 병합 조건으로 삼지 않는다.
코드 PR은 자동 검증 후 정상 병합하고 최종 RC/실물 시험 판정은 S8 #999에서 수행한다.

## 동시 upstream 병합의 호환성 확인

S7 PR #1039의 실제 병합 SHA는 88d3be1090af51fcb5daec77fc8de3a83926dcf9다.
병합 시 별도 Veeam PR #1038이 먼저 반영되어 첫 부모는 03a26033ef096c75c5143bf48bcf05702973aba6다.
실제 tree는 두 부모의 자동 merge-tree와 일치한다. 공유 경로는 ApiConstants.java 하나이며 상수 추가 위치가 겹치지 않는다.
그 외 S7 변경 파일은 최종 PR HEAD fe4a198eea6161790e2ba3ce48097de53ac1524e와 동일하고, 합쳐진 UI36 suite/382 tests도 통과했다.

별도 PR에서 추가한 ablestack-veeam 모듈의 부모 POM이 4.22.0.0-SNAPSHOT에 남아 reactor 구성이 실패했다.
기존 4.23 reactor에 맞도록 부모 버전을 수정한다.
Veeam noredist 프로파일이 compiler configuration 전체를 비워 부모의 Java 11 source/target 설정을 지우던 문제도 수정한다.
VMware provider의 소스/테스트 제외 목록만 해제하고, 부모의 source/target 등 컴파일 설정은 상속한다. Veeam 기능 구현과 실물 검증 범위를 추가하지 않는다.
후속 호환 PR의 최종 전체 컴파일/CI·동기화 결과는 #998 완료 기록에서 확인한다.
이는 Apache 고정299개 원본 판정을 바꾸지 않으며, 실물 검증은 #1025에 인수한다.

후속 CI에서 별도 Veeam 변경의 라이선스 헤더 누락16개도 발견했다.
스크립트·설정·문서15개에 저장소의 Apache 헤더를 추가하고, 헤더를 제외한 원문 바이트가 동일한지 확인했다.
주석을 넣을 수 없는 ablestack.key.default는 기존 *.key 데이터 제외와 동일한 취지로 RAT에 정확한 경로만 추가했다.
키의 내용과 형식은 변경하지 않는다. 셸10개의 bash -n 및 tracked-source RAT 검사를 수행한다.

전체 CI에서 mount helper 변경 후 구형 Script API를 mock하던 복원 실패 테스트1개를 발견했다.
새 마운트 실행 함수에 exit1을 주입하고, 30초 timeout·기존 실패 응답·임시 디렉터리 정리를 확인하도록 테스트를 수정했다.
실패 조건을 완화하거나 실제 복원 코드를 변경하지 않는다. 해당 복원 테스트14개가 통과했다.
KVM 전체972 tests와 이전 CI에서 건너뛴 의존 모듈223 tests도 실패/오류0으로 확인했다.
