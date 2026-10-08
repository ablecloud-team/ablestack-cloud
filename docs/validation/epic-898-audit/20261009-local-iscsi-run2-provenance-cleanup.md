# 로컬 iSCSI run2 계보·선행 적용·정상 cleanup 차분

이 문서는 부모의 [서비스 clear 실패 기록](20261009-local-iscsi-restart-clear-failure.md)에 추가되는 계보와 cleanup 증거만 정리한다. TCP 실패·journal의 상세를 중복하지 않는다. 원래 firstfailure/9a83 proof는 보존했으며 run2도 login/RAW 쓰기 0이다.

## 별도 signed 82cf와 hybrid copy

기준 source image는 exact fc3e / bfa SHA이며 수정 runtime은 `82cf23e2fae9d90ae3506c7b332dff095fd65f1b`다. 새 RAM Ed25519 key를 sealed memfd로만 사용했고 sign 후 FD를 닫았다. 원본 prototype을 다시 만들거나 relabel하지 않았다.

| 필드 | run2 실제 값 |
| --- | --- |
| signed archive SHA | `3b73594eeaa8d0452ccf36cc742ec7db35ad9301d6a8163821e415355ca57229` |
| signed manifest SHA | `5190abea9d6eea5e0e6a9054ec0cdebc368f798cd4c2dbb8beca3a1ff466a588` |
| installed CLI SHA | `65ab921bda59250a33bf78dc3d31417e9b8ad4052c4b98e8d288554b61c5caff` |
| installed iscsi_auth.py SHA | `4125ab3775a078de99b0ef071683ae0f6a1d76f594ee0f0e18cbfd960911a560` |
| installed native_render_runtime.py SHA | `77cee11eefed54f3e93d5e9073934110699d4c2178a2d930546619c118d46e4b` |
| updater SHA | `161c4726fdfe53e9eba51b635862a4f9dceb000acf7a2fb165541710d1b0e131` |
| runtime version | `epic898-local-82cf-iscsi-auth-20261009` |
| normal signed readback | signedRuntime / installedFiles / entrypoints verified true |

저장된 failed iSCSI desired가 존재하고 boot reconcile이 enabled임을 먼저 읽었다. old 9a83의 자동 재시도를 피하려고 parent 승인에 따라 **자기 Root copy의** 원래 reconcile unit을 backup한 뒤 임시 mask했다. offline chroot에서 동일 normal signed updater의 begin/transfer/finalize/verify/preflight/activate/readback을 수행하고 NBD를 정상 정리했다. boot 이후 QGA에서도 fresh readback을 다시 확인했다. 직접 unsigned CLI 교체로 대체하지 않았다.

base template manifest는 fc3e, 현재 component 및 support module은 82cf로 구분한다. CLI embedded closure와 별도 support file hash는 서로 다른 증거다. 이 hybrid copy는 최종 template/release가 아니다.

## 실제 디스크 이름 재해석과 apply 수

run2 QGA는 106.34초 안에 준비됐다. kernel `6.12.95+deb12-amd64`, boot `94df1c18-276b-40ea-9090-04dd796013cf`, lo ONLY 및 reconcile masked 상태를 확인했다.

| 역할 | 첫 run | run2 |
| --- | --- | --- |
| NEW RAW64 MiB | /dev/sda | /dev/sdb |
| ROOT / ancestor | /dev/sdb6 / sdb | /dev/sda6 / sda |
| RAW serial | `a4b1fbac5ab940b4b0e5` | 동일 |
| ROOT serial | `5a90d4777d914e9887f6` | 동일 |

과거 device path를 재사용하지 않고 serial/size/ROOT mount·ancestor 및 빈 signature exit2로 다시 해석했다. run2 payload는 fresh RAW /dev/sdb만 사용했다. masked boot의 자동 apply는 0, QGA의 explicit normal CLI apply는 1회다. 실패 후 추가 apply 또는 helper 우회는 0이다.

## 정상 종료 및 원래 unit 복원

QGA powerdown 뒤 자기 QEMU PID `310547` / startTicks `4892293`는 정상 종료했다. guest reply는 연결 종료로 받지 못했으나 PID gone을 확인했고 강제 quit/kill은 0이다.

- 원본 full SHA `bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c` unchanged.
- NEW RAW container full SHA `d736a1b6a0145f8e0132f938f70620f0cf698cb77ad441a5f5207f92ac1d24cc` unchanged.
- Root/Data qemu check errors 0, 자기 mount 0, NBD7 size0 / owner PID 없음.
- QEMU 종료 후 자기 inactive copy의 reconcile 원본 unit을 backup SHA `2e13eb55a8a02ef41faf1edec1b3f294d590470760f3f8c9504175c44de7afb2`로 복원했다.
- backup은 보존하고 추가 boot는 0이다. product/global prefs와 다른 VM의 unit은 바꾸지 않았다.

증빙은 `local-iscsi-auth-9a83/`의 `run2-offline-normal-signed-update-proof.json`, `run2-fresh-guest-readback-before-target-proof.json`, `run2-before-target-root-exclusion-and-raw-identity-proof.json`, `run2-cleanup-and-original-preservation-proof.json`, `run2-original-reconcile-unit-restored-proof.json`이다.

Cloud 등록/13번 배포·새 VM·기존 DATA·partial·OOBE 변경은0이다. source service lifecycle 수정의 새 signed pin과 parent GO 이후에만 run3를 수행한다. 실제 CHAP positive/wrong/mutual/fresh reconnect 인수는 아직 미완료다.
