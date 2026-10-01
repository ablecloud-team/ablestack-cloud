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

# 프로세스 관리 ABLESTACK Tools ISO 연결

Cloud #1173/#1194 / qemu-exec-tools #65/#79. 이 기능은 Global 설정 `vm.process.management.enabled=true`인 경우에만 이용한다.

## ISO 등록과 설정

Cloud의 일반 ISO 등록 화면에서 qemu-exec-tools가 만든 Tools ISO를 등록하고 Ready 상태를 확인한다. ISO 이름은 다음 중 한 가지 형식을 사용한다. 첫 번째는 기존 테스트 클러스터의 등록명이며, 두 번째는 qemu #79의 배포 파일명이다.

- `ABLESTACK-Tools-Process-{rocky|ubuntu|debian|windows}-{빌드 커밋}`
- `ABLESTACK-Tools-{rocky|ubuntu|debian|windows}-process-{패키지 번호}-{빌드 커밋}.iso`

빌드 커밋은 소문자 16진수 7~40자리이다. ISO는 비부팅, x86_64로 등록한다. Cloud의 ISO OS 타입은 네 계열 모두 `None`이므로 OS 타입 필드로 계열을 추정하지 않는다. UUID로 조회한 ISO의 **등록명**을 위 형식과 대조하여 계열을 판별한다. 등록명은 관리자가 지정할 수 있으므로 신뢰한 빌드의 ISO를 정확한 이름으로 등록해야 한다. UUID는 ISO 객체의 유일성을 제공하지만 파일 내용의 출처를 증명하지는 않는다.

그 뒤 Global 설정 `vm.process.tools.iso.catalog`에 사용할 ISO UUID만 JSON 문자열 배열로 입력한다. 영역, OS ID, 버전, SHA-256/512를 수동으로 쓰지 않는다. ISO가 여러 영역에 등록됐다면 UUID 하나만 입력해도 해당 영역에서 조회된다. 한 영역에는 계열별로 활성 ISO를 하나만 지정한다.

```json
[
  "<Rocky Tools ISO UUID>",
  "<Ubuntu Tools ISO UUID>",
  "<Debian Tools ISO UUID>",
  "<Windows Tools ISO UUID>"
]
```

기존 객체형 catalog를 사용 중이면 현재 값을 백업하고 UUID 배열로 교체한다. 이행을 위해 기존 객체형 항목에서는 `isoId`를 추출해 읽으며, 여러 버전이 같은 ISO를 가리키는 중복은 한 번만 사용한다. 객체형 항목과 UUID를 섞거나 새 UUID 배열에서 UUID를 중복 입력하면 `INVALID_CONFIG`로 추천을 거부한다. 문제가 있으면 백업한 설정으로 되돌린다. 기존에 VM에 연결된 ISO는 설정 변경으로 자동 분리되지 않는다.

## 매칭과 검증

QGA가 보고한 OS와 Cloud에 등록된 VM OS가 일치해야 한다. Rocky/RHEL 8.x/9.x/10.x는 Rocky ISO, Ubuntu 22.04/24.04/26.04는 Ubuntu ISO, Debian 12/13은 Debian ISO, Windows 11과 Windows Server 2019/2022/2025는 Windows ISO로 연결한다. 지원 범위는 Cloud 코드에 고정되어 있으며, 새 OS 버전 지원에는 코드·ISO 설치 검증이 필요하다. Debian 11은 대상이 아니다. RHEL은 Rocky ISO를 사용하도록 매칭 코드를 마련했지만 실제 RHEL 게스트 설치 검증이 없으므로 지원 확정 전에는 운영에 적용하지 않는다.

Cloud는 UUID로 ISO를 조회해 등록명, ISO 형식, Active 상태, 비부팅 여부, x86_64, VM 영역과의 연결을 검사한다. 같은 영역에 같은 계열 ISO UUID가 두 개이면 어느 것을 추천할지 추측하지 않고 `INVALID_CONFIG`를 반환한다. ISO가 실제로 Ready인지와 현재 사용자의 ISO 접근 권한은 연결 직전 `listIsos`로 다시 확인한다. Cloud ISO 메타데이터에 체크섬이 있으면 capability 조회와 연결 시점의 값이 같은지 자동 비교한다. 관리자가 해시를 입력할 필요는 없다.

Cloud 등록 OS가 실제 게스트 OS와 다르면 `OS_MISMATCH`, 지원하지 않는 OS는 `UNSUPPORTED_OS`, 해당 영역의 계열 ISO가 없으면 `NO_MATCH`, UUID나 ISO 메타데이터가 잘못됐으면 `INVALID_CONFIG`를 반환한다. QGA 연결 불가로 OS 관측만 없는 경우에는 지원되는 등록 OS와 템플릿 x86_64 정보를 사용해 설치 매체를 안내할 수 있다. 이때 `selectionSource: REGISTERED_OS`와 등록 OS 이름을 반환하고, 사용자가 콘솔에서 실제 OS 일치를 확인해야 연결 버튼이 활성화된다. 이 정보는 실행 권한이나 READY를 부여하지 않는다. 실제 관측 불일치·호스트 검사 실패·알 수 없는 등록 OS·다른 아키텍처는 이 안내로 우회하지 않는다. 확인 가능한 등록 정보가 없으면 `OS_UNKNOWN`을 유지하며 일반 ISO 탭에서 관리자가 설치 매체를 확인해야 한다. 기본값 `[]`은 ISO 자동 추천만 비활성화한다.

프로세스 탭은 기존 `attachIso` 비동기 작업과 CD 슬롯 검사를 사용한다. 다른 ISO가 연결되어 있으면 자동 분리하지 않는다. ISO 연결 성공은 `게스트 설치 대기`이며 READY가 아니다. 게스트 콘솔에서 관리자 권한으로 설치하거나 복구한다. Linux는 `lsblk`로 Tools ISO에 해당하는 CD 장치를 확인하고 읽기 전용으로 마운트한 뒤 `bash /mnt/ablestack-tools/install-linux.sh`를 인수 없이 실행한다. Windows는 CD-ROM 볼륨의 드라이브 문자를 확인하고 관리자 PowerShell에서 ISO 루트의 `install.bat`를 인수 없이 실행한다. 두 루트 설치 파일은 QGA/드라이버 및 프로세스 어댑터 설치를 함께 수행하며 `--mode process-management`나 `-Mode Apply`를 받지 않는다. 재부팅이 필요하면 재부팅한 뒤 재검사한다.

`READY`는 C2 실행 검증이 현재 VM에 대해 READY와 `process.list`를 반환하고 필수 QGA RPC 8개가 모두 ENABLED이며, Cloud의 읽기 작업이 같은 VM의 유효한 bootId/snapshotId와 만료 전 OK/PARTIAL snapshot을 반환한 뒤에만 표시한다. QGA 실행 RPC가 막힌 상태에서 QGA로 자기 자신을 고치려 하지 않는다. 연결·설치 성공 메시지·DB 설정만으로 READY 처리하지 않는다. 결과가 불명확한 연결 작업은 기존 job을 조회하고 재연결하지 않는다. 읽기 검증 timeout도 받은 job ID를 보존해 다음 확인에서 같은 결과를 조회한다. 대화상자를 다시 열면 ISO가 실제로 연결되어 있는지 재조회하며, VM/사용자/프로젝트 변경·닫기 후 늦은 응답은 화면에 반영하지 않는다.
