# #1443 최초 부팅 STATIC guard — 원인 실증과 최소 변경 설계

**설계·격리 주입 검증 완료. 제품 구현·모듈 빌드·배포·새 VM 생성·재부팅·대용량 인수 재시험은 수행하지 않았다. #1443 OPEN을 유지한다.**

기준 제품 source는 `cf5351e0557063780c4392b45e72b88333cd30da`이고, 착수 HEAD `c90b730f11749735864fe610dbe27c8a5e72bd18`는 해당 코드와 앞선 인수 문서이다. fetch 후 local/upstream Europa `929cbff799d`, 비교 0/0을 확인했다. 기존 제품 파일의 수정은 없다.

## 1. 정확한 차단 경로

단순한 무한 대기가 아니다. **SharedFS 최초 부팅의 cloud-init 메타데이터 탐색 → SSH 시작 순서 → 동기식 SSH 재시작 → 전체 cloud-early-config 완료 guard**의 직렬 대기가 기존 STATIC 30회 재시도보다 길다.

1. `sharedfsvm.sh`는 최초 부팅에서 `systemctl start --no-block cloud-init/cloud-config/cloud-final`을 호출한다.
2. 같은 파일의 마지막 줄이 `patch.sh && patch_sshd_config`를 실행한다. 새 이미지의 SSH 설정에 PubkeyAcceptedAlgorithms 항목이 없으면 추가 후 **동기식 `systemctl restart ssh`**를 호출한다.
3. 실제 guest의 cloud-init.service는 **Before=ssh.service**, ssh.service에는 **After=cloud-init.service**가 있다. SSH 재시작의 시작 job은 cloud-init network 단계 종료까지 기다린다.
4. UserData/ConfigDrive가 없는 L2에서 CloudStack datasource는 `http://10.10.0.1/latest/meta-data/instance-id`의 **HTTP 404**를 약 125초 재시도한다. 연결 자체가 끊긴 기록이 아니다.
5. 그동안 cloud-early-config.service는 activating/start이다. 이번 guard는 active/exited/success만 인정하므로 exit75/PENDING을 반환한다.
6. 서버는 30회 × (실측 QGA 관찰 약 1초 + 간격 2초), 약 87~89초에 포기한다. guard가 정상적으로 해제되기 전 CloudRuntimeException으로 바뀌어 초기 생성 cancelCreation/VM expunge 경로로 들어간다. 포맷 worker 호출 전이다.

### 실제 부팅 journal과 배포 소스 대조

기존 SharedFS 시험 VM `a14c8a94-8c5c-3675-b036-652cbb92d15a`, i-2-114-VM, host13.3의 최초 부팅 journal을 읽었다.

| 부팅 후 초 | 실제 기록 |
|---|---|
| 7.115 | DHCP 주소 획득 완료 |
| 8.326~8.355 | cloud-init/config/final 비동기 시작 |
| 8.379 | patch_sshd_config의 systemctl restart ssh |
| 10.397~129.492 | 메타데이터 HTTP404 재시도 |
| 136.495 | datasource 125초 대기 종료 |
| 136.552~136.589 | cloud-init 종료 뒤 SSH 재시작 완료 |
| 136.612 | cloud-early-config 완료 |

common.sh, init.sh, cloud-early-config, bootstrap.sh, patch.sh의 **실제 guest SHA가 현재 source와 모두 동일**하다. host의 현재 wrapper class SHA도 배포 `2567b3a1a1a4abcdee295764f8f8d77116c340efe768d7e9791eb27648475f13`과 동일하다. guard는 해당 Java 함수 본문을 별도 임시 class로 컴파일해 실제 생성 문자열을 추출했다.

**증거의 한계:** 앞서 실패한 VM117의 ROOT는 자동 취소로 삭제돼 그 VM의 마지막 명령 journal을 직접 회수할 수 없다. 위는 남아 있는 동일 계열 guest의 실제 최초 부팅 기록이며, 아래 동일 코드/실제 systemd 주입으로 실패 메커니즘을 재현한 근거다. VM117에서 해당 명령을 직접 읽었다고 기록하지 않는다.

## 2. 파일시스템 VM에서 수행한 격리 코드 주입

동일 VM에 QGA로 `/run/epic1443-guard-design-probe`와 이름을 분리한 임시 systemd unit만 만들었다. 운영 SSH·cloud-init unit을 실행/중지하지 않았다. VM을 재부팅하거나 실제 NIC/MAC·DATA·계정 파일을 바꾸지 않았다.

