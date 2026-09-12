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

# Diplo 선택 조회 실패와 인증 만료 경로 분리 (#1049)

## 범위와 설계

기준: upstream `ablestack-diplo` `678835c2008c47fc32c151196a0dd1a1cc446828`.
브랜치: `codex/fix-1049-diplo-optional-discovery`.
이슈 #1029 / PR #1051의 Java 연결 반환 수정과 독립적인 UI 변경이다.
Europa #1042 / PR #1050의 확정 구현을 참고하되 Diplo API와 초기화 차이를 유지한다.

- `api(command,args,method,data,options)`의 마지막 선택 인자로 내부 Axios metadata를 전달한다.
  기존 네 인자 GET/POST 동작은 유지한다. `getAPI`는 세 번째 options를 위 경로로 전달한다.
- 선택 조회는 `optionalDiscovery=true`, timeout 15000ms를 사용한다. metadata는 서버 파라미터에 넣지 않는다.
- `discoverOptional`은 현재 API 허용 목록의 own property를 확인하고 권한 미확정/없음이면 요청하지 않는다.
- 선택 조회의 network/timeout/403/432/404/503은 fallback으로 수렴한다. 현재 세션의 401은 기존 인증 만료 처리로 보낸다.
  실제 Axios 0.21.4 CancelToken 취소도 인식한다. 일반 API의 기존 오류 처리는 유지한다.
- 요청 시 로그인 generation을 내부 config에 기록한다. 이전 generation의 지연 오류는 401이어도 새 세션을 종료하지 않는다.
- store의 로그인/로그아웃/도메인 전환은 generation과 선택 기능 캐시를 초기화한다.
  API 목록 확정 후 capabilities를 읽고 provider/Cloudian/배경 재시작 알림을 권한별로 비동기 탐색한다.
  이 통합 탐색 완료를 로그인/라우트 생성 조건으로 사용하지 않는다.
- Diplo에서 주석 상태였던 로그인 LDAP 조회와 미사용 HSM 조회는 활성화하지 않는다.
  명시적 LDAP 설정 갱신은 권한 확인과 generation 방어를 사용한다.
- UserMenu의 favicon 설정·아이콘·호스트 조회는 기본값, 늦은 응답 차단, interval 단일 소유/해제를 적용한다.
  이벤트 구독과 store watcher도 해제한다. Wall 배너는 권한이 있을 때만 만들고 세션별로 다시 생성한다.
  배너 캐시는 사용자별 키를 사용하고 실패/해제 후 polling이 다시 생기지 않도록 한다.
- 종료 준비 배경 알림도 선택 조회 및 generation 방어를 적용한다.

## 소스 실행 및 단위 검증

