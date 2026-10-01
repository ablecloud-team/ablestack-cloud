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

# VM 프로세스 운영 및 C7 검증

이 도구는 승인된 테스트 클러스터의 명시한 UUID와 이름이 `Process`로 끝나는 VM만 검사한다. 일반 운영 VM에 부하나 변경 작업을 자동으로 적용하지 않는다. 게스트 fixture의 생성·정리와 실제 PID/서비스 후조건 확인은 별도 절차로 수행한다.

## 활성화와 ISO 등록

1. Global 설정 `vm.process.management.enabled`를 명시적으로 `true`로 설정한다. 비활성화 시 기존 목록·작업 API도 차단된다.
2. `vm.process.tools.iso.catalog`에 Ready 상태의 계열별 Tools ISO UUID 배열만 등록한다. 예: `["<Rocky ISO UUID>","<Ubuntu ISO UUID>","<Debian ISO UUID>","<Windows ISO UUID>"]`. ISO의 UUID는 Cloud 등록 리소스를 식별하고, ISO 내부 manifest·OS/arch/zone/권한 검사는 제품이 수행한다.
3. VM 등록 운영체제와 실제 게스트 OS를 일치시킨다. 등록 OS가 없으면 관리자 Guest OS 등록 기능으로 정의한다. OS 불일치 오류를 숨기거나 검사를 해제하지 않는다.
4. VM 프로세스 탭 상단에서 Tools ISO를 선택·연결하고 게스트 관리자로 ISO 루트의 `install-linux.sh` 또는 `install.bat`를 실행한다. Windows는 VirtIO 드라이버·QGA·Process Tools를 자동 설치한다. 필요한 경우 게스트를 재부팅한다.
5. 상단의 QGA 권한 다시 확인과 업데이트를 통해 실제 목록까지 확인한다. 설치 성공 메시지·RPC enabled·READY 하나만으로 설치 검증을 종료하지 않는다. Windows 설치 매체는 검증 후 모두 해제한다.

등록 지원 목표는 Rocky/RHEL 계열 8.x/9.x/10.x, Ubuntu 22.04/24.04/26.04, Debian 12/13, Windows 11/Server 2019/2022/2025이다. 실제 이번 실증 OS는 검증 보고서 표를 따른다. RHEL 설치 자체는 Rocky 결과로 대체하여 PASS로 표기하지 않는다.

## 목록과 변경 작업

CPU는 한 코어 100% 기준이고 `—`는 미측정이다. `PARTIAL` 결과는 일부 목록으로 표시하며 표시 항목 수가 VM 전체 프로세스 수라는 뜻이 아니다. 갱신 중 기존 행은 유지한다. 이름 검색·CPU/메모리 정렬·페이지 이동은 같은 snapshot에서 수행하며 만료하면 업데이트한다.

정상 종료·강제 종료·서비스 재시작은 관리자에게만 제공한다. 일반 프로세스 재시작은 지원하지 않는다. Windows에는 일반 프로세스 정상 종료를 제공하지 않는다. 작업 전에 현재 PID뿐 아니라 부팅 신원·시작 시각·snapshot·서비스 설정·현재 호스트를 확인한다. OS/ABLESTACK 보호 대상 거부를 우회하지 않는다.

응답이 없거나 `UNKNOWN`이면 저장한 `requestId` 또는 `operationId`로 `getVirtualMachineProcessOperation`만 조회한다. 새 요청 ID로 동일 작업을 다시 보내지 않는다. 관리 서비스 재시작 후에도 이 원칙을 유지한다. 이동·재부팅·권한 변경 후 오래된 관측을 사용하지 않는다. 성공 판정에는 `SUCCEEDED`, `effect=VERIFIED`, 종료의 `TARGET_EXITED` 또는 재시작의 `SERVICE_RESTART_VERIFIED`와 게스트 실제 결과가 필요하다.

## 읽기 검증 실행

WSL ext4 checkout에서 Python 3 표준 라이브러리만 사용한다. 인증 값은 비공개 환경 변수 `CLOUD_USERNAME`, `CLOUD_PASSWORD`로 제공하고 명령 인자·Git·보고서에 저장하지 않는다.

```bash
python3 tools/vm-process-e2e/probe.py   --api http://<management>:8080/client/api   --vm <explicit-test-vm-uuid> --output readiness.json
python3 -m unittest discover -s tools/vm-process-e2e -v
```

읽기 검증은 READY, RPC 8개, 같은 snapshot의 페이지/중복 PID/CPU 측정값을 검사한다. `PARTIAL`을 완전한 목록으로 인정하지 않는다. 실제 RPC 실행과 게스트 후조건은 별도 증거가 필요하다.

변경 검증은 `--fixture <fixture.json>`을 추가하고 VM 하나만 지정한다. fixture에는 `action`, `pid`, `snapshotId`, `receipt`가 필요하고 서비스 재시작에는 `serviceName`이 필요하다. 서비스 이름은 `process-c7-` 또는 `AbleC7`로 시작하는 전용 fixture만 허용한다. 도구는 전송 전에 요청 ID를 receipt에 저장하고 변경 API를 한 번만 호출한다. receipt는 실패 시에도 보존한다.

## 단계 배포와 되돌리기

