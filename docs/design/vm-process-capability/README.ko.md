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

- 기본 설정 `vm.process.capability.enabled=false`.
- 관리자가 `vm.process.capability.test.vm.uuids`에 쉼표로 지정한 VM은 root admin만 시험할 수 있다.
- C1의 논리 권한 `vm.process.read`는 Cloud 동적 역할에서 이 API 명령 이름의 조회 권한으로 관리한다.
- API ACL(ListEntry)과 서비스의 account/domain/project 접근 검사 모두 적용한다. 응답 직전 접근 권한도 재검사한다.
- User VM만 허용하고 비KVM/비Running 상태는 각각 UNSUPPORTED_HYPERVISOR/VM_NOT_RUNNING으로 응답한다.

## 관측과 준비 상태

네트워크 수집기나 네트워크 helper를 호출하지 않는다. 현재 배치 호스트의 Agent로 독립 명령을 보내며
KvmVmOperationGuard의 VM flock/잔여 lease/domain job/block job 확인 및 관측 budget을 사용한다.
보호 경로에는 도메인 이름을 전달하고, lock 안에서 이름이 가리키는 UUID를 요청 VM UUID와 대조한다.
QGA 조회 자체는 UUID로 수행한다. guest-info와 guest-get-osinfo만 사용하며 guest-exec/file/설치는 실행하지 않는다.

필수 RPC 8개의 상태는 ENABLED / DISABLED / UNSUPPORTED / UNKNOWN으로 각각 반환한다.
QGA transport 장애는 QGA_UNREACHABLE, 잘못된 응답이나 보호 경로의 busy/unknown은 CHECK_FAILED다.
호스트 실행 파일 누락은 HOST_TOOL_MISSING이며 RPC 미지원/비활성 및 지원 대상 밖 OS를 별도로 표시한다.
호스트 버전은 aspkg/dpkg-query의 패키지 진단 값으로, source overlay와 프로토콜 호환을 증명하지 않는다.

**현재 C2는 READY를 광고하지 않는다.** Q1 도구가 존재하고 8개 RPC가 활성화되어도
Q4/Q5의 어댑터 탐지와 Q6의 호환·무해 실행/file probe가 완료되기 전에는 TOOLS_REQUIRED다.
`guestAdapterVersion=null`, `supportedSchemaVersions=[]`, `allowedActions=[]`를 유지한다.
이는 Q1의 fail-closed 동작과 일치한다. C2 응답을 근거로 프로세스 변경 작업을 허용하면 안 된다.

## 시간·동시성·stale 방지

캐시 없이 요청마다 새 관측을 수행한다. 응답 ttlseconds=30은 공개 관측의 최대 유효 시간이며
변경 작업은 반드시 다시 확인해야 한다. 관리 서버에서 호스트 ID, VM UUID, update_count 세대를
요청 전후 대조하고 Running 상태·제거 여부를 재검사한다. 응답 requestId/authority 불일치나
10초 초과 응답은 폐기한다. 관측 시각은 관리 서버 시각으로 부여한다.

Management 8개, Agent 8개 동시 관측 상한을 적용하며 대기열을 만들지 않는다.
Agent의 기존 guard는 기본 5초 전체 관측 budget과 제한된 자식 프로세스 admission/회수를 제공한다.
개별 QGA probe 대기는 1.5초이며 wire timeout은 2초다. Agent command wait는 10초다.
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