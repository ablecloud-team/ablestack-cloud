# fc3e prototype private USER 등록 및 명시적 새 SPARSE fixture 선택 계획

2026-10-09 01:41 KST의 API/DB/host 읽기 전용 관측과 canonical source를 기준으로 작성했다. 현재 template 등록, artifact server/upload, VM 생성, 디스크 allocation 및 13번 설정 변경은 0 회다. SYSTEM 전역 등록과 기존 template/offering 변경을 계획하지 않는다.

## 등록·선택을 먼저 제한해야 하는 이유

`CreateSharedFSCmd`에는 templateid가 없고 `StorageVmSharedFSLifeCycle.deploySharedFSVM:181`이 `findSystemVMReadyTemplate`를 호출한다. DAO `listAllReadySystemVMTemplates:605`는 SYSTEM/Active/DOWNLOADED 또는 BYPASSED를 ID 내림차순으로 조회한다. preferred architecture 및 hypervisor의 첫 행을 선택하며 account/public/private 필터가 없다. lifecycle은 해당 owner의 launch permission도 자동 추가한다.

따라서 새 prototype을 SYSTEM Ready로 등록하면 다른 사용자의 새 SharedFS 및 해당 selector를 쓰는 SystemVM 작업에 영향을 줄 수 있다. 현재 SYSTEM 기본 후보는 Ready/scalable `b84320d0-adb4-4d70-aee8-3f3d4070812b`이며 원래 동작을 유지한다. prototype은 **private USER**로 등록하고, backend가 준비하는 optional 명시적 templateid 및 보호된 NEW SPARSE fixture 검증 경로로만 선택한다. 미지정 요청은 기존 동작을 유지해야 한다.

신규 경로 source/test/package/live 검증 전 등록 또는 VM 생성을 실행하지 않는다. USER template도 owner/launch 권한, zone store Ready, KVM/x86_64, 동적 확장 지원, 실제 image 및 protected manifest 계보, ROOT catalog의 필수 storage capability를 모두 확인해야 한다. client가 임의로 넣은 prototype detail만으로 이 보호 경로를 허용해서는 안 된다.

## 고정 artifact와 정상 등록 요청

| 항목 | 고정 값 |
| --- | --- |
| source | exact `fc3e7f5c134828288ac89dc98300533ffcb909ff`, 최종 완성 이미지 아님 |
| 이름 | `epic898-fc3e-systemvm-kvm-prototype-20261009` |
| type / visibility | USER / ispublic=false / isfeatured=false, root admin owner |
| hypervisor / arch | KVM / x86_64 |
| OS | Debian GNU/Linux 12 (64-bit), `a961b319-71f4-4750-9a28-e3b0b4d04f4f` |
| format / scalable | QCOW2 / isdynamicallyscalable=true |
| zone | `f4fd8c56-ed31-4dfd-bf3c-10e3000fd10f` |
| compressed image | `systemvmtemplate-4.23.0.0-fc3e-prototype.20261009-x86_64-kvm-202610090027.qcow2.bz2` |
| 정상 checksum parameter | `sha256:c86973ddd394f1d823d513b1cc832f21b742904c5a09387341e42a394d2cc7b4` |
| uncompressed qcow SHA-256 | `bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c` |
| virtual size | 5,242,880,000 B, backing-none, metadata L2 10/10 |
| platform / product | protected POM 기반 `4.23.0.0` |
| source POM hash | `3f7946fe718a90f360ddf9574c14d5b900ab3f3ae2fcfa1a54a918d3485d3f63` |
| CLI hash | `3451f840bb79bf776f37bc2b45f2d6fa4e2b9d9201c461099aa1002901a541ef` |
| public test key | `epic898-fc3e-prototype.pem`, SHA `0a1bb97a92ef6090b9dffd05807c32eb71ad669d8e710a4e0868ab506f504808` |

normal `registerTemplate` 요청은 위 값을 명시한다. URL은 parent GO 이후 SSVM에서 접근 가능한 task 전용 artifact endpoint를 정하고, serving 파일의 exact SHA를 확인하여 확정한다. 현재 endpoint를 열거나 URL이 이미 유효하다고 주장하지 않는다. 공개 manifest/key만 제공하며 signing private key는 이미 폐기됐다.

details는 installed protected `template-manifest.json`의 registrationDetails를 그대로 근거로 하고 prototype 표기를 더한다. fullFour/production AD/retained ROOT 완료를 true로 추가하지 않는다. download job 및 selected zone의 isready/downloaded, format/virtual size/checksum/owner를 readback한 뒤 새 template UUID를 고정한다.

## ROOT 및 DATA SPARSE 조건

첫 all4 fixture는 `epic898-all4-fc3e-sparse-audit-12` 하나를 계획한다. 신규 DATA 20 GiB, ROOT는 template의 약 4.883 GiB이며 보수적으로 5 GiB 이상을 budget에 넣는다.

| 역할 | 정상 오퍼링 및 확인 |
| --- | --- |
| 권장 SO | 4C/8 GiB `57c431e2-3330-4a1d-a0ff-11cbb5321899`, shared, sparse |
| 최소 SO 대안 | 2C/4 GiB `fc808c8b-00fa-42d2-98b6-a082f12c347f`, shared, sparse |
| linked ROOT DO | 4C SO → `1ae2dee3-dbf8-4626-9664-601a7dbef950`; 2C SO → `90b3f7d0-bf5b-46dc-bb6c-d46c57fa8774` |
| DB linked ROOT 값 | 두 행 모두 provisioning=sparse, diskSize=0, removed=NULL |
| DATA DO | `c48ab8d8-faba-4d43-85a6-e98a9ecc0cd5`, customized=true/shared/sparse/no tags |
| initial DATA | NEW/size=20, 정확 21,474,836,480 B, XFS 초기 준비 1 회만 |
| pool | `90b0c4e3-7078-4372-8da0-9625bc0e806a`, SharedMountPoint `/mnt/glue-gfs` |