1. upstream/origin을 확인하고 기존 Epic head에서 `codex/` 누적 브랜치를 만든다. 기존 더티 checkout을 덮어쓰지 않는다.
2. 변경 Maven 모듈만 WSL ext4에서 빌드한다. Cloud 전체 빌드는 별도 명시 요청 없이는 실행하지 않는다. Qemu 코드·패키지를 변경하면 GitHub Actions에서 빌드한 일치하는 RPM/ISO를 사용한다.
3. 배포 전 진행 중 프로세스 작업을 끝내고 현재 JAR·정적 자산·설정·VM inventory·FT/hangctl 상태를 보존한다. management의 `/usr/share/cloudstack-management/webapp/WEB-INF`, 존재하는 `META-INF`, `config.json`을 확인하고 디스크 여유 공간을 확인한다.
4. 변경 모듈을 대표 호스트부터 적용하고 설치된 클래스·리소스 SHA256, Mold/Mold Agent active, 실제 API 결과를 확인한 뒤 다른 테스트 호스트로 확장한다. UI는 정적 파일만 갱신한다. webapp 루트 교체와 `rsync --delete`를 금지한다.
5. 문제 발생 시 변경 API를 멈추고 Global 기능을 비활성화한다. 원래 작업 ID는 보존하여 읽기 조회로 실제 결과를 확인한다. 호환 카탈로그에서 승인된 구형 해시 묶음을 임의로 삭제하지 않는다.
6. 서비스를 중지하고 해당 서버에서 저장한 원본 JAR를 같은 경로에 복구한 후 소유권·권한·SELinux 라벨을 유지하고 서비스를 시작한다. UI는 백업한 정적 파일만 복구하고 서버 디렉터리·설정은 보존한다. rollback은 게스트 설치 파일이나 이미 실행된 프로세스 변경을 되돌리는 기능이 아니다.
7. 배포/rollback/재적용 각각에서 설치 바이트, WEB-INF/META-INF, `/client/` HTTP 200, 서비스 상태와 VM inventory를 확인한다. 새 기능의 guest postcondition을 다시 확인한 뒤 Global을 복구한다.

## 완료를 알 수 없는 읽기 작업 복구

Q4 읽기 실행의 응답이 유실되면 guest-exec PID를 포함한 `q4-read-REQUEST_UUID.json`이 남는다. 이때 새 읽기, capability, 일부 기존 관측도 차단된다. 운영 중인 guest collector를 중복 실행하거나 오래됐다는 이유로 마커를 삭제하지 않는다.

VM의 현재 호스트에서 root로 실행한다. `--vm`과 `--request`는 남은 마커에서 확인한 정확한 UUID를 지정한다.

```bash
python3 reconcile_read_lease.py --vm VM_UUID --request REQUEST_UUID
python3 reconcile_read_lease.py --vm VM_UUID --request REQUEST_UUID --apply
```

첫 명령은 상태 조회 RPC를 소비하지 않고 마커만 검사한다. `--apply`는 동일 호스트 부팅·VM UUID·원래 host owner PID/startTicks 종료·flock·파일 소유권/권한을 확인한다. **원래 guest-exec PID의 guest-exec-status가 exited=true일 때만 그 읽기 마커 하나를 정리한다.** 마커 교체, 살아 있는 owner, PID 누락, 관측 실패, 이미 회수된 guest-exec ID, 실행 미종료는 거부한다. lock 파일과 다른 lease, 변경 작업 journal은 삭제하지 않는다. 자동 재실행·자동 실패 성공 처리 기능은 없다.

QGA의 완료 상태는 조회 후 회수될 수 있다. 완료 응답 유실이나 도구 중단으로 종료 증거를 확보하지 못하면 시간 경과나 PID not found를 종료로 해석하지 않는다. 별도 원래 실행의 종료 증거 또는 승인된 VM 재부팅을 확보한 뒤 관리자가 판단한다. 실제 원래 완료 응답을 별도로 확보한 경우도 flock과 동일 파일/실행 신원을 대조하여 해당 마커만 정리한다. C7 보고서에는 실제 불명확 읽기와 별도 일회용 복구 도구 검증을 구분하여 기록했다.

## 문제 해결

| 상태 | 확인과 조치 |
|---|---|
| QGA_UNREACHABLE | 게스트 QGA 설치·서비스·VirtIO serial 드라이버·채널을 확인한다. 로그인 화면 진입만으로 제품 기능이 준비됐다고 판단하지 않는다. |
| RPC_DISABLED | QGA가 보고한 누락 RPC 8개를 확인하고 해당 OS의 Tools ISO 설치를 수행한다. |
| HOST_TOOL_MISSING | 호스트의 Mold Agent 모듈·Qemu 패키지·root 소유 승인 카탈로그를 확인한다. |
| TOOLS_REQUIRED | 실제 설치된 전체 어댑터 묶음과 승인 카탈로그·런타임을 확인한다. 최신 호스트의 단일 파일 해시와 같아야 한다는 뜻이 아니다. |
| CHECK_FAILED | transport 종료/잘린 출력/게스트 오류/보안 제품 이벤트를 확인한다. C7 Windows 2025 문제는 압축 PowerShell 데이터 전달을 일반 JSON으로 수정했다. Defender 비활성화나 제외 등록으로 우회하지 않는다. |
| BUSY / NOT_STARTED | 실행 전 잠금 경합임이 확인된 경우에만 새 관측으로 다음 작업을 판단한다. UNKNOWN과 구분한다. |
| STALE_IDENTITY | PID·부팅·시작 시각·호스트·snapshot 또는 파일 접근 오류의 증거를 확인한다. 메시지만으로 SELinux 원인으로 단정하지 않는다. |
| UNKNOWN | 원래 요청 ID로만 결과를 확인한다. 새 요청으로 재전송하지 않는다. |
| PARTIAL | 일부 목록이다. 업데이트하여 다시 확인하며 전체 수집 완료로 보고하지 않는다. |

네트워크/ISO/VM lifecycle 및 FT/hangctl 회귀의 실증 범위와 미실행 범위는 [C7 보고서](../../docs/validation/vm-process-1177/README.ko.md)를 따른다. ISO 연결 후 다른 페이지로 이동하는 별도 UI 문제는 Cloud #1198에서 관리한다.
