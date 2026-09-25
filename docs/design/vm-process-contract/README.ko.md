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

# VM 프로세스 관리 공통 계약 1.0

- 상태: **C1 설계 계약 확정**. 이 문서는 후속 구현의 규범이며 구현 완료/지원 OS 런타임 검증 선언이 아니다.
- Epic: https://github.com/ablecloud-team/ablestack-cloud/issues/1170
- C1: https://github.com/ablecloud-team/ablestack-cloud/issues/1171
- 기준: Cloud Europa `ba42c72e72372d83cce7101ad09127d44ab327f1`, qemu main `6c440e61ec80108ec9d59ef21b095d53bc9cf539`.
- 단일 원본: Cloud의 이 디렉터리. qemu는 Cloud **불변 commit SHA**와 이 디렉터리의 파일을 함께 고정하여 소비한다. 별도 schema를 손으로 복제·변경하지 않는다.
- MUST/금지/필수는 구현 요구사항이다. 시간·크기 상한은 1.0 기본 계약이며 큰 값으로 바꾸려면 양쪽 계약 버전을 갱신한다.

## 1. 경계와 제공 인터페이스

Cloud UI → Cloud API/Management → 현재 VM host의 Mold Agent → vm_exec → QGA → 고정 guest process adapter 순서로 실행한다. UI는 libvirt/호스트/helper를 직접 호출하지 않는다.

| 계층 | 소유 책임 |
|---|---|
| Cloud API/server | tenant/project/domain 접근, 동적 역할, VM 상태/배치 권위, 요청 ID, 영속 작업 및 감사, ISO mapping |
| KVM agent | 현재 domain UUID/배치 재검증, VM guard 획득, admission, 고정 CLI 호출, 출력/시간 상한 |
| qemu host vm_exec | RPC 실행/poll/인코딩, 프로토콜 버전, QGA exec PID, 게스트 deadline 및 결과 조회 |
| guest adapter | OS 검증, process identity, handle 기반 action, 서비스 제어, guest journal과 사후조건 |
| UI | 준비/실패/이전 관측 상태 표시, 제한된 refresh, 확인 모달과 async-job 표시 |

Cloud API 이름은 다음으로 확정한다. 기존 Cloud 응답 wrapper 내부에 이 계약 envelope를 `processstate` 필드로 전달한다. 공통 schema는 내부 envelope만 검증한다. UI 공개 투영에서는 `authority.hostUuid`와 `placementGeneration`을 제외한다. UI에 호스트 선택/내부 세대 입력을 요구하지 않는다.

| API | 동작 | 역할 permission |
|---|---|---|
| getVirtualMachineProcessCapabilities | 현재 readiness 또는 TTL 내 snapshot | vm.process.read |
| listVirtualMachineProcesses | snapshot의 필터/정렬/page | vm.process.read |
| refreshVirtualMachineProcesses | 제한된 새 process.list 수집, async job | vm.process.read |
| terminateVirtualMachineProcess | mode=normal/force → terminate/kill, async job | vm.process.terminate / vm.process.kill |
| restartVirtualMachineService | 명시 service target, async job | vm.process.service.restart |
| getVirtualMachineProcessOperation | operationId의 영속 상태/read-only reconcile | 원 VM 조회 권한 + 본인 작업 또는 관리자 |

UI mutation 입력: virtualmachineid, requestid(UUID), snapshotid, processidentity, 그리고 종료 mode 또는 servicetarget.
host UUID, 배치 세대, operationId, budget은 Cloud가 부여한다. 전체 command/script/파일 경로 입력은 허용하지 않는다.
ISO 연결은 기존 attachIso API 및 그 권한을 별도로 사용한다. 시스템 VM/비KVM/Running 이외 상태는 MVP에서 거부한다.

### CLI와 QGA 계약

추가 CLI:
`vm_exec --process-protocol 1.0 --request-json <private-file>`.
stdin/파일의 envelope는 readRequest 또는 actionRequest이며 host가 임의 command 문자열을 조립하지 않는다.
파일은 소유자만 읽는 0600 regular file, symlink 금지, 최대 64 KiB, 작업 후 정리한다.
JSON은 UTF-8·단일 객체·중복 키 금지·NaN/Infinity 금지이며 stdout에는 envelope 1개만 출력한다.
stderr는 정제된 진단(최대 4 KiB)으로 제한하고 command line/environment/secret을 출력하지 않는다.

