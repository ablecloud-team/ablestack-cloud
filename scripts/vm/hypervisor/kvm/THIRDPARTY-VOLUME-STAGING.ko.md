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

Commvault/NetBackup/Veeam 볼륨 백업의 최초 Host 실행 요청도 같은 원칙을 적용한다.
DB에 원래 Job UUID·Worker Host·경로·manifest를 먼저 저장하고 Host의 시작 준비 응답을 확인한 뒤,
`SUBMISSION_PENDING` 의도를 저장하고 실행 명령을 한 번만 보낸다.
Agent 접속 실패, timeout, 실패 응답 또는 응답 유실만으로 DB 행을 삭제하거나 강제 정리·즉시 Full 재시도를 하지 않는다.
같은 Job UUID의 시작 기록을 재조회하며, 확인 중 화면은 `RUNNING`/`START_CONFIRMATION`으로 표시한다.
시작 상태·대기 사유·제출/확인 시각은 Staging Job 관리 화면에서도 확인할 수 있다.

Host 시작 기록은 `/var/lib/ablestack/backup/backup-start/<job-id>.json`에 별도로 보관하며
`PREPARED` → `DISPATCHED` → `STARTED` 순서로 갱신한다.
Agent의 FileLock과 Python의 lockf가 같은 POSIX 잠금을 사용하여 실행 접수, 최초 engine 시작, 미시작 확정을 직렬화한다.
Python은 Staging/source IO 전에 `STARTED`를 기록한다.
시작 오류가 기록되었거나 60초 안에 시작이 확인되지 않으면 Host에서 미시작 상태를 다시 확인하고
`START_FAILED`와 admission 종료 기록을 영구 저장하여 늦게 도착한 실행 명령까지 차단한다.
Host가 이미 `STARTED`를 기록했다면 정상 진행 추적을 이어가고, Host 응답이 없으면 예약과 작업 정보를 유지한다.
systemd가 작업을 수락한 뒤 Agent 시작 응답만 실패한 경우에도 실제 실행 중인 unit을 재확인하여 `RUNNING` 추적을 이어간다.
미시작 차단 응답이 확인된 뒤에만 DB 실패 상태, 예약 해제, Host Job 기록 정리를 진행한다.
작은 시작 기록과 잠금 파일은 Job 정리 후에도 유지한다. 기존 시작 기록이 없는 작업은 기존 engine 추적 절차를 사용한다.
같은 VM의 이전 볼륨 백업이 진행 중이거나 실패 후 Staging/예약/Job 기록 정리가 남아 있으면 새 백업을 차단한다.
이 시작 절차를 적용할 때는 Management Server, KVM Agent와 `thirdparty_volume_backup.py`를 함께 배포한다.

볼륨 백업의 조회·취소·재조정·정리는 DB에 기록된 Worker Host ID를 기준으로 처리한다.
시작 plan과 admission에 Host ID가 있으면 서로 일치해야 하며, 해당 Host가 없어졌더라도 VM의 현재 Host로 대체하지 않는다.
ID가 없는 기존 볼륨 작업만 manifest에 기록된 원래 Host 이름/주소를 사용한다.
Worker Host가 없거나 기록이 충돌하면 작업과 예약을 유지하여 잘못된 Host에 취소·삭제 요청을 보내지 않는다.

최초 Host 시작 확인 중에도 DB 대기열이 `WAITING`이면 취소 의도를 먼저 저장할 수 있다.
Host의 admission 파일 생성이나 응답을 취소 접수의 전제 조건으로 삼지 않는다.
DB 대기열은 `CANCEL_PENDING`, 백업 화면은 `RUNNING`/`CANCEL_PENDING`으로 표시한다.
같은 취소 요청은 중복 접수할 수 있으며, 새 admission 승인과 외부 child Job 전송은 차단한다.
불확실한 작업은 동시 실행 슬롯과 이미 확보한 용량 예약을 유지한다.
Host가 응답하면 미시작 작업은 시작 잠금 안에서 차단하고, 이미 시작된 engine에는 반복 가능한 취소 marker를 전달한다.
응답 유실이나 Management Server 재시작 후에도 같은 Job UUID의 취소 의도를 이어서 처리한다.
미시작 차단 또는 engine 종료 확인 후 `Canceled`로 변경하고, 공통 정리가 끝난 뒤 예약과 Host Job 기록을 해제한다.
Staging Job 관리 화면은 취소 요청/Host 확인 시각과 정리 대기 사유를 별도로 표시한다.

