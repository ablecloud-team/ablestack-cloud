# STATIC 및 SMB 다중 IP 실제 인수 시험 — 2026-10-08

부모와 합의한 NEW disposable fixture만 사용했다. 원본 VM39/41와 ROOT 업그레이드용 VM49는 변경하지 않았다. 새 DATA 20GiB thin XFS 초기 준비만 FORMAT_IF_EMPTY로 실행했다.

| 항목 | 값 |
| --- | --- |
| SharedFS | 1ff60dc2-d276-499e-a7ac-3317d299a472 / epic898-static-smb-audit-06 |
| instance | b54a3c04-fe88-4bde-9ae3-1c67e0998030 |
| VM | 145cae0a-6077-4c0c-8cec-e126a36e4b82 / i-2-50-VM / host13.1 |
| template | b84320d0-adb4-4d70-aee8-3f3d4070812b |
| runtime | epic898-20261008-config-generation-agent / source93a30a7397b / catalogf15ce21b-a4d2-49cf-a827-184a4a69213b |
| DATA | a0bbe566-7aa6-4bbd-9998-226a4f52bf0c / XFS UUIDa4337197-eab7-4e86-bd4a-a7b4ffd64adf |
| NIC | 769fbd70-0979-4d3a-8173-8030c0919b60 / MAC02:01:00:cc:00:09 |
| network | L2 4d9047dc-c8b7-4c80-83bf-2c075adc2995 / STATIC10.10.13.240/16 / gateway10.10.0.1 |
| SMB share | 0aa9346a-2a32-441f-a2d1-4ca50d688736 / epic898-static-smb-audit-06 |
| real client | host10.10.13.1 mount.cifs / RAM secret→SSH stdin→PASSWD env / credentials 파일 없음 |

## 사전 안전 점검

host13.1 bridge0에서 두 주소 .240/.241에 각2회 duplicate ARP probe를 실행해 응답0을 확인했다. CloudDB connection.setReadOnly(true) SELECT로 nics(removedNULL)와 nic_secondary_ips 두 테이블의 두 IP 점유 수가 모두0임을 확인했다. protected db.properties/key는 관리 서버 런타임에서만 읽고 원문을 출력하거나 복사하지 않았다.

생성 job e5d88fcd-29fe-4af1-86b8-89cd60008403 SUCCESS. runtime upgrade1520b260-4e78-42d6-bd00-08fe31d3ba7f COMPLETE.

## #913 생성 직후 확인 범위

실제 guest ip route는 default via10.10.0.1 deveth0, 주소는10.10.13.240/16이다. sharedfs-network.json의 MAC/IP/CIDR/gateway가 일치한다. cloud-dhclient@eth0는 masked/inactive이고 실행 중 dhclient는0개다. static network unit은 Resultsuccess/active, Before에 network-pre/network/storage reconcile이 포함된다.

이는 생성 직후 게이트이며 재부팅/renewal/no-gateway/다른NIC명 전체 완료를 뜻하지 않는다. public SharedFS 응답에는 networkmode/ipcidr/ipaddress/cidr/gateway가 있지만 Generic NIC 응답에는 netmask/gateway가 없어 현재 네트워크 표의 해당 칼럼은 공백으로 보인다. 실제 UI 정적 설정 표시도 별도로 보완·검증한다.

## #900 현재 runtime에서 확인한 결함

1. A240:445 활성화 job7ea6ef3e-277e-43a6-a747-85b81918aab0 SUCCESS.
2. 새 share/empty DATA XFS 준비·로컬 인증 생성 jobe53c3bfe-3064-4319-9876-a6093e93083c SUCCESS.
3. 시험 harness의 원격 Python 문자열 줄바꿈 오류가 있어 첫 client call은 실행 전에 SyntaxError로 끝났다. 이를 고친 뒤 새 RAM 비밀번호로 시험 fixture 계정만 update했고 job0c5e08d4-d55a-4b0c-bacd-060c9fc73c76 SUCCESS.
4. 외부 host13.1에서 A240 CIFS mount/write/fsync/read/unmount 성공. 파일50바이트 SHAfe0840c894bd2240249ceffe5db2390a253d4e05a11d763f2ca6aed71bd33760.
5. B241:445 활성화 job3f65e24c-1782-4c76-a490-21ab71537195 SUCCESS.
6. API는 SMB endpoint두 행(A0d3a8e06-804e-4f9f-bb97-7adc559385d7 / B38444337-3bd7-4eec-85f2-63a746e6b7e6)을 Ready/runtimestateREADY로 반환했다.
7. testparm interfaces는 lo10.10.13.24010.10.13.241이며 guest NIC에는 두 주소가 있다. 그러나 외부 TCP B241:445는 ConnectionRefusedError로 실패했다. A는 재mount/read/unmount 성공하고 동일 SHA를 유지했다.
8. server baseline stat은 UID:GID1001:1001, mode0660, inode16777345이다. smbd PID4788/4790/4791을 기록했다.

