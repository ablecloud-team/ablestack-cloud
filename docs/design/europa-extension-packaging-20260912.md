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

# Europa 확장 배포 누락 분석

> 이 문서는 기본 확장 3종을 복구한 시점의 기록이다. 후속 조사에서 공식 별도
> Network 어댑터 저장소를 확인했고, Network 배포와 13번 대표 검증까지 완료했다.
> 최신 결과와 이전 판단의 정정은 [Network 후속 보고서](europa-network-extension-20260912.md)를 참조한다.

## 범위와 상태

- 조사일: 2026-09-12
- 수정 기준: `upstream/ablestack-europa`의 `8c221cb8071f44f53e71f74cd36b44f6be2d2f1b`
- 작업 브랜치: `codex/europa-extension-packaging`
- 사용자가 지정한 13, 22, 31, 32 테스트 클러스터의 관리 서버에 기본 확장 파일과 누락 의존성 배포를 완료했다.
- 사용자 요청에 따라 13번 클러스터를 API 인식 검증의 대표 샘플로 사용했다.
- 전체 빌드, 관리 JAR/UI 교체, DB 직접 수정, 관리 서비스 재시작, 외부 장치 작업은 수행하지 않았다.

## 확인한 서버 증거

관리 서버 `10.10.22.10`은 현재 SSH 22번 포트로 접근 가능하고, 기존 기록의 10022번 포트는 연결을 거부했다.

| 항목 | 확인 결과 |
| --- | --- |
| 설치 RPM | `cloudstack-management-4.23.0.0-Mold.Europa.202609080713.1.x86_64` |
| 설치 소스 표식 | `5616f85f282f61a566d84a3b9c2e89437862166a` |
| 설정 | `extensions.deployment.mode=production` |
| 실제 경로 | `/etc/cloudstack/extensions`, `/usr/share/cloudstack-management/extensions` 모두 없음 |
| RPM 소유 파일 목록 | `/extensions` 경로 없음 |
| 관리 서비스 | `mold` active, `/client/` HTTP 200, `WEB-INF` 존재 |
| 의존성 | python3, bash, curl, jq 존재. cloud 계정에서 `requests_oauthlib` 확인, `winrm` 미설치 |
| 런타임 오류 | 20:54 KST 로그에서 Proxmox, HyperV, MaaS 진입점이 없다는 오류 확인 |

## 원인

### 기본 실행 파일의 RPM 누락

`packaging/package.sh`은 `rocky9` 배포판을 `centos8` 스펙에 매핑한다. 그런데 확장 설치 및 `%files management` 소유 목록은 `packaging/centos7/cloud.spec`에만 있고, 실제 사용되는 `packaging/centos8/cloud.spec`에는 없다. 따라서 Java/API 및 DB 확장 등록 정보가 존재해도 실행 파일과 관리 서버 경로 링크는 RPM에 들어가지 않는다.

설치된 소스 커밋과 비교 기준 커밋 사이에서 `extensions/`의 세 실행 파일 내용은 동일하다. 파일 복구를 위해 최신 전체 Cloud 바이너리로 교체할 필요는 없다.

CentOS 7의 `EXTENSIONSDEPLOYMENTMODE` 누락도 조사 중 발견했지만, 해당 서버는 이미 production으로 설정되어 있다. 이를 이번 서버 장애 원인으로 판정하지 않는다.

### HyperV 의존성

`extensions/HyperV/hyperv.py`는 `winrm`을 import한다. 파일만 복원해도 이 모듈이 없으면 실행이 실패한다. 실제 배포 시 승인된 패키지 소스의 pywinrm 및 의존성을 설치하고, 서비스 계정으로 import를 확인해야 한다. MaaS는 `requests_oauthlib`, Proxmox는 curl/jq가 필요하다.

### Network는 별도의 문제

비교 기준 소스의 기본 `extensions/`에는 HyperV, MaaS, Proxmox만 있다. `Network`라는 기본 실행 파일은 없다. NetworkOrchestrator는 `framework/extensions`의 Java 프레임워크와 사용자가 제공하는 네트워크 장치 어댑터 스크립트로 구성된다.

서버의 설치 소스 커밋에는 `framework/extensions/src/main/java/org/apache/cloudstack/framework/extensions/network/NetworkExtensionElement.java`가 없고, 조사한 애플리케이션 JAR에서도 해당 클래스가 확인되지 않았다. 최신 비교 기준 소스에는 해당 클래스가 있다. 따라서 Network에 대해서는 다음 두 조건을 별도로 충족해야 한다.

