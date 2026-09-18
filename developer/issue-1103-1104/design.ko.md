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

# VM 작업 보호와 모니터링 수집 제한

관련: Cloud #1103, #1104. hangctl 선행 기준: qemu-exec-tools PR #58 `10cd94c`.

## 최종 구현 계약

- Agent와 hangctl은 `/run/ablestack-vm-operations/locks/<UUID>.lock`에 Linux **flock**을 사용한다. Java FileChannel의 POSIX record lock과 혼용하지 않는다. 잠금 파일은 삭제하지 않는다.
- 이슈의 최초 짧은 진입 잠금 설계에서 변경: 이 구현은 동기 snapshot/revert/delete 및 blockcommit 작업 전체에 배타 잠금을 유지한다. 보호 갱신은 이 잠금을 재획득하지 않는 단일 daemon scheduler로 수행하므로 갱신과 작업 종료 사이의 잠금 교착을 만들지 않는다.
- 일반 모니터링은 대기하지 않고 수집을 생략한다. 관리 작업 진입은 최대 5초 기다린 뒤 실패 응답을 반환한다. hangctl은 비차단 잠금 획득 후 기존 PR #58의 domain-job/paused/identity/action gate를 그대로 수행한다. 검사와 조치 발행 사이 새 Agent 작업이 진입할 수 없다.
- 각 작업은 무작위 operationId/generation으로 자신의 JSON만 작성·갱신·삭제한다. 디렉터리는 실행 UID 소유 0700이며 UUID·symlink·권한을 검증한다. 원자적 rename으로 갱신하고 5초 주기, 30초 만료 시각을 기록한다. TTL은 관측용이며 강제 종료 허가가 아니다.
- 남은 JSON 또는 부분 파일은 내용·만료와 무관하게 UNKNOWN 보호다. 잠금 획득 실패/쓰기 실패 시 보호 없는 snapshot을 시작하지 않는다. 모호한 snapshot/revert 실패 및 blockcommit 완료 대기 실패는 lease를 남긴다.
- Cloud job ID가 현재 로그 컨텍스트에 있으면 기록한다. 없는 경우 UUID/operationId/generation/ownerPid/ownerStartTime/bootId로 연계한다.

## 작업 스레드와 프로세스 관리

- 기존 StatsCollector 스케줄러를 변경하지 않는다. 조회용 executor, 작업 큐, Future, 스트림 읽기 스레드를 추가하지 않는다.
- 조회는 기존 명령 스레드에서 직접 수행하며 단명 virsh 프로세스에 제한 시간을 둔다. timeout/interrupt 때 자식과 후손을 종료하고 회수한다.
- 프로세스 동시 실행은 Agent 전체에서 최대 8개, 대기 큐 없이 admission 실패 시 skip한다. SIGKILL 이후에도 커널 I/O 때문에 살아 있는 프로세스는 실행 슬롯을 계속 점유한다. 다음 조회 때 실제 종료를 확인해야 슬롯을 돌려주므로 무한 대체 프로세스가 쌓이지 않는다.
- VM별 기본 수집 예산은 5초(`-Dcloud.vm.monitor.budget.ms`로 조정). 개별 probe는 남은 예산으로 제한한다. 같은 수집의 domstats/XML은 캐시하고 finally에서 ThreadLocal을 제거한다.
- 작업 lease scheduler는 하나이며 removeOnCancelPolicy를 활성화한다. close에서 renewal을 취소한다. 종료와 갱신은 객체 모니터로 직렬화한다.
- virsh 클라이언트를 회수한 사실이 서버의 libvirt RPC 취소를 보장하지는 않는다. 외부 domain/block job 또는 조회 불명 상태를 IDLE로 간주하지 않고 다음 수집도 gate를 거친다.

## 경로별 포함/제외