고정 read operation: capability.get / process.list / operation.get.
고정 mutation: process.terminate / process.kill / service.restart.
게스트 helper는 동일 envelope에서 authority를 host의 증명으로 간주하지 않고 아래 guest journal/epoch 규칙을 적용한다.
`operation.get`은 기록만 조회하며 action을 재실행하지 않는다. 데이터 전송에 guest-file-*가 필요하면 사전 지정 helper 설치 경로 및 작업별 임시 경로만 사용하고 handle은 finally에서 닫는다.

프로토콜 응답 stdout을 완성하면 exit 0(업무 실패 포함), 잘못된 CLI/입력은 exit 2, envelope조차 생성 못한 transport/internal 실패는 exit 3이다.
guestExitCode는 QGA가 실행한 adapter의 종료 코드이며 호스트 exit와 다르다.
guestExecPid는 QGA 실행 프로세스의 PID이며 종료 대상 identity.pid로 사용하지 않는다.
프로세스 CLI는 기존 vm_exec 호출 모드와 분리하여 하위 호환한다.

## 2. 버전 협상과 schema 사용

`schemaVersion="1.0"`은 소수 숫자가 아닌 문자열이다. capability의 supportedSchemaVersions와 양쪽 allowlist의 교집합 중 최고 호환 버전을 선택한다.
처음에는 1.0만 지원하며 fallback은 없다. 협상은 side effect 없이 수행한다.
알 수 없는 major/minor/operation/입력 필드는 **실행 전 거부**한다. 실패 응답은 baseline 1.0 failure/UNSUPPORTED_VERSION으로 반환한다.
추가 버전은 해당 schema와 검증기를 먼저 배포하고 양측이 지원한다고 광고한 뒤 사용한다. 잘못된 JSON/버전은 무해하게 거부하며 guest-exec 시험 실행으로 협상하지 않는다.
hostToolsVersion/guestAdapterVersion은 진단 문자열, supportedSchemaVersions가 기계적 호환 판정이다.

`schema.json`은 Draft 2020-12 구조 제약, `verify.py`는 cross-field 정적 제약의 규범적 보완이다.
둘 다 충족해야 하며 schema-only 검증만으로 계약 준수를 주장하지 않는다.
실시간 배치·PID 재사용·RBAC·deadline·journal 원자성은 정적 검사로 증명할 수 없으므로 후속 runtime gate를 반드시 실행한다.

## 3. QGA 준비 상태와 설치

필수 RPC: guest-exec, guest-exec-status, guest-file-open, guest-file-close, guest-file-read,
guest-file-write, guest-file-seek, guest-file-flush.

각 RPC를 ENABLED / DISABLED / UNSUPPORTED / UNKNOWN으로 분류한다.
guest-info.supported_commands에 없으면 UNSUPPORTED, enabled=false면 DISABLED,
RPC 목록을 얻지 못하면 UNKNOWN이다. guest-info는 판정 수단이며 위 8개 목록과 별도로 요구한다.
QGA 버전 문자열·설치 마커·패키지 성공 코드로 RPC 상태를 대체하지 않는다.

readiness 우선순위:
UNSUPPORTED_HYPERVISOR → VM_NOT_RUNNING → HOST_TOOL_MISSING → QGA_UNREACHABLE →
CHECK_FAILED(응답 파싱/guest-info 확인 불가) → RPC_UNSUPPORTED → RPC_DISABLED →
UNSUPPORTED_OS → TOOLS_REQUIRED(adapter 설치/호환/OS 실행 정책 미충족) → READY.
여러 원인이 있어도 우선순위로 단일 readiness를 선택하고 명령별 rpcs는 가능한 근거를 유지한다.
프로토콜 버전 불일치는 readiness가 아닌 failure/UNSUPPORTED_VERSION이다.
READY는 8개 RPC 모두 ENABLED, OS allowlist 및 호스트 도구·guest adapter 호환, 제한된 무해 실행/file probe 성공을 요구한다.
probe는 readiness 갱신 시에만 실행하며 기존 사용자 파일에 접근하지 않는다. cap TTL은 30초이고 mutation 직전 현재 RPC/adapter 가용성을 재검증한다.
readiness가 READY여도 protected process와 미지원 OS action은 allowedActions에서 제외한다.

