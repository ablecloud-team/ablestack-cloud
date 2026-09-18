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

# Europa DR 스키마 업그레이드 순서 수정 (#1122)

## 문제와 수정

`schema-42200to42210.sql`은 `dr_run`을 만들기 전에 복구 지점의 `run_id`를 보정했다. 기존 설치에 DR 테이블이 없으면 `Table 'cloud.dr_run' doesn't exist`로 관리 서버 초기화가 실패한다. `4.22.1.0 → 4.23.0.0` 스크립트만 바꿔서는 이전 단계의 실패를 해결할 수 없다.

복구 지점 데이터 보정과 중복 정리, 인덱스 보완 묶음을 `dr_run` 및 `dr_run_step` 생성/열 보완 뒤로 이동한다. 보정 SQL 자체와 최신 active run 선택 규칙은 유지한다. 오류를 무시하거나 DB 버전을 수동 변경하지 않는다.

## 재현 가능한 회귀 검증

WSL ext4 체크아웃에서 변경 모듈만 빌드한다.

```bash
mvn -pl engine/schema -Dtest=DrSchemaContractTest -Dcheckstyle.skip -Drat.skip=true package
```

순서 회귀 테스트는 `schema-42200to42210.sql`, `schema-42210to42300.sql`, `schema-Europa-After.sql` 각각에서 복구 지점 backfill에 필요한 테이블과 `run_id` 열이 먼저 준비되는지 확인한다. 기존 SQL로 되돌리면 새 테스트가 실패해야 한다.

실제 MySQL 데이터 회귀 테스트는 테스트 서버에서 다음과 같이 실행한다. mysql CLI와 DB/프로시저 생성 권한이 필요하며 인증은 mysql 옵션 파일 또는 `MYSQL_PWD`로 제공한다. 인증 정보를 소스나 명령 이력에 저장하지 않는다.

```bash
python3 engine/schema/src/test/scripts/test_dr_restore_point_upgrade.py --user root
```

이 테스트는 무작위 이름의 별도 DB를 생성하고 finally에서 제거한다. 실제 마이그레이션의 DR 구간과 실제 idempotent 프로시저를 사용한다. 외부 참조 대상 테이블은 ID만 갖는 테스트 fixture이므로 전체 업그레이드 검증을 대체하지 않는다.

- DR 테이블이 없는 상태에서 생성 및 빈 데이터로 재실행
- 기존 복구 지점에 새 열이 없는 상태에서 보정
- 제거된 run을 제외한 최신 run 선택, full-seed/incremental 체크포인트 추출
- 중복 복구 지점 soft delete, 레코드 보존
- 재실행 시 이미 존재하는 run 연결 보존

전체 업그레이드 검증은 실패 시점의 `cloud`와 `cloud_usage` 백업을 별도 DB에 복원하여 수행한다. 원본 DB 권한이 없는 테스트 계정으로 SQL을 실행하며, DB 이름과 프로시저의 스키마 참조를 모두 테스트 DB로 치환한다. mysql CLI 재현에서는 관리 서버 `ScriptRunner`처럼 `--`로 시작하는 줄을 주석으로 처리해야 한다. upstream의 `---` 주석을 mysql CLI에 그대로 전달하면 관리 서버와 다른 구문 오류가 발생한다.

## 실패한 설치의 배포 및 재개

1. 설치 패키지 버전, 실제 JAR 내부 SQL, `cloud.version`, 기존 DR 테이블/열/인덱스, VM/호스트 상태를 읽기 전용으로 확인한다.
2. `cloud`/`cloud_usage`의 데이터·routines·triggers·events와 원래 관리 JAR을 백업한다. 복제 DB에서 기존 실패 재현과 수정본 재시도를 먼저 검증한다.
3. usage/management 서비스를 정지한다. 설치된 SQL이 분석한 원본과 일치하는지 확인한 후, 모듈 빌드 산출물의 수정 SQL 리소스만 설치 JAR에 반영한다. 패치 전후 JAR의 다른 모든 엔트리가 동일함을 확인한다.
4. 관리 서버를 시작하여 실제 업그레이드 체인과 Java 데이터 마이그레이션이 끝나는지 확인한다. `cloud.version`을 수동으로 올리지 않는다.
5. `Complete` 버전, 시작 로그, HTTP 응답, UI 로그인, VM/호스트/DR 조회를 확인한 후 기존 활성 usage 서비스를 복구한다.
6. 실패 시 로그와 실제 DB 상태를 보존한다. MySQL DDL은 암묵적 커밋을 하므로 단순 transaction rollback 또는 JAR만 되돌리는 방법을 DB 복구로 간주하지 않는다. DB 버전이 진행된 뒤 복구가 필요하면 해당 백업과 호환되는 바이너리/DB를 함께 복구하는 별도 절차를 수립한다.

