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

# S4: 동일 버전 Europa DB·자원 예약·Usage/Quota 검증

부모 Epic #987, 작업 #992. 구현 PR #1016. 구현 기준 `5d12d16087121a48af6724e2c37097dac833458e`, 사용자 지정 DB 출발점 `014895d8f3dc2f062f379b51ee62d36a0adae88a`.

## 결과와 소스 추적

구현 소스 `551a19f7caecb817c48eca0e9f3754d433aac241`, 공식 PR 검사 checkout `c25e8bcabf12999afb676a5d05ed791a4e669b78`, 구현 병합 `96dffaa30a2cce4abd3a32824a4bda408831ffe0`. 제품 트리 `6500e0ccffb240de61af38f4c24f2f63619ced5e`. 정상 merge를 사용하며 관리자 우회나 CI 비활성화는 사용하지 않는다.

직접 S4 원본38개와 S2 연관2개를 판정했다. 40개는 Applied6, Adapted19, Already Satisfied14, Excluded1이다. 원본별 최초 적용 SHA·최종 검증 SHA·근거는 `s4-review.tsv`에 있다. 299개 전체는 Applied39 / Adapted49 / Already Satisfied37 / Excluded28 / Pending146이다. 제외1개 `4a691f43df62`는 이전 예약 구현을 기준으로 한 포맷 변경이다. 잘못 들어갈 수 있는 과거 문맥을 복원하고 현행 동작을 유지했으며 기능 변경을 제외한 것이 아니다.

범위 이전 Apache #9590 `449d3c7cb1df75867a3e1a9c3648fc3675c6b39e`의 Quota credit 목록 응답·API 등록이 Europa에 빠져 있었다. Quota UI의 실제 의존성으로 보완했다. 고정299개 inventory는 늘리지 않고 보충 의존성에 별도 기록한다. 원본 부모 SHA/날짜/evidence/pretriage는 유지한다.

## 같은 버전 DB 업그레이드

`cloud.ablestack_schema_migration`이 명명된 단계의 Pending/Complete를 기록한다. 기존4.23 DB에는 별도 S4 migration을 적용하고 기존 version 행을 삭제하거나 초기 버전으로 되돌리지 않는다. 신규 설치의 Before/After 단계와 일반 Apache upgrade 체인은 별도로 실행한다. 새 설치의 일반 체인이 version Complete를 기록했더라도 후속 Europa 단계가 실패하면 예외로 시작을 차단하고 Pending 단계부터 재시도한다.

S4 단계는 Quota tariff/usage 매핑과 권한, Usage 중복 이벤트 인덱스 제거, 볼륨 집계/VM별 부착 기간의 복합 unique index, S3 인증 스키마, 기준 DB에서 실패한 guest OS mapping·스토리지 runtime 필드·DR 인덱스를 적용한다. OAuth 도메인 FK의 CASCADE 규칙도 신규·기존 경로를 맞췄다. 외래 키/unique index 교체는 가능한 경우 단일 ALTER로 실행하여 검증 실패 시 기존 제약을 보존한다.

SQL 실행기는 따옴표·주석·프로시저 DELIMITER·마지막 SQL 문장을 처리하고 오류를 즉시 전파한다. 실제 index 삭제 helper가 예외를 삼키던 경로도 수정했다. MySQL8.0에서 지원하지 않는 조건부 ALTER, 자기 테이블 서브쿼리 UPDATE, `external_id` 오타, runtime 테이블 생성 순서를 수정했다. 프로시저/이벤트37개에 명시적 DELIMITER를 넣었다. 시뮬레이터의 template111 INSERT 누락은 Apache와 동일하게 복원했다.

### 실제 JDBC 및 데이터 검증

