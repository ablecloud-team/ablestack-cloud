# #900 formal SMB 복구 후 실제 인증·핸들 검증

2026-10-08 09:29~09:31 UTC. 부모가 동일 원래 repair key 재개 성공을 확인한 뒤 승인된 한 번의 새 synthetic LOCAL_USER password update를 수행했다. 비밀번호는 RAM-only이며 원문/NT 자료를 파일·로그·argv에 저장하지 않았다. 이전 d758/b38의 실패 증거는 보존하고 새 증거를 smb-postrepair-030efd-0c70-proof.json에 분리했다.

실제 구성은 signed catalog701c0c49-2386-47b5-af4f-8ed0155b0dce / epic898-030efd-format-discard-policy / CLI SHA9e24a184300a0069bb038b7c08a42ff61266a596414cad7023d6533a215f6d30와 MGT0c70 선택56-entry 오버레이이다. source 전체 HEAD 기능 배포로 확대하지 않는다.

## 실제 통과한 단계

- 새 정상 API reset job bd1edb90-2e6b-4cd1-99a1-17c64a82ee49 성공1회. 새reset intent를 요청 전에 독점 저장해 응답 손실 후 추가reset을 차단했다.
- current passdb와 동일 RAM password의 비교bool=true. NT/password 원문출력0.
- guest smbclient A240/B241 × UNQUALIFIED/QUALIFIED/DOMAIN =6/6 session setup+tree read 성공. 이전6/6 LOGON_FAILURE와 구분했다.
- host13.1 RO CIFS는 UNQUALIFIED 및 explicit DOMAIN 성공, 기준SHA fe084...보존. QUALIFIED username 문자열 형식은 mount.cifs exit32/CIFS13이며 원인을 추정하거나 전체형식 성공으로 표시하지 않는다. guest qualified는 성공했고 supported unqualified/domain으로 실제 I/O를 검증했다.
- A 인증 mount 후 held inode16777346 열린 핸들로 계속 쓰기/fsync, 오류0. B에 동일 RAM credential mount 후 양방향 파일 read/write 성공.
- A byte-range lock은 B 접근을 차단했고 unlock 후 B lock 허용, A/B baseline SHA 일치. B probe mount는 정상 umount했다. A 장기 RAM 세션은 부모의 선택적 B lifecycle 검증을 지원하기 위해 유지 중이다.

## 원래 데이터·daemon 관측

09:31:07 UTC original sentinel inode16777345/UID:GID1001:1001/mode0660/SHA fe0840c894bd2240249ceffe5db2390a253d4e05a11d763f2ca6aed71bd33760 보존. actual listener A346908/start2976913(parent346905), B346911/start2976914(parent346909)는 current passdb inode1381 및 secrets1421의 nlink1 FD를 보유한다. repair 이전 deleted inode3858/3850와 다르다. 두 managed endpoint unit은 active이고 A240/B241의 exact445 socket은 각각 해당 smbd가 소유한다.

09:31 status는 A writes375/errors0/heldFdOpen=true/같은heldinode와 baselineSHA를 기록했다. endpoint selective disable/delete/re-enable, reboot 후 양쪽 daemon/readiness/same credential 재검증은 아직 별도 단계이다. 준비된 장기 세션이 존재한다고 이 게이트를 완료 처리하지 않는다.

원래39/41/49와 partial51/021b 변경0, 신규disk0/THIN0, 추가 password reset0. MGT/agent/native/network restart는 I/O critical 동안 HOLD한다. 최종 UI#1275 QA는 착수하지 않는다. 이슈#900 OPEN 유지.

## 선택적 B 삭제 실제 통과