1. 설치 바이너리가 NetworkOrchestrator를 포함하는지 확인하고, 필요하면 해당 변경과 관련 모듈/API/DB 계약의 호환성을 검토하여 배포한다. 새 framework JAR 한 개를 기존 통합 JAR 옆에 임의 추가하면 중복 클래스 문제가 생길 수 있다.
2. 사용할 네트워크 공급자와 장치, 어댑터 소스를 지정한 뒤 실행 파일과 물리 네트워크 연결을 구성한다. 샘플 성공 응답 스크립트를 배포한 것을 실제 네트워크 기능 검증으로 간주하지 않는다.

## 수정 사항과 테스트

- CentOS 8/Rocky 9 스펙에 기본 확장 설치, `/etc/cloudstack/extensions`를 가리키는 관리 서버 심볼릭 링크 및 cloud:cloud 0755 소유 규칙 추가.
- Rocky 9.7과 9.8 빌드의 기존 관리 RPM 검증 단계에 `verify_extensions_payload.py` 연결.
- 검증기는 추출한 RPM에서 세 실행 파일 내용, 0755 권한, 디렉터리 권한, 심볼릭 링크, production 설정을 확인한다. 확장 스크립트 자체나 외부 장치 작업을 실행하지 않는다.
- 회귀 테스트 10개를 WSL ext4 작업 디렉터리에서 실행하여 모두 통과했다. 누락 파일, 잘못된 링크, 내용 변경, 실행 권한 누락, 비운영 설정도 검출한다.
- 전체 Cloud/RPM 빌드는 요청되지 않았으므로 수행하지 않았다. 새 RPM 산출물에 대한 검증은 아직 실행하지 않았다.

## 배포 및 검증 결과

네 관리 서버의 설치 소스와 비교 기준 사이에서 기본 확장 3개 파일 내용이 동일함을 확인했다. 원본 Git 파일을 사용했고, Python wheel은 PyPI SHA256과 대조 후 오프라인으로 설치했다.

| 클러스터 | 설치 소스 표식 | 기본 확장 배포 | DB 주기 검사 | API 대표 검증 |
| --- | --- | --- | --- | --- |
| 13 | `1fcb1b0467158e4e0b8e05c0f59886fe4a4b7d43` | 완료 | 3개 모두 `path_ready=1` | 3개 모두 Enabled / pathready=true |
| 22 | `5616f85f282f61a566d84a3b9c2e89437862166a` | 완료 | 3개 모두 `path_ready=1` | 대표 샘플 범위 아님 |
| 31 | `fa68586ae6cdb9d92cb5a2df15c176d202610b55` | 완료 | 3개 모두 `path_ready=1` | 대표 샘플 범위 아님 |
| 32 | `5616f85f282f61a566d84a3b9c2e89437862166a` | 완료 | 3개 모두 `path_ready=1` | 대표 샘플 범위 아님 |

13번의 인증된 `listExtensions` 응답:

| 이름 | 타입 | 상태 | pathready | 경로 |
| --- | --- | --- | --- | --- |
| HyperV | Orchestrator | Enabled | true | `/usr/share/cloudstack-management/extensions/HyperV/hyperv.py` |
| Proxmox | Orchestrator | Enabled | true | `/usr/share/cloudstack-management/extensions/Proxmox/proxmox.sh` |
| MaaS | Orchestrator | Enabled | true | `/usr/share/cloudstack-management/extensions/MaaS/maas.py` |

- 실제 파일은 `/etc/cloudstack/extensions`에 설치했고 관리 서버 경로는 심볼릭 링크다. 소유자/권한은 cloud:cloud / 0755다.
- HyperV와 MaaS는 cloud 계정으로 실제 진입점을 인자 없이 실행하여 import 성공과 정상적인 사용법 오류 응답을 확인했다. 외부 장치에 연결하는 작업은 호출하지 않았다.
- Proxmox는 실행 권한과 `bash -n` 구문 검사를 확인했다.
- 파일 배포 직후에는 path_ready가 0이었다. DB를 강제로 수정하지 않고 기존 300초 주기 검사로 1로 바뀌는 것을 확인했다.
- 네 서버에서 배포 전후 VM 식별자/상태/host_id, 활성 FTCTL 보호 건수, 관리 PID, server.properties가 동일했다. `/client/` HTTP 200과 `WEB-INF`도 유지됐다.
- 13번 API의 등록된 External 하이퍼바이저 호스트 수는 0이었다. 실제 HyperV/Proxmox/MaaS 장치에서 VM 생성·삭제를 검증한 결과가 아니다.
- NetworkExtensionElement는 네 서버의 설치 JAR 전체 검색에서 발견되지 않았다. Network를 배포 완료로 판정하지 않는다.