| 경로 | 처리와 이유 |
|---|---|
| CreateVMSnapshot | 실제 snapshotCreateXML 이전 보호; 메모리 저장 동안 유지 |
| RevertToVMSnapshot | 실제 revertToSnapshot과 필요한 전원 정리까지 보호 |
| RestoreVMSnapshot | REDEFINE/CURRENT 메타데이터 재등록 경계 보호. 실제 복원과 구분 |
| DeleteVMSnapshot | suspend/delete/resume/finally 복구까지 보호 |
| 실행 중 디스크/볼륨 snapshot | snapshotCreateXML 경계 보호 |
| blockcommit | 기존 listener/polling 전체를 보호; 완료 대기 실패 시 잔여 작업 가능성 때문에 lease 유지 |
| VM/disk/network 통계 | 공통 비차단 gate, domain job + 각 disk block job 검사, bounded virsh |
| QGA 게스트 네트워크 | 동일 gate, 기존 capability/parser/OS별 fallback 및 오류 전달 유지 |
| 작업 소유자의 block-job 완료 조회 | 변경하지 않음. 일반 모니터링 skip 대상이 아님 |
| offline qemu-img, start 시 metadata 복구 | 실행 중 QEMU가 없는 기존 경로 유지 |
| migration, FTCTL/DR, 외부 virsh | 기존 PR58 보호 유지. 이 PR이 해당 모든 작업의 producer를 교체하지 않음 |

## 파일별 변경 사유

| 파일 | 추가/수정/삭제와 구체적 사유 |
|---|---|
| KvmVmOperationGuard.java | 추가. Agent/shell 공통 admission, lease 수명, 제한된 조회 프로세스 회수를 한 곳에서 관리하여 wrapper별 잠금 구현 불일치를 방지 |
| KvmBoundedStats.java | 추가. native 통계 호출을 관측 전용 bounded virsh로 분리. domstats NOWAIT의 필수 필드 누락은 0으로 보충하지 않고 skip |
| LibvirtComputingResource.java | 수정. 네 가지 통계 진입점을 보호하고 실제 작업은 별도 보호. guest-info의 기본 장기 timeout 제거, 실패를 QGA 미설치로 단정하는 기본값 기록 제거. RBD 조회도 인자 배열과 예산 적용 |
| LibvirtGetVmStatsCommandWrapper.java | private domjobinfo gate 삭제. 통계 한 종류에만 존재했고 검사와 실행 사이 경쟁이 있었으므로 공통 guard로 이동 |
| LibvirtGetVmGuestNetworkStateCommandWrapper.java | 수정. 공통 gate와 bounded QGA 호출 적용. 기존 VM별 state/error 결과와 bounded guest helper 동작 보존 |
| Create/Revert/Restore/Delete wrappers | 수정. 실제 native 호출 앞 admission, finally 정리. Delete의 예외 복구 resume가 끝나기 전 보호 해제하지 않음 |
| KVMStorageProcessor.java | 수정. 실행 중 볼륨 snapshot의 snapshotCreateXML 경계 보호. 기존 변환·저장소 처리 및 완료 polling은 유지 |
| 기존 KVM 테스트 3개 | 조회/잠금 transport를 mock 경계로 분리하고 기존 parser/metrics/스토리지 단언 유지. 실제 OS 동작은 별도 테스트에서 검증 |
| 신규 guard/stats 테스트 | 실제 flock, 재갱신/취소, partial lease, timeout/interrupt 회수, NOWAIT 필드 누락 검증 |

## 실패·재시작·잔여 lease 운영

정상 종료는 자신의 lease만 제거하고 다음 주기부터 관측이 재개된다. Agent 중단은 pipe EOF로 flock을 해제하지만 JSON은 남으므로 hangctl이 강제 복구하지 않는다. `/run`이 비어도 PR58 domain-job/paused gate를 우회하지 않는다.