부모 정상 deleteStorageServiceProtocol(B241:445만) job8ec8520e-76cc-48db-9132-a4d7fb46beb3 성공 후 delete_probe는 BPortStillReachable=false를 확인했다. Aheldinode16777346/writes596/errors0/같은baselineSHA 유지. 09:32:12UTC A346908/start2976913(parent346905)/A240:445 socket이 그대로이며 B346911은 사라지고 Bmanagedunit inactive/listener 없음이다. original sentinel inode16777345/UID:GID1001:1001/0660/SHA보존을 별도 smb-postrepair-b-deleted-readonly.json에 저장했다. API가 Cloud primary241 주소를 유지한 것과 SMB B listener 삭제를 구분한다. A장기 RAM client는 계속 유지하며 재enable 및 reboot 게이트는 다음 단계이다.

## B 재활성화 및 재부팅 전 quiesce

부모 UI의 정상 enable operation0f7abe13-9f01-49fe-bea3-0a67cecfd8e0는 Gen14 COMPLETE였다. 같은 RAM credential check_b에서 B auth/crossRW/byte-range lock·unlock 및 baseline을 모두 다시 통과했다. Aheldinode16777346/writes843/errors0 유지. 09:33:05UTC A346908/start2976913는 보존됐고 새 B354713/start3031552(parent354712)/두 exact445 listener/currentTDB FD1381+1421 nlink1 및 original sentinel을 확인했다.

승인된 reboot_prepare는 writer stop→thread join→held FDclose→Aumount를 순서대로 수행했다. before snapshot은 writes940/errors0/heldinode16777346이며 응답 allClientMountsGone=true이다. 응답의 heldFdOpen=true는 close 전 before snapshot이며 종료 후 현재 열린 FD를 의미하지 않는다. 장기 host client process는 동일 RAM credential만 유지해 부모의 정상 VM50 reboot 후 reboot_probe를 기다린다. reboot 이후 인증/readiness/network/UUID 확인은 아직 pending이다.

## 재부팅 게이트 실제 실패 및 보존

부모 정상 VM50 reboot 후 bootID66f53e35-d969-4839-b912-b7781f6c6e93로 바뀌었다. 09:35:35UTC/uptime95.9초와09:37:28UTC/208.83초 fresh 관측은 runtime-bootstrap success/exited, reconcile service failed/exit1, SMBmanagedunits·445listeners0, secondary241없음을 확인했다. bootstrap 성공을 protocol 복구 성공으로 해석하지 않는다.

sanitized boot log는 root_binding_targets의 ROOT endpoint primary IP does not belong to its pinned MAC 오류와 이어진 SMB endpoint IP is not assigned 오류,12/12 attempt 실패를 보였다. 현재 guesteth0MAC02:01:00:cc:00:09/primary240/16/gateway10.10.0.1/DHCPprocess0는 유지됐지만 Cloud primary241와 충돌한다. 부모 formal NIC repair dry-run은 ELIGIBLE이나 아직 적용하지 않았다. 원래 DATA XFS UUIDa4337197-eab7-4e86-bd4a-a7b4ffd64adf/현재sdb와 original sentinel inode16777345/1001:1001/0660/SHAfe084 및 LOCAL_USER1001은 보존됐다.

same RAM reboot_probe가 bounded readiness에서 실패한 뒤 기존 helper의 status()가 이미 unmount된A의 baselinefile을 다시 읽는 버그로 응답 대신 종료됐다. secret-bearing stderr는 출력하지 않았고 자격 증명은 폐기됐다. ownclient root없음/A·B mountfalse를 별도 readonly로 확인했다. 전반부 실제 인증·crosslock·B selective lifecycle 통과는 유지하지만 재부팅 후 인증은 미완료다. 추가reset/수동IP·daemon복구/추가reboot0이다.

수정된 scratch v2는 unmounted currenthash를 관측하지 않고 expectedhash와 구분하며 failure 이후 RAM credential을 유지해 다음 명령을 받는다. 실제command-loop AST를 쓰는4회귀(unmounted/live/timeout→failure→nextcommand/quiesce before-after)가 PASS했다. v2는 원격 미실행이고 이 unit 결과는 native boot 복구 또는 실제재부팅 성공을 뜻하지 않는다. sourcekernel/rootowner의 정식 NIC/boot fix와 재개 일정 후 별도 승인된 검증이 필요하다.

