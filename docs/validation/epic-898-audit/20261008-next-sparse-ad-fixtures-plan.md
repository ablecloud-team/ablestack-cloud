# 다음 SPARSE DATA 및 AD 격리 fixture 시험 계획

2026-10-08 12:23 UTC 읽기 전용 inventory 및 현재 source38a4e51 기준이다. API/SSH runtime credential은 기록하지 않는다. 12:23 계획 이후 부모 allocation-only GO로 새 SPARSE20GiB 한 개를 생성했다. 최신 단계는 아래에 기록하며 연결·포맷·삭제는 아직0이다. 기존 VM50 DATAa0bbe... 및 VM51 partial021b, 원본39/41/49를 보존한다. 최종 UI#1275 표준 QA는 착수하지 않는다.

## NEW20 GiB volume-only 시험

사용자 Epic 시험 범위와 SPARSE 이상 필수 조건에 따라 새 DATA 한 개를 만드는 계획이다. 별도 기존 volume 재포맷이나 추가 사용자 허가를 전제로 하지 않는다. 부모가 현재 API/UI/native critical 일정에서 시작을 정한다.

| 항목 | 지정 |
| --- | --- |
| 대상 | VM50 / instanceb54a3c04-fe88-4bde-9ae3-1c67e0998030 |
| 새 이름 | epic898-volume-only-sparse-audit-10 |
| 오퍼링 | c48ab8d8-faba-4d43-85a6-e98a9ecc0cd5, customized=true/shared/SPARSE/no tags |
| pool / zone | 90b0c4e3-7078-4372-8da0-9625bc0e806a / f4fd8c56-ed31-4dfd-bf3c-10e3000fd10f |
| 논리 크기 | 정확히21474836480 B, createVolume(size=20) |
| 사전 qemu measure | metadata required3538944 B, fully-allocated21478375424 B |
| 준비 | 새 UUID serial/size/ROOT ancestor 제외 후 XFS FORMAT_IF_EMPTY 1회, SKIP_DISCARD 지원 확인 |
| 기존 DATA | a0bbe... MOUNT_EXISTING, mkfs0/owner recursive 변경0 |

정상 API createVolume(name,diskofferingid,size=20,zoneid,storageid,displayvolume=false) 전 intent를 저장한다. 사용자/도메인/project·volume UUID·offering·pool·SPARSE readback과 host agent preallocation=metadata 로그/qemu info backing-none를 대조한다. qemu measure는 예상 최소 metadata이며 실제 파일 allocation 증거를 대신하지 않는다. empty signature/serial/current device/size·ROOT exclusion proof 전 FORMAT은 시작하지 않는다. 기존 approved physical160 GiB/free3.30 TiB/formatter AS1 GiB 한도보다 작은20GiB만 대상으로 하고 새 formatter PID를 추적한다.

정규 createStorageNfsExport의 새 volume UUID/importmode=FORMAT_IF_EMPTY/filesystem=xfs로 attach·prepare하고 fresh FS UUID를 고정한다. 준비 완료 후 모든 후속 share는 MOUNT_EXISTING만 쓴다. native request/echo/status/success receipt와 formatterStarted 횟수1을 대조하며 partial이면 자동 재포맷/삭제/cleanup하지 않는다.

새 prefix는 epic898-volume-only-audit-10이다. 현재 NS/SN 실패 prefix는 손대지 않는다.

1. 새 DATA에 NFS parent rel=.../cross-parent, owner/anon1002 및 별도 SMB same-protocol parent rel=.../same-parent를 만든다. 기존 DATA에는 같은 relative의 새 SMB child만 만든다. 두 volume의 FS UUID가 다른 것을 확인한다.
2. 기존 DATA의 cross-parent/child SMB를 updateStorageSmbShare(id,volumeid=NEW)로만 바꾸려 한다. relative/path/name을 생략하고 crossprotocol=false를 유지하여 effective stored relative와 새 volume의 NFS overlap이 적용되는지 검증한다. 정상 source doUpdateStorageSmbShare는 volumeid만 있어도 validateFileSharePathAvailable을 부르므로 거절은 native preparation 앞에 있어야 한다. row volume/path/relative/FS UUID·GEN·inode/hash unchanged가 negative 인수 증거이다.
3. same-protocol branch에서 volume-only 이동이 허용되면 새 FS UUID로 재검사되고 stale backingPath/lastInspection이 제거되는 것을 API/native/부모 UI로 확인한다. 기존 volume의 source directory/file은 보존해야 한다.
4. name-only/path-only 변경은 stored relative를 유지하고 legacy default로 이동하지 않아야 한다. foreign/new/replaced UUID와 wrong FS identity는 효과 전에 거절한다.
5. crossprotocol=true의 일반 named ACL cross-I/O는 Ganesha4.3 실패의 해결 전 완료로 주장하지 않는다. 이번 작은 디스크의 overlap negative 및 same-protocol identity 시험을 분리한다.
6. 성공한 새 자기 share만 metadata 삭제→정확 UUID detach/deleteVolume 한다. formatter partial이면 보존하며 정상 cleanup 승인을 별도로 조율한다.

