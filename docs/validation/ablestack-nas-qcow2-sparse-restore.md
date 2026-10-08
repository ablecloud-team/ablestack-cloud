# ABLESTACK NAS QCOW2 증분 복원: `-S 0` / `-S 4k` 비교 테스트

## 목적과 범위

원본 Disk Offering과 최종 convert의 sparse 옵션을 분리해 확인한다. 대상은 **ablestack-nas의 QCOW2 증분 백업을 파일 볼륨으로 복원하는 경로**다. 기존 최신 leaf 선택, backing chain 읽기, 임시 파일 생성 및 볼륨 교체 순서를 사용한다. 두 모드의 convert 명령 차이는 `-S` 값 하나다.

- `FULL_ALLOCATED`: `qemu-img convert -p -S 0 -f qcow2 -O qcow2 <leaf> <temporary-target>`
- `SPARSE`: `qemu-img convert -p -S 4k -f qcow2 -O qcow2 <leaf> <temporary-target>`

rebase, 체인 재구성, preallocation, cache, coroutine, `--target-is-zero` 등의 옵션은 추가하지 않는다. 독립 FULL의 기존 복사 경로, RAW, RBD, 다른 백업 솔루션에는 테스트 모드를 적용하지 않는다. primary storage 공간 사전 검사는 기존 기준을 유지하므로 SPARSE 테스트에도 전체 가상 용량과 여유 공간을 준비한다.

## 테스트 모드 설정

새 KVM agent 코드를 설치한 **실제 복원 호스트**에 다음 파일을 만든다. NAS가 백업 데이터를 읽는 저장소 서버가 아니라 convert를 실행하는 KVM 호스트다.

`/etc/cloudstack/agent/ablestack-nas-restore-test.properties`

```properties
enabled=true
sparse.mode=FULL_ALLOCATED
fail.on.compare=true
source.disk.offering=THIN
case.id=THIN-INC3-S0-R1
```

다음 복원에서는 아래처럼 변경한다.

```properties
enabled=true
sparse.mode=SPARSE
fail.on.compare=true
source.disk.offering=THIN
case.id=THIN-INC3-S4k-R1
```

파일이 없거나 `enabled=false`이면 기존 운영 경로를 사용한다. 활성 설정의 잘못된 mode/boolean 값은 복원 전에 오류로 처리한다. 이 설정은 글로벌 설정이나 Disk Offering 정책이 아니라 **호스트별 테스트 설정**이며, 해당 호스트의 NAS 증분 파일 복원에 적용된다. 설정은 볼륨 복원 진입 시 읽고 해당 볼륨 처리 동안 유지한다. 다중 디스크 복원 중에는 설정을 바꾸지 않는다. 호스트 간 복원 배치가 달라지면 각 테스트 호스트의 설정도 확인한다.

`source.disk.offering`은 테스트 담당자가 입력하는 원본 오퍼링 라벨이다. 실제 오퍼링을 자동 판별한 값이 아니다. 원본 VM/볼륨의 오퍼링, provisioning type 및 실제 할당량을 별도 기록하고 라벨과 일치시키며, 새 복원 VM의 오퍼링과 혼동하지 않는다. 테스트 후 `enabled=false`로 돌린다. 파일 설정만 바꾸는 경우 agent 재시작은 필요하지 않다.

## 검사 순서와 실패 처리

1. 실제 선택 leaf 및 전달된 `backupPaths`를 기록한다.
2. `qemu-img info --backing-chain --output=json <leaf>`를 기록한다. JSON 순서는 **leaf → FULL**이다. 각 이미지의 format, virtual-size, actual-size, backing-filename, cluster-size를 남긴다.
3. 실제 chain의 QCOW2 및 전달된 QCOW2 경로에 `qemu-img check -f qcow2`를 실행한다. source check 결과는 관찰용이며, 실패만으로 복원을 중단하지 않는다. `-r`로 원본을 수리하지 않는다.
4. 기존 볼륨을 moved-aside로 보존하고 임시 파일에 convert를 실행한다. 시작/종료 시각과 경과 시간을 기록한다.
5. 임시 파일에 `qemu-img info --output=json`, `qemu-img check -f qcow2`, `stat -c '%s %b %B'`를 실행한다.
6. `qemu-img compare -f qcow2 -F qcow2 <leaf> <temporary-target>`를 실행한다. `-s`는 쓰지 않는다.
7. 검증 후에만 임시 파일을 실제 볼륨으로 교체하고 이전 파일을 삭제한다.

