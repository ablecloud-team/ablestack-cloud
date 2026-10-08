# 로컬 NVMe/TCP DH-HMAC-CHAP 실증 계획

이 문서는 실행 전 계획을 보존한 기록이다. 계획 작성 당시 boot·NVMe target/host/controller 생성·접속·DATA 쓰기는0이었다. 이후 parent가 아래 고정 범위의 로컬 시험을 승인했다. 실제 결과는 별도 proof로 기록하며 기존 local iSCSI run3 PASS와 Cloud/UI/production all4는 서로 다른 증거다.

## 재사용할 자기 SPARSE fixture

기존 owned Root full copy와 RAW64 MiB를 재사용할 수 있다. Root 원본 fc3e bfa SHA는 unchanged이고 현재 copy의 runtime은 정식 signed b6b4485이며 hybrid 표기를 유지한다. 고정 변경이 필요하면 새 committed source와 signed bundle/readback을 먼저 검증하고 임의 source overwrite를 하지 않는다.

| 항목 | 계획 |
| --- | --- |
| 소유 경로 | `/root/work/epic898-preparation/local-iscsi-auth-9a83/` 아래 별도 `nvme-plan/run4*` proof |
| RAW UUID / serial | `a4b1fbac-5ab9-40b4-b0e5-277a1ed5fb64` / `a4b1fbac5ab940b4b0e5` |
| RAW container baseline | SHA `33bd81f1aa5da696cc359446a862a1c4f521f73135b6bd839cc6736fb6fec793` |
| 보존할 iSCSI window | offset4096 / length4096 / pattern SHA `29ddd8df947f12dda43d4614877c4f839f4bf4339f1f2092ea662911a4fcf139` |
| 새 NVMe test window | offset65536 / length4096, 기존 window와 분리 |
| 총 쓰기 한도 | 신규 NVMe1MiB 이하, 실제 4KiB씩 최소 필요 횟수만 |
| QEMU | 자기2C/4GiB/noNIC/lo ONLY/QGA, source 원본 및 Cloud13 변경0 |
| target NQN | `nqn.2026-10.local.storage:epic898-nvme-auth14` |
| host NQN | `nqn.2026-10.local.epic898:ram-initiator14` |
| listener | 자기 guest127.0.0.1:4420 |
| namespace | NSID1, exact fresh RAW serial/size/ROOT ancestor 제외 |
| deadline | boot120s, target30s, connect/I/O15s, 정상cleanup30s |

새 디스크는 불필요하다. 새 소형 DATA가 필요하다고 판명되면 계획을 수정하고 별도 GO를 받으며 create/convert 모두 metadata 이상을 유지한다. THIN/COW/snapshot 및 mkfs는0이다. RAW의 device 이름은 각 boot에서 바뀔 수 있으므로 과거 /dev/sda/sdb를 재사용하지 않는다.

## kernel 및 RAM connect 선행 조건

pinned6.12.95 package config에서 `CONFIG_NVME_HOST_AUTH=y`, `CONFIG_NVME_TARGET_AUTH=y`, `CONFIG_NVME_AUTH=m`, `CONFIG_NVME_TCP=m`, `CONFIG_NVME_FABRICS=m`를 확인했다. 하지만 실제 guest module/attribute/crypto 지원 및 login은 아직 확인하지 않았다.

GO 이후 fresh `uname`, root/data serial·size·mount·ancestor, existing nvmet subsystem/host/port/controller inventory를 먼저 확인한다. unknown 기존 객체가 있으면 effect 전에 중단한다. 기존 iSCSI target/backstore/vault/session0과 패턴 보존도 baseline으로 고정한다.

