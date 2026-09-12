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

# S8 격리 DB 도메인 재실행 fixture

이 소스는 Maven 기본 test 경로 밖의 수동 JDBC 통합 fixture다. 실제 운영 DB에서는 실행하지 않는다.
파일 확장자 .fixture는 Maven 전체 Checkstyle이 default-package 보조 실행기를 제품 Java로 인식하지 않게 한다.

1. Docker 내부 작업 임시 디렉터리에 EuropaSystemVmReplay.java로 복사한다.
2. 최종 프로젝트 빌드의 engine-schema 및 전이 의존성 classpath로 javac -cp를 실행한다.
3. java -cp에 임시 class 디렉터리와 같은 classpath를 지정하고 EuropaSystemVmReplay의 인수로 격리 db.properties 경로를 전달한다.
4. host는 epic998-mysql-로 시작하는 전용 fixture만 허용한다. 이번 실행은 epic998-mysql-ddl을 사용했다.

db.cloud.host/username/password를 읽으며 값을 출력하지 않는다. 운영 구성 파일을 사용하지 않는다.
생산 migrate 메서드를 각 경우에 두 번 호출하여 설정18개 기대값을 확인한다.
ScriptRunner는 DML을 commit하므로 시험 후 원래 설정을 명시적으로 복원한다.
실행 전후 전체 DB snapshot 일치도 별도로 확인했다. 실패 시에도 finally에서 설정 복원을 시도한다.
새 DB/014895d8f3/S7 전체 DatabaseUpgradeChecker 검증 결과는 ../s8-db-results.tsv에 있다.
재현에는 해당 baseline schema와 합성 fixture가 필요하며 운영 데이터 제공은 요구하지 않는다.
