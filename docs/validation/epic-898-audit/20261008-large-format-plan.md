# #974 10TiB 실제 준비 작업 계획 — 2026-10-08

아직 large volume 생성·pool 설정 변경·포맷은 하지 않았다. 다음 계획을 부모에게 보고하고 생성 시작 신호를 받은 뒤 수행한다. 원래 VM39/41/49와 SMB 시험 VM50는 변경하지 않고 새 fixture만 사용한다.

## 현재 환경의 확인값

| 항목 | 실제 값 |
| --- | --- |
| target pool | 90b0c4e3-7078-4372-8da0-9625bc0e806a / SharedMountPoint / /mnt/glue-gfs / GFS2 |
| physical capacity/free | 4,079,912,058,880 / 3,870,144,983,040 bytes (3.711/3.520TiB) |
| logical allocated | 1,534,193,515,808 bytes (1.395TiB) |
| current overprovision | global1 / pool effective1.0 / pool override row없음(readonlyDB) |
| utilization limits | allocated85%, physical85% |
| requested DATA | 10,995,116,277,760 bytes =10TiB =10240GiB |
| volume max | storage.max.volume.size40000GiB |
| custom volume max | custom.diskoffering.size.max4096GiB |
| actual backend format | 새20GiB DATA qcow2 64KiB cluster, physical5,464,064 bytes |
| formatter deadline | min300 +120초×ceil(10TiB)=1500초 / QGA1620초 / probe20초 |

기존 custom offering으로 createVolume size10240는4096GiB 한도를 넘는다. 새 고정크기10TiB offering(customizedfalse)에는 size parameter를 다시 전달하지 않는다. fixed offering은 전체 max40000GiB 한도 안에 있고 custom4096 한도를 변경할 필요가 없다.

pool-scoped factor4는 부모에게 승인받았다. 적용 시 기존 allocated+새10TiB의 논리비율은76.8%로85%미만이다. factor1의 현재 pool로는10TiB Cloud할당이 불가능하다. 기존 override row가 없으므로 최종에는 resetConfiguration(name=storage.overprovisioning.factor, storageid=target)로 scope override를 제거해 상속1로 복구한다. global값은 변경하지 않는다.

## 디스크에 쓰지 않은 formatter geometry 계산

현재 Debian SystemVM의 mkfs.xfs/mkfs.ext4를 실제 실행하되 익명10TiB memfd에 F_SEAL_WRITE/GROW/SHRINK/SEAL을 걸고 각각 -N/-n으로 geometry만 계산했다. 파일시스템/디스크 파일을 만들지 않았고 앞뒤 allocated bytes0을 검증했다. 두 도구는1GiB RLIMIT_AS 안에서 정상 종료했다.

- XFS:4096byte block, AG10개, sector512, internal log521728block=약1.99GiB. AG header/Btree 등 작은 metadata와 qcow2 allocation overhead를 포함해 초기 physical 쓰기 예산16GiB로 잡는다.
- ext4:4096byte block, 335,544,320inode. inode256byte 기준 inode table80GiB, block/inode bitmap·descriptor·journal 등을 포함해 보수적 physical 쓰기 예산128GiB로 잡는다. lazy inode 초기화가 mount후 이어질 수 있으므로 종료까지 공간을 관측한다.
- dry-run의 UUID는 실제 volume filesystem UUID가 아니다. 포맷 성공 증거로 계산하지 않는다.
- 실제 formatter는 MemAvailable2GiB 이상에서만 시작하고 formatterRSS1GiB 초과나 이번 시험의 physical 증가160GiB 초과/host free3.30TiB 미만이면 해당 NEW formatter만 중지해 진단을 보존한다. 전체 host/guest나 다른 volume을 종료하지 않는다.

위 예산은 geometry 기반의 보수적 시험 한도다. 실제 쓰기량과 peakRSS는 포맷 진행 중 측정해 기록한다. 현재 physical 여유3.52TiB를 넘는 쓰기를 허용하지 않는다.

## 정확한 실행 순서

