# #910 VM50 전용 중첩 공유 실제 시험 단계

범위 승인: 부모의 NEWprefix epic898-nested-audit-09, VM50/instanceb54a3c04-fe88-4bde-9ae3-1c67e0998030, existingDATAa0bbe566-7aa6-4bbd-9998-226a4f52bf0c(20GiB)/FSUUIDa4337197-eab7-4e86-bd4a-a7b4ffd64adf, MOUNT_EXISTING만. 신규디스크/포맷/ROOT/endpoint/IP변경0, 기존audit-baseline·held파일·기존user/password/owner변경0, recursiveapply0.

현재 관리 구성 d3f9 selected58(b38 RuntimeUpgrade family보존), native4a3b stable actualinstalled. finaloneSHA renderer/AD/fulltemplate가 없으므로 전체원자·AD·freshROOT 검증을 완료로 표시하지 않는다.

## 선행 사실과 안전 fence

- 기존fixture48은DATA564e...20GiB를 재사용할 수 있으나 config restore RECOVERY_REQUIRED9행과 SMBUnknown이 남아 있다. 이를 무시하지 않고 부모 승인으로 정상 coldboot/auth/Gen16이 검증된50으로 범위를 옮겼다.
- NEW LOCAL_USER는 OS가 unique UID를 할당한다. 기존1001 epic898-static-client의 alias로 새계정을 만들지 않는다. FORCED_UID_GID>=10000/commonpolicy는 별도계약이므로 이번LOCAL_USER 시험에 섞지 않는다. 새principal의 실제UID named/defaultPOSIXACL은 NEWleaf만 적용한다.
- NFS NEWleaf의 anon/owner1001 또는65534 정책과 SMB NEWprincipal의 actualUID는 구분한다. 기존directory 자동chown 대신 부모의 singleleafpreview/token/명시apply를 사용한다.
- 매 단계 fresh operation/noUnresolvedWriter, exactDATA serial/FSUUID/currentdevice, ownprefix scope, 원래sentinelinode16777345/1001:1001/0660/SHAfe084 보존을 확인한다. 실패 operation은 정상reconcile 전 다음mutation을 진행하지 않는다.
- parentUI와 API가 같은NFS/SMBdesired를 동시에 변경하지 않는다. APIcreate후 exactIDs와config/runtimeproof 전달→부모UIedit/preview/delete→읽기재검증 순서로 진행한다.

## 단계 matrix

| 단계 | NEWrelative 경로 | API 단계 | 실제 인수 |
| --- | --- | --- | --- |
| NN | nn, nn/child | NFS parent/child create | independentACL/clientRW, childdelete 후parent/dir/inode/file보존 |
| SS | ss, ss/child | SMB parent/child create + NEWprincipalACL | sameRAMcredential/clientRW/ACL차이, childdelete 후directory/file보존 |
| NS | ns, ns/child | NFS parent→SMB child | crossprotocol명시허용/권한문제구분/같은DATAFSUUID |
| SN | sn, sn/child | SMB parent→NFS child | independentACL/parentdirectory읽기보존/상호clientI/O |
| CROSS | cross | NFS+SMB samephysicaldirectory | crossprotocol=false negative→true positive, 서로 만든NEWfile 관측 |
| UPDATE | rename/path/update leaf | storedrelative존재+path-only name변경 | effective relative가유지되고legacydefault로이동하지않음 |
| VOLUME | 필요시별도승인NEWSPARSE20G | volume-onlyupdate overlap | volume만바뀌어도nestedcheck/freshFSUUID필수; 현재NEWdisk승인0이라별도pending |
| SYMLINK | ownprefix/escape | 자기NEWsymlink만 만든뒤APIcreate negative | O_NOFOLLOW/escape fence 실패, outside경로쓰기0, ownsymlink정리 |
| BIND | ownprefix/bind-target | 자기NEWsameFSbindmount후APIcreate negative | same st_dev라도mountboundary거절, ownbind만finallyumount, rootmount변경0 |

