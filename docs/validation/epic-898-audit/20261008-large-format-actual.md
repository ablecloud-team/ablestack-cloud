# #974 실제 10TiB XFS·ext4 부분 인수 — 2026-10-08

새 사용자 기준: 앞으로 모든 NEW 디스크는 SPARSE 이상(SPARSE/FAT)이어야 한다. 아래 THIN 10TiB XFS·ext4 실험과 계획은 과거 기록이며 현재 #974 인수 증거에서 제외한다. 기존 DATA는 재포맷하지 않고 새 10TiB SPARSE DATA로 재시험한다.

검증은 부모의 명시적 승인 후 별도 NEW fixture와 새 DATA에만 수행했다. 원래 VM39/41/49와 SMB fixture50는 변경하지 않았다. 초기20GiB fixture51은 남기고 큰 DATA는 XFS와ext4를한개씩순차생성한다. XFS와 ext4 모두 실제 UI 검증 후 정리를 완료했다. 전체 #974 완료 기록이 아니다.

| 항목 | 값 |
| --- | --- |
| Backend | bffad8295c281cfa30422d545b0c526fa410206b / 13번 MGT |
| Host agents | 변경 API2/KVM4 클래스만 배포한 동일 bffad 계열 |
| Runtime | signed epic898-20261008-config-generation-agent / source93a |
| SharedFS | 4b760728-104b-4f90-a039-adca78d31a4c / epic898-format10t-audit-07 |
| instance / VM | 0b5a950b-7362-4cc6-8c48-81f938bbe26e / e38ab665-a5db-44a0-90f6-5ed06ee5df9f / i-2-51-VM@13.2 |
| network | STATIC10.10.13.242/16, gateway10.10.0.1, duplicate ARP응답0/Cloud primary+secondary점유0 |
| 기본 DATA | 5368d530-d290-48db-b6d6-9f20d34b5d31 /20GiB, 유지 |
| offering | bf945173-ad0e-48f5-b8c5-ccc8ca9f4138 / fixed10240GiB/THIN/shared/glue-gfs/writeback/hidden |
| scoped config | pool90b0c4e3만일시factor4, 원래override없음/상속1, global1/max40000/custom4096 불변 |
| 관리 절차 | 정확한 pool의 resetConfiguration으로 임시 override를 삭제했다. DB read-only로 override 부재와 API 상속값 1.0을 확인했다. |

## 실제 포맷

| 항목 | XFS | ext4 |
| --- | --- | --- |
| NEW DATA UUID | 25defed5-18b3-482f-8e64-4db5084446c0 | cda5578e-7c28-493d-9c0c-5c1691d15920 |
| virtual size | 10,995,116,277,760 bytes | 10,995,116,277,760 bytes |
| formatter/actualPID/starttime | mkfs.xfs /13935/57929 | mkfs.ext4 /9421/52123 |
| actual25초 pause | 25.000372초 | 25.000424초 |
| address space limit | exact ownedPID에1GiB prlimit | exact ownedPID에1GiB prlimit |
| native elapsed | 69.176204초 | 71.562968초 |
| format/QGA/probe deadline | 1500/1620/20초 | 1500/1620/20초 |
| native phase | COMPLETE | COMPLETE |
| FSUUID | e7406f6f-afb5-47f8-a299-705e5d5e35bf | 17c46a56-dc92-4cd5-a3ac-8c9554ec0154 |
| 실제 createNFS job | 26941a4e-3e76-436b-a0f3-b38704791040 SUCCESS | 8e82d159-6ba1-4a55-be71-b62a2b06b0a6 SUCCESS |
| qcow2 actual bytes | 1,348,476,928 (약1.26GiB) | 1,817,124,864 (약1.69GiB) |
| physicalfree | 약3.518TiB | 약3.517TiB |
| owner-device 검증 |10TiB/serial25defed518b3482f8e64/root제외/미마운트 |10TiB/serialcda5578e7c28493d9c0c/root제외/미마운트 |
| sector/fsblock |512logical/512physical/4KiB XFS·AG10 |512logical/512physical/4KiB ext4·inode335544320 |

포맷 전에새UUID·owner·zone·pool·typeDATADISK·size·serial을확인했다. watcher는exactvolume operation journal/PID/argvdevice/procstarttime을대조한뒤그formatter만SIGSTOP25초→SIGCONT했다. 임의device/rootdisk/다른PID는신호대상이아니다. 바이너리나공통entrypoint를교체하지 않았다. formatter가20초를실제로초과해도완료했다.

EXT4 monitor에서RSS8.2MiB/virtual11.9MiB를관측했고address-space1GiB제한을강제했다. 이관측값은전구간peak계측이라고주장하지 않는다. physical160GiB/RSS1GiB/free3.30TiB guard를두었고한도위반은없었다. ext4 lazy inode initialization의lifetimewrite51GiB도qcow2 physicalbudget안이었다.

## 실제 외부 클라이언트와 재부팅

host13.1에서새STATIC242의NFS4.1와CIFS3.1.1을mount했다. 각서브디렉터리에작은baseline만write/fsync/read했고모든clientmount는해제했다. CIFS비밀번호는RAM→SSHstdin→PASSWD환경변수로만사용하고파일·로그에보관하지 않았다.

