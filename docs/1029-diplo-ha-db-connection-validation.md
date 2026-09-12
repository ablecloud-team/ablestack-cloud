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

# Diplo HA 작업 DB 연결 반환 수정 및 검증

## 문제와 코드 설계

이슈: [#1029](https://github.com/ablecloud-team/ablestack-cloud/issues/1029)

기준은 upstream `ablestack-diplo`의 `678835c2008c47fc32c151196a0dd1a1cc446828`이며,
브랜치는 `codex/fix-1029-diplo-ha-db-context`이다. Europa의 `c558c69f021673c013e05213af940e88c0b2226c`
중 공통 연결 반환 수정만 이식했다. Diplo의 단일 `Long id` GuestOS DAO 인터페이스를 유지했다.

`BaseHATask`는 ManagedContextRunnable 밖에서 실행된다. 외부 executor에서 결과 처리까지,
내부 executor에서 performAction 종료까지 각각 `TransactionLegacy.open`과 try-with-resources로
DB 문맥을 소유한다. 내부 작업이 timeout 인터럽트를 무시해도 외부 스레드가 내부 연결을
먼저 닫지 않는다. 내부 소유 스레드가 실제로 종료할 때 반환한다. HA timeout 값과 정책,
fencing, 풀 크기, 공통 TransactionLegacy 구현은 바꾸지 않았다.

`GuestOSDaoImpl.findDoubleNames`는 Connection/PreparedStatement/ResultSet을 모두
try-with-resources로 반환하고, 연결 획득 시 SQLException 원인을 보존한다.

## WSL 검증

- WSL rocky ext4 전용 worktree, Java 11, Maven 3.6.3.
- 수정 전: HA 테스트 4건 실패; DAO 테스트 4건 실패와 연결 획득 오류 1건.
- 최초 Java 17 실행의 cglib 접근 제한 오류는 누수 재현 결과에 포함하지 않았다.
- 수정 후 신규 회귀 10건 성공: 정상/작업 예외/결과 예외/timeout/인터럽트 무시 후 소유 스레드 반환,
  JDBC 정상 반환 및 각 획득·prepare·query·read 실패 지점.
- 기존 HA 관리자 17건, KVM 펜싱 6건, HA DAO 2건 포함 **총 35건 성공**, 실패·오류·skip 0.
- `server,engine/schema`와 필요한 의존 모듈만 package. 전체 Cloud 빌드는 실행하지 않았다.

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -pl server,engine/schema -am \
  -Dtest=BaseHATaskConnectionTest,GuestOSDaoConnectionTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false package
mvn -B -pl server,engine/schema -am \
  -Dtest=BaseHATaskConnectionTest,GuestOSDaoConnectionTest,HighAvailabilityManagerImplTest,KVMFencerTest,HighAvailabilityDaoImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false test
```

## 실물 배포와 연결 반환 검증 (2026-09-12 KST)

- 대상: 사용자 지정 `10.10.1.10`, SSH 22, `mold.service`, Diplo `4.21.0.0-Mold.Diplo-202609101114`.
- UI: `http://10.10.1.10:8080/client`, `ABLESTACK v4.7.3-20260910`.
- 설치 통합 JAR: `/usr/share/cloudstack-management/lib/cloudstack-4.21.0.0-Mold.Diplo-202609101114.jar`.
- 전체 백업: `/root/issue1029-validation/backup-20260912-152611/` 아래 같은 JAR 이름.
- 교체: `BaseHATask.class`, `BaseHATask$1.class`, `GuestOSDaoImpl.class`만 변경.
  나머지 모든 ZIP entry 내용 동일성을 비교했고 공개 ABI 동일 및 class major 55를 확인했다.
- 배포 JAR SHA256: `8457c417384b0219e54603d84d4238d7300fe5c3e147e4d40294ff031efb409c`.
- 관리 서비스 재기동 후 설치 클래스 해시가 빌드 결과와 일치했다. UI 정적 파일은 수정하지 않았다.
- 설치 JAR와 실제 DB를 사용하는 별도 JVM 읽기 전용 스모크:
  수정 전 HA `missing DB context`, DAO 첫 반복에서 `leaked connection` 실패.
  수정 후 HA 100회(정상/작업 예외/결과 예외/timeout), DAO 100회 성공.
  각 반복 후 해당 시험 JVM의 Hikari Active 연결이 **0**으로 복귀했다.
- 실행 소스: `tools/diagnostics/HaDbSmoke.java`. HA 제공자는 설정 timeout만 반환하는 proxy이며,
  HA 작업은 `SELECT 1`, DAO는 중복 OS 이름 SELECT만 실행한다. VM/호스트 상태를 변경하지 않는다.
- 실제 관리 JVM과 위 시험 JVM은 서로 다른 풀이다. 시험 JVM Active=0을 관리 JVM 측정으로 해석하면 안 된다.

설치 JAR와 의존성을 classpath에 두고 Java 11로 probe를 컴파일한 후 대상에서 실행한다.
대상의 기존 `/etc/cloudstack/management` DB 설정을 읽으며 자격증명은 소스나 명령에 넣지 않는다.

```bash
java --add-opens=java.base/java.lang=ALL-UNNAMED \
  -cp '/root/issue1029-validation:/usr/share/cloudstack-management/lib/*:/etc/cloudstack/management:/usr/share/cloudstack-mysql-ha/lib/*' \
  HaDbSmoke ha
# 같은 classpath로 HaDbSmoke dao 실행
```

## UI 및 운영 상태 확인

배포 후 브라우저에서 재로그인, 호스트 목록 3대, ablecube1 상세 및 HA 상태,
해당 호스트의 VM 목록 6대, Mold-Theme-UI-TEST VM 상세(Running, HA=true)를 확인했다.
DB 대조에서도 배포 전후 전체 VM 상태가 Running 49 / Stopped 8로 동일하며 호스트 3대가 Up이다.
WEB-INF 보존과 `/client/` HTTP 200, 관리 서비스 정상 시작을 확인한다.

세 호스트는 배포 전후 모두 `Ineligible`이다. 따라서 실제 Eligible 호스트 장애·복구·fencing의
자동 HA 전환과 장시간 부하 누수 검증은 수행하지 않았다. 이 결과는 연결 반환 결함의
회귀·실제 DB 반복 검증 및 UI 조회 회귀에 해당한다. DR 기능 전체 검증으로 확대 해석하지 않는다.

## 롤백

관리 작업이 진행 중이지 않은 시점을 확인한 뒤 mold를 중지하고 위 백업 JAR를 원래 위치에
복원해 mold를 시작한다. `/client/` 200, 호스트/VM 상태, management-server.err를 확인한다.
DB 스키마 및 UI 파일 변경은 없어 별도 DB/UI 롤백이 필요하지 않다.