- 실제 배포 patch_sshd_config 함수에서 **sshd_config 경로와 SSH unit 이름만 시험 대상으로 치환**했다. 알고리즘 확인·설정 항목 추가·동기식 systemctl restart 동작은 그대로 실행했다.
- 실제 SharedFS cloud-init 시작 블록의 unit 이름/성공 marker 경로만 격리했다.
- systemd의 실제 Before/After 순서를 사용했다. SSH 대역 unit은 /bin/true여서 운영 SSH를 건드리지 않는다.
- 실제 설치 cloud-init 22.4.2의 `url_helper.wait_for_url(max_wait=120)`을 실행했다. PrivateNetwork=yes unit의 loopback HTTP404 서버만 사용해 실제 VM의 메타데이터/외부 네트워크를 호출하지 않았다.
- 기존 guard는 source 함수 컴파일 결과이며 unit 이름만 격리했다. 기존 30회·2초 간격과 실측 QGA 1초 관찰 지연을 재현했다.
- 새 gate/대기 정책은 **설계 검증용 Python prototype**이다. Java 제품 구현이 아니며 신규 서비스 생성 성공으로 대체하지 않는다.

| 주입 사례 | 결과 |
|---|---|
| 최초 부팅·설정 항목 없음·실제 120초 HTTP404 대기 | 기존 30회 루프 88.637초 실패; 같은 작업을 계속 관찰하면 127.154초 READY, 원래 guard도 이후 rc0 |
| 설정 항목이 이미 있는 재부팅 분기 | 원래 guard는 0.103초에 통과하지만 metadata unit은 activating/start. network stage까지 기다리는 prototype은 7.277초 뒤 통과(이 사례의 metadata max_wait=6으로 시간 축소) |
| cloud-init-local 뒤에 queued된 network stage | ActiveState=inactive/dead여도 Job이 남아 있음. Job까지 보는 gate는 PENDING, 종료 뒤 READY |
| cloud-early-config terminal 실패 | 원래 guard는 계속75, prototype은 FAILED로 구분 |
| 메타데이터 없음으로 cloud-init network stage가 종료/실패 | STATIC에서 새 성공 조건으로 강제하지 않음. 더 이상 실행/대기 job이 없으면 gate 통과 |
| 필수 cloud-early unit 없음 | FAILED |
| cloud-init 미설치 legacy 경로 | 선택 stage 없음이 새로운 필수 요건이 되지 않음 |
| 대기 정책·가상 단조 시계 | DHCP dispatch0회, 정상126초 완료, 영구PENDING360초 제한, terminal 즉시 실패, 마지막 RPC는 남은 예산 이하 |

실제 VM 안의 격리 복사본에서 현재 STATIC/DHCP bootstrap 회귀 **10개 통과**. 새 이미지의 bootstrap 두 파일 SHA도 원래 배포 SHA와 동일하다. 유효 STATIC은 기존 helper를 한 번 호출하고 DHCP를 건너뛴다. 상태 없음은 기존 DHCP 인자 유지, 잘못된 STATIC·MAC·권한·symlink·helper 실패는 DHCP로 우회하지 않는다.

두 주입 묶음 모두 운영 파일·계정 파일·unit/PID 상태·주소·라우팅·mount/FS UUID가 **전후 동일**하다. 시험 unit을 모두 제거했고 현재 실행 중인 시험 unit은 0개다. 결과를 회수한 뒤 경로·worker SHA·unit 부재를 검증하고 해당 /run 디렉터리만 제거했다. 시험용 VM/디스크 생성·삭제 0, 실제 VM 재부팅0, MAC 변경0이다.

## 3. 최소 제품 변경안 — 아직 적용하지 않음

**제품 변경은 기존 서버와 KVM wrapper 두 파일의 STATIC 경로로 한정한다.** guest bootstrap/helper/상태 파일과 systemd 의존성, 공통 cloud-init/SSH, DHCP, 포맷/마운트, DB schema와 공용 볼륨 코드를 변경하지 않는다.

### A. SharedFSServiceImpl.java

대상: `configureStaticNetwork`, `continueCreationStep`의 기존 오류 분기.

