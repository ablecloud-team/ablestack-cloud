# ABLESTACK NAS QCOW2 Full·증분 복원: `-S 0` / `-S 4k` 비교 테스트

## 목적과 범위

원본 Disk Offering과 최종 convert의 sparse 옵션을 분리해 확인한다. 대상은 **ablestack-nas의 QCOW2 Full·증분 백업을 QCOW2 파일 볼륨으로 복원하는 경로**다. 기존 최신 leaf 선택, backing chain 읽기, 임시 파일 생성 및 볼륨 교체 순서를 사용한다. 독립 Full은 해당 QCOW2 파일 하나를 읽는다. 두 모드의 convert 명령 차이는 `-S` 값 하나다.

- `FULL_ALLOCATED`: `qemu-img convert -p -S 0 -f qcow2 -O qcow2 <leaf> <temporary-target>`
- `SPARSE`: `qemu-img convert -p -S 4k -f qcow2 -O qcow2 <leaf> <temporary-target>`

테스트 설정을 켜면 독립 Full도 기존 복사 대신 위 convert와 검사·비교를 거친다. 설정이 없거나 꺼져 있으면 독립 Full의 기존 복사 경로를 사용한다. `FULL_ALLOCATED`는 `-S 0`을 고르는 테스트 모드 이름이며 백업 유형 Full을 뜻하지 않는다. Full과 증분 각각에서 두 모드를 사용할 수 있다.

rebase, 체인 재구성, preallocation, cache, coroutine, `--target-is-zero` 등의 옵션은 추가하지 않는다. RAW, RBD, 다른 백업 솔루션에는 테스트 모드를 적용하지 않는다. SPARSE 테스트에도 primary storage 공간 사전 검사를 수행하므로 전체 가상 용량과 여유 공간을 준비한다.