Linux ISO 정책은 기존 FULL 명령과 하위 호환하되 신규 `process-management` install/repair 모드는
8개 필수 RPC와 guest-info/guest-ping/guest-get-osinfo 등 실제 필요한 조회 RPC만 기존 허용 정책에 추가한다.
불필요한 RPC를 일괄 개방하지 않는다. QGA vendor의 allow/block 인자 지원 차이는 Q2에서 번역한다.
기존 사용자 제한 정책 변경 내역/백업/복구 결과를 기록하고 서비스 재시작 후 actual capability를 재조회한다.
Windows도 Q3에서 동일한 설치 결과 계약을 구현한다. OS 정책 때문에 실행 불가하면 READY로 만들지 않는다.

Cloud는 관리자 지정 zone/OS/arch/ISO ID/version/checksum으로 Ready ISO를 선택한다.
attachIso 완료는 설치 완료가 아니다. 권한이 없으면 콘솔에서 Linux root/Windows 관리자 설치를 안내한다.
이미 실행 가능할 때의 자동 설치와 VM 재부팅/오프라인 주입은 별도 확장이다.
installer가 cloud-init/Cloudbase-init/Sysprep·네트워크·자격증명을 재설정하지 않는 repair 모드를 제공해야 한다.
helper가 없는 기존 VM도 RPC 가능 여부는 표시할 수 있지만 프로세스 기능 READY에는 adapter 계약 충족이 필요하다.

## 4. OS 및 실행 환경 지원 매트릭스

아래는 **1.0 구현 대상으로 확정한 매트릭스**이며 실제 제품 지원 승인은 Q6/C7 실증 후에만 부여한다.
검증 전 전역 기능 플래그는 기본 off, 관리자 test allowlist에서만 활성화한다.
QGA의 특정 최소 버전만으로 승인하지 않는다. 8개 RPC+guest-info와 package hash별 probe 결과가 필수 gate다.
QGA/Tools 실제 검증 버전·ISO SHA는 Q6 보고서에 채워야 하며 아직 검증 완료 항목은 없다.

| 게스트 | 아키텍처 | adapter 필수 환경 | 조회 | 정상 종료 | 강제 종료 | 재시작 |
|---|---|---|---|---|---|---|
| Rocky Linux 9.6/9.7/9.8 및 10.2 | x86_64 | Python >=3.9, /proc, systemd, pidfd_open + pidfd_send_signal | 지원 목표 | TERM | KILL | systemd 서비스 |
| Ubuntu 22.04/24.04 | x86_64 | Python >=3.9, /proc, systemd, pidfd_open + pidfd_send_signal | 지원 목표 | TERM | KILL | systemd 서비스 |
| Windows Server 2022/2025 | x86_64 | Windows PowerShell 5.1, CIM, .NET/native handle helper, SCM | 지원 목표 | 미지원 | TerminateProcess | SCM 서비스 |

Rocky minor별 ISO는 일치하는 것을 사용한다. ARM/32-bit/다른 OS/컨테이너 init/비systemd 환경은 1.0 밖이다.
pidfd나 Windows process handle 신원 보장을 확보하지 못하면 조회만 가능하고 mutation은 UNSUPPORTED_ACTION이다.
Windows에서 taskkill /F를 정상 종료라고 표시하지 않는다. 서비스 제어가 필요한 경우 service.restart만 제공한다.
Linux CPU는 두 sample 사이 process CPU time / wall interval ×100(한 코어 100%, 다중 코어 100% 초과 가능),
Windows도 같은 의미로 정규화한다. 최초 sample/재부팅/카운터 감소/관측 누락은 null이다.
memoryBytes는 Linux RSS / Windows WorkingSetSize. owner는 접근 불가 시 null, PID/start identity 불확실한 행은 action 금지.

