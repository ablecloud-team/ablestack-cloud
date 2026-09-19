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

# HA-WIN RBD parent/thin provisioning 개선 설계

## 배경

HA-WIN RBD -> Local Storage 경로에서 primary RBD는 raw 블록 디바이스이지만 RBD 특성상 thin provisioned 이미지이다. 현재 blockcopy 또는 failback finalize 과정에서 60 GiB virtual size 전체가 RBD에 물리 할당되는 현상이 확인되었다. 실제 guest 데이터가 약 11 GiB 수준이어도 QEMU blockcopy/qemu-img convert가 zero 영역까지 실제 write로 처리하면 RBD allocated size가 virtual size에 가까워진다.

또한 현재 Windows primary root RBD는 parent image가 있는 clone 상태이다. parent-backed RBD와 thin 보존은 반드시 함께 처리해야 한다. parent가 있는 RBD에 sparse write를 수행하면 write하지 않은 영역이 zero가 아니라 parent image에서 읽힐 수 있다. 따라서 parent-backed primary RBD에 바로 sparse failback을 수행하면 데이터 정합성이 깨질 수 있다.

## 원인

1. RBD raw target은 thin이지만, full-range write가 발생하면 전체 extents가 allocated 상태가 된다.
2. `qemu-img convert -O raw` 또는 live blockcopy가 zero 영역을 skip하지 않으면 guest used size와 관계없이 virtual size 전체가 쓰인다.
3. parent-backed RBD에서는 sparse write를 안전하게 적용할 수 없다. 쓰지 않은 영역이 parent image로 fallback되기 때문이다.
4. local standby qcow2와 primary RBD raw 사이의 failback은 format 변환뿐 아니라 parent 제거, zero/discard 처리, sparse convert 정책이 함께 맞아야 한다.

## 개선 원칙

1. 데이터 정합성을 thin 보존보다 우선한다.
2. parent-backed RBD는 보호 설정 시점에 flatten한다.
3. parentless RBD에 대해서만 sparse write/discard 기반 thin 보존을 허용한다.
4. thin 보존이 불가능한 환경에서는 silent full allocation을 허용하지 않고 WARN/event를 남긴다.

## 보호 설정 시점 처리

primary RBD가 parent-backed clone이면 보호 설정 전에 다음 단계를 수행한다.

1. `rbd info --format json`으로 parent 존재 여부를 확인한다.
2. parent가 있으면 `rbd flatten <pool>/<image>`를 수행한다.
3. flatten 완료 후 `rbd info --format json`으로 parent가 제거되었는지 검증한다.
4. flatten으로 증가한 allocated size를 줄이기 위해 `rbd sparsify <pool>/<image>`를 수행한다.
5. `rbd du --format json`으로 before/after allocated bytes를 기록한다.
6. Cloud Event와 protection runtime/status에 flatten/sparsify 결과를 남긴다.

이 처리는 보호 설정 전에 1회 수행한다. failback 시점에 parent 문제를 뒤늦게 처리하면 이미 standby와 primary의 의미가 달라진 상태일 수 있으므로 늦다.

## failback finalize 처리

cloud-managed failback에서 standby가 정지된 뒤 primary를 시작하기 전에 offline finalize를 수행한다. thin 보존 조건을 만족하는 경우 다음 절차를 사용한다.

1. primary RBD가 parentless인지 검증한다.
2. primary VM과 standby VM이 모두 target disk를 쓰고 있지 않은지 확인한다.
3. target RBD를 discard 가능한 all-zero logical state로 준비한다.
4. standby qcow2를 primary RBD NBD target으로 sparse convert한다.

권장 명령:

```bash
qemu-img convert -p -n -S 4k -f qcow2 -O raw <standby-qcow2> <primary-rbd-nbd>
```

5. convert 후 `rbd sparsify <pool>/<image>`를 수행해 남은 zero extents를 회수한다.
6. `rbd du` 결과를 수집해 allocated bytes가 virtual size 전체로 증가하지 않았는지 확인한다.

주의할 점은 target discard이다. parent-backed RBD에 discard를 수행하면 parent data가 다시 노출될 수 있으므로 금지한다. discard/sparse finalize는 parentless 검증 후에만 허용한다.

## forward blockcopy 처리

initial sync에서 local standby qcow2를 만들 때는 sparse 상태가 유지되어야 한다.

1. standby qcow2는 `qemu-img create -f qcow2`로 preallocation 없이 생성한다.
2. remote NBD export가 discard/zero unmap을 지원하면 활성화한다.
3. libvirt blockcopy destination XML에서 discard/detect-zeroes 전달을 지원하면 활성화한다.

후보 XML:

```xml
<driver name='qemu' type='raw' discard='unmap' detect_zeroes='unmap'/>
```

후보 qemu-nbd 옵션:

```bash
qemu-nbd --discard=unmap --detect-zeroes=unmap ...
```

단, libvirt와 qemu-nbd 버전에 따라 지원 여부가 다르므로 런타임 probe 후 적용한다. 지원하지 않으면 기존 방식으로 진행하되 `thin_preserve=unsupported` WARN/event를 남긴다.

## 구현 항목

qemu-exec-tools:

- RBD parent 검출 함수 추가: `rbd info --format json`.
- RBD allocated/provisioned bytes 수집 함수 추가: `rbd du --format json`.
- 보호 설정 전 parent-backed primary RBD flatten/sparsify 단계 추가.
- reverse finalize 전 parentless 검증과 discard 준비 단계 추가.
- `qemu-img convert`에 sparse threshold 옵션 `-S 4k` 적용.
- convert 후 `rbd sparsify` 수행 및 결과 기록.
- qemu-nbd/libvirt XML discard 지원 probe 추가.
- thin 보존 불가 시 WARN과 명확한 fallback reason 기록.

cloud:

- protection runtime/status에 parent flatten 상태, thin 보존 상태, allocated bytes before/after를 반영.
- Cloud Event에 `flatten_started`, `flatten_completed`, `sparsify_completed`, `thin_preserve_disabled`, `thin_preserve_fallback_full_write` 기록.
- UI 장애 보호 탭에 parent/thin 관련 상태와 allocated/provisioned 비율 표시.
- 보호 설정 API 응답에 parent flatten/thin preserve 결과를 포함한다.

## 검증 기준

1. parent-backed primary RBD 보호 설정 후 `rbd info`에서 parent가 없어야 한다.
2. flatten 후 `rbd sparsify`가 실행되고 `rbd du` 결과가 event/status에 기록되어야 한다.
3. initial sync 및 failback 후 primary RBD allocated bytes가 virtual size 전체로 증가하지 않아야 한다.
4. guest 부팅 및 데이터 정합성 검증이 PASS여야 한다.
5. sparse/discard 지원이 없는 환경에서는 WARN/event가 남아야 하며 silent full allocation은 실패로 본다.

