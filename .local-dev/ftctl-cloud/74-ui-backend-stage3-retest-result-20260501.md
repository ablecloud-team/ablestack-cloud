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

# FTCTL UI/백엔드 3단계 재시험 결과 - 2026-05-01

## 목적

`72-cloud-managed-disk-target-and-visibility-fix-20260501.md` 반영 빌드 배포 후, 이전 3단계 실패 원인인 FTCTL disk map의 `vda/vdb` 생성 문제가 해결됐는지 확인했다.

## 실행 조건

- 실행 시각: 2026-05-01 20:23 KST
- 실행 위치: WSL
- 관리 UI: `http://10.10.22.10:8080/client`
- 테스트 VM: `r9-01`
- 테스트 VM UUID: `6573a23d-e128-4992-b17d-d8445fd3137c`
- primary host: `ablecube22-3`
- peer host: `ablecube22-1`
- mode: `HA`
- backend mode: `shared-blockcopy`
- fencing policy: `manual-block`
- provisioning backend: `cloud-managed`
- standby VM name: `r9-01-standby`
- 결과 디렉터리: `.local-dev/ftctl-cloud/artifacts/ui-backend-e2e/stage3-20260501-202323`

## 실행 결과

- 대상 테스트: `ui/tests/e2e/specs/ftctl-ui-register-save.spec.js`
- 결과: 성공
- 테스트 수: 1개
- 소요 시간: 46.2초
- Playwright 결과:
  - `registerFtctlProtection` 응답 검증 성공
  - `enabled=true`
  - `mode=ha`
  - `backendmode=shared-blockcopy`
  - `provisioningbackend=cloud-managed`
  - `fencingpolicy=manual-block`
  - `lasterror` 비어 있음
  - `protectionstate`가 `error`가 아님
  - `transportstate`가 `failed`가 아님
  - 장애보호 탭의 보호 상세, 점검, 상태, 이벤트 섹션 표시 확인

## DB 검증 결과

생성된 활성 보호 row:

- `ftctl_protection.id`: `7`
- `primary_vm_id`: `281`
- `secondary_vm_id`: `301`
- `secondary_vm_name`: `r9-01-standby`
- `peer_host_id`: `1`
- `target_storage_pool_id`: `1`
- `mode`: `ha`
- `backend_mode`: `shared-blockcopy`
- `provisioning_backend`: `cloud-managed`
- `provisioning_state`: `Ready`
- `removed`: `NULL`

생성된 보호 볼륨 row:

- root:
  - `ftctl_protection_volume.id`: `11`
  - `primary_volume_id`: `292`
  - `secondary_volume_id`: `324`
  - `primary_disk_path`: `r9-01-disk0`
  - `secondary_disk_path`: `78ac88a8-ca4b-4535-8c0a-e1f6b24ea18b`
  - `disk_label`: `root-0`
- data:
  - `ftctl_protection_volume.id`: `12`
  - `primary_volume_id`: `293`
  - `secondary_volume_id`: `325`
  - `primary_disk_path`: `r9-01-disk1`
  - `secondary_disk_path`: `c0ddea5c-fec3-442a-803f-0d61e65bd60e`
  - `disk_label`: `data-1`

Cloud-managed standby 객체:

- standby VM:
  - `vm_instance.id`: `301`
  - `instance_name`: `i-2-301-VM`
  - `display_name`: `r9-01-standby`
  - `state`: `Stopped`
  - `removed`: `NULL`
- standby root volume:
  - `volumes.id`: `324`
  - `name`: `r9-01-standby-root`
  - `path`: `78ac88a8-ca4b-4535-8c0a-e1f6b24ea18b`
  - `state`: `Ready`
  - `instance_id`: `301`
- standby data volume:
  - `volumes.id`: `325`
  - `name`: `r9-01-standby-data-1`
  - `path`: `c0ddea5c-fec3-442a-803f-0d61e65bd60e`
  - `state`: `Ready`
  - `instance_id`: `301`

`vm_instance_details`의 FTCTL cache:

- `ftctl.enabled=true`
- `ftctl.mode=ha`
- `ftctl.backend.mode=shared-blockcopy`
- `ftctl.provisioning.backend=cloud-managed`
- `ftctl.provisioning.state=Ready`
- `ftctl.peer.host.id=1`
- `ftctl.secondary.vm.name=r9-01-standby`
- `ftctl.target.storage.pool.name=Primary Storage Glue RBD`
- `ftctl.last.protection.state=syncing`
- `ftctl.last.transport.state=copying`
- `ftctl.last.fencing.state=clear`
- `ftctl.last.error`는 비어 있음

