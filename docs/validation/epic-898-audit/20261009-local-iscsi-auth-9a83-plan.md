# 9a83 iSCSI 인증의 로컬 SPARSE QEMU 실증 계획

이 문서는 실행 전 계획을 보존한 기록이다. 아래 0회 항목은 계획 작성 시점의 값이다. 이후 parent가 이 범위의 로컬 시험을 승인했고 공식 libiscsi의 별도 빌드와 RAM harness 자체 검사를 마친 후 자기 SPARSE ROOT copy·NEW64MiB DATA 준비를 진행 중이다. 실제 boot·kernel login·I/O의 확정 결과는 별도 결과 문서로 기록한다. Cloud 등록/13번 VM·host·DATA·partial·Client OOBE 변경은 0 회로 유지한다.

## 입력 pin과 표기

기준 prototype 원본은 fc3e 이미지 SHA-256 `bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c`이다. 이를 변경하지 않고 별도 backing-none SPARSE 전체 ROOT copy를 만든다.

iSCSI source pin은 `9a832ada774dce6be1d5aea6cedcf7beaa05f191`이며 source-only 42 focused PASS다. 실제 proof 경로는 `/root/work/epic898-preparation/native-iscsi-auth-clean-proof.json`이고 candidate 경로는 `native-iscsi-auth-clean-8fb68f384dbc`다. 경로의 8fb 이름을 9a83 commit이라고 추정하지 않고 proof의 6 파일 hash를 committed 9a83과 대조하여 모두 일치시켰다.

| 실제 사용할 production 파일 | 9a83 SHA-256 |
| --- | --- |
| CLI | `f7031bf4f0986f4075aefd91492002d64bc49e90bfa5d5e8b4759b7364cf906c` |
| iscsi_auth.py | `85681e9e249cc14d3dcce23b7c8c1fdcef70015cc7b2b748cf81e016d494c766` |
| native_render_runtime.py | `6ca8ee1f94272fb802ecdb966bd8311c09b30d7a3ffaf79f18f31dd06615f22f` |

original proof의 `kernelLoginVerified=false`를 수정하거나 성공으로 승격하지 않는다. 후속 실제 결과는 별도 local kernel-login proof에 저장한다. ROOT base image lineage는 fc3e, 적용할 signed runtime/component lineage는 9a83으로 따로 표시한다. hybrid test copy를 fc3e의 원본이나 최종 release로 부르지 않는다.

## 새 fixture 및 자원 한도

| 항목 | 제안 |
| --- | --- |
| 소유 경로 | `/root/work/epic898-preparation/local-iscsi-auth-9a83/` |
| ROOT copy | `fc3e-root-9a83-auth.qcow2`, 원본 전체 convert, metadata preallocation, backing-none |
| DATA container | `iscsi-raw-data-64m.qcow2`, NEW 64 MiB, metadata preallocation, backing-none |
| DATA guest 역할 | unformatted RAW block, filesystem format 0 회 |
| QEMU | 새 local process, KVM, 2 vCPU / 4 GiB RAM, QMP/QGA task Unix socket |
| 네트워크 | NIC 없음 우선, guest loopback에서 target 및 RAM initiator 실행 |
| target / initiator IQN | `iqn.2026-10.local.storage:epic898-auth13` / `iqn.2026-10.local.epic898:ram-initiator13` |
| raw I/O 한도 | 승인된 새 DATA의 LBA 8 이후 4 KiB 패턴, 전체 시험 쓰기 1 MiB 이하 |
| local disk budget | ROOT copy와 temporary prepare를 포함 보수 12 GiB 이하 |
| deadline | boot 120 s, native apply 30 s, login/I/O 각 15 s, 종료 30 s |

ROOT copy는 `qemu-img convert -f qcow2 -O qcow2 -o preallocation=metadata <exact original> <exact owned copy>`다. `-b`, `-snapshot`, overlay/COW는 사용하지 않는다. DATA는 `qemu-img create -f qcow2 -o preallocation=metadata <owned data> 64M`이며 RAW는 guest 사용 역할을 뜻한다. container의 qcow2 SPARSE metadata를 검증하고 DATA에 mkfs를 실행하지 않는다.

각 실행 직전에 절대 경로가 소유 디렉터리 아래인지, 원본 SHA, source/target inode 분리, local 공간/RAM, `/dev/nbd7` size 0 / owner 없음 및 다른 mount 없음부터 확인한다. 실제 argv/header/L1·L2/allocated bytes와 qemu compare를 기록한다. 부모 승인 전 생성 명령은 실행하지 않는다.

## 관측 채널과 client 선행 조건

fc3e recipe는 `qemu-guest-agent`를 disable한다. QGA가 자동 연결될 것으로 추정하지 않는다. 전체 copy를 먼저 byte/logical 비교한 후, 자기 복사본에만 QGA service enable 또는 별도 readonly bootstrap 채널을 준비하고 그 변경을 test-only로 기록한다. 원본 qcow는 readonly로 유지한다. QGA 준비에는 비밀번호나 새 Cloud 연결 설정을 넣지 않는다. QGA가 실패하면 실제 target 변경 전에 중단한다. 필요하면 자기 local guest에만 loopback-bound SSH 3922 fallback과 공개 key를 넣고 SSH private key는 sealed memfd로 유지하는 별도 채널 계획을 parent와 맞춘다.