createStorageNfsExport/CreateStorageSmbShare는 instanceid/volumeid/filesystem=xfs/importmode=MOUNT_EXISTING/relativepath/createdirectory를 명시한다. 정확한crossprotocol API parameter는 crossprotocol이다. NFSv4 mount는 APIlogical path 또는physicalpath를 쓰지 않고 실제Ganesha Pseudo를 사용한다. 모든NEW파일은O_EXCL/짧은고유이름으로 만든다. deleteStorageNfsExport/deleteStorageSmbShare는metadata/ACL만 제거하고 DATA directory/file을 삭제하지 않는지실제확인한다. 기존publicshare를수정하지않는다.

## 부모 UI #894 첫 leaf 단계

UIcreated export06ff6108-d102-4624-abc1-490f71c116b6(nameepic898-ui-root-squash09), Gen17, relative epic898-nested-audit-09/ui-root-squash는 별도namespace이다. NEWleaf inode33685632/UID:GID65534:65534/mode0775/rootSquash=true를확인했다.

첫 client mount exit32는 APIlogicalrelative /export/epic898-nested...를V4pseudo로오인한시험주소오류였다. actualGanesha Pseudo=/epic898-ui-root-squash09/Path=/export/epic898-ui-root-squash09를읽고정규pseudo로1회시험했다. host13.1 root client의NEWfile ui-root-client-rw.txt fsync/readback 성공, observedUID:GID65534:65534/inode33685633/SHA3f7d121ed5662227c76216e25e2e8ad02784771c5bebf719c8d3bbdb31585542, 정상umount/ownmountroot삭제를10:08:32UTC에확인했다. originalsentinel은전체보존됐다. 이rootSquashsubset을全matrix/전체권한/원자복구완료로승격하지않는다.

부모의noRootSquash권장owner0:0/mode0770 preview→singleleafapply/NFSupdate는현재별도단계이며 그완료신호전 다른NFS API변경을보류한다. 최종UI#1275 스타일/테마/키보드 전체QA는착수0이다.

## NN/SS/권한/NUMERIC 실제 단계와 mixed 미완료

NN parenta4415adf-adf0-4ff6-9b6a-07d546a5bd1b 및 child63ecccd8-27b9-4061-9483-9fd790ff8650는 정상 API로 Ready/MOUNT_EXISTING/formatInvoked=false를 확인했다. NFSv4 child write→parent read가 같은SHA5aa3a409.../fileinode33685636으로 통과했다. 부모 UI childdelete Gen23 후 parent 접근, physicalparent133/child33685634/file33685636의 UID1001·mode·hash가 보존됐고 직접 childpseudo는 serverENOENT였다. 모든 ownmount를 정상정리했다.

부모의 새 ui-root-squash leaf는 최초65534:65534/0775/rootSquash에서 rootclientRW 및 file33685633/UID65534를 확인했다. previewtoken default255 제한으로2회handler전431이 발생한 것은 기존권한변경 없이 보존됐다. 좁은 API131072 길이 수정 후 부모의 명시preview/token/checkbox apply Gen20은 singleleaf33685632만0:0/0770으로 변경했다. NO_ROOT update Gen21 후 새file33685635가rootclientRW/UID0으로 생성됐고 oldfile33685633의UID65534/mode0640/hash는 그대로다. RO update Gen22는 clientmount에ro를 지정하지 않은 정상mount에서 기존파일read와 NEWcreate errno30EROFS/파일없음을 확인했다. recursive0/oldDataRoot0:0/0755/originalsentinel 전체보존이다.

