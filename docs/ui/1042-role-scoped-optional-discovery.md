# #1042 역할별 선택 API 탐색과 헤더 수명주기

## 문제와 범위

일반 사용자 로그인에서 관리자용 `listNetworkServiceProviders`, `listConfigurations`, `listWallAlertRules`가 실행되고, 설정 실패/빈 응답이 거부되거나 끝나지 않는 Promise를 만들었다. API 권한 상승 문제는 아니며, 서버의 권한 검사는 그대로 유지한다.

기준: upstream `ablestack-europa` `4c42a079caf885ea03747f356390a2d2d633829d`. 이슈: https://github.com/ablecloud-team/ablestack-cloud/issues/1042

## 코드 설계

- `utils/optionalDiscovery.js`: 현재 `listApis` 결과의 own property로 API 허용 여부를 확인한다. API 맵이 없거나 권한이 없으면 HTTP 요청을 하지 않는다. 허용된 선택 조회 실패는 `undefined`로 정착한다. 역할 이름으로 root만 허용하지 않아 도메인/사용자 정의 역할에 실제 부여된 권한을 유지한다.
- `store/modules/user.js`: `RESET_DISCOVERY`가 로그인/OAuth 로그인 시작, 로그아웃, 도메인 전환 시 generation을 증가시키고 계정별 API·기능·LDAP·Cloudian·zone·Security Group 캐시를 비운다. 비동기 탐색 결과는 동일 generation에서만 반영한다. 기능 탐색은 API 맵 확정 후 실행하며 LDAP/Cloudian/HSM/provider 조회는 로그인·라우트 생성 완료를 기다리게 하지 않는다. `RefreshFeatures`/`UpdateConfiguration`은 실패/빈 결과를 기본값으로 처리한다. 필수 사용자/API 탐색 실패는 호출자에게 전달한다.
- `header/UserMenu.vue`: 세 favicon 설정의 빈 응답/실패에 기본값(60초, 0.75, 0.55)을 유지한다. host 조회는 허용된 `listHostsMetrics`에 한정한다. 소유 타이머는 한 개만 유지하고 권한 변경/컴포넌트 해제 시 제거한다. 늦은 설정/아바타 응답은 무시하며 이벤트 구독과 store watcher도 해제한다. Wall 포털 설정 역시 허용 API에 한정한다.
- `page/GlobalLayout.vue`: `listWallAlertRules` 권한이 있을 때만 배너를 마운트하며 generation 변경 시 새 인스턴스를 생성한다.
- `header/AutoAlertBanner.vue`: 배너 내부에서도 모든 조회 API를 확인한다. 조회 실패를 잡고, 해제/세션 변경 후 응답 및 polling 재시작을 막는다. 로컬 silence 키는 사용자 ID로 구분한다. 기존 전역 silence 키를 새 계정으로 복사하지 않는다.

선택 조회에는 `getAPI`의 내부 `optionalDiscovery` 옵션과 15초 timeout을 부여한다. `utils/request.js`는 이 옵션이 있는 비인증 오류만 호출자에게 반환하여 네트워크 오류의 강제 로그아웃 및 404의 화면 이동을 막는다. 실제 401과 요청 취소, 일반 API의 기존 오류 처리, 백엔드 권한, Wall 외부 서비스/토큰 계약은 유지한다. 실제 Wall 서비스 연동 시험은 #1025에서 진행한다.

## 검증 방법

- 단위: 일반/도메인/루트 허용 목록, 정상 선택 조회, 432/네트워크 오류, 미확정 API 맵, 빈 설정, 늦은 응답, pending 선택 조회의 로그인 비차단, 헤더 타이머와 배너 unmount.
- 기존 권한/요청 처리 회귀 테스트, 변경 파일 lint, 잠금 파일 기준 의존성 설치와 production UI 빌드.
- production UI를 소프트웨어 브라우저로 실행하고 로컬 모의 API로 로그인 역할을 바꿔 메뉴와 요청을 확인한다. 모의 API 요청 기록 및 browser error/unhandledrejection 수집을 사용한다. 실제 Cloud/Wall 서비스 또는 물리 클러스터 PASS를 의미하지 않는다.

로컬 재현: `ui`에서 `npm ci`, `NODE_OPTIONS=--openssl-legacy-provider npm run build` 후 `python3 tests/fixtures/optional_discovery_server.py`를 실행한다. `http://localhost:8872/client/`에서 `user`, `domain`, `admin` 계정과 임의의 6자 이상 fixture 비밀번호로 로그인한다. 서버는 실제 인증을 수행하지 않는 로컬 시험 전용이다. `/__evidence` 및 `/tmp/issue1042-browser-requests.jsonl`에서 요청과 브라우저 오류를 확인한다. 루트에 허용된 provider/Wall 등의 조회에는 의도적으로 432, 설정 조회에는 빈 배열을 응답한다.

## 결과

- `npm ci --no-audit --no-fund`: PASS. Node 20.20.2, npm 10.8.2, Axios 0.31.1. 공용 기존 node_modules의 Axios 0.21.4 불일치를 발견해 분리 설치 후 다시 검증했다. lockfile/의존성 버전을 수정하지 않았다.
- 관련 Jest 6개 suite / 30개 test: PASS. 추가 API metadata 격리, 선택 오류 432/404/503/네트워크 및 실제 401 구분, 기존 Permission/API/request 회귀를 포함한다.
- 변경 JS/Vue/test 파일 lint 및 `git diff --check`: PASS.
- production UI 빌드와 브라우저 검증은 아래에 완료 결과를 기록한다.

## Diplo 후속

동일 네트워크 오류의 Logout 경로가 Diplo `678835c2008c47fc32c151196a0dd1a1cc446828`에도 존재함을 소스와 오류 처리 함수 실행으로 확인했다. 별도 **P1 #1049**에 근거/설계/검증 기준을 등록했다. 이 PR은 Europa에만 적용하며 Diplo 구현은 포함하지 않는다.
