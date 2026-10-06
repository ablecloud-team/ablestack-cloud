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

# ABLESTACK 볼륨별 백업과 공통 Staging 운영

대상 Provider는 `ablestack-commvault`, `ablestack-netbackup`, `ablestack-veeam`이다.
Staging은 글로벌 설정의 GFS2/NFS/LOCAL 파일시스템을 사용한다.

## 용량과 실행

- 활성화 시 기준: 현재 VM 목록에서 가장 큰 볼륨의 provisioned size에
  `backup.thirdparty.staging.capacity.buffer.percent`를 더한 용량.
- 실제 백업/복원: 작업에 포함된 가장 큰 볼륨을 기준으로 같은 buffer를 적용한다.
  볼륨 하나를 외부 솔루션에 전송하거나 복원하여 처리한 후 다음 볼륨으로 진행한다.
- QCOW2 백업은 일관된 시점의 데이터를 유지하는 기존 pull/scratch 엔진을 사용한다.
  원본 primary 파일시스템별로 해당 볼륨 크기의 합 + `max(20%, 10 GiB)`를 별도로 예약한다.
  Staging이 NFS로 분리되어 있어도 이 primary 용량은 필요하다.
- 복원은 모든 새 볼륨 준비 후 VM 볼륨을 교체하므로 primary에도 원본을 보호할 추가 공간이 필요하다.
  같은 physical storage identity를 사용하는 Backup/Restore 예약은 합산한다.
- Host/Cluster 동시 작업 제한과 용량 검사를 통과한 작업이 진행한다.
  부족하면 `WAITING`에 남고, 설정된 queue timeout 이후 취소된다.
- Running RBD VM은 현재 Host에서, Stopped RBD VM은 pool 접근이 가능한 정상 Host 중
  현재 작업 수와 Staging 여유 공간을 고려하여 백업한다.

## Mold UI 복원

Mold가 Worker Host와 `restore/<restore-job-id>`를 정하고 용량을 예약한다.
메타데이터와 필요한 Full/증분 파일을 한 단계씩 외부 솔루션에서 받아 새 볼륨을 준비한다.
모든 볼륨의 준비가 성공한 뒤 교체하며, 실패하면 기존 볼륨 보호 및 rollback 절차를 수행한다.

## NetBackup/Veeam 외부 UI의 QCOW2 복원

볼륨별 Job 구조에서는 VM 디렉토리를 한 번 복원해 전체 VM을 가져오는 방식으로 사용할 수 없다.
Mold가 각 볼륨 파일을 개별 Full child Job으로 전송하고 마지막에 metadata Job을 생성한다.

1. Mold에서 대상 VM을 **Stopped**로 둔다.
2. 관리자의 Staging Job 조회에서 원하는 logical backup UUID와 metadata Job/catalog ID를 확인한다.
3. 외부 UI에서 **`ABLESTACK-<logical-backup-UUID>-metadata`** Job/Policy의 restore point를 선택한다.
4. 메타데이터만 작업 Host에서 접근 가능한 마운트된 경로로 복원한다.
   공유 Staging의 다른 Host/경로로 복원해도 source catalog ID로 연결한다.
5. NetBackup post/watcher 또는 Veeam restore agent가 완료 세션을 확인한다.
   `resolveBackupArtifact`가 정확한 catalog ID/restore point UUID를 logical backup에 연결한다.
6. `restoreBackupArtifact`가 외부 restore Job ID를 DB에 먼저 기록하고 Mold 복원을 요청한다.
   Mold가 별도 Worker Host를 선택하고 용량 예약 후 필요한 볼륨을 순차 복원한다.
7. Mold의 실제 VM 복원 완료를 확인한 뒤 VM을 시작한다.

주의 사항:

- 이 외부 UI 연결은 **QCOW2 metadata Job**에만 적용한다. RBD는 Mold에서 복원한다.
- 볼륨 child Job을 직접 복원한 완료 알림은 VM 복원을 시작하지 않는다.
- Veeam의 child restore point UUID가 확인되지 않으면 timestamp/latest 추정으로 다른 백업을 선택하지 않는다.
- Mold가 제출한 외부 restore Job과 같은 외부 UI 완료 알림의 반복은 새 복원을 시작하지 않는다.
- `restoreBackupArtifact`의 async Job 완료는 요청 접수 결과이며 실제 VM 복원 완료는 Mold 복원 진행 상태로 확인한다.
- 모든 파일과 API를 같은 버전으로 배포하고 watcher를 시작한 뒤 새 UI 복원을 수행한다.
  기존 운영자 설정 파일의 Staging root, 외부 솔루션 연결, 관리 API 권한도 함께 확인한다.
- 외부 UI가 메타데이터를 쓰기 전에는 Mold의 사전 용량 예약이 실행되지 않는다.
  대용량 볼륨 전송은 Mold가 예약한 경로에서 수행하며, 메타데이터 목적지는 운영 환경에서 마운트를 유지한다.

## 불확실한 Job과 정리

호출 전에 실패한 것이 확인되면 `NOT_SUBMITTED`/실패를 기록하고 engine 종료 확인 후 예약을 해제한다.
외부 요청의 전송 여부나 reader/writer 종료가 불확실하면 예약과 Artifact를 유지한다.
관리자는 Staging Job 조회/재조회/외부 Job 연결 기능으로 정확한 Job을 확인할 수 있다.

성공한 RBD 백업의 이전 checkpoint snapshot 정리가 실패하면 `sourcecleanupstate=WAITING`으로 표시하고 재시도한다.
현재 checkpoint snapshot은 다음 증분 백업을 위해 유지한다.

필수 checksum 검사는 추가하지 않는다. 기존 파일 존재, 크기, manifest 및 볼륨 구성 검사는 유지한다.
