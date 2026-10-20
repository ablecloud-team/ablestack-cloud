# #974 SPARSE 10TiB 실제 포맷·복구 대기 — 2026-10-08

사용자 필수 조건으로 모든 NEW 디스크는 SPARSE 또는 FAT이어야 하고 THIN 생성은 금지한다. 기존 THIN 10TiB XFS/ext4 실험은 과거 기록으로 남기되 현재 #974 인수 게이트에서 제외했다. 기존 DATA를 재포맷하지 않는다.

## 현재 준비한 실제 리소스

| 항목 | 실제 값 |
| --- | --- |
| 재사용 fixture | SharedFS4b760728-104b-4f90-a039-adca78d31a4c / instance0b5a950b-7362-4cc6-8c48-81f938bbe26e / VM51@13.2 / STATIC242 |
| 보존 범위 | 원래 fixture51 ROOT와 initial20GiB5368d530 및 원본 DATA/VM39·41·49·50. 새 ROOT/VM을 만들지 않았다. |
| signed native | catalog6186e071-a1cb-4641-8098-97c36d3934cf / epic898-e4c0fab-root-receipt / upgrade d8d43a97-1af4-426c-8b76-1a7f4a77be81 COMPLETE |
| 새 offering |80ea41ff-feec-4eb8-9919-7bb850b6e503 / epic898-format10t-sparse-fixed-audit / fixed10240GiB/SPARSE/shared/glue-gfs/writeback/hidden |
| 새 XFS 시험 DATA |021b0cac-327e-444b-9cff-3c9da0d1f539 / epic898-format10t-sparse-xfs-audit / Ready/VM51 attached/actualCloud provisioningtype=sparse |
| createVolume job |5d2539a5-943a-464f-95d5-5a7dbfeed034 SUCCESS. custom UUID를 생성 전에 고정하고 provenance receipt와 정확한 owner/zone/pool/type/size를 확인했다. |
| pool |90b0c4e3-7078-4372-8da0-9625bc0e806a만 일시 factor4. 원래 override 부재/상속1, global factor/max/custom4096 불변. 종료 시 fresh login→resetConfiguration으로 override를 삭제하는 finally guard 유지. |

정규 createStorageNfsExport(FORMAT_IF_EMPTY)가 VM51에 attach한 뒤 XFS formatter를 실행했다. 아래 실제 timeout/partial 증거를 확인했다. 정상 COMPLETE·외부 I/O·재부팅·cleanup은 아직 수행하지 않았고 새 formatter를 실행하지 않는다.

## source와 actual qemu 증거

Storage.ProvisioningType은 THIN/SPARSE/FAT이고 QemuImg.PreallocationType은 각각 off/metadata/full이다. SharedMountPoint는 QEMU_IMG_MANAGED_POOL_TYPES에 포함돼 createPhysicalDiskByQemuImg가 provisioning을 전달한다. new multiNEW planner와 actual allocation receipt에도 SPARSE/FAT 검증을 추가해 THIN/unknown/actual mismatch를 사전에 차단했다(32 unit PASS).

readonly host qemu-img measure의 SPARSE10TiB required metadata는1,678,114,816 bytes(약1.563GiB)이고 fully-allocated는10,996,794,392,576 bytes다. FAT 전체10TiB는 현재 물리 여유로 수행할 수 없으므로 SPARSE를 선택했다.

actual host13.1에서 exact NEW UUID를 대상으로 한 qemu-img create PID1028531/startticks61217426의 preallocation=metadata를 직접 /proc에서 관측했다. metadata 과정 RSS9,572,352 bytes, guard abort0이었다. 실제 API/host 생성 완료 후 양쪽13.1/13.2의 fresh qemu-img info/stat은 다음과 같이 일치한다.

| 항목 | 실제 값 |
| --- | --- |
| format / virtual bytes | qcow2 /10,995,116,277,760 |
| actual-size / st_blocks*512 |1,807,642,624 bytes(약1.684GiB) |
| apparent file bytes |10,996,794,392,576 |
| cluster |65536 bytes |
| backing-filename / full-backing-filename | 둘 다 null |
| corrupt / lazy-refcounts | false / false |
| file owner |0:0 |

