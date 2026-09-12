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

# S4 격리 fixture 재현 조건

이 문서는 실행 조건과 판정 방법을 기록한다. 운영 DB에 초기화 명령을 실행하는 도구가 아니다. 모든 소스·의존성·JVM 실행은 Rocky9.8 amd64 개발 컨테이너 안에서 수행했다. MySQL은 전용 네트워크의 `epic992-mysql-baseline`, `epic992-mysql-fresh`, `epic992-mysql-upgrade` 세 컨테이너이며, `com.ablecloud.task=epic992` label·tmpfs 데이터 경로·외부 포트 없음으로 분리했다. 개발 DB 설정을 복사하지 않고 Git에 있는 기본 설정을 시험용 호스트와 공개 dummy 암호로 치환했다.

## DB 실행 경로

1. 별도 worktree의 `014895d8f3`를 당시 JDK17 의존성으로 패키징하고, 해당 schema JAR과 Connector8.0.33으로 baseline의 cloud/cloud_usage를 생성한다. 기존 SQL99 오류와 정상 종료라는 잘못된 결과를 기록한다.
2. baseline에 구별 가능한 합성 행을 추가한다. API key pair, OAuth provider, usage volume/VM event, quota usage/credit, 사용자 설정, role4의 quotaCreditsList DENY다. 전체 dump와 기존 열·행 다중집합 및 version 행을 보관한다. 이번 기준은449 table/view,11,271행,version51행이었다.
3. 후보 전체 `mvn -B -Pdeveloper,systemvm -DskipTests install`로 만든 JAR와 Connector8.4.0을 사용한다. 소스 resources 디렉터리 대신 패키징한 schema JAR을 classpath에 넣는다. FileUtil의 JAR 리소스 경로도 실제 실행한다.
4. fresh에서 `com.cloud.upgrade.DatabaseCreator FRESH_PROPERTIES create-schema.sql create-schema-premium.sql templates.sql developer-prefill.sql com.cloud.upgrade.DatabaseUpgradeChecker --database=cloud,usage --rootpassword=DUMMY`를 실행한다. SQL 파일은 `developer/target/db`와 `developer/developer-prefill.sql`을 사용한다. 이 초기화 명령은 빈 전용 fresh fixture에만 실행한다.
5. baseline 전체 dump를 별도 upgrade fixture에 복원한다. 기존 DB에서는 **`com.cloud.upgrade.DatabaseCreator UPGRADE_PROPERTIES com.cloud.upgrade.DatabaseUpgradeChecker`**만 실행한다. SQL 파일·database 초기화·rootpassword 인수를 넣지 않는다.
6. upgrade의 cloud DB 사용자에게 cloud_usage ALTER 권한을 회수하고 같은 checker를 실행한다. 실패 종료1/Pending/기존 version·index 보존을 확인한 후 ALTER를 복구한다. 같은 명령을 서로 다른 JVM 두 개에서 동시에 실행해 잠금과 복구를 확인한다. 이어 두 번 순차 실행하고 전체 snapshot의 데이터·DDL·checkpoint timestamp를 비교한다.
7. fresh와 upgrade의 information_schema에서 열 type/null/default/extra/collation, index unique/열 순서, FK 참조·삭제/수정 action을 비교한다. 열 표시 순서·COMMENT·AUTO_INCREMENT 카운터는 비교하지 않는다.
8. fresh에서 simulator SQL 세 개(create-schema-simulator, templates.simulator, hypervisor_capabilities.simulator)를 `--database=simulator`로 실행하고 template100/111 및 참조를 확인한다. 실제 simulator zone 배포 검증은 S8에 남는다.

모든 JDBC 실행은 JDK17의 java.lang/java.lang.reflect add-opens와 `-Dpaths.script=/workspaces/ablestack-cloud/developer/target/db`를 사용한다. 원본 덤프·API secret·SSH 개인 키는 저장소에 포함하지 않는다. fixture 전체 row 비교의 결과만 [s4-db-results.tsv](../s4-db-results.tsv)에 공개한다.

## 예약과 과금

