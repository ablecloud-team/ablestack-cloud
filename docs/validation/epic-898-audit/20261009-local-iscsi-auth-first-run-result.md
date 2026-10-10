# 로컬 iSCSI 9a83 첫 실제 실행 결과

2026-10-09 02:31 KST까지 승인된 자기 local fixture에서 실행했다. 결과는 **actual kernel 6.12 boot 및 signed runtime readback PASS, 최초 정상 target apply FAIL, CHAP login/RW matrix 미실행**이다. 원래 source proof의 kernelLoginVerified=false는 그대로 보존했다. Cloud/UI/all4 성공으로 승격하지 않는다.

## 완료한 선행 검증

- 공식 Sahlberg libiscsi `1.20.0` / commit `d960e6253c04770062a7efedd8a92dd34a1ce7fe` / archive SHA `04effeb06941361bc8b3dec923ba59ef4896c97cf0e37cf1401bcba3779051f8`를 owned prefix에 빌드했다.
- C harness는 credentials stdin/RAM, mlock, core dump 0, 15초 wall/PDU timeout, auto reconnect off다. selftest PASS / session 0이며 binary SHA `dbc8641a91ce652e695499fed099ce1cf888f2f44cf53541c4f89e7829a1afe1`다.
- ROOT full convert와 NEW64 MiB RAW용 DATA를 모두 `preallocation=metadata` / backing-none로 생성했다. root L2 10/10, DATA 1/1, 원본과 qemu logical compare identical을 확인했다. overlay/THIN 생성 0이다.
- 자기 Root copy의 QGA만 활성화해 NIC 없는 QEMU를 boot했다. kernel `6.12.95+deb12-amd64`, QGA active, lo ONLY, boot `5dbde964-14bf-4cad-b4c4-c2b7852dcae4`를 실제 관측했다.
- RAW는 `/dev/sda` / 67,108,864 B / serial `a4b1fbac5ab940b4b0e5`, ROOT는 `/dev/sdb6` 및 ancestor `/dev/sdb`이다. unmounted/partition 없음/blkid exit2 빈 signature와 ROOT 제외를 확인했다.
- 정상 signed updater bootstrap/begin/finalize/verify/preflight/activate/readback을 수행했다. CLI `f7031bf4...`와 지원 모듈 2 hash는 exact committed `9a832ada774dce6be1d5aea6cedcf7beaa05f191`에 일치했다. 원래 template fc3e와 현재 component 9a83을 분리 표기했다.
- signed readback의 archive SHA `818cb4610b54d56d4ea0ddec8d094f53bfac6d2bd5cdb45e6744a7023377a234`, manifest SHA `62578015e39a57fdd670c9795ee3e43070fbfa1ad4fc2d6617271fd71da8606c`, installedFiles/entrypoints/signedRuntimeVerified true를 확인했다. signing key는 sealed memfd만 사용하고 닫았다.

빈 RAW signature는 정상이며 첫 preflight 실패는 harness가 기본 discovery_auth 디렉터리를 target으로 센 오류였다. target IQN/EUI/NAA 범위로 바로잡아 readonly preflight를 확인했다. native guard를 완화하지 않았다.

## 최초 정상 native apply 실패

정상 pinned CLI `iscsi target apply`의 protected native writer/configfs 경로를 사용했다. target `iqn.2026-10.local.storage:epic898-auth13` / initiator `iqn.2026-10.local.epic898:ram-initiator13` / NEW DATA만 요청했다.

최초 apply exit1 / success false였다. `ConfigfsIscsiAuth.attribute`의 stat-before-open와 opened-fstat identity 비교에서 `ValueError: iSCSI authentication attribute changed while opening`을 반환했다. descriptor들을 모으는 단계에서 거절되어 auth attribute 값 쓰기를 시작하지 않았다. canonical private credential 상태와 실제 kernel auth attribute 적용은 구분한다.

즉시 matrix를 중단하고 secret RAM을 폐기했다. 정상 rollback_created 이후 자기 target 0 / backstore 0 / port3260 listener 0을 확인했다. 자동 retry, 다른 password variation, 직접 helper 우회 및 RAW I/O는 0이다. positive/wrong/mutual/wrong-target/no-credentials login은 모두 미실행이다.

## configfs inode 실제 관측

own ACL은 rollback으로 제거되어 STATIC discovery_auth의 같은 kernel 4 attribute 메타데이터만 측정했다. own ACL의 직접 측정으로 확대하지 않는다. password/username 값 및 그 해시는 읽지 않았다.

| attribute | pre-stat inode | open FD / held named inode | close 후 fresh inode |
| --- | --- | --- | --- |
| userid | 25307 | 25308 / 25308 | 25309 |
| password | 25310 | 25311 / 25311 | 25312 |
| userid_mutual | 25313 | 25314 / 25314 | 25315 |
| password_mutual | 25316 | 25317 / 25317 | 25318 |

4개 모두 FD hold 중 named inode와 fstat가 일치하고, close 후 fresh inode가 달라졌다. dev/mode/uid/gid는 안정적이었다. 이 실제 관측은 configfs의 stat→open 사이 inode 재생성 가설과 일치한다. source 보완은 symlink/owner/mode/parent-FD/writer 보호를 유지하면서 open 이후 named↔FD 비교를 검증해야 하며, source 담당자가 수행한다. 읽기 전용 메타데이터 관측만으로 auth PASS를 만들지 않는다.

## targetcli dump 우려 정정

초기 line grep에서는 noninteractive control flow의 sys.exit가 누락돼 추가 dump 가능성을 우려했다. 설치된 `targetcli-fb 1:2.1.53-1.1`의 exact line297–306은 noninteractive run_cmdline 후 exit0이며 autosave line321–323에 도달하지 않는다. actual auto_use_daemon=false / daemon inactive를 boolean만 확인했다. global prefs를 임의 변경하지 않았고 불필요 product fix는 하지 않았다.

실제 첫 apply에서도 추가 saveconfig 파일을 관측하지 않았다. 이 초기 우려를 제품 P1 확정 또는 실제 credential dump 사건으로 기록하지 않는다. canonical 제품 private vault는 추가 generic targetcli dump 및 harness/operator credential 파일과 별도다.

## 정상 cleanup과 인수 경계

QGA powerdown 뒤 자기 QEMU PID `299684` / startTicks `4743673`가 정상 종료했다. QMP quit/kill은 0이며 guest shutdown reply는 연결 종료로 받지 못했지만 actual PID gone을 확인했다.

원본 bfa 전체 SHA `bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c`는 unchanged다. DATA container SHA `d736a1b6a0145f8e0132f938f70620f0cf698cb77ad441a5f5207f92ac1d24cc`도 unchanged이며 root/data qemu check errors0, 자기 mount0 / NBD7 size0·PID없음을 확인했다. 자기 파일은 review용으로 보존했다.

실제 protocol writes 0, login 0, target retry 0이다. 원본/partial/Client OOBE/Cloud 등록/13번 배포·재시작은 0이며 productionFour/AD/retained ROOT/최종 UI1275 완료를 주장하지 않는다. 수정된 새 native pin과 별도 재개 GO 이후 실제 인증 matrix가 여전히 필요하다.

증빙은 `/root/work/epic898-preparation/local-iscsi-auth-9a83/`의 creation/source/signature/QGA/readback proof, `local-kernel-auth-execution-proof.json`, `first-apply-configfs-identity-failure-readonly-proof.json`, `configfs-open-held-and-close-inode-proof.json`, `first-run-cleanup-and-original-preservation-proof.json`이다.