1. listConfigurations(name=storage.overprovisioning.factor,storageid=target), scoped detail 존재 여부, pool capacity/allocated/used/free, global max/custom값을 재조회한다. 비밀이 아닌 원래 scope 상태를 proof에 기록한다.
2. try/finally를 세운 뒤 updateConfiguration(name=storage.overprovisioning.factor,value=4,storageid=target)하고 pool effective4.0을 확인한다.
3. createDiskOffering(name=epic898-format10t-fixed-audit,displaytext=...,disksize=10240,customized=false,provisioningtype=thin,storagetype=shared,tags=glue-gfs,cachemode=writeback,displayoffering=false). global 설정은 변경하지 않는다.
4. 새 SharedFS epic898-format10t-audit-07을 기존 SO2C4GB/Ready template와 DHCP network에20GiB initial DATA로 생성한다. 현재 runtime93a 또는 후속 검증된 signed runtime으로 업그레이드한다. 초기20GiB는 임의 포맷하지 않는다.
5. createVolume(name=epic898-format10t-xfs-audit,zoneid=13zone,diskofferingid=fixed10t,storageid=target)에서 size를 생략하고, 새VM에 attachVolume(id=newDATA,virtualmachineid=newVM). 반환UUID·owner·zone·pool·typeDATADISK·expectedSize10TiB·SCSIserial·root제외를 확인한다. 소스가 storageid를 해당 API에서 허용하는지 binding contract를 마지막으로 검증한다.
6. guest lsblk/blkid/wipefs --no-act, sector/logical/physical alignment, existing mount/signature 없음, 같은 volume UUID+size+serial을 확인한다. NEW 대상에만 FORMAT_IF_EMPTY를 요청한다.
7. 해당 새10TiB volume을 명시한 createStorageNfsExport를 실행하고 getStorageServiceVolumePreparation(instanceid,volumeid)로 같은 volume operation ID/FORMATTING heartbeat를 관측한다. 실제 mkfs PID·starttime·argv·NEW target device가 모두 일치한 프로세스만 SIGSTOP25초→SIGCONT해20초를 초과하는 실제 formatter를 검증한다. 바이너리나 공통 entrypoint를 교체하지 않는다.
8. COMPLETE/TYPEXFS+UUID/mountRW/한정파일write+fsync+hash/size/alignment, API·share·volume·nativejournal 상태를 대조한다. 재부팅 후 stable UUID와 같은 파일hash를 재확인한다.
9. mkfs 완료 후 응답 손실, formatter 중단/guest 또는 MGT재시작, blank/partial signature, wrongUUID/foreign/duplicate 요청은 새 전용 volume별로 나눠 시험한다. formatStarted/UUID를 관측하고 자동 재포맷이 발생하지 않음을 확인한다. 관리 서버 중단은 부모와 시점을 합의한다.
10. XFS 시험 volume을 정확한 ownedID 확인후PRESERVE/detach로 보존해 proof를 확보하거나 폐기 승인을 받은 disposableID만 제거한다. 추가10TiB ext4는 동시생성이 아니라 XFS cleanup 후 같은 논리reservation 안에서 순차 실행한다.
11. finally에서 새 DATA/fixture를 정상stop/detach/허용된정확ID의삭제로 정리하고 logical10TiB reservation을 해제한 뒤 pool scope를 resetConfiguration으로 제거한다. original/global factor1·override없음·원본VM/volume IDs/hash 미변경을 검증한다. 실패/정리pending이면 그 범위만 보고하고 원본DATA삭제로 해결하지 않는다.

## 게이트의 구분

현재 getStorageServiceVolumePreparation은 새20GiB XFS의 COMPLETE/formatDeadline420/FSUUID/formatterActivefalse를 실제 반환한다. 이것은10TiB 인수 완료가 아니다.

현 소스는 native volume journal의 동일 UUID/fs 재관측 재개를 지원하지만 전용 resume API·DB/UI 연계와 RECOVERY_REQUIRED의 전체 사용자 흐름은 미완료다. resume를 기존 share재시도 API로 검증한 결과와 정식 resume UI 완료를 구분한다.

현재 클러스터에는3.711TiB physical file-backed pool만 있다. 일반/preallocated10TiB 물리 시험은 이 환경의 용량을 넘는다. 실제10TiB thin XFS/ext4와 별도 작은 정상 block/preallocation 회귀를 분리하며, 후자를 자동으로10TiB 일반block완료로 주장하지 않는다.

읽기 증거: large-format-readonly-environment.json, large-format-sealed-layout-readonly.json, large-format-small-volume-preparation-status.json, Epic898PoolScopeReadProbe.java. 생성 시작 전 exact API binding과 fresh physical free를 재확인한다.
