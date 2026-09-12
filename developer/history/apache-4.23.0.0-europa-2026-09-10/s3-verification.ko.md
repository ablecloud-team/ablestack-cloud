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

# S3 인증·권한·API 통합 검증

이슈 #991 / 부모 Epic #987 / 구현 PR #1009. 구현 병합 SHA는 `21d1eef95d39d55756aa83796f2a3181797c979d`이며, 검증 후보 및 제품 소스와 tree가 같다.

## 기준과 판정 범위

- 통합 시작: Europa `11fc3c536c2e1de229ee1bc2812b5774ce475bec`.
- 사용자 지정 기존 DB 출발점: `014895d8f3dc2f062f379b51ee62d36a0adae88a`.
- Apache 범위: `3166e64891fc75d4d32b66d874cff3f613b09b52..463f8d0294702a920e8020b62ae3b67f52ae1473`, 태그 `4.23.0.0`.
- 검증 대상 제품 소스: `e086c2433302499068ccfea6ae41b3e642554297`.
- [원본별 S3 판정](s3-review.tsv): 직접 배정 66개와 S2 연계 일반 커밋 6개, 총 72개.
- 50개 원본을 이력으로 반영하고 19개 중복 backport 및 3개 기존 충족 항목을 별도 기록했다. 중복의 patch-id·역적용 결과와 다른 부분의 의미를 검토하고 최종 코드 및 테스트로 확인했다.
- [inventory.tsv](inventory.tsv)의 고정 기준 증거, 원본 299개, 부모 관계와 [evidence.tsv](evidence.tsv)의 사전 조사값은 보존한다. 추적표 검사는 제품 동작 검증을 대신하지 않는다.

## 통합 내용

| 영역 | 최종 동작 및 Europa 적응 |
| --- | --- |
| API key pair | 기존 key pair 데이터와 권한 상속을 보존한다. 관리 UI·1024자 설명·규칙 관리·동기 `registerUserKeys` 응답을 함께 반영한다. |
| 계정·권한 | ACL 전용 검사기로 권한 상승 여부를 검사하고 계정 생성 트랜잭션 전에 실행한다. 프로젝트 사용자 정리, userdata·annotation·host tag·CKS·2FA 접근 검사를 적용한다. |
| OAuth | 도메인별 공급자 설정과 ROOT/global 처리를 통합한다. 공급자 목록의 비밀키는 검증된 root 관리자 세션에서만 반환한다. 요청별 인증 흐름과 구성한 서명 키·클레임을 검증한다. |
| SAML·CA | 실제 RSA 서명 응답/Assertion과 등록된 IdP 인증서로 검증한다. 사용자 설정 CA의 키·인증서 정합성을 검사하고 잘못된 설정을 덮어쓰지 않는다. ROOT CA trust store와 강제 인증서 공급 경로를 반영한다. |
| 입력·백업 | 웹훅 목적지 정책, 진단 입력, URL 검증 순서 및 NFS 다운로드 처리를 반영한다. 백업 복원은 개별 명령 인자, mount/rsync 제한 시간, RBD XML 임시 파일, Linstor raw 형식 및 Europa cache mode를 보존한다. |
| API 기반 | UUID 파라미터 처리, 이벤트 사용자, OpenTelemetry, 비동기 작업 저장·이벤트·비밀번호 표시, JSON content type 및 Jetty 오류 응답 변경을 반영한다. |
| UI·의존성 | Europa DR/FTCTL/clone flatten/백업·브랜딩·로그인 확장을 유지하고 신규 한국어 문구를 추가한다. Axios 0.31.1과 의존성 잠금을 Node14/npm6에서 검증한다. S2의 Java 의존성 버전을 유지한다. |

## 검증 결과

검증 실행 환경은 Docker Rocky Linux 9.8 x86_64, JDK17, Maven3.9.10, Node14.21.3/npm6, MySQL8.0.46 x86_64다.

