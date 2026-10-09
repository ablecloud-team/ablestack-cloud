# 다음 all4 검증용 단일 source module · native · runtime · template 준비

이 문서는 2026-10-09 읽기 전용 준비 결과다. 아래 최종 source S 는 아직 고정되지 않았다. NVMe 정상 경로, retained ROOT, AD lifecycle · winbind · principal authority 의 필요한 native/backend 핀을 먼저 통합하고 normal 검증 뒤 새 full image 를 빌드한다. 같은 S 의 Java5/UI/native/runtime/새 template 일치와 제한 배포는 필수이며 정식 RPM/SRPM publication/설치는 별도 명시 요청이 없는 packaging 재현 준비다. 기존 fc3e 이미지나 hybrid runtime copy 를 S 로 재라벨하지 않는다. 이 계획으로 신규 QEMU · 디스크 · template 등록 · Cloud 배포를 실행하지 않았다. 별도 승인된 로컬 NVMe 결과는 다른 문서로 관리한다.

현재 후속 소스는 retained manual 7064edbdfd8의 normal 752/118, AD SERVICE 0e699dac326의 normal 780/123 및 원본 암호문 f0dd1962bea의 native 117까지 반영했다. NVMe run7은 실제 로컬 인증·I/O를 통과했다. 아래 표의 713/720 및 run5는 당시 provenance이며 현재 인수 결과로 재사용하지 않는다. 최신 source825 ec40eb3f618b 와 native163 01788c642a2, UI216 4a2a27cfbf4c 및 POSIX244 ca82d7cb9ced 는 추가로 고정됐다. ROOT/SERVICE opaque 권한·inverse 서버 소비자 및 실제 AD·ROOT 인수는 남았다. 최종 S는 이 후속을 포함해 새로 고정한다. 현재 actual MGT 는 ADE707 / old Runtime2 이며 새로운 통합 family 를 실제 배포했다고 표시하지 않는다.

## 현재 source 와 actual 구성

| 영역 | 확정 상태 | 다음 단일 source 빌드의 조건 |
| --- | --- | --- |
| source 713 | 2ffda7548a354269fb86d224968a5bac9a7f1f3a / normal Checkstyle 713 tests · 114 classes · failure/error/skip 0 | backend retained manual phase · compensation 및 full AD semantic 후속은 아직 포함되지 않음 |
| native ROOT | cfe9a15f7fb 의 PRESTOP/AFTERSTOP 신원 · latest7 AEAD 연결은 source 검증 | retained capture/authorize/stage 와 AD authority 후속 핀 필요 |
| native NVMe | 793003af 의 정상 owned cleanup source 57 focused PASS | local run5 첫 apply nonzero, 원인 미확정 · 실제 인증 0; full-entry 검증과 다음 핀 필요 |
| signing helper | d8201f42765 의 실제 sealed memfd · child inheritance · cleanup 15 tests PASS | 단일 source S 에 포함하며 정식 key 제공 여부는 별도 |
| actual MGT | ADE JAR ade51548bcdce95d6f9783458da009432c6f7f0904618b742000176c6f6443e5 / PID 1189913 / source 707 reviewed 197 entries | strict Runtime 전체와 source 713 ROOT 후속은 아직 live 아님 |
| actual Runtime | b38 outer + $1 두 classes exact 유지 | 새 outer + $1 + $RuntimeResourceScope 세 classes 와 interface/consumer 를 같은 검증 출력으로 맞춤 |
| actual hosts | 379 KVM command wrappers 와 0a10 LibvirtStorageAdaptor 두 classes, 3 hosts Up | 새 command/API ABI 및 QemuImg overload 를 실제 host JAR 과 대조 후 제한 배포 |
| fc3e prototype | kernel 6.12 · Ganesha 5.5.3 ACL self-test · SPARSE image build PASS | 과거 producer key 와 초기 handler code 계보이므로 새 producer/AD/retained 코드를 증명하지 못함 |

production fullFour / AD 는 전체 실제 인수 전 false 를 유지한다. handler availability 및 보호된 validation profile 은 production 완료 표기가 아니다. actual ROOT swap · all4 import · Cloud fresh validation VM 은 아직 0 이다. 인간 OOBE / capacity 답변 의존 조건을 바꾸거나 질문을 반복하지 않는다. 최종 UI 1275 는 착수 0 이다.

## source S 고정과 빌드 입력

parent 가 필요한 native/backend 핀을 모두 검토한 뒤 단일 S 를 선정한다. canonical tree 의 동시 WIP 로 빌드하지 않는다. 새 isolated ext4 detached checkout 의 HEAD=S · tracked source byte 일치 · local base/upstream 0 0 · source tree hash 를 확인한다. runtime bundle · template · Java 5 modules · UI 는 같은 S 에서 만든다. 별도로 RPM 을 재현할 경우에도 같은 S 를 사용하되 RPM 실행·publication을 기능 시작 조건으로 강제하지 않는다.

