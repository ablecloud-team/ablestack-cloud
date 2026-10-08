# fc3e fresh SPARSE SystemVM validation prototype 로컬 빌드 계획

2026-10-09 로컬 준비 기록이다. 부모의 실제 로컬 build GO 전이며 이미지 생성·변환·QEMU build guest·서명 키 생성·클러스터 등록/VM 생성/배포는 아직 0이다. 최종 UI #1275 착수도 0이다.

## 고정 입력과 격리

- Exact committed source: fc3e7f5c134828288ac89dc98300533ffcb909ff. 현재 canonical working tree의 WIP는 빌드에 섞지 않는다.
- 예정 source 작업 디렉터리: /root/work/epic898-preparation/systemvm-fc3e-prototype-source. 기존 ea392 source/image와 canonical tree를 변경하지 않는다.
- GO 후 로컬 shared clone을 새 owned directory로 만들고 checkout --detach fc3e 를 수행한다. Git branch/commit/push/rebase는 하지 않는다. 빌드 시작 직전 rev-parse HEAD와 tracked source clean 상태를 검사한다.
- git archive만 풀고 latest HEAD 환경값을 붙이는 방식은 쓰지 않는다. build-systemvm-storage-release.sh가 git rev-parse HEAD를 강제로 기록하므로 exact detached clone이 필요하다.
- 외부 sealed wrapper와 공개 prototype trust key만 추가한다. 공개 key directory는 template sourceTreeSha256 계산에서 제외된다. Build recipe/script/lock/POM은 committed bytes 그대로 유지한다.

## 실행 entry와 환경

정규 순서는 build-systemvm-storage-release.sh → tools/appliance/build.sh → shar_cloud_scripts.sh → write_storage_template_manifest.py → Packer template-base_x86_64-target_x86_64.json → guest provision/kernel/Ganesha → KVM export/validator/compression → signed runtime bundle이다.

| 변수 | 예정 값 |
| --- | --- |
| SYSTEMVM_VERSION | 4.23.0.0-fc3e-prototype |
| SYSTEMVM_BUILD_NUMBER | 20261009 |
| STORAGE_RUNTIME_SIGNING_KEY_ID | epic898-fc3e-prototype |
| STORAGE_RUNTIME_SIGNING_PRIVATE_KEY_FILE | /proc/self/fd/N, sealed RAM-only |
| PACKER_CACHE_DIR | /root/.cache/packer |
| SYSTEMVM_VALIDATE_NBD_DEVICE | 사전 확인한 unused /dev/nbd7 |
| Builder guest | 2 CPU / RAM 2048 MiB / disk 5000M / KVM |

build.sh prepare는 dist와 image glob를 삭제하므로 새 격리 source directory에서만 실행한다. 실제 경로가 위 owned root 안에 있는지 확인한다. shell debug에서 private key 원문을 출력하지 않는다. 서명은 prototype test key이며 production key로 표시하지 않는다.

## SPARSE 이상 조건

Committed Packer recipe는 qemu_img_args.create와 convert에 preallocation=metadata를 모두 설정한다. 최종 KVM export도 compat=0.10,preallocation=metadata이며 -c 압축을 사용하지 않는다. bzip2는 완성 artifact 바깥의 파일 압축이다. 현재 main은 KVM export만 실행하며 RAW/VMDK/VHD 함수는 호출되지 않는다.

새 디스크마다 실제 qemu create/convert argv와 qemu-img info/check, virtual size·actual allocation·QCOW2 L1/L2 metadata를 기록한다. 최종 image는 backing-none이어야 한다. THIN/off/COW backing 새 디스크는 만들지 않는다. 후속 isolated cold boot가 필요하면 새 full SPARSE metadata copy를 쓰고 기존 image의 thin COW overlay를 만들지 않는다. 과거 kernel-auth-builder-base-ea392.qcow2 및 prototype image를 fc3e로 재라벨하거나 직접 수정하지 않는다.

## 읽기 전용으로 확인한 cache와 도구

| 입력/도구 | 확인 결과 |
| --- | --- |
| WSL ext4 | 총 약 1007 GiB, available 824 GiB |
| Host RAM | total 31 GiB, available 약 30 GiB, swap 8 GiB 미사용 |
| KVM | /dev/kvm 존재, RW |
| Packer | 1.15.3, qemu plugin 1.1.4 설치 |
| QEMU | qemu-img 9.1.0, qemu-system-x86_64 및 qemu-nbd 설치 |
| NBD | 현재 device/active pid 없음. WSL kernel 6.18.40.1의 nbd module metadata 존재 |
| Sealed memfd | nonsecret readiness bytes로 F_SEAL_WRITE/GROW/SHRINK/SEAL 작동 확인 |
| Debian ISO | /root/.cache/packer/90348009e37455fd6ad8963e5f2f1130b4fc8bf5.iso, 704643072 B |
| ISO SHA512 | c93055182057dd19a334260671c7e10880541b7721ad9c8df87be47e0a11d5bbf85018350ff224ff6a5f6a68320b07e95d539cef9dc020c93966bfaa86d4b2ce, recipe와 일치 |
| Kernel cache | linux-image-6.12.95.deb, 약 102 MiB, SHA256 5e524b782be67cb0d6a2e7b51816a2ac519e573ef7324b9a63a4124365653ecc, lock와 일치 |
| Ganesha cache | 기존 source553/build553/5.5.3 .deb·manifest 존재. 캐시 .deb SHA e4b72ff2...는 exact fc3e 새 package lineage를 대신하지 않음 |

