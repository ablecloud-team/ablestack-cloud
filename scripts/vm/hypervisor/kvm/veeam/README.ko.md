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

# Ablestack Veeam + Mold 백업 (호스트 / datadisk)

공통 Staging의 볼륨별 백업을 사용하는 경우 [볼륨별 Staging 운영 안내](../THIRDPARTY-VOLUME-STAGING.ko.md)를 먼저 확인한다.
이 모드의 외부 UI 복원은 `ABLESTACK-<backup-UUID>-metadata` Job을 복원하고
`restoreBackupArtifact`를 통해 Mold의 순차 볼륨 복원을 요청한다.
아래 호스트 전체/datadisk 절차는 기존 방식에 해당한다.

## 핵심

| 항목 | 값 |
|------|-----|
| 모드 | **host** — KVM Agent + `/tmp/mold/veeam` |
| Mold 저장 | **datadisk** (`/data/backup`) — Mold Veeam/NAS repository 미사용 |
| Veeam Job | **Veeam UI**에서 생성·Pre/Post 등록 (PowerShell 자동화 없음) |
| FLR→Mold | KVM `mold-veeam-restore-agent.timer` → `restoreBackup` |

## 최초 설정 (KVM) — 조회 후 env/conf 자동 생성

호스트 전체 백업이면 **VM 이름을 넣을 필요 없음** (`VM_INCLUDE=*`, 기본 `VM_EXCLUDE=scvm`).
MS URL·KVM IP·hostname·zone은 agent/API에서 조회합니다. API key/secret만 넘기면 됩니다.
스크립트가 없으면 이전 배포 패키지입니다 → `veeam-host-deploy/deploy-from-ms.sh` 재실행.

`veeam_config.sh` / pre-notify가 **VeeamBackup offering을 자동 등록**합니다
(`importBackupOffering` provider=`ablestack-veeam`, externalid=`veeam`). NAS repository는 만들지 않습니다.

```bash
# Mold UI → Accounts → API Keys (현재 MS 192.168.1.30 용)
bash /etc/ablestack/veeam/bootstrap-host-veeam-env.sh \
  --api-key KEY --api-secret 'SECRET' \
  --job-name 'Agent Backup Job 1' \
  --veeam-host 192.168.1.240 \
  --veeam-password 'Ablecloud1!'

# 또는 수동:
# /etc/ablestack/veeam/veeam_config.sh --job-name "..." --vm-include "*" --backup-mode host --install

bash /etc/ablestack/veeam/setup-datadisk-veeam-backup.sh --env-file /etc/ablestack/veeam/mold-backup.env
```

## Veeam UI

1. KVM에 Linux Agent Job 생성 (SelectedFiles: `/tmp/mold/veeam`)
2. Guest Processing Pre/Post:
   - Pre: `/etc/ablestack/veeam/ablestack_veeam_pre_notify.sh`
   - Post: `/etc/ablestack/veeam/ablestack_veeam_post_notify.sh`
3. FLR 후 Mold 반영: `enable-veeam-mold-restore.sh`

## 배포

| 목적 | 스크립트 |
|------|----------|
| KVM 훅 설치 | `push-to-kvm.sh` → `install.sh` |
| 설정 | `veeam_config.sh` / `setup-datadisk-veeam-backup.sh` |
| UI FLR→Mold | `enable-veeam-mold-restore.sh` |