1. NetworkMode != STATIC의 즉시 return을 그대로 둔다.
2. 현재 30회 루프에서 **명시적 bootstrap PENDING만 STATIC 전용 360초 단조 시계 deadline**까지 관찰한다. 기존 2초 간격은 유지하고 각 dispatch의 60초 RPC 상한은 남은 예산으로 제한한다. 일반 QGA/미분류 오류의 기존 30회 보호는 유지한다. 전체 서비스/format timeout 설정을 올리지 않는다.
3. 360초는 관측된 metadata125초·초기 부팅 지연을 포함하고 guest cloud-early unit의 5분 제한에 여유를 준 상한이다. 성공을 보장하는 SLA가 아니며 이보다 오래 걸리면 대기를 끝낸다. Thread interrupt와 unit terminal 실패는 별도로 즉시 전달한다.
4. PENDING deadline은 nested `SharedFsBootstrapPendingTimeoutException extends CloudRuntimeException`으로 구분한다. 마지막 unit 상태·경과 시간은 길이를 제한해 메시지에 포함한다.
5. `continueCreationStep`의 기존 `OperationTimedoutException / OBSERVATION_PENDING` 보존 분기에 이 예외만 추가한다. vmId·planned VM UUID·ownedVolumeIds와 원래 request를 유지하고, 기존 OperationFailed→Error/OBSERVATION_PENDING로 저장한다. **PENDING 때문에 cancelCreation을 호출하지 않는다.** Ready로 처리하지 않는다.
6. 기존 UI의 같은 요청 '상태 확인·이어하기'가 Error에서 resume API를 호출하는 경로를 재사용한다. 다음 CONTINUE는 보존한 VM을 확인하고 STATIC 단계부터 진행하므로 추가 VM/ROOT/DATA를 만들지 않는다. 실제 DAO/재개 통합 검증은 구현 시 해야 하며 이번에 실행했다고 주장하지 않는다.

설계 의사 코드:

```java
if (fs.getNetworkMode() != STATIC) return;
long deadline = System.nanoTime() + SECONDS.toNanos(360);
int ordinaryFailures = 0;
while (hasAtLeastOneSecondRemaining(deadline)) {
    Result r = dispatch(existingStaticCommand,
        min(60, wholeSecondsRemaining(deadline)));
    if (r.isSuccess()) return;
    BootstrapObservation b = parseBoundedBootstrapObservation(r);
    if (b.isTerminalFailure()) throw existingStaticFailure(b);
    if (!b.isPending() && ++ordinaryFailures >= 30)
        throw existingStaticFailure(r);
    sleepBounded(2000, deadline);
}
if (lastObservation.isPending())
    throw new SharedFsBootstrapPendingTimeoutException(lastObservation);
throw existingStaticFailure(lastResult);
// existing creation catch: this exception -> OBSERVATION_PENDING,
// state Error, preserve VM/owned DATA; genuine failure rules unchanged.
```

success/FAILED/PENDING의 wire는 아래 wrapper만 생산한다. 명시적 typed observation 없이 임의의 에러 문자열을 무조건 PENDING으로 바꾸지 않는다. 배포 중 구 guard의 exit75/고유 SHAREDFS_BOOTSTRAP_PENDING marker만 제한적인 호환 경로로 인식할 수 있으며 성공으로 바꾸지 않는다.

### B. LibvirtStorageServiceHostCommandWrapper.java

대상: `buildSharedFsBootstrapGuard`와 동일 STATIC shell의 진단 반환. 기존 helper/설정 저장·실제 주소 검증은 유지한다.

- cloud-early-config는 기존처럼 loaded + active/exited + Result=success여야 한다. failed/not-found/bad-setting은 FAILED.
- **추가 확인은 cloud-init-local.service / cloud-init.service의 '실행 또는 시작 대기 종료'**다. ActiveState/SubState뿐 아니라 Job(실제 idle은 빈 값으로 관찰됨)도 확인해 inactive이지만 queued인 경우를 놓치지 않는다.
- cloud-init이 metadata 없는 L2에서 실패 종료한 것을 STATIC 실패로 바꾸지 않는다. 성공을 요구하는 대신 네트워크 변경 단계가 더 이상 실행/queued되지 않았는지 확인한다. cloud-init 미설치 경로도 새 필수 조건으로 막지 않는다.
- PENDING/FAILED는 existing host resultJson에 bounded `sharedFsBootstrap.state/reason/units` JSON을 반환하고 stderr에도 unit의 LoadState/ActiveState/SubState/Result/Job을 남긴다. 성공 guard는 stdout을 추가하지 않아 기존 STATIC helper의 단일 IP/route JSON과 섞이지 않는다.
- gate 통과 뒤 기존 static unit/helper를 실행해 **지정 MAC·실제 IPv4/프리픽스·선택 gateway를 검증한 결과만 성공**으로 반환한다. 단순 unit 종료를 서비스 Ready로 바꾸지 않는다.
- Python/systemctl probe에도 작은 제한 시간을 둔다. boot 완료 flag나 별도 checkpoint 파일·daemon·새 API를 만들지 않는다.

