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

# 관리 서버 암호화 라이브러리 패키징 검증

## 문제와 변경

Netris SDK 1.0.0은 구버전 Bouncy Castle 클래스를 내부에 포함한다. Maven 의존성에서 bcprov를 제외해도 SDK 내부 클래스까지 제외되지는 않으므로, 관리 서버 통합 JAR이 먼저 로드되면 정식 1.83 라이브러리와 섞인다. 실제 RPM 설치 후 Root CA 초기화에서 `NoSuchFieldError: xmss_SHAKE128_512ph`가 발생했고, 별도 재현에서는 `id_ml_dsa_44` 오류도 확인했다.

관리 서버 Shade 설정에서 SDK 내부 암호화 클래스를 제외하고, bcutil도 bcprov/bcpkix/bctls와 함께 버전이 일치하는 별도 JAR로 배포한다. 서버 실행 환경의 CLASSPATH 순서를 변경하지 않고 해결하는 것이 목표다.

## 재현 가능한 산출물 테스트

WSL ext4 작업 트리에서 관리 서버 모듈과 필요한 의존 모듈을 빌드한 뒤, Java 17 이상으로 실행한다.

```bash
# BUILT_MANAGEMENT_JAR: client/target/cloud-client-ui-<version>.jar
# RUNTIME_LIB: client/target/lib
java -cp "$BUILT_MANAGEMENT_JAR:$RUNTIME_LIB/*" tools/packaging/ManagementCryptoSmoke.java
java -cp "$RUNTIME_LIB/*:$BUILT_MANAGEMENT_JAR" tools/packaging/ManagementCryptoSmoke.java
```

검사는 네 가지 Bouncy Castle 구성 요소의 실제 클래스 출처를 확인하고, CloudStack CertUtils로 RSA 키·CA 인증서·클라이언트 인증서를 생성하여 유효기간과 서명을 검증한다. 개인키와 인증서는 메모리에만 생성되며 테스트 서버 CA를 변경하지 않는다.

## 배포 검증 기준

- 새 Management RPM에서 관리 서버 통합 JAR에 `org/bouncycastle/` 클래스가 없고, `lib/`에 같은 버전의 bcprov/bcpkix/bcutil/bctls가 있어야 한다.
- 임시 Bouncy Castle 우선 로딩 CLASSPATH 설정을 제거한 기본 실행 설정에서 `mold`가 정상 기동해야 한다.
- `/client/` HTTP 200, UI 로그인, Routing 호스트 3대 Up을 확인한다.
- 기존 VM의 라이브 마이그레이션 왕복, CD-ROM 구성 보존, QGA 응답을 확인한다.

전체 RPM 빌드는 `-DskipTests`로 패키징되므로 전체 단위 테스트 통과를 의미하지 않는다. 위 산출물 테스트와 배포 후 동작 검증을 별도로 수행한다.
