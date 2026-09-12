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

# S5C KMS 검증 fixture

모든 소스·의존성·실행은 Docker의 /workspaces/ablestack-cloud 내부에서 수행한다.
호스트에서는 프로젝트의 ./dev exec를 사용한다.
일회용 epic996-mysql-ddl (MySQL 8)과 loopback 18996의 Management만 대상으로 한다.
기존 개발 DB·볼륨에 이 fixture를 실행하지 않는다.

## 환경과 빌드

- Rocky 9.8 linux/amd64, JDK 17, Maven 3.9.10, Node 14/npm 6, Python 3.10.
- Maven 명령: mvn -B -P developer,systemvm -Dsimulator -Dnoredist -DskipTests install
- 집중 테스트: 같은 profile에서 server,engine/orchestration,engine/storage/volume,plugins/kms/database,plugins/kms/pkcs11 모듈을 선택하고 KMS*,DatabaseKMSProviderTest,PKCS11*,VolumeObjectTest,VolumeOrchestratorTest를 실행한다.
- 로컬 실행 자료·로그는 /tmp/epic996에 있다. S5B에서 확보한 native QEMU 10.1 경로는 /tmp/epic994/native/usr/bin/qemu-img이며 LD_LIBRARY_PATH는 /tmp/epic994/native/usr/lib64다.
- Python API/DB fixture는 PyMySQL을 설치한 Python 3.10 환경을 사용한다. 이번 실행은 /tmp/epic992/db-venv를 사용했다.
- DB 인증의 epic996-fixture-dummy, 기본 fixture 관리자 admin/password 및 S5c!test2026은 이 일회용 환경 전용 값이다.

## DDL 중단 복구

EuropaKmsDdlReplay.java.fixture를 /tmp/epic996/EuropaKmsDdlReplay.java로 복사한다.
현재 engine/schema jar·의존성 및 engine/schema/src/main/resources를 classpath에 놓고 javac -d /tmp/epic996/db-classes로 컴파일한다.
실행 클래스는 com.cloud.upgrade.dao.EuropaKmsDdlReplay이며 인수는 epic996-mysql-ddl 하나다.

이 프로그램은 해당 fixture의 cloud DB를 매 반복 초기화한다.
실제 새 S5C SQL의 10개 커밋 경계에 JDBC 오류를 주입하고 Pending journal, 재실행 완료, 반복 실행,
기존 legacy 키 참조와 이전 journal 시간, 6개 KMS 테이블 및 FK 거부를 확인한다.
전체 신규/014895d8f3/S5B DB 업그레이드는 실제 DatabaseCreator와 DatabaseUpgradeChecker로 별도 실행했고
입력 dump·명령·스냅샷은 /tmp/epic996의 fresh/upgrade/s5b 파일에 보존했다.

## Management와 Usage

EuropaManagementStartup.java.fixture는 조립된 client jar와 client/target/lib/*를 사용한다.
인수는 fixture용 DB properties 파일이다. db.cloud.host는 epic996-mysql- 접두어를 검사한다.
server/target/classes를 조립된 jar와 함께 추가하면 module.properties가 중복되므로 최종 검증에서는 조립된 jar만 사용했다.
18996의 loopback API가 준비된 뒤 아래 API fixture를 실행한다.

EuropaUsageStartup.java.fixture는 usage/target/cloud-usage-4.23.0.0-SNAPSHOT.jar와 usage/target/dependencies/*만 사용한다.
실제 UsageServer.start/component lifecycle, 공통 KMSWrappedKeyDao·VolumeDao 주입 및 CLOUD_DB 조회 후 종료한다.
Management 전체 classpath는 Usage 패키지에 없는 관리용 bean을 불필요하게 스캔하므로 섞지 않는다.

## API·키·데이터 검증 순서

1. 업그레이드한 일회용 DB에 Zone 메타데이터가 있어야 한다. 이번 실행은 S5B의 S5B-code-fixture Zone을 보존했다.
2. api-smoke.py는 기본 Database profile을 관리자 권한으로 활성화하고 키 생성·조회·수정·회전을 요청한다.
   메타데이터는 /tmp/epic996/api-key.json에 저장한다.
3. api-volume.py는 회전 async job 완료를 확인하고 암호화 disk offering과 legacy/KMS 지정 Allocated 볼륨을 생성한다.
   실제 호스트·스토리지 작업 없이 API 메타데이터를 준비한다.
4. api-acl.py는 계정·프로젝트·public/private HSM을 만들고 역할별 권한과 비밀 마스킹을 검사한다.
   사용자 생성은 enable=false로 외부 SSO 연동을 끄며 로컬 fixture 비밀번호 길이 정책을 따른다.
   공개 HSM 조회에는 listall=true를 사용한다.
5. EuropaKmsSecret.java.fixture를 client jar·lib/*와 함께 컴파일한다.
   실행 인수는 DB properties, 볼륨 UUID, /tmp/epic996 아래 출력 파일이다.
   실제 DB의 wrapped key를 DatabaseKMSProvider로 풀고 VolumeObject의 키 출력 바이트를 권한 0600 파일에 기록한다.
   raw secret을 콘솔·API·로그에 출력하지 않는다.
6. api-failures.py가 호출할 /tmp/epic996/secret-java-command.json은 위 Java argv 배열이다.
   마지막 두 인수는 이 스크립트가 대상 볼륨과 출력 파일로 교체한다.
7. fixture DB의 kms.rewrap.interval.ms를 5000으로 설정하고 Management를 재시작한 뒤 api-failures.py를 실행한다.
   DB BEFORE UPDATE 오류의 rollback, 비활성 HSM·키 거부, 반복 전환, 동시 회전과 background rewrap을 확인한다.
   전환 전 생성한 4 MiB 비영 payload의 LUKS QCOW2를 전환·rewrap 뒤 복호화하여 전체 바이트를 비교한다.
8. Management/Usage 재시작 후 같은 Java 조회와 QEMU 복호화를 반복한다.
   작업 후 fixture Management와 epic996-mysql-ddl을 중지하며 개발 DB는 그대로 둔다.

MySQL master-key 설정은 배포의 기존 DBEncryptionUtil 계약을 따른다.
Database provider 테스트는 실제 AES-GCM 암복호화를 수행하고 PKCS#11 단위 테스트는 native/HSM 경계를 mock으로 검증한다.
위 결과는 실물 PKCS#11 장치·SharedMountPoint·LINSTOR·KVM I/O 결과가 아니다.
실물 절차는 #1025와 S8 #999에서 최종 병합 SHA 기준으로 실행한다.
