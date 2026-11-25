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

# #1586 KVM RBD 삭제 판정·진단 보강 완료 검증

2026-11-13 KST. 독립 자원 수명주기 결함 [#1586](https://github.com/ablecloud-team/ablestack-cloud/issues/1586) 범위다. 종료된 네트워크 Epic #1554/#1569와 분리한다.

## 결과

31 GFS2 정상 삭제 1건, 32 Glue RBD 정상 삭제 1건과 watcher 실패 → 정상 해제 → UI 일반 재시도 **3회**를 실제 클러스터에서 통과했다. UI 성공 표시는 API job 결과, 직접 물리 확인, Cloud DB VM/volume/network/NIC removed 상태와 대조했다. 새 시험 네트워크 5개·라우터 5개·ROOT 볼륨 5개·복제 오퍼링 2개는 정상 제품 경로로 정리했다. **기존 확인용 자원은 모두 유지**했다.

libvirt native 오류 변환은 [별도 후속 #1648](https://github.com/ablecloud-team/ablestack-cloud/issues/1648)로 분리했다. libvirt/RBD/QEMU 패키지·native 바이너리는 교체하지 않았다. 현재 설치 libvirt가 watcher busy를 ENOENT로 표현하는 문제는 남아 있다. 이번 KVM 변경은 그 문구를 성공 근거로 사용하지 않고, 실제 이미지가 남으면 실패와 진단을 유지한다.

## 구현

- `RbdDeletionProbe`: libvirt pool XML과 KVM pool의 mon/pool/auth/namespace 대조 후 같은 연결 설정으로 FSID·pool ID·이미지 상태를 읽는다. `PRESENT`, `ABSENT_CONFIRMED`, `UNKNOWN`으로 구분한다. 이미지 open의 실제 ENOENT만 부재로 인정한다. 인증·연결·pool·stat·snapshot 조회·정리 실패는 UNKNOWN이다.
- `LibvirtStorageAdaptor`: 삭제 전후 실제 이미지 상태와 FSID/pool ID를 대조한다. 물리 부재만 성공, 존재/불확실은 실패다. 이미지 존재를 확인한 뒤 libvirt의 명시적 NO_STORAGE_VOL에만 metadata refresh 1회와 lookup 재시도를 허용한다. RBD 자동 unmap 우회는 제거했다. 기존 snapshot 준비 계약은 유지하며 probe/준비 native 핸들을 닫은 뒤 libvirt 삭제를 호출한다.
- `KVMStorageProcessor`: RBD에서 기존 포괄적 physical lookup 예외를 이미 삭제된 것으로 오판정하지 않게 우회 경로를 제거하고, 삭제 false 반환을 실패 Answer로 유지한다. non-RBD 경로는 유지한다.
- 진단에 reason/state/stage/FSID/pool ID/기본 namespace/snapshot/image prefix, libvirt code와 **libvirtReportedErrno**를 기록한다. native 문구를 실제 librbd 삭제 errno로 단정하지 않는다. 비밀키·원문 설정/XML은 새 진단에 포함하지 않는다.

API 계약·UI 소스/배치 변경은 없다. 현재 VM 표준을 사용하는 기존 삭제 대화상자의 실패 유지와 선택 재시도 경로로 검증했다. 새 메시지는 agent Answer/관리 서버 로그에 있으며 네트워크 UI/API 530은 기존 상위 네트워크 정리 메시지를 유지한다. 이번 시험에서 프로젝트 왕복·모바일·다크모드 전체 행렬을 새로 수행했다고 주장하지 않는다.

## 실환경 검증

| 클러스터 | 시험 | 라우터 / 실제 배치 | 실행 경로 | 결과 |
|---|---|---|---|---|
| 31 | normal | r-1054-VM / 10.10.31.2 | 정상 삭제 | PASS |
| 32 | normal | r-529-VM / 10.10.32.1 | 정상 삭제 | PASS |
| 32 | watch1 | r-530-VM / 10.10.32.3 | watcher 실패 530 → 정상 해제 → UI 재시도 | PASS |
| 32 | watch2 | r-531-VM / 10.10.32.3 | watcher 실패 530 → 정상 해제 → UI 재시도 | PASS |
| 32 | watch3 | r-532-VM / 10.10.32.3 | watcher 실패 530 → 정상 해제 → UI 재시도 | PASS |

watcher는 시험 ROOT 이미지 header에만 `rados watch`로 건 읽기 전용 참조다. 디스크 내용 쓰기·커널 매핑·강제 unmap 없이 실행했다. UI 삭제가 QEMU를 종료해도 watcher 1개가 남아 실제 이미지가 존재하는 상황을 만들었다. 각 실패의 agent 진단은 `IMAGE_PRESENT_DELETE_FAILED`, `state=PRESENT`, snapshot 0, `libvirtReportedErrno=2`였다. UI/API는 530, VM Expunging/volume Destroy 및 removed null을 유지했다. 그 후 watcher 입력 종료로 **정상 unwatch exit 0 / 실제 watchers 0**을 확인하고 UI의 `실패·미실행 항목만 다시 확인·실행`을 눌렀다. 모두 새 jobstatus 1, 직접 `rbd info` exit 2, domain/volume 부재, DB removed까지 일치했다.

제품 경로 외 `rbd rm`, pool refresh, DB UPDATE/DELETE, 강제 정리를 사용하지 않았다. 제품 자체가 NO_STORAGE_VOL lookup 분기에서 수행할 수 있는 1회 metadata refresh와 운영자의 수동 우회는 구분한다. 이번 실제 반복에서는 이미지가 libvirt에 존재한 상태에서 삭제를 수행했다.

| 시험 | watcher 실패 job (status 2/code 530) | 정상/재시도 성공 job (status 1) |
|---|---|---|
| 31/normal | — | 9b6a413b-129b-4d71-add7-0b3dd6f00130 |
| 32/normal | — | ce6e5108-b0fc-4b48-a14b-651de1d8b7dd |
| 32/watch1 | 6183bfaa-c9f6-4e95-8434-1371f993898d | 28a7da8c-35b8-4fe3-a57a-0a57fb999156 |
| 32/watch2 | 5f5d30c9-47cf-48c4-83c6-fdd28204985b | b41597e3-21ae-46df-ac71-224cd73bf6df |
| 32/watch3 | 1cbf001e-207b-4c0b-9ff4-e5a5044bcfd4 | 17728f08-12d2-49df-91e9-7049fed0626a |

## 빌드·배포

- 기준 Europa `0edd20dcc9218312b3da533582f03e11535c5eb8`, 검증된 생산 소스 `dc231e2a84d1ba35d7ca45063408ad6253a2accb`.
- 별도 브랜치 `dhslove/issue-1586-rbd-delete-diagnostics`. Windows 별도 worktree와 WSL ext4 `/home/ablecloud/work/dhslove/cloud-issue-1586`에서 관리했다.
- 로컬 변경 Maven 모듈 `plugins/hypervisors/kvm`만 compile/test/package. **121 tests, failures/errors/skipped 0**. 새 판정/처리 계약 36개와 기존 storage/RBD 회귀 85개. Full Cloud/GitHub Actions 빌드는 수행하지 않았다.
- 변경 Java 5개 focused Checkstyle 0건. 전체 KVM 기본 Checkstyle에는 기준 커밋부터 존재한 다른 파일 `LibvirtStorageServiceHostCommandWrapperTest.java`의 unused Answer/import wildcard **2건**이 있어 module test/package에 `-Dcheckstyle.skip=true`를 사용했다. 해당 파일이 기준과 byte 동일함을 대조했다. [상세와 로그 요약](evidence/build-style-baseline.json). 전체 모듈 style 성공으로 보고하지 않는다.
- 빌드 모듈 1926064 bytes / SHA256 `704685b05f39fa31fdc34631e832d1d9dcdbdff549d0806fbd74409425e96234`.
- 기존 테스트 배포의 검증된 다른 클래스 보존을 위해 빌드 모듈에서 **생산 클래스 5개**를 추출해 각 host KVM JAR에 overlay했다. `LibvirtStorageAdaptor`, `KVMStorageProcessor`, `RbdDeletionProbe`, `RbdDeletionProbe$Result`, `RbdDeletionProbe$State`. 나머지 **555 entry의 내용 hash 일치**, 새 5개는 빌드 산출물 hash 일치. 따라서 배포 JAR hash와 전체 새 모듈 hash는 서로 다르다.
- 6개 host에서 원본 JAR을 `/var/tmp/qa-i1586-dc231e2a84d1/original-kvm.jar`와 WSL ext4의 `/home/ablecloud/work/issue-1586-impl-20261113/backups/<host>/original-kvm.jar`에 보존했다. atomically 교체 후 `mold-agent`만 순차 재시작, API host Up/Enabled 확인. 관리 서버/UI webapp 파일과 native 패키지는 변경하지 않았다.

| 클러스터 | 호스트 | 배포 KVM JAR SHA256 | 종료 시 원본 QEMU PID 일치 수 |
|---|---|---|---|
| 31 | 10.10.31.1 | 5e6464393c237cf6efe71578f3d3a56d1fbed9a725bac4e218c74271d61ea8e4 | 46 |
| 31 | 10.10.31.2 | 8e803d78e0d78299326f4112c0495423cb462440d9275d11653b7110a0c1aef5 | 41 |
| 31 | 10.10.31.3 | 0dd72227766893b789ab5c89b07cf74bebaacc68a20ebe2b2100dd0aebe130fc | 53 |
| 32 | 10.10.32.1 | db478ce8a9a4297d3a91451e0cd5fe1c98c7ccab1556c7abf2bdca091d90ad3c | 17 |
| 32 | 10.10.32.2 | 6e4d34bc7eee884225bfcc2a0d25576a235dfae2049b341f53c13b03ec5faa13 | 30 |
| 32 | 10.10.32.3 | d6421cfb64f1b8654370f49d9125f9715cbf0f11ef7c106aeefd86dcb29c8baf | 14 |

각 배포 receipt와 종료 검증에 agent PID, 변경/비변경 클래스, native backend 불변을 기록했다. 31-2 배포 전에 이번 폐기용 `r-1054-VM`이 추가되어 원본 PID 불변 + 시험 라우터 1개 추가를 확인한 새 배포 직전 기준을 사용했다. 시험 종료 후 전체 원본 QEMU PID set으로 복귀했다. 실제 삭제 런타임은 31-2(GFS2), 32-1(RBD 정상), 32-3(RBD watcher 3회)에서 확인했다. 나머지 호스트는 배포/서비스/클래스/기존 VM 무영향 검증이며 동일 삭제 시나리오를 모두 수행한 것은 아니다.

## 기존 자원 보존

전체 원본 목록과 ID set, VM 상태·host·NIC(network/IP/MAC/default), network/VPC 상태·project/offering을 전후 대조했다. 31 VM **149**, network **44**, VPC **5**; 32 VM **72**, network **28**, VPC **4**가 동일하다. Public IP 상태별 개수도 원복했다. 기존 QEMU PID set, libvirt 계열 서비스 PID/상태, FTCTL 타이머 및 native RBD backend SHA가 동일하다. 기존 dirty FT 작업 tree도 HEAD/diff/file hash가 모두 동일하다.

- [31 종료 검증](evidence/31-final-verification.json) · [32 종료 검증](evidence/32-final-verification.json)
- [31 Public IP 원복](evidence/31-public-ip-restored.json) · [32 Public IP 원복](evidence/32-public-ip-restored.json)
- [기존 FT 작업 tree 보존](evidence/protected-worktree-verification.json)
- [32 기존 확인용 네트워크 화면](screenshots/32-retained-network.jpg)

## 캡처

캡처는 실제 Chrome UI 원본 JPEG이며 가공/합성하지 않았다. UI evidence JSON에 URL·시각·대화상자 텍스트와 실제 job을 연결했다. 실패 화면의 라우터 Running은 실행 전 dependency snapshot이다. 실패 이후 실제 domain 부재/DB Expunging은 해당 `failed.json`의 직접 확인 결과를 기준으로 한다.

| 캡처 | 설명 |
|---|---|
| [31 정상](screenshots/31-normal-success.jpg) | GFS2 일반 삭제 확인 완료 |
| [32 정상](screenshots/32-normal-success.jpg) | Glue RBD 일반 삭제 확인 완료 |
| [실패 1](screenshots/32-watch1-failure.jpg) / [재시도 1](screenshots/32-watch1-retry-success.jpg) | watcher 유지 530 → 정상 해제 → 성공 |
| [실패 2](screenshots/32-watch2-failure.jpg) / [재시도 2](screenshots/32-watch2-retry-success.jpg) | 동일 반복 |
| [실패 3](screenshots/32-watch3-failure.jpg) / [재시도 3](screenshots/32-watch3-retry-success.jpg) | 동일 반복 |

## 범위와 후속

원래 #1586의 과거 watcher 해제 후 지속 실패는 이번 수정 전 재검토에서 그대로 재현되지 않았다. 당시 수동 정리 이력을 성공으로 바꾸지 않는다. 재검토에서는 watcher를 정상 해제한 뒤 기존 코드의 일반 재시도도 성공했다. 따라서 이번 변경을 과거 미확정 native 결함의 해결로 주장하지 않는다. KVM 성공 오판정 방지/진단/원인 해소 후 일반 재시도를 구현·회귀한 범위로 #1586을 완료하고, native 잘못된 오류 표현은 #1648에서 계속 추적한다.

실제 watcher·정상 삭제와 별도로 권한/연결/잘못된 pool/namespace/부재·멱등/캐시 lookup·snapshot child·핸들 정리·non-RBD는 focused unit으로 검증했다. 운영 공유 풀의 인증·연결 장애나 snapshot child 파괴를 실환경에 주입하지 않았다. LB 부하분산, 신규 UI/전체 네트워크 회귀, native package 빌드는 범위에 없다.

후속 순서는 1) 별도 PR 검토·병합, 2) #1648의 libvirt downstream 소스/패키지 빌드 경로 확인, 3) native 실제 반환 코드 보존 수정·별도 배포/회귀다. 이번 PR은 자동 병합하지 않는다.