Rocky9.8 linux/amd64, JDK17, Maven3.9.10, Python3.10, MySQL8.0.46. 개발 DB와 볼륨을 사용하지 않고 `com.ablecloud.task=epic992` 표식의 전용 tmpfs MySQL 컨테이너3개를 사용했다. 기준014895d8f3의 실제 JAR·Connector8.0.33로 전체 cloud/cloud_usage DB를 생성하고, 전체 dump 복제본에는 후보 JAR·Connector8.4.0으로 `DatabaseCreator`→`DatabaseUpgradeChecker`→`ScriptRunner`를 실행했다.

- 기준: SQL99오류를 재현했지만 종료0과 version Complete를 기록하는 기존 문제 확인.
- 후보 신규 설치: SQL오류0, 종료0, legacy/S4 단계10개 Complete. 시뮬레이터도 JDBC 초기화 종료0, template100/111 및 참조 확인.
- 기존 전체 복제본:449개 table/view에서452개로 변경. 추가 객체는 migration journal, quota_tariff_usage, quota_usage_view다.
- 기준 원본11,271행을 기존 열 기준으로 비교했다. 권한 추가에 따른 기존60행의 sort_order 조정 외에는 원본 행의 값을 보존했다. version51행은 완전히 동일하다. API key/secret, OAuth provider secret, Quota credit/usage, 사용자 설정과 명시적 quotaCreditsList DENY fixture를 포함한다.
- 신규·업그레이드 결과의 열 계약5,717개, 인덱스 항목1,698개, 외래 키525개가 동일하다. OAuth 열의 표시 순서·COMMENT와 AUTO_INCREMENT 카운터는 계약 비교에서 제외한다.
- ALTER 권한을 회수한 실제 실패 주입: 종료1, S4 Pending, 원래 version51행과 기존 Usage index 보존. 권한 복구 후 서로 다른 JVM2개를 동시에 시작하여 두 프로세스 모두 정상 종료 및 단일 Complete 확인.
- 이후2회 반복 시작의 전체 데이터·DDL·checkpoint timestamp snapshot이 동일하다.
- usage_volume 동일 volume/date의 누적 행과 서로 다른 VM별 행 공존, 같은 VM의 중복 행 거부, usage_vm_instance 중복 이벤트 허용을 SQL로 검증했다.

이 결과는 전체 구조를 가진 지정 기준 DB의 합성 데이터 fixture 검증이다. 운영 데이터 dump를 제공받아 검증한 것은 아니다. 추가로 최종 조립 JAR의 실제 관리 서버를 시작하고 두 번 재시작했다. 매번 HTTP 로그인과 quotaCreditsList를 호출해 credit123.4567 및 Europa 통화(₩), 계정 scope를 검증했다. version51행·checkpoint timestamp·기존 API/OAuth secret 행·명시적 DENY·credit·Usage 복합 인덱스가 모두 유지됐다. 관리 서버가 정상적으로 초기화하는 별도 설정·SSH 키 등의 변경은 전체 DB 무변경으로 주장하지 않는다.

관리 서버 fixture에는 SystemVM 메타데이터, 임시 SSH 디렉터리와 Rocky iproute 라이브러리를 준비했다. 개발 DB를 연결하지 않고 loopback HTTP만 열었다. 실제 네트워크가 없는 기준 DB의 LB health-check 조회, systemd 없는 컨테이너의 Usage 서비스 제어, 클러스터 주소/인증서 및 설치 경로 관련 백그라운드 경고는 남는다. 따라서 운영 환경 전체 정상화나 클러스터/TLS 검증의 통과를 뜻하지 않는다. S8에서 실제 운영 규모 데이터·클러스터·업그레이드 시간·백업 복구를 검증한다.

## 자원 할당과 예약

통일된 allocator 호출에서도 Europa의 스토리지/이동 가능 호스트 후보 목록을 유지한다. 명시적 빈 목록을 전체 호스트로 바꾸지 않고, tag rule이 후보 밖 호스트를 다시 추가하지 못하게 하며 UEFI/TPM 필터를 마지막에 적용한다.