POM 직접 product version 은 현재 4.23.0.0 이고 POM SHA 는 3f7946fe718a90f360ddf9574c14d5b900ab3f3ae2fcfa1a54a918d3485d3f63 이다. protected manifest 의 platformVersion/productVersion 은 POM 에서 얻고, 파일명/build suffix 또는 5-part 값을 trim 해서 추정하지 않는다. final S 에서 POM · writer · validator · builder hash 를 다시 고정한다.

manifest 에 source S, sourceTreeSha256/sourceFiles, POM SHA, kernel/package lock, selected Ganesha service/ACL proof, entrypoint 3개 SHA, signed archive/manifest/signature/public key SHA, compatibility 범위, ABI/schema/features 와 test/profile 범위를 기록한다. actual producer 의 storage.service.data.identity.inspect 등 canonical key 를 검증하고 기존 이미지 registration details 를 임의 보충하지 않는다.

## 재사용 가능한 캐시와 자원

| 입력 | 현재 읽기 전용 확인 | 사용 조건 |
| --- | --- | --- |
| Debian 12.12 ISO | 704,643,072 B / SHA512 c93055182057dd19a334260671c7e10880541b7721ad9c8df87be47e0a11d5bbf85018350ff224ff6a5f6a68320b07e95d539cef9dc020c93966bfaa86d4b2ce | committed recipe checksum 과 정확히 일치 |
| kernel deb | 106,422,608 B / SHA256 5e524b782be67cb0d6a2e7b51816a2ac519e573ef7324b9a63a4124365653ecc | package linux-image-6.12.95+deb12-amd64 / version 6.12.95-1~bpo12+1 일치 |
| Ganesha source | 5.5.3 commit 2a57b6d53295426247b200cd100ba0741b12aff9 / archive SHA 8dbd579e24a4113ffbea16cd9487978002d9834c5d5d1ac87e8f9f8ccf147528 | locked fresh guest build 와 ACL self-test 필요 |
| ntirpc source | bf7fd0259c33ce95f5f2bf22817a8912f7fe5188 / archive SHA 32f129907769c0779555057d46be09d1a6fb30f613cc41d473642cffc7084bdd | Ganesha source lock 과 함께 검증 |
| 과거 Ganesha deb 캐시 | SHA e4b72ff200b2d262b8bb551102710a0d5f536e4f3bad4ba37e0208c645a403c4 | 새 selected-service producer/recipe 를 대체하지 않음 |
| fc3e bz2 | SHA c86973ddd394f1d823d513b1cc832f21b742904c5a09387341e42a394d2cc7b4 | 과거 결과 비교용이며 새 source artifact 로 사용하지 않음 |
| 로컬 자원 | ext4 available 878,115,020,800 B / 약 817.8 GiB, available RAM 약 30.5 GiB / KVM 있음 | 실제 시작 직전 재확인하고 parent UI/Java build 와 동시 사용 조율 |

현재 Packer 1.15.3 · QEMU 9.1.0 · Java 17 · Maven 3.6.3 · Node 20.20.2 를 관측했다. CI 의 Packer 1.9.4 / RPM Node 14.21.3 기본값과 다르므로 같은 artifact hash 를 기대하지 않는다. 정확 tool version 을 build manifest 에 넣고 선택한 toolchain 을 고정한다.

ISO cache 는 installer 다운로드만 줄인다. 현재 kernel/Ganesha recipe 는 guest 안에서 apt와 고정 URL 다운로드를 수행하며 cached deb 를 사용하는 공식 toggle 은 없다. 완전 offline 빌드라고 표시하지 않는다. apt package inventory · repository metadata · download hashes 를 추가로 보존해야 같은 source 재현과 byte-identical 빌드를 구분할 수 있다.

## 정규 빌드 순서와 검증