볼륨 복원도 동일하게 plan의 Worker Host ID만 사용하여 조회·재조정·정리를 진행한다.
기록된 Host가 삭제되면 Host 이름이나 VM의 현재/마지막 Host로 대체하지 않는다.
Host Job 기록 삭제도 해당 복원 plan의 UUID만 사용하며 VM 정보로 만든 기존 작업 ID를 삭제 대상으로 추가하지 않는다.
복원 plan, 현재 추적 정보와 같은 Job의 admission 정보가 충돌하면 Host 요청과 정리를 중단하고 예약을 유지한다.
정리된 이전 복원의 `RELEASED` admission은 새 plan의 Worker Host 선택에 사용하지 않는다.

새 시작 프로토콜을 사용하는 복원이 `WAITING`이고 외부 복원 요청 기록이 없으면,
Host admission 파일 준비 여부나 접속 상태와 관계없이 취소 의도를 복원 이력에 저장한다.
대기열은 `CANCEL_PENDING`, 화면 상태/단계는 `RUNNING`/`CANCEL_PENDING`으로 표시한다.
취소 요청 후 시작 명령, 용량 승인과 외부 복원 전송을 차단하며 동시 실행 슬롯 및 용량 예약을 유지한다.
Host가 응답하면 같은 시작 잠금 안에서 미시작을 확정하거나,
초기화된 정확한 Host plan을 대조한 뒤 `volume-restore-cancel` marker로 엔진 종료를 요청한다.
엔진은 admission 대기, 승인 직후 및 외부 파일 요청 전에 취소를 검사한다.
시작 검사와 초기화는 JVM 내부 잠금과 Host 파일 잠금을 함께 사용하여 지연된 시작을 차단한다.
미시작 차단 또는 엔진 종료가 확인되면 `CANCELED`로 표시하고,
원본 볼륨 상태·외부 writer 종료·Staging 정리가 확인된 뒤 예약을 해제한다.
취소된 대기열에 외부 요청 기록이 없으면 외부 솔루션의 receipt 정리 호출도 생략한다.
rollback이 필요한 결과는 취소 완료로 바꾸지 않으며 원본 복구와 정리 확인을 계속 요구한다.
Management Server 재시작, Host 응답 유실과 관리자의 재조회도 같은 Job의 취소 의도를 이어서 처리한다.
취소 요청/Host 확인 시각과 정리 사유는 현재 복원 및 과거 복원 이력에서 확인할 수 있다.
시작 기록이 없는 기존 복원은 기존 대기열 취소 절차를 사용한다.
이 취소 절차를 적용할 때는 Management Server와 KVM Agent를 함께 배포한다.

`START_FAILED`로 미시작 차단이 확인된 백업은 원래 manifest가 그대로이고 외부 전송 시도가 없으며,
Staging 정리·DB 예약 해제·Host Job 기록 삭제가 모두 완료된 경우 저장된 증거만으로 삭제할 수 있다.
집계 대기 기록으로 해당 백업이 건수·용량 집계 전 실패/취소되었는지도 확인한다.
이 경우에는 원래 Host가 삭제되었거나 Staging 설정이 변경되어도 mount/외부 솔루션/Ceph/libvirt를 다시 조회하지 않는다.
증거가 없거나 일부 정리가 남은 작업은 기존 종료·정리 확인 절차를 유지한다.

