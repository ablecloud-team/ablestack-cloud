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

# Europa Network 확장 배포 완료 보고

## 원인과 정정

기본 확장 3종은 Rocky RPM spec에서 확장 파일을 누락한 문제였다.
Network는 여기에 두 가지 별도 조건이 있었다.

1. Network Namespace 참조 어댑터는 Cloud 저장소가 아닌
   [공식 cloudstack-extensions 저장소](https://github.com/apache/cloudstack-extensions/tree/e0af7a457768e7acb4f0e64d0a9d6370479095b1/Network-Namespace)에 있었다.
   이전의 "어댑터가 없어 배포할 수 없음"이라는 판단을 정정한다.
2. Europa 최신 소스에는 Network 연동이 있지만, 네 테스트 서버의 설치 JAR에는
   `NetworkExtensionElement`와 관련 스키마가 없었다. 스크립트만 추가해서는 등록할 수 없었다.

## 소스 수정

- Apache 참조 구현 `e0af7a457768e7acb4f0e64d0a9d6370479095b1`의 프록시,
  원격 wrapper, 원본 설명서를 포함했다. 라이선스와 출처를 보존했다.
- 프록시의 JSON 처리를 표준 파서로 변경했다. 숫자 포트, 이스케이프 문자,
  여러 줄 SSH 키를 처리하고 잘못된 입력을 거부한다.
- SSH 호스트 키 검증을 활성화하고 명령·시간 제한·사용자·포트·경로·ID를 검증한다.
- 관리 RPM 페이로드 검증 대상에 Network 실행 파일 2개를 추가했다.
- 설치·등록·인증·의존성 절차는 [확장 README](../../extensions/network-namespace/README.md)에 기록했다.

## 테스트 서버 적용 방식

설치 커밋은 13=`1fcb1b046715`, 22/32=`5616f85f282f`, 31=`fa68586ae6cd`였다.
각 기준 사이의 이번 교체 대상 Network 소스가 동일함을 확인했다.
13번 설치 기준에 기존 Europa Network 연동 커밋 `0d8c07ea1df`만 적용해,
WSL ext4에서 API, engine API/schema/orchestration, framework extensions,
server 모듈을 빌드했다. 누락된 maintenance/external 의존성 모듈도 별도로 빌드했다.
전체 Cloud/RPM 빌드는 수행하지 않았다.

서버마다 원본 집합 JAR을 백업한 뒤 Network 관련 클래스와 Spring XML 171개 항목만
교체했다. 그 외 모든 JAR 항목의 내용이 원본과 동일함을 ZIP 단위로 검증했다.
이는 테스트 서버의 제한적 호환 패치이며 일반 업그레이드 패키지가 아니다.
다른 버전에 이 overlay를 재사용해서는 안 된다.

DB는 기존 Network 변경에 해당하는 세 항목만 적용했다.

- `extension_details.value`: VARCHAR(4096)
- `extension_resource_map_details.value`: VARCHAR(4096)
- `physical_network_service_providers.custom_action_service_provided`: 기본값 0의 새 컬럼

각 관리 서버에서 `mold`를 재시작했다. VM 및 활성 FT 보호 행은 변경하지 않았다.
UI는 이미 활성 `js/app.c3c67ec4.js`에 NetworkOrchestrator 지원이 있어 교체하지 않았다.
`WEB-INF`를 보존했으며, 네 서버 모두 `/client/` HTTP 200을 확인했다.

## 배포 결과

| 클러스터 | 관리 백엔드/스크립트 | Network API 등록 | KVM wrapper | VM/FT 상태 |
| --- | --- | --- | --- | --- |
| 13 | 완료 | Enabled / NetworkOrchestrator / pathready=true | 3대 완료 | 유지 |
| 22 | 완료 | Enabled / NetworkOrchestrator / pathready=true | 3대 완료 | 유지 |
| 31 | 완료 | Enabled / NetworkOrchestrator / pathready=true | 3대 완료 | 유지 |
| 32 | 완료 | Enabled / NetworkOrchestrator / pathready=true | 3대 완료 | 유지 |

기존 HyperV, Proxmox, MaaS 역시 네 서버 모두 Enabled / pathready=true다.
Network 등록은 인증된 `createExtension` API로 수행했다. DB에 확장 행을 직접 삽입하지 않았다.
기존 물리 네트워크에 연결하지 않았으며 Network 확장의 활성 resource binding은 0이다.
12개 KVM 호스트에는 wrapper 파일만 설치했다. 네트워크 관련 서비스나 브리지 설정은 바꾸지 않았다.

## 13번 대표 검증

- API: UUID `6db5ab6a-ded6-4ad8-a8ce-596b842d5ed9`, 이름 `network-namespace`,
  타입 `NetworkOrchestrator`, 상태 `Enabled`, `pathready=true`.
- 실행 계정 `cloud`로 실제 프록시의 `ensure-network-device`를 호출했다.
- 검증한 SSH 호스트 키를 사용해 실제 `10.10.13.1`에 연결했다.
- 응답: `{"host":"10.10.13.1","namespace":"cs-net-91313"}`.
- 이 명령은 호스트를 선택할 뿐 namespace를 생성하지 않는다. 기존 네트워크 변경은 없다.
- 기존 관리 SSH 키의 인증이 실패하여 비밀번호 인증 의존성인 `sshpass-1.09-4.el9`를
  Rocky 저장소에서 받아 서명 및 SHA256 확인 후 13번 관리 서버에만 설치했다.
  임시 인증 payload는 검증 직후 제거했다. 비밀번호·키·세션을 보고서에 저장하지 않았다.

Maven 대상 테스트 265개(API 47, 확장 214, Network guru 4),
패키지 검사 10개, 프록시 계약 테스트 7개가 모두 통과했다.
프록시 단위 테스트의 SSH는 모의 처리이며 위 실제 SSH 검증과 구분한다.

실제 VM의 DHCP, DNS, NAT, 방화벽, LB, VPC 트래픽 시험은 수행하지 않았다.
이는 사용자가 지정한 확장 인식 대표 검증 범위 밖이다. 실제 사용에는 서비스별 의존성,
SSH 인증과 물리 네트워크/provider 및 오퍼링 구성이 추가로 필요하다.

## 무결성과 복구

| 항목 | SHA256 |
| --- | --- |
| 프록시 | `b8884d7b0e187d9383f4151a4b2d4727b2bdbf1955bd228f8bfd07d40960ca9d` |
| 최종 원격 wrapper | `44349547449e85ffab7870c0d72a0da3244bb789fc9bae11db75acca0c1bfe89` |
| 제한적 overlay 번들 | `390f9654f442d32debb0bd02113673978ee25cd72bc81a078a1b11882e80930a` |
| 13번 추가 sshpass RPM | `788d3639aeace23cfd8fd0fb93fa8ec1af851df48cfc244df6565e1cd7673a7c` |

최초 overlay에 포함된 wrapper의 SHA256은 `a27125eb885917d23288100a05a83c66bf367c4653027e81b314295f93d3045f`였다.
PR 정리 시 원본의 파일 끝 빈 줄 하나를 제거한 최종 파일로 16개 설치 위치를 동기화했다.
스크립트 동작 변경은 없다.

서버별 백업에는 원본 JAR, 변경 manifest, 이전 스키마, VM/보호 전후 목록이 있다.

| 클러스터 | 백업 디렉터리 |
| --- | --- |
| 13 | `/root/europa-network-recovery-20260912-psSYAu` |
| 22 | `/root/europa-network-recovery-20260912-4QbRl0` |
| 31 | `/root/europa-network-recovery-20260912-6ufAu4` |
| 32 | `/root/europa-network-recovery-20260912-AYYWoA` |

복구 시 Network resource binding이 없음을 재확인하고 확장을 API로 비활성화한 다음,
관리 서비스를 중지해 해당 서버의 원본 JAR을 복원한다. 이후 서비스를 재시작하고
HTTP/API 및 VM·보호 상태를 확인한다. 확대된 DB 컬럼을 무조건 축소하거나
신규 컬럼을 삭제하지 않는다. 후속 데이터가 있을 수 있다.

빌드·배포·API·최종 상태 증거는 WSL ext4의
`/home/ablecloud/work/europa-extension-packaging-20260912/` 아래
`network-*.log`, `network-deploy/`, `network-api/`, `network-ssh/`, `network-final/`에 보관했다.