initiator는 secret argv를 만드는 nvme-cli connect 대신 guest의 `/dev/nvme-fabrics`에 options를 RAM에서 직접 전달하는 bounded Python/C harness를 제안한다. Linux6.12는 이 장치의 write에서 controller를 만들고 `dhchap_secret`, `dhchap_ctrl_secret` options를 지원한다. 정상 controller instance/cntlid 결과만 읽고 secret은 로그에 남기지 않는다. [Linux fabrics interface](https://raw.githubusercontent.com/torvalds/linux/v6.12/drivers/nvme/host/fabrics.c)

secret 전송 전 장치를 credential 없이 읽어 allowed option 목록에 두 auth token이 있는지 확인한다. kernel config/장치/모듈 확인이 실패하면 secret은 보내지 않는다. source kernel은 malformed/unknown option의 내용을 로그에 넣을 수 있으므로 wrong-key negative도 문법적으로 올바른 DHHC key만 사용한다. malformed 문자열이나 잘못된 CRC를 의도적으로 보내지 않는다.

키는 RAM에서 cryptographic random32 bytes + little-endian CRC32 + base64로 유효한 DHHC-1 형태를 만들고, 보수적인32-byte key/고정 hash-id 형태를 local validator로 먼저 검사한다. 원문/CRC/hash/keytab 같은 credential 파생값은 출력하지 않는다. kernel은 key length와 CRC를 확인하며 default target hash는SHA256다. [Linux auth format](https://raw.githubusercontent.com/torvalds/linux/v6.12/drivers/nvme/common/auth.c), [target auth attributes](https://raw.githubusercontent.com/torvalds/linux/v6.12/drivers/nvme/target/configfs.c)

모든 credentials는 host/guest RAM·sealed memfd/QGA stdin만 사용한다. operator credential은0이며 CLI argv/URL/env dump/host 파일/extra saveconfig에는 저장하지 않는다. 제품 canonical root0600 NVMe vault는 정상 native state로 관리하고 raw 내용을 출력하지 않는다. private key가 필요한 새 runtime signing도 sealed memfd만 사용한다.

## 정상 native target 및 실제 matrix

1. 자기 copy의 reconcile 원래 상태를 보존하고 parent가 승인한 test-only mask로 automatic old replay0을 확보한다. 필요한 signed runtime을 정상 updater로 적용해 template/runtime/support 계보를 구분하고 fresh readback을 확인한다.
2. official nvmet/nvmet-tcp/nvme-tcp/nvme-auth modules와 configfs·fabrics를 준비한다. helper 초기화로 생긴 static 디렉터리를 실제 target으로 오판하지 않는다. Root ancestor 및 current RAW device가 확정되기 전 write0이다.
3. 정상 pinned CLI `nvmeof subsystem apply`와 protected native writer 경로로 자기 NQN/NSID1/host/loopback port만 만든다. allowAnyHost=false 및 DHCHAP host key를 적용한다. helper를 직접 호출하거나 optional feature를 true로 속이지 않는다.
4. 실제 `dhchap_key` / `dhchap_ctrl_key` 존재·owner/mode/FD hold metadata 및 fresh managed-value equality boolean을 확인한다. inode churn은 열린FD 이후 named↔FD 신원 기준으로 안전하게 관측하고 guard를 무력화하지 않는다.
5. valid host key positive connect → controller exact subsystem/host/namespace size64MiB를 확인 → 새65536 window4KiB write/sync/read → owned controller delete → fresh reconnect/read를 수행한다.
6. 같은 alive target에서 valid-format wrong host key1회와 credentials 없는 connect1회가 auth reject/I/O0인지 확인한다. network/module 오류와 auth reject를 같은 PASS로 묶지 않는다.
7. controller key를 켠 별도 mutual stage에서 correct host+controller keys positive/RW/fresh reconnect를 확인하고 valid-format wrong controller key1회가 거절되는지 확인한다. 각 stage 전후 원래 iSCSI window와 window 밖 영역을 보존한다.
8. after each connection은 owned controller의 `delete_controller`로 정상 teardown하고 정확 instance/NQN/host match를 재검사한다. generic disconnect-all을 쓰지 않는다. auth error는 안전한 errno/status class와 required-auth/positive continuity만 기록한다. kernel 전체 로그와 raw key는 출력하지 않는다.

target helper가 raw key readback 검증을 제공하지 않으면 structured apply success만으로 완료시키지 않는다. native key-vault equality와 실제 positive/negative handshake를 별도로 확인한다. failed/partial이면 자동 connect/apply/reset 반복 없이 metadata와 structured failure를 보존한다.

## 보존 및 정상 cleanup

자기 controller/session0 → 정상 CLI로 자기 namespace/subsystem/host/port만 제거 → managed NVMe vault entry0 → QGA shutdown/ownPID gone → original reconcile unit checksum 복원 순서다. 이미 cleanup된 iSCSI 상태나 다른 커널 객체를 건드리지 않는다.

원본 bfa SHA, Root/Data qemu check, metadata/backing-none·원래 pattern4096 window, 새 window65536 및 그 밖 전체 RAW 영역의 변경 범위를 증명한다. old iSCSI 패턴을 다시 쓰거나 초기화하지 않는다. 자기 mount/NBD/프로세스만 정리하며 원본/Cloud13/partial/OOBE/production VM에는 effect0이다.

성공 범위는 local actual6.12 NVMe/TCP DHCHAP·mutual·negative·bounded RAW I/O다. Cloud/외부 host/Windows/all4/ROOT/AD/정식stable signing/최종11탭UI 인수는 별도다. 현재는 source 조사 및 계획만 완료했으며 effect는0이다.
