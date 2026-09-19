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

# HA-WIN-11 FAILBACK 후속 조치 설계 - reverse sync 실패 및 Stream closed 보강

## 배경

- 대상 테스트: `HA-WIN-11-FAILBACK-ACTION`
- 대상 VM: `w22-02`
- 관찰 결과:
  - Cloud API `failbackFtctlProtection` 응답이 `java.io.IOException: Stream closed`로 종료되었다.
  - FTCTL 이벤트/상태에는 `failback.sync` 실패와 `reverse_sync_timeout`이 기록되었다.
  - reverse NBD export가 primary 측에 잔류했다.
  - standby domain은 Cloud 상태와 libvirt 실제 상태가 어긋난 상태로 관찰되었다.

## 원인 분리

### 1. 실제 업무 실패 원인

`failback-sync` 단계에서 secondary VM의 변경분을 primary 볼륨으로 되돌리는 reverse blockcopy가 제한 시간 안에 `ready` 상태가 되지 못했다.

현재 qemu/ftctl 쪽 실패 처리의 문제점은 다음과 같다.

- `ftctl_blockcopy_wait_reverse_sync_ready`가 실패해도 원인이 `timeout`, `domain_lost`, `blockjob_missing`, `query_failed` 중 무엇인지 명확히 남기지 않는다.
- `failback-sync` 실패 경로에서 primary reverse NBD export 정리가 보장되지 않는다.
- 실패 직전 standby domain 상태, reverse blockjob 상태, NBD export 상태가 구조화된 결과로 남지 않는다.

### 2. Cloud 응답의 `Stream closed`

`Stream closed`는 reverse sync 실패의 직접 원인이 아니라 Cloud KVM agent wrapper의 출력 처리 오류다.

현재 흐름:

- KVM wrapper가 `OutputInterpreter.AllLinesParser`를 사용한다.
- `AllLinesParser.drain()`은 stdout/stderr stream을 별도 task에서 먼저 소비한다.
- ftctl 프로세스가 non-zero exit로 끝나면 `Script.executeInternal`이 같은 process input stream을 다시 `processError()`로 읽는다.
- 이미 닫혔거나 소비된 stream을 다시 읽으면서 `java.io.IOException: Stream closed`가 발생한다.

결과적으로 실제 ftctl 실패 원인인 `reverse_sync_timeout`이 API 응답에서 가려진다.

## 수정 방향

### qemu/ftctl

1. `failback-sync` 실패 경로를 단일 helper로 모은다.
   - `protection_state=error`
   - `transport_state=reverse_sync_failed`
   - `last_error=<구체 원인>`
   - `failback.sync` 이벤트 details에 `reverse_sync=<구체 원인>` 기록

2. reverse sync 실패 시 primary reverse NBD export를 best-effort로 정리한다.
   - 실패 후에도 볼륨을 잡고 있는 qemu-nbd가 남지 않도록 한다.
   - 이후 Cloud-managed stop/cleanup/expunge가 block device holder 때문에 실패하는 것을 줄인다.

3. reverse sync polling 중 standby domain 상태를 함께 확인한다.
   - secondary libvirt에서 active standby domain이 사라지면 `reverse_sync_domain_lost`로 즉시 실패 처리한다.
   - blockjob query 실패와 domain loss를 구분한다.

4. `--json` action 실행 결과를 실패 시에도 출력한다.
   - `command`
   - `result`
   - `vm`
   - `exit_code`
   - `protection_state`
   - `transport_state`
   - `active_side`
   - `last_error`

### cloud

1. KVM wrapper에서 drain된 output을 non-zero exit 경로에서도 보존한다.
   - `processError()`가 이미 닫힌 stream을 다시 읽지 않고, drain된 output을 반환하도록 전용 parser를 사용한다.

2. Cloud API 응답과 Cloud Event에는 Java wrapper 예외보다 ftctl 구조화 결과를 우선 노출한다.
   - 예: `last_error=reverse_sync_timeout`
   - `Stream closed`는 wrapper 내부 로그에만 남아야 하며 운영자가 보는 action 실패 원인을 대체하면 안 된다.

3. timeout/exit-code/output-parse 실패를 구분한다.
   - action answer의 `exitCode`와 raw output을 유지한다.
   - JSON parse 실패 시에도 raw output을 잃지 않는다.

## 기대 결과

- HA-WIN-11 failback 실패 시 API 응답에서 `Stream closed`가 아니라 실제 ftctl 실패 원인이 보인다.
- reverse sync 실패 뒤 primary reverse NBD export가 잔류하지 않는다.
- standby domain이 중간에 사라진 경우 `timeout`으로 뭉개지지 않고 `reverse_sync_domain_lost`로 식별된다.
- 후속 재시도/cleanup 시 디스크 holder 잔류로 인한 부작용이 줄어든다.

