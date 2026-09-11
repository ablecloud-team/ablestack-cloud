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

# S6 네트워크 소프트웨어 검증 fixture

프로젝트의 ./dev exec를 통해 Docker /workspaces/ablestack-cloud 안에서 실행한다.
Rocky 9.8 linux/amd64, JDK 17, Maven 3.9.10, Python 3.10, MySQL 8을 사용한다.
일회용 epic997-mysql-ddl과 loopback Management 18997만 대상으로 하며 기존 개발 DB·볼륨은 사용하지 않는다.
로그·명령·DB 스냅샷은 Docker /tmp/epic997에 보존했다.

## 빌드와 단위 검증

- mvn -B -ntp -P developer,systemvm -Dsimulator -Dnoredist -DskipTests install
- 동일 profile에서 test로 전체 회귀, framework/extensions의 NetworkExtensionScriptTest로 실제 프로세스/JSON 임시 파일 계약을 검증한다.
- UI 디렉터리에서 npm run lint -- --no-fix 및 npm run test:unit -- --runInBand를 실행한다.
- Python 3.10 venv에 mock/netaddr/requests/jinja2/psutil을 설치하고 systemvm/test의 TestCsDhcp·TestCsAddress·TestCsConfig·TestCsNetfilter를 실행한다.
  로깅은 /tmp/epic997의 파일로 초기화한다. 전체 TestCs 비교에는 TestCsRoute 기존 실패 2개가 있으므로 전체 PASS로 보고하지 않는다.

## DB 중단·복구

EuropaNetworkDdlReplay.java.fixture를 /tmp/epic997/EuropaNetworkDdlReplay.java로 복사한다.
engine/schema jar·의존성과 engine/schema/src/main/resources를 classpath에 두고 javac -d로 임시 디렉터리에 컴파일한다.
com.cloud.upgrade.dao.EuropaNetworkDdlReplay epic997-mysql-ddl로 실행한다.
이 프로그램은 지정한 일회용 cloud DB를 매 반복 초기화하므로 API fixture보다 먼저 실행한다.
새 SQL 24개 커밋 경계의 Pending→재시도→Complete→재실행을 확인하고 기존 규칙·네트워크·provider·이전 journal 값을 대조한다.

전체 신규 설치는 DatabaseCreator, 업그레이드는 DatabaseUpgradeChecker를 조립된 client jar로 실행했다.
014895d8f3 baseline dump와 S5C 완료 dump를 각각 별도 초기화해 검증했다.
전체 입력 dump·명령·3경로 before/after/repeat JSON은 /tmp/epic997에 있다.
DB 비밀번호 epic997-fixture-dummy는 일회용 fixture 전용 값이다.

## 관리 API와 PowerDNS HTTP 계약

EuropaManagementStartup.java.fixture는 조립된 client jar와 client/target/lib/*를 사용한다.
fixture DB properties 경로를 인수로 받으며 db.cloud.host의 epic997-mysql- 접두어를 검사한다.
사용자 홈의 .ssh와 engine/schema/dist 메타데이터 경로, iproute 실행 파일 및 DB fixture의 cloud DB 사용자/구성 키를 준비한다.
이번 실행에는 /tmp/epic997/management-java-command.json과 restart-management.py, runtime-root의 기존 컨테이너 도구를 사용했다.

powerdns-fixture.py를 같은 개발 컨테이너에서 시작한다. 호스트 포트는 공개하지 않는다.
컨테이너 내부 18953의 모의 서버가 X-API-Key, JSON body, DNS zone/record CRUD HTTP 계약을 처리한다.
이 서버는 PowerDNS 제품이나 실제 DNS 서비스가 아니며 외부 DNS 질의·전파를 검증하지 않는다.
Management가 준비되면 api-dns.py를 실행한다. Python 표준 라이브러리만 사용한다.
관리자/일반 사용자 요청은 Europa의 단일 로그인 정책을 유지하면서 순서대로 재로그인한다.
삭제 등 async API는 job 완료를 기다리며 테넌트 존은 example.test 전체 이름으로 요청한다.
스크립트의 admin/password, S6!test2026 및 s6-provider-dummy는 일회용 fixture 값이다.
검증 결과는 api-dns-result.json, HTTP 요청 계약은 provider-http.log에 남는다.

실물 PowerDNS·VR·KVM·CKS·외부 provider는 #1025에 NOT_RUN으로 인수한다.
