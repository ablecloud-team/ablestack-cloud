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

# v2k 이관 디스크 드라이버 계약

관련 도구 설계: [qemu-exec-tools #89](https://github.com/ablecloud-team/ablestack-qemu-exec-tools/issues/89). 이번 구현은 v2k에 적용하며 n2k 적용은 후속 범위다. [도구 PR #88](https://github.com/ablecloud-team/ablestack-qemu-exec-tools/pull/88)과 함께 사용한다.

## API와 실행 순서

| API | 권한 및 입력 | 보장 범위 |
|---|---|---|
| `getMigrationDiskControllerCapabilities` | Root admin, storageid, 선택 hostid, machine=q35, firmware=bios/efi | 풀에 연결된 현재 Up/Enabled KVM 호스트의 domcapabilities와 실제 QEMU 장치 모델 확인 |
| `verifyMigratedVirtualMachine` | Root admin, id, planhash, 선택 controllerplan(최대 65536자) | 정지 VM의 실제 볼륨 결합 또는 실행 VM의 실제 XML 검증 |

1. v2k가 소스 장치 키/주소/ROOT와 요청 대상 정책을 확정하고 버전 1 계획의 SHA-256을 생성한다.
2. 대상 기능을 확인한 후 실제 VMware CBT/base/incremental/final 전송과 게스트 준비를 수행한다.
3. `deployVirtualMachineForVolume(startvm=false)`의 details로 계획, ROOT/DATA 버스, q35, BIOS/UEFI를 전달한다. Windows의 기존 q35 호환 프로파일은 원본 게스트 계열에 따라 v2k가 최초 요청에 명시한다.
4. 모든 DATA 연결 후 검증 API가 실제 DB의 볼륨 UUID, 역할, device ID, 크기, 풀, 경로, 포맷을 계획과 연결한다.
5. 공통 시작 경로가 저장된 결합과 현재 DB를 비교한다. 선택된 실제 Agent도 버전과 계획 해시를 확인해야 한다.
6. Agent가 XML 변환 훅 이후와 libvirt define 정규화 이후에 검사한다. 게스트를 실행하기 전에 디스크 수, ROOT 순서, 머신/펌웨어, 파일/RBD 식별자, 포맷, 버스, 드라이브 주소에 연결된 실제 컨트롤러 모델이 일치해야 한다.
7. 시작 이후 검증 API가 실제 실행 호스트의 XML을 조회하고 `running-xml` 증거를 저장한다. 이미지 파일은 읽지 않는다.

정지 Cloud VM의 libvirt 도메인이 아직 존재한다고 가정하지 않는다. 정규화 전의 별도 메모리 풍선 `<devices>` 구역은 합친 뒤 검사한다. metadata 아래의 동명 요소는 실제 장치 목록으로 보지 않는다. 사용되지 않는 VirtIO 컨트롤러 하나를 추가해 실제 LSI 컨트롤러를 숨기는 XML도 거부한다.

## 저장 및 변경 방지

기존 `vm_instance_details(vm_id,name,value,display)`에 다음 숨김 키를 저장한다. `value`는 기존 5120자 컬럼을 사용하고 값은 4000자씩 나눈다. 테이블/컬럼 추가나 DB 마이그레이션은 없다.

- `migration.disk.controller.plan`: 버전이 있는 정규화 계획 및 해시
- `migration.disk.controller.bindings`: 실제 ROOT/DATA 볼륨 UUID와 파일/풀/포맷 결합
- `migration.disk.controller.evidence`: 마지막 실행 XML 관측

일반 VM 세부 정보 업데이트는 기존 숨김/표시 상태와 무관하게 이 네임스페이스를 보존한다. 일반 metadata API 및 cleanupdetails를 통한 변경/삭제는 거부한다. 계획 없이 결합만 남아 있는 VM은 시작하지 않는다.

선택 controllerplan의 복구는 Root admin, 정지 상태, 기존 결합, #87의 아직 첫 기동하지 않은 strict import 볼륨, 동일한 해시/결합이 모두 성립할 때만 허용한다. 실행 후 증거를 새 첫 기동 승인으로 바꾸는 우회 경로가 아니다.

## 정책과 검증 한계

원본 IDE/SATA/SCSI 정보와 선택된 대상 SATA/AHCI 또는 VirtIO SCSI를 구분한다. 자동 정책의 IDE/SATA는 SATA, SCSI는 VirtIO SCSI다. 혼합 DATA는 동일한 VirtIO SCSI로 정규화한다. v1의 preserve는 검증된 원본 SATA만 허용한다. 이 계약은 모든 VMware/Nutanix 버전과 임의 컨트롤러의 원형 복원을 보장하지 않는다.

#87의 기본 falloc 및 writeback, 실제 GFS2 import 증거, 최초 기동 차단을 유지한다. 실행 이후에는 예약 extent를 다시 고정하거나 실행 중 이미지를 offline 검사하지 않는다. 게스트 OS 부팅/파일 해시/I/O 검증 결과와 XML 계약의 성공은 별도로 기록한다.

## 자동 검증

`MigrationDiskControllerPolicyTest`, `MigrationDiskControllerManagerImplTest`, `MigrationDiskControllerGuardTest`가 정규화 해시, 다수 디스크 장치명, 청크 저장, ROOT/볼륨 교체, 구형 Agent, 계획 유실, 모델/경로/포맷 변조, XML 외부 참조와 다중 장치 구역을 검증한다. 기존 volume import 계약 CI에도 세 테스트를 포함한다. 실제 VMware 6대 및 GFS2 검증 결과는 도구 저장소의 검증 문서에 기록한다.