## 5. identity와 snapshot

identity = vmUuid + bootId + pid + startTicks.
모든 UUID는 lowercase canonical이다. PID는 양의 32-bit unsigned 범위, 세대/ticks는 20자리 이하 십진 **문자열**이다.
JSON/JavaScript의 53-bit 정밀도 한계 때문에 큰 tick을 숫자로 전달하지 않는다.

- Linux bootId: `linux:<kernel boot_id UUID>`; startTicks: /proc/PID/stat field 22 (clock ticks since boot).
- Windows bootId: `windows:<Win32_OperatingSystem.LastBootUpTime UTC converted to FILETIME ticks>`;
  startTicks: GetProcessTimes creation FILETIME tick. CIM/handle 시각 변환 단위를 일치시킨다.
- clone/복구 VM의 Cloud UUID도 반드시 비교한다. 부팅 세대 확인 불가/OS 시간 근거 불일치 시 mutation 금지.
- Linux는 대상 pidfd를 먼저 열고 해당 PID의 start tick을 재검증한 뒤 pidfd_send_signal한다.
  pidfd 획득 후 기존 프로세스가 종료/재사용되면 새 PID로 신호를 보내지 않는다.
- Windows는 핸들을 열고 GetProcessTimes로 identity를 검증한 같은 핸들로 종료한다.
  PID만 다시 열거나 taskkill PID 문자열을 쓰는 경로는 허용하지 않는다.

snapshot은 10초 TTL, 최대 10,000행/UTF-8 wire 1 MiB(먼저 도달하는 상한), snapshotId·observedAt·expiresAt·truncated를 가진다.
수집 중 일부 누락은 PARTIAL, 크기 때문에 잘리면 PARTIAL+truncated=true, 전체 수 모르면 totalKnown=null.
PARTIAL에서 신원이 검증된 행의 action은 허용할 수 있으나 각 행의 allowedActions와 실행 직전 재검증이 필수다.
같은 snapshot의 모든 행은 동일 VM/boot이며 identity 중복을 허용하지 않는다.
Cloud page/filter/sort는 해당 고정 snapshot 안에서 수행하며 page당 최대 200행이다.
TTL 초과 mutation은 STALE_SNAPSHOT, UI는 새 목록에서 다시 확인한다. 관측 TTL은 Cloud 시각 기준이며 guest clock을 신뢰하지 않는다.
snapshot을 영속 장기 이력으로 저장하지 않는다. action 대상 identity/서비스/감사에 필요한 최소 정보만 보존한다.

## 6. mutation과 사후조건

service target = manager + name + configurationHash.
hash는 guest adapter가 canonical service 설정(실행 파일/argv, 실행 계정, dependency, stop/start 가능 여부)을
키를 Unicode code point 순으로 정렬한 UTF-8 JSON으로 직렬화하여 SHA256한 값이다.
공백 없는 쉼표/콜론 구분, 비ASCII 문자 그대로, BOM/끝 개행 없음, null·배열 순서 보존,
설정 숫자 값은 정규화한 십진 문자열로 변환한다. 비밀 원문은 반환하지 않는다.
실행 직전 같은 정규화 규칙으로 비교한다. 서비스 설정 변경/삭제/공유 프로세스 영향 불명은 STALE_IDENTITY/UNSUPPORTED_ACTION이다.

| action | 사전조건 | SUCCEEDED 사후조건 |
|---|---|---|
| process.terminate | Linux + identity + 보호 대상 아님 | 해당 identity 종료 확인(TARGET_EXITED) |
| process.kill | 안전한 OS handle + identity + 보호 대상 아님 | 해당 identity 종료 확인(TARGET_EXITED) |
| service.restart | identity가 선택 service에 매핑, hash 일치, 제어 가능 | service stop/start 수행 증거 + running/active + 새 실행 세대 확인(SERVICE_RESTART_VERIFIED) |

