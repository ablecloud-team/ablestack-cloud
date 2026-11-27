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

# v2k 게스트 설정 결합과 현재 상태 검증

v2k가 원본 의도와 OS 어댑터·첫 부팅 payload를 준비하고, Mold가 실제 VM/볼륨/NIC·실행 호스트와 결합해 결과를 확인한다. 게스트 설정은 `StartCommand` 응답을 기다리는 경로에서 처리하지 않는다. VM을 시작해 Running/host를 반영한 뒤 별도 reconcile 명령을 사용한다.

## Root admin API

| API | 입력 | 결과 |
|---|---|---|
| getMigrationGuestConfigurationCapabilities | storageid, 선택 hostid | 실제 Up/Enabled KVM worker의 버전 1·qga-file-v1·fresh observation 지원 |
| reconcileMigratedGuestConfiguration | id, planhash, operationid | BOUND, PENDING, VERIFIED, FAILED, DRIFT, UNKNOWN과 관측 |

계획이 없는 일반 VM은 새 guest worker를 요구하지 않는다. 계획은 Root admin의 volume import 기반 VM 생성에만 받으며, 일반 VM/metadata 갱신이나 삭제로 변경·소실되지 않게 보호한다. Source/ROOT/OS adapter/helper SHA, #89 controller 계획 hash를 함께 검증한다. Windows DATA 목록과 ROOT source disk ID는 controller 역할과 일치해야 한다.

## 저장과 시작 경계

기존 `vm_instance_details`의 숨김 `migration.guest.config.*` namespace에 plan/binding/evidence를 보관한다. #89 청크·상한·해시 함수를 재사용하며 새 테이블/컬럼은 없다. 정지 VM의 DB 결합에는 볼륨 UUID·풀·경로·형식·슬롯과 NIC UUID/네트워크/MAC을 고정한다. 선택된 실제 Agent가 새 계약을 긍정 응답해야 시작한다.

KVM의 실제 devices/channel을 검사한다. metadata의 동명 문자열로 QGA 채널이 있다고 판단하지 않는다. Running reconcile은 현재 host를 다시 조회하고, domain UUID·#89 실행 XML·실제 serial·NIC·미디어 상태·QEMU 실행 scope를 확인한 뒤 결합을 전달한다. Source/target serial을 같은 값으로 가정하지 않는다.

## 제한된 QGA 전송

새 API에 임의 명령, guest-exec, 사용자 파일 경로를 노출하지 않는다. `guest-info`의 enabled commands를 확인하고 고정된 Linux/Windows 전용 디렉터리의 binding/commit/report만 file RPC로 읽고 쓴다. 파일은 64 KiB, 읽기는 작은 조각으로 제한하며 모든 핸들을 finally에서 닫는다. Windows의 EOF는 다음 0바이트 읽기에서 확인하는 동작도 지원한다. 부분 commit은 같은 payload를 바꾸지 않고 복구한다.

마지막에 소비한 binding hash는 management metadata에 남긴다. 이미 VERIFIED에 사용한 보고서는 새로운 관측으로 인정하지 않고 확인값을 바꾼다. 따라서 같은 QEMU process 안에서 게스트가 재부팅해도 이전 보고서로 새 성공을 만들지 않는다. Pending 확인값은 Agent 재시작 후에도 동일하게 이어갈 수 있다. 보고서는 현재 binding hash·operation/plan/VM/ROOT·boot scope/ID·helper SHA와 필요한 모든 항목이 일치해야 VERIFIED다. 해시는 게스트 root에 대한 보안 attestation이 아니다.

## 실패와 검증

채널 부재/비활성 RPC·상한 초과·과거/불완전 보고서·잘못된 UUID/serial/NIC·계획 변경은 완료로 승격하지 않는다. 현재 host와 guest 채널을 새로 조회하며 source 이미지에 대한 offline 수정을 수행하지 않는다. 실제 게스트 변경과 재시도/보상은 도구 어댑터의 소유 journal로 제한한다. VERIFIED 후 관측은 읽기 전용이다.