offering의 enum만으로 완료 판정하지 않는다. 새 ROOT/DATA와 template cache의 최초 create/convert argv가 metadata 이상이어야 하며, 실제 qemu info/backing-none/L1·L2 allocation/XML/Cloud provisioning을 대조한다. RAW protocol용 후속 DATA는 filesystem format 0 회이며 독립 mapping/provenance를 사용한다.

**등록·생성 전 source gap:** 현재 `KVMStorageProcessor.copyTemplateToPrimaryStorage:418` → `KVMStoragePoolManager.copyPhysicalDisk:548` → `LibvirtStorageAdaptor.copyPhysicalDisk:1711/1713`는 새 primary cache를 명시적으로 THIN 생성한다. 뒤에 cp로 SPARSE header를 복사해도 앞선 NEW THIN 생성은 필수 조건에 맞지 않는다. alternate convert도 metadata 옵션이 없다. 해당 cache/copy의 before-first-create SPARSE/FAT guard, meaningful tests 및 실제 agent class pin이 검증되기 전 VM 생성을 실행하지 않는다. 현재 Java freeze 중 수정은 backend 소유로 전달했고 감사자는 source를 변경하지 않았다.

## 주소 및 capacity

첫 all4 fixture는 기존 client 접근이 가능한 L2 `4d9047dc-c8b7-4c80-83bf-2c075adc2995`의 STATIC 후보 `10.10.13.243/16`, gateway `10.10.0.1`을 제안한다. 보조 endpoint 후보는 `10.10.13.244`다. 현재 public API의 VM/NIC 관측에서 두 주소가 보이지 않았지만 전체 DB NIC/secondary 점유 및 L2 ARP는 생성 직전에 별도로 확인해야 한다. 아직 reserved/free 확정 또는 실제 주소 할당을 주장하지 않는다. MAC은 Cloud가 새로 배정하고 원래 240/241/242 NIC를 변경하지 않는다.

AD fixture는 별도 primary Isolated `5b054061-0c15-4389-bfca-873871f4b470`의 정상 DHCP를 사용하고 DC `192.168.16.2` 및 Client `.11`은 보존한다. isolated망에 L2용 STATIC 설정을 넣지 않는다. fc3e는 AD handler 이전 source이므로 AD 완료 candidate로 쓰지 않는다. 별도 signed handler 설치/정상 COMPLETE/protected profile expectedCLI 승인 뒤 시험하며 Client OOBE 사용자 답변 전 DNS/hostname/join을 바꾸지 않는다.

현재 authoritative DB는 capacity type 3 total 16,319,648,235,520 B / used 12,577,502,346,528 B / reserved 0, threshold 0.85, pool factor 4, global factor 1이다. normal headroom은 약 1,205.316 GiB다. 32개 live volume SUM은 약 998 KiB 작으므로 allocator capacity의 더 큰 used 값을 보수적으로 사용한다.

첫 fixture 25 GiB를 빼도 약 1,180.316 GiB가 남는다. 이후 #909 DATA 170 GiB와 ROOT 보수 여유 10 GiB, 별도 AD 25 GiB를 동시에 남기면 약 975.316 GiB이므로 추가 1 TiB 시험과 함께 유지할 수 없다. 실제 #909 frozen plan의 initial/DATA/root 중복 포함 여부는 allocation 직전에 exact plan으로 다시 계산하고 시험을 순차 진행한다.

세 host의 같은 GFS pool을 합산하지 않는다. 최소 physical free 관측은 3,841,122,127,872 B다. 초기 template/cache/ROOT/20G DATA 및 metadata는 보수적으로 20 GiB 이하 physical budget을 제안하고 생성 시 actual allocation과 free3.30 TiB guard를 유지한다. partial 021b 및 factor4 guard23325 보존/finally 미완료를 새 fixture 생성으로 해결했다고 주장하지 않는다. factor/threshold/global max/기존 offering을 변경하지 않는다.

## 실행 전 후속 게이트

1. optional templateid의 보호된 private USER selection source/normal tests/live API 준비 및 cache-copy SPARSE guard를 먼저 검증한다.
2. parent 등록 GO 이후 exact 파일 publish와 normal USER registerTemplate만 수행하고 Ready/zone/owner/checksum를 고정한다.
3. 별도 VM 생성 GO 직전에 DB·ARP·logical/physical budget과 NODE agent pin을 재검사한다.
4. 정상 createSharedFileSystem의 explicit 새 template UUID와 SPARSE SO/DO로 자기 새 fixture만 생성한다.
5. ROOT/DATA identity/provisioning/current device/FS/serial, fresh signed CLI/readback 및 actual boot/kernel/package capability를 검증한다.
6. 보호된 validation profile 인수 후 all4 stage/failure/external I/O를 수행하며 productionFour는 false로 유지한다.

현재는 위의 읽기 전용 계획만 완료했다. 기존7개 서비스, 원본 DATA, partial 021b, Client 47 및 전역 SYSTEM template 선택은 변경하지 않았다.