13.2 watcher가 생성 도중 먼저 본 작은 파일 크기는 GFS 작성 초기 snapshot이므로 완료 증거로 사용하지 않았다. 완료 후 fresh 두 호스트 값으로 교정했다. agent 파일 로그의 matching debug command는0개였으므로 로그 PASS로 표현하지 않고 exact PID/starttime/UUID/option의 actual process 관측을 구분해 기록했다.

## 시험·관측 일정과 안전 예산

포맷은 새 작업 탭 actual UI 준비 신호까지 hold한다. 최소25초를 넘는 실제 지연을 위해 정확한 NEW mkfs PID만60초 SIGSTOP→SIGCONT하며 AS1GiB를 제한한다. physical160GiB/free>=3.30TiB/RSS1GiB guard를 유지하고 한도 초과 시 UUID/serial/device/formatter 일치한 NEW PID만 중단한다. device 이름이나 프로세스 이름만으로 신호를 보내지 않는다. XFS→소유한 share 삭제→정확한 NEW detach/delete→ext4 신규 SPARSE 한 개의 순서이며 동시에20TiB를 예약하지 않는다.

formatter 중 관리 서버 재시작은 금지한다. 독립 readonly preparation API는 client15초 timeout으로 phase/heartbeat/deadline/formatterActive/current identity를 기록한다. 부모가 새 작업 탭에서 자동 갱신/상태/diagnostic 실제 화면을 검증한다. 완료 후 NFS/SMB I/O·같은 credential reboot·FSUUID/hash/UID/mode/inode/currentdevice/sector/fsblock을 다시 실증한다.

증거는 acceptance-audit scratch의 large-sparse-runtime-upgrade51.json, large-sparse-xfs-allocation-proof.json, large-sparse-metadata-watch-13.1/13.2.json, large-sparse-xfs-preformat-host-proof.json, large-sparse-pool-factor-guard.json이다. 아직 SPARSE format/resume 인수 완료가 아니며 이슈는 열어 둔다.

## 실제 SPARSE XFS 시험과 실패 상태

정규 NFS create job f51dc0d4-4c0d-46f5-9d54-07bd2ff06a70는 13:56:33 KST에 시작했다. 정확한 UUID/serial/startTicks719319의 NEW mkfs.xfs PID78663을 13:56:38.527963에 SIGSTOP하고 13:57:38.528413에 SIGCONT했다(60.000450초). AS1GiB·physical 증가160GiB·free3.30TiB 제한을 유지했다.

writer 실행 중 scoped preparation GET은 1.25~2.25초에 응답했고 FORMATTING/formatterActive/heartbeat/deadline1500/currentDevice sdd/EXACT를 반환했다. 부모는 새 작업 탭 자동 조회와 실제 화면을 캡처했다. 이 게이트는 조회가 writer와 공존함을 증명하며 포맷 완료를 의미하지 않는다.

XFS의 기본 discard는 10,240개 명령·21,474,836,480 sectors(10TiB)를 처리했지만 이후 로그 ZERO_RANGE fallocate에서 D/submit_bio_wait가 관측됐다. 1500초 deadline 후 TERM/KILL이 pending인 동안 native의 kill→communicate 무제한 대기가 끝나지 않아 journal이 FORMATTING/elapsed1500.19에 머물렀다. MGT job은 error530, operation1c1a61a3-d110-4100-96c5-292a38d9ba68는 RECOVERY_REQUIRED가 됐다. UI는 조회 실패 경고로 자동 갱신을 중지하고 이전 FORMATTING 값을 보존했다. 이는 새 진행 상태가 아니라 이전 관측이다.