잔여 lease는 TTL만 보고 자동 삭제하지 않는다. 관리자 재조정 시 해당 UUID의 공통 flock을 획득하고 owner PID/시작 시각/boot ID, Agent 작업 종료, 현재 domain identity 및 domain/block job 종료, VM 전원 상태를 확인해야 한다. 판단 불가하면 보호를 유지한다. 타 호스트로 이동한 VM의 이전 호스트 기록을 현재 실행 권한으로 쓰지 않는다.

## 배포 및 롤백

consumer는 PR58 위 추가 패치와 producer KVM JAR를 함께 배포한다. 설치된 PR58 파일 내용이 기준과 다르면 덮어쓰기를 중단한다. hangctl timer를 잠시 중지하고 진행 중 scan 종료를 기다린 뒤 파일을 교체한다. Agent 서비스만 재시작하고 실행 VM UUID 집합이 유지되는지 확인한다. Agent 단계에서는 관리 서버·UI·DB를 교체하지 않는다. 통계 freshness 표시는 별도로 빌드한 API/core/server 클래스와 UI 모듈을 배포하며 기존 #1097 DB 보정 클래스를 보존한 관리 서버 JAR에 해당 클래스만 반영한다. DB 스키마 변경은 없다. 기존 #1101 UI 배포 내용 위에 이 변경만 더한다.

rollback은 활성 보호 작업이 없음을 확인한 다음 백업 JAR와 shell 파일을 함께 복구한다. Cloud producer만 남기고 consumer를 PR58 이전으로 내리는 혼합 버전은 금지한다. 잔여 lease를 롤백 편의 때문에 지우지 않는다.

## 완료 범위 추적

실물 검증 결과는 verification.ko.md에 기록한다. API/UI에는 기존 vm_stats의 마지막 성공 timestamp를 statslastsampled로 전달하고 수집 간격의 두 배(최소 2분)를 넘기면 statscollectionstatus=STALE로 표시한다. 조회 요청 시각으로 덮어쓰지 않는다. InfoCard는 마지막 수집 시각과 갱신 지연을 표시한다. exporter는 누락 VM의 새 0 샘플을 만들지 않는 기존 동작을 유지한다. 세부 skipReason의 API 전달, 모든 migration/backup/blockcopy 조합의 실물 검증, host reboot/장애 주입은 검증 범위를 구분해 기록한다. 이슈의 미검증 항목을 완료로 표시하지 않는다.

## Freshness 및 카운터 변경 사유

| 파일 | 추가/수정 이유와 영향 |
|---|---|
| VmStats / VmStatsEntry | optional sampledAt/collectionStatus 추가. 인터페이스 default는 구형 구현에 UNKNOWN을 제공하며 기존 통계 필드를 변경하지 않음 |
| StatsCollector | 기존 저장된 성공 샘플의 timestamp를 API 조회 결과에 붙임. 누락 주기에 0을 생성하거나 요청 시각을 새 수집 시각으로 저장하지 않음. 스케줄러와 작업 스레드 설정은 유지 |
| UserVmResponse / UserVmJoinDaoImpl | optional statslastsampled, statscollectionstatus 제공. 오래된 수치를 소비자가 식별할 수 있게 함 |
| InfoCard / en, ko_KR locale | 마지막 수집 시각과 STALE 표시. theme 기본 tag를 사용하고 고정 흰 배경/글자색을 추가하지 않음 |
| LibvirtComputingResource | CPU/network/disk 누적 카운터 감소 시 baseline을 교체하고 첫 샘플 생략. 복원·재시작 후 음수 delta 또는 잘못된 0 샘플을 방지. 다음 정상 주기부터 수집 재개 |
| StatsCollectorTest | persisted timestamp와 STALE→FRESH 전이를 확인하고 freshness 조회가 DB에 가짜 샘플을 추가하지 않음을 검증 |

의존 API 모듈의 기존 `KvmTpmConfigTest` wildcard import도 명시적 assert import로 바꿨다. Checkstyle을 생략하지 않고 통과시키기 위한 테스트 소스 정리이며 TPM 동작·단언은 변경하지 않는다.
