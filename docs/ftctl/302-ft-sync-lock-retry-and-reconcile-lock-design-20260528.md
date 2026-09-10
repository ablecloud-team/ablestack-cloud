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

# FT sync lock retry and reconcile lock scope design

## Background

FT protection registration can legitimately overlap with the qemu FTCTL periodic `reconcile` timer. In one FT validation run, Cloud had already created the standby VM and volumes, but the registration job failed during cluster/profile sync because qemu returned a retryable lock:

```text
{"command":"config","result":"locked","lock_file":"/run/ablestack-vm-ftctl/lock","holder_command":"reconcile","exit_code":20,"retryable":true}
```

This was not a VM provisioning failure and did not reach the X-COLO transfer path.

## Cloud Behavior

Cloud already retries retryable lock responses for FTCTL runtime actions. The same rule applies to FTCTL sync commands because cluster/profile sync is part of the protection transaction:

- retry `FtctlSyncAnswer` when the answer reports exit code `20`;
- retry when `ftctlResult` is `locked`;
- retry when details or output contains `"result":"locked"`;
- use the existing FTCTL lock retry interval and timeout;
- fail immediately for non-lock sync failures.

## qemu Behavior

qemu FTCTL must reduce timer contention:

- `reconcile` does not take a global command lock;
- reconcile uses VM-specific locks only;
- `reconcile --vm` and timer-driven reconcile use the same VM lock model;
- a reconcile lock miss is recorded as a skipped reconcile cycle, not as a fatal error.

## Ownership Boundaries

- Cloud remains responsible for cloud-managed VM and volume creation.
- qemu FTCTL remains responsible for X-COLO runtime state and replication.
- Cloud sync retry does not duplicate qemu state handling; it only handles transient command admission while qemu is busy.

## Compatibility

This design is shared by FT and the existing HA/DR cloud-managed model. It does not change action semantics, VM lifecycle ownership, or the Mold Agent/qemu command boundary.