기본값 `fail.on.compare=true`에서는 compare 1~4, 실행 오류 및 응답 실패를 모두 복원 실패로 처리한다. 실패하면 임시 파일을 실제 볼륨으로 승격하지 않고 기존 moved-aside 볼륨을 원복한다. 원복 자체에 실패하면 기존 `.bak` 파일을 보존하고 `TARGET_MOVED_ASIDE_RESTORE_FAILED`를 기록한다. 실패한 임시 출력은 기존 정리 방식대로 삭제되므로 진단 로그를 보관한다.

target check 실패, 정보 조회 실패, 가상 용량 차이, backing file 잔존, 실제 할당량 조회 실패도 테스트 복원을 실패시킨다. 비교 및 요약 로그를 남긴 뒤 판정한다. `fail.on.compare=false`는 불일치/compare 오류 시에도 교체를 허용하는 별도 관찰 정책이므로 이번 6개 검증에서는 **true를 유지한다**. 이 설정으로 target 구조 검사를 생략하지는 않는다.

compare exit code:

| 값 | 의미 |
|---|---|
| 0 | 논리 데이터 동일 |
| 1 | 논리 데이터 불일치 |
| 2 | 이미지 열기 오류 |
| 3 | 섹터 할당 조회 오류 |
| 4 | 데이터 읽기 오류 |
| -1 등 | 명령 실행 실패 또는 결과 확인 불가 |

