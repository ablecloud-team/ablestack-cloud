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

# C2: VM 프로세스 capability/readiness API

관련 이슈: https://github.com/ablecloud-team/ablestack-cloud/issues/1172
상위 Epic: https://github.com/ablecloud-team/ablestack-cloud/issues/1170
계약 원본: Cloud C1 commit `7573322eb7fe81fb7a42566e6e8ce4eb32e38acf`, `docs/design/vm-process-contract`.

## API와 배포 제한

`getVirtualMachineProcessCapabilities&virtualmachineid=<VM UUID>&response=json`

응답은 `getvirtualmachineprocesscapabilitiesresponse.processcapability.processstate`이다.
C1 capability envelope의 공개 투영으로 authority에는 vmUuid만 포함한다. 내부 hostUuid와
placementGeneration은 Agent 응답 확인에만 사용한다. nullable 버전 필드는 JSON null로 유지한다.

- C4부터 최상위 설정 `vm.process.management.enabled=false`를 사용하며 명시적 true가 필요하다.
- 종전 `vm.process.capability.enabled`와 `vm.process.capability.test.vm.uuids`는 등록을 제거하고 기존 DB 행도 정리한다. 관리자도 전역 설정을 우회할 수 없다.
- 아래 2026-09-25 기록은 당시 C2 검증이며, 현재 정책과 C4 검증은 `../vm-process-snapshot/README.ko.md`를 따른다.
- C1의 논리 권한 `vm.process.read`는 Cloud 동적 역할에서 이 API 명령 이름의 조회 권한으로 관리한다.
- API ACL(ListEntry)과 서비스의 account/domain/project 접근 검사 모두 적용한다. 응답 직전 접근 권한도 재검사한다.
- User VM만 허용하고 비KVM/비Running 상태는 각각 UNSUPPORTED_HYPERVISOR/VM_NOT_RUNNING으로 응답한다.

## 관측과 준비 상태

네트워크 수집기나 네트워크 helper를 호출하지 않는다. 현재 배치 호스트의 Agent로 독립 명령을 보내며
KvmVmOperationGuard의 VM flock/잔여 lease/domain job/block job 확인 및 관측 budget을 사용한다.
보호 경로에는 도메인 이름을 전달하고, lock 안에서 이름이 가리키는 UUID를 요청 VM UUID와 대조한다.
QGA 조회 자체는 UUID로 수행한다. guest-info/guest-get-osinfo 후 `vm_exec`의 guest-exec/status 경로로
고정된 읽기 전용 검증 프로그램을 실행한다. 게스트 파일·서비스·프로세스·작업 journal은 변경하지 않는다.

필수 RPC 8개의 상태는 ENABLED / DISABLED / UNSUPPORTED / UNKNOWN으로 각각 반환한다.
QGA transport 장애는 QGA_UNREACHABLE, 잘못된 응답이나 보호 경로의 busy/unknown은 CHECK_FAILED다.
호스트 실행 파일 누락은 HOST_TOOL_MISSING이며 RPC 미지원/비활성 및 지원 대상 밖 OS를 별도로 표시한다.
호스트 버전은 aspkg/dpkg-query의 패키지 진단 값으로, source overlay와 프로토콜 호환을 증명하지 않는다.

**2026-09-30 보완부터 실제 검증 성공 시 READY를 반환한다.** RPC 8개 활성과 지원 OS 확인 후
호스트의 qemu 패키지가 관리하는 `process/guest_adapter_compat.json`을 읽는다. 카탈로그와 부모 경로는
root 소유이며 쓰기 권한·symlink·파일 크기·중복 키·protocol·완전한 파일 집합을 검사한다.
RPM의 `/usr/libexec`와 DEB/make의 `/usr/local/lib` 설치 경로를 지원한다.

게스트 파일 SHA-256은 파일별 독립 허용 목록이 아닌 승인된 **전체 묶음**과 비교한다.
Windows read script의 CRLF/LF 허용 규칙도 qemu 실행 경로와 동일하다. 미승인 파일은 로드하지 않는다.
검증 프로그램은 Linux read 모듈로 자신의 `/proc` identity를 읽고, action 모듈 로딩과 자신의 pidfd
열기/닫기를 확인한다. Windows는 승인된 read/action script의 구문을 검사하고 native DLL을 로드하여
자신의 process identity 및 JSON parser를 확인한다. 신호 전송·서비스 제어·action journal 호출은 하지 않는다.