기본스토리지 복원 여유율은 글로벌 설정 `backup.restore.primary.storage.capacity.buffer.percent`로 지정한다. NAS·Commvault·NetBackup·Veeam의 공통 설정이다. 기본 `20`, 허용 범위 `0`~`100`이며 검사별 최소 여유 공간은 `10 GiB`다. `15`이면 필요 용량 1000 GiB인 검사에 1150 GiB의 가용 공간이 필요하다. 이 비율은 새 복원의 QCOW2 파일 및 Glue RBD 공간 검사에 적용된다. 진행 중인 복원은 시작할 때의 비율을 유지한다. 솔루션별 스테이징·기본스토리지 기준은 [BACKUP.md의 복원 공간 여유율](../../scripts/vm/hypervisor/kvm/BACKUP.md#솔루션별-복원-공간-여유율)을 참고한다.

## Windows 게스트 데이터 생성

[데이터 생성 스크립트](ablestack-nas-windows-incremental-test.ps1)를 VM의 `C:\NasIncrementalTest.ps1`로 복사한다. 새 VM의 데이터 디스크가 F:이면 관리자 PowerShell에서 실행한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\NasIncrementalTest.ps1 -Stage FULL -DataDrive F
```

FULL은 DATA 파일 합계 500GiB를 생성한다. 후속 단계는 `INC1 → INC2 → INC3 → INC4` 순서이며, INC3은 600GiB, INC4는 900GiB가 된다. 기존 파일 구성과 난수·제로 데이터 쓰기는 유지하며, 각 단계 완료 시 `DATA STAGE COMPLETE`를 출력한다.
게스트 파일의 SHA256 계산, CSV 생성 및 `VERIFY` 단계는 수행하지 않는다. 단계 순서와 데이터 드라이브는 `state.json`으로 관리하고, 여유 공간과 기존 파일 덮어쓰기 방지는 확인한다.
각 단계에서 백업을 만들려면 데이터 작성을 완료한 뒤 VM을 정상 종료하고 해당 백업을 완료한 후 다음 단계를 실행한다. 복원 후에는 Windows 부팅·로그인과 데이터 볼륨 접근을 확인한다. 호스트의 QCOW2 check·compare는 아래 테스트 설정에 따라 별도로 수행한다.

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

파일이 없거나 `enabled=false`이면 기존 운영 경로를 사용한다. 활성 설정의 잘못된 mode/boolean 값은 복원 전에 오류로 처리한다. 이 설정은 글로벌 설정이나 Disk Offering 정책이 아니라 **호스트별 테스트 설정**이며, 해당 호스트의 NAS QCOW2 Full·증분 파일 복원에 적용된다. 설정은 볼륨 복원 진입 시 읽고 해당 볼륨 처리 동안 유지한다. 다중 디스크 복원 중에는 설정을 바꾸지 않는다. 호스트 간 복원 배치가 달라지면 각 테스트 호스트의 설정도 확인한다.

`source.disk.offering`은 테스트 담당자가 입력하는 원본 오퍼링 라벨이다. 실제 오퍼링을 자동 판별한 값이 아니다. 원본 VM/볼륨의 오퍼링, provisioning type 및 실제 할당량을 별도 기록하고 라벨과 일치시키며, 새 복원 VM의 오퍼링과 혼동하지 않는다. 테스트 후 `enabled=false`로 돌린다. 파일 설정만 바꾸는 경우 agent 재시작은 필요하지 않다.

## 검사 순서와 실패 처리

1. 실제 선택 leaf 및 전달된 `backupPaths`를 기록한다.
2. `qemu-img info --backing-chain --output=json <leaf>`를 기록한다. JSON 순서는 **leaf → FULL**이다. 독립 Full이면 이미지 하나가 나온다. 각 이미지의 format, virtual-size, actual-size, backing-filename, cluster-size를 남긴다.
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

비동기 복원은 `LibvirtAblestackJobMain`이라는 별도 Java 프로세스에서 수행한다. 이 진입점은 `AgentShell`을 거치지 않으므로 `log4j-cloud.xml`을 직접 초기화해야 한다. 수정된 진입점은 시작 시 `WORKER_LOGGING_INITIALIZED`와 command class/file 경로를 INFO로 기록한다. 실제 복원 호스트에 수정된 에이전트가 설치되어 있어야 하며, 해당 호스트의 Log4j 설정에서 `com.cloud` 및 파일 appender의 INFO 기록을 허용해야 한다.

**기본 확인 대상은 실제 복원 호스트의 `/var/log/cloudstack/agent/agent.log`와 회전된 `.gz` 로그다.** 에이전트 기본 설정은 콘솔 INFO 출력을 끄므로 작업별 `job.log`에 모든 진단 로그가 들어간다고 가정하지 않는다. 복원 완료 처리에서는 작업 폴더와 상태 파일을 자동 정리할 수 있다. `job.log`가 없다는 사실만으로 복원이 실행되지 않았다고 판단하지 않는다.

`ENTER`의 restoreJobId/jobLog, 대상 VM, 전체 디스크의 source/target이 포함되도록 수집한다. 완료한 작업은 압축된 로그까지 검색한다.

```bash
zgrep -H -E 'WORKER_LOGGING_INITIALIZED|QCOW2_|phase=\[CONVERT\]' \
  /var/log/cloudstack/agent/agent.log*
```

`QCOW2_TEST_SUMMARY`에 케이스, 원본 오퍼링 라벨, mode, `-S`, source/target 크기, source checks, target check, compare, convert/compare 시간, backing 잔존 여부를 기록한다.

할당량은 `apparentSizeBytes = stat %s`, `allocatedBytes = stat %b × %B`, `allocationRatio = allocatedBytes / virtualSize × 100`으로 계산한다. QCOW2 메타데이터 때문에 비율이 100%를 넘을 수도 있다. `ls -lh`만으로 Thin 여부를 판단하지 않는다. source actual-size는 leaf 파일의 점유량이며 chain 전체의 합계가 아니다.

### 새 인스턴스 생성으로 복원할 때의 확인·보관 절차

1. 실제 복원 호스트에 KVM 에이전트를 배포하고, 두 복원에 사용할 동일한 INC3 백업본을 준비한다.
2. 복원 가능한 호스트에 테스트 설정을 적용한다. 첫 복원은 `FULL_ALLOCATED` / `case.id=INC3-S0-R2`, 두 번째는 `SPARSE` / `case.id=INC3-S4k-R2`로 구분한다. `enabled=true`, `fail.on.compare=true`, 실제 원본 오퍼링 라벨을 유지한다. 복원 중에는 설정을 바꾸지 않는다.
3. 복원 로그를 저장할 SSH 터미널을 실제 복원 호스트에 열어 둔다. `rg` 설치 없이 다음 명령으로 원본 로그를 별도 파일에 저장할 수 있다. `CASE_ID`는 이번 복원 설정과 맞춘다. 이 명령은 계속 실행되며, 복원이 완료된 뒤 Ctrl+C로 수집을 끝낸다.

   ```bash
   CASE_ID='INC3-S4k-R2'
   EVIDENCE_DIR='/root/ablestack-nas-restore-evidence'
   mkdir -p "$EVIDENCE_DIR"
   tail -n 0 -F /var/log/cloudstack/agent/agent.log \
     | tee "$EVIDENCE_DIR/$CASE_ID.capture.log"
   ```

4. Mold에서 동일한 INC3 백업의 **백업에서 새 인스턴스 생성**을 실행한다. 실제 볼륨 복원 호스트와 최종 부팅 호스트는 다를 수 있다. 호스트를 확인할 때는 Mold 관리 서버에서 다음 명령을 실행하고 `hostName`, `vmName`, `restoreJobId`를 확인한다. 백업 UUID는 선택한 INC3 백업의 값이다.

   ```bash
   BACKUP_UUID='<INC3 백업 UUID>'
   zgrep -h -F 'phase=[RESTORE_COMMAND_SEND]' \
     /var/log/cloudstack/management/management-server.log* \
     | grep -F "$BACKUP_UUID"
   ```

   수집 터미널의 호스트가 `hostName`과 다르면 해당 실제 호스트에서도 수집한다. 수집 시작 전에 출력된 기록은 그 호스트의 기존 agent 로그에서 추출해 함께 보관한다.
5. 새 복원이 시작되면 `WORKER_LOGGING_INITIALIZED`와 해당 작업의 command file 경로가 기록되는지 확인한다. 이어서 `QCOW2_CONVERT_BEGIN`의 mode와 `sparseSize`를 확인한다. `QCOW2_TEST_SKIPPED`가 있으면 해당 디스크는 이번 QCOW2 파일 변환 테스트 대상이 아니다. 이 경우를 S0/S4k 성공 결과로 집계하지 않는다.
6. 진행 중에는 다른 SSH 터미널에서 실제 명령을 확인한다.

   ```bash
   ps -ww -eo pid,etime,args | grep '[q]emu-img'
   ```

   `convert ... -S 4k`는 SPARSE 변환 단계이고, `compare -f qcow2 -F qcow2`는 변환이 끝난 뒤 데이터 검증 단계다. `compare` 명령에는 `-S` 옵션이 없다. `etime`은 해당 프로세스의 경과 시간이며 전체 복원 시간이나 완료율이 아니다. 요약은 compare가 끝나야 기록된다.

   작업 상태는 실제 `restoreJobId`로 조회한다. 상태 파일은 작업 폴더 안의 `job.properties`가 아니라 아래 경로에 있다. 완료 후 자동 정리되기 전에 필요한 내용을 복사해 둔다.

   ```bash
   RESTORE_JOB_ID='<RESTORE_COMMAND_SEND에 기록된 restoreJobId>'
   grep -E '^(state|step|progress|details|volumeIndex|volumeCount|vmName|provider|updated)=' \
     "/var/lib/ablestack/backup/jobs/$RESTORE_JOB_ID.properties"
   ```

7. 복원 완료 후 case별 결과를 압축 로그까지 검색해 별도 파일로 저장한다. 이 파일은 VM 삭제나 작업 폴더 정리와 별도로 보관된다.

   ```bash
   CASE_ID='INC3-S4k-R2'
   EVIDENCE_DIR='/root/ablestack-nas-restore-evidence'
   mkdir -p "$EVIDENCE_DIR"
   zgrep -h -F "testCase=[$CASE_ID]" /var/log/cloudstack/agent/agent.log* \
     > "$EVIDENCE_DIR/$CASE_ID.result.log"
   grep -F 'phase=[QCOW2_TEST_SUMMARY]' "$EVIDENCE_DIR/$CASE_ID.result.log"
   ```

   ROOT와 DATA 각각의 요약을 확인한다. `sourceVirtualSize=targetVirtualSize`, `targetCheckExitCode=0`, `compareExitCode=0`, `backingFileRemaining=false`가 정상 기준이다. 요약이 출력되지 않으면 결과를 확보한 것으로 처리하지 않는다.
8. 복원 VM에서 Windows 부팅·로그인, 데이터 볼륨 인식 및 접근을 확인한다. 호스트의 case별 result/capture 로그와 부팅 확인 결과를 VM 외부에 보관한 뒤 해당 테스트 VM을 정리한다.
9. 동일한 조건으로 S0와 S4k 결과를 ROOT끼리, DATA끼리 비교한다. `allocatedBytes`와 `allocationRatio`는 실제 점유량, `convertElapsedMillis`는 변환 시간이다. 밀리초를 60,000으로 나누면 분이다. Windows 부팅 결과와 호스트 check/compare 결과도 두 모드 모두 기록한다. 이벤트의 시작/완료 시각은 전체 작업 시간이며 convert 시간으로 대체하지 않는다. 두 케이스를 마치면 각 테스트 호스트의 설정을 `enabled=false`로 돌린다.

### 원본 Windows VM의 DATA를 600GiB에서 900GiB로 늘려 재검증

기존 `FULL → INC1 → INC2 → INC3`를 완료한 **원본 VM**에서 진행한다. 이 단계는 `F:\NasRestoreTest\Data\inc4.bin`에 기존과 같은 방식의 난수 300GiB를 새로 쓴다. 기존 DATA 파일은 유지하고, ROOT의 단계 표식은 `INC4`로 바꾼다. DATA 파일의 논리 크기 합계는 900GiB이며, 기존 `zeros.bin` 20GiB와 파일시스템·QCOW2 메타데이터 때문에 실제 물리 점유량과는 다르다.

1. 수정된 `ablestack-nas-windows-incremental-test.ps1`를 원본 VM의 `C:\NasIncrementalTest.ps1`에 복사한다. 관리자 PowerShell에서 F:의 용량과 기존 단계를 확인한다.

   ```powershell
   Get-Volume -DriveLetter F | Select-Object DriveLetter, FileSystemType,
       @{Name='SizeGiB'; Expression={[math]::Round($_.Size / 1GB, 2)}},
       @{Name='FreeGiB'; Expression={[math]::Round($_.SizeRemaining / 1GB, 2)}}
   Get-Content -LiteralPath C:\NasRestoreTest\Results\state.json
   ```

   기존 단계는 `INC3`, DataDrive는 `F`여야 한다. 추가 쓰기에는 300GiB와 2GiB의 여유 공간이 필요하며, 부족하거나 `inc4.bin`이 이미 있으면 스크립트가 중단한다.
2. `INC4`를 실행한다. 난수 파일 생성이 끝나서 `DATA STAGE COMPLETE: INC4`가 출력될 때까지 기다린다.

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\NasIncrementalTest.ps1 -Stage INC4 -DataDrive F
   ```

3. 원본 VM을 정상 종료하고 기존과 같은 백업 스케줄 또는 수동 백업 방식으로 NAS 백업을 완료한다. **실제 백업 유형 Full 또는 증분을 기록한다.** 자동 재시도 등으로 Full이 됐어도 대용량 `-S 4k` 변환·부팅 검증에 사용할 수 있다. 데이터 생성 단계의 이름 `INC4`와 실제 백업 유형은 별개다. Full만 시험한 결과를 증분 체인 검증으로 집계하지 않는다.
4. 실제 복원 호스트에 Full도 지원하는 수정된 KVM agent를 설치한다. 테스트 설정은 `sparse.mode=SPARSE`, `fail.on.compare=true`, `source.disk.offering=THIN`, `enabled=true`로 지정한다. `case.id`는 Full이면 `THIN-FULL-900GiB-S4k-R1`, 증분이면 `THIN-INC4-900GiB-S4k-R1`로 구분한다. 위의 로그 수집 절차에 따라 새 case ID로 수집을 시작한 뒤, 종료된 원본 VM에 900GiB 백업을 직접 복원한다. 기존 VM과 새 인스턴스 복원은 같은 NAS 파일 볼륨 변환 함수를 사용하므로, 이번 대용량 변환·부팅 검증은 원본 VM 직접 복원으로 진행할 수 있다. 다중 디스크 복원이 끝날 때까지 설정을 유지하고, 결과에는 `기존 VM 직접 복원`으로 기록한다. 새 인스턴스 생성 절차 자체는 별도 검증 범위다.
5. ROOT/DATA 각각에서 `QCOW2_CONVERT_BEGIN`의 `sparseSize=[4k]`, 요약의 가상 용량 일치, `targetCheckExitCode=0`, `compareExitCode=0`, `backingFileRemaining=false`를 확인한다. `QCOW2_TEST_SKIPPED`이거나 실제 옵션을 확인하지 못한 결과는 `-S 4k` 성공으로 집계하지 않는다.
6. 복원된 원본 VM을 부팅해 Windows 로그인과 F: 데이터 볼륨 접근을 확인한다. 호스트 진단 로그와 Windows 정상 부팅 결과를 함께 보관한다. 테스트 후 호스트 설정을 `enabled=false`로 돌린다.

원본 VM에 복원이 완료되면 해당 VM의 활성 증분 체인은 `vm-restore-completed` 사유로 봉인된다. 이후 첫 백업이 FULL로 시작하는 것은 정상 동작이다. 증분 체인까지 검증할 계획이면 해당 증분 백업을 먼저 완료한 뒤 직접 복원한다.

판정은 **900GiB DATA를 가진 이번 VM·NAS/QCOW2 Full 또는 증분 체인·QEMU 버전·스토리지 조합에서 `-S 4k` 변환, 이미지 검증 및 부팅을 통과했음**으로 실제 백업 유형과 함께 기록한다. 게스트 파일 해시 검증은 미실시로 기록한다. 한 번의 부팅 성공이나 특정 용량만으로 다른 환경 전체의 안전성을 보장하지 않는다. 운영 적용 환경이 다르면 해당 조합에서 검증한다.

### 복원 진행 화면

수정된 KVM 에이전트와 UI를 배포한 뒤 새로 시작한 NAS 복원은 현재 볼륨과 처리 단계를 상태 API로 전달한다. 목록에는 `복원 중` 하나만 표시한다. 상태에 마우스를 올리면 볼륨 `2/2`, 단계 `복원 데이터 비교`와 상세 `DATA · QCOW2 ↔ QCOW2` 또는 `DATA · QCOW2 → QCOW2 · -S 4k`를 확인할 수 있다.

일반 복원은 대상 준비, 이미지 복사/변환, 볼륨 적용, 원본 정리를 구분한다. QCOW2 테스트를 활성화하면 백업 이미지 검사, 복원 이미지 검사, 데이터 비교도 각각 구분한다. RBD 복원은 전체 백업 가져오기, 증분 적용, 이미지 변환, 볼륨 적용을 표시한다. 실제 변환 옵션은 테스트 설정으로 정한다.

`getBackupRestoreJobStatus`의 `volumeindex`/`volumecount`는 현재 볼륨의 순서와 전체 개수다. `step`은 현재 단계, `details`는 볼륨 종류와 작업 설명이다. 복원 화면은 개별 명령의 실제 완료율을 계측하지 않은 단계별 추정치를 퍼센트로 표시하지 않는다. 긴 compare 동안에도 볼륨과 데이터 비교 단계는 계속 확인할 수 있다.

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
5. 각 단계의 데이터 생성 완료 여부와 파일 논리 용량, filesystem 상태를 기록한다. 모든 오퍼링에서 동일한 데이터와 변경 패턴을 사용한다.
6. 동일한 `INC3 → INC2 → INC1 → FULL`을 두 mode로 복원한다. 백업 chain에 쓰기가 진행되지 않는 상태에서 각 복원 전 대상 VM/볼륨 상태를 동일하게 맞춘다. 백업 파일 checksum도 복원 전후에 확인한다.
7. 최초 부팅 전에 호스트 진단 로그를 보관한다. 부팅 이후에는 게스트가 데이터를 변경하므로 부팅한 디스크를 INC3와 compare하지 않는다.
8. 복원 VM을 격리된 동일 네트워크/부팅 조건에서 시작하고 부팅 성공 여부, kernel I/O 오류와 데이터 볼륨 접근을 확인한다. 게스트 파일 해시 검증은 미실시로 기록한다.
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
  -Dtest=LibvirtAblestackJobMainTest,LibvirtAblestackRestoreProgressTest,LibvirtAblestackAsyncBackupRunnerTest,LibvirtAblestackNasRestoreDiagnosticsTest,EuropaNasRestoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

별도 JVM 회귀 테스트는 비동기 진입점이 `log4j-cloud.xml`을 발견하여 INFO를 agent 파일 로그에 기록하는지 확인한다. mock 기반 회귀 테스트는 mode 선택, compare 전 교체 방지, 실패 시 기존 볼륨 원복, source 파일 미변경, 기존 기본 경로 유지를 확인한다. 실제 QEMU를 이용한 1 TB 데이터 정합성·부팅 검증은 현장에서 별도로 수행한다.
