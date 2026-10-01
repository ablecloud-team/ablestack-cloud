<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# Wall 가용성 및 TLS 설정

Wall은 Mold와 독립된 서비스입니다. 가용 상태가 Ready인 경우에만 알림 및 사일런스 조회를 허용합니다.
경량 상태 확인은 health, 사용자 인증, 알림 읽기 권한을 점검하며 알림 데이터를 읽지 않습니다.

## 사설망 HTTPS

글로벌 설정 **wall.tls.verify**의 기본값은 **false**입니다.

- false: Wall HTTPS의 인증서 체인과 호스트명/IP 일치를 검증하지 않습니다. 자체 서명 인증서와 주소 불일치 인증서를 사용하는 사설 Wall 환경을 지원합니다. HTTPS 암호화는 유지되지만 서버 신원 확인은 생략됩니다.
- true: JVM 신뢰 저장소를 이용해 인증서 체인과 접속 주소를 검증합니다. 사설 CA를 신뢰 저장소에 등록하고 실제 접속 IP 또는 DNS 이름을 인증서 SAN에 포함하면 사설망에서도 사용할 수 있습니다.
- 사설 IP라는 이유만으로 자동 판단하지 않습니다. 설정값 하나를 가용성 확인과 모든 Wall API 호출에 일관되게 적용합니다.
- Mold의 다른 HTTP 클라이언트, JVM 기본 SSLContext, 전역 hostname 검증 설정은 변경하지 않습니다. 리다이렉트는 따라가지 않습니다.
- 인증 토큰과 알림 읽기 권한 검사는 두 모드에서 모두 유지됩니다. TLS 오류 후 자동으로 검증을 끄는 fallback은 없습니다.

관리자 글로벌 설정 화면 또는 updateConfiguration API에서 설정할 수 있습니다.
설정은 동적이며 캐시에 전파된 이후 다음 Wall 요청에 적용됩니다. 기존 진행 중 요청은 완료될 수 있습니다.
가용 상태 캐시는 TLS 설정 변경을 감지하면 TTL을 기다리지 않고 다시 점검합니다.
이미 DB에 저장된 명시적 설정값은 업그레이드 시 덮어쓰지 않습니다.

## 검증 범위

WallTlsTest는 실행 시 임시 인증서를 생성한 HTTPS 서버를 사용합니다.
접속은 127.0.0.1이며 인증서 SAN은 wall.invalid로 설정하여 미신뢰와 주소 불일치를 함께 재현합니다.
기본 허용, 엄격 모드 거부, 런타임 설정 전환, 일반 HttpClient의 검증 유지, 인증 실패 차단을 검사합니다.
임시 키와 인증서는 테스트 디렉터리에만 생성하고 저장소에 포함하지 않습니다.
