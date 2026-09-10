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

# 209. DR Re-protection IOPS and Async Failure Design

Date: 2026-05-20

## 1. Purpose

During DR re-protection from the adopted replica site back to the recovered site, remote replica resource preparation can fail before qemu FTCTL receives any profile or block-copy command.

The observed failure was:

```text
prepareFtctlDrReplicaResources failed with HTTP 431
The min IOPS must be greater than 0.
```

The source volumes reported `miniops=0` and `maxiops=0`. For CloudStack custom IOPS volume creation, zero is not a valid explicit IOPS value. In FTCTL Cloud-managed provisioning, zero must mean "unspecified" and must not be passed into create-volume APIs.

## 2. Scope

This design applies to Cloud-managed FTCTL provisioning in both directions and both Mold layouts:

- DR with remote Mold
- DR with current Mold
- HA Cloud-managed standby provisioning

The same rule must apply when the current operating VM is an adopted DR replica and is being protected again toward another site.

## 3. Principles

- Cloud owns VM, volume, NIC, network, storage, host placement, and lifecycle API calls.
- qemu FTCTL starts only after Cloud has prepared the target resources and returned a usable disk map.
- qemu FTCTL must not compensate for invalid Cloud volume-create parameters.
- IOPS values greater than zero are preserved.
- IOPS values that are null, zero, or negative are treated as unspecified.
- A submitted async registration job is not equivalent to protection success. The UI must follow the async job result and show failures.

## 4. Backend Design

Cloud normalizes FTCTL IOPS values at all Cloud-managed volume-provisioning boundaries:

1. Source Mold source-volume JSON generation omits `miniops` and `maxiops` when the source value is null or not greater than zero.
2. Target Mold remote replica parsing also normalizes received `miniops` and `maxiops` so older source Mold builds that still send zero do not break provisioning.
3. Local/current-Mold Cloud-managed standby volume creation applies the same normalization before constructing the create-volume command.
4. Positive custom IOPS values remain unchanged and are passed to CloudStack create-volume APIs.

This keeps DR current-Mold and remote-Mold behavior consistent and avoids a regression in HA Cloud-managed provisioning.

## 5. UI Design

The protection configuration dialog continues to close after `registerFtctlProtection` accepts an async job. However, it must register the job with the common async polling mechanism.

Expected behavior:

- On job submission, show the existing started message.
- Poll `queryAsyncJobResult` for the returned job id.
- On success, refresh FTCTL data.
- On failure, show the async job failure notification, including backend `errortext`, and refresh FTCTL data.

This prevents the operator from seeing a silent close/no-progress state when resource preparation failed before block copy started.

## 6. Failure Boundary

If replica resource preparation fails:

- no qemu FTCTL profile should be created;
- no block-copy job should start;
- the Cloud async job must fail with the provisioning error;
- the UI must surface that async failure;
- cleanup should focus on partial Cloud resources, not qemu runtime state, unless a previous profile already existed.

## 7. Verification

Validate with a DR adopted-replica re-protection flow:

1. Use a source VM whose Cloud volume rows have `min_iops=0` and `max_iops=0`.
2. Protect it toward a peer Mold and select target host, storage, and network.
3. Confirm target replica VM and volumes are created without `min IOPS must be greater than 0`.
4. Confirm qemu FTCTL profile is created only after the Cloud resource preparation succeeds.
5. Confirm block copy starts normally.
6. Force a backend registration failure and confirm the UI reports the async job failure instead of only closing the dialog.