수정 전 Diplo error handler 실행에서 선택 네트워크 오류는 Logout+로그인 이동,
404는 화면 이동을 유발했다. 수정 후 모두 발생하지 않고 현재 세션 401만 기존 Logout을 수행했다.
이전 generation의 401은 무시했다. Europa의 같은 잔여 경합은 별도 [#1052](https://github.com/ablecloud-team/ablestack-cloud/issues/1052)로 등록했다.

Diplo package-lock으로 `npm ci`를 수행했으며 Axios 0.21.4를 사용한다.
선택 요청 36건과 기존 API 테스트 1건, **총 37건 PASS**. 변경 파일 lint PASS.
권한별 조회, 미확정/상속 API 차단, 빈 설정/432, timeout/cancel/401, 늦은 응답,
재로그인 generation, 타이머 해제, 기존 Diplo GET/POST 계약을 검증했다.

```bash
npm ci --no-audit --no-fund
NODE_OPTIONS=--openssl-legacy-provider npm run test:unit -- --runInBand --coverage=false \
  --runTestsByPath tests/unit/api/index.spec.js tests/unit/api/request.spec.js \
  tests/unit/api/optionalRequest.spec.js tests/unit/optionalDiscovery.spec.js \
  tests/unit/autoAlertDiscovery.spec.js
NODE_OPTIONS=--openssl-legacy-provider npm run build
```

## 브라우저 장애 주입 재현 방법

`ui/tests/fixtures/optional_discovery_server.py`는 실제 production dist를 제공하는 localhost 전용 fixture다.
실제 Cloud 서버·자격증명을 사용하지 않는다. user/domain/admin으로 로그인할 수 있다.
`/tmp/issue1049-browser-fault` 파일 값(432/404/503/network/timeout/401)으로 선택 조회 오류를 지정한다.
`/tmp/issue1049-browser-requests.jsonl`에서 역할별 호출과 브라우저 error/unhandledrejection을 확인한다.
network는 연결 종료, timeout은 16초 응답 지연으로 재현한다.

## 브라우저 및 시험 서버 검증 결과 (2026-09-12)

Production UI 빌드: 소스 `d706d6a43ad`, WSL ext4, Diplo package-lock / Axios 0.21.4.
`npm run build` exit 0. Vue 파일 및 JS 변경 파일 lint PASS.

| 환경 / 조건 | 결과 |
| --- | --- |
| localhost production UI, 일반 사용자 / 도메인 관리자 | 로그인 성공, 관리자 선택 API 호출 없음 |
| localhost production UI, 관리자 / 432 | 로그인과 목록 진입 성공, 배경 오류 전파 없음 |
| 관리자, 선택 조회 404 / 503 / 연결 종료 / 15초 timeout | SSH 키 쌍 화면 및 세션 유지 |
| 관리자, 현재 세션 선택 조회 401 | 로그인 화면으로 이동, 기존 인증 만료 처리 유지 |
| 이전 세션의 지연 401 / 취소 / timer 해제 | 단위 테스트 PASS; 브라우저 시간 경합 재현 결과는 아님 |
| 실제 Diplo 시험 서버 | 배포 후 대시보드, 호스트 3개 조회/새로고침, 명시적 로그아웃과 재로그인 성공 |

장애 주입은 localhost fixture에서 수행했다. 실제 클러스터에는 서버 장애나 권한 변경을 주입하지 않았다.
실제 VM의 전원/스토리지/HA 상태를 변경하지 않았고, 기존 #1029 관리 JAR를 보존했다.

시험 중 빈 IDP 목록의 기존 Login.vue 예외를 발견하여 [#1054](https://github.com/ablecloud-team/ablestack-cloud/issues/1054)에 등록했다.
로그인 화면의 이 기존 예외와 선택 기능 탐색은 구분했다. fixture의 3회 로그인에서 해당 예외가 있었으므로
브라우저 전체가 무오류라고 주장하지 않는다. 이후 404/503/network/timeout 화면 새로고침에서 추가 미처리 rejection은 없었다.
실제 서버 로그인에서도 기존 빌드와 수정 빌드 모두 431 콘솔 오류가 관측됐으나 로그인은 성공했다.
431의 정확한 요청 원인은 미확정이며 해당 후속 이슈에서 로그인 초기 조회와 함께 분석한다.

## 배포 및 복구 기록

- 활성 UI: `/usr/share/cloudstack-management/webapp`.
- 백업: `/root/issue1049-ui/backup-20260912-160216/static-ui.tar.gz`.
- index SHA256: `76f1a135f142cfca4939867b61785714ddfad9426718079c0bfa01ba5038dfe1`.
- `WEB-INF`, 기존 `config.json`, #1029 관리 JAR SHA256 보존.
- `/client/` HTTP 200, `mold.service` active.
- 설치 JS에서 optionalDiscovery/discoveryGeneration 확인.
- 서버 디렉터리는 유지하고 static asset을 병합 복사한 뒤 index를 마지막에 교체했다. 서비스 재기동 없음.
- 복구 시 백업의 static asset을 활성 UI에 복원하고 index를 마지막에 복원한다.
  `WEB-INF`/`META-INF`를 삭제하지 않고 config 보존 여부와 HTTP 200을 확인한다.

## PR CI와 후속 작업

[PR #1053](https://github.com/ablecloud-team/ablestack-cloud/pull/1053)은 Diplo 대상으로 생성했으며 병합하지 않는다.
첫 CI에서 UI 전체 테스트의 AutogenView/Status 77건 실패와 SwiftUtilTest HMAC 실패를 확인했다.
독립된 이전 [PR #1051](https://github.com/ablecloud-team/ablestack-cloud/pull/1051)의 같은 Diplo 기반 CI에도
동일한 77건과 HMAC 실패가 있다. 이번 변경의 37건은 WSL에서 전부 통과했다.
RAT는 이전 PR 42개, 이번 PR 41개 미승인 파일로 실패했다. 기존 GlobalLayout의 ASF 헤더를 추가했다.
문서의 MD018 린트 오류는 이번 변경에서 생겨 수정했다. 전체 CI 성공으로 보고하지 않는다.

우선순위는 P1 [#1052](https://github.com/ablecloud-team/ablestack-cloud/issues/1052) Europa 이전 세션 401 방어,
P2 [#1054](https://github.com/ablecloud-team/ablestack-cloud/issues/1054) 로그인 초기 조회 예외와
[Diplo CI 후속](https://github.com/ablecloud-team/ablestack-cloud/issues/1055) 기존 CI 정리다.
