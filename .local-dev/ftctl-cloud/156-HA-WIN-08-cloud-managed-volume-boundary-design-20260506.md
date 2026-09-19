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

# HA-WIN-08 cloud-managed 볼륨 경계 수정 설계 - 2026-05-06

## 배경

HA-WIN-08 재검증 중 `cloud-managed` 보호 구성에서 Cloud가 생성한 standby volume이 아닌 별도 파일이 복제 대상으로 사용되는 정황이 확인되었다.

확인된 실행 상태:

- Cloud standby root volume 파일: `/var/lib/libvirt/images/4198be90-5992-4cfd-bf27-d5d98cb4f2f6`
- 실제 `qemu-nbd` root 대상: 상대 경로 `4198be90-5992-4cfd-bf27-d5d98cb4f2f6`
- 실제 파일 위치: `/root/4198be90-5992-4cfd-bf27-d5d98cb4f2f6`
- Cloud standby data volume 파일: `/var/lib/libvirt/images/284fac92-bf38-47ec-a0c4-2d566b4bf29f`
- 실제 data 복제 대상: `/var/lib/libvirt/images/w22-02/sdb-d0393f8f-b2fb-40b4-8dd3-4489ce688f00.raw`

즉, Cloud DB/VM에는 Cloud volume이 연결되어 있지만 실제 blockcopy 데이터는 qemu/ftctl이 만든 별도 파일로 들어갈 수 있는 상태다. 이 상태에서는 failover 후 standby VM이 Cloud volume을 부팅 디스크로 사용하므로 복제 데이터와 VM 디스크가 분리된다.

## 원인

1. `remote-nbd` 경로에서 `FTCTL_PROFILE_DISK_MAP` 대상이 없거나 `auto`인 경우 qemu/ftctl이 `FTCTL_PROFILE_SECONDARY_TARGET_DIR/<vm>/...` 아래에 fallback 파일을 만든다.
2. `cloud-managed`인데 disk map 값이 절대 경로가 아닌 상대 경로로 들어오면 qemu/ftctl이 현재 작업 디렉터리 기준 파일을 생성한다.
3. Cloud가 추정한 disk target과 실제 libvirt target이 다르면, 해당 target은 disk map에서 누락된 것으로 처리되고 qemu/ftctl fallback이 작동한다. 예: profile은 `vdb`, 실제 blockcopy target은 `sdb`.
4. API/UI 응답의 disk map 표시가 Cloud volume의 원시 `path`를 그대로 보여줄 수 있어 runtime에서 사용해야 하는 절대 경로와 화면 표시가 어긋날 수 있다.

## 수정 원칙

- `cloud-managed`에서는 Cloud가 생성한 VM/volume만 보호 대상 자원이다.
- qemu/ftctl은 Cloud가 지정한 대상 경로에 데이터 전송만 수행한다.
- qemu/ftctl은 `cloud-managed` 상태에서 복제 대상 파일명을 새로 결정하거나 fallback 파일을 생성하지 않는다.
- disk target 불일치 또는 disk map 누락은 자동 보정하지 않고 등록/동기화 단계에서 실패시킨다.

## qemu 수정 방향

- `FTCTL_PROFILE_PROVISIONING_BACKEND=cloud-managed`이면 `FTCTL_PROFILE_DISK_MAP=auto`를 금지한다.
- `cloud-managed`에서는 각 실제 libvirt disk target에 대해 명시적인 disk map entry가 반드시 있어야 한다.
- `remote-nbd` 대상 경로가 상대 경로이면 실패시킨다.
- missing target일 때 `FTCTL_PROFILE_SECONDARY_TARGET_DIR/<vm>/...` fallback을 수행하지 않는다.
- 실패 메시지는 운영자가 원인을 바로 볼 수 있도록 다음 형태로 명확히 낸다.
  - `cloud-managed requires an explicit FTCTL_PROFILE_DISK_MAP`
  - `cloud-managed missing destination mapping for disk target <target>`
  - `cloud-managed disk target <target> must use an absolute Cloud-managed path`

## Cloud 수정 방향

- cloud-managed provisioning context의 disk map이 비어 있거나 `auto`이면 profile sync 전에 실패시킨다.
- `remote-nbd` + `cloud-managed` disk map 값은 절대 경로만 허용한다.
- filesystem/local storage 대상은 `storage_pool.path + "/" + volume.path`로 해석한 절대 경로를 runtime disk map으로 사용한다.
- RBD 대상은 `/dev/rbd/<pool>/<image>` 형식으로 해석한다.
- API/UI 응답의 `diskmap`, `secondarytargetdisk`, secondary volume path도 runtime에서 쓰는 절대 경로 기준으로 표시한다.
- 보호 대상 primary volume 수와 disk map entry 수가 다르면 등록을 실패시킨다.

## 검증 계획

- qemu selftest:
  - cloud-managed + remote-nbd + `disk_map=auto` 실패 확인
  - cloud-managed + remote-nbd + missing target 실패 확인
  - cloud-managed + remote-nbd + 상대 경로 실패 확인
  - 명시 절대 경로 disk map은 통과 확인
- Cloud unit test:
  - filesystem target disk map이 절대 경로로 생성되는지 확인
  - cloud-managed profile sync에서 상대 경로 disk map이 거부되는지 확인
  - API/UI 응답 disk map이 storage pool path 기준 절대 경로로 표시되는지 확인

## 기대 결과

- Cloud-managed 보호 등록 시 standby VM/volume과 실제 복제 대상이 항상 같은 Cloud 자원을 가리킨다.
- target name 추정 오류가 발생해도 qemu가 별도 파일을 만들지 않고 즉시 실패한다.
- 운영자는 UI/API에서 실제 사용 중인 standby volume 경로를 확인할 수 있다.