`guestAdapterVersion`은 검증된 read/action 묶음 ID이며, `supportedSchemaVersions=["1.0"]`이다.
호스트 업그레이드나 마이그레이션 후에도 승인 카탈로그에 남아 있는 이전 묶음은 허용한다.
읽기 검증만 성공하면 `allowedActions=["process.list"]`, action 런타임도 성공하면 Linux는 정상 종료·강제 종료·
서비스 재시작, Windows는 강제 종료·서비스 재시작을 추가한다. action 지원 목록은 대상별 권한·보호 정책·
identity 검증을 대체하지 않으며 실제 변경 명령은 기존 C5/Q5 경로에서 다시 검증한다.

호스트 카탈로그가 없거나 안전하게 읽을 수 없으면 HOST_TOOL_MISSING, read 어댑터가 없거나 승인된
해시 묶음과 불일치하면 TOOLS_REQUIRED, 런타임/통신/응답 검증 실패는 CHECK_FAILED다.
게스트 파일 RPC 6개의 실제 쓰기·flush·cleanup 검증과 설치/복구 전체 매트릭스는 Q2/Q6에서 추적한다.
이 API는 쓰기 probe를 수행하지 않는다. Management는 READY/RPC/버전/허용 작업의 불변조건을 다시 검사한다.

## 시간·동시성·stale 방지

캐시 없이 요청마다 새 관측을 수행한다. 응답 ttlseconds=30은 공개 관측의 최대 유효 시간이며
변경 작업은 반드시 다시 확인해야 한다. 관리 서버에서 호스트 ID, VM UUID, update_count 세대를
요청 전후 대조하고 Running 상태·제거 여부를 재검사한다. 응답 requestId/authority 불일치나
15초 초과 응답은 폐기한다. 관측 시각은 관리 서버 시각으로 부여한다.

Management 8개, Agent 8개 동시 관측 상한을 적용하며 대기열을 만들지 않는다.
일반 모니터링 guard의 기본 5초 budget은 유지하고, C2에만 최대 10초 관측 budget을 적용한다.
개별 QGA 조회 대기는 1.5초, vm_exec 검증 대기는 6초, Agent command wait는 15초다.
Windows 명령행 길이 제한을 지키도록 승인 목록을 gzip/base64로 전달하고 encoded argument를 30,000자로 제한한다.
실패·재부팅·마이그레이션을 READY로 추정하지 않으며 오래된 snapshot을 재사용하지 않는다.

## 검증 방법

WSL ext4 checkout에서 변경 모듈만 빌드한다.

```bash
mvn -pl api,core,server,plugins/hypervisors/kvm \
  -Dtest=VmProcessCapabilityProbeTest,VmProcessCapabilityServiceImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Drat.skip=true install
mvn -pl plugins/hypervisors/kvm \
  -Dtest=VmProcessCapabilityProbeTest,KvmVmOperationGuardTest -Drat.skip=true install
```

테스트는 8개 RPC별 missing/disabled, 잘못된 JSON 및 타입/중복 RPC, readiness 우선순위,
OS allowlist, cross-tenant 거부, VM 상태 제한, 세대 변경 응답 폐기, 내부 authority 비노출,
null 직렬화와 guard에 전달하는 domain name/UUID 경계를 검증한다.
실제 VM을 중지하거나 QGA 정책을 변경하지 않는 오류 fixture 검증과 실제 게스트 검증을 구분한다.

## 실제 환경 검증 기록

아래 2026-09-25 기록은 초기 C2 구현 당시 결과이다. 현재 READY 보완 검증은
`../../validation/vm-process-capability-1172-ready/README.ko.md`를 따른다.

- Management: 10.10.31.10.
- Linux: 10.10.31.1 / i-2-15-VM / UUID 708d87cd-1d8a-4911-b22a-4211cdf1634f.
- Windows: 10.10.31.3 / i-2-27-VM / UUID 9a2a9ac1-088e-40d5-b753-197a559b06e3.
- 기존 JAR를 백업한 뒤 모듈 빌드로 생성한 신규 클래스와 Spring 등록 bean만 반영하는 class overlay를 사용했다.
  기존 ManagementServerImpl 및 다른 변경 클래스는 교체하지 않았다. 서비스 재시작은 mold/mold-agent만 대상이다.
- 관리 서버 backup: `/root/issue1172-deploy-20260925-103827`.
- 호스트 최초 backup: 31.1 `/root/issue1172-deploy-20260925-103822`, 31.3 `/root/issue1172-deploy-20260925-103824`.
- 첫 실제 호출은 guard에 UUID를 넘겨 CHECK_FAILED로 종료되었다. 도메인 이름을 요구하는 virsh domuuid 사용을 수정하고 회귀 테스트를 추가했다.
- 원본 증거: `/home/ablecloud/work/validation/cloud-issue1172/`, 빌드 로그 `/home/ablecloud/work/c2-*-build.log`.

