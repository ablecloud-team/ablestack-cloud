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

# Cloud-managed transient standby check/status 검토 - 2026-05-01

## 배경

3단계 보호 설정 저장 통합 테스트는 성공했다. 다만 사후 FTCTL runtime 확인에서 다음 값이 확인됐다.

```json
{
  "command": "check",
  "vm": "r9-01",
  "result": "ok",
  "inventory_result": "warn",
  "primary_rc": 0,
  "peer_rc": 1
}
```

추가 운영 확인:

- `r9-01-standby` VM과 볼륨은 Cloud 관리 UI에서 확인된다.
- 생성된 standby VM은 `Stopped` 상태다.
- ablestack-cloud의 KVM VM은 기본적으로 transient VM이다.

## 현재 동작 분석

FTCTL `check`는 현재 `ftctl_inventory_check_vm`을 통해 다음을 확인한다.

- primary URI에서 `virsh dominfo <primary-vm-name>`
- peer URI에서 `virsh dominfo <primary-vm-name>`

이번 테스트에서는 primary host에서 `r9-01` 조회는 성공했지만, peer host에서 `r9-01` 조회는 실패했다. 그래서 `primary_rc=0`, `peer_rc=1`, `inventory_result=warn`이 됐다.

그러나 Cloud-managed standby VM은 Cloud DB/UI에는 존재하더라도 `Stopped` 상태이고, Cloud의 기본 VM 모델이 transient이므로 stopped VM이 peer libvirt에 persistent domain으로 존재하지 않는 것이 정상이다.

또한 현재 FTCTL `standby.prepare` 구현은 primary persistence가 `yes`가 아니면 peer에 `virsh define`을 수행하지 않고 `standby_state=prepared-transient`로 기록한다.

따라서 이번 `peer_rc=1`은 peer SSH/libvirt 장애를 의미하지 않는다. Cloud-managed transient standby 구조에서 peer libvirt에 조회 가능한 standby domain이 아직 없기 때문에 발생한 값이다.

## 판단

3단계 성공 판정은 유지한다.

다만 `inventory_result=warn`은 Cloud-managed transient standby 상태를 표현하기에는 부정확하다. 현재 값은 운영자가 peer 장애로 오해할 수 있다.

4단계 DB 검증과는 별개로, FTCTL runtime 계약 보강이 필요하다.

## 필요한 변경 방향

### 1. FTCTL check 의미 보정

`check`는 peer에서 primary VM 이름을 조회하는 단일 기준을 버려야 한다.

권장 동작:

- primary domain 조회는 계속 primary VM 이름으로 수행한다.
- peer 연결성은 domain 존재 여부와 분리해 확인한다.
- standby domain 조회가 필요한 경우 primary VM 이름이 아니라 `secondary_vm_name` 또는 Cloud가 전달한 standby instance name을 사용한다.
- `cloud-managed` + `prepared-transient` + standby VM `Stopped` 조합에서는 peer domain 미존재를 `warn`으로 보지 않는다.
- 이 경우 별도 필드로 `standby_domain_state=not-defined-expected` 또는 `standby_domain_expected=false` 같은 값을 반환한다.

### 2. FTCTL profile/schema 보강

기존 `14-cloud-managed-provider-contract-20260430.md`의 방향대로 FTCTL profile에 다음 정보가 필요하다.

- `provisioning_backend`
- `secondary_vm_id`
- `secondary_vm_uuid`
- `secondary_vm_instance_name`
- volume mapping 상세

현재 `profile-show` 응답에는 `backend_mode`, `target_storage_scope`, `secondary_vm_name`은 있지만 `provisioning_backend`와 Cloud standby VM 식별자가 없다. Cloud는 DB에는 값을 저장하고 `FtctlActionCommand` context에는 `ftctl.provisioning.backend`를 넣지만, FTCTL profile/runtime 계약에는 충분히 반영되지 않았다.

### 3. status JSON 보강

`status`의 `protection_state=syncing`, `transport_state=copying`, `standby_state=prepared-transient` 자체는 현재 상태와 맞다.

다만 Cloud-managed 해석을 위해 다음 필드를 추가하는 것이 좋다.

- `provisioning_backend=cloud-managed`
- `standby_domain_state=prepared-transient` 또는 `not-defined-expected`
- `secondary_vm_name`
- `secondary_vm_instance_name`
- `volume_mapping_count`

### 4. Cloud 백엔드의 역할

Cloud의 standby VM/volume 생성 및 DB/UI 표시 동작은 이번 관찰 기준으로 정상이다.

Cloud 백엔드에서 해야 할 일은 FTCTL로 전달하는 profile/context를 보강하고, FTCTL check/status의 새 필드를 UI/API에 노출하는 것이다. Cloud가 단순히 `peer_rc=1`을 숨기는 방식은 권장하지 않는다.

## 결론

`r9-01-standby`가 Cloud UI에 보이고 `Stopped` 상태인 것은 Cloud-managed standby 구조에서 정상이다.

따라서 보호 등록 백엔드 프로비저닝 자체를 바꿀 필요는 없다.

변경이 필요한 부분은 FTCTL runtime 계약이다. 특히 `check`가 peer domain 존재 여부를 Cloud-managed transient standby의 정상/비정상 기준으로 잘못 해석하지 않도록 보정해야 한다.
