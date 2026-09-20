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

# Europa ConfigDrive 라이브 마이그레이션 검증 (#1127)

검증일: 2026-09-18, 테스트 환경: 31번 클러스터.
관리 서버 `10.10.31.10`, KVM 호스트 `10.10.31.1/2/3`.

## 원인 및 수정 범위

기존 코드는 `hdd` CD-ROM을 ConfigDrive로 간주했다. ISO가 없는 VM에도 두 개의 빈 CD-ROM이 생성되므로, 대응하는 `DiskTO`가 없는 상태에서 `getVolumePath(null)`을 호출해 마이그레이션이 실패했다.

- VM별 ConfigDrive 경로와 ISO 타입으로 전송 프로필을 검증한다. 빈 CD-ROM과 일반 ISO는 갱신하지 않는다.
- 런타임 ConfigDrive를 찾을 때 고정 디스크 이름/버스를 가정하지 않는다.
- Secondary Storage는 `configdrive` 디렉터리 자체가 별도 NFS 풀로 마운트될 수 있다. 아래는 실환경에서 확인한 동일 자원의 두 표현이다.

```text
NFS source: 10.10.31.10:/nfs/secondary/configdrive
Host mount: /mnt/006ac919-7755-3e59-9cd8-9b906a9e22e8
ISO file:   /mnt/006ac919-7755-3e59-9cd8-9b906a9e22e8/i-2-8-VM.iso
```

`/mnt` 또는 UUID 모양만으로 ConfigDrive를 판별하지 않는다. libvirt 풀 XML의 source 디렉터리, target 경로, VM별 ISO 파일 이름을 함께 확인한다. NFS 저장 방식은 변경하지 않았다.

미디어 갱신은 원래 XML의 target/bus/address/readonly와 소스 호스트 경로를 유지한다. 목적지 저장소 경로는 마이그레이션 XML에서 검증된 기존 소스 경로만 치환한다. 갱신 실패 시 원래 미디어 복원을 시도하고 실패를 명령 호출자에게 전달한다. 갱신 중 공유 저장소를 정리하거나 언마운트하지 않는다.

관리 서버에서는 빈 ISO 경로의 null 역참조를 제거하고 ConfigDrive의 예약 슬롯을 중복 없이 갱신한다. 다른 ISO가 점유한 슬롯은 덮어쓰지 않는다. 보조 ConfigDrive NIC 추가 후 마이그레이션에서만 ConfigDrive가 갑자기 생성되던 경로도 수정했다. 기존 생성/재생성 규칙과 동일하게 기본 NIC가 ConfigDrive 준비를 담당한다.

새 API 및 DB 스키마 변경은 없다.

## 빌드 및 자동 테스트

WSL ext4 작업 트리에서 Java 17 / Maven 3.9.10으로 변경 모듈 `server`, `plugins/hypervisors/kvm`만 빌드했다. 전체 Cloud 빌드는 실행하지 않았다.

| 테스트 클래스 | 성공 |
| --- | ---: |
| LibvirtComputingResourceTest | 324 |
| LibvirtMigrateCommandWrapperTest | 23 |
| ConfigDriveMediaTest | 7 |
| ConfigDriveNetworkElementTest | 6 |
| ConfigDriveDiskProfileTest | 3 |
| 합계 | 363 |

실행한 모듈의 Checkstyle 통과. 실패/오류/스킵 0건. 신규 테스트는 빈/일반 CD-ROM 구분, NFS 풀 원본 검증, IDE/SATA/SCSI 버스와 주소 보존, 중복 ConfigDrive 거부, 미디어 복원 및 오류 전달, 빈 프로필 슬롯/사용자 ISO 보호, 보조 NIC 준비 생략을 확인한다.

## 실제 클러스터 검증

Cloud 비동기 작업 결과, API 상태/호스트, libvirt CD-ROM XML, QGA 응답을 대조했다. ConfigDrive의 `meta_data.json`과 `user_data`를 실제 ISO에서 읽어 이동 전후 데이터를 확인했다. libvirt가 재할당하는 source `index`는 디스크 식별자로 비교하지 않았다.

