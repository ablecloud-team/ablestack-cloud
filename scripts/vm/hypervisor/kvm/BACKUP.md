<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# ABLESTACK 백업 및 복원 운영 안내

이 문서는 인프라 관리자가 ABLESTACK 백업 환경을 구성하고 백업·복원을 운영하기 위한 안내다.
솔루션별 구성, 사용 절차와 동작 흐름을 이 파일에서 함께 관리한다.

## 목차

- [운영자용 백업 구성 요약](#운영자용-백업-구성-요약)
- [백업 솔루션별 준비와 실행 순서](#백업-솔루션별-준비와-실행-순서)
- [Full 및 증분 백업 운영](#full-및-증분-백업-운영)
- [작업 상태와 제어](#작업-상태와-제어)
- [복원 절차](#복원-절차)
- [임시 파일 정리와 실패한 백업 삭제](#임시-파일-정리와-실패한-백업-삭제)
- [NetBackup 및 Veeam 서비스 운영](#netbackup-및-veeam-서비스-운영)
- [NetBackup 및 Veeam 인증정보 관리](#netbackup-및-veeam-인증정보-관리)
- [Veeam Host 구성과 배포](#veeam-host-구성과-배포)

## 운영자용 백업 구성 요약

이 절은 백업 기능을 사용하는 인프라 관리자를 위한 안내다.
Provider는 Mold가 사용할 백업 연동 종류이며, 아래의 `ablestack-*` 이름을 설정한다.
백업 오퍼링은 VM에 연결하는 백업 정책이다. 스테이징은 외부 백업 솔루션으로 보내기 전에
백업 파일을 잠시 두는 저장 공간이며, 장기 보관용 백업 저장소와 역할이 다르다.

### VM 스토리지와 스테이징 스토리지 구분

| 구분 | 종류 | 운영자가 확인할 내용 |
| --- | --- | --- |
| VM 기본스토리지 | RBD | VM 디스크가 Glue RBD 이미지로 저장된다. 백업할 시점의 데이터를 읽어 파일로 내보낸다. 원본 VM 디스크를 Host에 별도 마운트할 필요는 없다. |
| VM 기본스토리지 | QCOW2, GFS2 환경 등 | VM 디스크가 QCOW2 파일로 저장된다. 해당 시점의 디스크 데이터를 백업 파일로 복사한다. |
| 공통 스테이징 | GFS2 | Host에서 사용하는 GFS2 경로를 지정한다. 기본스토리지 경로인 `/mnt/glue-gfs`도 사용할 수 있다. |
| 공통 스테이징 | NFS | NFS 서버의 공유 경로와 Host의 마운트 경로를 지정한다. |
| 공통 스테이징 | LOCAL | Host에 연결한 별도 디스크의 마운트 경로를 지정한다. 백업을 수행할 각 Host에 경로와 용량을 준비한다. |

RBD VM도 NFS 또는 GFS2 스테이징을 사용할 수 있다.
스테이징 종류를 VM 기본스토리지의 종류와 동일하게 맞출 필요는 없다.
현재 공통 스테이징은 파일시스템 경로를 사용하며, 스테이징용 RBD 이미지를 생성·map하는 방식은 사용하지 않는다.

### 솔루션과 VM 스토리지별 백업 방식

| 백업 솔루션 | VM 스토리지 | Full 백업 | 증분 백업 | 최종 보관 위치 |
| --- | --- | --- | --- | --- |
| `ablestack-nas` | RBD | 백업 시점의 전체 디스크를 NAS에 직접 내보낸다. | 이전 백업 시점 이후 변경된 데이터를 NAS에 직접 내보낸다. | Mold에 등록한 NAS 백업 저장소 |
| `ablestack-nas` | QCOW2 | 전체 디스크 백업 파일을 NAS에 직접 생성한다. | 변경된 디스크 데이터로 증분 파일을 NAS에 생성한다. | Mold에 등록한 NAS 백업 저장소 |
| `ablestack-commvault` | RBD | 볼륨 하나의 전체 데이터를 스테이징에 내보낸 후 Commvault로 전송한다. | 볼륨 하나의 변경분을 스테이징에 내보낸 후 Commvault로 전송한다. | Commvault의 백업 저장소 |
| `ablestack-commvault` | QCOW2 | 볼륨 하나의 전체 데이터를 스테이징에 복사한 후 Commvault로 전송한다. | 볼륨 하나의 변경분을 스테이징에 복사한 후 Commvault로 전송한다. | Commvault의 백업 저장소 |
| `ablestack-netbackup` | RBD | 볼륨 하나의 전체 데이터를 스테이징에 내보낸 후 NetBackup 작업으로 저장한다. | 볼륨 하나의 변경분을 스테이징에 내보낸 후 NetBackup 작업으로 저장한다. | NetBackup 정책의 백업 저장소 |
| `ablestack-netbackup` | QCOW2 | 볼륨 하나의 전체 데이터를 스테이징에 복사한 후 NetBackup 작업으로 저장한다. | 볼륨 하나의 변경분을 스테이징에 복사한 후 NetBackup 작업으로 저장한다. | NetBackup 정책의 백업 저장소 |
| `ablestack-veeam` | RBD | 볼륨 하나의 전체 데이터를 스테이징에 내보낸 후 Veeam 작업으로 저장한다. | 볼륨 하나의 변경분을 스테이징에 내보낸 후 Veeam 작업으로 저장한다. | Veeam Job의 백업 저장소 |
| `ablestack-veeam` | QCOW2 | 볼륨 하나의 전체 데이터를 스테이징에 복사한 후 Veeam 작업으로 저장한다. | 볼륨 하나의 변경분을 스테이징에 복사한 후 Veeam 작업으로 저장한다. | Veeam Job의 백업 저장소 |

Commvault·NetBackup·Veeam은 `볼륨 1 생성 → 외부 저장 완료 → 해당 임시 파일 정리 → 볼륨 2 처리` 순서로 진행한다.
모든 볼륨을 처리한 뒤 VM 구성과 디스크 순서, 백업 연결 관계를 담은 메타데이터도 마지막으로 저장한다.
Mold에서는 이 전체 묶음이 VM 백업 하나로 표시되지만, 외부 솔루션에는 여러 작업이 보일 수 있다.
NAS는 NAS 자체가 최종 저장소이므로 공통 스테이징 설정을 사용하지 않는다.

### Commvault·NetBackup·Veeam 공통 준비

1. 백업에 사용할 Host에 해당 솔루션의 백업 에이전트 또는 클라이언트를 설치하고 외부 서버와 통신을 확인한다.
   VM 이동이나 정지 VM 백업에 대비하여 백업을 수행할 수 있는 Host 모두에 준비한다.
2. Mold의 Zone 설정에서 `backup.framework.enabled=true`와 사용할 `backup.framework.provider.plugin`을 설정한다.
3. 글로벌 설정에서 공통 스테이징 종류와 경로를 입력한다.

   | 글로벌 설정 | 입력 내용 |
   | --- | --- |
   | `backup.thirdparty.staging.storage.type` | `GFS2`, `NFS`, `LOCAL` 중 실제 스테이징 구성 |
   | `backup.thirdparty.staging.mount.path` | Host에서 스테이징 스토리지를 마운트하는 절대 경로 |
   | `backup.thirdparty.staging.root.path` | 임시 백업·복원 디렉터리를 만들 경로. 마운트 경로와 같거나 그 하위여야 한다. |
   | `backup.thirdparty.staging.nfs.source` | NFS 사용 시 `서버주소:/공유경로` |
   | `backup.thirdparty.staging.capacity.buffer.percent` | 최대 볼륨 크기에 추가할 여유 공간 비율. 기본 `20` |
4. `backup.thirdparty.staging.enable=true`로 활성화한다.
   Mold가 대상 Host의 마운트, 쓰기 가능 여부와 여유 공간을 확인한다.
   실패하면 활성화되지 않으므로 표시된 Host·경로·용량 문제를 해결하고 다시 활성화한다.
5. 백업 오퍼링을 가져오거나 구성 스크립트로 등록하고 대상 VM에 연결한다.
   외부 서버 연결과 스테이징 검증을 완료한 뒤 첫 백업을 실행한다.

GFS2 기본스토리지를 사용한다면 `mount.path=/mnt/glue-gfs`, `root.path=/mnt/glue-gfs`로 모두 입력할 수 있다.
실제 임시 파일은 `ablestack-commvault`, `ablestack-netbackup`, `ablestack-veeam` 하위에 생성된다.
백업 후 정리는 개별 작업의 임시 파일을 대상으로 하며 기본스토리지 경로 자체를 삭제하지 않는다.

스테이징 준비 용량은 현재 VM들 중 **가장 큰 단일 볼륨의 할당 크기 + 설정한 여유 비율**을 기준으로 한다.
예를 들어 최대 볼륨이 1 TiB이고 여유 비율이 20%이면 활성화 검사 기준은 1.2 TiB이다.
동시 작업은 각각 용량을 사용하며, QCOW2 백업은 원래 기본스토리지에도 별도 임시 작업 공간이 필요하다.
QCOW2 기본스토리지의 임시 공간은 백업 대상 볼륨의 할당 크기 합에
그 합의 20% 또는 10 GiB 중 큰 여유 공간을 더하여 확인한다.
기본스토리지와 스테이징이 같은 파일시스템이면 두 용량 예약을 합산한다.
스테이징 활성화가 성공해도 실제 작업 시작 시 공간·동시 작업 수를 다시 검사한다. 부족하면 `WAITING`으로 대기한다.

실행 중인 VM의 백업은 해당 VM을 실행하는 Host에서 처리한다.
정지 VM도 백업할 수 있으며, RBD는 원본 디스크와 스테이징에 접근할 수 있는 Host를 Mold가 선택한다.
QCOW2 정지 VM은 게스트 OS를 실행하지 않는 임시 백업 환경을 사용한다.
공통 스테이징은 작업 후에도 마운트를 유지하며, 외부 UI에서 복원 파일을 쓰는 시점에도 접근 가능해야 한다.
마운트가 빠져 있으면 NFS는 글로벌 설정의 공유 주소로 연결한다.
GFS2·LOCAL은 Host의 `/etc/fstab`에 마운트 정보가 있어야 자동으로 다시 연결할 수 있으므로
각 Host에서 파일시스템과 마운트 설정을 먼저 준비한다.

## 백업 솔루션별 준비와 실행 순서

### NAS: `ablestack-nas`

**사전 준비**

1. NAS 공유와 백업 Host의 접근 권한을 준비하고 Mold에 백업 저장소를 등록한다.
2. Zone의 백업 프레임워크와 `ablestack-nas`를 설정한다.
3. NAS 백업 오퍼링을 등록하여 VM에 연결한다. 주기 백업을 사용할 때는 Mold에 백업 스케줄을 설정한다.

**백업 순서**

1. Mold에서 백업을 실행하거나 설정한 스케줄을 기다린다.
2. NAS 저장소의 여유 공간을 확인한 후 Host에서 작업에 사용할 공유를 연결한다.
3. RBD VM은 백업 시점의 전체 데이터 또는 변경분을 NAS에 내보낸다.
   QCOW2 VM은 전체 또는 증분 디스크 파일을 NAS에 직접 생성한다.
4. 복원에 필요한 VM 구성 정보와 디스크 정보를 함께 저장한다.
5. Mold의 백업 결과를 확인한다. 작업용 NAS 연결과 임시 작업 자료는 후처리에서 정리한다.

NAS에서는 스테이징에서 외부 솔루션으로 다시 전송하는 단계가 없다.
백업 파일과 필요한 이전 Full·증분 파일은 NAS 백업 저장소에 남아 있어야 한다.

### Commvault: `ablestack-commvault`

**사전 준비**

1. Commvault 서버의 백업 저장소와 Plan을 준비한다.
2. Mold에 Commvault 서버 접속 정보를 설정하고 백업 Host의 에이전트 설치·등록을 확인한다.
3. 공통 스테이징을 활성화한 후 Commvault 백업 오퍼링을 가져와 VM에 연결한다.

**백업 순서**

1. Mold의 VM 백업 기능 또는 Mold 백업 스케줄에서 백업을 시작한다.
2. 실행 Host와 여유 공간을 확인한 뒤 VM의 백업 시점을 준비한다.
3. RBD는 볼륨의 전체 데이터 또는 변경분을 내보내고, QCOW2는 해당 데이터를 스테이징 파일로 복사한다.
4. Commvault로 해당 볼륨 파일을 보내고 저장 완료를 확인한 뒤 임시 파일을 정리한다.
5. 다음 볼륨에 같은 절차를 반복하고 마지막에 메타데이터를 저장한다.
6. Mold에서 VM 백업이 `BackedUp`으로 표시되는지 확인한다.

`COMMVAULT_TRANSFER`는 Host에서 만든 파일을 Commvault로 보내는 단계다.
Host 측 디스크 복사가 끝났더라도 이 전송과 마지막 메타데이터 저장이 끝나야 전체 백업이 완료된다.

**마이그레이션과 스테이징 호스트**

실행 중인 VM은 현재 실행 호스트에서 스테이징하고, 해당 호스트의 Commvault Client 아래에서 VM 내부 이름의 BackupSet을 생성하거나 재사용한다.
마이그레이션 후에는 새 호스트의 BackupSet을 사용하며, 이전 호스트의 BackupSet은 기존 백업을 위해 유지한다.
NFS·LOCAL·GFS2 모두 이 선택 절차를 따른다. NFS·GFS2의 같은 경로가 여러 호스트에서 보여도 전송은 선택한 작업 호스트에서 수행한다.
각 볼륨·메타데이터 전송 전에 해당 BackupSet의 콘텐츠 경로를 현재 작업의 파일 경로로 지정한다.
중지된 QCOW2 VM은 마지막 실행 호스트를 사용하며, 중지된 Glue RBD VM은 기본스토리지 접근성과 스테이징 공간을 확인해 작업 호스트를 선택한다.
QCOW2는 이전 백업 체인의 스테이징 호스트와 달라지면 새 Full 체인을 시작한다. Glue RBD는 호스트가 바뀌어도 스냅샷과 체인이 정상이고 증분 조건을 충족하면 이어갈 수 있다.
복원은 현재 VM 위치가 아니라 각 백업에 저장된 원본 호스트·외부 작업 정보를 사용한다.

### NetBackup: `ablestack-netbackup`

**사전 구성 흐름**

1. NetBackup Primary Server와 백업 저장소를 준비하고 백업 Host에 NetBackup Client를 설치한다.
   서버에서 각 Host를 Client로 인식하며 이름 또는 IP로 연결할 수 있는지 확인한다.
2. NetBackup에 기준 정책을 준비한다. 정책 종류는 **`Standard`**, 스케줄에는 **`Full Backup`**이 필요하다.
   실제 백업 Host가 정책의 Client에 등록되어 있어야 한다.
   현재 구성 스크립트의 정책 이름은 Mold에 등록된 Host 이름과 맞춰 사용하는 것이 좋다.
3. NetBackup REST API 주소와 API key, 대상 Mold 서버의 관리자 API key·secret key를 준비한다.
   NetBackup API 계정에는 정책 조회·복제, 백업 실행, 상태 조회 및 복원 작업에 필요한 권한을 부여한다.
4. 위의 공통 준비 절차에 따라 Mold의 Zone 설정과 스테이징을 활성화한다.
5. 해당 Host에서 [netbackup-host-config.py](netbackup-host-config.py)를 실행한다.

   ```bash
   python3 /usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/netbackup-host-config.py \
     --policy-name "<Mold Host 이름과 맞춘 NetBackup 정책 이름>" \
     --vm-include "<대상 VM의 i-계열 인스턴스 이름>" \
     --mold-url "<Mold API 주소>" \
     --admin-apikey "<Mold 관리자 API key>" \
     --admin-secretkey "<Mold 관리자 secret key>" \
     --netbackup-url "<NetBackup REST API 주소>" \
     --netbackup-apikey "<NetBackup API key>" \
     --backup-chain-size 10
   ```

   `--vm-include`는 여러 VM을 쉼표로 지정할 수 있으며, 미지정 시 `*`를 사용한다.
   VM의 표시 이름과 Host에서 사용하는 `i-...-VM` 인스턴스 이름을 구분하여 입력한다.
   실제 키를 명령행에 입력할 때는 Shell history와 명령행 접근 권한을 관리한다.
6. 스크립트는 Mold의 Zone·Cluster 설정을 확인하고 NetBackup 오퍼링을 등록한다.
   현재 스크립트는 해당 Cluster의 `kvm.incremental.backup`을 `true`로 설정한다.
   관리 서버 재기동 안내와 함께 중단되면 Mold를 재기동하고 같은 명령을 다시 실행한다.
7. 사전·사후 백업 스크립트, 암호화된 Mold 인증정보와 복원 감시 서비스를 Host에 구성한다.
   신규 Host 키는 자동 준비하지만 Mold API key 자체를 발급하는 기능은 아니므로 3번의 키는 필요하다.
8. NetBackup 연결 설정과 서비스 상태를 확인하고, 등록된 오퍼링을 대상 VM에 연결한다.
   이 구성은 각 백업 Host에서 수행한다. VM이 이동할 수 있다면 이동 대상 Host도 준비한다.

구성 스크립트는 `/usr/openv/netbackup/bp.conf`가 있으면 기존 파일을 백업하고
사전·사후 작업 및 Client 읽기 timeout을 24시간으로 설정한 후 NetBackup 서비스를 재시작한다.
진행 중인 NetBackup 작업이 없는 시간에 구성한다. NetBackup Client 자체를 설치하는 스크립트는 아니다.

**백업 순서**

1. NetBackup UI 또는 정책 스케줄에서 기준 정책을 실행한다.
2. Host의 사전 백업 스크립트가 대상 VM을 찾아 Mold에 백업을 요청한다.
3. 기준 정책의 작업이 종료된 뒤 Mold가 실제 볼륨별 백업과 외부 저장을 진행한다.
4. RBD는 전체 데이터 또는 변경분을 내보내고, QCOW2는 해당 데이터를 스테이징에 복사한다.
5. Mold가 기준 정책을 바탕으로 볼륨별 정책·작업을 만들어 해당 파일을 NetBackup에 저장한다.
   저장 완료를 확인한 뒤 임시 파일을 정리하고 다음 볼륨을 처리한다.
6. 마지막으로 메타데이터를 저장하고 Mold의 VM 백업 결과를 확정한다.

기준 정책은 유지되며 볼륨별 작업의 대상 파일은 Mold가 지정한다.
기준 정책 하나의 성공만으로 VM 백업 완료를 판단하지 않고, Mold에서 `BackedUp`을 확인한다.
구성 스크립트가 등록하는 NetBackup 오퍼링은 사용자 수동 백업을 허용하지 않으므로,
기본 운영 시작점은 NetBackup 정책과 사전 백업 스크립트이다.

### Veeam: `ablestack-veeam`

**사전 준비**

1. Veeam 백업 저장소와 Linux Agent를 준비하고 백업 Host가 Veeam에서 발견·등록되는지 확인한다.
2. 파일 단위 백업을 사용하는 기준 Linux Agent Job을 준비한다. Mold는 이 Job을 복제해 볼륨별 파일을 저장한다.
3. Mold에 Veeam 접속 정보를 설정하고 서버 측 작업 명령을 실행할 수 있는 연결을 확인한다.
   공통 볼륨별 백업은 Veeam 서버의 PowerShell 작업을 SSH로 실행하는 경로를 사용한다.
4. 공통 스테이징을 활성화하고 Host에 Veeam 연동 스크립트를 설치한다.
   Host의 Mold 인증정보를 구성하고 Veeam 오퍼링을 등록하여 VM에 연결한다.
5. 기준 Job의 Pre/Post에 설치된 Veeam 연동 스크립트를 지정한다.
   실행 파일과 구성 도구는 아래의 [Veeam Host 구성과 배포](#veeam-host-구성과-배포)를 참고한다.

**백업 순서**

1. Veeam UI 또는 Job 스케줄에서 기준 Job을 실행한다.
2. Pre 스크립트가 Mold에 대상 VM의 백업을 요청한다.
3. 기준 Job이 종료된 뒤 Mold가 RBD 또는 QCOW2 데이터를 볼륨별 스테이징 파일로 생성한다.
4. Mold가 기준 Linux Agent Job을 복제하여 해당 볼륨 파일을 Veeam에 저장한다.
5. 저장 완료를 확인한 파일을 정리하고 다음 볼륨을 처리한다.
6. 마지막 메타데이터 Job까지 완료한 후 Mold에서 VM 백업이 `BackedUp`인지 확인한다.

기준 Job과 Mold가 만든 볼륨별 Job을 구분한다.
외부 UI 복원에서 선택해야 하는 마지막 메타데이터 Job과 실제 VM 복원 절차는
아래의 [외부 UI 복원 안내](#netbackupveeam-외부-ui의-rbdqcow2-복원)를 따른다.

## Full 및 증분 백업 운영

### 설정과 백업 선택

- **Full**: 해당 백업 시점의 볼륨 전체 데이터를 보관한다. 최초 백업은 Full로 시작한다.
- **증분**: 이전 정상 백업 이후 변경된 데이터만 보관한다. 복원 시 기준 Full과 필요한 이전 증분 백업도 사용한다.
- `kvm.incremental.backup`: VM이 속한 Cluster의 증분 백업 사용 여부이다.
- `kvm.backup.chain.size`: Full과 후속 증분이 연결된 백업 체인의 길이 상한이다. 기본값은 `10`이다.

Mold가 이전 성공 백업과 설정을 확인하여 Full 또는 증분을 선택한다.
증분 기능을 켰다고 모든 요청이 증분이 되는 것은 아니며,
첫 백업, 체인 길이 상한 도달, 이전 연결 정보 누락·사용 불가, 볼륨 구성 변경 등에서는 새 Full이 필요하다.
기존 VM 단위 백업에서 새 볼륨별 방식으로 전환하는 첫 백업도 Full로 시작한다.
호스트 이동 후 이전 정보를 이어 쓸 수 없는 경우에도 Full로 바뀔 수 있다.

RBD 증분은 이전 RBD 백업 시점과 현재 시점의 변경분을 파일로 내보낸다.
QCOW2 증분은 디스크의 변경 기록을 이용해 변경된 부분만 파일로 만든다.
이전 기록이 유효하지 않아 증분이 실패하면, 기존 작업과 임시 자료의 정리가 확인된 경우 Full로 재시도한다.
외부 작업이나 정리가 미확정이면 즉시 재시도하지 않고 대기하므로 실패 사유와 정리 상태를 확인한다.

### 외부 솔루션의 Full 표시와 보관 정책

Commvault·NetBackup·Veeam에 보내는 파일은 Mold가 먼저 전체 또는 변경분으로 생성한다.
**Mold의 VM 백업은 증분이어도 외부 솔루션의 파일 저장 작업은 Full로 표시될 수 있다.**
이는 VM 전체를 다시 내보냈다는 의미가 아니라, 이번에 생성한 파일을 외부 솔루션이 보관했다는 의미이다.
Mold의 백업 유형과 외부 작업의 유형을 각각 확인한다.

Commvault·NetBackup·Veeam의 보관·만료는 외부 솔루션 정책을 따른다.
Mold는 만료 결과를 동기화하며, 필요한 Full이나 증분 파일이 사라진 복원 시점은 복원 불가로 표시한다.
외부 솔루션에서 볼륨별 작업이나 마지막 메타데이터를 임의로 삭제하면 전체 VM 복원에 영향을 줄 수 있다.
NAS는 NAS에 남은 기준 Full·증분 파일과 Mold 백업 목록을 함께 관리한다.

### 백업 후 확인할 항목

1. Mold에서 해당 VM 백업의 최종 상태가 `BackedUp`인지 확인한다.
2. 외부 솔루션에서 볼륨별 작업과 마지막 메타데이터 작업이 완료됐는지 확인한다. NAS는 저장소의 백업 결과를 확인한다.
3. `WAITING`이면 여유 공간과 동시 작업 제한을, 전송 중이면 해당 외부 솔루션의 작업 상태를 확인한다.
4. 실패했다면 화면의 실패 사유와 정리 상태를 확인한다. 임시 파일이 남았다는 이유로 기본스토리지나 스테이징 상위 경로를 직접 삭제하지 않는다.
5. 첫 Full과 후속 증분을 각각 실행하여 백업 유형을 확인하고, 운영 전 별도 VM으로 복원 결과를 확인한다.

### 원본 VM 삭제와 보관된 백업 사용

ABLESTACK 백업 오퍼링을 제거하면 VM의 오퍼링 연결과 Mold 백업 예약을 해제하고, 기존 백업은 보존한다.
진행 중인 백업·복원과 관련 정리를 먼저 완료한다. 백업 삭제는 백업 목록에서 별도로 실행한다.

| ABLESTACK 솔루션 | 오퍼링 제거 시 외부 백업 | Mold 백업 이력 |
| --- | --- | --- |
| NAS | NAS의 Full·증분 파일과 메타데이터를 보존한다. | 보존한다. |
| NetBackup | 외부 백업을 보존한다. 외부 보관·만료 정책은 계속 적용된다. | 보존하고 카탈로그 결과를 동기화한다. |
| Veeam | 외부 백업을 보존한다. 외부 보관·만료 정책은 계속 적용된다. | 보존하고 카탈로그 결과를 동기화한다. |
| Commvault | 호스트별 BackupSet과 외부 백업을 보존한다. 외부 보관·만료 정책은 계속 적용된다. | 보존하고 카탈로그 결과를 동기화한다. |

NAS·Commvault·NetBackup·Veeam은 원본 VM을 영구 삭제한 뒤에도 보관된 백업을 사용할 수 있다.
원본 VM을 삭제하기 전에 백업·복원 작업과 관련 정리를 완료하고, VM의 백업 오퍼링 연결과 예약을 해제한다.
VM 삭제 시 DATA 볼륨을 함께 삭제할지 선택하고, 기본스토리지 공간은 볼륨의 물리 정리 후 반환되는지 확인한다.
보존된 Full·증분 파일과 백업 메타데이터는 VM 삭제와 별도로 관리한다.
Commvault·NetBackup·Veeam은 오퍼링 연결 해제나 VM 영구 삭제 후에도 보관 백업에 저장된 오퍼링·원본 정보를 기준으로 카탈로그와 외부 만료 결과를 정기적으로 확인한다.

Commvault 오퍼링을 다시 할당하면 대상 호스트의 VM BackupSet을 확인한다.
오퍼링에 연결된 Plan과 일치하는 기존 BackupSet을 재사용하며, 백업 경로와 기존 백업을 유지한다.
BackupSet 목록에 Plan 정보가 없으면 해당 BackupSet ID의 상세 정보를 조회하여 확인한다. 생성 후 확인과 오퍼링 삭제 전 검사에도 같은 절차를 적용한다.
BackupSet이 없으면 선택한 Plan으로 생성한다. 호스트가 바뀐 후 백업할 때도 동일하게 확인한다.
조회가 실패하거나 응답·생성 결과를 확인하지 못하거나 Plan이 일치하지 않으면 할당 또는 백업을 성공 처리하지 않는다.
기존 백업은 저장된 호스트·외부 작업 정보로 조회하고 복원하므로, 해당 Commvault Client와 BackupSet을 유지한다.
백업 이력이 남아 있는 동안 BackupSet은 오퍼링 연결과 별도로 관리하며, 개별 백업 삭제가 BackupSet 전체 삭제를 의미하지 않는다.

Commvault 백업 오퍼링은 Zone마다 하나를 등록하고 외부 Plan과 연결한다.
VM의 오퍼링 연결 해제와 백업 오퍼링 자체 삭제는 별개의 작업이다.
오퍼링 자체는 연결된 백업이 있거나 할당된 VM이 있으면 삭제할 수 없다.
보관된 백업을 Mold에서 조회·복원하려면 해당 오퍼링 정의를 유지한다.
연결된 백업과 할당된 VM이 모두 없으면 해당 Zone의 호스트·VM 내부 이름·Plan으로 확인한 관리 대상 BackupSet을 정리한 뒤 오퍼링 정의를 삭제한다.
생성·재사용한 BackupSet 정보를 오퍼링에 기록하여 VM·호스트 DB 이력이 정리된 뒤에도 삭제 대상을 확인한다.
외부 삭제나 삭제 완료 확인에 실패하면 오퍼링 정의를 유지하며, 같은 삭제 작업을 재시도할 수 있다.
Commvault의 Plan 자체는 유지한다.

| 기능 | 원본 VM 영구 삭제 후 사용 |
| --- | --- |
| 가상머신 백업 복원 | 영구 삭제된 원본 VM으로는 복원할 수 없다. 일반 삭제 상태인 VM은 영구 삭제와 구분한다. |
| 볼륨 복원·연결 | 백업에 저장된 볼륨 정보를 이용해 복원하고, 같은 Zone의 다른 사용 가능한 VM에 연결한다. |
| 백업에서 새 인스턴스 생성 | 백업 당시 구성과 디스크 데이터를 이용해 새 VM과 볼륨을 생성한다. 필요한 템플릿·오퍼링·네트워크와 기본스토리지 공간을 준비한다. |
| 백업 삭제 | 보관된 백업의 삭제 정책과 증분 의존 관계에 따라 별도로 삭제한다. |

볼륨 복원·연결과 새 인스턴스 생성에는 원본 VM의 실행 상태나 원본 디스크 파일이 필요하지 않다.
필요한 Full·증분 백업과 메타데이터는 유지해야 하며, Mold가 참조하는 삭제 VM의 DB 이력을 수동으로 제거하지 않는다.
백업 이력이 있는 VM의 일반 볼륨 연결·분리 제한은 VM 삭제와 별도로 적용한다.

## 작업 상태와 제어

### 상태와 단계 읽기

Mold의 백업 상태는 VM 백업 전체의 결과다. 단계는 현재 처리 중인 작업을 나타낸다.
볼륨 파일이 생성되었더라도 외부 전송과 마지막 메타데이터 저장이 끝나야 `BackedUp`이 된다.
목록에서는 복원을 `복원 중`으로 표시하고, 단계·볼륨 번호·상세 내용은 상태에 마우스를 올려 확인한다.
백업 단계도 마우스오버에서 확인하며, 백업 진행률을 제공하는 경로에서는 진행 막대를 함께 표시한다.
백업·복원 단계는 화면 언어와 관계없이 `QCOW2_BACKUP`, `RESTORE_CONVERT` 같은 영문 코드로 표시한다.

| 표시 | 의미 |
| --- | --- |
| `BackingUp` | VM 백업을 진행 중이다. 단계를 확인하고, 볼륨 정보를 제공하는 경로에서는 현재 볼륨도 확인한다. |
| `BackedUp` | 필요한 볼륨과 메타데이터 저장을 완료했다. |
| `Restoring` | 복원 파일을 가져오거나 실제 VM 볼륨을 복원 중이다. |
| `Failed` | 작업이 실패했다. 실패 사유와 임시 파일 정리 상태를 함께 확인한다. |

복원 결과는 백업본의 상태와 별도로 관리한다. 복원 후 목록이 `BackedUp`으로 돌아와도
이번 복원의 성공 여부는 **가상머신 복원 결과**와 복원 실패 사유로 확인한다.
카탈로그 `AVAILABLE`은 필요한 백업 파일을 외부 카탈로그에서 확인했다는 의미이며,
VM 복원 완료나 게스트 OS 부팅 성공을 의미하지 않는다.

### 백업 단계

다음은 NAS와 Commvault·NetBackup·Veeam에서 사용하는 주요 단계다.
백업 엔진과 연동 방식에 따라 일부 단계만 표시되며, 단계가 짧으면 상태 조회 사이에 지나갈 수 있다.

| 단계 | 수행하는 작업 |
| --- | --- |
| `WAITING` | 공통 스테이징과 기본스토리지 용량, 동시 실행 수의 허용을 기다린다. |
| `PREPARING` | 외부 기준 작업의 준비 완료를 기다리고 원본 디스크 읽기 작업을 준비한다. 볼륨별 외부 백업 경로에서 사용한다. |
| `QCOW2_BACKUP` | QCOW2 데이터를 백업 파일로 복사 중이다. NAS는 VM 단위 백업 작업을, 외부 솔루션의 볼륨별 경로는 현재 볼륨의 파일 생성을 표시한다. |
| `RBD_EXPORT` / `RBD_EXPORT_DIFF` | RBD 볼륨의 전체 데이터 또는 변경분을 내보내는 중이다. |
| `COMMVAULT_TRANSFER` / `NETBACKUP_TRANSFER` / `VEEAM_TRANSFER` | 생성한 볼륨 파일을 해당 솔루션의 저장소로 전송 중이다. |
| `METADATA_TRANSFER` | 모든 볼륨을 연결하는 VM 정보와 복원용 메타데이터를 저장 중이다. |
| `FINALIZING` | 전체 백업 결과를 확정하고 후처리를 진행 중이다. |

외부 솔루션의 볼륨별 백업은 다음 순서로 진행한다.

```text
WAITING → PREPARING
  → 볼륨 1 생성 → 외부 전송 확인 → 해당 볼륨 임시 파일 정리
  → 볼륨 2 생성 → 외부 전송 확인 → 해당 볼륨 임시 파일 정리
  → METADATA_TRANSFER → FINALIZING → BackedUp
```

NAS는 볼륨 파일을 NAS에 직접 저장하므로 외부 전송 단계가 없다.

#### 솔루션·백업 엔진별 진행 표시

단계 코드가 같더라도 작업을 실행하고 진행률을 제공하는 단위는 백업 경로에 따라 다르다.

| 백업 경로 | 단계와 볼륨 표시 | 진행률 기준 |
| --- | --- | --- |
| NAS / QCOW2 | `QCOW2_BACKUP`을 표시하며 현재 볼륨 `1/2`, `2/2`는 제공하지 않는다. 여러 디스크를 하나의 libvirt VM 백업 작업으로 실행한다. | `virsh domjobinfo`의 VM 백업 작업 전체 처리량을 백업 진행 구간으로 환산한다. 특정 볼륨 하나의 진행률이 아니다. |
| NAS / Glue RBD | `RBD_EXPORT` 또는 `RBD_EXPORT_DIFF`와 현재 볼륨 번호를 제공한다. 볼륨별로 내보내고 NAS에 저장한다. | 현재 볼륨 처리량과 볼륨 순서로 계산한다. 증분 내보내기처럼 총 처리량을 알 수 없는 경우 진행률을 표시하지 않을 수 있다. |
| Commvault·NetBackup·Veeam / 볼륨별 백업 | 현재 볼륨 번호와 파일 생성·`*_TRANSFER` 단계를 제공한다. 볼륨별 생성·전송을 반복한 뒤 `METADATA_TRANSFER`로 진행한다. | 각 볼륨의 생성·전송 구간과 마지막 메타데이터 저장 구간에 배분한 비율로 계산한다. |

화면은 작업 상태 API가 볼륨 번호와 전체 볼륨 수를 함께 제공할 때만 `볼륨: 1/2`처럼 표시한다.
예를 들어 NAS QCOW2에서 `BackingUp / RUNNING / QCOW2_BACKUP / 11%`만 표시되는 것은 정상이며, ROOT·DATA 중 어느 볼륨을 처리 중인지 나타내는 값은 아니다.
이 진행률들은 전체 백업의 모든 처리 시간을 측정한 값이나 모든 디스크의 단순 바이트 복사 비율과는 다르다. `BackedUp`이 되어야 전체 백업이 완료된다.

#### NAS 증분 실패와 Full 재시도

증분 시도가 실패하고 실패 작업의 정리가 확인되면 Full 백업으로 재시도할 수 있다.
데이터를 추가했다는 이유만으로 Full이 되는 것은 아니며, 이전 백업의 체크포인트·비트맵과
체인 상태가 다음 증분 백업에 사용할 수 있어야 한다.
실패한 증분 시도의 부모 체인은 이후 증분의 기준으로 사용하지 않도록 닫고,
재시도한 Full이 성공하면 그 백업을 새 기준으로 사용한다. 기존 백업본을 삭제한다는 뜻은 아니다.

QCOW2 증분 시작 전 부모 체크포인트를 libvirt에 재등록해야 할 수 있다.
재등록이 실패하면 실제 백업을 시작하지 않고 정리 후 실패를 반환한다.
실패 사유의 `Failed to redefine checkpoint ...`와 뒤에 붙은 libvirt 오류를 확인한다.

실행 Host의 `/var/lib/ablestack/backup/jobs/<백업 UUID>/job.log`로 실패한 시도를 확인한다.
Full 재시도의 새 작업 UUID와 혼동하지 않는다. 실패 작업 정리가 끝나면 이 파일은 이미 없어질 수 있다.
체크포인트 재등록 오류는 작업 로그와 Agent 로그에 함께 기록한다.

대용량 NAS QCOW2의 `-S 4k` 변환·부팅 검증은 **Full과 증분 백업 모두** 대상으로 할 수 있다.
테스트 설정을 활성화하면 독립 Full도 단순 복사 대신 지정한 `-S` 값으로 변환하고 검사·비교한다.
예를 들어 900GiB 데이터를 담은 Full을 `-S 4k`로 복원하여 부팅과 파일 해시를 검증할 수 있다.
증분 체인 복원 기능까지 확인하려면 새 Full 이후 증분 백업을 추가로 생성하여 그 시점도 복원한다.
설정과 판정 절차는 [NAS QCOW2 sparse 복원 테스트](../../../../docs/validation/ablestack-nas-qcow2-sparse-restore.md)를 참고한다.

### 백업 완료 처리와 완료 후 카탈로그 확인

Commvault·NetBackup·Veeam의 볼륨별 백업은 데이터 전송, 완료 후 정리, 외부 카탈로그 확인을 각각 관리한다.
백업 완료 전후에는 다음 순서로 처리한다.

| 순서 | 처리 내용 | 확인할 표시 |
| --- | --- | --- |
| 1 | 각 볼륨의 외부 저장 완료를 확인하고 해당 볼륨의 스테이징 파일을 정리한다. | `BackingUp`, 해당 솔루션의 `*_TRANSFER` |
| 2 | 모든 볼륨의 연결 관계와 VM 구성, 체크포인트 등 복원 메타데이터를 마지막으로 저장한다. | `BackingUp` / `METADATA_TRANSFER` |
| 3 | Host 작업 종료를 확인하고, 다음 증분에 필요한 체크포인트 정보를 보관한다. 원본의 임시 작업 파일과 백업 스테이징 디렉터리를 정리하고 용량·실행 슬롯 예약을 해제한다. | `BackingUp` / `FINALIZING` |
| 4 | 전체 백업의 크기·결과와 사용량을 확정한다. 별도의 카탈로그 확인 전에는 `PENDING`으로 표시한다. | `BackedUp`, 정리 상태 `COMPLETED`, 스테이징 `RELEASED`, 카탈로그 `PENDING` |
| 5 | Management Server의 백그라운드 동기화가 외부 카탈로그에서 선택한 시점에 필요한 메타데이터와 Full·증분 파일의 존재 여부를 조회한다. | `PENDING` 또는 직전 카탈로그 확인 결과 |
| 6 | 필요한 파일이 모두 있음을 확인하면 카탈로그 상태와 확인 시각을 저장한다. | 카탈로그 `AVAILABLE`, 카탈로그 확인 시간 |
| 7 | 이후에도 같은 주기로 확인하여 외부 저장소의 파일 만료·누락 또는 조회 실패를 반영한다. | 확인 결과에 따른 카탈로그 상태·사유와 확인 시각 |

카탈로그 동기화 주기는 `backup.framework.sync.interval`이며 기본 `300`초다.
백업 완료 후 다음 동기화에서 확인하므로 완료 직후 `BackedUp / PENDING / 정리 COMPLETED`가 함께 표시될 수 있다.
갱신에는 다음 동기화까지의 대기 시간과 외부 API 조회 시간이 필요하며, 여러 VM·백업을 순서대로 조회하면 더 걸릴 수 있다.
진행 중 작업을 확인하는 `backup.framework.active.job.sync.interval`(기본 `10`초)과는 별도 주기다.

**카탈로그 확인 시간**은 백업 완료 시간이 아니라 마지막으로 성공한 외부 카탈로그 조회의 시각이다.
백업 목록을 새로고침하면 저장된 최신 확인 결과를 볼 수 있다.
외부 API 조회가 실패하면 `UNKNOWN`과 사유를 표시하며, 이전에 성공한 확인 시각은 남을 수 있다.
정리 상태 `COMPLETED`는 임시 파일 정리 결과이고, 카탈로그 `AVAILABLE`은 외부 백업 파일 확인 결과다.
이전 증분 기준의 추가 정리가 필요한 작업은 **이전 RBD 스냅샷 정리 상태** 또는 **이전 QCOW2 비트맵 정리 상태**로 별도 확인한다.

### 이전 증분 기준의 정리

Commvault·NetBackup·Veeam의 볼륨별 증분 백업은 전체 볼륨과 메타데이터의 외부 저장을 완료한 뒤
원본 스토리지의 **직전 parent 기준**을 정리한다. 이 정리는 다음 백업의 변경 추적을 위한 원본 자원 정리다.
외부 저장소에 보관한 Full·증분 백업 파일이나 복원 연결 관계는 유지한다.

| 원본 스토리지 | 상태 항목 | 정리 대상 | 보존 대상 |
| --- | --- | --- | --- |
| Glue RBD | 이전 RBD 스냅샷 정리 상태 | 이번 증분이 사용한 직전 parent 스냅샷 | 현재 백업 시점의 스냅샷과 외부 백업 파일 |
| QCOW2 | 이전 QCOW2 비트맵 정리 상태 | 이번 증분이 사용한 직전 parent 체크포인트에 연결된 비트맵 | 현재 비트맵, 체크포인트 등록 정보와 보관한 XML, 외부 백업 파일 |

1. **완료 확인:** 모든 볼륨과 메타데이터 전송, Host의 백업 종료·완료 처리를 확인한다.
2. **대상 등록:** 백업 계획에 기록한 직전 parent가 있으면 원본 정리 상태를 `WAITING`으로 저장한다.
   parent가 없는 새 Full 백업에서는 이 절차로 과거 기준을 일괄 삭제하지 않는다.
3. **소유·사용 여부 확인:** 이번 백업에 기록한 원본 이미지·파일, parent 이름과 현재 기준을 확인한다.
   QCOW2는 모든 대상 디스크의 현재 비트맵이 유효하고 다른 작업과 충돌하지 않는지 확인한다.
4. **직전 parent 정리:** RBD는 해당 이미지의 기록된 parent 스냅샷만 제거한다.
   QCOW2는 실행 중 VM의 QMP 또는 정지 VM 디스크의 정상 잠금을 이용해 parent 비트맵만 제거한다.
   현재 비트맵을 초기화하거나 체크포인트와 하위 항목을 일괄 삭제하지 않는다.
5. **결과 확인:** 제거 결과를 확인하고 해당 작업의 정리 완료 기록을 남긴 뒤 `COMPLETED`로 반영한다.
   QCOW2의 `COMPLETED`는 이전 비트맵 정리 결과이며, 체크포인트 XML 삭제를 뜻하지 않는다.
6. **정리 재시도:** Host 연결 실패, 이미지 잠금·사용 중 또는 조회·제거 결과 미확정이면 `WAITING`과 사유를 유지한다.
   백그라운드 정리 작업이 30초 간격으로 다시 확인하며, Management Server 재시작 후에도 저장된 대기 작업을 이어서 처리한다.

일부 디스크만 정리된 상태에서 실패해도 재시도는 같은 parent만 대상으로 한다.
이미 제거된 비트맵·스냅샷은 다시 생성하지 않으며, 다른 백업 기준을 추가로 삭제하지 않는다.
QCOW2의 parent 비트맵이 공유될 수 있는 backing 계층에도 남아 있으면 하위 파일을 임의 변경하지 않는다.
원본 파일의 소유·사용 관계와 안전한 정리 범위를 확인하지 못한 경우에는 사유를 남기고 정리를 대기한다.
백업은 `BackedUp`을 유지하면서 원본 정리만 `WAITING`일 수 있다.
카탈로그 확인과 스테이징 정리도 각각 별도 상태이므로 함께 확인한다.

다음 증분은 **이번 백업이 남긴 현재 기준**에서 시작한다.
QCOW2 디스크에 비트맵이 남아 있고 libvirt 등록 정보만 사라졌다면 보관한 XML로 필요한 직전 기준을 재등록한다.
재등록 시 현재 증분 기준을 검증하고, 정리된 과거 비트맵을 새로 만들거나 초기화하지 않는다.
XML은 체크포인트 메타데이터이므로 실제 비트맵이 유실된 경우의 변경 추적 데이터를 복구할 수는 없다.
백업본에서 디스크를 복원할 때에는 외부 저장소의 Full·증분 파일을 사용한다.

NAS의 QCOW2 증분도 저장 완료 후 이전 parent 비트맵을 정리하고 새 비트맵을 보존한다.
NAS의 작업 내 정리 결과는 Host 작업 로그에서 확인한다.
위의 별도 원본 정리 상태와 백그라운드 재시도는 공통 스테이징을 사용하는 세 솔루션에 적용한다.

### 공유 스테이징의 동시 사용과 예약 절차

Commvault·NetBackup·Veeam의 백업과 복원은 공통 스테이징을 함께 사용한다.
작업마다 provider·VM·백업 시점 또는 복원 작업 ID로 디렉터리를 구분하고,
각 작업의 용량과 실행 슬롯을 확보한 뒤 실행한다. 한 VM의 여러 볼륨을 순서대로 처리하는 작업은 실행 슬롯 하나를 사용한다.

| 글로벌 설정 | 기본값 | 적용 범위 |
| --- | --- | --- |
| `backup.thirdparty.staging.concurrent.jobs.per.host` | `1` | Host 하나에서 동시에 실행하는 백업·복원의 합 |
| `backup.thirdparty.staging.concurrent.jobs.per.cluster` | `4` | Cluster 하나에서 동시에 실행하는 백업·복원의 합 |
| `backup.thirdparty.staging.concurrent.jobs.total` | `4` | 세 솔루션과 모든 Host를 합친 동시 백업·복원 수 |
| `backup.thirdparty.staging.queue.timeout` | `3600`초 | 새 작업이 실행 허용을 기다릴 수 있는 시간 |

기본 설정에서는 같은 Host의 작업은 하나씩 실행하고, 여러 Host에서는 Cluster·전체 제한과 용량 범위 안에서 병렬로 실행한다.
같은 Host에서 둘 이상 실행하려면 Host 제한을 조정하고 Cluster·전체 제한 및 각 작업의 필요 공간도 함께 확보한다.
GFS2·NFS를 공유하는 Host들은 같은 파일시스템의 예약분을 함께 계산한다.
`LOCAL`은 Host별 공간을 검사하되 Host·Cluster·전체 동시 실행 수 제한은 공통으로 적용한다.

1. **대기 등록:** 작업을 `WAITING`으로 등록하고 실행 Host, 스테이징과 기본스토리지의 요구량을 확인한다.
2. **공간·슬롯 확인:** 현재 가용 공간에서 다른 활성 작업의 예약분을 제외하여 판단하고,
   Host·Cluster·전체 제한도 모두 확인한다. 같은 자원을 사용하며 실행 조건을 갖춘 앞선 대기 작업을 우선한다.
3. **예약 확정:** 예약할 용량과 허용 요청을 저장하고 Host의 예약 완료를 확인하여 `ADMITTED`로 전환한다.
   Host 응답을 확인 중인 `ADMITTING`도 용량과 슬롯을 점유한다.
4. **작업 실행:** 볼륨별 생성·전송·임시 파일 정리를 반복한다. 볼륨 하나의 임시 파일 정리만으로 전체 작업의 예약을 해제하지 않는다.
5. **종료와 정리 확인:** 실제 백업·복원 및 외부 작업의 종료와 필요한 임시 작업 공간 정리를 확인한 뒤 예약을 `RELEASED`로 바꾼다.
   해제된 공간과 슬롯을 기준으로 다음 대기 작업의 실행 여부를 다시 판단한다.

예약은 Mold 작업 사이의 용량 사용을 조정하는 기록이다. 예약 크기만큼 빈 파일을 미리 생성하는 방식은 아니므로,
파일시스템의 실제 사용량과 예약량은 다를 수 있다. 기본스토리지와 스테이징이 같은 파일시스템이면
두 요구량과 다른 작업의 예약분을 합산한다. 공간 여유율은 [솔루션별 복원 공간 여유율](#솔루션별-복원-공간-여유율)을 참고한다.

대기 제한 시간이 지나면 대기 작업을 취소하는 절차를 수행한다. 실행 중인 작업의 예약은 경과 시간만으로 만료하지 않는다.
실패·취소 요청 후에도 프로세스 종료, 외부 전송 종료 또는 정리를 확인하지 못하면 예약을 유지한다.
취소 처리가 대기 중인 `CANCEL_PENDING`도 공간과 슬롯을 점유한다.
이 경우 스테이징 관리에서 대기 사유를 확인하고 **작업 상태 다시 확인 → 필요한 임시 파일 정리 재시도 → 예약 해제 확인** 순서로 처리한다.

### 스테이징 대기와 정리 대기 구분

스테이징의 `WAITING`은 실행 허용을 기다리는 상태다. 사유를 함께 확인한다.

| 스테이징 상태 또는 대기 사유 | 의미 |
| --- | --- |
| `WAITING` / `PREPARING_ADMISSION` | 작업의 실행 허용 요청을 준비 중이다. 이 문구만으로 공간 부족이라고 판단하지 않는다. |
| `WAITING` / `STAGING_CAPACITY` | 스테이징의 예약 가능한 공간이 부족하다. |
| `WAITING` / `PRIMARY_STORAGE_CAPACITY` | 원본을 유지하면서 임시 볼륨을 준비할 기본스토리지 공간이 부족하다. |
| `WAITING` / `HOST_CONCURRENCY_LIMIT`, `CLUSTER_CONCURRENCY_LIMIT`, `STAGING_CONCURRENCY_LIMIT` | Host·Cluster·전체 동시 실행 수 제한 때문에 기다린다. |
| `WAITING` / `EARLIER_WAITING_JOB` | 같은 자원을 사용하는 먼저 대기한 작업의 실행을 기다린다. |
| `ADMITTING` | Host의 실행 허용 응답을 확인 중이며, 용량과 실행 슬롯을 확보한 상태다. |
| `ADMITTED` | 용량과 실행 슬롯을 확보하여 작업 실행이 허용됐다. 작업 완료를 뜻하지 않는다. |
| `CANCEL_PENDING` | 취소 요청 후 종료·정리 확인을 기다리며 예약을 유지한다. |
| `RELEASED` | 작업의 자원 예약이 해제됐다. 복원 성공 여부는 별도 결과로 확인한다. |

**복원 정리 상태**는 실행 허용과 별개로 복원용 스테이징 파일의 정리 상태를 표시한다.
복원 중 `WAITING`과 `Restore job has not finished`가 함께 나오는 것은
복원에 필요한 파일을 아직 정리하지 않고 있다는 정상 표시다. 이때 정리 재시도를 할 필요는 없다.
작업 종료 후에도 정리가 `WAITING`으로 남으면 정리 사유와 외부 작업 종료 여부를 확인한다.
`Restore staging directory ownership is unconfirmed; existing data is protected`는
디렉터리가 해당 작업 소유인지 확인하지 못해 기존 파일을 보존한다는 의미다.

복원 정리가 `COMPLETED`이고 예약이 `RELEASED`인 작업은 저장된 복원 이력으로 상태를 표시한다.
정상 정리 과정에서 Host의 작업·예약 요청 파일도 제거할 수 있으므로, 완료 후 그 파일을 다시
조회해서 `UNKNOWN` 또는 `Staging admission request is not ready`가 표시되면 조회 처리 문제를 확인한다.
이 문구만으로 이미 확정된 `COMMITTED / COMPLETED` 결과가 취소되거나 복원이 다시 실패한 것은 아니다.

### 대역폭 조정과 백업 취소

Commvault·NetBackup·Veeam의 QCOW2 백업은 실행 중인 VM과 정지 VM 모두
`backup.qos.bandwidth.limit.mbps`를 초기 제한값으로 사용한다. `0`은 `제한 없음`을 뜻한다.
`QCOW2_BACKUP` 단계에서는 Mold에서 대역폭을 변경할 수 있으며 변경값은 다음 볼륨에도 적용된다.
이 제한은 원본 디스크에서 스테이징 파일로 복사하는 작업에 적용한다.
외부 솔루션 전송 단계와 메타데이터 전송·최종 완료 처리에는 적용하지 않는다.
RBD export/export-diff의 실시간 대역폭 조정은 제공하지 않으며, RBD 백업의 상태 툴팁에는 대역폭과 적용 상태를 표시하지 않는다.

용량 대기 중인 작업과 `PREPARING`, `QCOW2_BACKUP`, `RBD_EXPORT`, `RBD_EXPORT_DIFF` 단계에서는
백업 취소를 제공한다. 외부 작업이 실행 중이거나 종료 여부를 확인하지 못한 경우에는 취소할 수 없다.
`COMMVAULT_TRANSFER`, `NETBACKUP_TRANSFER`, `VEEAM_TRANSFER`, `METADATA_TRANSFER`, `FINALIZING` 단계에서는 취소를 제공하지 않는다.
이미 일부 볼륨 전송을 완료했더라도 다음 볼륨 생성 중 외부 전송이 없는 상태라면 취소할 수 있다.
취소 요청 후 실제 작업 종료와 임시 파일 정리가 확인되어야 공간 예약이 해제된다.
예를 들어 볼륨 1의 전송을 완료하고 볼륨 2를 `QCOW2_BACKUP` 또는 `RBD_EXPORT` 단계에서 생성 중이면
취소 후 원본 체크포인트·스테이징 파일·공간 예약·Host 작업 기록 정리를 진행한다.
외부 작업의 종료 여부를 확인하지 못하면 정리를 `WAITING`으로 유지하고 재확인한다.
이미 외부 저장소에 전송한 볼륨 1의 파일은 취소 백업 기록과 함께 보존한다.
해당 외부 파일까지 제거하려면 정리 완료 후 취소 백업에서 일반 **백업 삭제**를 실행한다.
삭제는 해당 취소 백업이 소유한 파일을 대상으로 하며, 외부에 전송하지 않은 볼륨은 건너뛴다.
외부 파일 삭제와 정리를 완료하면 Mold 백업 행을 제거한다. 외부 승인 또는 삭제 확인이 대기 중이면 행과 삭제 진행 기록을 유지한다.

## 복원 절차

### Mold UI에서 복원

NAS·Commvault·NetBackup·Veeam의 복원은 대상 백업 시점과 VM을 선택하여 진행한다.
기존 VM에 복원할 때에는 VM을 정지한 뒤 실행한다.

**NAS**는 백업 저장소를 연결하여 선택한 시점의 파일을 읽는다.
**Commvault·NetBackup·Veeam**은 Mold가 복원 Host와 공통 스테이징 경로를 준비한 뒤
외부 솔루션에서 필요한 파일을 가져온다. 백업 당시 Host나 임시 경로가 남아 있을 필요는 없다.

1. Mold에서 원하는 VM 백업 시점을 선택하고 복원을 요청한다.
2. 실행 Host, 스테이징 및 기본스토리지의 공간을 확인한다.
3. 필요한 Full·증분 파일을 볼륨별로 가져와 새 볼륨을 준비한다.
4. 모든 볼륨의 준비가 성공한 뒤 실제 VM 볼륨을 교체한다.
5. VM 복원 결과와 후처리 완료를 확인한 뒤 VM을 시작한다.

볼륨 준비 중 실패하면 기존 VM 볼륨을 교체하지 않는다.
교체 도중 실패하면 기존 볼륨으로 되돌리는 절차를 수행하며, 복구가 완료되지 않은 경우에는 관리자 확인이 필요하다.
원본을 보호하면서 새 볼륨을 준비하므로 기본스토리지에 추가 공간이 필요하다.
QCOW2 백업도 원본 기본스토리지에 별도 임시 작업 공간을 사용하므로 스테이징 여유 공간만 확인해서는 안 된다.

### 솔루션별 복원 공간 여유율

여유율은 전체 스토리지의 일정 비율을 비워 두라는 뜻이 아니라, **복원에 필요한 것으로 계산한 용량에 추가하는 비율**이다.
스테이징과 기본스토리지는 각각 검사한다. Disk Offering의 thin/sparse 설정이나 게스트의 실제 데이터량,
작은 증분 백업 파일 크기만으로 복원 공간 요구량이 줄어들지는 않는다.

| 백업 솔루션 | 검사 대상 | 설정 | 기본값 및 범위 |
| --- | --- | --- | --- |
| NAS·Commvault·NetBackup·Veeam | 복원 대상 기본스토리지: QCOW2 파일·Glue RBD | `backup.restore.primary.storage.capacity.buffer.percent` | 기본 `20`, 정수 `0`~`100`. 검사별 최소 여유 공간 `10 GiB` |
| Commvault | 공통 스테이징: QCOW2·RBD 백업 파일 | `backup.thirdparty.staging.capacity.buffer.percent` | 기본 `20`, 정수 `0`~`100` |
| NetBackup | 공통 스테이징: QCOW2·RBD 백업 파일 | `backup.thirdparty.staging.capacity.buffer.percent` | 기본 `20`, 정수 `0`~`100` |
| Veeam | 공통 스테이징: QCOW2·RBD 백업 파일 | `backup.thirdparty.staging.capacity.buffer.percent` | 기본 `20`, 정수 `0`~`100` |

**기본스토리지:** ABLESTACK의 네 백업 솔루션이 글로벌 설정
`backup.restore.primary.storage.capacity.buffer.percent`를 함께 사용한다.
`15`로 지정하면 새 복원의 QCOW2 파일 공간 검사와 Glue RBD 풀 공간 검사에 모두 15%를 적용한다.
필요 용량이 1000 GiB인 검사라면 최소 1150 GiB의 가용 공간이 필요하다.
`0`으로 설정해도 최소 10 GiB의 여유 공간은 필요하다.
이 설정은 Full·증분, 기존 VM 복원·새 인스턴스 생성·개별 볼륨 복원에 적용한다.

NAS 파일 복원은 같은 대상 디렉터리의 볼륨별 임시 공간과 누적 증가량으로 전체 복원 계획을 검사하고,
각 볼륨 처리 전에도 확인한다. RBD는 해당 Glue 풀의 가용 공간과 복원 추정 용량을 비교한다.
Commvault·NetBackup·Veeam은 기존 VM 볼륨을 유지하면서 새 볼륨을 준비하므로 대상 볼륨들의 용량을 반영한다.
세 솔루션의 외부 전송 전 기본스토리지 예약, 스테이징과 겹치는 공간 계산, 실제 볼륨 준비 검사에도
같은 복원 계획에 저장한 기본스토리지 여유율을 사용한다.

글로벌 설정값을 바꿀 때에는 서비스 재시작이 필요 없다.
변경값은 새로 요청한 복원부터 적용하며, 진행 중인 복원은 시작할 때의 값을 유지한다.
Host 로그의 `PRIMARY_CAPACITY_POLICY`, `PRIMARY_SPACE_PLAN_CHECK`, `PRIMARY_SPACE_CHECK`, `RBD_SPACE_CHECK`,
`VM_RESTORE_PRIMARY_CAPACITY`와 용량 예약 정보에서 적용값과 요구·가용 바이트를 확인한다.
NAS의 `-S 4k` 테스트 설정과는 별개이며,
`/etc/cloudstack/agent/ablestack-nas-restore-test.properties`에 여유율을 추가하지 않는다.

**스테이징:** Commvault·NetBackup·Veeam은 공통 스테이징 설정 하나를 함께 사용한다.
RBD 원본도 `.raw`·`.rbdiff` 파일로 스테이징하므로 QCOW2와 같은 스테이징 여유율을 사용한다.
활성화 검사는 가장 큰 VM 볼륨 크기에 비율을 더하고, 실제 복원은 선택한 복원 계획의 스테이징 요구량에 적용한다.
이 비율에는 기본스토리지 복원의 최소 10 GiB 규칙을 적용하지 않는다. 복원 계획 생성 시 비율을 저장하므로 변경값은 새 작업에서 사용한다.
스테이징과 기본스토리지가 같은 파일시스템이면 두 공간 요구량과 다른 작업의 예약분을 함께 반영한다.
스테이징 비율과 기본스토리지 비율은 독립적이다. 예를 들어 스테이징만 15%로 바꾸고 기본스토리지는 20%로 유지하거나,
기본스토리지 설정도 15%로 변경할 수 있다. 백업 시 원본 기본스토리지의 작업 공간 예약은 별도 기준을 사용한다.

### Commvault·NetBackup·Veeam 볼륨별 복원 단계

| 단계 | 수행하는 작업 |
| --- | --- |
| `WAITING` | 스테이징·기본스토리지 공간과 실행 슬롯의 허용을 기다린다. |
| `COMMVAULT_RESTORE` / `NETBACKUP_RESTORE` / `VEEAM_RESTORE` | 해당 솔루션에서 메타데이터 또는 볼륨 백업 파일을 스테이징으로 가져온다. 상세 문구로 어느 파일인지 확인한다. |
| `PREPARE_VOLUME` | 가져온 파일을 기본스토리지의 복원용 임시 볼륨에 적용한다. 첫 Full 파일은 변환·복사하고, 후속 증분은 순서대로 병합·적용한다. |
| `SWITCH_VOLUMES` | 모든 볼륨의 준비를 마친 뒤 실제 VM 볼륨으로 교체하는 구간이다. 교체의 확정 여부는 VM 복원 결과로 확인한다. |
| `CLEANUP_SOURCE` | 볼륨 교체 확정 후 복원에 사용한 임시 파일의 후처리 구간이다. 정리 완료 여부는 정리 상태로 확인한다. |
| `FINALIZING` | Host 작업 종료 후 Mold가 최종 결과를 반영 중일 때 UI에서 표시할 수 있다. |

파일 가져오기와 적용은 같은 볼륨에서도 반복된다.

```text
WAITING → 메타데이터 가져오기·검증
  → 볼륨 1: Full 가져오기 → PREPARE_VOLUME → 증분 가져오기·적용 반복
  → 볼륨 2: Full 가져오기 → PREPARE_VOLUME → 증분 가져오기·적용 반복
  → SWITCH_VOLUMES → CLEANUP_SOURCE → 복원 결과·정리 완료 확인
```

`Applying volume 1/2, artifact 1`은 **전체 2개 볼륨 중 첫 번째 볼륨의 첫 번째 백업 파일**을
임시 볼륨에 적용 중이라는 뜻이다. `artifact`는 디스크 파티션이 아니라 Full·증분 백업 파일의 순번이다.
첫 파일이 Full이고, 증분이 있으면 같은 볼륨의 `artifact 2`, `artifact 3` 순서로 이어진다.
볼륨의 처리 순서는 복원 계획을 따르므로 `1/2`만으로 ROOT 또는 DATA라고 단정하지 않는다.
메타데이터를 가져오는 동안 표시되는 볼륨 번호는 볼륨 데이터 적용 완료를 뜻하지 않는다.

`PREPARE_VOLUME`은 대용량 변환·병합 명령이 끝날 때까지 같은 상세 문구를 유지할 수 있다.
단계나 파일 번호가 한동안 같다는 이유만으로 중단됐다고 판단하지 않는다.
작업 Host의 `job.log`와 실제 실행 중인 변환·병합 작업을 함께 확인한다.
이 과정에서는 기존 VM 볼륨을 유지하고, 모든 볼륨의 준비가 성공한 뒤 교체한다.

Glue RBD 대상의 RAW Full 파일은 NAS와 같은 공통 `rbd import` 함수로 임시 이미지에 적용하고,
후속 RBD 증분 파일은 `rbd import-diff`로 적용한다. QCOW2 파일을 RBD로 복원하거나 RBD의 RAW 파일을
GFS 파일 볼륨으로 복원할 때는 `qemu-img convert`로 대상 형식에 맞춘다.
RBD 이미지 변환·정보 조회에는 `<pool>/<이미지>` 경로를 사용하며, 이미지 존재 확인·스냅샷 처리·이름 변경·정리 명령도
복원 대상으로 선택한 기본스토리지의 pool을 명시한다.

### NAS 복원 단계

NAS는 백업 저장소의 Full·증분 체인을 읽어 복원한다.
아래 단계는 파일 형식·대상 스토리지·단일 볼륨 또는 VM 복원 방식에 따라 일부만 나타난다.

| 단계 | 수행하는 작업 |
| --- | --- |
| `VALIDATE_CHAIN` | 선택한 시점의 Full·증분 체인과 복원 계획을 검증한다. |
| `PREPARE_SOURCE` | NAS 백업 저장소를 연결하고 복원 파일에 접근할 준비를 한다. |
| `RESTORE_DATA` | 현재 볼륨의 데이터 복원을 시작한다. 하위 단계로 바뀔 수 있다. |
| `RESTORE_PREPARE_TARGET` | 대상 파일 형식과 임시 볼륨, 기본스토리지의 작업 공간을 준비한다. |
| `RESTORE_SOURCE_CHECK` | NAS QCOW2 복원 테스트 설정을 사용한 경우 원본 Full 또는 증분 체인의 구조를 점검한다. |
| `RESTORE_CONVERT` | 백업 파일 또는 체인을 대상 디스크 형식으로 변환한다. sparse 테스트의 `-S 4k` 적용 여부는 상세 문구와 로그로 확인한다. |
| `RESTORE_COPY` | 별도 변환이 필요하지 않은 QCOW2 백업 파일을 복원 대상에 복사한다. |
| `RESTORE_RBD_IMPORT` / `RESTORE_RBD_DIFF` | Full 데이터를 RBD로 가져오거나 후속 RBD 증분을 적용한다. |
| `RESTORE_TARGET_CHECK` / `RESTORE_COMPARE` | NAS QCOW2 복원 테스트 설정을 사용한 경우 결과 파일을 점검하고 원본 Full 또는 증분 체인과 데이터를 비교한다. |
| `RESTORE_PROMOTE` | 준비한 임시 볼륨을 복원 대상 경로 또는 이미지로 반영한다. |
| `ATTACH_VOLUME` | 복원 방식에 따라 생성한 볼륨의 연결을 처리한다. |
| `CLEANUP_SOURCE` | 복원에 사용한 NAS 연결과 임시 자원을 정리한다. |
| `FINALIZING` | Host 작업 종료 후 Mold가 최종 결과를 반영 중일 때 UI에서 표시할 수 있다. |

테스트용 검사·비교가 비활성화된 복원에서는 `RESTORE_SOURCE_CHECK`, `RESTORE_TARGET_CHECK`,
`RESTORE_COMPARE`가 표시되지 않아도 정상이다.

### 가상머신 복원 결과와 기본스토리지 정리 상태

가상머신 복원 결과를 제공하는 경로에서는 UI가 **`결과(outcome) / 단계(phase)`** 순서로 표시한다.
스테이징 관리 화면에서는 두 값을 `·`로 구분한다.
결과는 볼륨 교체가 확정됐는지, 단계는 그 결과에 이르기까지 어디까지 처리했는지를 나타낸다.

| 결과 / 단계 예 | 의미 |
| --- | --- |
| `NOT_STARTED / WAITING` | 볼륨 준비 트랜잭션을 시작하기 전이다. |
| `RUNNING / PREPARING` | 메타데이터 검증과 볼륨별 파일 가져오기·적용을 진행 중이다. 기존 볼륨은 아직 교체하지 않는다. |
| `RUNNING / PREPARED` | 전체 임시 볼륨의 준비를 완료했다. 교체를 확정하기 전이다. |
| `RUNNING / SWITCHING` | 준비한 볼륨으로 교체 중이다. |
| `COMMITTED / COMMITTED` | 볼륨 교체를 확정했다. 기본스토리지의 기존 볼륨 정리가 남아 있을 수 있다. |
| `COMMITTED / COMPLETED` | 볼륨 교체를 확정하고 기본스토리지의 기존 볼륨 정리까지 완료했다. |
| `RUNNING / ROLLING_BACK` | 준비 또는 교체 실패 후 기존 볼륨으로 되돌리는 중이다. |
| `ROLLED_BACK / ROLLED_BACK` | 기존 볼륨으로 되돌렸다. 요청한 백업 시점으로의 복원 성공은 아니다. |
| `FAILED / FAILED` 또는 `FAILED / START_FAILED` | 볼륨 준비 트랜잭션 시작 전에 실패했다. 실패 사유를 확인한다. |
| `RECOVERY_REQUIRED` 또는 `UNKNOWN / COMMIT_UNCONFIRMED` | 되돌리기·정리 또는 교체 확정 기록을 확인해야 한다. 복원 완료로 판단하지 않는다. |

예를 들어 `COMMITTED / COMPLETED`의 두 값은 중복 결과가 아니라
**교체 확정 결과 / 후처리까지 끝난 단계**를 함께 보여 준다.
이 결과는 디스크 복원 결과이며, Windows 부팅·로그인과 데이터 검증 결과는 별도로 확인한다.

**기본 스토리지 볼륨 정리 상태**는 공통 스테이징 파일 정리와 별개다.

| 기본스토리지 정리 상태 | 의미 |
| --- | --- |
| `NOT_REQUIRED` | 볼륨 준비 시작 전 등으로 정리할 기본스토리지 자원이 아직 없다. |
| `NOT_STARTED` | 볼륨 준비·교체 중이며 성공 후 기존 볼륨 정리 또는 실패 후 임시 볼륨 정리를 아직 시작하지 않았다. |
| `RUNNING` | 교체 후 기존 볼륨을 정리하거나 롤백하며 임시 볼륨을 정리 중이다. |
| `WAITING` | 정리를 완료하지 못했으며, 사유와 복구 기록을 확인해야 한다. |
| `COMPLETED` | 해당 결과에 필요한 기본스토리지 볼륨 정리를 완료했다. |

따라서 복원 중 `RUNNING / PREPARING`, 스테이징 `ADMITTED`,
복원 정리 `WAITING (Restore job has not finished)`, 기본스토리지 정리 `NOT_STARTED`가
함께 표시되는 것은 정상 흐름이다. 각 항목은 서로 다른 대상과 처리 시점을 나타낸다.

### NetBackup/Veeam 외부 UI의 RBD·QCOW2 복원

NetBackup·Veeam은 **RBD와 QCOW2 모두 외부 UI에서 복원을 시작할 수 있다.**
볼륨별 백업에서는 해당 시점의 **메타데이터 백업 작업**을 선택한다.
Commvault는 Mold UI에서 복원을 시작한다.

1. Mold에서 대상 VM을 **Stopped**로 둔다.
2. 원하는 Mold 백업 시점에 연결된 메타데이터 작업을 확인한다.
   외부 작업 이름은 **`ABLESTACK-<Mold 백업 UUID>-metadata`**이다.
3. NetBackup/Veeam UI에서 해당 작업의 복원 시점을 선택한다.
4. 메타데이터를 복원 감시 서비스가 접근할 수 있는 마운트된 경로로 복원한다.
   Host의 복원 감시 설정에 등록된 경로를 사용하고, 외부 복원 전에 마운트 상태를 확인한다.
5. NetBackup 사후 스크립트·복원 감시 서비스 또는 Veeam 복원 에이전트가 완료된 작업을 확인하여
   해당 Mold 백업 시점의 VM 복원을 요청한다.
6. Mold가 복원 Host와 용량을 준비하고 필요한 볼륨을 순서대로 가져와 VM을 복원한다.
7. Mold에서 실제 VM 복원 완료를 확인한 뒤 VM을 시작한다.

볼륨별 파일 작업을 외부 UI에서 직접 복원해도 VM 복원은 시작되지 않는다.
메타데이터 작업의 정확한 백업 시점을 선택해야 하며, 여러 볼륨의 파일과 메타데이터를 서로 다른 시점에서 섞지 않는다.
외부 UI의 파일 복원 완료와 Mold의 VM 복원 완료는 별도 단계다.
외부 복원 완료 알림이 반복되어도 같은 작업으로 새 VM 복원을 반복 요청하지 않는다.

외부 UI에서 메타데이터를 쓰는 시점에는 Mold의 사전 용량 예약이 실행되지 않는다.
따라서 해당 경로의 마운트와 접근 권한을 운영 환경에서 유지해야 한다.
대용량 볼륨 파일은 이후 Mold가 용량을 예약한 스테이징 경로에서 처리한다.
RBD는 기준 Full 파일과 필요한 증분 파일을 순서대로 적용하여 새 RBD 볼륨을 준비한다.
QCOW2도 동일한 백업 시점의 Full·증분 파일을 사용한다.
두 방식 모두 모든 볼륨 준비가 성공한 뒤 실제 VM 볼륨을 교체한다.

## 임시 파일 정리와 실패한 백업 삭제

### 임시 경로와 보존 범위

#### 백업·복원 데이터 파일

아래의 `<root>`는 `backup.thirdparty.staging.root.path`이며,
`<provider>`는 `ablestack-commvault`, `ablestack-netbackup`, `ablestack-veeam` 중 해당 솔루션이다.
`<VM>`은 `i-2-220-VM`과 같은 VM 인스턴스 이름이고, `<시점>`은 `yyyy.MM.dd.HH.mm.ss.SSS` 형식이다.
GFS2 기본스토리지의 `/mnt/glue-gfs`를 마운트 경로와 `<root>`로 함께 사용할 수 있다.

| 구분 | 생성·접근 경로 | 파일과 용도 |
| --- | --- | --- |
| NAS 백업본 | `<NAS 공유 루트>/<VM>/<시점>/` | QCOW2 또는 RBD 백업 파일, VM 구성과 체크포인트 정보. NAS가 최종 보관 위치다. |
| NAS 작업 중 마운트 | Host 임시 디렉터리의 `csbackup*` 아래 `<VM>/<시점>/` | NAS의 같은 백업 파일을 마운트하여 읽고 쓴다. 백업 스크립트의 기본 예는 `/tmp/csbackup.XXXXX/<VM>/<시점>/`이며, 복원은 JVM 임시 디렉터리를 사용한다. |
| 세 솔루션의 백업 스테이징 | `<root>/<provider>/<VM>/<시점>/` | 외부 전송 전 볼륨 파일과 `domain-config.xml`, `backup-manifest.json`, 체크포인트 정보 등을 생성한다. |
| 세 솔루션의 복원 메타데이터 | `<root>/<provider>/restore/<복원 작업 ID>/metadata/` | 외부 저장소에서 가져온 VM 구성, `backup-manifest.json` 등 해당 백업 시점의 메타데이터를 둔다. |
| 세 솔루션의 복원 볼륨 파일 | `<root>/<provider>/restore/<복원 작업 ID>/payload/` | 필요한 Full·증분 파일을 가져와 기본스토리지의 새 볼륨을 준비하는 데 사용한다. |
| 세 솔루션의 최종 백업본 | 각 외부 솔루션의 백업 저장소 | Mold의 스테이징 경로와 별도로 보관하며 외부 카탈로그의 작업·파일 정보로 조회한다. |

볼륨 백업 파일의 기본 이름은 `root.<볼륨 UUID>` 또는 `datadisk.<볼륨 UUID>`에 확장자를 붙인다.
QCOW2는 `.qcow2`, RBD Full은 `.raw`, RBD 증분은 `.rbdiff`이다.
복원의 `metadata`와 `payload`에는 외부 복원 작업이 원래 백업 디렉터리 구조를 포함하여 파일을 생성할 수 있다.
작업별 정확한 경로는 스테이징 관리의 **임시 파일 저장 경로**와 해당 작업 계획에서 확인한다.

`root.path=/mnt/glue-gfs`인 Commvault의 경로 예는 다음과 같다.

```text
/mnt/glue-gfs/
├── ablestack-commvault/
│   ├── i-2-220-VM/2026.10.15.10.00.00.000/  # 백업 스테이징
│   └── restore/<복원 작업 ID>/
│       ├── metadata/                         # 복원 메타데이터
│       └── payload/                          # 복원 볼륨 파일
└── .volume-reservations/                     # 공유 스테이징 용량 예약 기록
```

여러 Host가 같은 GFS2 또는 NFS 공유를 마운트하면 같은 작업 경로와 파일이 모든 Host에서 보인다.
Host마다 별도의 복원 파일을 한 벌씩 만든다는 의미가 아니다. 실제 복원은 선택한 작업 Host에서 실행한다.
파일이 보이는 Host 수가 아니라 스테이징 관리의 **작업 호스트**로 실행 위치를 확인한다.
`LOCAL` 스테이징은 Host별 파일시스템이므로 같은 경로명이라도 파일을 공유하지 않는다.

#### 실행 Host의 작업·예약·복원 기록

Host의 백업·복원 관리 기록은 `/var/lib/ablestack/backup/` 아래에 보관하며, 작업별 상태와 실행 파일의 루트는 `jobs/`이다.
아래의 `<작업 ID>`는 백업에서는 백업 UUID, 복원에서는 별도로 발급한 복원 작업 ID다.
각 파일은 해당 실행 방식과 진행 단계에서 필요할 때 생성되며, 모든 작업에 항상 존재하는 것은 아니다.

| 구분 | 경로 | 주요 내용 |
| --- | --- | --- |
| 작업 상태 | `/var/lib/ablestack/backup/jobs/<작업 ID>.properties` | 상태·단계·실행 Host 작업 정보와 로그 경로 |
| 작업 로그·실행 기록 | `/var/lib/ablestack/backup/jobs/<작업 ID>/` | `job.log`, `events.jsonl`, `run.sh`, `exit-code` |
| 백업 계획과 진행 기록 | 위 백업 작업 디렉터리 | `backup-command.json`, `volume-plan.json`, `volume-progress.properties`, 외부 전송 요청·확인 및 정리 기록 |
| 이전 증분 기준 정리 기록 | `/var/lib/ablestack/backup/jobs/<백업 UUID>/source-cleanup.json` | 이번 백업이 사용한 parent와 현재 기준, RBD 스냅샷 또는 QCOW2 비트맵의 정리 결과·대기 사유 |
| 복원 계획과 진행 기록 | 위 복원 작업 디렉터리 | `restore-command.json`, `volume-restore-plan.json`, `restore-capacity-plan.json`, 외부 복원 요청·확인 및 정리 기록 |
| 백업 시작 기록 | `/var/lib/ablestack/backup/backup-start/<백업 UUID>.json`, 같은 이름의 `.lock` | 백업 시작 준비·요청 전달·실제 시작 여부를 기록한다. 잠금과 시작 기록으로 같은 작업의 중복 시작이나 시작이 차단된 요청의 뒤늦은 실행을 방지한다. |
| 실행 허용 요청·확인 | 해당 작업 디렉터리 | `staging-admission-plan.json`, `staging-admission-request.json`, `staging-admission-granted` 등 |
| 공유 스테이징 예약 | `<root>/.volume-reservations/` | `<작업 ID>.json` 예약량, `filesystem.id` 파일시스템 식별값, `capacity.lock` 동시 예약 제어 |
| 실행 허용 종료 기록 | `/var/lib/ablestack/backup/staging-admission-closed/<작업 ID>.closed` | 작업 디렉터리 정리 후에도 같은 작업이 다시 공간을 예약하지 않도록 보관하는 기록 |
| 복원 시작 기록 | `/var/lib/ablestack/backup/restore-start/<복원 작업 ID>.json`, 같은 이름의 `.lock` | 볼륨별 복원의 시작 준비·초기화 결과를 기록한다. 잠금과 시작 기록으로 중복 시작이나 시작이 차단된 요청의 뒤늦은 실행을 방지한다. |
| 복원 볼륨 교체 기록 | `/var/lib/ablestack/backup/jobs/restore-transactions/<대상 VM>/<트랜잭션 ID>.properties` | 볼륨 준비·교체·원상 복구와 정리 진행 기록 |
| VM 복원 결과 | `/var/lib/ablestack/backup/restore-outcomes/<복원 작업 ID>.json` | 볼륨별 복원의 결과와 기본스토리지 정리 상태. 작업 디렉터리와 별도로 보관한다. |

이 기록들은 실행 Host에 생성한다. 공유 스테이징이 다른 Host에서도 보이는 것과 별개이므로
`job.log`는 스테이징 관리에 표시된 작업 Host에서 확인한다.
필요한 실행 경로와 진행 단계에서 디렉터리를 생성하므로 Host마다 디렉터리 구성이 다를 수 있다. 같은 경로명이어도 각 Host의 기록은 별개다.
백업·복원 시작 기록, 실행 허용 종료 기록과 VM 복원 결과는 작업 디렉터리 정리 후에도 중복 실행 제어와 결과 확인에 사용하므로 별도로 보존한다.
작업 디렉터리의 로그·계획 파일이 남아 있다는 사실만으로 데이터 파일이나 용량 예약이 남았다고 판단하지 않는다.

#### NetBackup Host 연동 기록

NetBackup Host 연동 스크립트의 기본 상태 경로는 `/var/lib/ablestack/netbackup/`이며, `STATE_ROOT`로 지정할 수 있다.
이 스크립트나 복원 감시기를 사용할 때 필요한 디렉터리와 파일을 생성한다. 공통 작업 기록인 `/var/lib/ablestack/backup/`과 함께 관리한다.

| 디렉터리·파일 | 용도 |
| --- | --- |
| `contexts/` | 백업 시작·종료 알림 사이에 이어받을 작업 정보와 진행 표시를 저장한다. |
| `locks/` | 같은 작업에 대한 연동 스크립트의 동시 실행을 제어한다. |
| `sessions/` | 해당 연동 작업에서 조회한 Mold VM 목록 등 API 응답을 보관한다. |
| `runtime/` | 정책별 실행 환경과 작업 기록을 저장한다. |
| `restore-watcher-state.json` | 복원 감시기의 처리 상태를 저장한다. 기본 파일 경로이며 감시기 설정에 따라 지정할 수 있다. |
| `restore-watcher.lock` | 복원 감시기의 중복 실행을 제어한다. 기본 파일 경로이며 감시기 설정에 따라 지정할 수 있다. |

#### 기본스토리지의 백업·복원 작업 파일

백업 스테이징 파일 외에 원본 또는 복원 대상 기본스토리지에도 임시 작업 공간을 사용할 수 있다.

| 구분 | 생성 위치와 이름 | 용도 |
| --- | --- | --- |
| 세 솔루션의 QCOW2 백업 작업 파일 | 원본 볼륨 파일과 같은 디렉터리의 `.backup-<백업 UUID>-<디스크 이름>.qcow2` | 백업 도중 원본 디스크의 데이터를 안정적으로 읽기 위한 임시 작업 파일 |
| QCOW2 복원 준비·원본 보존 파일 | 대상 볼륨과 같은 디렉터리의 `<대상 볼륨>-csrestore-prepared-<ID>`, `<대상 볼륨>-csrestore-original-<ID>` 등 | 새 볼륨을 준비하고 교체 동안 기존 볼륨을 보존한다. 증분 적용 중에는 `csrestore-overlay-*.qcow2` 등의 작업 파일도 사용할 수 있다. |
| RBD 복원 준비·원본 보존 이미지 | 대상 Glue pool의 `<대상 이미지>-csrestore-prepared-<ID>`, `<대상 이미지>-csrestore-original-<ID>`, `<대상 이미지>-csrestore-<ID>` 등 | 파일시스템 디렉터리 대신 Glue RBD 이미지로 새 볼륨 준비와 기존 볼륨 보존을 수행한다. |
| 최종 복원 볼륨 | Mold에 등록된 대상 기본스토리지의 볼륨 경로 또는 Glue RBD 이미지 | 모든 준비와 교체가 성공한 뒤 VM이 사용하는 실제 디스크 |

예를 들어 파일 기본스토리지의 최종 볼륨은 `/mnt/glue-gfs/<볼륨 경로>`일 수 있다.
실제 파일·이미지 이름은 복원 계획, 작업 로그와 볼륨 교체 기록에서 확인한다.

#### 보존과 정리 범위

볼륨 백업 파일은 외부 저장 완료를 확인한 뒤 정리한다.
복원 파일은 VM 복원 과정에서 필요한 처리가 끝난 뒤 정리한다.
정리 대상은 해당 작업의 임시 파일이며, 기본스토리지 마운트 경로·스테이징 root·provider 상위 경로를 삭제하지 않는다.
작업 종료나 파일 정리를 확인하지 못하면 용량 예약을 유지한다.

### 실패한 백업 삭제 순서

1. Mold에서 실패 사유와 정리 상태를 확인한다.
2. 외부 작업이 실행 중이거나 정리가 대기 중이면 먼저 해당 작업의 종료 여부와 연결 상태를 확인한다.
3. 필요한 경우 **스테이징 관리**에서 작업 상태를 다시 확인하거나 정리를 재시도한다.
4. 정리 완료 후 Mold의 일반 **백업 삭제**를 실행한다.

정리 상태가 `COMPLETED`이고 UI의 일반 삭제가 정상 완료되면 추가 수동 파일 삭제는 필요하지 않다.
카탈로그의 `PENDING` 표시만으로 Host의 디렉터리를 수동 삭제하지 않는다.
작업 종료 후에도 정리가 `WAITING`이면 사유를 확인하고 실행 Host, 스테이징 마운트와 외부 서버 연결을 확인한다.
증분 백업이 참조하는 Full·이전 증분 파일은 후속 복원 시점에 필요하므로 개별적으로 삭제하지 않는다.

**스테이징 관리**는 자동 처리만으로 작업 결과나 정리를 확정하지 못한 경우 사용하는 관리자 화면이다.
작업 상태 다시 확인은 외부 작업과 파일을 조회하며, 새 백업을 시작하는 기능이 아니다.
정리 재시도는 종료가 확인된 작업의 남은 임시 파일과 작업 기록 정리를 시도한다.
외부 작업 번호의 수동 연결은 실제 솔루션에서 해당 VM·볼륨·시점의 작업임을 확인한 경우에만 사용한다.
확인되지 않은 작업을 완료로 간주하거나 강제로 삭제하는 기능은 아니다.

백업 하나의 실패를 정리할 때 공통 스테이징, 다른 VM의 파일이나 백업 정책을 직접 삭제하지 않는다.
Host 작업 기록도 Mold의 정리 결과를 확인하기 전에 수동으로 지우지 않는다.

## NetBackup 및 Veeam 서비스 운영

백업 솔루션의 Client·Agent는 데이터 전송을 담당한다.
아래 Mold 연동 서비스는 외부 UI에서 완료된 복원을 확인하여 해당 VM의 Mold 복원을 요청한다.
카탈로그 동기화와 실제 백업·복원 작업 상태 확인은 Management Server가 별도로 수행한다.

### 서비스별 역할과 준비

| 실행 위치 | 서비스 또는 작업 | 역할 | 기본 실행 방식 |
| --- | --- | --- | --- |
| NetBackup Client Host | `netbackup-host-restore-watcher.service` | NetBackup의 완료된 외부 UI 복원을 확인하고 복원 알림 스크립트로 Mold에 연결 | 상시 실행, 조회 사이에 기본 60초 대기 |
| Veeam Agent Host | `mold-veeam-restore-agent.timer` | Veeam 복원 감시 프로그램을 주기적으로 실행 | 기본 3분 주기 |
| Veeam Agent Host | `mold-veeam-restore-agent.service` | 완료된 Veeam 복원 세션의 정확한 복원 시점을 Mold 백업과 연결 | 타이머가 호출할 때 한 번 실행 후 종료 |
| Management Server | 백업 목록·카탈로그 동기화 | 외부 백업 파일의 존재 여부와 만료 결과를 Mold에 반영 | `backup.framework.sync.interval`, 기본 300초 |
| Management Server | 진행 중인 작업 동기화 | 백업·복원 상태와 완료 후처리를 확인 | `backup.framework.active.job.sync.interval`, 기본 10초 |

외부 UI 복원을 사용할 Host마다 해당 솔루션의 감시 서비스를 준비한다.
RBD·QCOW2 모두 같은 서비스를 통해 메타데이터 복원 완료를 Mold에 연결한다.
볼륨별 파일 전송과 실제 VM 볼륨 복원은 이후 Mold가 관리하며, 감시 서비스가 직접 수행하지 않는다.

**NetBackup**

- [netbackup-host-config.py](netbackup-host-config.py)가 감시 프로그램과 서비스 파일을 생성하고 활성화한다.
- 기본 서비스 파일은 `/etc/systemd/system/netbackup-host-restore-watcher.service`이다.
  별도의 timer 파일은 사용하지 않는다.
- 감시 설정은 `/etc/ablestack/netbackup/restore-watcher.conf`,
  Mold 복원 요청 설정은 `/etc/ablestack/netbackup/restore.conf`이다.
- 감시 주기는 `restore-watcher.conf`의 `POLL_INTERVAL_SECONDS`로 조정한다. 기본값은 `60`이다.
- NetBackup Client의 `netbackup` 서비스는 별도이다. 구성 스크립트는 Client를 설치하지 않으며,
  기존 `bp.conf`를 갱신한 경우 Client 서비스를 재시작한다.

**Veeam**

- [install.sh](veeam/install.sh)가 복원 감시 service·timer를 설치하고 timer 활성화를 시도한다.
  설치 후 실제 활성 상태를 확인한다.
- 기본 서비스 파일은 `/etc/systemd/system/mold-veeam-restore-agent.service`,
  타이머 파일은 `/etc/systemd/system/mold-veeam-restore-agent.timer`이다.
- Host의 `/etc/ablestack/veeam/mold-backup.env`와 Job별 `.conf`에 Mold 인증정보,
  Veeam 접속 정보와 감시 대상 Job을 구성한다.
- Veeam Linux Agent 설치·등록은 별도로 필요하며 Mold 감시 서비스가 이를 대신하지 않는다.
- 평상시에는 **timer가 `active`**인지 확인한다.
  service는 한 번 조회한 뒤 종료하므로 조회 사이에 `inactive`로 보이는 것은 정상이다.

외부 UI에서 복원을 시작하기 전에 감시 서비스를 활성화한다.
서비스 시작 시 이미 존재하는 복원 작업은 중복 요청 방지를 위해 처리 대상으로 제외할 수 있다.
서비스 활성화만으로 스테이징이 마운트되는 것은 아니므로 공통 스테이징 설정을 준비하고,
외부 솔루션이 메타데이터를 쓸 경로의 마운트와 접근 권한을 유지한다.

### 활성 상태와 로그 확인

각 명령은 해당 솔루션을 구성한 Host에서 실행한다.

**NetBackup**

```bash
systemctl is-enabled netbackup-host-restore-watcher.service
systemctl status netbackup-host-restore-watcher.service --no-pager
journalctl -u netbackup-host-restore-watcher.service -n 100 --no-pager
tail -n 100 /var/log/netbackup-mold-restore-watcher.log
tail -n 100 /var/log/netbackup-mold-restore.log
```

**Veeam**

```bash
systemctl is-enabled mold-veeam-restore-agent.timer
systemctl status mold-veeam-restore-agent.timer --no-pager
systemctl list-timers --all mold-veeam-restore-agent.timer
journalctl -u mold-veeam-restore-agent.service -n 100 --no-pager
tail -n 100 /etc/ablestack/veeam/events/restore.log
tail -n 100 /var/log/mold/veeam-hook.log
```

기본 경로를 변경한 구성은 해당 conf/env에 지정된 설정·로그 경로를 확인한다.
감시 로그의 요청 접수와 실제 VM 복원 완료는 별도 결과이므로 최종 완료는 Mold에서 확인한다.

### 기존 Host 갱신 시 서비스 확인

현재 구성에서는 `mold-veeam-catalog-sync.service/.timer`와
`mold-veeam-flr-watch.service`를 사용하지 않는다.
카탈로그는 Management Server에서 동기화하고 외부 UI 복원은 `restore-agent.timer`로 감시한다.

소스 파일이 없어도 이전에 Host에 설치한 서비스는 남아 있을 수 있다.
백업·복원이 진행 중이지 않은 시간에 아래 명령으로 기존 서비스를 확인한다.

```bash
systemctl list-unit-files 'mold-veeam-*'
systemctl list-units --all 'mold-veeam-*'
```

이전 `catalog-sync`·`flr-watch` 서비스가 실행 중이면 현재 감시 구성으로 전환한 뒤 사용을 중지한다.
현재 설치 도구는 이전 `mold-veeam-restore-watch.timer`만 자동 중지하므로
`catalog-sync`·`flr-watch`가 남아 있는 Host는 별도로 확인해야 한다.
현재 사용하는 `mold-veeam-restore-agent.service/.timer`는 유지한다.

## NetBackup 및 Veeam 인증정보 관리

Host의 사전·사후 스크립트와 복원 감시 서비스는 Mold API key와 secret key로 Mold에 요청한다.
이 API 인증정보는 대상 Mold 서버에서 발급받아야 한다.
Host의 `/root/.ssh/ablestack.key`는 API secret을 보호하는 암호화 키이며 API 계정의 secret key와 구분한다.

- 신규 구성은 Host별 임의 키를 자동 생성하고 NetBackup·Veeam이 같은 Host 키를 함께 사용한다.
- 새 API secret 저장에는 두 솔루션 모두 AES-256-GCM을 사용한다.
- 키와 암호화된 인증정보 파일은 root 전용으로 관리한다. 파일 권한은 `600`, 보호 디렉터리는 `700`이다.
- 필요한 Python 암호화 라이브러리는 `python3-cryptography`이다.
- 키와 암호화된 인증정보는 함께 보호된 위치에 보관한다.
  키만 삭제하거나 다른 Host의 암호문만 복사하면 인증정보를 읽을 수 없다.

| 기존 Host 구성 | 구성·갱신 시 동작 |
| --- | --- |
| 키와 암호화 파일 모두 없음 | 신규 키를 생성하여 인증정보를 저장한다. |
| 기존 키만 있고 암호화 파일 없음 | 설치 시 키를 보존하고 솔루션 구성 시 미사용 키를 신규 형식으로 전환한다. |
| 기존 키와 암호화 파일 있음 | 키를 보존하며 기존 인증정보를 읽을 수 있다. |
| 암호화 파일은 있는데 키 없음 | 새 키 생성을 차단한다. 원래 키를 복구하거나 인증정보를 다시 구성해야 한다. |
| 신규 형식 키가 이미 있음 | 동일한 키를 재사용한다. |

키를 바꿀 때에는 NetBackup·Veeam의 인증정보 파일과 설정을 함께 전환한다.
암호화된 인증정보가 남아 있으면 자동 키 교체를 차단하므로 키 파일만 수동 삭제하지 않는다.
실제 백업·복원이나 복호화 과정에서는 키를 새로 생성하거나 교체하지 않는다.
구성 명령에 실제 API secret을 입력할 때는 명령행·Shell history 접근 권한을 관리한다.

## Veeam Host 구성과 배포

| 목적 | 구성 도구 |
| --- | --- |
| Host 스크립트 설치 | [install.sh](veeam/install.sh) |
| 원격 Host 배포 | [push-to-kvm.sh](veeam/push-to-kvm.sh) |
| 최초 환경 구성 | [bootstrap-host-veeam-env.sh](veeam/bootstrap-host-veeam-env.sh) |
| Job 및 API 인증정보 구성 | [veeam_config.sh](veeam/veeam_config.sh) |
| Veeam 사전 백업 스크립트 | [ablestack_veeam_pre_notify.sh](veeam/ablestack_veeam_pre_notify.sh) |
| Veeam 사후 백업 스크립트 | [ablestack_veeam_post_notify.sh](veeam/ablestack_veeam_post_notify.sh) |

Host 설치 경로는 기본 `/etc/ablestack/veeam`이다.
Mold API key와 secret을 준비한 뒤 구성 도구의 `--help`와 운영 환경의 conf/env 설정을 확인하여 적용한다.
신규 구성은 API secret을 암호화 파일에 저장하고 설정 파일에는 해당 파일의 경로를 기록한다.
Veeam 기준 Job의 Pre/Post에는 설치한 사전·사후 스크립트 경로를 지정한다.
스테이징 경로는 Mold의 공통 글로벌 설정을 사용한다.