### C. UI/API·DB 영향

새 API/응답 DTO/화면 layout/DDL을 만들지 않는다. wrapper의 내부 resultJson만 서버에서 소비한다. NFS/SMB 초기 NEW 흐름은 이미 observeInitialCreation의 최대1440회×5초 관찰을 사용하므로 360초 STATIC 예산보다 길다. 그동안 기존 VM_ALLOCATED 알림을 유지하고, timeout은 기존 오류·pending 요청 보존 UI로 표시한다. CONTINUE 전에 같은 요청과 VM identity를 다시 검증한다. 최종 UI 표준 과제 #1468은 착수하지 않는다.

## 4. 기존 STATIC·DHCP 영향

| 경로 | 보존/변경 |
|---|---|
| DHCP | configureStaticNetwork 즉시 return 유지. 새 guard·360초 대기를 사용하지 않는다. userdata/ConfigDrive·기존 dhclient 인자·lease 처리·cloud-init 동작을 바꾸지 않는다. |
| STATIC 최초 부팅 | metadata/SSH 직렬 대기를 정상적으로 관찰한 뒤 기존 helper로 최종 주소 적용. 먼저 포맷하거나 임시 DHCP 주소를 Ready로 인정하지 않는다. |
| STATIC 설정된 재부팅 | cloud-early가 먼저 끝나도 아직 남은 cloud-init network 작업이 종료될 때까지 최종 적용을 미룬다. VM 내 주입으로 확인한 조기 해제 경로를 보호한다. |
| 잘못된 STATIC/다른 MAC/손상 상태 | 기존 거부·DHCP fallback 금지·권한 검증 유지. |
| 일반 VM·다른 SystemVM·SMB/NFS·볼륨 | 기능·파일·전역 timeout 변경 없음. |

기존 unit의 Before/After를 지우거나, SSH 재시작 순서를 바꾸거나, cloud-init을 끄거나, metadata timeout을 낮추는 방식은 선택하지 않는다. 이들은 네트워크 적용/사용자 데이터 동작을 함께 바꿀 수 있다. 이번에는 확인한 지연을 허용하면서 마지막 네트워크 쓰기 뒤 최종 STATIC을 적용하도록 기존 경계를 보강한다.

## 5. 구현 승인 후 확인할 범위 — 지금 실행하지 않음

- 서버 fake monotonic clock/dispatcher 회귀: DHCP0회, 126초 PENDING 이후 성공, 360초 종료, interrupt, terminal fail-fast, RPC 잔여 예산.
- 실제 생성 shell의 systemd 출력/빈 Job/queued Job/네트워크 stage 종료/손상 응답 회귀. 성공 stdout은 기존 주소 JSON 1개인지 검사.
- 실제 DAO 오류 경계: PENDING timeout 후 Error/OBSERVATION_PENDING·VM/ROOT/DATA/request 유지, 같은 요청 CONTINUE 이후 중복 생성 없음. 실제 unit 실패/잘못된 MAC 보호는 기존대로인지 확인.
- 제품 구현·관련 모듈 빌드·13번 변경 모듈 배포 후, 실제 UI의 새 소형 STATIC과 DHCP 최초 생성/재시작부터 인수.
- 그 인수 뒤에만 #1443 2TiB/10TiB SPARSE 순차 시험을 재개. 기존 실패 DATA를 재사용하지 않는다.

## 근거

- [실제 최초 부팅의 unit·SSH·metadata 기록](evidence/observed-first-boot-cause.json)
- [실제 systemd/설치 cloud-init 주입 결과](evidence/real-systemd-injection.json)
- [현재 agent class 대조](evidence/agent-runtime-identity.json), [guest/source 대조](evidence/source-runtime-identity.json)
- [VM 내부 STATIC/DHCP 10개 회귀](evidence/guest-unchanged-static-dhcp-regressions.json)
- [주입 정리 결과](evidence/injection-cleanup.json)
- [격리 주입 스크립트](probes/injected-design-probe.py), [queued job·deadline 검증](probes/extra-probe.py)

**이번 산출물은 설계와 그 타당성 실증이다. Java 제품 구현, 실제 UI 생성 성공, 새 DHCP lease/재부팅 성공 또는 #1443 종료 근거로 확대하지 않는다.**
