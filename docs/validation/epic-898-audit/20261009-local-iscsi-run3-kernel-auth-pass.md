# 로컬 iSCSI run3 실제 kernel CHAP·mutual 인수 결과

2026-10-09 03:04 KST에 승인된 자기 local fixture의 actual 인증 matrix와 정상 cleanup을 완료했다. **actual kernel6.12 CHAP·mutual positive/RW/fresh reconnect, negative3 거절 PASS**다. 원래 source-only kernelLoginVerified=false 및 run1/run2 실제 실패 증거는 보존했다. 이 결과는 Cloud/UI/all4/AD/retained ROOT 완료가 아니다.

## 서명·runtime·template 계보

기준 Root image는 exact fc3e / SHA `bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c`다. 전체 SPARSE copy를 사용했으며 원본은 바꾸지 않았다. 새 Root/DATA 생성·convert는 metadata preallocation/backing-none이고 overlay·THIN은0이다.

| 항목 | run3 고정 값 |
| --- | --- |
| runtime source pin | `b6b4485afc5295a213ce9e924c313746286e2e4a` |
| installed CLI | `cac4bb0ecb238598bfbce36b113f65c357fbd858ca856b54b26412ccf14a65e6` |
| iscsi_auth.py | `4125ab3775a078de99b0ef071683ae0f6a1d76f594ee0f0e18cbfd960911a560` |
| native_render_runtime.py | `77cee11eefed54f3e93d5e9073934110699d4c2178a2d930546619c118d46e4b` |
| signed archive SHA | `cbe6ed38dfa3b2010f156abc8d2834375f11e61440fd07351795402431371b10` |
| signed manifest SHA | `240bc30b458cd7471f0bf85817b32a5ac39db0b56ff63f463b69165c24f378c9` |
| updater SHA | `161c4726fdfe53e9eba51b635862a4f9dceb000acf7a2fb165541710d1b0e131` |
| runtime version | `epic898-local-b6b-iscsi-auth-20261009` |
| actual kernel / boot | `6.12.95+deb12-amd64` / `bcaed874-2aeb-4943-bc00-02d09b02afa4` |

새 test signing key는 sealed RAM memfd만 사용해 서명하고 닫았다. 자기 inactive copy에서 normal signed updater의 begin/transfer/finalize/verify/preflight/activate/readback을 먼저 수행했다. boot 후 QGA에서도 signedRuntime/installedFiles/entrypoints verified true를 다시 확인했다. component/support 파일과 base template 계보를 혼합하지 않았고 hybrid copy를 최종 release로 재라벨하지 않았다.

저장된 failed desired의 자동 replay를 피하려고 부모 승인으로 자기 copy의 reconcile만 임시 mask했다. masked boot의 자동 apply는0, 정상 CLI의 one-way 및 mutual stage apply는 각각1회다. target/rtslib global prefs를 바꾸거나 helper 직접 적용으로 우회하지 않았다.

## 실제 kernel·RAW 경계

QEMU는 2 vCPU / 4 GiB / NIC 없음이며 lo127.0.0.1만 있다. QGA 준비는70.80초 안에 완료됐다. RAW는 `/dev/sda` / 67,108,864 B / serial `a4b1fbac5ab940b4b0e5`, ROOT는 `/dev/sdb6` 및 ancestor sdb다. 각 boot의 이름을 serial/size/ROOT mount로 새로 해석했고 과거 /dev 이름을 재사용하지 않았다.

RAW는 format0이며 unmounted/no partition/빈 signature exit2를 확인했다. 최초 target 전 configfs의 늦은 초기화 때문에 read-only harness가 경로 존재를 먼저 기대한 준비 오류가 있었으나 native apply 호출은0이었다. 준비 오류 증거를 별도 보존하고 official module/readonly identity 완료 뒤 정상 최초 apply를 수행했다. 인증 실패를 자동 반복한 것이 아니다.

## 실제 인증 및 bounded I/O

공식 Sahlberg libiscsi 1.20.0 / pinned commit d960e625를 static library로 사용한 C harness다. credentials는 stdin/RAM으로만 전달하고 secret argv/URL/debug dump/core dump는0이다. PDU timeout5초, process wall15초, 자동 reconnect off와 mlock을 적용했다. operator credential은0이다.