[도구 PR #88](https://github.com/ablecloud-team/ablestack-qemu-exec-tools/pull/88)과 [13번 GFS2 실기 증거](https://github.com/dhslove/ablestack-qemu-exec-tools/blob/codex/issue-87-mold-preallocation/docs/validation/issue90-v2k-cluster13-20261127.md)를 함께 검토한다. 전용 CI는 기존 allocation/controller 계약에 guest plan·worker·QGA 전송 검증을 포함한다.

## Rocky Linux 9.7 검증 확장

v2k의 Linux adapter에 NetworkManager Ethernet keyfile을 추가한다. 기존 version 1의 MAC/ifname·ROOT 식별자·operation/plan/helper 해시와 새 report 검증 계약을 재사용하므로 Mold API·DB 스키마·Agent RPC를 추가하지 않는다. source OS의 QGA RPC allow list와 SELinux 접근 권한은 v2k가 반입 전에 복제본에서 준비한다. QGA의 guest-file 기능만 추가하며 전용 디렉터리에 국한한 SELinux CIL 정책으로 Enforcing을 유지한다. Mold가 게스트 명령을 실행하거나 네트워크를 직접 조정하지 않는다.

실기 범위는 13번 GFS2의 Rocky 9.7 IDE/BIOS, SATA/EFI, SCSI/EFI이다. [도구 측 검증 기록](https://github.com/dhslove/ablestack-qemu-exec-tools/blob/codex/issue-87-mold-preallocation/docs/validation/issue90-v2k-rocky-cluster13-20261127.md)에 최초 자동 검증과 재기동 결과, 제한 및 기존 VM 보존을 기록한다. 기존 Ubuntu/Windows 계약과 새 NetworkManager 단위 검증을 함께 유지한다.


## 2026-11-27 n2k source adapter와 13번 GFS2 검증

n2k도 기존 version 1 disk-controller/guest plan, fixed-path binding/commit/report와 actual-domain 검증을 사용한다. 이번 확장에는 신규 Mold API·DB 테이블/컬럼·Agent Java 변경이 없다. 소스 인증·원본 상태 수집·PE device/vDisk identity 연결·오프라인 준비·완료 gate는 도구가 소유한다. Mold는 현재 VM/ROOT/DATA/NIC/XML/host와 신선한 보고서를 검증한다.

Nutanix CE의 Windows Server 2022·Ubuntu 24.04.5·Rocky 9.9 각 IDE/SATA/SCSI 9대(모두 UEFI)가 Running/VERIFIED를 완료했다. 기존 123대의 ID·이름·전원·호스트를 보존했고 최종 132대, 18개 qcow2/falloc/writeback 볼륨과 실제 실행 XML을 확인했다. 다른 호스트 stop/start 뒤 source fixture SHA와 ROOT/DATA fsync I/O를 반복했다.

초기 실행·보완·재개를 구분한다. PC device UUID/PE vDisk UUID 차이와 CD-ROM source-map, 같은 크기 디스크, whole-filesystem DATA, Rocky 활성 profile/cloud-init ifcfg, Windows 최대 절전 NTFS·완전 종료·VirtIO serial·32비트 firstboot 실행 경로를 보완했다. Linux bounded observer의 지연 재조회에는 path와 timer를 사용하며 VERIFIED 뒤 적용은 해제하고 관찰만 재개한다. 반입된 실행 이미지를 guestfs로 다시 열지 않는다.

Windows boot_id는 helper 시작 때 고정한 startup token이다. host RTC/Windows 시간 보정 뒤 Win32 LastBootUpTime의 UTC 문자열과 다를 수 있다. 재기동 검증은 바뀐 token, 현재 actual-domain binding/scope/SHA, 실제 VM·ROOT·DATA 및 관측 파일/I/O를 함께 대조했다. Running 또는 오래된 VERIFIED를 새 성공으로 재사용하지 않았다.

Windows 검증은 basic GPT/NTFS DATA T:/online/RW 및 기존 SAN 정책 범위다. 원본 Windows 정적 IP 자동 복원은 v1 DATA adapter 범위에 포함하지 않으며 이 시험은 새 VirtIO NIC의 DHCP로 접근했다. 공식 Release/전체 오프라인 RPM 설치, 직접 libvirt/RAW/RBD 및 추가 OS/네트워크 조합 자격과 구분한다.

[도구 실기 기록과 자격 범위](https://github.com/dhslove/ablestack-qemu-exec-tools/blob/codex/issue-87-mold-preallocation/docs/validation/issue90-n2k-cluster13-20261127.md) · [기계 판독 증거](https://github.com/dhslove/ablestack-qemu-exec-tools/blob/codex/issue-87-mold-preallocation/docs/validation/issue90-n2k-cluster13-20261127.json)


## Windows IPv4 자동 복원 계약 v2 (#91)

신규 Windows Server 2022(build 20348) 작업의 `--guest-config-policy preserve`는 DATA와 함께 NIC의 정적 IPv4/prefix, 기본 게이트웨이·route metric, 자동/수동 interface metric 정책, DNS 방식·서버 순서를 보존한다. 원본이 DHCP이면 lease 숫자를 고정하지 않고 DHCP 방식을 유지한다. IP의 DHCP 여부와 DNS의 자동/수동 여부는 별개다.

공통 읽기 전용 probe는 VMware Tools Guest Operations 또는 Nutanix의 승인된 WinRM으로 실행한다. source NIC MAC·InterfaceGuid·PnP 정보를 수집하고 원본 VM UUID를 검증한다. VMware Windows의 SMBIOS UUID 표현은 원본 vCenter BIOS UUID와 일치하거나 그 UUID의 알려진 byte-order 표현인 경우만 수용하며 원래 guest 표현도 기록한다.

Nutanix는 OS를 계정명으로 추정하지 않는다. 인벤토리에서 OS를 확인할 수 없는 비대화형 작업에는 `--source-guest-os windows` 또는 `linux`를 지정한다. Wizard는 OS를 선택받고 이후 SSH/WinRM 인증을 수집한다. 미리보기는 probe 실행·비밀번호 FD 소비 없이 명령과 미검증 항목을 출력한다.

계획/helper/report/binding의 v2와 `windows-network-ipv4-v1` 기능을 관리 서버·실제 KVM Agent에서 확인한다. 지원 작업은 현재 Mold GFS2/file-qcow2와 L2 네트워크다. IPAM 네트워크는 원본 IP 예약을 지원하는 어댑터가 검증되기 전까지 사전 차단한다. 기존 v1 Linux·Windows DATA-only 계획은 자동 변환하지 않는다.

대상 첫 부팅 SYSTEM 작업은 실제 Mold NIC UUID/network UUID/MAC과 현재 guest 장치를 결합한다. PnP/link 준비를 기다린 후 대상 NIC만 변경한다. 동일 source GUID/PnP/IP의 non-present 이전 장치만 제한적으로 처리하며 다른 NIC의 IP 점유는 충돌로 중단한다. 전체 NIC 초기화나 전역 TCP/IP reset은 수행하지 않는다.

대상 설정은 WinRM/RDP 연결 없이 적용하고, 기존 QGA 파일 RPC로 관측한다. 원본 OFF 확인 후 같은 IP/MAC의 대상을 시작한다. 수동 IPv6, 팀/브리지/VLAN/VPN, 정책 DNS, 모호한 대응은 전용 검증 없이 평탄화하지 않는다. IPv6의 기본 자동 동작은 유지한다.

`complete=true`는 기존 preallocation/controller/DATA와 필요한 모든 Windows NIC의 주소·gateway·DNS가 실제 보고서에서 VERIFIED일 때만 기록한다. 정적 주소의 DAD Preferred와 지속 설정도 확인한다. 재부팅·호스트 이동 시 새 binding/boot scope로 읽기 전용 관측하며 이후 사용자가 바꾼 IP/DNS를 되돌리지 않는다. `off`와 기존 DATA-only 기록은 Windows 네트워크 복원 성공으로 표시하지 않는다.

반입 이후 계획 hash/helper를 교체하거나 관리 이미지를 오프라인 재개방하지 않는다. v2 canonical JSON은 자동 metric의 명시적인 null을 보존한다. 초기 저장 경로에서 누락된 null은 원래 SHA-256이 정확히 재현되는 경우에만 읽기 시 복원하며 정책이나 저장된 hash를 변경하지 않는다.

이전 NIC 제거가 3010(재부팅 필요)으로 끝나거나 비동기 IP 점유 해제가 지연되면, 종료 코드를 journal에 남기고 최대 1회만 제어된 게스트 재부팅을 수행한다. 재부팅 전에는 새 NIC의 IP를 변경하지 않는다. 새 guest boot와 새 binding challenge를 확인한 뒤 같은 계획으로 재개한다. 게스트 내부 재부팅은 QEMU PID가 같을 수 있으므로 Agent는 결합된 재부팅 요청에만 nonce를 한 번 갱신한다. 그 뒤에도 충돌이 남으면 자동 재부팅을 반복하지 않고 복구 필요로 중단한다.