따라서 설정과 DB endpoint 보존은 통과했지만 신규 endpoint의 실제 서비스와 성공 판정은 실패한다. native의 reload-config만으로 신규 specific-IP listener가 생기지 않고 IP별 socket readback 없이 Ready/LKG로 승격되는 현재 경로를 확인했다. 전체 smbd 재시작만 하면 기존 A 연결 유지 조건을 위반할 수 있으므로 endpoint binding 변경과 기존 세션 보존을 함께 설계해야 한다.

아직 B삭제·같은B 반복요청·재부팅은 이 기록의 시점에 실행하지 않았다. 기존 데이터 볼륨 삭제나 자동 재포맷은 하지 않았다. client 임시 mount는 모두 해제했다. 비밀번호는 RAM에서만 생성·전달하고 완료 후 폐기했다.

## 증거와 UI 재현

WSL scratch acceptance-audit의 static-fixture-create-result.json, static-fixture-bindings.json, static-fixture-runtime-upgrade.json, static-guest-initial.json, static-guest-second-ip-false-ready.json, static-smb-initial-live-proof.json에 원본의 비밀 필드를 제거해 저장했다.

실제 UI는 /client/#/sharedfs/1ff60dc2-d276-499e-a7ac-3317d299a472의 SMB 탭에서 A/B 두 Ready행과 share를 확인한 뒤 외부 B연결 실패와 대조한다. 네트워크 탭은 정적 IP240/MAC을 확인한다. 브라우저 조작·화면 검증은 부모 담당이며 이 감사는 브라우저를 조작하지 않았다.

이 기록은 #900/#913 전체 완료 및 이슈 종료를 의미하지 않는다.

## 반복 요청 및 재부팅 후 추가 관측

같은 B 활성화 job0e84814a-5fd5-4c07-93cb-8c55fde046fe는 성공했으며 두 행의 원 UUID가 정확히 유지됐다. 중복 행 생성은 없었다. 새 SharedFS만 restart한 job5b5e0e70-a46d-42fb-a4ad-83b92ae45f97도 성공했다.

재부팅 후 boot ID는 a28296fa-26d3-46a9-bde8-2f978e413f68이다. guest STATIC240/16+default via10.10.0.1, DHCPmasked/inactive·dhclient0, networkunitBefore reconcile, reconcileResultsuccess/exit0, baselinehash/UID:GID1001:1001/mode0660/inode16777345가 유지됐다. NIC에는 A/B 주소와 smb.conf interfaces 양쪽이 있지만 실제 smbd master1291의 socket은 A240/lo445뿐이며 B socket은 여전히 없다. 따라서 reboot시 복수 endpoint 실제복구 게이트도 실패한다.

부모가 실제 UI에서 NIC 기본IP 불일치를 발견해 읽기 전용 DB와 로그를 대조했다. DB NIC79 primary는 B241, secondary table도 B241이며 실제 guest primary는 A240이다. 10:07:39,082 VmIpAddrFetchThread는 primaryA240을 읽었지만 10:07:39,102에는 IPB241 retrieved successfully를 기록했다. UserVmManager IPfetch의 무조건 setter와 QGA parser의 같은 MAC 마지막IPv4 선택이 STATIC 선언을 덮어쓴 경로다. 인위적인 NIC복구나 DB write는 하지 않았다.

추가 근거는 static-smb-repeat-reboot-proof.json, static-guest-post-reboot.json, static-primary-ip-drift-proof.json이다. 부모 승인하에 per-endpoint Samba master 방식의 별도 실험과 A 열린FD 연속 I/O/B동일 인증/cross-endpoint byte-range lock을 진행 중이다. 해당 실험이 통과하기 전에는 이 방식의 지원 완료를 주장하지 않는다.

## B 전용 master 실험의 실제 클라이언트 검증

부모 승인 후 kernel/runtime 담당이 common smb.conf/passdb/state/lock을 그대로 쓰고 pid directory만 분리한 B-only transient smbd master6629를 기동했다. 기존 A master1291과 A240/lo445 socket은 유지됐다.

real host13.1에서 A CIFS 열린 descriptor를 유지하며200ms append/fsync 및 baseline읽기를 반복했다. B241도 같은 로컬 계정으로 인증·mount에 성공했고 양방향 cross파일 읽기/쓰기와 baseline SHA가 일치했다. A가byte-range lock을 가진 동안 별도Python프로세스의B nonblocking lock이 거절됐고 A unlock후B획득은 성공했다.

B clientmount를 해제한 뒤 담당 agent가 그 transientunit만 선택적으로 stop했다. B445는 연결불가, A 열린FD/inode/baselinehash는 유지됐다. 전체868회 write/fsync/read에서 오류0, 최대fsync0.014204초였다. driver가 정상EXIT0으로 종료됐고 모든 임시clientmount와/run clientdirectory를 정리했다. RAM비밀번호는 폐기했다.

이는 multiparent 가능성을 확인한 prototype이다. 정식 API endpoint삭제, native registry/journal/generation/boot/fault/rollback/권한 및 release source 구현을 완료한 시험이 아니다. 현재 API BReady와 DBprimaryB drift는 원인 재현 상태로 보존했다. 정식 source를 배포한 뒤 다시 전체 lifecycle을 검증한다.
