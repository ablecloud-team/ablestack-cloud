# fc3e 로컬 SystemVM 프로토타입 빌드 결과

2026-10-09 01:29:28 KST에 로컬 정규 빌드가 exit 0으로 완료됐고, 01:32:21 KST에 완성 이미지의 독립 읽기 전용 검증을 완료했다. exact source는 `fc3e7f5c134828288ac89dc98300533ffcb909ff`이다. 이후 canonical base/rebase의 새 SHA로 재라벨하지 않았다.

이 결과는 all4 후속 검증용 로컬 프로토타입이다. Cloud 등록·VM 생성·13번 배포·재시작은 0 회이며 production fullFour, AD, retained ROOT 완료는 false다. 최종 UI `#1275`는 착수하지 않았다.

## 생성 및 artifact

| 항목 | 실제 값 |
| --- | --- |
| source root | `/root/work/epic898-preparation/systemvm-fc3e-prototype-source`, detached exact fc3e, tracked clean |
| 이미지 | `tools/appliance/dist/systemvmtemplate-4.23.0.0-fc3e-prototype.20261009-x86_64-kvm-202610090027.qcow2` |
| qcow SHA-256 | `bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c` |
| 압축 SHA-256 | `c86973ddd394f1d823d513b1cc832f21b742904c5a09387341e42a394d2cc7b4` |
| 압축 크기 | 707,124,991 B |
| virtual / physical allocation | 5,242,880,000 B / 2,070,904,832 B |
| qcow 형식 | v2, cluster 65,536 B, backing-none |
| metadata | 필요한 L2 table 10 개 / 실제 할당 10 개 |
| 생성·변환 | 실제 create 1 회 + convert 2 회 모두 `preallocation=metadata`; `-b`, `-c` 없음 |

Packer fresh ISO 설치에서 software → GRUB → finishing → boot → SSH provision 전진을 읽기 전용 콘솔로 확인했다. 캐시의 기존 ea392 이미지나 Ganesha 패키지를 재사용·재라벨하지 않았다. 초기 SPARSE 이미지 실제 할당은 991,232 B였다. finalization의 zero-fill은 이 새 자기 이미지의 free space만 채우고 자기 zero 파일을 제거하는 committed recipe 그대로 실행됐다.

원본 qcow2 구조·템플릿 검증, bzip2 압축 해제본과 원본 byte 비교, 압축 해제본의 구조·템플릿 재검증을 모두 통과했다. signing key는 sealed Ed25519 memfd만 사용했고 빌드 후 FD를 닫았다. 공개 키만 prototype trust 경로에 저장했다.

## 서명 및 소스 계보

| 항목 | 검증 값 |
| --- | --- |
| platform / product | POM에서 직접 가져온 `4.23.0.0` |
| prototype runtime version | `4.23.0.0-fc3e-prototype-20261009` |
| source POM SHA-256 | `3f7946fe718a90f360ddf9574c14d5b900ab3f3ae2fcfa1a54a918d3485d3f63` |
| source tree SHA-256 | `8ebbe5218a3a77d3f96aad7586f000e190bf77c6ec7eeed6b5077167ea74e751` |
| 설치 CLI SHA-256 | `3451f840bb79bf776f37bc2b45f2d6fa4e2b9d9201c461099aa1002901a541ef` |
| runtime TAR SHA-256 | `07419ba5d871c704acea4a9f78e8721702df60429d520cd1d8ee1b07e40f9d4b` |
| signed manifest SHA-256 | `05896387ffc8ab29199c65087158afe1207569beb262cd1a026cb76c74f5f8d9` |
| public key SHA-256 | `0a1bb97a92ef6090b9dffd05807c32eb71ad669d8e710a4e0868ab506f504808` |

installed protected template manifest의 모든 sourceFiles를 exact detached source와 대조하고, sourceTree의 정규 JSON 해시와 POM hash를 검증했다. signed runtime archive의 3 entrypoint 내용·root ownership·mode 0755가 manifest와 committed source에 일치했다. 공개 키 Ed25519 signature를 독립 재검증했다.

manager/agent/template compatibility range는 각각 `4.23.0.0` 이상, `4.24.0.0` 미만이다. filename의 prototype/build suffix를 productVersion으로 해석하거나 5-part 버전을 자르지 않았다. signed feature에는 `LOGICAL_RESOURCE_RESERVATION`, `SERVICE_MAINTENANCE`, `RENDERED_CONFIG_GENERATION_HANDLER`가 있으며 production AD 3 keys는 없다.

## Kernel 및 Ganesha 실제 이미지 검증

설치 kernel은 `6.12.95+deb12-amd64`이고 image validator가 kernel·initrd·module과 다음 config를 확인했다: `CONFIG_NVME_TARGET=m`, `CONFIG_NVME_TARGET_TCP=m`, `CONFIG_NVME_TARGET_AUTH=y`, `CONFIG_NVME_AUTH=m`. 새로운 Cloud guest에서의 6.12 cold boot 또는 NVMe 인증 연결을 이 빌드 결과로 주장하지 않는다.

Ganesha는 pinned source `2a57b6d53295426247b200cd100ba0741b12aff9`로 새로 빌드한 `5.5.3`이다. 실제 설치 패키지 SHA-256은 `c166eefc91f617e0e8e0b3ed756ff905895a27c031e65886aa1d3cb2fdbed921`이며 protected build manifest의 package hash 및 모든 runtime binary hash와 일치했다.

committed sandbox self-test의 보호된 결과는 `namedAclReadWrite=true`, `defaultAclInheritance=true`다. root:root 0770 child의 named/default UID 1002 ACL을 실제 NFS에서 읽고 쓰는 시험이 성공했다. 이 결과는 빌드 guest 내부 시험이며 기존 VM 50의 Ganesha 4.3 mixed EACCES 해결 또는 Cloud 외부 실증으로 확대하지 않는다.

실제 `/usr/local/bin/ganesha.nfsd` symlink는 `/opt/ablestack-ganesha/5.5.3/bin/ganesha.nfsd`를 가리킨다. endpoint template와 stock service의 두 drop-in hash/ExecStart가 protected manifest와 정확히 일치하고 stock `nfs-ganesha.service`는 masked다.

## cleanup 및 남은 gate

독립 검사는 `qemu-nbd --read-only`와 root `ro,noload`, boot `ro` mount로 수행했다. 첫 boot 검사에서 ext2에 부적합한 noload 옵션이 거절돼 자기 root mount/NBD를 정상 정리한 뒤, recipe와 같은 readonly boot 옵션으로 검사 harness만 정정했다. 이미지와 committed recipe bytes는 변경하지 않았다.

완료 후 `/dev/nbd7` size 0 / owner PID 없음, 자기 mountpoint 없음, builder QEMU 종료, signing FD closed를 확인했다. 기존 ea392 artifact, 원본 DATA 및 partial `021b`는 변경하지 않았다.

증빙은 `/root/work/epic898-preparation/fc3e-prototype-build-control/`의 `build-result.json`, `final-image-attestation.json`, `prototype-result-summary.json`, `qemu-img-argv.jsonl`, `build.log` 및 resource/console 관측 파일이다.

다음 Cloud 등록·fresh SPARSE validation fixture·strict consumer/readback·all4 외부 I/O·failure recovery·ROOT swap·AD는 parent의 별도 GO와 각 실제 인수 증거가 필요하다. 이 이미지를 최종 완성 이미지로 바꾸거나 relabel하지 않는다.