이후 프로세스가 자연 종료한 fresh 관측에서 PID78663은 없고 writerIdle=true, journal=TIMED_OUT_PENDING_RECONCILE로 바뀌었다. bounded blkid는 XFS/UUID f8c35b5d-3cb3-4a7f-82a8-06dce2c2108d를 읽었다. **부분 filesystem 헤더는 정상 완료 증거가 아니므로 mount/I/O·새 mkfs·VM51 reboot·볼륨 삭제·수동 kill을 수행하지 않는다.** kernel source 담당은 deadline journal 선기록·bounded TERM/KILL·orphan writer FD 계승을 보완 중이며 정식 reconcile/resume 계약으로 이어간다.

resource guard 위반은 없었다. formatter RSS 약3.25MiB/AS1GiB, 물리 증가 약3.17GB, free 약3.51TiB였다. host13.2 QEMU는 S/poll이고 D thread0/new GFS blocked warning0이었다. 이를 전체 GFS 장애 해소나 filesystem 정상 완료로 확대하지 않는다.

UI total이10.02TiB→20GiB로 내려간 뒤 API/DB/libvirt/guest를 읽기 전용으로 대조했다. 021b 볼륨은 Ready/destroyed=false/removed=NULL/instance51/SPARSE10TiB이고 libvirt와 guest에서 같은 serial의 attachment가 유지됐다. guest sdd와 libvirt target sdc는 명명 계층 차이다. 따라서 이 시점 total 감소는 share rollback 뒤 숨김·미참조 backing projection 누락이며 자동 detach/delete나 수동 cleanup이 아니다.

현재 pool factor4 임시 override와 정확한 NEW 021b 논리 예약을 보존한다. 원래 override 부재/상속1과 global 불변 증거를 가진 finally guard는 유지하지만 partial 복구 대기 볼륨을 삭제해 원복시키지 않는다. 정식 recovery/retain/cleanup 승인 후 override 삭제와 상속1 확인을 별도 완료해야 한다. ext4 SPARSE 순차 시험도 아직 시작하지 않았다.

주요 증거: large-sparse-xfs-watcher-complete.json, large-sparse-xfs-readonly-status-proof.json, large-sparse-xfs-resource-monitor-proof.json, large-sparse-xfs-post-deadline-process.json, large-sparse-xfs-post-exit-journal-identity.json, large-sparse-xfs-current-attachment-read.json, large-sparse-xfs-job-fresh-read.json. #974는 OPEN이며 SPARSE 정상 format/resume/response loss/partial와 ext4·일반 block·실제 UI 최종 인수가 남는다.

## 승인된 no-modify 진단 결과

xfs_repair6.1.0 -n -m512 -P가 fresh exact serial/10TiB/ROOTancestor 제외/unmounted/WRITER_IDLE를 확인한 NEW021b만 읽었다. primary superblock의 **filesystem mkfs-in-progress bit set**를 실제 검출했고 secondary superblock scan 중120.000520875초 deadline으로 종료했다. PID151105/startTicks1304904에 pidfd TERM을 전달해 exit-15/terminationPending=false, KILL 없이 종료됐다. fresh child0/writerIdle=true를 확인했다.

AS1GiB를 강제했고 관측 RSS5.09MB/AS9.34MB였다. 대상 write counters4/0/4174337/645120과64KiBheaderSHA1f036503a7447d1c8cf2c9b046a8337a831f4d4b5487b3dfc799a82703d98eec는 전후 동일하다. FSUUID f8c35...와 TIMED_OUT_PENDING_RECONCILE journal을 보존했다. **filesystemHealthy=false/FORMAT_COMPLETE승격false**다. -L/쓰기 repair/mount/새 mkfs/VM51 reboot/detach/delete를 수행하지 않았다. 진단 종료는 filesystem 완성이나 자동 resume 허가가 아니다.

증거는 sparse-partial-xfs-readonly-diagnostic-summary.json/full proof/inflight fresh child0 파일이다. pool factor4와 partial 볼륨은 여전히 보존되며 finally override 제거/상속1 복원은 미완료다. 현재 factor1 환산 logical allocation307.75%이므로 원복과 후속 allocation 제약을 구분해서 보고한다.