VM/CPU/RAM/볼륨/스토리지·프로젝트·IP·VPC 예약을 목록 형태로 해제하고, offering 변경 시 기존/신규 tag 및 증감량을 고려한다. GPU·KVDO·fast-clone·명시적 placement·S3 VM import 권한과 typed failure 계약을 유지한다. 예약 생성 도중 예외가 나면 이미 만든 행을 회수한다. 해제 중 한 종류/행의 DB 오류가 나도 나머지 예약의 해제를 시도하고 실패한 항목은 재시도할 수 있다.

실제 ReservationDaoImpl과 MySQL GlobalLock을 사용한8개 동시 요청에서 1 CPU fixture 한도에 대해 승인1/거부7, 종료 후 예약0을 확인했다. 한도 정책만 시험용 ResourceLimitService로 구성했고 실제 VM 배포/하이퍼바이저를 실행한 시험은 아니다. 별도 단위 테스트는 한도 초과·persist 실패·lock timeout·tag 교체·부분 해제 실패·후보/UEFI/TPM 경계를 검증한다.

## Usage/Quota와 API/UI

실제 Usage Spring context는 필수 설정 누락 시 시작 오류를 반환하고, 정상 설정과 quota.enable.service=true에서는 정상 시작한다. KMS 빈을 요구하지 않는 현행 구성임을 실제로 확인했다.

실제 QuotaManager/DAO/MySQL 집계에서 IP에 중첩 요금2개,1GiB 볼륨과 같은 볼륨의 VM 부착 기간을 입력했다. 결과는 IP3 + volume24 =27, tariff mapping3개, 부착 기간 추가 과금0, credit100에서 잔액73이다. 같은 집계를 다시 실행해 중복 과금/매핑이 없음을 확인했다. 원래 Usage job이 복사하는 account 테이블과 credit API가 함께 생성하는 quota_balance를 fixture에 준비했다.

Quota UI의 계정/프로젝트 선택·크레딧·잔액·사용량·CSV/차트 화면과 API 응답을 함께 반영했다. 누락된 소유자 선택으로 호출자에게 잘못 크레딧을 주지 않도록 제출을 차단하고, 응답 소수 정밀도·프로젝트 scope·API 오류 후 재시도를 시험했다. 새36개 한국어 문구를 추가하고 공유 UI의 Europa 동작을 보존했다. Node14/npm6으로 lockfileVersion1을 재생성하고 npm ci를 검증했다.

## 검증 결과

로컬 Java는 현재 실행된 XML만 수집하여 957 suites / 11,838 tests / failures0 / errors0 / skipped14를 확인했다. 중간 실패를 수정한 최종 재실행 결과를 사용하고, 제거된 모듈의 오래된 XML과 중복 실행은 합산하지 않았다. 최종 소스 전체160개 모듈의 developer/systemvm install도 통과했다. UI는 Node14/npm6으로 29 suites / 348 tests가 통과했다. 세부 결과는 [로컬 Java](s4-local-tests.tsv), [DB·runtime](s4-db-results.tsv)에 기록한다.
공식 [Java Build](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34486486247)는171개 reactor,1,073회 suite 실행,12,611 tests / failures0 / errors0 / skipped17로 통과했다. 로컬 결과와 별도 집계하며 합산하지 않는다. [UI Build](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34486486362)는 production build·lint·29 suites/348 tests 통과, [License Check](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34486486204)는 Unknown Licenses0으로 통과했다.