원본 준비 단계 전에 외부 솔루션 설정 오류나 대기 timeout으로 실패한 경우,
종료된 Host engine의 `volume-source-start.json`이 `NOT_STARTED`이고 외부 전송 시도가 없으면
외부 솔루션과 Ceph/libvirt 조회 없이 Staging 파일과 예약을 정리한다.
시작 기록은 원본 작업 전에 디스크에 저장하며, 원본 작업을 시작한 경우 `STARTED`로 변경한다.
기록이 없는 기존 작업이나 기록이 불확실한 작업은 기존 종료 확인 절차와 예약 유지 정책을 따른다.
물리적 정리 후 DB 예약을 해제한 다음 정리 완료를 표시하여 서버 재시작 시 해제 재시도를 유지한다.
정리 시 용량 잠금 안에서 admission 종료 기록을 저장하여 지연된 용량 승인 요청이 예약을 다시 생성하지 못하게 한다.
Backup/Restore 승인 종료 기록은 Host의
`/var/lib/ablestack/backup/staging-admission-closed/<job-id>.closed`에 저장한다.
이 작은 기록은 Job 디렉토리 삭제 대상에 포함되지 않으며, 이미 실행 중인 지연 승인도 계속 차단한다.
기존 Job 디렉토리 안의 `staging-admission-closed` 기록도 읽어 이전 종료 상태를 유지한다.
복원도 같은 용량 잠금 안에서 승인 종료 기록을 저장한 뒤 예약을 삭제한다.
종료된 복원 Job은 지연된 승인이나 `prepare`/`reserve` 요청으로 다시 용량을 확보할 수 없으며,
정리 도중 중단되면 같은 Job의 예약 해제를 재시도할 수 있다. 새 복원은 새 Job ID를 사용한다.

볼륨별 백업 취소는 기존 Provider의 강제 삭제 경로 대신 공통 정리 절차를 사용한다.
Host engine 및 외부 reader 종료 확인 → 원본 Scratch/Snapshot과 Staging 정리 → 물리적/DB 예약 해제
→ Host Job 기록 삭제 순서로 처리한다. 대기열 취소에도 같은 절차를 적용한다.
Job 기록 삭제가 실패하거나 응답이 유실되면 `jobcleanupstate=WAITING`으로 기록하고 별도로 재시도한다.
이때 이미 확인한 Staging 정리 완료 상태는 유지하여 삭제된 원본 Job 기록을 다시 요구하지 않는다.

모든 child Job과 metadata 전송이 완료된 뒤 checkpoint 정보 저장, DB 예약 해제 또는 완료 상태 저장이
일시적으로 실패하면 백업을 `Failed`로 바꾸지 않고 `BackingUp`을 유지하여 완료 처리만 재시도한다.
Host 완료 후 Mold 완료 확정 전 화면 상태/단계는 `RUNNING`/`FINALIZING`, 진행률은 99%로 표시한다.
관리자의 Staging Job 조회에서 `finalizationstate`/`finalizationreason`으로 대기 사유를 확인할 수 있다.
재시도와 Management Server 재시작 후 복구는 완료된 파일을 다시 전송하지 않는다.

최종 metadata Job의 정확한 catalog 완료 정보는 Host의 `volume-transfer-complete.json`에도 저장한다.
이후 원본 reader/Scratch 정리, 승인 종료 기록 저장 또는 물리적 예약 해제에서 실패해도
Mold는 DB의 전체 전송 완료 증거를 기준으로 `BackingUp`/`FINALIZING`을 유지한다.
Host engine 종료 후 `FINALIZE_BACKUP`으로 같은 Job의 정리만 재시도하고,
`volume-finalization.json`에 정리 완료를 저장한 뒤 DB 예약 해제와 logical backup 완료를 확정한다.
각 재시도는 별도의 응답 기록을 사용하여 이전 성공 응답을 현재 요청의 성공으로 오인하지 않는다.
Host 재시작이나 마지막 ACK 유실로 전송 완료 기록이 저장되지 못했어도,
DB의 모든 volume/metadata catalog 완료 정보를 원래 Host plan과 대조한 뒤 완료 처리를 이어간다.
실패 종료 직후 Host가 RBD snapshot을 바로 삭제하지 않으며, 외부 작업 결과를 확인한 공통 정리가 삭제 여부를 결정한다.
완료 처리에서는 현재 RBD/QCOW2 checkpoint와 metadata를 보존하고, 이전 RBD snapshot은 별도 재시도 정리에서 처리한다.
Host 연결이 끊겨도 DB에 전체 전송 완료 증거가 있으면 화면은 `RUNNING`/`FINALIZING`/99%와 대기 사유를 표시한다.
이 단계의 백업 취소와 대역폭 변경은 허용하지 않는다.

