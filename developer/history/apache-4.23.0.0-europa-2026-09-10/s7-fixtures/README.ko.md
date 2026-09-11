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

# S7 소프트웨어 검증 fixture

이 fixture는 실제 개발 DB·운영 호스트에 연결하지 않는다. 소스/의존성/실행은 Rocky9.8 amd64 Docker 안에서만 수행한다.
고정 도구는 JDK17, Maven3.9.10, Node14/npm6, Python3.10 및 MySQL8이다.

- `EuropaGuiThemeDdlReplay.java`: 호스트 인수가 정확히 epic998-mysql-ddl인 임시 MySQL만 허용한다. **해당 fixture의 cloud DB를 삭제하고 생성**한다. S7 SQL 커밋 직후 실패 주입, Pending/재시도/Complete/재실행과 기존 theme/journal 보존을 검사한다. dummy 비밀번호는 epic998-fixture-dummy이며 개발용 설정 파일과 무관하다.
- 전체 DB 경로는 이전 S4/S6 fixture의 014895d8f3 및 S6 dump와 새 DatabaseCreator를 사용했다. 신규/014/S6별 실행 뒤 전체 DB의 테이블·view DDL과 모든 행을 snapshot으로 저장하고 DatabaseUpgradeChecker 재실행 결과를 바이트 단위 비교했다. `s7-db-results.tsv`에 해시/객체/행 수를 기록한다. 원본 데이터 dump나 개발 DB 비밀번호는 커밋하지 않는다.
- S6에서 S7로 넘어갈 때 바뀌는 객체는 gui_themes, gui_themes_view, ablestack_schema_migration 3개다. 이전 journal 행과 시간은 그대로이며 S7 행 하나만 추가된다.
- `api-theme.py`: /tmp/epic998에 작업 디렉터리를 준비하고, 조립된 client JAR와 lib 디렉터리로 관리 서버를 loopback18998에서 시작한다. DB 설정은 위 임시 DB만 사용한다. 실제 HTTP login/createGuiTheme/updateGuiTheme/listGuiThemes/removeGuiTheme와 일반 사용자 접근 거부를 검증한다. 새 시험 계정만 만들며 기본 fixture 인증 정보는 운영용이 아니다.
- `api-discovery-contracts.json`: quota.enable.service=true인 임시 관리 서버의 listApis 결과에서 API key/KMS/Quota/DNS 화면의 32개 API 파라미터·응답 이름만 추출했다. 사용자/세션/키 값은 포함하지 않는다.
- UI 단위 회귀는 `ui/tests/unit`의 AutogenView, GuiTheme, ImageDeployInstanceButton, ListViewAccounts, Permission 및 기존 전체 suite로 실행한다. 실제 Vue 컴포넌트와 라우터 guard를 사용하며 외부 API만 통제한다.
- Chromium은 Docker 내부의 기존 @playwright/test1.34.3과 Chromium114를 사용한다. 개발 UI는 CS_URL=http://127.0.0.1:18998, CS_COOKIE_HOST=127.0.0.1, port19098로 실행한다. 기존 FTCTL 실물 E2E 전체 명령은 실행하지 않는다. 최종 브라우저 결과는 PR #1039와 S7 보고서에 기록한다.

실제 공급자/물리 VM/운영 DB 검증과 성능·장애 전환 시험은 #1025에 NOT_RUN으로 인수한다.
