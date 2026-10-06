<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# VM 스냅샷과 1차 스토리지 할당량 개선 방향

## 조사 결과와 한계

사용자가 지적한 값은 계정의 Resource Limit이 아니라 **1차 스토리지 목록·상세의 할당량**이다. 최신 upstream Europa `b31026f919856a1b61c1f86dca450e16ac0673e1`에서 KVM 내부 VM 스냅샷에 원본 볼륨의 논리 크기를 반복 가산하는 경로를 확인했다.

31번 클러스터 Primary는 KVM / SharedMountPoint / CLUSTER 범위이며 상세 화면에서 아래 값이 표시되었다 (2026-10-03 관찰).

| 항목 | 화면/API 원시 값 | 표시 |
|---|---:|---|
| 전체 크기 | 7,999,462,260,736 bytes | 7,450.08 GiB, overprovisioning 1.0 |
| 할당된 크기 | 6,052,431,595,232 bytes | 75.66% |
| 사용된 크기 | 725,021,159,424 bytes | 9.06% |

이 차이 전체를 VM 스냅샷 때문이라고 단정하지 않는다. Thin 볼륨의 정상적인 논리 할당과 물리 사용 차이, 템플릿, 예약도 포함된다. 이번 조사는 최신 소스와 화면의 읽기 검토이다. 배포 JAR과 upstream의 동일성, DB의 볼륨별 chain 값, 호스트 물리 크기, 생성 전후 증분은 아직 검증하지 않았다. 실제 스냅샷 생성·복원·삭제나 DB 수정은 수행하지 않았다.

## 소스에서 확인한 계산 경로

모든 링크는 검토한 upstream commit에 고정한다.