| 검사 | 결과 |
| --- | --- |
| Java 클린 빌드 | 161-module clean install 구간 및 재개 빌드 통과. 추가한 검증 도구를 기존 tools/build 배치 규약으로 정리한 후 완료했다. 최종 마스킹 변경의 Utils 재빌드도 통과했다. |
| Java 전체 회귀 | [951개 suite 결과](s3-local-tests.tsv), 11,704 tests / failures 0 / errors 0 / skipped 14. 161개 모듈 전체 실행 통과. |
| UI 설치·lint·단위 테스트 | `npm ci --no-audit --no-fund`, lint 통과. 28 suites / 343 tests 통과. |
| UI 프로덕션 빌드 | `./dev ui-build` 통과. 로컬 소스맵 제외 빌드이며 공식 UI Actions는 기본 프로덕션 빌드도 통과했다. |
| 기존 API 키 서명 | 실제 ApiServer HMAC 검증에서 정상 키 허용, 변조·삭제·만료·사용자 비활성·키 권한 거부 통과. DB/계정 조회는 fixture 사용. |
| MySQL S3 migration | 기존 인증 테이블, 신규 스키마, 중간 실패 후 재실행 3개 시나리오 통과. |
| 공식 Java Actions | [Build 34472435417](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34472435417) 통과. 172-module Noredist 빌드, 1,068개 suite 실행 기록, 12,485 tests / failures 0 / errors 0 / skipped 17. [개별 실행 결과](s3-ci-tests.tsv). |
| 공식 UI·라이선스 | [UI 34472435576](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34472435576), [License 34472435328](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34472435328) 통과. |
| 공식 RPM Actions | [Rocky 9.8 34472435525](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34472435525), [Rocky 9.7 34472435569](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34472435569) 통과. Rocky9.8 [공식 산출물](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34472435525/artifacts/10151049217)의 RPM9개 다운로드 체크섬 및 패키지 내부 실행 검사 통과. [환경·체크섬 기록](s3-artifact-results.tsv). |
| 추적표 | 고정 299개 및 부모·280개 근거·19개 merge·37개 의존성·10개 작업 배정 검사 통과. 최종 판정 113개(Applied33 / Adapted30 / Already Satisfied23 / Excluded27), Pending186. S3 66개 전부 확정, S2 Pending30 유지. |

공식 Java/UI/RAT는 PR 병합 후보 `e6dc42759bf6d61b9210c8b5d062632838f139be`를 검사했다. 후보와 제품 소스 `e086c24333`의 tree는 `1324d1b3ef7788d561bff27d92eed532dd27d68a`로 같다. 공식 suite 실행 기록은 클래스명이 중복된 모듈 1쌍을 포함한 1,068건이며 서로 다른 클래스명은 1,067개다. Coverage/Sonar/Simulator CI는 기존 workflow 조건에 따라 skipped였으며 통과로 합산하지 않는다.

추가·보강한 검증은 [API 서명 검증 코드](../../../tools/build/EuropaApiAuthenticationSmoke.java), `RegisterUserKeysCmdTest`, `ListOAuthProvidersCmdTest`, `OAuth2FlowCacheTest`, GitHub/Google/Keycloak provider tests, `RootCAConfigurationTest`, `SAML2SignatureVerificationTest`, `TemplateUrlValidationOrderTest`, `PasswordMaskingTest`, `LibvirtRestoreBackupCommandWrapperTest`, `GenerateApiKeyPair.spec.js`다. 기존 계정·동적 역할·프로젝트 역할·annotation·LDAP·CKS·진단·다운로드·비동기 작업 및 Europa 회귀 테스트도 전체 실행에 포함한다.

인증 fixture는 실제 서명 키/인증서와 공급자 설정 객체를 사용하고 HTTP 응답은 통제한 fixture로 제공한다. 성공/실패 서명, 발급자·클라이언트·유효기간, 도메인 경계, 재사용·동시 요청, 공급자 상태와 비밀키 노출 조건을 검증했다. 실제 외부 IdP 운영 환경 로그인, KVM mount/복원 및 다중 노드 CA 공급은 S8 인프라 검증에서 수행한다. Keycloak 설정은 해당 realm의 HTTPS token endpoint와 정상적인 서명 키 조회가 필요하다. Google/GitHub/Keycloak 로그인에는 공급자가 검증한 이메일을 사용한다. S8 운영 설정 점검에 이 조건을 포함한다.

API 서명 fixture는 컴파일된 `ApiServer.verifyRequest`를 호출하며 서명 비교를 대체하지 않는다. `server` 모듈의 테스트 의존성 classpath로 `tools/build/EuropaApiAuthenticationSmoke.java`를 `javac`으로 컴파일하고 같은 classpath에서 실행한다. 검증용 키·사용자·계정과 TransactionLegacy 접근만 고립시켜 실제 개발 DB를 사용하지 않는다.

Rocky9.8 산출물은 noredist/x86_64이며 Node14.21.3/npm6.14.18, Maven3.9.10, JDK17, Python3.10.21을 기록했다. 다운로드한 RPM9개는 Actions SHA256 manifest와 일치했고, 의존성 입력 176개 파일의 해시도 병합 제품 소스와 일치한다. 패키지 내부 BC RSA/X.509 및 OkHttp5.1.0/MinIO/InfluxDB 실행 검사가 통과했다. 이번 산출물은 S3 Snapshot 검증용이며 S8 최종 RC/Release 게시를 대신하지 않는다. 별도 release version을 지정하지 않아 UI release stamping 검사는 기존 조건대로 실행하지 않았다.

## 이미 4.23인 Europa DB 처리