QGA/helper 자신, Linux PID1, Windows 핵심/protected 프로세스는 기본 denylist다.
서비스 공유 PID는 서비스 이름으로만 제어한다. 핵심/종속 서비스 영향은 adapter가 판정하고
안전한 범위를 설명할 수 없으면 거부한다. QGA 서비스를 중단시키는 restart도 거부한다.
서비스가 원래 inactive이거나 중간에 identity가 바뀌면 '재시작'으로 추정 실행하지 않는다.
Linux oneshot/inactive 및 신뢰할 새 generation을 얻지 못하는 서비스는 MVP restart에서 제외한다.
일반 프로세스의 재시작은 지원하지 않는다. Q7/C8에서 승인된 executable/argv/cwd/user/environment 참조가 있는
등록 profile로만 확장하며 관측 command line을 재실행하지 않는다.

신호 전송 성공은 완료가 아니다. deadline까지 종료 확인이 없으면 UNKNOWN이며 자동으로 KILL로 승격하지 않는다.
kill 직전 대상이 이미 종료되어 handle 검증을 못 하면 STALE_IDENTITY; 호출 후 목표 identity 종료를 확인하면 성공이다.
감독자가 새 프로세스를 띄워도 기존 identity 종료와 신규 프로세스를 구분한다.
service running만으로 restart 성공을 판단하지 않는다. durable stop/start 증거와 서비스 실행 세대가 필요하다.

## 7. 영속성·중복 방지·상태 머신

Cloud unique key = (accountId, vmUuid, requestId). operationId는 최초 수락 시 발급한 전역 UUID.
권한 확인 후 requestId를 예약하고 정규화 payload digest(action/identity/service/snapshot)를 저장한다.
같은 ID+동일 payload는 기존 operation 반환, payload 다르면 REQUEST_CONFLICT.
DB 트랜잭션을 RPC 대기 동안 유지하지 않는다. 단일 VM mutation reservation을 별도로 유지한다.

상태 전이: ACCEPTED → RUNNING → SUCCEEDED | FAILED | UNKNOWN.
ACCEPTED에서 사전 검증 실패는 FAILED. UNKNOWN은 **관측으로만** SUCCEEDED/FAILED로 확정하며 증거 없으면 그대로 남긴다.
RUNNING/UNKNOWN 효과는 MAY_HAVE_RUN, SUCCEEDED 효과는 VERIFIED,
FAILED의 effect는 실행 전 거부면 NOT_STARTED, 실행 후 확정 실패면 MAY_HAVE_RUN이다.
일반 failure envelope는 접수/dispatch 전 거부 또는 read 오류다.
dispatch 여부 불명 mutation은 반드시 operationId를 가진 actionResult/UNKNOWN으로 기록한다.

guest journal은 (vmUuid, operationId)를 키로 requestId/payload digest/bootId/상태/사후조건을 보존한다.
실제 side effect **전에** 예약 레코드를 atomic write+fsync하고 guest VM별 mutation mutex를 획득한다.
DISPATCHED 표시도 side effect 전에 durable하게 쓴다.
표시 후 crash한 창에서는 실행 여부를 모를 수 있으므로 재실행하지 않고 UNKNOWN으로 반환한다.
이 계약은 exactly-once 성공 보장이 아니라 **중복 실행 금지와 불명 상태 보존**이다.
서비스 stop/start는 같은 journal에 단계별 기록하며 중간 crash 후 자동으로 다음 단계를 재개하지 않는다.
helper 업그레이드는 journal을 보존한다. terminal 결과 30일, request tombstone 90일, 미해결 UNKNOWN은 기간 삭제 금지.
Cloud도 동일 기간을 유지하고 24시간 초과 최초 요청은 수락하지 않는다.
오래된 request replay/tombstone은 '없는 작업'으로 새 실행하지 않는다.

operation.get은 operationId로 기존 기록만 조회한다. NOT_FOUND는 '미실행' 증거가 아니다.
management/agent/host/guest 재시작 후 Cloud는 guest 기록과 동일 boot의 process/service 사후조건을 조회한다.
단순 QGA exec-status 없음이나 서비스 active만으로 결과를 확정하지 않는다.
guest reboot/rollback으로 journal 증거가 사라지면 UNKNOWN을 유지하고 관리자가 영향 확인 후 별도 새 request를 명시 제출한다.
신규 요청도 기존 UNKNOWN reservation을 운영자 조정 없이 우회하지 않는다.
거부/조정도 감사하고 원래 작업을 성공으로 고쳐 쓰지 않는다.

