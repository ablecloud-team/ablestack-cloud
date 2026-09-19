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

# HA-WIN-11 Failback Reverse Sync Size Accounting 보완 설계 - 2026-05-08

## 배경

HA-WIN-11 누적 테스트에서 `ha-w22-01` failback이 `FAILBACK_SYNC` 단계에서 실패했다.

관측 결과:

- 대상 VM: `ha-w22-01`
- Primary runtime domain: `i-2-333-VM`
- Standby runtime domain: `i-2-337-VM`
- Backend: `remote-nbd`
- Target storage scope: `secondary-local`
- Primary root RBD size: `64424509440`
- Standby root qcow2 guest virtual size: `64424509440`
- Standby root qcow2 container file size: `64434601984`
- Reverse blockcopy final progress:
  - `sda offset=64436568064`
  - `sda len=64436572160`
  - `sda ready=false`
  - `sda status=running`
- Cloud state after failure:
  - primary VM: `Stopped`
  - standby VM: Cloud DB/API 기준 `Running`
  - standby domain: peer host libvirt 기준 없음
  - protection: `error/reverse_sync_failed`
  - last error: `reverse_sync_timeout`
- Host runtime leftovers:
  - reverse `qemu-nbd` process remained
  - primary RBD maps remained
  - stale FTCTL lock metadata remained

## 원인 판단

`query-block-jobs`의 `offset/len`은 raw disk의 쓰기 주소 상한이 아니라 QEMU block job의 처리량/총 작업량 카운터다. 따라서 `len > guest virtual size`가 곧바로 raw 대상 디스크에 out-of-range write가 발생했다는 뜻은 아니다.

그러나 `sda len`이 guest virtual size보다 약 11.5 MiB 크게 증가한 것은 정상 완료 조건으로 볼 수 없다. 이 증가는 standby root qcow2 container overhead 규모와 유사하다.

따라서 HA-WIN local qcow2 -> RBD raw failback에서 다음 문제가 확인된다.

1. live reverse blockcopy가 guest-visible virtual disk size만으로 안정화된다고 가정했다.
2. 실제 block job accounting은 qcow2 format/storage layer 및 runtime dirty accounting 영향으로 guest virtual size를 초과할 수 있다.
3. Windows guest가 idle이어도 root disk는 boot/runtime metadata를 갱신할 수 있고, qcow2 container accounting도 같이 반영되어 `ready=true`에 도달하지 못할 수 있다.
4. `ready=true`를 무한히 기다리는 구조는 libvirt monitor lock 장기 점유와 domain crash/state divergence를 유발한다.
5. failback 실패 시 reverse NBD/RBD/lock cleanup이 충분히 강하지 않아 다음 테스트 정합성을 해친다.

## 수정 방향

Cloud-managed failback 순서를 다음과 같이 보강한다.

기존:

1. `FAILBACK_SYNC`
2. Cloud-managed standby stop
3. NIC identity handoff to primary
4. Cloud-managed primary start
5. `FAILBACK_REPROTECT`

변경:

1. `FAILBACK_SYNC`
   - live reverse sync 시작
   - guest virtual size와 target raw size 검증
   - blockjob이 `ready=true`가 되면 기존처럼 sync-ready
   - blockjob이 guest virtual size 이상까지 진행했지만 `ready=false`이면 `reverse_sync_cutback_required`로 성공 반환
   - `len > guest_virtual_size`는 progress JSON과 event에 `virtual_size_exceeded`로 기록
2. Cloud-managed standby stop
3. `FAILBACK_FINALIZE`
   - standby가 반드시 stopped/not-running인지 확인
   - 남은 live reverse block job/NBD를 정리
   - standby qcow2를 source로 primary raw target에 offline finalize 수행
   - remote-nbd 경로에서는 primary host가 raw RBD를 NBD로 export하고 peer host가 `qemu-img convert -f qcow2 -O raw`로 NBD target에 기록
   - source guest virtual size와 primary raw target size가 일치하지 않으면 실패
4. NIC identity handoff to primary
5. Cloud-managed primary start
6. `FAILBACK_REPROTECT`

## 구현 항목

qemu-exec-tools:

- `failback-finalize` CLI 명령 추가
- reverse state record에 `source=standby target`, `dest=primary target`, `format=primary target format` 유지
- reverse sync size guard 추가
  - standby source guest virtual size 측정
  - primary raw target size 측정
  - progress JSON에 `guest_virtual_size`, `target_size`, `virtual_size_exceeded`, `excess_bytes` 추가
- `ftctl_blockcopy_wait_reverse_sync_ready`에서 cutback 가능 상태를 성공 코드로 구분
- `ftctl_failback_sync_for_cloud_cutback`은 `reverse_sync_cutback_required`를 성공으로 반환
- `ftctl_failback_finalize_after_secondary_stop` 추가
  - secondary domain stopped/not-found 확인
  - live reverse jobs/NBD cleanup
  - remote qcow2 -> primary raw NBD offline convert
  - cleanup 후 `transport_state=cutback_ready`

cloud:

- `FtctlActionCommand.Action.FAILBACK_FINALIZE` 추가
- cloud-managed failback sequence에 `FAILBACK_FINALIZE`를 standby stop 후 primary start 전에 삽입
- failback finalize event를 FTCTL state update로 기록

검증:

- qemu selftest에 reverse size guard 및 finalize 명령 smoke test 추가
- cloud ftctl-service Maven module test/build는 WSL ext4 clone에서 수행
- qemu package build는 GitHub Actions로 수행

## 기대 효과

- qcow2 local standby -> raw RBD primary failback에서 guest virtual size 기준으로 최종 raw 디스크를 확정한다.
- `len > virtual size` 상황을 운영자가 원인 식별 가능한 상태/이벤트로 확인할 수 있다.
- standby stop 이후 primary start 전 offline finalize가 수행되어, primary가 미완성 raw disk로 시작되는 것을 방지한다.
- 실패 시 reverse NBD/RBD/lock 잔여물로 인한 다음 테스트 오염을 줄인다.