`EuropaSecuritySchemaUpgrade`를 `Upgrade42210to42300.performDataMigration`과 Europa `afterUpgradeAblestack` 진입 경로에 연결했다. 기존 Europa는 DB 버전이 이미 4.23일 수 있으므로 과거 SQL의 `CREATE TABLE IF NOT EXISTS`만으로 완료 처리하지 않는다.

- 기존 `api_keypair.description`의 100자 정의를 1024자로 확장한다.
- OAuth authorize/token URL·domain 열, 인덱스·외래키를 필요한 경우에만 추가한다.
- 기본 역할의 `listUserKeyRules` 권한을 추가하되 기존 명시적 DENY와 사용자 정의 역할은 보존한다.
- 기존 키/암호화된 secret/provider 값 보존, 1024자 저장, 도메인별 설정 및 중복 제한, 반복 실행 시 권한·순서 불변을 확인했다.
- 외래키 적용 실패 fixture에서는 실패가 보고되고 기존 키가 보존되며, 원인을 수정한 후 재실행으로 완료된다.

Fixture는 014895d8f3의 인증 DDL과 최소 identity 테이블로 구성한다. 전체 운영 DB 복제본이 아니다. S2에서 확인한 99개 공통 초기화 SQL 오류와 simulator FK 오류의 해결 및 전체 업그레이드 검증은 #992에서 계속한다. 이번 S3 인증 migration 통과를 전체 DB 업그레이드 통과로 계산하지 않는다.

재현: [fixture SQL](s3/auth-schema-fixture.sql), [실행 스크립트](s3/run-schema-fixture.py), [JDBC 검증 코드](../../../tools/build/EuropaSecuritySchemaSmoke.java). 스크립트는 고정된 `epic991-auth-db` 호스트와 전용 marker만 사용하며 개발 DB 설정을 읽지 않는다. 이미 있는 `cloud` DB의 marker가 없거나 다르면 초기화를 거부한다.

```sh
# 프로젝트 호스트에서 새 검증 컨테이너를 만든다. 비밀번호는 폐기용 fixture 상수다.
docker run -d --name epic991-auth-schema-fixture --platform linux/amd64 \
  --network ablestack-cloud_development --network-alias epic991-auth-db \
  --tmpfs /var/lib/mysql \
  -e MYSQL_ROOT_PASSWORD=epic991-disposable-fixture -e MYSQL_ROOT_HOST=% mysql:8.0.46
# MySQL ready 이후, 소스/의존성/검증 명령은 개발 컨테이너에서 실행한다.
./dev exec mvn -B -ntp -pl engine/schema dependency:build-classpath \
  -Dmdep.outputFile=/tmp/s3-schema-classpath.txt
./dev exec python3 developer/history/apache-4.23.0.0-europa-2026-09-10/s3/run-schema-fixture.py \
  --classpath-file /tmp/s3-schema-classpath.txt
# 이 절차로 새로 만든 컨테이너만 제거한다. 기존 개발 볼륨은 삭제하지 않는다.
docker rm -f epic991-auth-schema-fixture
```

전체 lint는 S2와 같은 14개 실패 범주를 유지한다. 최초 후보의 수정 경로에서 나온 7개 진단도 S2 기준 로그와 동일했다. 검증용 SQL에 새로 발견된 라이선스 URL 오타는 최종 후보에서 수정했고 e086c24333 검사에서 신규 경로 진단 0개를 확인했다. 범주는 이미지 최적화, Markdown/properties/Shell/SQL/Vue/YAML 헤더, 실행 비트, 깨진 symlink, EOF, 혼합 줄바꿈, 뒤쪽 공백, codespell, markdownlint다. 전체 lint를 성공으로 처리하거나 검사/예외를 완화하지 않았다.

## 공유 merge와 다음 단계

S3 관련 merge는 `fb5e24fa0868`, `c7e2c748f746`, `efa58cc52fca`, `7fd56e573b8d`, `02182a1572a1`이다. remerge의 Rule/OTel/JSON 설정, userdata ACL, LDAP, 웹훅·OAuth, 계정 메서드·역할 cache, 백업 restore, UI escaping 및 테스트 충돌 해결을 최종 S3 코드와 대조했다. `7fc063ec13df`의 userdata DAO/계정 import 중복도 확인했다.

위 행들은 다른 기능의 side commit 또는 S4/S5/S6/S7 해결 내용을 포함하므로 전체 merge 및 `7fc063ec13df`는 Pending을 유지한다. S3가 소유한 해결 부분의 검증 사실은 merges.tsv와 s2-review.tsv에 기록한다. 후속 단계에서 미반영 side commit과 함께 최종 마감해야 한다.

S3 이후 #992에서 전체 DB/자원 할당/Usage/Quota를 진행한다. S8의 RC·실제 인프라·복구 검증, 남아 있는 전체 lint 기준 오류는 계속 게이트로 유지한다.
