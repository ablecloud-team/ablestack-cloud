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

# FT XCOLO KRBD Hook Guard Consumer Deployment Design - 2026-06-27

## Purpose

FTCTL now creates a KRBD guard under `/run/ablestack-vm-ftctl/krbd-guard/<vm>`
during FT XCOLO cold conversion. The guard is incomplete unless cloud agent
scripts consume it before unmapping KRBD devices.

This document records the confirmed cloud source files, build/install paths,
current host runtime paths, and the deployment verification required for the
hook-side guard consumer change.

## Confirmed Files And Paths

| Area | Source | Transformed/build file | Package install path | Active runtime path |
|---|---|---|---|---|
| libvirt qemu hook | `agent/bindir/libvirtqemuhook.in` | `agent/target/transformed/libvirtqemuhook` | `/usr/share/cloudstack-agent/lib/libvirtqemuhook` | `/etc/libvirt/hooks/qemu` |
| RBD cleanup script | `agent/bindir/cleanup-rbd.in` | `agent/target/transformed/cleanup-rbd` | `/usr/bin/cleanup-rbd` | Current 32.x hosts override to `/usr/local/sbin/cleanup_rbd.sh` |
| cleanup-rbd unit | `packaging/systemd/cleanup-rbd.service` | package unit | `/usr/lib/systemd/system/cleanup-rbd.service` | Current 32.x hosts shadow it with `/etc/systemd/system/cleanup-rbd.service` |
| cleanup-rbd timer | `packaging/systemd/cleanup-rbd.timer` | package unit | `/usr/lib/systemd/system/cleanup-rbd.timer` | `cleanup-rbd.timer` enabled and active |

The EL9 spec installs the hook library and then copies it to the libvirt hook
runtime path in `%posttrans agent`:

```text
install -D agent/target/transformed/libvirtqemuhook ... /usr/share/cloudstack-agent/lib/libvirtqemuhook
cp -a .../libvirtqemuhook /etc/libvirt/hooks/qemu
systemctl restart libvirtd
```

Therefore `/etc/libvirt/hooks/qemu` is not package-owned on the checked hosts,
but agent package reinstall should refresh it through `%posttrans`.

## Current 32.x Host Verification

Checked hosts:

```text
10.10.32.1
10.10.32.2
10.10.32.3
```

Confirmed:

- `/etc/libvirt/hooks/qemu` exists and is executable.
- `/usr/bin/cleanup-rbd` exists and is owned by `cloudstack-agent`.
- `/etc/libvirt/hooks/qemu` is not package-owned.
- `cleanup-rbd.timer` is enabled and active.
- Active service is `/etc/systemd/system/cleanup-rbd.service`.
- Active service runs `/usr/local/sbin/cleanup_rbd.sh`, not `/usr/bin/cleanup-rbd`.

This means future deployment must verify both the package-installed script and
the active override script.

## Required Code Design

### `libvirtqemuhook.in`

Add guard consumer logic to `unmapStorage()`.

Guard is active when:

1. `/run/ablestack-vm-ftctl/krbd-guard/<domain>/enabled` exists.
2. `expires` exists and is greater than current epoch time.
3. The RBD path being unmapped matches one entry from `paths`.

Path matching must support:

- `/dev/rbd/<pool>/<image>`
- `/dev/rbdN`, resolved through `rbd showmapped`
- `<pool>/<image>`
- `<image>`

Expected behavior:

```python
if is_ftctl_krbd_guarded(domain_name, img):
    logger.info("ftctl_krbd_guard_skip_unmap domain=%s path=%s", domain_name, img)
    continue
```

Only skip the guarded `rbd unmap`; do not skip the whole `release/end` hook or
custom hook execution.

Generated FT XCOLO primary XML may carry qemu-commandline disks and therefore
libvirt hook XML can contain disk nodes without a `source dev` value. The hook
must skip that empty source explicitly and log
`ftctl_krbd_skip_empty_unmap_source` instead of attempting `rbd unmap ""`.