### 의존성과 원본 무결성

추가한 패키지: `pywinrm==0.5.0`, `requests-ntlm==1.3.0`, `pyspnego==0.12.0`, `xmltodict==1.0.4`. 네 서버에서 기존에 없던 패키지만 `--no-index --no-deps`로 설치했다. 기존 requests, cryptography 등의 업그레이드는 하지 않았다. 새 RPM의 스크립트 포함과 외부 확장의 선택적 Python 의존성 설치는 별도 절차다. 이 PR이 새 RPM에 Python 의존성까지 자동 번들링하는 것은 아니다.

공식 패키지 메타데이터: [pywinrm](https://pypi.org/project/pywinrm/0.5.0/), [requests-ntlm](https://pypi.org/project/requests-ntlm/1.3.0/), [pyspnego](https://pypi.org/project/pyspnego/0.12.0/), [xmltodict](https://pypi.org/project/xmltodict/1.0.4/).

| 파일 | SHA256 |
| --- | --- |
| HyperV/hyperv.py | `dfcfee8c527e94f4e096c8e2c492c89b840d5a868c6c4ae3213de1602e7ff9fd` |
| MaaS/maas.py | `fa93c47ccc78f603ebf6064717c2970dad1e5881aeb9eafdd927a184193bc60c` |
| Proxmox/proxmox.sh | `9fd00619571a08c142166eb7ac956f58a79c1bb13937fc2352d291cec984ccd2` |
| 복구 번들 | `d675b072580117c912a031271df453c72e9cf1cc6ad48b11b5d9e5d9d8803f06` |

### 백업 및 증거

각 서버의 백업에는 원본 설정, 관리 PID, VM/보호 목록 전후 비교, Python 패키지 목록, 원본 파일, wheel, manifest가 있다. 비밀키나 로그인 쿠키를 검증 출력에 저장하지 않았다.

- 13: `/root/europa-extensions-recovery-20260912-sfFDTy`
- 22: `/root/europa-extensions-recovery-20260912-NCt8Ce`
- 31: `/root/europa-extensions-recovery-20260912-XbnrhH`
- 32: `/root/europa-extensions-recovery-20260912-tb0BXI`
- 작업 증거: WSL ext4의 `/home/ablecloud/work/europa-extension-packaging-20260912/` 아래 preflight, state-before, python-before, deploy, runtime-after, api-after.

기존 확장 경로가 전부 없었음을 확인한 뒤 신규 파일만 설치했다. 롤백이 필요하면 위 manifest와 대조하여 이번에 추가한 파일 및 Python 패키지만 제거해야 한다. 후속 사용자 수정 여부를 재확인하지 않은 일괄 삭제는 하지 않는다.

## 후속 배포 시 점검 절차

1. 대상 관리 서버, 관리 서버 피어, 설치 버전, 서비스 계정, 확장 DB/API 상태를 다시 확인한다.
2. 설치 버전에 맞는 확장 원본의 SHA256을 기록하고 기존 경로/설정을 백업한다. 사용자 커스텀 확장은 덮어쓰지 않는다.
3. 세 기본 실행 파일과 링크를 복원하고 cloud 계정의 읽기/실행 권한 및 Python 의존성을 검증한다. production 설정이 이미 맞으면 이 복구만을 위한 관리 서비스 재시작은 필요하지 않다.
4. 주기적 경로 확인 또는 API 경로 재검사를 통해 ready/checksum 상태와 오류 로그 해소를 확인한다. 이 상태는 외부 하이퍼바이저의 실제 VM 생성 성공을 의미하지 않는다.
5. Network는 공급자 어댑터와 호환 백엔드 범위가 결정된 다음 별도로 배포한다. 사용자의 명시적 전체 빌드 요청 없이는 전체 빌드를 시작하지 않는다.
6. 배포 후 `/client/` HTTP 200, `WEB-INF`, 기존 VM 및 보호 상태 불변을 확인한다. 외부 HyperV/Proxmox/MaaS 장치 연동 시험은 엔드포인트/테스트 자원이 확보된 범위만 수행한다.