성공한 RBD 백업의 이전 checkpoint snapshot 정리가 실패하면 `sourcecleanupstate=WAITING`으로 표시하고 재시도한다.
Snapshot 정리 상태와 실패 사유는 일반 백업 정보 저장에서 제외하여 기존 상태로 덮어쓰거나 삭제하지 않는다.
현재 checkpoint snapshot은 다음 증분 백업을 위해 유지한다.
Host의 Job 기록 삭제도 성공한 RBD 증분 백업의 `source-cleanup.json` 완료 증거를 검사한다.
기록이 없거나 정리 중/실패 상태이면 `volume-plan.json` 등 재시도에 필요한 기록을 삭제하지 않는다.
새 완료 증거에는 version, backup UUID와 현재/이전 checkpoint 이름을 저장하여 원래 Host plan과 대조한다.
기존 UUID/state 형식의 완료 기록도 같은 Job의 기록인 경우 사용할 수 있다.
Management Server가 정리 완료 응답을 받지 못하면 DB의 `sourcecleanupstate=WAITING`을 유지하여
삭제 요청을 차단하고 같은 작업의 정리 완료 확인을 재시도한다.

NetBackup의 `updateNetBackup` 전체 백업 완료 API는 볼륨 방식 백업에 대한 요청을 거부한다.
백업 상태, timestamp, catalog ID 및 정책 정보를 쓰기 전에 거부하여 기존 callback이 child/metadata 정보를 덮어쓰지 못하게 한다.
볼륨별 catalog와 logical backup 완료는 공통 volume coordinator에서 확정한다.
이 API의 Host 기록 삭제도 볼륨 방식 백업에는 실행하지 않는다.
최신 NetBackup post hook의 `.volume-bootstrap`/child Policy 확인과 함께 서버에서도 같은 경계를 보호한다.

Veeam의 기존 하루 경과 `BackingUp` 자동 정리는 볼륨 방식 백업에 적용하지 않는다.
`WAITING`, 외부 전송 또는 `FINALIZING` 대기 시간이 길어져도 작업 정보와 예약을 보존하며,
공통 coordinator가 실제 종료와 정리를 확인한 뒤 처리한다.

## 백업 자원 집계

볼륨 방식 백업은 Host에 명령을 보내기 전에 초기 manifest와
`backup.resource.count.pending=true`를 하나의 DB 트랜잭션으로 저장한다.
Logical backup을 `BackedUp`으로 확정할 때 같은 트랜잭션 안에서 백업 건수와 용량을 집계하고 pending 기록을 해제한다.
집계는 해당 백업 행을 잠근 뒤 현재 상태와 pending 기록을 다시 확인하여,
동시 처리나 서버 재시작 후 재시도에서도 한 번만 반영한다.
집계 중 실패하면 전체 트랜잭션이 취소되며 `FINALIZING`에서 완료 처리를 재시도한다.

이미 `BackedUp`이지만 pending 기록이 남은 기존 작업도 백그라운드에서 별도로 조회하여 집계한다.
이 집계에는 VM이나 Host 연결이 필요하지 않다.
일반 백업 정보 저장은 pending 기록을 삭제하거나 이미 해제된 기록을 다시 생성하지 않는다.
지연된 백업 시작 API 응답이나 기존 restore-point 동기화는 볼륨 방식의 건수·용량을 중복 집계하지 않는다.
Full fallback도 자신의 초기 집계 대기 기록을 사용하며, 실패한 증분 작업의 pending 기록을 뒤늦게 복사하지 않는다.
완료 전에 실패하거나 취소된 백업은 pending 기록을 유지하며, 이후 삭제 시 집계되지 않은 건수·용량을 차감하지 않는다.

필수 checksum 검사는 추가하지 않는다. 기존 파일 존재, 크기, manifest 및 볼륨 구성 검사는 유지한다.