정식 패키지 전체 빌드, READY/프로세스 목록·변경 API, ISO 설치 및 UI 탭은 이번 범위가 아니다.
지원 OS 전체 실증과 QGA 정책 장애 주입, 실제 reboot/migration 동시성 E2E는 Q6/C7의 후속 gate다.
### 최종 검증 결과 (2026-09-25)

- API/core/server/KVM 4개 변경 모듈 빌드 성공. 신규 테스트 10개와 기존 KvmVmOperationGuard 테스트 7개 통과.
- 사용자가 Rocky 10.2 및 추가 VM의 Ubuntu 26.04를 지원 목표에 포함하도록 승인했다.
  C1 PR #1179의 README와 fixture를 함께 갱신했으며, 정적 검증은 유효 28개/위반 12개/malformed 3개 통과했다.
- 추가 VM: 10.10.31.1 / i-2-28-VM / UUID 4cb71960-fc75-43e1-9830-8f4ee198def7.
  Cloud 등록 OS는 Ubuntu 24.04이나 QGA 실제 OS는 Ubuntu 26.04 LTS, QGA 10.2.1이다.
  운영체제 등록 메타데이터는 변경하지 않았다.
- Rocky 10.2(QGA 10.1.0), Windows Server 2025(QGA 110.2.3), Ubuntu 26.04를 현재 테스트 매트릭스로 사용한다.
- 두 기존 VM의 실제 API에서 RPC 8개 활성, 패키지 버전 0.9.5, 내부 호스트 정보 비노출,
  null 필드 보존 및 요청별 새 관측을 확인했다. Rocky 10.2 목표 추가 후 두 VM 모두 TOOLS_REQUIRED다.
- 실제 별도 일반 계정으로 admin 소유 VM 조회가 error 531로 거부되는 것을 확인했다.
  임시 계정 삭제 API는 기존 외부 연동 Connection refused로 계정만 soft-delete하고 사용자 레코드를 남겼다.
  삭제된 이번 임시 계정의 UUID/id와 일치하는 사용자 1건만 disabled/removed 처리했다.
  남은 활성 테스트 계정·사용자는 0건이고 생성한 VM/볼륨도 없다. 이 외부 연동 오류를 C2 성공으로 집계하지 않는다.
- 전역 기능 활성화 설정은 false를 유지했다. 테스트 허용 목록은 검증 후 원래 빈 값으로 복원했다.
- Ubuntu 26.04에서는 기존 Q1 vm_exec smoke의 6개 확인 지점(문자 출력, stderr/exit, signal,
  deadline UNKNOWN/PID, 후속 종료, 출력 제한)도 통과했다.

원본 로그에 인증 정보는 저장하지 않는다. API 검증은 로그인 세션을 사용하며 기존 API 키는 변경하지 않았다.
Management WEB-INF 보존, /client/ HTTP 200, mold 및 두 호스트 mold-agent active,
기존 두 VM Running/배치 host_id/update_count 불변, 두 host Up을 확인했다.

롤백은 해당 호스트의 최초 backup JAR를 복원하고 restorecon 후 서비스를 재시작한다.
class overlay는 정식 패키지 버전을 바꾸지 않았으므로 향후 패키지 재설치 시 덮어써질 수 있다.

세 VM 최종 API 검증은 모두 통과했으며 실제 응답의 C1 공개 투영 schema와 정적 불변조건도 검증했다.

| VM | 실제 OS | QGA | 필수 RPC | 최종 readiness |
|---|---|---|---|---|
| i-2-15-VM | Rocky Linux 10.2 | 10.1.0 | 8/8 ENABLED | TOOLS_REQUIRED |
| i-2-27-VM | Windows Server 2025 | 110.2.3 | 8/8 ENABLED | TOOLS_REQUIRED |
| i-2-28-VM | Ubuntu 26.04 LTS | 10.2.1 | 8/8 ENABLED | TOOLS_REQUIRED |

최종 실제 응답 로그: `live-api-final.jsonl`. 설치된 클래스와 빌드 산출물의 바이트 일치도
관리 서버(api 4/core 3/server 1), 두 호스트 각각(api 4/core 3/KVM 2)에서 통과했다.
최종 로그: `installed-class-checks.txt`, `deploy-ubuntu2604-10.10.31.1.log`, `deploy-ubuntu2604-10.10.31.3.log`.