| stage | 실제 결과 |
| --- | --- |
| one-way normal CLI apply | success/listening true,127.0.0.1:3260, exact NEW64MiB block LUN |
| one-way positive | login true,4096 B write/sync/read true, fresh context reconnect/read true |
| wrong initiator secret | login false, authenticationRejected true, I/O0 |
| mutual normal CLI apply | success/listening true, same owned target/LUN |
| mutual positive | login true,4096 B write/sync/read true, fresh context reconnect/read true |
| wrong target secret | login false, authenticationRejected true, I/O0 |
| no credentials | login false, authenticationRejected true, I/O0 |

positive와 fresh reconnect의 successful context login은4개, 의도한 negative 요청은3개다. negative의 generic safe error 문구 `LOGIN_REJECTED_OR_UNAVAILABLE`만으로 auth rejection을 주장하지 않았다. library error를 RAM에서 auth/CHAP class로 분류한 boolean, 직전·직후 정상 positive와 살아 있는 loopback endpoint를 함께 근거로 쓴다. raw protocol/error/secret/hash는 출력하지 않았으며 추가 인증 test는 없다.

정상 native writer 경로 이후 userid/password/userid_mutual/password_mutual 4개를 managed canonical vault와 RAM 안에서 fresh 비교했다. 4개 matches boolean 및 FD hold named↔fstat 일치는 true다. 값 및 credential hash는 출력하지 않았다. normal managed vault는 root0600이며 추가 generic saveconfig 두 경로는 없다.

실제 RAW 쓰기는 같은 offset4096의4096 B window를 두 번 기록한 총8192 B이다. sync/read/fresh reconnect에서 패턴을 검증했다. 전체64MiB의 그 window 밖은 all zero로 보존됐고 pattern SHA는 비밀이 아닌 test data의 `29ddd8df947f12dda43d4614877c4f839f4bf4339f1f2092ea662911a4fcf139`다.1MiB 한도보다 작다.

## session·target·vault 및 process 정상 cleanup

ACL 디렉터리 목록을 session 증거로 쓰지 않았다. 실제 ACL info의 no-active-session boolean, TCP3260 established0, C program의 logout/destroy 호출을 확인했다.

자기 target/backstore만 존재하는 것을 검사한 뒤 정상 pinned CLI의 empty owned desired cleanup을 수행했다. target0/backstore0/vault entry0/root0600/generic dump0을 확인했다. credential RAM을 폐기했고 자기 client process는 모두 종료했다.

QGA powerdown 후 PID `320452` / startTicks `4986023`는 정상 종료했다. QMP quit/kill은0이며 original bfa full SHA unchanged, Root/Data qemu check errors0, 자기 mount0/NBD7 size0·PID없음이다.

DATA container SHA는 예상된 pattern 쓰기 후 `33bd81f1aa5da696cc359446a862a1c4f521f73135b6bd839cc6736fb6fec793`로 바뀌었다. byte 변화가 없음으로 주장하지 않고 실제 RAW window와 outside preservation을 구분한다.

자기 inactive copy의 original reconcile unit을 backup SHA `2e13eb55a8a02ef41faf1edec1b3f294d590470760f3f8c9504175c44de7afb2`로 복원했다. backup과 fixture 파일은 보존했고 추가 boot는0이다.

## proof와 남은 범위

`/root/work/epic898-preparation/local-iscsi-auth-9a83/run3-*`의 source/signature/offline update/QGA/fresh readback/RAW guard/matrix/fresh attribute/pattern/session cleanup/process cleanup/original-unit restore proof를 사용한다.

Cloud template 등록·13번 VM/agent/원본 DATA/partial/OOBE 변경은0이다. 실제 Cloud iSCSI client와 all4 atomic/failure/ROOT/AD 및 최종11탭UI 인수는 남아 있다. 다음 NVMe DHCHAP는 별도 계획 및 parent GO 이후에만 수행하며 같은 RAW를 쓰면 이 패턴 window를 보존해야 한다.