## 8. 호스트 이동 fencing과 잠금

현재 KvmVmOperationGuard는 VM UUID별 flock과 30초 lease(5초 갱신)를 사용하고,
hangctl도 같은 lock/잔존 lease를 보고 동작을 멈춘다. **이는 host-local이며 distributed fencing이 아니다.**

1. Cloud VM mutation reservation → 짧은 DB 트랜잭션으로 placementGeneration/host/상태 확인.
2. lifecycle 작업(이동/중지/복구)도 같은 reservation gate에 참여해야 한다. mutation dispatch 동안 lifecycle 전이를 예약하지 못한다.
3. agent admission → host VM flock → libvirt domain UUID/Running/Cloud authority 최신 확인 → guest VM mutex 순서.
   같은 guard 안에서 libvirt domain job/block job 충돌도 확인한다. ACTIVE 또는 확인 불가는 BUSY로 닫는다.
4. agent는 deadline 만료/authority 재검증 불가 시 실행하지 않는다.
   hostUuid+placementGeneration은 관측/요청별 권위이며 다음 작업의 고정 배치 정보로 재사용하지 않는다.
5. guest journal에 최근 승인된 placementGeneration을 보존하여 낮은 세대 요청을 거부한다.
   이것만으로 지연된 구 host 요청을 차단할 수 없으므로 Cloud lifecycle gate 및 agent의 실행 직전 검증이 함께 필수다.
6. 외부 virsh/강제 이동 등 Cloud 밖 변화 또는 authority 확인 유실은 UNKNOWN/STALE_AUTHORITY로 닫고
   mutation을 새 host로 재전송하지 않는다. 이 fencing을 확보하지 못한 환경은 조회만 허용한다.

lock 경로: /run/ablestack-vm-operations/locks/<vmUuid>.lock.
VM lock 파일은 unlink하지 않는다. root-owned 0700 parent, regular no-symlink lock 검증.
Cloud Java agent가 획득한 flock을 vm_exec가 독립 FD로 다시 획득하면 자기 교착이 된다.
**단일 소유자**를 유지하고 guest 호출 동안 agent가 guard를 보유한다.
Cloud 전용 vm_exec entrypoint는 private request 파일과 부모 생존 채널로 agent 소유 컨텍스트를 받으며 독립 host flock을 다시 잡지 않는다.
환경 변수만으로 lock 우회를 허용하지 않는다. standalone invocation은 자기 flock을 획득하고 Cloud 관리 VM의 mutation은 거부한다.
guest mutex와 host admission도 hold 시간에 포함하고, guest callback은 Cloud로 재진입하지 않는다.

호스트/게스트 결과 불명은 기존 guard.uncertain()을 사용해 lease를 보존한다.
TTL이 지났다고 lock/lease를 삭제하지 않는다. hangctl은 잔존 파일을 ACTIVE/UNKNOWN 보호로 유지한다.
reconciliation은 동일 flock 안에서 owner PID+start/host boot/Cloud operation·guest journal을 확인하고
더 이상 실행 중인 작업/자식이 없고 사후조건 또는 미실행이 확인된 경우에만 자기 operation lease를 제거한다.
monitoring entrypoint는 잔존 lease가 있으면 skip하므로 전용 제한된 reconcile 경로가 필요하다.
expired lease 자동 삭제/무조건 kill은 금지하며 미해결은 관리자 조정 대상으로 남긴다.

## 9. 실행 예산·자원 상한

| 항목 | 기본값/상한 |
|---|---|
| capability/readiness 관측 TTL | 30초 |
| process snapshot TTL | 10초 |
| Cloud 요청 전체(read) | 10초 |
| host 관측 guard 내부 budget | 5초(기존 guard), 나머지는 routing/queue |
| 정상/강제 종료 전체 | 15초 |
| 서비스 restart 전체 | 90초 |
| 개별 QGA RPC | min(3초, 남은 예산) |
| Cloud→agent 운송 여유 | 5초, guest 실행 예산을 늘리지 않음 |
| host read admission | 기존 monitoring 8개 상한 공유 |
| host mutation admission | 최대 4개, VM당 1개 |
| guest exec-status poll | 250ms→500ms→최대1초 |
| UI polling | 활성 탭에서 최소5초, 새 수집 VM당 최소5초 |
| request/output | 64KiB / 1MiB, stderr 4KiB, 최대10,000 process |
| mutation queue | VM당 미해결1개, 새 요청은 BUSY |