[전체 Lint](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34486486235)는 기존13개 오류 분류로 실패했다. S3의14개 분류 중 SQL 라이선스 문제를 해결했고, 최종 변경 경로의 새 진단은0개다. CI 색상 escape를 제거하고 전체 파일을 단순 나열하는 identity hook을 제외한 실제 진단 구간을 대조했다. Simulator/Coverage/Sonar는 기존 조건으로 skipped이며 PASS로 계산하지 않는다. 원본 소스의 [Actions 기록](s4-ci-results.tsv)과 [공식 Java 상세](s4-ci-tests.tsv)를 참조한다.
[Rocky9.8 공식 패키징](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34486486328)과 [Rocky9.7 호환 패키징](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34486486349)이 통과했다. Rocky9.8 [산출물10157331479](https://github.com/ablecloud-team/ablestack-cloud/actions/runs/34486486328/artifacts/10157331479)의 RPM9개는 SHA256·패키지4.23.0.0/SNAPSHOT/x86_64·환경 메타데이터를 확인했다. 산출물의 source_sha는 위 공식 checkout과 같으며 그 tree는 구현 소스와 동일하다. 번들 BC의 RSA/X.509, OkHttp5.1.0의 MinIO/InfluxDB smoke도 공식 패키징에서 통과했다. 이는 mock endpoint를 사용하는 패키지 의존성 검증이며 실제 운영 인프라 시험이 아니다. 상세 값은 [산출물 및 체크섬](s4-artifact-results.tsv)에 기록한다.

재현용 테스트: ScriptRunnerTest, EuropaMigrationCheckpointTest, DatabaseUpgradeCheckerTest, CheckedReservationFailureTest, FirstFitAllocatorTest, QuotaCreditsListCmdTest, ui/tests/unit/views/plugins/QuotaCredit.spec.js. 실제 runtime fixture 실행기: tools/build/EuropaReservationConcurrencySmoke.java, tools/build/EuropaUsageRuntimeSmoke.java. 후자는 aggregate 인수를 주면 실제 QuotaManager를 호출하며, 성공 반환만으로 판정하지 않고 DB 합계/잔액/재실행 결과를 별도로 검사했다.

## 다음 단계와 운영 조건

- S5A #993: Guest OS rules·VM import/VDDK·live scaling·schedule 변경을 기존 예약 handles와 함께 통합한다. 이전 CPU/RAM check API를 다시 도입하거나 GPU/TPM/fast-clone guard를 덮어쓰지 않는다.
- S5B #994 및 #978: 다중 image-store 업로드와 provider/백업 기능의 실제 전송·실패·정리 검증은 해당 이슈에서 수행한다. 이번 byte/unlimited 및 예약 변경이 #978 전체 해결을 뜻하지 않는다.
- S5C #996: KMS d2c8aa7dff73과 Usage 빈 이동600201a46bf9를 함께 반영한다. 지금 존재하지 않는 KMS DAO 빈만 먼저 추가하지 않는다. 이번 Usage 시작 성공을 KMS 검증으로 표시하지 않는다.
- S6 #997: soft-delete API가 도입되면 Quota의 LB/port-forward removed metadata를 연결한다. 현재 모델에는 필드가 없어 null을 사용한다. Guest OS rule의 JS interpreter 확장은 S5A의 해당 원본과 함께 통합한다.
- S7 #998: Quota 한국어·브랜딩·CSV/차트 실제 브라우저 검수와 최종 릴리즈 문구를 확인한다.
- 공유 merge ce52b9dae0ce/d5101b0c905a/b3b9caddc191/c7e2c748f746는 S4 DB/예약 부분을 검토했더라도 남은 side commit과 기능이 있으므로 Pending을 유지한다.
- 이후 같은4.23에서 스키마가 바뀌는 작업은 별도의 명명된 checkpoint를 추가하고 실제 새 설치/014복제/부분 실패/재시작을 다시 검증해야 한다. 완료된 S4 v1 SQL만 바꾸면 기존 설치에서 재실행되지 않는다. view 변경도 새 단계에서 적용한다.
- MySQL DDL은 transaction rollback만으로 전체 원복되지 않는다. 실제 업그레이드 전 검증된 전체 DB 백업을 확보하고, 운영 rollback은 코드/DB 백업의 일치하는 조합으로 복원한다. 부분 migration Pending을 임의 Complete로 바꾸지 않는다.
- FTCTL/DR progress는 host 소유 상태를 유지하며 DB에 mirror를 추가하지 않았다. S4는 최종 Europa 릴리즈 승인이나 운영 배포 완료를 뜻하지 않는다.