The hook keeps `/var/log/libvirt/qemu-hook.log` as the production default. For
local smoke testing, `QEMU_HOOK_LOG` may override that path without changing host
runtime behavior.

### `cleanup-rbd.in`

Add guarded images to the used-image set:

```bash
used_images=($(get_used_images; get_guarded_images | sort -u))
```

The same guard and path normalization rules used by the qemu hook apply here.
Expired or malformed guards must be ignored so stale mappings can be cleaned
later.

### Current Host Override

Because the active unit runs `/usr/local/sbin/cleanup_rbd.sh`, immediate test
deployment must update:

- `/usr/bin/cleanup-rbd`
- `/usr/local/sbin/cleanup_rbd.sh`

The package unit can remain unchanged during the test. Removing the local
override and returning to `/usr/bin/cleanup-rbd` should be handled as a separate
operations change.

## Deployment Checklist

After package or hotfix deployment, verify on every compute host:

```bash
cmp -s /usr/share/cloudstack-agent/lib/libvirtqemuhook /etc/libvirt/hooks/qemu
grep -n "ftctl_krbd_guard_skip_unmap" /etc/libvirt/hooks/qemu
grep -n "ftctl_krbd_skip_empty_unmap_source" /etc/libvirt/hooks/qemu
grep -n "get_guarded_images" /usr/bin/cleanup-rbd
grep -n "get_guarded_images" /usr/local/sbin/cleanup_rbd.sh
systemctl is-enabled cleanup-rbd.timer
systemctl is-active cleanup-rbd.timer
systemctl cat cleanup-rbd.service
```

Expected:

- Runtime hook matches the cloudstack-agent library hook.
- Runtime hook contains FTCTL guard logic.
- Packaged cleanup script and active override cleanup script both contain guard
  logic.
- `cleanup-rbd.timer` remains enabled and active.

Before host deployment, also verify Maven transformed artifacts:

```bash
grep -n "ftctl_krbd_guard_skip_unmap" agent/target/transformed/libvirtqemuhook
grep -n "get_guarded_images" agent/target/transformed/cleanup-rbd
python3 -m py_compile agent/target/transformed/libvirtqemuhook
bash -n agent/target/transformed/cleanup-rbd
```

## Runtime Test Evidence

During FT protection retest, collect:

```bash
find /run/ablestack-vm-ftctl/krbd-guard/<vm> -maxdepth 1 -type f -print -exec cat {} \;
tail -n 200 /var/log/libvirt/qemu-hook.log | grep -E "ftctl_krbd_guard|rbd unmap"
systemctl start cleanup-rbd.service
journalctl -u cleanup-rbd.service -n 200 --no-pager
```

Expected:

- qemu hook logs `ftctl_krbd_guard_skip_unmap` while guard is active.
- cleanup-rbd treats guarded mappings as used.
- generated primary does not fail with `/dev/rbd/rbd/<image>: No such file or directory`.

## Build And Deployment Notes

- This is a cloud agent change, not a UI-only or management-only change.
- Full package deployment should deploy the `cloudstack-agent` package to
  compute hosts.
- On the current 32.x cluster, package work must use `aspm`/`aspkg`.
- If doing a manual hotfix, copy the transformed hook to both
  `/usr/share/cloudstack-agent/lib/libvirtqemuhook` and `/etc/libvirt/hooks/qemu`.
- If doing a manual hotfix, copy the guarded cleanup script to both
  `/usr/bin/cleanup-rbd` and `/usr/local/sbin/cleanup_rbd.sh`.
- Run `systemctl daemon-reload` and verify `cleanup-rbd.timer` after deployment.

## Success Criteria

- FTCTL guard producer and cloud hook/cleanup consumers use the same guard
  contract.
- libvirt qemu hook does not unmap guarded KRBD paths during XCOLO conversion.
- cleanup-rbd timer does not unmap guarded KRBD paths during XCOLO conversion.
- Guard expiry still permits later cleanup of stale mappings.
- Deployment verification distinguishes source, transformed, package-installed,
  and active runtime files.