budgetMs는 전송/대기마다 경과 시간을 차감하여 전달하는 상대 budget이다.
벽시계는 감사용 UTC Z이고 deadline은 monotonic clock으로 강제한다.
guest helper가 자기 deadline을 적용해야 하며 host subprocess kill이 guest 작업 중단임을 의미하지 않는다.
이미 signal을 보낸 뒤 deadline 경과는 read-only reconcile 대상이다.
1MiB는 stream 읽는 동안 강제한다. 종료 후 파일 크기만 확인하여 디스크를 무제한 사용하는 경로는 보완해야 한다.
출력이 잘리면 malformed JSON을 내보내지 말고 완결된 PARTIAL snapshot 또는 OUTPUT_LIMIT failure로 반환한다.
uninterruptible child가 남으면 slot을 반환하지 않는다. UI/job timeout이 slot을 새로 만들게 하지 않는다.

## 10. 오류·보안·감사 계약

| 코드 | 의미 | 자동 처리 |
|---|---|---|
| UNSUPPORTED_VERSION | 협상/adapter 버전 불일치 | 실행 금지, 도구 갱신 |
| RPC_DISABLED / RPC_UNSUPPORTED | 비활성 / 빌드 미지원 | ISO 정책 복구 / QGA 업데이트 |
| QGA_UNREACHABLE / CHECK_FAILED | 통신 / 확인 실패 | 제한된 read 재시도 |
| TOOLS_REQUIRED / HOST_TOOL_MISSING | guest adapter / host 도구 부족 | 해당 위치 설치 |
| PERMISSION_DENIED | Cloud RBAC 또는 guest OS 거부 | 권한 복구, 자동 변경 재시도 금지 |
| BUSY | lock·admission·미해결 작업 | 상태 조회만 |
| STALE_AUTHORITY / STALE_IDENTITY / STALE_SNAPSHOT | host/VM/target/목록이 바뀜 | 새 관측 후 사용자 재확인 |
| PROTECTED_TARGET / UNSUPPORTED_ACTION | 보호 대상 / OS action 불가 | 실행 금지 |
| REQUEST_CONFLICT | ID 재사용+다른 payload | 기존 작업 조회 |
| OUTPUT_LIMIT / EXEC_FAILED | 출력 상한 / 확정 실행 실패 | 실패 표시, 임의 mutation replay 금지 |
| DEADLINE_EXCEEDED / RESULT_UNKNOWN | mutation 효과 불명 | UNKNOWN+READ_ONLY |
| NOT_FOUND | 조회할 작업 기록 없음 | 미실행으로 추론하지 않음 |

read timeout은 QGA_UNREACHABLE/CHECK_FAILED로 반환한다.
UNSUPPORTED_OS/UNSUPPORTED_HYPERVISOR/VM_NOT_RUNNING은 readiness 단계에서 구분한다.
retryMode는 NONE/READ_ONLY/NEW_REQUEST_AFTER_REVALIDATION이며 마지막 값도 사용자 재확인 없는 자동 변경 재시도를 허용하지 않는다.
UNKNOWN은 READ_ONLY만 허용한다. UI는 결과 불명을 성공 토스트나 재시도 버튼으로 숨기지 않는다.

Cloud 서버는 모든 조회/변경/operation.get에 소유권과 현재 역할을 재검증한다.
owner/account/project 변경 후 이전 사용자에게 기록을 노출하지 않는다.
감사: actor/VM/requestId/operationId/action/identity/service/시간/상태/errorCode·명시 조정 이벤트.
command line/environment/secret 원문을 저장하지 않는다. 오류 메시지는 제어문자 제거·512자 이하.
QGA는 guest root/SYSTEM 권한 경로이므로 API에 arbitrary shell 기능을 우회 노출하지 않는다.