1. S 의 normal server/KVM/storagevm -am package 및 native/runtime/inline/identity/boot/credential 테스트를 먼저 수행한다. source 713 의 argv 는 root-prestop-target-final-713/root-prestop-target-test-command.json 에 있고 skipTests=false · maven.test.skip=false · normal Checkstyle 를 유지한다. 새 후속 테스트를 합치되 713 PASS 를 새 전체 source PASS 로 확대하지 않는다.
2. Java 출력은 engine/schema, api, server, plugins/hypervisors/kvm, plugins/storage/sharedfs/storagevm 다섯 modules 를 함께 고정한다. source before/after NUL hash, classes/test outputs 및 모든 JAR/entry SHA 를 보존한다.
3. 같은 S 에서 functional UI lint/unit/build 를 수행한다. 이 기능 빌드는 최종 style/theme/button/dialog/keyboard QA 가 아니다. 최종 11탭 QA 경계는 그대로 유지한다.
4. sealed RAM key wrapper → tools/build/build-systemvm-storage-release.sh → tools/appliance/build.sh → fresh ISO Packer → writer/validator 경로를 사용한다. SYSTEMVM_VERSION, BUILD_NUMBER, KEY_ID 를 manifest 에 고정한다. release helper 가 실제 HEAD 를 buildCommit 으로 기록하므로 다른 HEAD 환경에 옛 archive 를 넣지 않는다.
5. KVM create/convert/export 의 실제 argv 가 preallocation=metadata 이상인지 기록한다. qcow2 backing-none · virtual size · L1/L2 allocation · actual allocated bytes · qemu check · compressed roundtrip 을 확인한다. THIN/COW/-c 를 사용하지 않는다. NBD7 미점유와 자기 cleanup 절대 경로를 사용 직전 확인한다.
6. fresh image 의 kernel/auth config · boot entry · module, canonical protected template manifest · POM/source/entrypoint, signed updater/readback, Ganesha 5.5.3 selected service · binary/package/service binding hashes · named/default ACL self-test 를 독립 검사한다. old fc3e 또는 hybrid copy 를 새 이미지로 변환해 대체하지 않는다.
7. 별도 packaging 재현 계획으로 RPM 은 isolated Rocky 9.7 container/rootfs 에서 tools/build/rocky97-rpm-build.sh 의 정상 path 를 사용한다. PACK/BRAND/PACKAGE_VERSION=4.23.0.0/RELEASE/TIMESTAMP 를 고정하고 LOCAL_FAST=false 로 전체 modules 를 포함한다. UI-only RPM 을 full RPM 으로 표시하지 않는다. helper 가 DNF repository/global config 를 수정하므로 canonical WSL 환경에서 바로 실행하지 않는다.
8. 별도 packaging 재현 계획의 RPM spec 은 Maven -DskipTests 로 packaging 하므로 별도 normal 테스트 gate 가 필요하다. RPM/SRPM manifest · NEVRA · dependency list · packaged JAR/entry hashes · public trusted key · generated scripts 를 추출해 앞 단계 출력과 대조한다. 아직 RPM build/install 은 0 이다.

사용자가 승인한 Epic 구현·빌드·테스트 배포 범위에서 사용할 정규 command 형태는 아래와 같다. 필수 소스와 입력이 고정되면 불필요한 재승인 없이 진행한다. S · selector · build number · key ID · release 값은 parent 가 고정한 manifest 에서 가져오고, 아래 명령은 현재 실행하지 않는다.

```bash
mvn -B -pl server,plugins/hypervisors/kvm,plugins/storage/sharedfs/storagevm -am \
  -Dtest="$FROZEN_TEST_SELECTOR" -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false -Dmaven.test.skip=false \
  -DskipITs -Drat.skip=true -Dmaven.javadoc.skip=true package

python3 tools/build/with-storage-runtime-signing-key.py \
  --key-id "$BUILD_KEY_ID" \
  --trusted-key-dir systemvm/debian/etc/ablestack-storage/runtime-trusted-keys \
  --require-stable false -- bash tools/build/build-systemvm-storage-release.sh

PACK=noredist DISTRO=rocky9 PACKAGE_VERSION=4.23.0.0 \
  RELEASE="$BUILD_RPM_RELEASE" BUILD_SRPM=true LOCAL_FAST=false \
  bash tools/build/rocky97-rpm-build.sh
```

첫 command 는 normal test package, 두 번째는 승인된 local test-key build, 세 번째는 자기 isolated Rocky rootfs 안의 full RPM/SRPM path 다. 두 번째 command 의 SYSTEMVM_VERSION / SYSTEMVM_BUILD_NUMBER / STORAGE_RUNTIME_SIGNING_KEY_ID 는 사전에 고정한다. 정식 publication 은 별도 요청 범위에서 require-stable true 및 CI stable key path 를 사용한다. 승인된 13번 기능 시험은 require-stable false 와 정확 public trust 를 쓰며 stable key provisioning/정식 RPM publication 이 기능 전체의 hard blocker 는 아니다.

현재 CI 는 캐시 ISO 를 file URL 로 바꾸면서 recipe bytes 를 수정한다. buildCommit 하나만으로 이 transport delta 를 숨기지 않고 원래 recipe hash 와 파생 recipe hash · ISO checksum 을 기록해야 한다. 로컬에서는 이전 fc3e 처럼 committed URL 과 Packer verified cache 를 사용해 recipe 를 바꾸지 않는 경로가 있다. template sourceTreeSha256 가 동일하다고 추정하지 않는다.