현 capacity readback12:26:11 UTC는 기존31개/논리할당12556027510048 B/total16319648235520 B/reserved0/factor4/threshold0.85로 이전과 같다. 정상 headroom약1225.313 GiB에서 새20 GiB 후 약1205.313 GiB다. #909 source+target170 GiB 및 별도 AD ROOT약5+DATA20을 동시에 남기면 총215 GiB 추가이고 약1010 GiB가 남으므로 NEW1TiB 보조 시험과 동시에 유지할 수 없다. #909 정리 후 큰 시험을 순차 진행한다. 정확10TiB 문제를20G/1TiB로 대체하지 않으며 guard23325/factor4 finally는 미완료 상태이다.

## AD 서비스는 새 단일 isolated NIC 우선

실제 network5b054061-0c15-4389-bfca-873871f4b470는 Implemented/Isolated/VLAN201/192.168.16.0/24/gateway.1이고 UserData·DHCP·DNS를 제공한다. zone은 Advanced다. DC.2 및 Client.11은 같은 망에 있다. network DNS 기본값은8.8.8.8이므로 이를 AD DNS라고 해석하지 않는다.

새 epic898-ad-sparse-audit-11을 primary isolated DHCP로 만드는 안을 우선한다. 정상 createSharedFileSystem은 networkid 한 개만 받으며 STATIC은 source상 L2 전용이다. isolated망에 STATIC ipcidr를 넣지 않는다. Cloud가 배정한 주소를 readback하고 기존 DC/Client/NIC/secondary와 중복0을 확인한다. 임의로.12를 보장하거나 기존50에 blind NIC 추가하지 않는다.

ROOT/DATA 모두 SPARSE SO fc808c8b-00fa-42d2-98b6-a082f12c347f와 customized SPARSE DO c48.../size20을 쓴다. 실제 ROOT linked offering90b3f7d0...·volume provisioning·preallocation metadata·backing-none·최종 template 실제 virtual size를 생성 후 검증한다. 계획상 ROOT5+DATA20약25GiB이나 최종 image 크기/템플릿 reserve는 아직 확정되지 않았다.

CreateSharedFSCmd에는 templateid가 없고 StorageVmSharedFSLifeCycle.deploySharedFSVM은 templateDao.findSystemVMReadyTemplate로 선택한다. **새 최종 image Ready 등록만으로 해당 fixture가 그 image를 사용했다고 간주하지 않는다.** 부모/kernel이 정상 템플릿 선택을 확정한 뒤 실제 VM template UUID/ROOT lineage/protected template manifest platformVersion4.23.0.0·POM SHA를 readback해야 한다. 현재 final all4/AD template Ready0이므로 b843/ea392 prototype으로 AD·fresh ROOT 인수를 대체하지 않는다.

SMB/AD template와 signed manifest가 SMB_ACTIVE_DIRECTORY, SMB_AD_IDENTITY, POSIX_AD_PRINCIPALS를 실제 지원하고 fresh caps가 일치한 뒤에만 join을 한다. 새 activation은 manager/agent/template version을 각각 실제3~4 numeric parts로 검사하며 five-part/UNKNOWN을 자르거나 예외 허용하지 않는다. 기존 installed legacy read service는 자동 중단하지 않는다.

joinStorageServiceToAdDomain(instanceid,domainname=ablestack.local,workgroup=ABLESTACK,dnsservers=192.168.16.2,username,password RAM-only)의 정상 경로로 service DNS를 명시한다. network 전역DNS/VR/GPO/NTP/security를 바꾸지 않는다. join 전 guest route/sourceIP/DNS SRV/DC reachability/host UTC 및 DC UTC를 읽고, join 후 JOINED/SID/idmap/protected encrypted identity/SPN/alias/Kerberos를 검증한다. Windows Client OOBE 사용자 완료 회신 전 Client DNS/hostname/domain mutation은 금지한다.

Windows Client에서 동일 VLAN 서비스 IP의 정상 DNS 이름/UNC로 Kerberos 실제 인증·AD user/group 권한·POSIX SID↔UID/GID를 시험한다. 관리 호스트와 guest는 QGA로 관측할 수 있으므로 공개 포트포워딩이나 기존50 네트워크 변경이 선행 조건이 아니다. isolated DHCP/boot와 DNS persistence를 검증한 뒤에 필요한 경우에만 **새 fixture**의 L2 secondary NIC 계획을 재검토한다. generic addNicToVirtualMachine은 Advancedzone/same owner/zone/network permission/MAC uniqueness 검사지만 SharedFS cold rootBinding 모든 NIC persistence를 보장하는 인수 증거는 아니므로 dual-NIC는 별도 단계이다.

증빙: 910-new-sparse-data-ad-fixture-readonly-inventory.json, ad-isolated-network-service-readonly.json, 910-new20-sparse-capacity-and-qemu-measure-readonly.json 및 기존 authoritative capacity. 실제 생성/포맷/AD 가입/새 네트워크 변경은 본 계획 단계에서0이다.

## 21:34 KST allocation-only 실제 단계

