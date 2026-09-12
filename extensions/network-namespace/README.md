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

# Network Namespace 확장

## 출처

Apache CloudStack의 별도 공식 참조 구현을 포함합니다.

- 저장소: https://github.com/apache/cloudstack-extensions
- 브랜치: `network-namespace`
- 고정 커밋: `e0af7a457768e7acb4f0e64d0a9d6370479095b1`
- 원본 경로: `Network-Namespace/`
- Cloud 연동: https://github.com/apache/cloudstack/pull/13032
- 라이선스: Apache-2.0. 원본 사용 설명서는 [UPSTREAM.md](UPSTREAM.md)에 보존했습니다.

`network-namespace-wrapper.sh`는 파일 끝 빈 줄만 정리했고 원본 동작은 유지합니다. 프록시는 JSON 파서를
사용하도록 수정하고 SSH 호스트 키 검증을 활성화했으며, 명령·시간 제한·포트·사용자·경로·ID 입력을 검사합니다.

## 설치 위치

관리 RPM은 두 스크립트를 `/etc/cloudstack/extensions/network-namespace/`에 포함합니다.
관리 서비스의 진입점은 다음과 같습니다.

```text
/usr/share/cloudstack-management/extensions/network-namespace/network-namespace.sh
```

실제로 네트워크를 처리할 Linux 장비에는 `network-namespace-wrapper.sh`를
`/etc/cloudstack/extensions/network-namespace/network-namespace-wrapper.sh`에
실행 권한 0755로 별도 설치해야 합니다. 관리 RPM 설치가 원격 호스트까지 배포하지는 않습니다.

관리 서버에는 Bash, Python 3, OpenSSH가 필요합니다. 비밀번호 인증을 사용할 때만
`sshpass`가 추가로 필요합니다. 실행 계정 `cloud`의 `~/.ssh/known_hosts`에
검증된 원격 장비 키를 등록해야 합니다. 호스트 키 검증을 비활성화하지 마십시오.
원격 장비의 서비스별 의존성(iproute, iptables, dnsmasq, haproxy, httpd 등)과
브리지·트래픽 인터페이스 설정은 원본 사용 설명서를 따릅니다.

## 등록

NetworkOrchestrator 백엔드가 포함된 Europa에서 다음 Cloud API 매개변수로 등록합니다.

```text
command=createExtension
name=network-namespace
type=NetworkOrchestrator
path=network-namespace/network-namespace.sh
details[0].network.services=SourceNat,StaticNat,PortForwarding,Firewall,Gateway,Dhcp,Dns,UserData,Lb,NetworkACL
```

등록 후 `listExtensions`의 `state=Enabled`, `type=NetworkOrchestrator`,
`pathready=true`를 확인합니다. 이는 어댑터 등록과 파일 접근 확인이며,
실제 VM 네트워크 기능의 성공을 뜻하지 않습니다. 실제 사용에는 별도 물리 네트워크
등록, 제공 서비스·capability 설정, SSH 인증, 네트워크 오퍼링 구성이 필요합니다.
기존 물리 네트워크의 provider를 임의로 교체하지 마십시오.

## 검사

Linux 파일시스템에서 다음 명령으로 패키지 구성 및 프록시 계약을 검사합니다.

```bash
python3 -m unittest discover -s tools/build/tests -p test_extensions_payload.py -v
python3 -m unittest discover -s tools/build/tests -p test_network_namespace.py -v
```

프록시 단위 테스트는 SSH를 모의 처리합니다. 실제 원격 장비나 네트워크를 변경하지 않습니다.