- `tools/build/EuropaReservationConcurrencySmoke.java`: 실제 DAO·DB GlobalLock을 호출한다. 한 CPU 정책만 mock으로 구성하여8개 동시 요청의 승인1/거부7/예약0을 검증한다. CheckedReservationFailureTest는 persist·lock·부분 정리 오류를 별도로 검증한다.
- `tools/build/EuropaUsageRuntimeSmoke.java`: 필수 Usage 설정 없이 시작하면 오류, 정상 schedule와 quota.enable.service=true이면 실제 Spring 시작이 성공해야 한다. `aggregate` 인수는 실제 QuotaManager를 호출한다.
- 집계 fixture는 Usage job처럼 cloud account를 usage account에 복사하고, credit API처럼 quota_credits와 quota_balance에 함께100을 기록한다. 2026년1월 IP 요금744+372와2시간 raw usage, volume 요금744/GiB와1GiB·24시간 raw usage, 같은 볼륨의 VM 부착 기간을 넣는다. IP3/volume24/부착0, mapping3,잔액73을 SQL로 확인한다. 집계를 반복해 행수·합계·잔액이 같아야 한다. aggregate의 반환값만으로 통과 판정하지 않는다.

## 실제 관리 서버 및 API 재시작

[EuropaManagementStartup.java](EuropaManagementStartup.java)는 최종 client JAR의 ServerDaemon을 loopback18992에서 시작한다. `epic992-mysql-` 호스트만 허용한다. 실제 운영 HTTPS/클러스터 시험을 대신하지 않는다.

```bash
javac -cp 'client/target/cloud-client-ui-4.23.0.0-SNAPSHOT.jar:client/target/lib/*' -d /tmp/epic992 developer/history/apache-4.23.0.0-europa-2026-09-10/s4-fixtures/EuropaManagementStartup.java
java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED -Xmx2g -Duser.home=/tmp/epic992/management-home -Djava.io.tmpdir=/tmp/epic992/management-tmp -Dcatalina.home=/tmp/epic992/management-conf -Dpaths.script=/workspaces/ablestack-cloud/developer/target/db -cp '/tmp/epic992:/workspaces/ablestack-cloud/client/target/cloud-client-ui-4.23.0.0-SNAPSHOT.jar:/workspaces/ablestack-cloud/client/target/lib/*' EuropaManagementStartup /tmp/epic992/upgrade-db.properties
```

작업 디렉터리는 임시 management-home이다. 그 안에 `.ssh`와 `engine/schema/dist/systemvm-templates` 링크를 준비한다. Rocky iproute와 해당 libmnl/libbpf 라이브러리가 필요하다. root 권한이 없는 개발 컨테이너에서는 DNF로 RPM을 다운로드하여 임시 디렉터리에 추출하고 해당 JVM에만 PATH/LD_LIBRARY_PATH를 지정했다.

별도 시험 관리자와 quota.enable.service=true를 fixture에 추가한다. `/client/api` login 후 세션으로 quotaCreditsList를 호출한다. root domain UUID와 기존 account2 UUID를 사용하고, 2025-12-31~2026-01-31 범위의 합성 credit123.4567·통화₩·count1을 확인한다. 기존 사용자 암호를 변경하지 않는다. 프로세스를 종료하고 같은 명령으로 두 번 재시작하여 매번 API를 검증한다.

각 시작 전후에 version51행 전체, migration journal 전체(timestamp 포함), 합성 API key pair/OAuth provider 행, role4 DENY, 기존 credit, Usage volume 복합 unique index를 비교한다. 정상 초기화가 생성하는 시스템 설정·SSH 키 등의 변경과 이를 구분한다. API 응답의 세션 토큰과 raw secret은 로그나 추적표에 공개하지 않는다. 시험 후 이 helper가 소유한 JVM만 종료한다.

빈 네트워크 DB의 LB health-check, systemd 없는 컨테이너, 클러스터 IP/인증서와 설치 경로 경고는 남는다. 이 결과는 실제 관리 서버의 시작·로그인·Quota 조회·migration 재시작 검증이며, 운영 규모/클러스터/물리 하이퍼바이저의 최종 검증은 #999에서 수행한다.

Standalone Java 실행기는 `.java.fixture` 확장자로 보관한다. developer 모듈의 소스 루트가 이력 디렉터리까지 포함하므로 직접 컴파일 대상에 섞이지 않도록 분리한 것이다. 실행 시 `/tmp`의 동일 이름 `.java`로 복사하여 javac로 컴파일한다.