1. [DefaultVMSnapshotStrategy.java:378](https://github.com/ablecloud-team/ablestack-cloud/blob/b31026f919856a1b61c1f86dca450e16ac0673e1/engine/storage/snapshot/src/main/java/org/apache/cloudstack/storage/vmsnapshot/DefaultVMSnapshotStrategy.java#L378)에서 `volumeVO.getSize()`를 읽는다. `finalizeCreate`는 `vm_snapshot_chain_size += volumeSize`, `finalizeDelete`는 현재 볼륨 크기를 차감한다. 실제 COW 증분 측정값이 아니다.
2. [LibvirtCreateVMSnapshotCommandWrapper.java:50](https://github.com/ablecloud-team/ablestack-cloud/blob/b31026f919856a1b61c1f86dca450e16ac0673e1/plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtCreateVMSnapshotCommandWrapper.java#L50)는 libvirt internal memory snapshot을 만들고 전달받은 VolumeTO 목록을 그대로 반환한다. 이 답변에는 새 물리 사용량 측정 단계가 없다.
3. [VolumeDaoImpl.java:453](https://github.com/ablecloud-team/ablestack-cloud/blob/b31026f919856a1b61c1f86dca450e16ac0673e1/engine/schema/src/main/java/com/cloud/storage/dao/VolumeDaoImpl.java#L453)는 해당 풀의 `volumes.vm_snapshot_chain_size`를 합산한다.
4. [CapacityManagerImpl.java:599](https://github.com/ablecloud-team/ablestack-cloud/blob/b31026f919856a1b61c1f86dca450e16ac0673e1/server/src/main/java/com/cloud/capacity/CapacityManagerImpl.java#L599)는 원본 볼륨 크기·extra bytes에 이 합계를 더한다. 템플릿 등의 기존 항목도 별도로 포함한다.
5. [AlertManagerImpl.java:357](https://github.com/ablecloud-team/ablestack-cloud/blob/b31026f919856a1b61c1f86dca450e16ac0673e1/server/src/main/java/com/cloud/alert/AlertManagerImpl.java#L357)의 storage capacity 재계산이 이 값을 저장한다. [StoragePoolJoinDaoImpl.java:151](https://github.com/ablecloud-team/ablestack-cloud/blob/b31026f919856a1b61c1f86dca450e16ac0673e1/server/src/main/java/com/cloud/api/query/dao/StoragePoolJoinDaoImpl.java#L151)는 usedCapacity + reservedCapacity를 `disksizeallocated`로, provider의 usedBytes를 `disksizeused`로 반환한다.
6. [StorageManagerImpl.java:3578](https://github.com/ablecloud-team/ablestack-cloud/blob/b31026f919856a1b61c1f86dca450e16ac0673e1/server/src/main/java/com/cloud/storage/StorageManagerImpl.java#L3578)의 storagePoolHasEnoughSpace도 같은 할당 계산을 사용한다. 따라서 화면 숫자만의 문제가 아니라 배치 가능 공간·경보에 영향을 준다.
7. 새 upstream #1215의 [VmStorageSelectionManager.java:129](https://github.com/ablecloud-team/ablestack-cloud/blob/b31026f919856a1b61c1f86dca450e16ac0673e1/server/src/main/java/com/cloud/storage/VmStorageSelectionManager.java#L129)도 같은 계산으로 listDeploymentStoragePools의 할당/가용량을 만든다. VM 생성·볼륨 생성/연결의 스토리지 선택 용량 표시를 함께 회귀한다.

예를 들어 100 GiB 원본 볼륨에서 이 경로로 스냅샷 3개를 만들면, 다른 항목을 제외한 계산은 원본 100 + chain 300 = 400 GiB가 된다. 이는 소스 수식의 예시이며 31번 실측값이 아니다.

이 결과는 **default strategy의 내부 스냅샷 경로**에 한정한다. Disk 전용 외부 스냅샷의 KvmFileBasedStorageVmSnapshotStrategy는 별도의 actual size / SnapshotDataStoreVO.physicalSize 경로가 있다. managed storage는 CapacityManagerImpl의 provider getUsedBytes 분기를 사용한다. 이를 모두 같은 방식으로 0으로 바꾸지 않는다.

## 용량의 의미와 목표 계약

| 값 | 의미 | 개선 원칙 |
|---|---|---|
| 논리 할당량 | 볼륨에 제공한 논리 크기와 기존 템플릿/명시적 할당 항목 | 동일한 내부 COW 스냅샷마다 원본 볼륨 크기를 다시 더하지 않는다 |
| 실제 풀 사용량 | 스토리지가 보고하는 소비 공간 | COW 보존 블록, 저장된 메모리 상태, qcow2 메타데이터를 포함한 provider/풀 통계를 사용한다 |
| 추가 예약량 | 작업 중 필요한 공간과 향후 증가에 대한 명시적 정책 | 목적·기간·해제 조건을 갖는 예약으로 분리한다. 원본 크기 × 스냅샷 수를 실제 소비량으로 표시하지 않는다 |
| 개별 스냅샷 물리 크기/회수량 | 공유 블록을 고려한 별도 추정 또는 측정 | 미측정은 알 수 없음으로 처리한다. VM RAM 크기나 volumeSize를 회수 가능량으로 표시하지 않는다 |

qcow2 내부 스냅샷은 기존 블록을 공유하고 이후 쓰기에서 COW를 수행한다. 메모리 포함 스냅샷은 VM state를 저장하므로 생성 직후에도 공간을 사용한다. 스냅샷이 항상 0바이트라는 전제는 옳지 않다. 개별 스냅샷 삭제의 회수량은 공유 블록 때문에 단순 합산으로 구할 수 없다. 근거: [QEMU qcow2 format](https://www.qemu.org/docs/master/interop/qcow2.html#snapshots), [QEMU VM snapshots](https://www.qemu.org/docs/master/system/images.html#vm-snapshots).

풀의 실제 사용 통계에 이미 반영된 스냅샷 데이터를 물리 사용량에 다시 더하지 않는다. 논리 할당 모델 변경과 물리 여유 공간 보호를 함께 검증한다.

## 구현 방향 (S7)

1. **경로별 계약 확정**: KVM/SharedMountPoint 내부 DiskAndMemory를 첫 적용 대상으로 삼고, Disk 외부 체인·RBD·managed·다른 하이퍼바이저의 현재 의미를 표로 구분한다. capability/strategy에 따라 적용하여 무조건 전체 pool 합산을 제거하지 않는다.
2. **계산 분리**: 내부 COW의 원본 크기 반복 가산을 중단한다. 할당량·실제 사용량·추가 예약을 같은 변수로 취급하지 않는다. 생성/삭제/복원과 capacity 정기 재계산, API, 경보, VM 배치가 같은 계약을 사용하게 한다.
3. **기존 값 전환**: `vm_snapshot_chain_size`의 누적 논리값을 새 물리값으로 재해석하지 않는다. 새 측정/예약 의미가 필요하면 별도 필드와 출처·갱신 시각을 둔다. 적용 전 dry-run으로 풀별 before/after와 제외 이유를 기록한다. 전환은 idempotent하고 재시작 후에도 값이 되살아나지 않아야 한다. DB를 수동으로 0으로 초기화하는 것을 해결책으로 삼지 않는다.
4. **실제 공간 보호**: 기존 physical-used threshold를 유지하고, 메모리 저장과 COW 증가·동시 생성의 여유 공간/작업 예약 정책을 검토한다. 공간이 실제로 부족하거나 통계가 오래되면 안전하게 제한하고 사유를 보여준다. 측정은 Agent/provider/backend에서 수행하며 UI가 libvirt/qemu를 직접 호출하지 않는다.
5. **주변 경로 감사**: usage event의 new chain - previous chain, 볼륨 resize 후 삭제 차감, 재시도/중복 완료, pool migration, last snapshot 삭제, reserved capacity 해제를 검토한다. 사용량 과금 이벤트의 의미는 별도 확인하며 변경을 조용히 섞지 않는다.
6. **UI 의미 명시**: 1차 스토리지 목록·상세에 논리 할당량과 실제 사용량의 의미를 동일하게 안내한다. 계산이 개선돼도 할당량과 사용량이 같아지는 것은 아니다. VM 스냅샷 목록에 근거 없는 개별 용량을 추가하지 않는다.

## 완료 기준과 31번 검증

- [ ] 사용자 지정 시험 VM에서 Disk / DiskAndMemory 생성 전후의 Cloud DB, listStoragePools API, capacity 재계산, 실제 풀 사용 통계와 provider/Agent 답변을 같은 시각대로 기록한다.
- [ ] 원본 볼륨 논리 크기가 100 GiB인 시험에서 내부 스냅샷 개수만으로 +100 GiB가 반복 가산되지 않는다. 실제 메모리/COW 증가는 풀 사용 통계에 반영된다.
- [ ] 생성 직후와 게스트 쓰기 이후, 분기/복원, 첫/중간/마지막 삭제에서 논리 할당·물리 사용·예약이 각각 일관된다.
- [ ] root + data volume 및 여러 pool에 걸친 VM의 중복 합산이 없다. resize/migration/shared pool host 중복 통계도 확인한다.
- [ ] 생성/삭제/복원 실패, 타임아웃, 중복 job 완료, 동시 생성, management 재시작과 capacity 정기 갱신에서 누수·음수·중복 보정이 없다.
- [ ] 기존 데이터 dry-run → 전환 → 재실행 → 롤백 절차를 검증한다. 측정 불가/통계 만료를 0으로 처리하지 않는다.
- [ ] 실제 여유 공간 부족과 stale statistics에서 새로운 스냅샷 및 VM 배치를 차단한다. 가짜 논리 중복 제거로 공간 보호가 사라지지 않는다.
- [ ] 내부 스냅샷을 제외한 provider/strategy와 기존 overprovisioning/template/reservation 정책에 회귀가 없다.
- [ ] 1차 스토리지 목록/상세/API/경보/allocator 결과가 같은 계산을 사용하고 사용자에게 사유를 설명한다.
- [ ] listDeploymentStoragePools와 새 StoragePoolCapacity 표시의 할당/가용량이 같은 계산 계약을 따른다.

S7 구현 후 S6에 통합한다. 소스 변경의 Maven 모듈 검증은 WSL ext4에서 수행하며 Full Cloud build는 별도 사용자 요청 시 GitHub Actions로만 수행한다.