| sentinel | 생성과재부팅후 SHA-256 | UID/GID·mode·inode |
| --- | --- | --- |
| XFS NFS | e193dca5a9eb5c01094ec7a2f7212f57cafcb0d4791d9ae9fcba3716028dcb29 |65534:65534/0644/132 |
| XFS SMB | aab30f265b43b3275b756c77ed41d3dc4cd2ea201c6578e2fdaf5770332513b1 |1001:1001/0660/4294967425 |
| ext4 NFS |0098918e77e6e111679b0fe5326d22315b93ad0fa36006cffd32e7fa86627938 |65534:65534/0644/196739074 |
| ext4 SMB |fd84a44f6272f76e22f22782a1803ad23e2909e97bfb742cfa3d5637b426bf69 |1001:1001/0660/102760451 |

XFS와ext4 각각같은CIFScredential을동일프로세스RAM에유지해beforeauth/read→reboot→afterauth/read를검증했다. XFSboot은c686b519…(후속동일credential검증별도), ext4최종boot은b1118a63-398b-4d2e-8bca-b6a6dd23da7f다. STATIC242/gateway도유지됐다.

재부팅후XFSdevice는sdc→sdb, ext4는sdd→sdb로바뀌었다. volumeUUID의mountsource를freshre-resolve해같은FSUUID/10TiB/sector/fsblock과sentinel을확인했다. 이전device명만믿은첫진단은initial20GiB를읽어수정했고그출력도별도증거로보존한다. 어떤device도다시포맷하지 않았다.

## 시험중 발견한경로와남은게이트

- 일반attachVolume은SharedFS VM보호로431차단됐다. 보호를우회하지않고StorageService createNFS의내부attach(forceShareFS)를사용했다.
- 첫잘못된relativepath 요청은mutation전에BLOCKED됐다. 바르게매핑한retry에서만실포맷이실행됐다.
- 장기writer중getStorageServiceVolumePreparation API가client45초timeout했다. 같은기간guestdirectjournal와asyncjob 조회는정상이다. UIbackgroundprogress·독립상태조회와공통transport serialization을후속검증한다.
- 완료journal의devicePath는old경로로남아있으며reboot후다른20GiB disk일수있다. 현 HEAD5de32797의 API source는 historicalDevicePathOnly=true와 fresh currentIdentity(EXACT/UNAVAILABLE)를 추가했다. StorageServiceHostCommand도 read-only operations를 host-wide sequential queue에서 분리했다. 이 변경은 이번 live bff/source93a 실증 이후의 source 보완이며, 배포 후 장기 formatter 동안 bounded status와 reboot 후 fresh identity를 다시 실제 검증해야 한다.
- CreateVolume displayvolume=false 때문에 일반 listVolumes/UI backing 표가 비어 보였다. 부모가 scope bounded/known UUID/RootAdmin hidden volume fallback을 보완한 live UI를 배포했다. ext4 NFS·SMB 양쪽 backing 표에서 cda557 UUID, 이름, 10.0TiB, ext4, 현재 /dev/sdb, EXACT, Ready를 실제 DOM과 PNG로 검증했다. UI는 부모 전담이며 이 감사 에이전트는 브라우저를 조작하지 않았다.
- 같은instance/action/idempotencykey를다른resource에재사용한harness가oldCOMPLETE를리플레이받았다. source는requestfingerprint없는현재계약이며harness키를별도로수정했다. #892의target/비밀외params동일성검증과구분해기록한다.
- reboot API의VMRunning후protocolreadiness가잠시지연돼445connectionrefused가있었다. boundedlistenerretry후동일credential/data를검증했다.
- 응답직전통신손실·formatter도중MGT/guest재시작·partial/blank/foreign/wrongUUID·duplicate/resume/manualrecovery·일반blockstorage·정식resume API/UI·progress 전체인수는아직남는다. 이결과로 #974를닫지 않는다.

## 정리

XFS의ownedSMB/NFSshare만delete→volume25defed5만detach→deleteVolume했다. activevolume제거와capacityfresh조회1.5609TB복귀를확인하고나서만ext4한개를생성했다. 즉동시에20TiB를예약하지 않았다.

EXT4의 소유한 SMB/NFS share를 삭제하고 정확한 cda557 NEW DATA만 detach→deleteVolume했다. DB read-only에서 XFS와 ext4 모두 Expunged/removed/vm=NULL을 확인했다. 기본 20GiB 5368d530 DATA는 Ready/vm51, fixture51은 유지한다. 원본 DATA를 정리에 사용하지 않았다.

pool guard의 finally 첫 reset은 오래된 API session 401로 실패했다. 새 login으로 동일 pool의 resetConfiguration을 재시도해 성공했고, DB read-only에서 scoped override 부재, API effective factor 1.0 및 details 키 제거를 확인했다. global factor 1, storage.max.volume.size 40000, custom.diskoffering.size.max 4096, min 1은 그대로다. 명시값 1 override를 남긴 것이 아니라 원래 상속 상태로 복원했다.

큰 두 DATA 삭제 후 nondestroyed volume 30개 size 합은 1,560,910,233,600 bytes로 원복됐다. 삭제 직후 op_host_capacity type3/listStoragePools에는 12,556,027,510,048 bytes의 오래된 집계가 잠시 남았다. 12:12 KST fresh API에서 disksizeallocated가 1,560,911,232,288 bytes로 복귀했다(볼륨 합에 template/overhead 포함). CapacityManagerImpl.getAllocatedPoolCapacity의 fresh nondestroyed volume 합과 API 표시의 일시 집계 지연을 구분했다. 모든 formatter/API critical은 종료해 부모에 재배포 가능 신호를 보냈다.

증거는WSLscratch acceptance-audit의large-xfs-*/large-ext4-* JSON, post-bffad-original-six-readonly-health.json 및large-pool-factor-guard.json에있다. 기존5+fixture50는bffad배포후모두Running/Ready/healthok이며기존IP도유지됐다. 큰전체이슈의완료와작은성공게이트를분리한다.
