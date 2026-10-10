# SPARSE 템플릿 캐시 복사 모듈 및 실제 호스트 반영

소스 0a10eccd4c0928fc5a1246aebc7f504079b4d378의 두 파일을 pin했다.
정상 KVM -am Checkstyle/package의 7 suites /57 tests가 실패·오류·skip0으로
통과했다. 새 meaningful10은 최초 create와 real convert의 metadata/full,
explicitSPARSE/FAT, QCOW fast-copy 우회 방지, RAW source/RBD source→QCOW,
파일 RAW NULL/SPARSE 및 unknown destination의 생성 전 거절을 검증한다.

NULL copy/cache는 SPARSE이고 QCOW2는 metadata 최초생성·convert를 보존한다.
파일 RAW에서 metadata를 구현했다고 표시하지 않고 NULL/SPARSE는 최초 생성
전에 거절하며 명시적 FAT는 full이다. 기존 다른 요청의 explicitTHIN 동작과
TAR/DIR 및 backend별 RBD/block 제약은 구분한다. 본 SharedFS 새 디스크는
QCOW2 SPARSE이고 새 THIN을 만들지 않는다.

Java9876 NUL source SHA27d210e…는 빌드 전후 같다. immutable classes와
test-classes를 kvm-cache-0a10eccd4c09에 보관했고 실제 host outer+$1 두
클래스의 기존 private/public ABI가 동일하다. 새 QemuImg overload는 기존
host JAR에 있으며 세 host의 해당 QemuImg bytes가 같음을 확인했다.

13.1/.2/.3의 각 정확 baseline JAR을 fullbackup한 뒤 LibvirtStorageAdaptor
family2개만 교체하고 mold-agent를 한 host씩 재시작했다. 다른 JAR entries와
QemuImg 및 libvirt 실행 VM 목록은 모두 보존했다.

| host | backup | 새PID | 새JAR SHA |
| --- | --- | --- | --- |
| 10.10.13.1 | /root/epic898-backup-20261009-023149 | 3734282 | 10fccce60dd077721ea4a633a9baec9d6ca273f6adb913aec73e93f912ce9890 |
| 10.10.13.2 | /root/epic898-backup-20261009-023218 | 3347391 | 43b91addc2028c3ad875e8827b86239b06c1424c0ed2dcb31edccc7b2eea7c67 |
| 10.10.13.3 | /root/epic898-backup-20261009-023231 | 3730227 | f26b5d03cef2297fb692e496b0b558dd1d9a848a971bd90a5a0d56c9700eb339 |

fresh 새 로그인 API에서 3host Up/Enabled 및7instance Running을 확인했다.
Chrome의 원래 nfs-test 상세에서 정상 업데이트→Ready/XFS/100GiB/NFS 개요가
표시됐다. 새 template 등록과 Cloud VM/디스크 생성은 아직0이며 actual
cache 디스크 생성/구조 인수는 다음 보호된 생성 경로에서 별도로 검증한다.

최종 UI #1275는 미착수다. 기존 UI 확인은 기능 회귀이며 최종 레이아웃 QA
착수로 확대하지 않는다. 이슈 인수가 남아 있어 이슈를 닫지 않는다.
