<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
 -->

# 볼륨·스냅샷 VM 생성 개발·검증 환경 준비 결과

확인일: 2026-10-09 / 대상 Epic: [#1335](https://github.com/ablecloud-team/ablestack-cloud/issues/1335)

31번 GFS2와 32번 Glue(Ceph) krbd 환경의 개발·검증 기반을 준비했다. 32번은 관리 서버와 호스트 Agent 3대를 Diplo에서 Europa 4.23으로 업그레이드했다. 로그인, API, 관리 UI, 스토리지 기본 동작, 업그레이드 전 자원 보존을 확인했다. 볼륨·스냅샷을 이용한 실제 VM 생성·게스트 부팅 E2E는 아직 실행하지 않았다.

## 1. 환경 구분과 준비 상태

| 항목 | 31번: GFS2 | 32번: Glue(Ceph) krbd |
|---|---|---|
| 관리 서버 | 10.10.31.10 | 10.10.32.10 |
| 호스트 | 10.10.31.1 / .2 / .3 | 10.10.32.1 / .2 / .3 |
| SSH | 실제 확인 포트 22, 관리 ablecloud / 호스트 root | 실제 확인 포트 22, 관리 ablecloud / 호스트 root |
| Cloud 로그인 | admin 인증 성공 | admin 인증 성공 |
| OS / Java | Rocky Linux 9.8 / Java 17 | Rocky Linux 9.8 / Java 17 |
| 관리/Agent | 기존 Europa 4.23 유지 | 4.21 Diplo → 4.23.0.0-1 설치 완료 |
| Cloud Routing 호스트 | 3/3 Up | 3/3 Up, version=4.23.0.0, HA Available |
| 기본 스토리지 | Primary / SharedMountPoint / CLUSTER / Up | Primary / RBD / CLUSTER / Up |
| 실제 데이터 경로 | /mnt/glue-gfs, GFS2 공유 마운트 | rbd 풀, kernel RBD 장치 매핑 |
| 관리 서비스 | mold / mold-usage active, /client/ HTTP 200 | mold / mold-usage active, /client/ HTTP 200 |
| 전용 테스트 네트워크 | E1335-VM-Origin-L2 / VLAN 3135 | E1335-VM-Origin-L2 / VLAN 3235 |
| 네트워크 UUID | ae274fff-3d8e-4285-ac73-26a767c1fea7 | 6400f908-4315-49db-a3e1-1fa58948c303 |
| 네트워크 범위 | Account L2, ConfigDrive, Setup, canusefordeploy=true | Account L2, ConfigDrive, Setup, canusefordeploy=true |
| 기준 OS 이미지 | Linux BIOS 및 Windows UEFI Ready | Linux BIOS Ready, Windows UEFI 신규 등록 후 Ready |

암호, API key, session cookie는 문서와 GitHub 이슈에 포함하지 않았다. 전용 VLAN은 기존 네트워크와 중복되지 않으며 자동 VLAN 할당 범위 밖에 지정했다. 이는 Cloud의 격리 설정과 배포 가능 여부 확인이다. 새 VM의 NIC 연결, 물리 스위치 trunk, 호스트 간 패킷 통신은 실제 테스트 시작 단계에서 확인한다. L2 자체에 인터넷/DHCP/라우터가 제공된다고 가정하지 않는다. 초기 부팅·데이터 확인은 콘솔/QGA를 사용한다.

31번은 여러 테스트가 동시 진행 중이다. 이번 작업에서 기존 VM/볼륨을 변경하지 않았으며, 매 실행 직전에 inventory를 다시 확보해야 한다. 31번과 32번의 현재 패키지 배포 시점은 다르다. 기능 구현 비교 테스트에서는 동일 변경 source SHA의 관련 모듈과 UI를 양쪽에 배포하고 기록해야 한다.

## 2. 32번 Europa 업그레이드와 복구

사용 source는 작업 시작 시점 upstream/ablestack-europa의 `2871963cd43aeb592f619c1b67d8a5974846409d`다. 기존 [GitHub Actions run 37803365591](https://github.com/dhslove/ablestack-cloud/actions/runs/37803365591)의 Rocky 9.8 RPM artifact를 사용했다. 새 전체 Cloud 빌드나 Actions 실행은 요청하지 않았다.

**해당 Actions 전체 결과는 failure다.** RPM 생성 이후 extension 검증이 management 패키지에서 host 전용 network-namespace wrapper를 요구하면서 실패했다. management와 host network-runtime의 합친 payload, source 본문, embedded schema/JAR revision, 필요한 라이브러리, 제한된 Java runtime smoke를 별도로 확인한 뒤 테스트 환경에 적용했다. 이 확인은 전체 CI 통과를 의미하지 않는다.

관리 서버에는 common/management/ui/usage `4.23.0.0-1`을 설치했다. 호스트에는 common/agent `4.23.0.0-1`과 network-runtime `1.0.0-2.el9`를 설치했다. management와 host network-runtime을 같은 서버에 설치할 때 extension 디렉터리 소유자 충돌이 있어 host 전용 패키지는 호스트에만 적용했다. 32번에서는 aspkg/aspm을 사용했다. 누락 dependency는 공식 Rocky 저장소와 GPG 검증을 이용해 설치했으며, 기존 영구 저장소 비활성화 정책은 유지했다.

### Diplo의 선행 스키마 누락 보완

기존 DB는 4.21 Complete로 표시됐지만 표준 4.20.4→4.21 마이그레이션 일부가 없었다. 그대로 기동하면 extension 테이블, VM details 테이블명, OS 분류, configuration scope 타입 오류로 초기화가 실패했다. 원본 `schema-42040to42100.sql`과 `Upgrade42040to42100.java`를 확인하여 다음 선행 항목을 보완하고 Europa 초기화를 다시 실행했다.

- extension 관련 테이블과 template extension 컬럼.
- user_vm_details → vm_instance_details 및 FK 대상 변경. 변경 전후 상세정보 155행을 확인했다.
- Fedora / Rocky Linux / AlmaLinux 분류와 guest_os_category 컬럼.
- 원본의 12개 CREATE TABLE IF NOT EXISTS 블록과 45개 idempotent ADD_COLUMN 호출로 누락된 추가 항목 보완. 이미 존재하는 항목은 재생성하지 않았다.
- configuration.scope의 문자열을 표준 Scope bitmask로 변환. 기존 문자열은 legacy_scope에 보존했다. 기존 숫자 표현도 보존했다.
- 보완 작업에서 root 소유로 생성된 OS helper procedure 3개의 definer를 기존 애플리케이션 DB 계정으로 변경했다. SYSTEM_USER 권한을 애플리케이션에 추가하지 않았다.

최종 DB version은 **4.23.0.0 / Complete**다. 이후 실제 애플리케이션 초기화, admin 로그인, VM/호스트/스토리지/템플릿/스냅샷 조회, HTTP 200을 확인했다. user_vm_view / volume_view / storage_pool_view CHECK도 OK이며, 업그레이드 후 전체 cloud/cloud_usage dump와 gzip 무결성 검사도 성공했다. 이번 보완은 32번 현 DB에 대한 운영 복구이고, 모든 Diplo 설치의 자동 업그레이드가 검증됐다는 뜻은 아니다. 적용 SQL과 진단 로그는 아래 WSL 작업 경로 및 서버 백업 경로에 보존했다.

### 자원·설정·UI 보존 확인

- 관리 서버의 기존 Cloud VM 15개(사용자 VM 13개 + 시스템 VM 2개), 활성 볼륨 17개의 UUID·상태·연결·호스트/스토리지 값이 업그레이드 전과 동일했다. 사용자 VM 상태는 Running 7 / Stopped 6이다.
- 호스트별 실행 중 domain은 5 / 3 / 5로 동일했다. 재부팅이나 VM stop/start 명령을 실행하지 않았다.
- Agent 설정은 RPM 적용 직후 파일 hash가 같았다. Agent 기동 후 파일의 정렬/주석 표현이 변경됐으나 properties key/value 전체 비교는 3대 모두 차이 0이다.
- 기존 krbd pool/image/device 매핑이 유지됐고 Ceph HEALTH_OK를 확인했다.
- 외부 HA 관리가 Agent stop 직후 다시 시작시켜 stop job이 취소됐다. 설치 동안만 runtime mask를 사용한 뒤 제거했으며, 최종 Agent는 active/enabled이고 Cloud HA도 3대 Available이다.
- 활성 webapp의 WEB-INF를 보존했다. static asset 840개가 설치 UI payload와 hash 일치했다. config.json은 보존하고 buildVersion 표기만 Europa/배포 SHA로 갱신했다. 관리 JAR Implementation-Revision은 위 source SHA와 일치한다.
- 기존 시스템 VM 2대는 유지했다. 시스템 VM guest 이미지 자체의 교체/재생성은 이번 업그레이드에 포함하지 않았다. 기존 SSVM을 통한 새 Windows 이미지 다운로드·설치·checksum 검증은 성공했다.

## 3. 스토리지 기본 검증

| 확인 | 결과 | 범위 |
|---|---|---|
| GFS2 공유 파일 | 31.1에서 쓴 테스트 파일을 31.1/.2/.3에서 같은 SHA256으로 읽음 | 공유 마운트·파일 접근 |
| Ceph 상태 | HEALTH_OK, 15/15 OSD, active+clean PG | 기본 상태 |
| krbd 읽기/쓰기 | 전용 64 MiB RBD를 32.2에서 kernel map 후 4 KiB 쓰기/읽기 checksum 일치 | krbd 데이터 접근 |
| RBD snapshot/clone | 전용 snapshot을 protect/clone하고 32.3에서 kernel map 후 동일 데이터 확인 | Ceph 기본 snapshot/clone |
| 정리 | 자체 테스트 GFS2 파일, RBD 원본/clone/snapshot, kernel map 제거 | 기존 이미지/VM 무변경 |

GFS2 probe SHA256: `23ca7295959d60a5de34f36ee108b95b3611042a24c2f12338f1341641392a14`.
RBD probe SHA256: `028593eb9b746961e1c50b7cfa8f1fa49e61a0e5c21789bc58091f145c9dc442`.

이 결과는 Cloud snapshot provider restore, ROOT 편입, 새 VM guest boot의 PASS가 아니다. 해당 결과는 실제 UI 생성 요청과 Cloud/host/guest 증거를 연결하여 별도로 확보한다.

## 4. 테스트 이미지와 실행 자원

| 환경 | Linux BIOS | Windows UEFI LEGACY |
|---|---|---|
| 31 | rocky-9-4-minimal.qcow22 / e3d8677e-70f0-44bd-917e-6068636b97a8 | Windows Server 2022 x86_64 Base Install / 75c45d7c-87e6-496f-bf89-a29c7780ed15 |
| 32 | Rocky-Linux-9-5-x86-64-Server-Cloud-Image.qcow2 / b31d2c92-67b9-40c4-a137-4ff9778c6b0d | E1335-Windows2022-UEFI-base / 67614e8c-51fe-4265-90e3-261e72c26776 |

32번 Windows 기준 이미지는 31번 Base Install을 복사했다. 전송 전후 SHA256과 SSVM 설치 checksum을 확인했고, **Download Complete / isready=true / logical size 100 GiB**다. UEFI=LEGACY, CPU 4, memory 8192, rootdisksize 100, video.hardware=virtio, TPM NONE 메타데이터를 유지했다. SHA256: `2ff53a1cd2c0b1f9f231a9fbd8d6e066f9d0a86c97f8d27840b5d6091ba97072`.

첫 등록 요청의 `sha256:` checksum 형식이 잘못돼 설치가 실패했다. 실패한 자체 template를 API로 정리하고 표준 `{SHA-256}` 형식으로 다시 등록해 성공했다. 임시 다운로드 URL과 staging 복사본도 제거했다(HTTP 404). 원본 template 및 Ready template의 secondary 데이터는 유지했다.

기존 볼륨·스냅샷을 성공 사례의 fixture로 임의 선택하지 않는다. 실제 검증 시작 시 새 seed VM을 만들고 guest에 test case ID와 checksum 파일을 기록한다. snapshot 시점 전후 데이터를 다르게 만들어 복구 시점을 확인한다. volume 사용 원본은 전용 seed의 ROOT만 분리하고 출처/boot profile/스토리지와 무결성을 기록한다. 현재 단계에서 전용 detached ROOT/새 Cloud snapshot fixture 및 성공 VM은 만들지 않았다.

실행 값과 케이스 이름은 [fixture-manifest.json](evidence/fixture-manifest.json)에 고정했다. Linux는 2 CPU/4 GiB, Windows는 4 CPU/8 GiB로 시작하고 각 환경에서 대표 케이스를 순차 실행한다. 31번은 CPU 사용량이 높고 다른 검증이 동시 진행 중이므로 시작 전 용량을 다시 확인한다.

## 5. Epic 설계에 반영할 선행 제약

두 저장소 모두 **CLUSTER** 범위다. 현재 UserVmManagerImpl의 기존 볼륨 생성 경로는 Zone 범위만 허용한다. 따라서 환경 준비 완료와 현재 볼륨 생성 기능의 사용 가능을 동일하게 표시하지 않는다. 스토리지 scope를 DB에서 Zone으로 바꿔 우회하지 않았다.

[#1337](https://github.com/ablecloud-team/ablestack-cloud/issues/1337)을 P0으로 올려 다음을 선행 구현한다.

1. CLUSTER SharedMountPoint(GFS2)/RBD(krbd)의 기존 ROOT 편입 지원.
2. 원본 pool 유지와 원본 cluster 배치 고정. 다른 cluster/접근 불가능한 host 선택은 allocation 전에 거절.
3. Ready·미연결·부팅 출처·소유권·실제 runtime 사용·동시성 재검증. 원본을 복사/이동하거나 일반 DATADISK를 부팅 가능으로 간주하지 않음.
4. UI 적격성 응답과 실제 deploy의 같은 지원 계약, 명확한 거절 사유.

스냅샷은 별도 신규 ROOT 복구 경로이며 Zone-only 제한을 그대로 옮기지 않는다. GFS2와 Ceph의 provider/state/저장 위치, 원본 VM/volume 삭제 여부, 복구 시점 boot metadata를 각각 확인한다. CLVM/CLVM-NG/HOST scope 지원 확대는 지정된 두 환경의 필수 성공 범위에 포함하지 않는다.

필수 대표 성공 행렬은 **2환경 × 2원본(볼륨/스냅샷) × 2OS(Linux BIOS/Windows UEFI) × 2시작 옵션 = 16개**다. 두 환경 모두 성공해야 Epic을 완료한다. 기존 상세 SRC/VOL/SNP/BOOT/CAP/JOB/UI/회귀 케이스는 [validation.ko.md](validation.ko.md)를 따른다.

## 6. 준비 결과와 남은 검증

**완료:** SSH·admin 인증, 기존 환경 inventory, 32 management/Agent 업그레이드와 스키마 복구, UI/runtime/자원 보존, GFS2 공유 접근, Ceph krbd I/O 및 기본 snapshot/clone, Linux/Windows Ready 기준 이미지, 전용 L2 네트워크 설정, 실행 manifest와 증거/백업 보존.

**실행 전 필요:** 동일 기능 변경 SHA의 관련 모듈/UI 배포, 전용 seed/ROOT/Cloud snapshot 생성과 guest checksum baseline, 신규 VLAN의 실제 NIC/패킷 확인, #1337의 CLUSTER 볼륨 지원 구현.

**미실행:** 볼륨/스냅샷 기반 VM 생성·게스트 부팅 16개, Cloud snapshot restore, 실패·동시성·회귀 검증, 새 전체 Cloud 빌드. 전체 CI 통과도 미확인 상태다.

31번 UI에는 기존 VR 2대의 webserver health check 경보가 있고, 32번에는 작업 전부터 존재한 32.3 장치 sensor 경보가 남아 있다. 이를 전체 클러스터 무경보 상태로 보고하지 않는다. 지정된 테스트 기반 점검은 통과했으며, 새 케이스의 baseline에서 영향 여부를 확인한다. 기존 네트워크나 센서 설정을 임의 변경하지 않았다.

## 7. 증거와 백업

- [환경/API 요약](evidence/environment-readiness.json), [RPM 무결성](evidence/environment-packages.json), [스토리지 probe](evidence/environment-storage.json), [실행 manifest](evidence/fixture-manifest.json).
- [CLUSTER 볼륨 시안](evidence/proposed-volume-cluster-dark.jpg), [원본 사용 확인 시안](evidence/proposed-volume-cluster-confirm-dark.jpg), [설계 시안 변경 확인 4개](evidence/environment-mockup-verification.json). 이 4개는 오프라인 시안 검증이며 제품 E2E가 아니다.
- [32번 Europa 호스트 화면](evidence/environment32-europa-hosts.jpg), [32번 호스트 DOM](evidence/environment32-hosts.json), [31번 snapshot 화면](evidence/environment31-snapshot.jpg), [32번 snapshot 화면](evidence/environment32-snapshot.jpg).
- WSL 원본 로그/SQL/artifact: `/home/ablecloud/work/vm-origin-environment-20261009`.
- 32번 관리·호스트 백업: 각 서버 `/root/vm-origin-europa-upgrade-20261009/backup` (상위 디렉터리 mode 700).
- 사전 Cloud/usage dump SHA256: `0b85560ab4fac0d39c94ad4c9219b3c187124a20afce5134805174e86c96ae59`.
- 사전 management config/runtime SHA256: `144e044d282d76e322b48a5cf63d51a1ec79e442fa9bd45c80b6bcede945fb03`.
- 업그레이드 후 Cloud/usage dump SHA256: `7093456250c4eeefbe68b21daaa996602aab2ee418962b7dabb931d0aff0b31e`.

업그레이드 중 정적 UI 파일만 갱신하고 WEB-INF/META-INF를 삭제하지 않았다. 원래 dirty FTCTL 체크아웃을 유지하고 dhslove의 별도 깨끗한 source/문서 작업 디렉터리를 사용했다. Cloud source 변경 시에는 WSL ext4에서 변경 Maven 모듈만 빌드하며, 전체 Cloud 빌드는 별도 명시적 요청이 있을 때 GitHub Actions로 수행한다.
