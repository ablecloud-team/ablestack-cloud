# #909 / #974 실제 후속 시험의 용량 배치 계획

2026-10-08 09:22 UTC 읽기 전용 DB/API/host 대조. 현재 관리 서버는 0c70 선택 오버레이이다. 전체 HEAD의 모든 기능을 배포했다고 간주하지 않는다. 새 ROOT/DATA/백업은 모두 SPARSE 또는 FAT만 허용한다. partial VM51/021b 및 원래 DATA는 보존한다. 생성 및 포맷 승인 전 계획이다.

## 정상 allocator 한도

| 항목 | 실제 값 |
| --- | --- |
| 유일한 등록 pool | 90b0c4e3-7078-4372-8da0-9625bc0e806a, SharedMountPoint/CLUSTER |
| 물리 총량 | 4,079,912,058,880 B |
| 물리 여유 | 약 3.499 TiB, 세 호스트가 같은 GFS이므로 합산 금지 |
| DB allocated, capacity type 3 | 12,556,027,510,048 B |
| logical total, factor4 | 16,319,648,235,520 B |
| allocated / physical disablethreshold | 각각 0.85, pool/zone override 없음 |
| 정상 allocator 잔여 | 1,315,673,490,144 B = 1.1966 TiB, 정수 1225 GiB |
| global factor / custom max / volume max | 1 / 4096 GiB / 40000 GiB, 변경 없음 |
| pool factor guard | override4, guard23325 보존, finally 복구 미완료 |

StorageManagerImpl.checkPoolforSpace는 기존 할당량 + 새 요청(템플릿 및 snapshot reserve 포함)을 85%와 비교한다. 재시작 직후 API disksizeallocated=0은 실제 여유가 아니다. DB live volume31개 합계12,556,026,511,360 B와 capacity의 약1 MB 차이를 임의 제거하지 않는다.

| 추가 DATA | 기존 할당 + 요청 / logical total | 정상 API |
| --- | --- | --- |
| 10 TiB | 144.312% | 거절 |
| 3 TiB | 97.150% | 거절 |
| 1 TiB | 83.675% | 새 ROOT/예약량 재확인 후 가능 |
| 1280 GiB | 85.360% | 거절 |

호스트 OS root libvirt pool은 약412 GiB이며 Cloud 등록 pool이 아니다. 동일 GFS 중복 등록, allocator bypass, manual DB, partial 삭제는 계획에 포함하지 않는다.

## #974 보조 시험과 정확한 10 TiB

현재 설정 그대로 가능한 후보는 NEW 1 TiB SPARSE XFS → 자기 NEW DATA 정리 → NEW 1 TiB SPARSE EXT4 순차 시험이다. **1 TiB는 정확한 10 TiB 요구를 대체하지 않는다.** 3 TiB는 현 설정으로 정상 할당할 수 없다.

- 새 전용 SharedFS epic898-sparse-large-audit-08: fixed SPARSE 2C4GB SO fc808c8b-00fa-42d2-98b6-a082f12c347f, 초기 SPARSE DATA20 GiB. linked ROOT offering SPARSE 및 실제 qemu metadata 생성도 검증한다.
- 1024 GiB DATA는 정상 API와 새 fixed SPARSE offering 또는 호환 customized SPARSE offering을 쓴다. 정확한 volume UUID/owner/zone/pool/provisioning, guest serial/size, ROOT ancestor 제외를 포맷 전에 검증한다.
- ROOT5 + 초기DATA20 + 추가DATA1024 = 1049 GiB 추가 시 약83.84%, threshold까지 약176 GiB 여유. 실제 template reservation/다른 allocation을 fresh DB로 다시 계산한 뒤 생성한다.
- signed native SKIP_DISCARD 능력, 실제 XFS -K / EXT4 nodiscard source와 argv/request echo를 검증한다. old VM51 CLI 변경/업그레이드로 이를 대신하지 않는다.
- 실제 qcow2 preallocation=metadata/backing-none/virtual size/host allocation, formatter AS1 GiB, own physical growth160 GiB 이하, 공유 FS free3.30 TiB 이상을 검증한다.
- exact NEW formatter STOP60s/CONT1회, bounded readonly API, 부모 작업 탭 UI, 완료 후 NFS/SMB I/O 및 새 fixture만 reboot 후 FS UUID/STATIC/hash/alignment을 검증한다.
- formatStarted 후 timeout/partial이면 새 mkfs/자동삭제/detach 없이 journal/identity를 보존하고 정식 resume/reconcile 승인 경로로 연결한다.
- XFS 정상 완료 뒤 exact own share → detach → deleteVolume provenance를 확인하고 EXT4를 순차 진행한다. 기존 partial021b 및 initial20은 cleanup 대상이 아니다.