## 정식 NIC CAS 복구 후 두 번의 정상 cold boot / 동일 RAM 인증 통과

최초 cold negative 이후 부모가 정상 UI의 NIC identity repair CAS로 Cloud defaultNIC769fbd70-0979-4d3a-8173-8030c0919b60의 primary241→240을 같은MAC에서 정렬하고 secondary241을 보존했다. 이어 기존B에 대한 정상 enable/reapply c62be023-13b8-4e64-82ec-8dedca2891fd가 Gen15 COMPLETE되어 native source binding을 fresh240으로 저장했다. manual DB/IP/receipt 편집0이다.

두 번째 정상 reboot는 bootID1426d39c-7824-4f2d-a8f5-29621b5da469로 변경됐고 120초 내 양쪽owned445/primary240+secondary241/route/DNS/SID/DATA를 복구했다. reconcile은 Result=success/ExecMainCode1/ExecMainStatus0이며 RemainAfterExit 없는 oneshot의 inactive(dead)는 정상 종료이다. 최초 poll이 active/exited만 요구했던 과엄격 조건은 정정해 별도 증거에 명시했다. API09:45:53UTC도 같은defaultNIC/MAC/primary240/secondary241와 STATIC240/16/gw0.1을 대조했다.

첫 번째 postrepair RAM credential이 helper 오류로 폐기됐으므로, 부모가 별도 second-cold 시험용 normalreset1회(jobbf256d99-ced2-4c0f-94f6-202f63403881)를 승인했다. 이전jobbd1의 동일 credential인 척하지 않고 새 proof smb-postnic-second-coldboot-030efd-0c70-proof.json로 분리했다. comparebool/guest6/hostunqualified+domain/BcrossRW/byte-range lock 모두 통과, Aheldinode16777346/writes260/errors0를 확인한 뒤 정상quiesce(afterFDfalse/writerStoppedtrue/noMounttrue)했다. v2 장기host process는 이 새 credential을 RAM에서 계속 유지했다.

부모의 세 번째 정상 reboot는 newbootIDbc60a348-7ce1-4ca6-9ed4-33ebf406e05d, uptime24.18초에서 양쪽owned listener(A1487/B1491)와 정상oneshotexit0/주소/route/SID/DATA를 확인했다. 이후 **같은 RAM credential을 그대로 사용한 reboot_probe는 readinessAttempts1, A/Bauth/crossRW/byteRangeLock 차단→해제 및 baselinehash를 모두 통과**했다. 추가reset0이며 reboot 전afterquiesce 열린session이 계속된다고 주장하지 않는다. 재접속이 같은자격증명으로 성공한 것이다.

finish 후 REMOTE_EXIT0, host의 자기client/authroot없음/A·Bmountfalse를09:48:07UTC readonly확인했고 credential폐기/I/Ocritical해제했다. DATAFSUUIDa433.../sdb/originalsentinelinode16777345/1001:1001/0660/SHAfe084 및 UserSID S-1-5-21-3551915171-1730839649-2712666417-1000는 이전baseline과 동일하다. currentTDB inode1381+1421 nlink1도 coldboot 뒤 양 daemonFD에서 일치했다. 최종API09:48:53UTC는 두SMBReady 및 resetGen16 COMPLETE/이전reapply15·enable14·delete13·repairCOMPLETE_NO_CONFIG_CHANGE를 확인했다.

이 실제인증시험의 관리 구성은0c70이고 이후d3f9 배포의startup/health는 별도증거다. 최초cold failure도 역사로보존하며 #900/#913 최종release/전체원자·AD/ROOT·multiNEW/최종UI1275 인수를 자동완료로 승격하지 않는다. 신규disk0/원본39·41·49 및 partial51변경0/THIN신규0이다.
