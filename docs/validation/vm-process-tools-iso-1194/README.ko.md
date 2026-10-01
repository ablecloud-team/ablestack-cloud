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

# Cloud #1194 다중 버전 Tools ISO 검증

2026-09-29, 테스트 영역 `7013f5a1-de59-4cea-a596-f4f58815f31c`. Cloud PR #1192 head에서 새 누적 브랜치를 만들었다. 전체 Cloud 패키지를 다시 빌드하지 않고 WSL ext4 clone에서 변경된 server/KVM Maven 모듈을 빌드했다. 테스트 클러스터 관리 서버와 호스트 3대에는 해당 class만 기존 JAR에 overlay하여 나머지 선행 변경을 보존했다.

qemu #79 / PR #80 GitHub Actions run `36545619710`의 `process-9-89806b1` manifest와 ISO 원본의 SHA-256을 대조하고, 같은 파일의 SHA-512를 직접 계산해 Cloud `listIsos.checksum`과 비교했다. 네 ISO 모두 Ready이며 x86_64/테스트 영역에 등록되었다.

| 계열 | Cloud ISO ID | selector |
|---|---|---|
| Rocky | `77f985d1-1f61-4594-aa1d-be4a4c61c654` | rocky 8/9/10 |
| Ubuntu | `76f457be-c401-44a5-b43d-778a465732de` | 22.04/24.04/26.04 |
| Debian | `715788e2-88b4-4c29-b09e-6ceeb70f61e8` | 12/13 |
| Windows | `bc0d7fe7-2126-4944-b830-f2894b16543f` | client 11, server 2019/2022/2025 |

Global 설정 `vm.process.tools.iso.catalog`는 위 ISO ID 네 개의 JSON 문자열 배열로 교체했다. 계열은 ISO 등록명 `ABLESTACK-Tools-Process-{계열}-89806b1`에서 읽고, 지원 버전은 Cloud 코드로 판정한다. `zoneId`·OS 버전·SHA-256/512를 설정에 수동 입력하지 않는다. Cloud 저장 체크섬과 `listIsos.checksum`의 표기 차이(`{SHA-512}` 접두사)는 정규화해 연결 시점에 비교한다. 기존 객체형 catalog는 이행 기간 동안 `isoId`를 추출해 읽는다.

실제 `getVirtualMachineProcessCapabilities` API를 12개 VM에서 재호출한 결과:

| VM | QGA 관측 OS | Cloud 등록 OS | catalog | 설명 |
|---|---|---|---|---|
| Rocky 10 | 10.2 | Rocky 10 | MATCHED | Rocky ISO 하나 |
| Rocky 9 | 9.8 | Rocky 8 | OS_MISMATCH | 잘못된 등록 OS 수정 전 자동 추천 보류 |
| Rocky 8 | 8.10 | Rocky 8 | MATCHED | Rocky ISO 하나, RPC_DISABLED 상태 유지 |
| Ubuntu 26 | 26.04 | Ubuntu 24.04 | OS_MISMATCH | 기존 등록 OS 불일치 |
| Ubuntu 24 | 24.04 | Ubuntu 24.04 | MATCHED | Ubuntu ISO 하나 |
| Ubuntu 22 | 22.04 | Ubuntu 22.04 | MATCHED | Ubuntu ISO 하나 |
| Debian 13 | 13 | Debian 12 | OS_MISMATCH | 기존 등록 OS 불일치 |
| Debian 12 | 12 | Debian 12 | MATCHED | Debian ISO 하나 |
| Windows Server 2025 | server/2025 | Server 2025 | MATCHED | Windows ISO 하나 |
| Windows Server 2022 | QGA 없음 | Server 2022 | OS_UNKNOWN | 자동 추정 금지 |
| Windows Server 2019 | QGA 없음 | Server 2019 | OS_UNKNOWN | 자동 추정 금지 |
| Windows 11 | QGA 없음 | Windows 11 | OS_UNKNOWN | 자동 추정 금지 |

`MATCHED`는 ISO 추천일 뿐 READY가 아니다. 실제 RPC/프로세스 조회·작업과 출시 가능 상태는 Cloud #1177에서 검증한다. Windows 11/2019/2022는 ISO 탭에서 실제 OS를 확인한 관리자가 수동 연결하고, QGA 설치 뒤 재조회해야 자동 추천을 판단할 수 있다. RHEL은 코드상 Rocky ISO 계열로 매칭되지만 실제 RHEL 테스트 VM/설치 증거가 없으므로 지원 확정 대상이 아니다. Debian 11은 지원 대상에서 제외했다.

집중 테스트: server catalog 5개, KVM capability 5개, UI 7개, C1 schema/fixture 28 accepted·12 rejected·3 malformed. Server Checkstyle 0건. KVM 모듈에는 선행 변경 파일의 wildcard import 3건이 있어 이번 모듈 컴파일·테스트에서는 Checkstyle만 제외했다.

관리 서버의 정적 UI 자산을 배포한 뒤 `/client/` HTTP 200과 `WEB-INF` 보존을 확인했다. 브라우저의 Windows Server 2025 프로세스 탭에서 권장 Windows ISO와 활성화된 연결 버튼을 확인했다. 설치 대화상자를 연 상태로 자동 갱신 간격(9초)보다 오래 기다려도 권장 ISO와 버튼이 유지되며 화면이 깜빡이지 않았다. 어두운 테마에서 대화상자의 표, 본문, 버튼 대비도 확인했다. 실제 ISO 연결은 수행하지 않았다.