| 시나리오 | 결과 |
| --- | --- |
| 기존 실패 VM `CLVM-TEST-VM`: 빈 CD-ROM 2개, ISO 0개 | 최종 수정본에서 31-2 → 31-3 → 31-2 성공 |
| 일반 ISO 1개 | 31-1 → 31-2 성공, ISO 보존 |
| 일반 ISO 2개 | 31-2 → 31-1 성공, 두 ISO 모두 보존 |
| Secondary Storage ConfigDrive 단독 | 성공, 메타데이터/사용자 데이터 보존 |
| Secondary Storage ConfigDrive + 일반 ISO | 왕복 성공; 최종 수정본에서 31-3 포함 재검증 |
| 기존 ConfigDrive VM에 일반 NIC 추가 후 이동 | 성공, ConfigDrive/일반 ISO 보존 |
| ConfigDrive 없는 VM에 보조 ConfigDrive NIC 추가 후 이동 | 최종 수정본 성공, 불필요한 ConfigDrive 생성 없음 |
| Host Cache ConfigDrive 단독 | 31-1 → 31-2 성공 |
| Host Cache ConfigDrive + 일반 ISO | 31-2 → 31-1 성공, 데이터 보존 |

대표 비동기 작업 ID:

- `secondary-configdrive-plus-iso-outbound`: `a7359952-b57b-482f-b3bb-b21ed6a7e323`

- `secondary-configdrive-plus-iso-return`: `ae0f14a3-7e04-43c5-847f-7b7bf94e00f5`

- `ordinary-iso-one`: `3df86154-222b-4218-bebe-2670f45f5af8`

- `ordinary-iso-two`: `ee4ec845-0b56-408f-83c8-2d4178399a44`

- `secondary-configdrive-network-without-media`: `ce4236cb-9f29-4f5f-b9db-fac392a41a50`

- `hostcache-outbound`: `de03577d-9e86-4efa-a5d1-24f39900206a`

- `hostcache-plus-iso-return`: `986a6eb7-70b8-4b09-b6c4-13dc2e8e89be`

- `final-secondary-configdrive-third-host`: `7df4765b-7dc1-4866-90c2-30670f00746a`

- `final-secondary-configdrive-return`: `114f2855-1b16-46db-a392-fec153706b9a`

- `final-original-empty-cdrom`: `be0e3554-415c-4b91-b0cd-cb3e301f1cd1`

- `final-original-placement-restored`: `db1b9d53-7333-4310-ab4f-d2c2270f7b2f`

## 배포와 정리

- 세 호스트의 KVM 모듈 SHA-256: `dcf8b8a4fa7a0ad58ec64e79bbf0ecc9643a552bdf0a209cc1d4f3b2af792380`.
- 관리 서버는 빌드한 서버 모듈의 `ConfigDriveNetworkElement` 클래스 2개만 기존 통합 JAR에 반영했다. 최종 JAR SHA-256: `d35d7395fb578a271d7703ed770617c5784be017b6b3cccec049b8095011ad84`.
- 관리 서버/각 호스트 원본 JAR 백업: `/root/issue-1127-backup/`. 롤백은 해당 서버의 서비스를 중지하고 원본 JAR를 원래 경로에 복원한 뒤 서비스를 재시작한다.
- `mold`, 세 `mold-agent` 서비스 정상, 세 호스트 `Up`, `/client/` HTTP 200 및 `WEB-INF` 보존 확인.
- 실제 실행 도메인이 API의 호스트와 일치하며 중복 실행이 없는 것을 확인했다.
- 테스트 VM 3대, ISO 2개, ConfigDrive 네트워크 1개를 삭제했다. 임시 ISO 제공용 HTTP 프로세스와 웹 정적 파일도 정리했다.
- Host Cache 생성 검증용 Zone 설정은 기존 `false`로 복원했다. 기존 사용자 VM은 원래 호스트 31-2에서 Running이며 ISO 없는 상태를 유지한다.

## 검증 범위와 관찰 사항

- 실환경은 BIOS/IDE 기반이다. SATA/SCSI XML 보존은 단위 테스트로 검증했으며 UEFI VM 실기동/마이그레이션을 실행한 것은 아니다.
- 이 클러스터에 NFS Primary Storage가 없어 해당 저장소 조합의 실환경 검증은 하지 않았다. Secondary NFS와 Host Cache는 실제 검증했다.
- 템플릿 QGA 정책상 `guest-exec`/`guest-file-*`가 차단되어 게스트 내부 파일 실행 검사는 하지 않았다. QGA ping/OS 응답 및 실제 연결 ISO 내용은 확인했다.
- 재기동 직후 호스트가 Connecting인 동안 요청한 초기 작업 1건은 상태 검사에서 거절됐다. Up 확인 후 재실행했다.
- 개발 중 보조 NIC의 프로필/미디어 불일치를 발견해 수정했고 최종본에서 재검증했다.
- 호스트 초기 연결 시 `KVMHostInfo`의 lscpu CPU 속도 조회 NPE가 관찰됐다. 배포 전 01:06/01:07/10:19 로그에도 동일하게 존재하는 별도 현상이며, 본 ConfigDrive 마이그레이션 오류와 구분했다.
