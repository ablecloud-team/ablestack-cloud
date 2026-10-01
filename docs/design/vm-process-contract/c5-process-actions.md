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

# C5: 프로세스 변경 작업과 영속 작업 조회

상위 Epic: ablecloud-team/ablestack-cloud#1170 · 구현 이슈: #1175

## 선행 변경과 활성화

Cloud #1181(C4, `cae147f768b4b26d96c9eb1fcecabeded9060b60`)에서 누적 분기한다.
qemu #73(Q5, `4b59f4125742aa9d347fc668921b2af557f69fa2`) 및
`codex/issue-1175-action-clock-fix`의 `283f85acbb2918b667caedb4c632159b244857f4`가 필요하다.
기존 PR은 병합하지 않았으며 각각 upstream Europa/main을 대상으로 한다.

Global 설정 `vm.process.management.enabled`의 기본값은 **false**다.
관리자가 명시적으로 true로 설정해야 목록과 변경 API를 사용할 수 있다.
이번 변경은 백엔드 C5이며 프로세스 탭/확인 모달은 후속 C6 범위다.

## API와 권한

| API | 종류 | 기본 권한 | 입력 |
| --- | --- | --- | --- |
| terminateVirtualMachineProcess | 비동기 정상 종료 | Admin | virtualmachineid, requestid, snapshotid, pid |
| killVirtualMachineProcess | 비동기 강제 종료 | Admin | 동일 |
| restartVirtualMachineService | 비동기 서비스 재시작 | Admin | 동일 + servicename |
| getVirtualMachineProcessOperation | 작업 조회 | 기존 VM 조회 권한 | virtualmachineid + operationid 또는 requestid 중 하나 |

변경 API 이름별 동적 역할 권한을 독립적으로 부여할 수 있다. API 권한과 별도로
현재 VM ownership의 OperateEntry/ListEntry를 검사한다. 조회 반환 직전에도 권한과
활성화 설정을 재검사한다. requestid 조회는 호출 계정 범위, operationid 조회는 VM 접근 범위다.
requestid/snapshotid는 정규 UUID, pid는 1..4294967295이고 서비스명은 제한된 문자만 허용한다.
임의 명령, 인자, 환경 변수, PID 시작 신원, 서비스 hash를 클라이언트에서 받지 않는다.

1. `refreshVirtualMachineProcesses`의 비동기 완료 후 목록에서 대상 PID/서비스를 선택한다.
2. 10초 이내 서버 snapshotid와 새 requestid로 변경 API를 한 번 호출한다.
3. async-job SUCCEEDED는 VERIFIED 및 TARGET_EXITED/SERVICE_RESTART_VERIFIED 확인을 뜻한다.
4. FAILED/UNKNOWN async-job은 실패로 종료한다. 오류의 operationId 또는 원래 requestid로
   `getVirtualMachineProcessOperation`을 조회한다. UNKNOWN일 때 새 requestid로 재시도하지 않는다.

## 중복 방지와 배치 보호

`vm_process_operation`은 독립 JDBC 트랜잭션으로 **전송 전에 UNKNOWN 예약을 커밋**한다.
`(account_id, request_id)` 고유키는 요청 재사용을 막고, nullable `active_vm_id` 고유키는
한 VM의 미확정 변경을 직렬화한다. 같은 requestid/내용은 저장 결과만 반환하며 다른 내용은
REQUEST_CONFLICT다. UNKNOWN은 예약을 해제하지 않으며 자동 삭제/재전송하지 않는다.
완료 결과는 UNKNOWN에서만 갱신할 수 있어 지연된 응답이 완료 상태를 덮어쓰지 않는다.

전송 직전 Running/host_id/update_count를 VM 행 FOR UPDATE 트랜잭션에서 확인하고
bounded Agent 호출이 끝날 때까지 행 잠금을 유지한다. snapshot은 monotonic 10초 TTL과
동일 배치/세대를 다시 확인한다. Agent는 기존 VM lock과 제한된 프로세스 슬롯을 사용하며,
root 소유의 요청/context, 상속된 잠금 FD, Agent PID/start ticks/boot ID 및 monotonic 만료를
qemu에 전달한다. qemu가 게스트 identity와 서비스 매핑을 다시 검증한다.

Management/Agent 재시작 후에도 영속 requestid 예약은 남는다. 작업 조회는 저장된 배치가
현재와 일치할 때 `operation.get`만 보내 게스트 journal에서 결과를 복구한다.
배치가 변경되었거나 결과를 입증할 수 없으면 UNKNOWN을 유지한다. 배치 변경 후 자동 복구,
미확정 예약의 운영자 해제, journal 보존 정책은 후속 운영 범위이며 임의 DB 삭제로 우회하지 않는다.

## 결과와 감사

Agent boolean 성공만으로 성공 처리하지 않는다. 64KiB 제한, 중복 키/추가 JSON 거부,
operation/request/authority/identity/service 일치와 사후조건을 검증한다.
FAILED/MAY_HAVE_RUN 같은 불확실 조합은 UNKNOWN으로 보존해 예약 해제를 막는다.
게스트 오류의 자유 텍스트는 공개하지 않고 정해진 code/retryMode와 일반 메시지로 바꾼다.
공개 authority에는 vmUuid만 남긴다. Cloud 기본 JSON 직렬화에서는 null이 생략될 수 있다.