정확한 새 10 TiB는 현 pool factor4/threshold0.85로 불가능하다. 같은 pool이면 ROOT/template 여유 전부터 최소 factor 약6.791이 필요하며 기존 factor4 승인을 넘는다. 별도 실제 물리 capacity/독립 pool 또는 ratio 정책에 대한 추가 판단이 필요하다. 지금 ratio/threshold 변경, partial 삭제, manual DB, 같은 GFS 중복 pool 등록은 하지 않는다.

## #909 multi NEW 실제 배치

먼저 source/target 두 새 SPARSE fixture만 검증·정리하고 큰 시험을 순차 실행하면 용량 경쟁을 줄인다. 각 ROOT약5 + DATA4개×20 = 85 GiB, 두 fixture 약170 GiB 논리 예약이다. 현재 factor4 정상 한도에는 들어가지만 factor4의 기존 임시 scope와 후속 용도를 부모가 합의한 뒤 생성한다.

| source DATA | 소비자 | target NEW 조건 |
| --- | --- | --- |
| 초기 XFS20 GiB | NFS + SMB | SharedFS creator 실제 initial UUID로 bind, 중복 allocation0 |
| 추가 EXT420 GiB | NFS + SMB | deterministic UUID, format1회, fresh FS UUID |
| RAW20 GiB | iSCSI target/LUN | allocate/attach, mkfs0, bounded offset sentinel |
| RAW20 GiB | NVMe namespace + subsystem | namespace RAW, volume 없는 실제 subsystem container만 skip |

정상 API 순서: source4DATA/protocol runtime/client I/O → createStorageServiceConfigBackup → download/upload/validate → planStorageServiceConfigRestore(targetmode=CREATE_NEW,mapping) → dry-run 검토 → applyStorageServiceConfigRestore(artifactid,plantoken,confirmation,credentials RAM-only) → target 관측/client검증. 구성 번들은 DATA 내용 백업이 아니므로 source 파일 복제를 주장하지 않는다.

mapping.volumes에는 네 source UUID 각각 NEW; newVolumes[sourceUuid]에는 diskofferingid/storageid/필요 sizeGiB/dataPolicy=PRESERVE. createNew는 zone/network/fixed SPARSE SO/SPARSE initial DO/pool/STATIC 고유 주소/이름을 지정한다. 실제 UUID는 생성 후 고정하고 initialVolumeSourceUuid/signed runtimeBundleUuid를 freeze한다.

필수 증거: frozen planSHA → parentSHA/executionSHA → additional plannedUUID 보존 → 표준 customID allocation+volume_details+ALLOCATEDreceipt 동일 transaction → physical/attach/FORMAT_STARTED/PREPARED CAS → exact owner/domain/project/zone/pool/offering/provisioning/targetVM/revision. FILE format1회, RAW0회.

Fault 게이트: response loss/동일 artifact replay/관리 재시작 전후 UUID·count 보존; INTENT/ALLOCATED/attach 실패의 receipt/PRESERVE; formatted partial 재포맷·자동삭제0; foreign UUID/owner/pool/revision/provisioning 거절; imported Ready DATA 삭제0; source hash/FS identity 보존. 재시작은 부모 critical 일정과 조율한다.

시작 전 배포된 adapter 클래스/signed native rendered config/formatcap/fresh protected template platform/protocol4종 client/IP고유성/SPARSE ROOT 실제생성을 검증한다. verified configuration flag는 fresh API true이다. helper40 PASS/package439 PASS는 실제 multi NEW 완료 증거가 아니다.

## pool finally 경계

원래 override ABSENT/global1. guard23325 임의 종료/manual DB 해제0. partial021b 보존 상태 factor1 복귀는 기존 할당률 약307.75%이며 새 allocation을 막는다. 기존 서비스 영향 및 다음 시험 capacity를 부모가 검토한 뒤 정상 reset API, DB ABSENT/API inherit1/global 불변을 separately 검증한다. partial 보존/finally 미완료를 계속 명시한다.

최종 UI #1275 스타일/버튼/테마/키보드 QA는 착수하지 않는다.
