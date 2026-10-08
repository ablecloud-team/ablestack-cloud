# Ganesha VFS POSIX ACL 빌드와 실제 NFS 비교 검증

2026-10-08, Bookworm의 로컬 격리 network/mount namespace에서 검증했다. 관리형 서비스 VM/클러스터의 패키지 배포·서비스 재시작·권한 변경은 0회다. 실제 VM50의 기존 Ganesha 4.3 문제를 이 시험으로 직접 수정했다고 주장하지 않는다.

[공식 ACL 지원 표](https://github.com/nfs-ganesha/nfs-ganesha/wiki/ACL-Support)는 FSAL_VFS의 POSIX ACL 지원을 V5.5.3 이상으로 명시한다. [공식 V5.5.3 source](https://github.com/nfs-ganesha/nfs-ganesha/tree/2a57b6d53295426247b200cd100ba0741b12aff9)와 submodule을 다음처럼 고정했다.

| 항목 | 고정값 |
|---|---|
| Ganesha commit | 2a57b6d53295426247b200cd100ba0741b12aff9 |
| source archive SHA-256 | 8dbd579e24a4113ffbea16cd9487978002d9834c5d5d1ac87e8f9f8ccf147528 |
| ntirpc commit | bf7fd0259c33ce95f5f2bf22817a8912f7fe5188 |
| ntirpc archive SHA-256 | 32f129907769c0779555057d46be09d1a6fb30f613cc41d473642cffc7084bdd |
| CMake | ENABLE_VFS_POSIX_ACL=ON, ENABLE_VFS_DEBUG_ACL=OFF, USE_FSAL_VFS=ON, USE_DBUS=ON |

후보 libfsalvfs.so SHA-256은 034f33afd15bb183b510e3227775af6e06645af6a8d07fccb94a5623c7c85616이다. ELF 동적 import에 acl_get_fd, acl_get_file, acl_set_fd가 모두 존재한다. Debian 공식 nfs-ganesha-vfs 4.3-2의 library SHA-256은 f1e6dddc38d83f0a26b2d54bf510e6c6cde7f36fb087851e3d176a156ce25e9c이며 실제 VM50에서 읽은 동일 library SHA와 일치하고 이 세 import가 없다. libacl 링크 또는 버전 문자열만으로 지원을 추론하지 않았다.

같은 구조의 두 격리 fixture에 NFSv4.1 서버와 실제 Linux NFS client를 실행했다. parent UID:GID 1002:1002/mode0770, child 0:0/mode0770에 named user1002:rwx와 default ACL을 설정했다. 기존 파일은 1002:1002/mode0660이고 AllSquash/anonymous1002 및 numeric owners를 사용했다.

| 시험 | Debian 4.3-2 | 후보 5.5.3/ACL ON |
|---|---|---|
| 실제 NFS mount | 성공 | 성공 |
| named ACL child의 기존 파일 읽기 | Permission denied | 성공, 원본과 내용 동일 |
| NFS를 통한 child 파일 생성/쓰기 | 읽기 실패 후 실행하지 않음 | 성공, 생성 UID:GID1002:1002 |
| child owner/mode | 0:0/0770 보존 | 0:0/0770 보존 |

후보의 신규 파일은 일반 생성 mode/umask와 default ACL mask를 따랐다. 기존 디렉터리의 자동 chown/chmod, world permission 완화, 기존 데이터 포맷은 수행하지 않았다. 원본 패키지의 실패는 mount 실패가 아니라 mount 후 파일 접근 EACCES로 구분했다.

원시 증거는 canonical WSL의 /root/work/epic898-preparation/ganesha-vfs-compiled-feature-probe에 있다: source-tag-proof.json, debian43-primary-package-proof.json, cmake553-proof.txt, acl-build-comparison-proof.json, acl-selftest/{source-acl,candidate-symbols,post-stat,client-read,ganesha.log}, acl-selftest43/{source-acl,original-symbols,post-stat,client-error,ganesha.log}, bookworm553-acltest.log, bookworm43-aclnegative2.log.

최종 템플릿 builder에 고정 source package와 보호된 build/library/selftest attestation을 넣고 fresh guest의 실제 byte hash를 재검증하는 후속 단계가 남았다. NFS_VFS_POSIX_ACL capability는 이 검증이 설치된 candidate에서 실제로 확인될 때만 true가 되어야 한다. 기존 4.3 guest는 계속 unsupported이며 최종 single-SHA template/서명 runtime/RPM/CI 및 실제 ROOT·클라이언트 검증은 아직 완료되지 않았다.

## 실제 managed service 실행 경로 추가 검증

초기 private ELF 해시/selftest만으로는 Debian unit의 /usr/bin/ganesha.nfsd 선택을 증명할 수 없는 P1을 발견해 보완했다. 기본 및 managed template unit의 root-owned drop-in은 ExecStart/ExecReload를 reset하고 /opt/ablestack-ganesha/5.5.3/bin/ganesha.nfsd를 고정한다. 설치 manifest는 두 drop-in SHA를 포함한다. unknown late/instance override, legacy executable, 삭제/교체 library inode는 지원 판정을 거부한다.

로컬 격리 systemd PID1 컨테이너에서 실제 inactive effective ExecStart가 private path임을 관측한 후 managed unit을 정상 시작했다. 실제 NFS mount/read가 성공했으며 active PID159/startTicks3406972, executable inode와 VFS map inode가 설치 manifest byte hash와 일치했다. 기본 nfs-ganesha.service는 masked였다. 같은 managed unit을 실제 /usr/bin/ganesha.nfsd로 override해 실행한 negative에서는 nfsVfsPosixAclSupported=false가 확인됐다. 이후 own unit과 컨테이너를 정지해 정리했다. 관리형 클러스터 변경은 0회다.

최종 package SHA-256: f49210a3642a7ac39dc593adedc93d5f0292ff1ac624155b5e0d4058557bf31c
최종 build manifest SHA-256: c53452e56a5273eb5f26a98c61cce41e1106b42f5368b9a49716ed3e89319e93
최종 private VFS SHA-256: 91cd2ae323a156f58d129193bcd461b51564db4c1519b107e652a4a6dd81824f

추가 원시 증거: systemd-effective-before.txt, systemd-inactive-cap-proof.json, systemd-active-cap-proof.json, systemd-effective-selected-legacy.txt, systemd-selected-legacy-negative.json, real-systemd-binding.log, real-systemd-selected-legacy.log. 원시 증거 경로는 위와 같은 ganesha-vfs-compiled-feature-probe 디렉터리다. 전체 fresh template 및 실제 클러스터 ROOT 변경/4protocol fault 검증은 여전히 다음 단계다.

소스는 b5543adda03으로 고정했다. native124/Storage220 및 6개 실행 경로 회귀가 통과했다. 비활성 서비스는 configured binding 검증으로 지원 여부를 판정하되 activeRuntimeVfsVerified=false/activeRuntimeNotApplicable=true로 실제 active 관측이 없음을 표시한다. 실제 PID가 있을 때만 activeRuntimeVfsVerified=true이며 schemaVersion bool 값은 거부한다. 새 패키지를 13번 VM에 설치하거나 최종 템플릿을 빌드한 횟수는 아직 0이다.