VM.PROCESS.TERMINATE/KILL/RESTART 이벤트는 예약 이벤트 외에 RESULT/REPLAY/RECONCILED
완료 이벤트를 기록한다. requestId, operationId, state, effect, postcondition을 남기고
검증된 성공만 INFO, 그 외는 ERROR다. 원시 명령줄/환경 변수/자격 증명은 기록하지 않는다.

## 스키마와 배포

새 설치 `setup/db/create-schema.sql`과 4.22.1→4.23 업그레이드 SQL에 테이블을 추가했다.
기존 테스트 서버의 모듈 패치 배포에는 같은 디렉터리의 `process-operation-schema.sql`을
적용한다. 새 설치 전체 스키마를 실행해서는 안 된다. 이번 실제 검증은 운영자 모듈 패치
경로이며 정식 전체 업그레이드/전체 Cloud 패키지 검증을 대신하지 않는다.

변경 Maven 모듈: api, core, server, plugins/hypervisors/kvm, engine/schema.
WSL ext4에서 해당 모듈만 빌드한다. 전체 Cloud 빌드/배포 및 UI 변경은 하지 않는다.
서버 JAR의 기존 클래스와 Spring 등록을 백업 후 갱신하고 mold/mold-agent를 재시작했다.

## 2026-09-27 통합 검증

관리 서버 10.10.31.10, KVM 10.10.31.1/3에서 Cloud API를 통해 검증했다.

| 대상 | 정상 종료 | 강제 종료 | 서비스 재시작 | OS 직접 확인 |
| --- | --- | --- | --- | --- |
| Rocky Linux 10.2 / i-2-15-VM | 통과 | 통과 | systemd 통과 | 기존 PID 없음, 서비스 PID 28957→29062 |
| Ubuntu 26.04 / i-2-28-VM | 통과 | 통과 | systemd 통과 | 기존 PID 없음, 서비스 PID 25589→25628 |
| Windows Server 2025 / i-2-27-VM | Q5의 지원 범위 밖 | 통과 | SCM 통과 | 기존 PID 없음, 서비스 PID 3272→288 |

모든 변경은 전용 sleep/fixture 서비스에만 실행했다. Rocky 수동 `/etc/sysconfig/qemu-ga`
설정은 보존했고 SELinux Enforcing을 유지했다.

- 같은 requestid 재조회는 같은 operationId/completedAt을 반환했다. 동시 중복은 새 전송 없이 UNKNOWN을 반환했다.
- requestid 내용 충돌, 10초 경과 snapshot, 서비스명 주입, 기본 일반 사용자 3개 변경 API,
  cross-tenant 작업 조회가 차단되었다.
- 응답 유실 검증은 완료된 전용 fixture의 **Cloud 결과 행만 최초 UNKNOWN으로 되돌리는 장애 주입**이었다.
  실제 네트워크 패킷 유실 테스트는 아니다. Management와 Agent를 재시작한 뒤 동일 변경 요청은
  UNKNOWN을 유지했고 읽기 전용 조회가 원래 성공 및 완료 시각 `2026-09-27T12:03:37.617Z`를 복구했다.
- Windows 게스트 시계와 host의 약 16시간 차이로 최초 요청은 실행 전 STALE_SNAPSHOT으로 실패했다.
  게스트 wall clock 비교를 제거하고 Cloud/host monotonic 경계에서 freshness를 확인하는 qemu 보완 후 통과했다.
  게스트 시계나 보안 정책을 바꾸지 않았다.
- qemu GitHub Actions 36317806585: Windows MSI/ISO, Linux helper, EL9/10 정책 빌드/테스트 성공.
  해당 산출물의 helper와 hash를 배포했다. Windows 테스트 fixture EXE는 실행 중이라 이전 동일 fixture를 유지했다.
- 권한 검증용 임시 계정은 삭제 API의 외부 SSO 정리 오류가 있었으나 Cloud DB removed 상태를 확인했다.

실행 증적은 WSL `/home/ablecloud/work/validation/cloud-issue1175/`에 보존한다.
주요 파일: release-build.log, live-api.jsonl, live-ubuntu.jsonl, live-windows.jsonl,
rbac.jsonl, recovery.jsonl, proof-rocky.json, proof-ubuntu.json, proof-windows.json.
소스/PR에는 자격 증명이나 원시 게스트 프로세스 목록을 포함하지 않는다.

### 최종 배포 확인

- 변경 5개 Maven 모듈 빌드 성공. decoder 6, action service 7, snapshot service 8,
  KVM guard 7, 총 28개 관련 테스트 성공. 전체 Cloud 빌드/전체 CI는 실행하지 않았다.
  모듈 빌드에서는 checkstyle/RAT를 생략했으며 `git diff --check`는 통과했다.
- 최종 배포 JAR의 대상 클래스 bytes가 빌드 payload와 모두 일치했다.
- 저장된 성공/실패 요청을 재실행 없이 조회하는 replay 검증에서 각각 Completed INFO/ERROR
  감사 이벤트와 올바른 requestId/operationId를 확인했다 (`audit-live.jsonl`).
- 전용 Linux c5 fixture unit과 Windows AbleProcessQ5Fixture 서비스를 제거했다.
  게스트 journal과 서버 백업/검증 증적은 보존했다.
- 관리/Agent 서비스 active, WEB-INF 보존, `/client/` HTTP 200, 대상 VM 3대 Running,
  미확정 active 예약 0건 및 `vm.process.management.enabled=false`를 확인했다
  (`final-management.jsonl`, `final-host1.jsonl`, `final-host3.jsonl`).
