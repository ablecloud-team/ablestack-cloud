# Ablestack Veeam + Mold 백업 (호스트 / datadisk)

## 핵심

| 항목 | 값 |
|------|-----|
| 모드 | **host** — KVM Agent + `/tmp/mold/veeam` (게스트별 Veeam Job 없음) |
| Mold 백업 오퍼링 | `BACKUP_OFFERING_NAME` (예: `VeeamBackup`) |
| Job conf | Veeam Job 이름과 맞추면 편함 (`--job-name`) |
| FLR→Mold | KVM `mold-veeam-restore-agent.timer` → `restoreBackup` |

## 최초 설정 (KVM)

```bash
/etc/ablestack/veeam/veeam_config.sh \
  --job-name "Mold ablecube31-2" \
  --offering-name "VeeamBackup" \
  --mold-url "http://10.10.31.20:8080/client/api" \
  --api-key KEY --api-secret 'SECRET' \
  --zone-id ZONE_UUID \
  --vm-include "*" \
  --kvm-host "10.10.31.2" \
  --backup-mode host \
  --install
```

Datadisk 한 번에:

```bash
bash /etc/ablestack/veeam/setup-datadisk-veeam-backup.sh --env-file /etc/ablestack/veeam/mold-backup.env
```

## 백업 / 복원

```bash
# 전체(실행 중 VM) 또는 VM_INCLUDE에 맞춘 백업
/etc/ablestack/veeam/mold-backup.sh backup-full --job "Mold ablecube31-2"

# 백업 ID 확인 후 개별 복원 (VM 정지 필요)
/etc/ablestack/veeam/mold-backup.sh list-backups --job "Mold ablecube31-2"
virsh -c qemu:///system shutdown i-2-11-VM
/etc/ablestack/veeam/mold-backup.sh restore --job "Mold ablecube31-2" \
  --vm-name i-2-11-VM --backup-id "<uuid>"
```

## Veeam Job (KVM file-level)

```bash
# mold-backup.env 에 VEEAM_SSH_HOST=10.10.254.246 등 설정 후
bash /etc/ablestack/veeam/push-to-veeam.sh --env-file /etc/ablestack/veeam/mold-backup.env
```

| 단계 | 내용 |
|------|------|
| PS1 배포 | `C:\ProgramData\Mold\backup\veeam\` |
| `setup-veeam-mold-job.ps1` | SelectedFiles `/tmp/mold/veeam` + Pre/Post |
| Pre/Post | KVM `ablestack_veeam_pre/post_notify.sh` → Mold API |

## Veeam UI Guest files → Mold

```bash
bash /etc/ablestack/veeam/enable-veeam-mold-restore.sh --vm-include 'i-2-XX-VM'
# timer: mold-veeam-restore-agent.timer → mold-backup.sh restore-watch --trigger-mold
```

## 배포 경로

| 목적 | 스크립트 |
|------|----------|
| KVM 훅 설치 | `push-to-kvm.sh` → `install.sh` |
| 설정 생성 | `veeam_config.sh` / `setup-datadisk-veeam-backup.sh` |
| Veeam PS1/Job | `push-to-veeam.sh` |
| UI FLR→Mold | `enable-veeam-mold-restore.sh` |

## 유지 파일 (요약)

- **KVM 코어**: `mold-backup.sh`, `mold-backup.lib.sh`, `veeam_config.sh`, `install.sh`, pre/post notify, `ablestack_cvtbackup.sh`
- **FLR**: `enable-veeam-mold-restore.sh`, `mold-veeam-restore-agent.*`
- **Veeam PS1**: `setup-veeam-mold-job.ps1`, `create-veeam-agent-job.ps1`, `install-veeam-job.ps1`, `veeam-job-*.ps1`
- **패키지/에이전트**: 상위 `ablestack_nasbackup.sh`, `postinstall_mold_backup.sh`, `setup_agent.sh`