전체 Cloud 패키지 재빌드나 호스트 패키지 배포는 이 SQL 수정에 필요하지 않다. 정식 패키지는 이후 이 커밋을 포함한 릴리즈 빌드로 배포해야 한다.

## 2026-09-18 테스트 기록

- 테스트 Mold: `192.168.1.110`, MySQL `8.0.45`, 설치 패키지 `4.23.0.0-Mold.Europa.202609171515.1`.
- 변경 전: DB `4.22.0.0 Complete`, DR 테이블 6개, `dr_run` 없음, UI HTTP 503.
- 설치 원본 SQL과 upstream 기준 파일의 SHA256 일치: `42826a7a597b3a3654c834af5629a4882f61b56f29bb41c10be5a9509fed6431`.
- `cloud-engine-schema` 모듈 package 성공, `DrSchemaContractTest` 2건 통과. 원본 SQL 대조 실행에서는 새 순서 테스트가 예상대로 실패했다.
- MySQL 독립 fixture 테스트: DR 미사용/빈 테이블 재실행, 기존 데이터 및 누락 열, 중복 soft delete, 기존 run 연결 보존 모두 통과.
- 실패 DB 복제 검증: 원본 SQL 실패 재현, 수정 `42200to42210` 전체 SQL 성공 및 재실행 성공, 다음 `42210to42300` 전체 SQL 성공. 테스트 DB와 제한 계정 제거 완료.
- 배포 리소스 SHA256: `5e073712edaf48a935f524952370729f11f811a46f4c896399dd80e27fe7c91b`. JAR 내부 `META-INF/db/schema-42200to42210.sql` 외 모든 엔트리 내용이 동일함을 검사했다.
- 테스트 환경 백업: `/root/europa-upgrade-1122-20260918/` (DB dump와 기존 JAR, 전후 로그). 인증 정보와 DB 백업은 저장소에 포함하지 않는다.
- 실제 관리 서버 시작으로 `4.22.1.0 Complete`(01:25:27 UTC), `4.23.0.0 Complete`(01:25:41 UTC) 기록을 확인했다. DB 버전을 수동 변경하지 않았다.
- 관리 서버/usage 서비스 active, `/client/` HTTP 200, `WEB-INF` 보존. 관리자 브라우저 로그인과 대시보드/VM 목록/호스트 목록 조회를 확인했다.
- 호스트 `192.168.1.104/105/106` 모두 Up/정상, 시스템 VM 연결도 Up. 사용자 VM 7대(실행 4, 정지 3)를 포함한 기존 VM의 ID/UUID/type/state/host_id가 백업 시점과 일치했다.
- 현재 테스트 환경은 `cloud.dr.service.enabled=false`이다. DR 메뉴가 노출되지 않으며 `listDrPlans`/`listDrSites`는 Unknown API command를 반환한다. 이 설정을 변경하지 않았고 DR 기능의 활성 상태 UI/복제/복구 검증을 완료했다고 주장하지 않는다.
- 시작 로그에 `commands.properties` 탐색 ERROR 1건이 남았지만 DB 업그레이드 예외 및 모듈 초기화 실패는 재발하지 않았고 UI 로그인/호스트 연결은 성공했다. 이번 SQL 순서 수정으로 모든 기존 패키지 경고가 해소된 것은 아니다.
- 검증 범위는 변경 스키마 모듈, MySQL 마이그레이션 및 실제 관리 서버 업그레이드/기본 UI이다. 전체 Cloud 빌드, 전체 테스트 스위트, DR end-to-end 검증은 수행하지 않았다.