현재 WSL `/lib64/libiscsi.so.0`는 `libiscsi_node_login` 계열이며 요청한 userspace RAM session API가 아니다. `iscsi_set_initiator_username_pwd` 또는 `iscsi_full_connect_sync`가 확인된 것으로 주장하지 않는다. standalone Sahlberg libiscsi를 소유 경로의 pinned source로 별도 준비하고 C harness/필요 library를 guest로 전달하는 선행 단계가 필요하다. 시스템 library를 덮어쓰지 않는다.

공식 libiscsi header의 `iscsi_set_initiator_username_pwd`, `iscsi_set_target_username_pwd`, `iscsi_full_connect_sync`, SCSI read/write API로 credentials를 process RAM에서 전달한다. mutual CHAP는 initiator CHAP를 설정한 뒤 target credentials를 설정한다. portal/IQN argv에는 secret이 없고 모든 생성 password는 stdin/QGA input-data 또는 sealed memfd로만 전달한다. iscsiadm node database, secret URL/argv, debug credential 출력은 사용하지 않는다. [공식 libiscsi API](https://raw.githubusercontent.com/sahlberg/libiscsi/master/include/iscsi.h)

## 실행 순서와 인수 증거

1. 실제 GO 이후 ROOT 전체 SPARSE copy와 NEW64 MiB DATA를 만들고 원본 bfa SHA unchanged / no backing / 실제 metadata를 고정한다. 자기 QGA channel만 준비한다.
2. new QEMU를 boot하고 fresh boot ID, `uname -r=6.12.95+deb12-amd64`, configfs mount 및 iscsi_target_mod/target_core 계열을 실제 관측한다. RAW DATA의 synthetic UUID/serial/size와 ROOT ancestor 제외를 확인한다. configfs에 예상 밖 기존 target이 있으면 쓰기 전에 중단한다.
3. 자기 copy에 exact 9a83 component를 적용한다. core entrypoints는 새 test key의 sealed-memfd signed bundle과 정상 updater로 검증하며, support module 2 파일의 installed SHA는 별도 대조한다. original template/sourceFiles는 fc3e인 점과 component overlay를 숨기지 않는다. fresh installed-code readback으로 CLI f703 및 pinned archive/manifest/key를 확인한다.
4. 소유 IQN/initiator/LUN만 정상 pinned CLI로 만든다. block backstore는 NEW DATA만 사용한다. 실제 `auth/userid`, `password`, `userid_mutual`, `password_mutual`의 존재·owner/mode·inode를 확인한다. 값이나 password hash는 출력하지 않는다.
5. native helper가 자기 FD 9의 root0600 writer file/inode 및 실제 exclusive flock을 보유한 상태에서 4 attribute를 기록하도록 한다. fake lock 또는 foreign path 예외를 만들지 않는다. fresh kernel readback 비교는 boolean만 남긴다.
6. CHAP positive login → 새 RAW 영역 write/sync/read 패턴 비교 → logout/destroy → fresh 새 context의 동일 credentials 재접속/readback을 수행한다. 그 뒤 의도한 wrong initiator secret 1 회는 auth rejection이어야 하고 data I/O는 0이다. raw `iscsi_get_error` 내용을 그대로 출력하지 않고 login status/safe error class만 남긴다.
7. 새 단계로 mutual CHAP를 적용하고 correct initiator+target credentials의 login/RW/fresh readback을 확인한다. wrong target secret 1 회와 credentials 없는 연결의 거절을 구분한다. 자동 password variation/reset/retry는 없다.
8. target health/readback, session 종료, own target/backstore만 정상 삭제 또는 보존 상태를 기록하고 QGA shutdown으로 own QEMU를 종료한다. deadline이면 exact 자기 PID/startTicks/QMP socket으로 bounded 종료하고 재실행하지 않는다. VM/DATA 파일은 증거 검토 전 자의 삭제하지 않는다.

source의 정상 private vault 및 configfs credential state는 제품 canonical 경로이며 추가 targetcli saveconfig dump를 만들지 않는다. 시험 harness/host 파일·로그·argv에는 password/CHAP hash/key를 저장하지 않는다. canonical vault와 snapshot raw 내용을 출력하지 않고 필요시 scope/owner/mode/비밀 아닌 hash 또는 비교 boolean만 기록한다.

모든 probe는 phase-specific timeout과 전체 deadline을 가진 별도 child supervisor로 실행한다. 실패나 partial apply 시 새로운 apply/login 반복 없이 source/runtime/attribute metadata와 bounded process 상태를 남긴다. 기존 original kernel-login false는 그대로 보존하고 새 실제 proof만 결과를 표시한다.

## 결과 범위

성공 시 주장할 범위는 자기 local guest의 실제 6.12 kernel 4 auth attribute 쓰기/readback, CHAP 및 mutual CHAP login·negative·RAW I/O다. Cloud/Windows interoperability, all4 원자 적용, production AD, retained ROOT 및 최종 UI #1275 완료는 아니다. 현재는 source 비교와 계획만 완료했고 실제 create/boot/target/login은 0 회다.