RPM noredist helper 의 cloudstack-nonoss clone 은 현재 floating HEAD 이고 Node archive 는 URL 다운로드다. 선택한 nonoss commit/아카이브와 Node SHA 를 고정하거나 OSS 범위를 명시해야 dependency 재현이 가능하다. CI actions major tag, Maven/NPM cache 는 lock/package artifact hash 로 추적한다. 이러한 CI 입력 부족을 기능 완료나 artifact byte 재현 PASS 로 표시하지 않는다.

## 배포 ABI · schema · producer/consumer gate

새 Runtime manager interface 의 freshSignedRuntimeValidationProof · signedSupportedFeatures · default legacy reject, StorageService managed begin/verify/finish · required feature/package guard · scoped fixture hook 을 caller/implementation 같은 출력으로 맞춘다. old Runtime 2 classes 만 유지한 실제 구성에서 새 profile/strict consumer 를 활성화하지 않는다.

API StorageServiceHostCommand allowlist/DTO, SharedFS lifecycle explicit-template overload/CreateSharedFS params, TemplateUpgradeRequest artifact references, Manager/helpers/provider Spring XML 을 함께 검증한다. agents 는 API command classes + KVM wrappers + existing QemuImg overload ABI 를 실제 JAR 과 대조한다. 새 family 에 required class 가 빠지거나 다른 dependency 를 끌어오면 부분 반영하지 않는다.

operation control/policy 의 기존 nullable column/table 은 이미 적용됐고 source 707/713 에 새 DDL 은 없다. 새 S 의 migration diff 를 다시 검토한다. 최초 deployment 에서는 global/instance policy OFF · 기존 recovery rows · 원본 DATA/VM/guest protocol 보존을 확인한다. 전체 새 package/runtime 배포는 부모 에이전트가 ABI·입력 검증 후 조율하며 현재 13번 source 713/strict family 적용은 0 이다.

protected NEW SPARSE validation artifact 는 exact instance/name/ROOT/DATA/current signed pin/expected CLI/source S 와 original exclusion 을 고정한다. private USER template 의 zone/KVM/arch/Ready/owner/launch 권한, SPARSE ROOT+DATA offerings · actual metadata cache · unique NIC/IP · capacity 를 fresh로 검증한 뒤 사용자 승인 범위의 테스트 등록·생성을 진행한다. SYSTEM 전역 선택은 바꾸지 않는다. helper/default false 및 platform proof 부족을 waiver 로 우회하지 않는다.

## 서명 권한과 최종 acceptance 경계

local 검증은 test key 를 sealed memfd 로 생성·상속하고 공개 PEM 만 보존한다. d820 보장은 실제 descriptor seal/mode/owner/close/입력 buffer wipe 이며 모든 Python 불변 문자열의 영구 zeroize 보장은 아니다.

정식 workflow secret 은 storage_runtime_signing_private_key 이다. repo 목록에서 storage/runtime key 는 없었고 org Actions API 는 403 이므로 org key 부재를 단정하지 않는다. 실제 stable key provisioning/use 는 0 이다. published release 는 require_stable_runtime_signing_key=true 와 해당 공개 trust lineage 확인이 필요하며 ephemeral test key 를 정식 key 로 재라벨하지 않는다.

required native 핀과 새 full image 이후 실제 all4 · fault/restart · multi NEW · ROOT/retained · AD/Windows/API/UI 인수가 남는다. 한 source 의 빌드 성공이 전체 완료는 아니다. 모든 다른 gate 완료 후 최종 UI 1275 착수 직전 전체 작업을 중단하고 사용자에게 보고·추가 지시를 기다린다.

읽기 전용 입력 proof: /root/work/epic898-preparation/acceptance-audit/20261009-final-single-source-build-readonly-inputs.json. source 713/actual ADE/host 0a10 provenance 와 cache 결과를 각 시점대로 유지한다.

## 기능 인수와 분리할 항목

이슈 #924 원문의 기능 완료 조건은 RootAdmin catalog 등록·검증·게시·중지·폐기, 실제 적용·호환·권한 및 API/UI 정합성이다. sealed RAM 시험 키와 정확 public trust 로 이 시험을 진행할 수 있다. org Actions API 403, stable key 실제 사용 0, full RPM/SRPM build/install/publication 미수행은 각 별도 사실이며 기능 완료 gate 를 임의 확대하지 않는다. unrelated CI #926 는 계속 분리한다. #1275 최종 11 탭 표준 QA 는 모든 기능 인수 뒤 사용자 경계에서만 착수한다.
