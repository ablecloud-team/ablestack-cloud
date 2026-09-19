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

# HA-WIN-01~15 Full UI Chain Result

- Date: 2026-05-08
- Target VM: `ha-w22-01`
- Target VM UUID: `9088492d-ba30-4239-b87c-10ece5e1dd13`
- Primary domain: `i-2-338-VM`
- Primary host: `10.10.22.3`
- Secondary/standby host: `10.10.22.2`
- Standby domain: `i-2-347-VM`
- Data path: Primary RBD -> Secondary local qcow2, `remote-nbd`
- Execution policy: UI actions for register, pause/resume, failover, primary stop, fence confirm, failback, and release. API/DB/host checks were used only for verification.

## Verdict

PASS.

The full HA-WIN chain was executed from a clean state for `ha-w22-01`: protection registration, initial sync, pause/resume, manual-fence failover, guest verification, failback, guest verification, protection release, and residual cleanup verification.

Final state:

- Active FTCTL protection rows for primary VM `338`: `0`
- Active FTCTL protection volume rows: `0`
- Active standby VM rows for `ha-w22-01-standby`: `0`
- Active standby volume rows: `0`
- Primary FTCTL VM details: `0`
- Primary VM: `Running` on host `3`
- Primary MAC/IP: `02:01:00:cc:00:81` / `10.10.254.59`
- Host residuals: no matching `qemu-nbd`, FTCTL runtime config, or blockjob residue on `10.10.22.1`, `10.10.22.2`, `10.10.22.3`

## Step Results

| Step | Result | Summary |
| --- | --- | --- |
| HA-WIN-00-PREFLIGHT | PASS | Packages, timers, API readiness verified. |
| HA-WIN-01-GUEST-BASELINE | PASS | Primary QGA, MAC, and IP baseline verified. |
| HA-WIN-02-CLEANUP-BEFORE | PASS | No active protection or host residue before test. |
| HA-WIN-03-REGISTER-UI | PASS | Protection registered through UI. Standby domain: `i-2-347-VM`. |
| HA-WIN-04-REGISTER-API-DB | PASS | Protection rows and DB/API state created correctly. |
| HA-WIN-05-RUNTIME-TRANSPORT | PASS | Runtime transport reached `protected/mirroring` with 100% block copy progress. |
| HA-WIN-06-UI-DETAIL | PASS | Fault protection tab rendered registration details and runtime status. |
| HA-WIN-07-PAUSE-RESUME | PASS | Pause and resume actions completed through UI. |
| HA-WIN-08-FAILOVER-ACTION | PASS | Failover action entered manual fence flow. Primary VM was stopped through UI, then `펜스 확인` completed through UI. |
| HA-WIN-09-FAILOVER-GUEST | PASS | Standby VM booted and QGA reported expected MAC/IP. |
| HA-WIN-10-FAILOVER-CONSISTENCY | PASS | Protection state was consistent as `failed_over/failed_over/secondary/manual-fenced`. |
| HA-WIN-11-FAILBACK-ACTION | PASS | Failback completed through UI. |
| HA-WIN-12-FAILBACK-GUEST | PASS | Primary VM booted and QGA reported expected MAC/IP. |
| HA-WIN-13-FAILBACK-CONSISTENCY | PASS | Protection state returned to `protected/mirroring/primary/clear`. |
| HA-WIN-14-CLEANUP-AFTER | PASS | Protection release completed through UI. Release output included remote NBD cleanup evidence. |
| HA-WIN-15-RESIDUAL-CHECK | PASS | DB and host-level residual checks passed. |

## Manual Fence Observation

During HA-WIN-08, `confirmFtctlFence` correctly rejected confirmation while the primary VM was still `Running`:

```text
FTCTL manual fence confirmation requires primary VM ... to be Stopped, current state is Running
```

The test then followed the manual fencing precondition through the UI:

1. Stop the primary VM from the Cloud UI `작업 > 정지`.
2. Open the fault protection tab.
3. Click `펜스 확인`.
4. Confirm the standby VM starts and becomes active.

This behavior matches the intended split-brain prevention rule: fence confirmation is not accepted until the primary VM is stopped in Cloud.

## Cleanup Evidence

Release response included:

```json
{
  "command": "unprotect",
  "result": "ok",
  "vm": "i-2-338-VM",
  "block_jobs_cancelled": 2,
  "rbd_unmapped": 0,
  "remote_nbd_required": true,
  "remote_nbd_released": true
}
```

Residual DB verification:

```text
active_protection          0
active_protection_volumes  0
active_standby_vms         0
active_standby_volumes     0
primary_ftctl_details      0
```

## Evidence

- Test work directory: `/home/ablecloud/work/ha-win-full-ui-chain-20260508`
- Final result: `/home/ablecloud/work/ha-win-full-ui-chain-20260508/result.json`
- Progress log: `/home/ablecloud/work/ha-win-full-ui-chain-20260508/evidence/progress.json`
- UI screenshots: `/home/ablecloud/work/ha-win-full-ui-chain-20260508/screens/`
- API response evidence: `/home/ablecloud/work/ha-win-full-ui-chain-20260508/evidence/ui-api-responses*.json`
- Residual DB evidence: `/home/ablecloud/work/ha-win-full-ui-chain-20260508/evidence/15-residual-db.txt`
- Residual host evidence: `/home/ablecloud/work/ha-win-full-ui-chain-20260508/evidence/15-residual-host.txt`