## FTCTL runtime 검증 결과

primary host `10.10.22.3`에서 profile 생성이 확인됐다.

```json
{
  "command": "config.profile-show",
  "result": "ok",
  "vm": "r9-01",
  "mode": "ha",
  "peer_uri": "qemu+ssh://10.10.22.1/system",
  "disk_map": "sda=78ac88a8-ca4b-4535-8c0a-e1f6b24ea18b;sdb=c0ddea5c-fec3-442a-803f-0d61e65bd60e",
  "backend_mode": "shared-blockcopy",
  "target_storage_scope": "shared",
  "secondary_vm_name": "r9-01-standby",
  "fencing_policy": "manual-block"
}
```

상태 조회 결과:

- `active_side=primary`
- `protection_state=syncing`
- `transport_state=copying`
- `fencing_state=clear`
- `admin_state=active`
- `last_error` 비어 있음
- `standby_state=prepared-transient`

이벤트 확인:

- `profile.validate`: `ok`
- `inventory.disks`: `ok`, count `2`
- `blockcopy.start`: `ok`, target `sda`
- `blockcopy.start`: `ok`, target `sdb`
- `standby.materialize`: `ok`
- `standby.prepare`: `ok`
- `verify.vm`: `ok`

## QMP block job 확인

등록 직후 FTCTL 이벤트에는 `sda`, `sdb` 대상 `blockcopy.start`가 남았다.

사후 현재 QMP 조회 결과:

```json
{
  "return": []
}
```

즉, `query-block-jobs` 기준 현재 잔여 block job은 없다.

## `inventory_result=warn`, `peer_rc=1` 확인

이 값은 4단계 DB 검증 항목이 아니라 FTCTL runtime 검증 중 `check --vm r9-01 --json`에서 확인된 peer-side inventory 경고다.

FTCTL 코드 기준:

- `check`는 `ftctl_inventory_check_vm`을 호출한다.
- local probe는 `FTCTL_PROFILE_PRIMARY_URI`에서 `dominfo r9-01`을 실행한다.
- peer probe는 `FTCTL_PROFILE_SECONDARY_URI`에서 동일하게 `dominfo r9-01`을 실행한다.
- peer probe가 실패하면 `primary_rc=0`, `peer_rc=1`, `inventory_result=warn`이 된다.

현장 확인 결과:

- primary host `10.10.22.3`에서 `qemu+ssh://10.10.22.1/system` 접속 자체는 성공했다.
- peer libvirt 목록에는 `scvm`, `ccvm`, `i-2-287-VM`, `i-2-297-VM`만 존재했다.
- peer에서 다음 도메인은 모두 존재하지 않았다.
  - `r9-01`
  - `r9-01-standby`
  - `i-2-301-VM`
- primary `r9-01`의 `dominfo` 기준 `Persistent: no`였다.
- FTCTL `standby.prepare` 구현은 primary persistence가 `yes`가 아니면 standby XML만 생성하고 `standby_state=prepared-transient`로 기록한 뒤, peer에 `define`을 수행하지 않는다.

따라서 이번 `peer_rc=1`은 peer SSH/libvirt 연결 장애가 아니라, 현재 보호 등록 직후 peer에 조회 가능한 standby domain이 정의되어 있지 않아서 발생한 경고로 판단한다.

현재 3단계 성공 판정에는 영향을 주지 않는다. 다만 후속 runtime 기준을 엄격히 하려면 다음 중 하나를 결정해야 한다.

- transient standby 설계에서는 `check`의 peer inventory warn을 허용한다.
- 또는 `check`가 primary VM 이름이 아니라 `secondary_vm_name`을 기준으로 peer를 조회하도록 보정한다.
- 또는 Cloud-managed standby VM을 보호 등록 시점에 peer libvirt에 persistent define하도록 동작을 변경한다.

## 결론

3단계 보호 설정 저장 통합 테스트는 성공했다.

이전 실패 원인인 `vda/vdb` disk map 생성 문제는 재현되지 않았다. 최신 재시험에서는 FTCTL profile의 disk map이 실제 primary VM disk target인 `sda`, `sdb` 기준으로 생성됐고, 각 target에 standby volume path가 정상 매핑됐다.

현재 보호 상태는 활성 상태로 남아 있다. 다음 시나리오를 동일 VM 기준으로 이어서 수행하지 않을 경우, 사후 정리 전에 현재 상태를 추가 분석하거나 필요한 로그를 먼저 수집해야 한다.