NUMERIC service UI Gen24 뒤 Ganesha Allow_Numeric_Owners/Only_Numeric_Owners=true를 먼저확인했다. hostclient nfs4_disable_idmapping은원래Y였고 기존NFSmount0이라설정write0/afterY·inode보존이다. 같은NN file33685636은NAME_DOMAIN client65534 표시에서 NUMERIC client1001:1001 표시로 바뀌었고 실제guestUID는계속1001이었다. NEWnumericfile134/RW/UID1001을확인했다. 파일소유권변경과NFSv4클라이언트 표시변경을혼동하지않는다.

SS parent521d647f-133a-42ab-8614-2604b7547740/child4f2cca2c-9a68-46ff-b107-3be1801857d6와 NEWlocaluser epic898-nested-client09는UID1002이며1001alias0이다. metadata-only RAMcontroller의hand-off 부족을정정해원래credential을폐기하고승인된별도정상update1회+coupledRAMcontroller로 검증했다. 양share auth/crossRW/file16777353(actual1002:1002/0660/SHA14d7da...)와 parentheld1549writes/error0이통과했다. 부모UI childdeleteGen30 뒤parentauth/read/hash 및 physicaldir50331777/16777352·named/defaultACL1002가보존됐다. removed childmount는errno2/ENOENT였으며 명시NT_STATUS_BAD_NETWORK_NAME은관측하지않았으므로 주장하지않는다. A1487/B1491 PID/startTicks와originaldata보존, 정상finish/remoteexit0/ownmount0/credential폐기 완료다.

NS NFSparent27b8ceda-7b71-42a7-9e17-27611598c92b/SMBchildfe5003e1-1fdc-4c2c-998a-0d9f7686e66e, SN SMBparent5b20b5de-5e3e-461d-b756-65414b3d434b/NFSchildcfee9287-7725-4be0-9c50-c3e48c182507는 normal6jobs로Ready, NFSanon/owner1002와SMBnamedACL1002를정렬했다. 하지만 실제 mixed4mount에서SMB NSchild file137/1002:1002/0660/SHAce4a7755... 생성후 NFS NSparent read가EACCES였다. 이는feature실패이며 remoteexit0은cleanup성공만뜻한다. 모든mount/credential정리, childdelete/추가reset/chmod/restart0으로보존한다.

## Mixed EACCES 원인 조사와 새 패키지 gate

exact exportbind/physicalDATA는 dev2064/inode137/FSUUIDa433가동일하다. setpriv1002:1002/clear-groups로두경로를직접읽어SHAce4a...성공, /export·/srv ancestor0755, NFSparent1002:1002/0770, childroot0:0/0770의named/defaultACL1002:rwx 및file1002:1002/0660을확인했다. 실제GaneshaCLIENT wildcard/RW/All_Squash/anon1002:1002도정확하다. 경로불일치/Unix권한실패가아닌NFS VFS ACL enforcement로진단범위를좁혔다.

actual packages nfs-ganesha/nfs-ganesha-vfs는4.3-2, activePID80235의VFS library는/usr/lib/x86_64-linux-gnu/ganesha/libfsalvfs.so다. readelf는미설치(추가설치0), librarybytes에는acl_get_fd/acl_get_file/posix_acl_2_fsal_acl 이름이없었으나libacl은링크돼있다. 링크만으로VFS ACL 지원을확정하지않고동적symbol엄밀검증은미완료로표시한다. [공식Ganesha ACL 지원표](https://github.com/nfs-ganesha/nfs-ganesha/wiki/ACL-Support)는FSAL_VFS의POSIX ACL 지원을5.5.3+로명시한다. 실제4.3 package와Unix/NFS대조negative는현재build의VFS namedACL지원부족에대한강한근거이다.

해결은새공식package/build의VFS POSIX ACL capability 및freshfulltemplate 검증을별도gate로다룬다. owner/worldmode/chmod/daemonrestart를추측해우회하지않는다. mixedcrossRW/NS·SNchilddelete/crosssamepath/volume-only/symlink·bindfence/NUMERICcoldpersist 및전체원자·AD 인수는미완료로유지한다. 최종UI1275 QA는여전히0이다.
