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

# #1084 구현·배포·검증 기록

검증일: 2026-09-15. 기준 Europa: `39f798757d5109e6717386680a75cb497b20a91b`.
실물 후속 검증: [#1085](https://github.com/ablecloud-team/ablestack-cloud/issues/1085).

## 구현

- Deploy API의 선택적 `additionalisoids`에 비부팅 ISO 1개를 전달한다. 기존 `templateid`가 설치 ISO다.
- 추가 ISO가 없으면 기존 생성 분기를 유지한다. 추가 ISO가 있으면 KVM, 부팅 가능 설치 ISO, 비부팅 추가 ISO, 호출자/소유자 권한과 Zone 연결, 중복 및 부팅 설정을 검증한다.
- VM 생성 트랜잭션에서 주 ISO 슬롯 3과 추가 ISO 슬롯 4의 연결 의도를 저장한다. 검증된 내부 객체를 요청 범위 CallContext에 전달하고 finally에서 복원한다. 사용자 VM details에서 ISO ID를 역으로 신뢰하지 않는다.
- startvm=false는 연결 의도를 저장하고 정지 상태로 반환한다. 실제 ISO 파일 준비/장치 생성은 시작 시 수행한다.
- 실제 배치 호스트의 클러스터 설정, capability, ConfigDrive 한도를 시작 전에 검증한다. 불가능한 후보는 기존 배치 제외/재시도 경로로 처리한다.
- 모든 ISO 준비 후 StartCommand를 구성한다. 설치 ISO=boot order 1, 루트 디스크=2, 추가 ISO는 부팅 순위 미지정. OS 수준 boot dev와 장치별 boot order를 혼용하지 않는다.
- 추가 ISO 생성 VM에서 이후 bootOrder=hd로 변경하면 디스크 우선으로 전환된다. OS 설치 완료를 자동 판정하지 않는다.
- 추가 ISO 옵션은 기본 해제, ISO 생성 방식에만 표시한다. 템플릿/볼륨/스냅샷으로 전환하면 초기화한다. 목록은 bootable=false이며 주 ISO를 제외한다.
- 상단 이미지 카드와 추가 ISO 옵션 사이에 24px 여백을 둔다. 테마 색상을 덮어쓰지 않는다.

### 설계 구체화 및 제약

- ISO ID 전달은 다수의 기존 생성 메서드 시그니처를 확장하는 대신, 타입이 지정된 요청 범위 내부 객체를 사용한다. 저장 트랜잭션과 기존 생성 경로를 공유하며 신규 외부 API 호출 체인은 만들지 않는다.
- UI는 API 파라미터 지원과 KVM 여부로 활성화하고 최대 추가 1개를 받는다. 호스트 미확정 상태에서 임의 호스트를 조회해 최대값을 확정하지 않는다. 최종 한도는 실제 시작 시 서버에서 검증하며 UI에는 ConfigDrive/슬롯 제한 안내를 표시한다.
- startvm=false의 특정 hostid도 이후 시작의 배치 호스트를 보장하지 않는다. 이번 구현은 모든 최종 용량 판정을 실제 시작 시 수행한다.
- 시작 실패 시 기존 deploy 오류/복구 상태를 따른다. ConfigDrive 테스트 VM은 Error(비실행) 상태가 되었으며, 자동으로 정지 성공이라고 표시하지 않았다. API 최종 오류는 기존 배치 실패 메시지이고 구체적인 ISO 한도 원인은 관리 로그에 기록된다.

## 기본값 및 업그레이드

`vm.iso.max.count` ConfigKey 기본값만 1에서 2로 변경했다. 기존 설정 값을 강제 변경하는 SQL은 추가하지 않았다.

기존 저장 value=1이나 클러스터 override는 보존된다. 기본값 메타데이터가 2라고 해서 저장 값도 자동 2로 변경됐다고 해석하지 않는다. 기존 값 1이 관리자의 명시적 값인지 구분할 근거가 없으면 덮어쓰지 않는다. 운영자는 전역 설정과 해당 클러스터 override를 확인하고 필요 시 관리 UI/API에서 2로 변경한다.

이번 13번은 배포 전 해당 설정 행이 없었으며 새 코드 초기화 후 API에서 value/defaultvalue 모두 2가 확인됐다. 기존 저장 값 1 보존은 설정 초기화 코드 검토에 근거하며, 별도 운영 설정을 변경하는 실물 테스트는 하지 않았다. 호스트 capability 누락/한도 1 및 ConfigDrive 제한을 기본값 변경으로 우회하지 않는다.

## Docker 빌드 및 코드 검증

소스·의존성·빌드·테스트는 Rocky Linux 9.8 linux/amd64 Docker 볼륨에서 수행했다. 호스트에 소스나 프로젝트 의존성을 설치하지 않았다.

- Java 관련 5개 테스트 클래스: **765 tests, failures 0, errors 0**.
  - UserVmManagerImplTest 249, TemplateManagerImplTest 64
  - VirtualMachineManagerImplTest 100
  - LibvirtComputingResourceTest 322, LibvirtVMDefTest 30
- UI 전체: **49 suites, 487 tests passed**.
- 변경 UI 파일 대상 lint 통과. 전체 lint는 기존 DR 테스트 들여쓰기 오류가 남아 있으며 전체 lint 통과로 보고하지 않는다.
- Maven 전체 checkstyle은 기존 GuestOSDaoConnectionTest의 wildcard import 오류로 차단되어 확장 빌드에 `-Dcheckstyle.skip` 사용. 수정 범위의 선행 서버 checkstyle 검증과 테스트 결과는 별도 기록했다.
- 런타임 RSA/X509 및 OkHttp/MinIO/InfluxDB 의존 클래스 smoke 검증 통과.
- UI 프로덕션 빌드 통과. 소스맵은 개발 환경의 기존 ui-build 정책에 따라 제외했다.

주요 명령:

```sh
mvn -B -ntp -pl server,plugins/hypervisors/kvm,engine/orchestration \
  -Dcheckstyle.skip \
  -Dtest=UserVmManagerImplTest,TemplateManagerImplTest,LibvirtComputingResourceTest,LibvirtVMDefTest,VirtualMachineManagerImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -B -ntp -pl client clean
mvn -B -ntp -pl client -am -Dnoredist -DskipTests -Dcheckstyle.skip -T2 install
mvn -pl plugins/hypervisors/kvm dependency:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory=/tmp/issue1084/agent-lib
```

사용자는 필요한 의존 모듈까지 Docker에서 빌드하도록 승인했다. 전체 RPM/운영체제 빌드는 수행하지 않았다.

## 13번 배포

관리 서버 10.10.13.10, KVM 10.10.13.1/2/3, UI에 배포했다. 기존 관리 fat JAR에는 VmIsoMap/KMS/예약 관련 최신 클래스가 없어 개별 클래스 덮어쓰기로 배포하지 않았다. 해당 client 런타임과 agent 의존 모듈을 빌드했다.

기본 프로파일 빌드는 기존 Veeam 구성에 필요한 VMware 클래스가 없어 최초 관리 서버 시작이 실패했다. 즉시 기존 lib로 롤백한 후 noredist 프로파일로 재빌드하고 클래스 smoke 검증을 통과한 산출물을 재배포했다.

에이전트에는 최신 코드가 요구하지만 기존 배포에 없던 `scripts/vm/hypervisor/kvm/imageserver/`와 `ablestack_veeam.sh`도 추가했다. 기존 lib의 비JAR 스크립트를 보존했다. 교체 직후 실행 중인 게스트 UUID 목록이 교체 전과 동일함을 확인했다.

기존 DB 버전은 4.23이었지만 다음 뷰 정의가 최신 코드보다 오래돼 조회 API가 실패했다. DB 전체 백업 후 **현재 소스의 CREATE OR REPLACE VIEW**로 두 뷰를 정합화했다. 테이블 데이터 초기화/마이그레이션은 수행하지 않았다.

- `engine/schema/src/main/resources/META-INF/db/views/cloud.host_view.sql`
- `engine/schema/src/main/resources/META-INF/db/views/cloud.user_vm_view.sql`

UI 배포 빌드는 기존 13번에 이미 배포된 #1083 UI 변경을 임시 worktree에서 함께 적용했다. 이 PR의 diff에는 #1083 변경을 포함하지 않는다. config.json/WEB-INF 보존 및 업로드 SHA256 검증을 수행했다. 기존 해시 asset을 유지하고 index.html을 마지막에 교체했다.

### 백업 및 복구

서버 전용 경로이며 DB/config 백업의 비밀값을 저장소에 복사하지 않았다.

- 관리 DB/config/lib: `/var/tmp/issue1084-20260915-041017/` (원래 lib는 `lib-before`)
- 최초 실패/롤백 기록: `/var/tmp/issue1084-20260915-040538/`
- 에이전트 .1: `/var/tmp/issue1084-agent-20260915-041054/`
- 에이전트 .2: `/var/tmp/issue1084-agent-20260915-041056/`
- 에이전트 .3: `/var/tmp/issue1084-agent-20260915-041058/`
- 최초 UI 백업: `/var/tmp/issue1084-20260915-041340/ui-before.tar.gz`
- 간격 조정 후 UI 백업: `/var/tmp/issue1084-20260915-043153/ui-before.tar.gz`.

런타임 롤백은 해당 서비스 정지 후 백업 lib 디렉터리를 복원하고 재시작한다. 뷰 복원은 백업에서 해당 원래 VIEW 정의만 추출하여 적용한다. 전체 DB 덤프를 덮어쓰면 배포 이후의 사용자 변경을 잃으므로 일반 롤백 절차로 사용하지 않는다. UI는 해당 백업의 진입점/asset을 복원한다.

## 실제 기능 검증

| 항목 | 결과 |
|---|---|
| BIOS startvm=false 생성 | Stopped, 설치/추가 ISO 슬롯 3/4 저장 |
| BIOS 이후 시작 | Running, 두 ISO 경로 및 장치별 boot order 확인 |
| UEFI LEGACY startvm=true 생성 | Running, OVMF loader, 두 SATA CD-ROM 및 boot order 확인 |
| 설치 ISO 중복/추가 ISO 중복 | API 431, VM 생성 전 거절 |
| 부팅 가능한 추가 ISO/비부팅 주 ISO | API 431, VM 생성 전 거절 |
| ConfigDrive + 설치/추가 ISO | 실제 호스트 용량 검사에서 거절, 게스트 시작 없음 |
| 템플릿 startvm=false | Stopped, isos=[], 추가 ISO marker/bootOrder 없음 |
| 템플릿/볼륨/스냅샷 UI 전환 | 추가 ISO 영역 제거, ISO 재진입 시 해제 상태 |
| 다크 테마 | 추가 선택/안내문 정상 표시, 밝은 고정 배경 없음 |
| 관리/호스트 상태 | API 정상, KVM 3대 Up, version=4.23.0.0, cdrom max=2 |

VM 실행과 XML 확인은 Windows 설치 화면 진입/드라이버 로드 검증을 대신하지 않는다. 브라우저 보안 정책이 콘솔 프록시 URL 접근을 차단했으므로 이 부분을 우회하지 않았고, 미검증 항목은 #1085에 남겼다. 볼륨·스냅샷은 UI 분기 및 기존 서버 회귀 테스트 범위로 확인했으며 실제 생성 성공으로 보고하지 않는다.

### 테스트 리소스

- BIOS: `d5461b33-5b9c-4c5d-a677-2b9ab31732a3`, i-2-150-VM, Running
- UEFI: `da857e3c-9b98-4e6d-bc1d-508748758dd3`, i-2-151-VM, Running
- 템플릿 회귀: `002fa5b1-b906-43db-ad53-bc5d7ca40d20`, Stopped
- ConfigDrive 거절: `33b05777-545d-4a47-9158-40de16b1601c`, Error, 비실행

사용자가 설치 테스트 VM 콘솔을 확인 중이므로 임의 종료/삭제하지 않았다. 후속 검증 종료 시 정리한다. 기존 사용자 VM을 정지/시작하거나 ISO를 변경하지 않았다.

## 최종 UI 간격 확인

Docker UI 재빌드·배포 후 브라우저의 계산된 margin-top과 상단 카드 사이 실제 간격이 모두 24px임을 확인했다. 다크 테마 스크린샷에서도 영역 분리 및 텍스트 가독성을 확인했다. config.json과 WEB-INF 해시 보존 검증을 통과했다.