부모 allocation-only GO로 이름 epic898-nested-volumeonly-sparse-20261008-122953 / UUID7b4fae44-e926-41c9-bdd5-c0eca7ccd634를 정상 createVolume으로 한 번 생성했다. 표준 customid가 intent UUID와 일치했고 job364060bb-d563-45c8-8f43-3a5664690ea2는 status1/code0이다. API Ready/DATADISK/SPARSE/20GiB/admin·ROOT domain/정확 pool·zone·오퍼링, displayvolume=false 및 VM/device NULL를 확인했다. 같은 intent의 재실행은 조회만 하고 새 createVolume을 보내지 않음을 실제 검증했다.

host13.1/.2/.3은 공유 GFS의 같은 inode1313342를 관측했다. qcow2v3/backing-none/virtual21474836480 B, actual allocation3743744 B다. 읽기 전용 QCOW2 metadata 검사에서 L1 size40/전체L2 table40/327680 entry를 확인했고 data cluster는 읽지 않았다. INFO agent log에는 literal qemu-img preallocation=metadata argv가 남아 있지 않아 그 argv를 관측했다고 주장하지 않는다. Cloud SPARSE와 실제 metadata 구조 증거는 별도로 확정했다.

12:34:25 UTC DB는32 volume/allocated12577502346528 B/reserved0/total16319648235520 B로 정확히20GiB 증가했다. factor4/global1/threshold.85/custom4096은 unchanged이며 정상 headroom1205.316 GiB다. 물리 free3843055128576 B이고 세 호스트의 같은 pool을 합산하지 않는다. 각 host의 live/inactive libvirt XML에서 새 volume disk reference0을 확인했다.

부모가 숨김 volume 상세·NFS selector를 실제 UI에서 검증한다. display flag를 임의 변경하지 않는다. 현 단계 attach0/format0/delete0이며 기존 DATA/partial021b는 보존한다. 증거는 910-new20-sparse-allocation-only-proof.json이다.

## 21:44 KST 표시 metadata 변경과 정상 경로 복구

처음 displayvolume=false는 감사자가 내부 hidden backing 조회 경계를 함께 확인하려고 명시한 값이었다. 일반 unattached DATA의 필수 조건은 아니며 부모가 이 새 일반 fixture만 정상 updateVolume(displayvolume=true)를 승인했다. 기존 managed hidden DATA와 default visibility는 바꾸지 않았다.

정상 metadata-only update jobda49290e는 status1/code0으로 완료됐지만 before API path7b4UUID가 after DB volumes.path=NULL로 바뀌었다. API view만 생략된 결과로 단정하지 않고 DB와 source를 대조했다. VolumeApiServiceImpl.updateVolume의 path==null else도 volume.setPath(path)를 호출하는 결함을 확인했고 별도 P1#1333 수정/meaningful regression을 부모/backend가 맡았다. 실제 qcow2 파일/inode/metadata는 보존됐다.

부모가 승인한 새7b4 한 개만 original intent·before API path·host file inode1313342·pool/owner를 검증한 뒤 정상 updateVolume(path=exact7b4UUID,displayvolume=true)로 복구했다. jobe548b312는 status1/code0, readonlyDB path7b4UUID/size21474836480/SPARSE/display1/instance/deviceNULL/Ready/removedNULL/pool1/owner2/domain1이다. host inode1313342/apparent21478375424/allocated3743744B/0:0 그대로이다. 수동 DB 변경0, 원본/partial path 변경0, attach/format/delete0이다.

현재 표시값은 true이며 부모가 정상 volume 상세/selector를 검증한다. source P1 수정 및 metadata-only 재회귀는 아직 별도 단계이다. hidden known UUID lookup의 기존 attached DATA 검증과 일반 volume 표시 정책을 혼동하지 않는다.

## 22:10 KST #1333 실제 UI/API metadata omission 재회귀

부모가379 관리 모듈 위 Volume13 class family hotfix를 배포한 뒤, 정상 UI Edit의 경로 switch OFF로 새7b4의 이름만 ...122953-path-check로 변경했다. exact UpdateVolumeCmdByAdmin job9ec92f34-f35b-486d-960e-297414ad05d1(DB894/volume62/actor2)는 status1/code0이고 API/DB pathUUID와 SPARSE20GiB·owner/pool·미연결 상태를 보존했다.

이어 승인된 새7b4만 normal updateVolume에 path parameter를 넣지 않고 deleteProtection true→false 및 displayvolume false→true의4개 작은 intent를 실행했다. job3d6a7831/60179cd8/75a5abde/bd8db374는 각각 status1/code0이며 매번 API tuple와 DBpath/size/provisioning/owner/pool/VM NULL가 보존됐다. host qcow2 inode1313342·virtual20GiB·allocated3743744B 및 header+전체L1/L2 metadata SHAe72bc0b2...가 전후 동일이다. DATA cluster는 읽지 않았다.

현재 name path-check/display=true/deleteProtection=false이다. 기존/root/attached DATA는 실제 변경하지 않고 source4조합 unit과 구분한다. 새 disk attach/format/delete는 계속0이다. 증빙1333-new20-normal-ui-rename-after-readonly.json, 1333-new20-normal-api-metadata-roundtrip.json을 기록한다.