## 11. fixture 실행과 양 저장소 소비

Python 3.9+와 requirements.txt의 jsonschema를 사용한다. runtime command/네트워크/VM에 접근하지 않는다.

```sh
python3 -m venv /tmp/vm-process-contract-venv
/tmp/vm-process-contract-venv/bin/pip install -r docs/design/vm-process-contract/requirements.txt
/tmp/vm-process-contract-venv/bin/python docs/design/vm-process-contract/verify.py
/tmp/vm-process-contract-venv/bin/python docs/design/vm-process-contract/verify.py --file producer-envelope.json
```

qemu 개발자는 WSL ext4의 Cloud clone에서 게시된 불변 commit을 fetch한 뒤 다음으로 동일 원본을 추출한다.
`<C1_COMMIT>`은 C1 완료 보고의 SHA를 사용하며 branch/latest로 대체하지 않는다.

```sh
git -C /home/ablecloud/work/dhslove/ablestack-cloud archive <C1_COMMIT> docs/design/vm-process-contract > /tmp/c1-contract.tar
mkdir -p /home/ablecloud/work/contracts/c1
tar -xf /tmp/c1-contract.tar -C /home/ablecloud/work/contracts/c1
```

두 consumer는 같은 schema/fixture/validator checksum을 CI 로그에 기록한다.
Cloud는 agent DTO 직렬화 결과, qemu는 adapter/CLI 결과를 --file로 검증한다.
fixtures.json의 valid=false는 **형식/정적 불변조건 위반** 예시이며 실행하지 않는다.
failure-STALE_* 예시는 wire 응답 예시일 뿐 실제 경쟁 상태 테스트가 통과했다는 뜻이 아니다.
현재 파일은 QGA 버전/도구 버전에 fixture-only 값을 포함하므로 설치 승인의 증거로 쓰지 않는다.

## 12. 소유 이슈와 runtime gate

| 후속 이슈 | 이 계약의 적용 영역 | 필수 runtime 증거 |
|---|---|---|
| qemu #60 (Q1) | CLI/버전/deadline/출력 | clean RPM/DEB, argv/Unicode/timeout |
| qemu #61/#62 (Q2/Q3) | ISO/RPC/adapter 준비 | 실제 8개 RPC·기존 VM 설정 보존 |
| Cloud #1172 (C2) | readiness API | false READY·cross-tenant 차단 |
| qemu #63 (Q4), Cloud #1174 (C4) | snapshot/identity | OS 도구 대조·PARTIAL·stale/page |
| Cloud #1173 (C3) | ISO handoff | 연결≠설치, 설치 후 probe |
| qemu #64 (Q5), Cloud #1175 (C5) | action/journal/lock/fencing | PID 재사용·이동·중복·crash·UNKNOWN |
| Cloud #1176 (C6) | UI | KeepAlive/VM 전환·권한·job 결과 |
| qemu #65 (Q6), Cloud #1177 (C7) | package/E2E | OS 매트릭스·artifact SHA·회귀 |
| qemu #66 (Q7), Cloud #1178 (C8) | 후속 profile | 승인된 일반 프로세스 재시작 |

C1 완료는 규범 문서·schema·양 consumer 공용 fixtures·validator의 게시 및 정적 검증이다.
실제 API/VM 동작·guest journal·권한 설정·lifecycle gate 추가는 후속 이슈이며 C1에서 구현하지 않는다.
서비스 재시작은 MVP, 일반 프로세스 재시작은 후속으로 고정한다.

### 2026-09-25 지원 목표 추가

사용자 승인으로 Rocky Linux 10.2 x86_64를 지원 목표에 추가했다. 지정 VM의 QGA 8개 RPC와 Q1 transport 실행은 확인했으나 프로세스 어댑터·pidfd·서비스 변경의 전체 지원 승인은 Q6/C7 gate에 남아 있다. C2는 10.2에서도 어댑터 검증 전 TOOLS_REQUIRED를 반환하며 READY를 광고하지 않는다. JSON 계약 구조와 버전 1.0은 변경하지 않는다.
