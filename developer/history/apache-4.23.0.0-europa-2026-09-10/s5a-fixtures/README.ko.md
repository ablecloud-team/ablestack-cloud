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

# S5A 전용 검증 fixture

이 디렉터리는 #993의 실제 JDBC DDL 실패 재시도와 관리 서버 API 시험에 사용한 실행기를 보존한다. 운영 또는 기존 개발 DB에 실행하지 않는다. 모든 실행·컴파일은 `./dev exec`를 통한 Rocky9.8 컨테이너 내부에서 한다.

## DDL 실패 재시도

`EuropaComputeDdlReplay.java.fixture`는 실제 `EuropaComputeSchemaUpgrade.migrateSchedules`를 실행한다. 정확한 호스트명 `epic993-mysql-ddl`만 허용하며 그 전용 fixture의 cloud DB를 매 사례마다 재생성한다. 외부 DB 주소로 일반화하지 않는다.

1. 작업 전용 Docker network `epic993-fixtures`와 MySQL8.0 tmpfs 컨테이너를 만든다. 컨테이너 label은 `com.ablecloud.task=epic993`이며 root password `epic993-fixture-dummy`는 공개 합성 시험값이다.
2. 이 디렉터리의 Java 및 SQL을 컨테이너 `/tmp/epic993/`에 복사한다.
3. 현재 schema 및 utils JAR과 Maven dependency classpath로 Java를 컴파일하고 MySQL Connector/J8.4.0을 포함하여 `com.cloud.upgrade.dao.EuropaComputeDdlReplay epic993-mysql-ddl`을 실행한다.
4. 모든21개 자동 커밋 DDL 경계, 반복 실행, 데이터·unique·cascade, 모호한 테이블 거부 PASS 출력을 확인한다.

DDL helper는 원본 cron 문자열 보존을 시험한다. 이6필드 합성 문자열로 실제 scheduler 실행을 승인하는 시험은 아니다. 별도의 실제 API CRUD는5필드 cron을 사용한다.

## 전체 업그레이드·관리 서버

전체014 출발점은 S4에서 만든449개 table/view의 cloud/cloud_usage dump를 복제하여 사용했다. 전용 사용자와 grant를 만든 뒤 실제 `DatabaseCreator` checker 경로를 실행했다. 새 설치는 init SQL부터 실행하며 기존 fixture는 init SQL을 실행하지 않는다. S4 공식 Rocky9.8 management RPM의 JAR로 먼저 S4 단계만 완료한 별도 fixture에서도 S5A 전환을 실행했다.

`EuropaManagementStartup.java.fixture`는 `epic993-mysql-` 접두사 DB만 허용하고 loopback HTTP18993에 관리 서버를 연다. JDBC properties, 독립 user.home/SSH 키, 로그, IP 도구와 SystemVM 메타데이터는 전용 `/tmp/epic993`에 준비한다. 실제 개발 `db.properties.override`를 복사하거나 비밀번호를 출력하지 않는다. S4 fixture README의 관리 서버 구성 절차를 참고하되 이 호스트·포트로 분리한다.

`check-compute-api.py`는 전용 upgrade fixture의 VM993001과 기존 schedule5개를 전제로 한다. 합성 사용자 `s5a-runtime-admin`/`S5A-fixture-password`는 그 DB에만 존재한다. 로그인 후 신규 API 생성, 기존 API 수정, 신규 API 삭제와 기존5개 보존을 확인한다. API 응답의 sessionkey나 DB row 전체를 보고서에 저장하지 않는다. PyMySQL은 Docker 내부 전용 venv를 사용한다.

각 실행 전후 전체 schema/data snapshot과 version/journal 행을 비교했다. 원본 snapshot·dump에는 합성 인증 자료가 포함될 수 있어 Git에는 올리지 않고 결과·체크섬만 기록한다. 관리 서버가 생성하는 초기 설정을 전체 DB 무변경으로 주장하지 않는다.

## 범위와 정리

전용 fresh/S4/DDL tmpfs 컨테이너는 검증 후 정지하여 메모리를 반환했다. tmpfs DB는 정지 후 보존되지 않으므로 재시험 시 fixture부터 재생성한다. 개발 DB와 Docker 기존 볼륨은 삭제·초기화하지 않았다. 실물 KVM·Ceph·VMware·FTCTL 시험은 이 fixture의 범위가 아니며 #1025에 이관되어 모든 코드 병합 후 수행한다.

Standalone Java 실행기는 `.java.fixture` 확장자로 보관한다. developer 모듈의 소스 루트가 이력 디렉터리까지 포함하므로 직접 컴파일 대상에 섞이지 않도록 분리한 것이다. 실행 시 `/tmp`의 동일 이름 `.java`로 복사하여 javac로 컴파일한다.
