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

# S5B Docker 검증 fixture

이 디렉터리는 #994 / PR #1035의 자동 검증 실행기를 보존한다. 모든 실행은 프로젝트의 `./dev exec`를 통해 `/workspaces/ablestack-cloud`에서 수행한다. 운영·기존 개발 DB에 실행하지 않는다.

## NAS 명령 경계

`python3 developer/history/apache-4.23.0.0-europa-2026-09-10/s5b-fixtures/test_nas_backup.py -v`로 실행한다. 실제 nasbackup.sh의 함수 정의를 로드하고 virsh/qemu-img/udevadm 및 mount 경계를 작업 전용 임시 디렉터리로 대체한다. 호출·XML·실패 뒤 정리와 resume를 검증하며 테스트 후 그 임시 디렉터리만 제거한다.

## DDL 재시도

`EuropaStorageDdlReplay.java.fixture`는 정확히 `epic994-mysql-ddl` 호스트만 허용하고 해당 fixture의 cloud DB를 매 사례마다 재생성한다. MySQL8.0 amd64 tmpfs 컨테이너와 Docker network를 별도로 준비한다. 공개 합성 비밀번호 `epic994-fixture-dummy`는 테스트에만 사용한다.

Java 파일을 Docker `/tmp/epic994/EuropaStorageDdlReplay.java`로 복사하고 현재 schema/utils/API/cluster JAR 및 Maven dependency classpath로 javac를 실행한다. Connector/J8.4.0을 포함하여 `com.cloud.upgrade.dao.EuropaStorageDdlReplay epic994-mysql-ddl`을 실행한다. 현행 schema-europa-4.23-s5b.sql과 IDEMPOTENT_ADD_COLUMN procedure를 사용한다. 운영 주소로 인수를 일반화하지 않는다.

전체 설치 검증에는 기존014의449개 table/view dump와 S5A454개 dump를 전용 fixture에 복제하고 실제 DatabaseCreator를 실행했다. 신규 설치는 create-schema/create-schema-premium/templates/developer-prefill부터 실행한다. manifest의 Implementation-Version이 없는 classes classpath만 사용하면 checker가 upgrade를 수행하지 않을 수 있으므로 실제 journal·스키마와 JAR manifest를 확인한다. 덤프/전용 properties와 인증 자료는 커밋하지 않는다.

## 관리 서버

`EuropaManagementStartup.java.fixture`는 `epic994-mysql-` 접두사 DB와 loopback18994로 한정한다. Java 파일은 `/tmp`로 복사하여 조립된 client JAR로 컴파일한다. 전용 home/tmp/SSH 경로, SystemVM metadata, IP 도구와 DB properties를 준비한다. 실제 개발 db.properties.override를 복사하거나 출력하지 않는다.

`check-storage-api.py`는 신규 fixture의 기본 admin과 `/tmp/epic994/api-zone.json`에 저장된 비활성 합성 Zone을 사용한다. fixture에서만 backup.framework.enabled=true 및 zone의 backup.framework.provider.plugin=nas,kboss를 설정하고 관리 서버를 재시작한다. KBOSS offering CRUD, 기존 provider 조회, 외부 retention plan 거부 및 backup/jobs 목록을 검증한다. sessionkey와 API 전체 응답은 기록하지 않는다.

관리 서버 시험과 DDL fixture는 같은 전용 DB를 재사용했으므로 동시에 실행하지 않는다. DDL replay가 cloud DB를 재생성하기 전에 관리 서버를 정지한다. 개발 DB 및 Docker 영속 볼륨에는 이 초기화 경로를 사용하지 않는다.

## 이미지 전송

테스트는 Python3.10에서 `scripts/vm/hypervisor/kvm/imageserver/tests`를 실행한다. Rocky9.8 QEMU10.1 및 libnbd1.20.3 RPM은 Docker `/tmp/epic994/native`에 추출했고 libnbd의 generated Python binding을 현재 Python3.10 ABI로 빌드했다. 호스트에는 설치하지 않았다. PATH/LD_LIBRARY_PATH/PYTHONPATH는 그 fixture 경로로 한정한다.

처음 skip된4개에는 16MiB qcow2를 만들어 서로 다른 offset에 알려진 데이터 패턴을 기록하고 IMAGESERVER_TEST_QCOW2와 IMAGESERVER_STRESS_TEST_QCOW_DIR로 전달했다. 작은 합성 파일의 결과를 운영 크기·성능·실물 Veeam 검증으로 해석하지 않는다.