Kernel lock은 6.12.95+deb12-amd64 / package 6.12.95-1~bpo12+1 및 NVME_TARGET/TCP/AUTH 설정을 요구한다. Ganesha lock은 5.5.3 source commit 2a57b6d... / ntirpc bf7fd025...이며 source archive SHA와 ENABLE_VFS_POSIX_ACL=ON, DEBUG_ACL=OFF, DBUS=ON을 고정한다.

ISO cache는 정상 Packer 경로로 재사용한다. Kernel와 Ganesha committed guest recipe에는 공식 local cache toggle이 없으므로 현재 계획은 guest가 locked URL에서 다시 다운로드·hash 검사·빌드하는 정규 경로다. 캐시를 주입하려고 recipe URL/bytes를 바꾸지 않는다. Apt 및 codeload network 접근이 필요하다. 현재 캐시 .deb는 참고·장애 분석용이며 selected serviceBindings와 실제 ACL self-test를 새 빌드로 확인한다.

## Sealed signing 방식

Canonical with-storage-runtime-signing-key.py는 memfd를 쓰지만 seal하지 않는다. 그래서 외부 Python wrapper가 prototype Ed25519 private key를 RAM에서 생성하고 MFD_CLOEXEC|MFD_ALLOW_SEALING, mode 0600, F_SEAL_WRITE/GROW/SHRINK/SEAL을 적용한다. F_GET_SEALS가 mask와 같은지 검사한다.

Wrapper는 PUBLIC PEM만 격리 source의 runtime-trusted-keys에 쓰고, private key는 /proc/self/fd/N 및 pass_fds=(N,)으로 직접 build-release에 전달한다. Private key 원문·환경 원문·파일·로그·argv 값으로 저장하지 않는다. 빌드 종료/실패 finally에서 FD를 닫고 RAM 참조를 해제한다. 실제 signing key 생성은 GO 후에만 수행한다.

## Manifest와 검증 게이트

- Source POM direct version은 4.23.0.0이며 SHA256 3f7946fe718a90f360ddf9574c14d5b900ab3f3ae2fcfa1a54a918d3485d3f63 이다. Guest protected template-manifest의 platformVersion/productVersion은 이 값과 sourcePomSha를 기록한다. Filename suffix나 five-part release를 잘라 product version으로 추정하지 않는다.
- Runtime manifest buildCommit은 exact fc3e, CLI SHA는 3451f840bb79bf776f37bc2b45f2d6fa4e2b9d9201c461099aa1002901a541ef 이다. Boot reconcile/monitor/updater 및 sourceTree hashes를 readiness JSON과 비교한다.
- Runtime signature/public key verify와 SHA256SUMS, qcow2 check, 압축 roundtrip cmp/check, image readback validator를 정상 entry의 결과로 기록한다. 등록 이전 image/manifest/runtime archive의 hash·size·source linkage를 하나의 proof로 묶는다.
- Validator가 NBD를 disconnect/connect하므로 GO 직전 지정 /dev/nbd7이 다른 작업에 사용되지 않는지 확인한다. 연결 대상은 자기 새 prototype image뿐이며 자기 mount/NBD/QEMU pid만 cleanup한다.
- Guest build에서 Ganesha actual named ACL RW 및 default inheritance self-test를 수행하고 protected ganesha-build-manifest의 source/ntirpc/flags/files/package SHA/serviceBindings/selfTest를 확인한다. Kernel config와 boot 가능성도 별도 evidence로 확인한다.
- Source fc3e의 handler availability 및 ROOT identity/POSIX/SERVICE 지원과 fullFourProtocolActivationSupported=false, adIdentity=false를 구분한다. 최종 완성/AD/fullFour 이미지로 표시하지 않는다.

## 자원량과 종료 경계

Local budget은 새 source/output/roundtrip 및 guest build를 합해 최소 32 GiB 여유를 확보한다. 현재 824 GiB 여유로 충족한다. Guest recipe RAM 2 GiB/CPU 2, Ganesha -j2를 그대로 사용한다. Fresh network build 시간은 보장하지 않으며 SSH timeout 120분 등 recipe deadline을 따른다. 블로킹 tool wait는 60초 이하로 나누고 로그의 의미 있는 phase 변경을 보고한다.

실패 시 자기 local build process와 owned NBD/mount만 정리하고 실패 로그·원인·partial artifact를 보존한다. Cloud API 등록/VM 생성/13번 host 배포·재시작·DATA 변경은 build GO에도 포함하지 않으며 별도 parent GO가 필요하다. 원본/partial/Client47 및 최종 UI는 변경하지 않는다.

준비 proof는 acceptance-audit/fc3e-prototype-build-readiness-source.json에 source file hashes·sealed 지원 및 artifactBuildExecuted=false로 저장했다. 이 계획 단계에서 image·private key·local build VM은 생성하지 않았다.
