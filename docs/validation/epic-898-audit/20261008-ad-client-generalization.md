# #902 Windows Client 일반화 실제 진행 — 2026-10-08

사용자는 SMB-Client SID 일반화를 위한 Sysprep을 직접 승인했다. 범위는 Client VM5bb10934-04b0-4649-b872-fd96eaa5d303/i-2-47-VM/ROOT20036b5f-95e2-4f56-ab3c-16c76afb3e2e 한 개다. DC VM45를 일반화하지 않고 original ROOT를 삭제하지 않는다.

## 일반화 전 보존 증거

DC domainSID와 Client local RID500 SID prefix가 같고 MachineGuid도 같았다. DC4776 credential validation성공 뒤6167 machine ID mismatch/4625 failure로 join이 실패한 사실과 원래SID·GUID JSON을 보존했다. 제공 Domain/local credential은 RAM-only bool검증 및 부모 실제 콘솔 로그인으로 유효함을 확인했다.

양쪽 Windows timezone/UTC는 앞선 부모 GO로 각1회 정정했고 KST/hostUTC delta≤4.81ms/60초 재발0을 확인했다. peer/GPO/보안/SID/암호/서비스restart는 변경하지 않았다.

Client만 정상stop job83ccfe0f-fefe-4e8b-b791-ff5d388a64a2로 종료했다. Cloud transient libvirt domain이 undefine돼 domain없음/active VM없음/QEMU의 sourceROOT openFD없음을 별도로 확인했다. ROOT는기존100GiB SPARSE/backing-none/실제17.3GiB이며 extra DATADISK0이다.

## 실제 SPARSE backup 검증

새 사본은 host13.1의 /mnt/glue-gfs/epic898-audit-backups/smb-client-47-root-20036b5f-20261008.qcow2에있다. folder0700/file0600/rootowner로 보호하고 원본이름을 덮어쓰지 않았다.

- 실제 qemu-img convert는 preallocation=metadata/AS1GiB로30.112초 완료했다. virtual100GiB/actual18,477,129,728 bytes/qcow2/backing-none다. 물리증가160GiB/free3.30TiB 제한을 유지했다.
- qemu-img compare는11.038초/exit0/Images are identical로 virtual content동일성을 확인했다.
- 두100GiB apparent container의 전체SHA256은471.665초에 완료했다. source5f9d30c07fcd506383833c652d76ec8d323481524725354df69805c2b19621ff, backupa2bea5b14beafd155e7f3499e26293a7775f7367c2a0260fdec76e2482a1604c다. qcow metadata재배치로container hash가다르며 virtual content비교와구분한다.
- 원본inode1311830을 유지했고 backupVerified=true/provenance JSON을같은protectedfolder에보존했다. 원문 password/unattend 파일을만들지 않았다. backup은Cloud 추가DATADISK가아닌별도SPARSE image archive다.

backup검증전 Sysprep은0이었다. source/backupSHA/provenance proof는 acceptance-audit/ad-client-root-sparse-backup-proof.json에있다.

## Sysprep 실제1회 및 부팅

원본start738f9327-7cfe-47e4-bffb-c7ddf7b9be57 뒤RootUUID/단일SPARSEdisk를확인하고 16:28:57 KST에 SYSTEM에서 Sysprep /generalize /oobe /shutdown을1회 launch했다(PID4440). 시작직전WIN-K3VFIUGK849/WORKGROUP/role2/originalSID·GUID/KST를보존했다. DC일반화0/추가disk0/원본삭제0/암호파일·unattend0이다.

16:31:53에는GeneralizationState3/CleanupState2/ImageState UNDEPLOYABLE/프로세스응답 및CPU진행을관측했다. Info수준IE registrymissing을fatal 실패로해석하지않고추가launch·강제kill을하지않았다. 16:34:36에는hypervisor domain이 inactive/undefined로종료됐다.

MGT API Running동기화지연을정상stop02bb3a7c-8e5d-447c-888e-fb8f1bc0cbe6로정렬한뒤원본boot5d9deab6-a546-49a2-ae5c-fcd6ac878cef를실행했다. ROOT UUID20036/SPARSE/추가disk0과기존NIC UUID/MAC02:01:00:d4:00:03/IP192.168.16.11/network/gateway를API로확인했다.

16:39:09 부팅은Running이지만 QGA channeldisconnected라새SID/GUID·guestNIC/time는아직읽지못했다. 부모의실제브라우저콘솔OOBE/약관/관리자입력handoff를요청했다. **Sysprep launch·shutdown·boot 증거를고유SID인수완료로승격하지않는다.** 새SID/기존DC와GUID분리·guestNIC/DNS/KSTUTC·join/reboot·AD SMB/Kerberos/SPN/alias/ACL/WindowsI/O/API/UI gate가남는다.

원래39/41/49 및 partial SPARSE10TiB VM51은이번작업에서변경하지않았다. partial021b/poolfactor4/finally원복미완료는별도보존한다. 최종 #1275 스타일표준화착수전전체작업중단·사용자보고·추가지시대기경계도유지한다.

## 16:47 KST 새고유신원 실제관측

QGA재연결후ClientRID500 SID는 S-1-5-21-786796763-2696268617-1721076406-500으로바뀌고 MachineGuid는 c58fa7e3-fb62-4aed-ba5e-88dadd252c6b다. 둘다원래DC/Client중복신원과다르므로실제SID/GUID고유화는통과했다. ROOT20036/단일SPARSEdisk/IP192.168.16.11/KSTUTC를보존했다.

아직msoobe/OOBEInProgress1/SetupPhase4/ImageState UNDEPLOYABLE다. hostname은 WIN-63TQIUEHE5Q로바뀌고guestDNS는DHCP .1/8.8.8.8로초기화됐다. 부모가실제브라우저 Hi there→License terms화면을확인했고약관/새Administrator입력은사용자handoff답변을기다린다. handoff완료전에OOBE우회/unattend/QGA암호설정/DNS·hostname·join 추가mutation을하지않는다. 완료후원래hostname과DNS DC.2 복원·승인된join·필요reboot·ADSMB I/O를계속한다. 고유신원PASS와OOBE/AD전체완료는구분한다.