compare의 일반 모드는 할당 구조가 달라도 읽히는 데이터가 같으면 동일하게 판단한다. 가상 용량이 다르더라도 추가 영역이 0이면 동일 판정이 가능하므로 테스트 코드에서 가상 용량 일치를 별도로 확인한다. [QEMU qemu-img 공식 문서](https://www.qemu.org/docs/master/tools/qemu-img.html#cmdoption-qemu-img-arg-compare)

## 로그 수집

기존 로그 형식을 사용한다.

```text
[ABLESTACK_BACKUP_TRACE] provider=[ablestack-nas] operation=[RESTORE] phase=[QCOW2_CONVERT_BEGIN], testCase=[THIN-INC3-S4k-R1], sparseMode=[SPARSE], sparseSize=[4k], source=[...], temporaryTarget=[...], convertStart=[...]
[ABLESTACK_BACKUP_TRACE] provider=[ablestack-nas] operation=[RESTORE] phase=[QCOW2_COMPARE], source=[...], target=[...], exitCode=[0], result=[IDENTICAL], elapsedMillis=[...], output=[...]
```

기존 비동기 restore job의 `jobLog`와 agent 로그를 보관한다. `ENTER`의 restoreJobId/jobLog, 대상 VM, 전체 디스크의 source/target이 포함되도록 수집한다.

```bash
# JOB_LOG는 기존 ENTER / RESTORE_COMMAND_SEND에 출력된 실제 경로 지정
JOB_LOG='/var/lib/ablestack/backup/jobs/<job-id>/job.log'
rg 'phase=\[(QCOW2_|TEMP_TARGET_|TARGET_MOVED_ASIDE|FILE_RESTORE_FAILED)' "$JOB_LOG"
```

`QCOW2_TEST_SUMMARY`에 케이스, 원본 오퍼링 라벨, mode, `-S`, source/target 크기, source checks, target check, compare, convert/compare 시간, backing 잔존 여부를 기록한다.

할당량은 `apparentSizeBytes = stat %s`, `allocatedBytes = stat %b × %B`, `allocationRatio = allocatedBytes / virtualSize × 100`으로 계산한다. QCOW2 메타데이터 때문에 비율이 100%를 넘을 수도 있다. `ls -lh`만으로 Thin 여부를 판단하지 않는다. source actual-size는 leaf 파일의 점유량이며 chain 전체의 합계가 아니다.

## 1 TB 주요 테스트

| 원본 오퍼링 | 복원 지점 | mode | `-S` | 케이스 |
|---|---|---|---|---|
| THIN | INC3 | FULL_ALLOCATED | 0 | THIN-INC3-S0-R1 |
| THIN | 동일한 INC3 | SPARSE | 4k | THIN-INC3-S4k-R1 |
| SPARSE | INC3 | FULL_ALLOCATED | 0 | SPARSE-INC3-S0-R1 |
| SPARSE | 동일한 INC3 | SPARSE | 4k | SPARSE-INC3-S4k-R1 |
| FAT | INC3 | FULL_ALLOCATED | 0 | FAT-INC3-S0-R1 |
| FAT | 동일한 INC3 | SPARSE | 4k | FAT-INC3-S4k-R1 |

1. 각 오퍼링으로 동일한 게스트 OS/가상 용량, 디스크 수, 부팅 설정의 검증 VM을 준비한다. 1 TB의 단위(TB/TiB)를 통일하고 실제 virtual-size를 기록한다.
2. 원본 오퍼링 정보, 정지 상태에서의 `qemu-img info --output=json` / `stat`, QEMU/libvirt 버전, NAS와 primary의 filesystem/mount 설정, 여유 공간을 보관한다. 실행 중인 원본 디스크에는 `qemu-img check`를 실행하지 않는다.
3. 게스트에 재현 가능한 동일한 데이터를 약 500 GB 작성하고 `sync` 후 FULL을 백업한다. 데이터에는 비제로 영역과 알려진 제로 영역을 포함한다.
4. 약 100 GB를 추가해 INC1, 기존 파일 수정·삭제·신규 추가로 INC2, 약 100 GB의 추가 변경으로 INC3를 백업한다. 파일 삭제만으로 해당 블록이 0이 되지는 않을 수 있으므로 discard/fstrim 사용 여부를 통일하고 기록한다.
5. 각 단계의 파일 목록·checksum·filesystem 상태를 보관하고, INC3 시점의 manifest를 VM 외부로 복사한다. 모든 오퍼링에서 동일한 데이터와 변경 패턴을 사용한다.
6. 동일한 `INC3 → INC2 → INC1 → FULL`을 두 mode로 복원한다. 백업 chain에 쓰기가 진행되지 않는 상태에서 각 복원 전 대상 VM/볼륨 상태를 동일하게 맞춘다. 백업 파일 checksum도 복원 전후에 확인한다.
7. 최초 부팅 전에 호스트 진단 로그를 보관한다. 부팅 이후에는 게스트가 데이터를 변경하므로 부팅한 디스크를 INC3와 compare하지 않는다.
8. 복원 VM을 격리된 동일 네트워크/부팅 조건에서 시작하고 부팅 성공 여부, kernel I/O 오류, 전체 검증 파일 checksum, 삭제한 파일이 없는지 확인한다.
9. filesystem 검사는 unmount 상태 또는 rescue 환경에서 수행한다. ext 계열은 `e2fsck -fn`, XFS는 `xfs_repair -n` 등 해당 filesystem의 읽기 검사 명령을 사용한다. 부팅·게스트 검사 결과는 호스트 compare/check 결과와 별도로 기록한다.
10. 1회 성공만으로 대용량 안전성을 보장하지 않는다. 케이스 반복과 당시 문제 환경에 가까운 부하 조건으로 재확인한다.

convert/check/compare를 추가하므로 테스트 복원의 전체 소요 시간은 증가한다. `backup.data.operation.timeout`에 변환 및 검사 시간을 확보한다. convertElapsedMillis를 전체 복원 시간으로 해석하지 않는다.

## 결과 기록과 해석

케이스별로 아래 값을 기록한다.

| 케이스 | source check | target check | compare | virtual-size 동일 | backing 없음 | allocatedBytes/비율 | convert ms | compare ms | 부팅 | guest checksum/filesystem |
|---|---|---|---|---|---|---|---|---|---|---|
| THIN-INC3-S0-R1 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 |
| THIN-INC3-S4k-R1 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 |
| SPARSE-INC3-S0-R1 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 |
| SPARSE-INC3-S4k-R1 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 |
| FAT-INC3-S0-R1 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 |
| FAT-INC3-S4k-R1 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 | 미실시 |

- source check부터 이상이면 convert 이전의 백업 파일이나 원본 디스크 상태를 조사한다. 오퍼링 이름만으로 원인을 확정하지 않는다.
- source가 동일한데 S4k에서만 compare가 불일치하면 sparse 변환 조건, QEMU 버전, NAS read/primary write 등을 분리해 확인한다.
- 두 mode에서 compare 동일·target check 정상이더라도 게스트 부팅이나 filesystem 정상성까지 보장하지는 않는다. 백업 시점의 게스트 데이터와 부팅 구성도 확인한다.
- 두 mode의 논리 데이터가 같고 할당량만 다르면 예상한 sparse 효과를 관측한 결과로 기록한다.

## 로컬 회귀 테스트

```bash
mvn -o -pl plugins/hypervisors/kvm -am test \
  -Ddownload.plugin.skip=true \
  -Dtest=LibvirtAblestackNasRestoreDiagnosticsTest,EuropaNasRestoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

mock 기반 회귀 테스트는 mode 선택, compare 전 교체 방지, 실패 시 기존 볼륨 원복, source 파일 미변경, 기존 기본 경로 유지를 확인한다. 실제 QEMU를 이용한 1 TB 데이터 정합성·부팅 검증은 현장에서 별도로 수행한다.
