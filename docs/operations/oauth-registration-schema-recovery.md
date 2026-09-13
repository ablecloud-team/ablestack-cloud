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

# Europa OAuth 등록 대화 상자 복구

관련 이슈: https://github.com/ablecloud-team/ablestack-cloud/issues/1067

## 원인

Europa UI의 OAuth 등록/수정 동작은 `authorizeurl`, `tokenurl`, `domainid`를
포함한다. 구형 OAuth 모듈이 남아 있는 관리 서버는 `listApis`에 이 매개변수를
노출하지 않는다. `AutogenView.getArgs()`는 없는 매개변수를 `undefined`로 반환하고,
폼 생성 과정에서 `type`/`name` 접근 오류가 발생하여 제목만 있는 창이 표시된다.

이 문제는 API가 전혀 없는 경우와 다르다. API 이름이 존재해도 매개변수 계약이
구형이면 발생한다. 브라우저 캐시 삭제만으로 구형 서버 API를 복구할 수 없다.

## UI 변경

- API 매개변수 배열과 화면이 요구하는 명시적 `args`를 폼 표시 전에 검증한다.
- 불일치하면 폼을 닫고 API 이름과 누락 매개변수를 한국어/영어로 안내한다.
- 서버에 없는 필드를 숨겨 불완전한 등록을 허용하거나 API 스키마를 조작하지 않는다.
- 기존 함수형 `args`, 대소문자 구분 없는 매칭, 합성 필드 처리는 유지한다.
- OAuth 등록/수정의 신형/구형 스키마, API 자체 누락, 합성 필드를 회귀 테스트한다.

## 배포 의존성

최신 OAuth 플러그인만 교체하는 것은 충분하지 않다. 배포본과 다음 항목의
바이트코드/스키마 계약을 함께 확인해야 한다.

| 대상 | 필요한 내용 |
| --- | --- |
| `plugins/user-authenticators/oauth2` | OAuth API, DAO/VO, 제공자, 매니저, Spring 리소스 |
| `api` | `UserOAuth2Authenticator`의 도메인 인자 오버로드 |
| `framework/config` | `ConfigKey.withStrictScope()`와 관련 스코프 조회 지원 |
| `oauth_provider` | `domain_id`, `authorize_url`, `token_url`, 도메인 FK/인덱스 |

2026-09-13 사전 점검에서 13번은 DB 컬럼/인덱스가 이미 있었지만 OAuth 클래스는
구형이었다. 22/31/32번은 해당 OAuth 컬럼/인덱스도 누락되어 있었다.

전체 Cloud 빌드 대신 WSL ext4 체크아웃에서 필요한 Maven 모듈과 UI만 빌드한다.
단일 배포 JAR에 병합할 때는 OAuth 네임스페이스, 공통 OAuth 인터페이스,
`ConfigKey` 클래스 계열만 허용 목록으로 관리한다. `ConfigDepotImpl` 등 다른
배포 클래스와의 메서드 호환성을 별도로 확인한다.

Spring 설정 파일 내용뿐 아니라 JAR의 `META-INF/cloudstack/oauth2/` 디렉터리
엔트리도 유지해야 한다. 디렉터리 엔트리가 사라지면 Spring 리소스 탐색이 설정
파일을 놓칠 수 있다. 이때 HTTP 200과 관리 서비스 active만으로 성공 판단하면 안 된다.

## 빌드 및 검증

```bash
mvn -pl plugins/user-authenticators/oauth2 -Dcheckstyle.skip=true -Drat.skip=true test package
mvn -pl api -DskipTests -Dcheckstyle.skip=true -Drat.skip=true package
mvn -pl framework/config -Dcheckstyle.skip=true -Drat.skip=true test package
cd ui
npm run test:unit -- --runTestsByPath tests/unit/views/OAuthActionSchema.spec.js tests/unit/views/AutogenView.spec.js --runInBand
./node_modules/.bin/eslint --no-fix src/views/AutogenView.vue tests/unit/views/OAuthActionSchema.spec.js
NODE_OPTIONS=--openssl-legacy-provider npm run build
```

API 모듈 명령은 테스트 실행을 생략한다. OAuth 및 설정 모듈의 테스트 결과와
구분하여 보고한다. `checkstyle`/`RAT` 생략 역시 전체 CI 통과를 의미하지 않는다.

2026-09-13 로컬 결과: OAuth 95개, 설정 모듈 29개, UI 135개 테스트가 모두
통과했다. API 모듈 패키징, UI ESLint 및 production 빌드도 성공했다.
PR의 자동 전체 Cloud 빌드는 작업 승인 범위 밖이므로 커밋에 `[skip ci]`를
명시한다. 이는 CI 통과를 의미하지 않으며 전체 CI/전체 배포 검증과 구분한다.

## 안전한 적용 순서

1. 관리 서비스, 로그인, OAuth API 매개변수, DB 컬럼/인덱스, OAuth 제공자 수를 확인한다.
2. 원본 JAR과 OAuth 테이블을 백업하고 VM 상태/호스트, 활성 보호, 자원 스케줄을 기록한다.
3. OAuth 전용 상위 버전 스키마 구문만 멱등 적용한다. 전체 업그레이드 SQL을 수동 실행하지 않는다.
4. 허용된 클래스/리소스만 병합하고 나머지 JAR 엔트리가 바이트 단위로 동일한지 확인한다.
5. 관리 서비스를 재시작하고 로그인 및 `listApis`의 신규 매개변수 노출을 검증한다.
6. UI의 해시 자산을 먼저 배포하고 `index.html`을 마지막으로 적용한다. `config.json`,
   `WEB-INF`, `META-INF`와 이전 해시 자산을 보존한다. webapp 전체 교체나 `rsync --delete`는 금지한다.
7. 13번에서 OAuth 등록 창, 필수 입력 검증, 제공자 선택과 추가 필드를 확인한다.
8. 가능한 경우 테스트 전용 제공자의 등록/수정/조회/삭제를 검증하고 잔여 데이터를 확인한다.
   OAuth가 비활성화되어 있으면 검증 목적으로 인증 정책을 임의 변경하지 않는다.
9. 다른 클러스터에 순차 적용하고 VM/보호/스케줄 보존, 기존 API와 UI 기능 표식을 확인한다.

실제 Google/GitHub/Keycloak 인증 서버와의 로그인 완료 검증은 유효한 IdP 설정을
요구한다. 임시 제공자 CRUD나 등록 창 표시 성공을 외부 인증 전체 성공으로 보고하지 않는다.
