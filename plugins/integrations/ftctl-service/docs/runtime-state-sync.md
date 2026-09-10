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

# FTCTL Runtime State Sync

FTCTL runtime state is owned by the qemu-side controller, not by Cloud
management. The server-side `ablestack_vm_ftctl` process is responsible for
inspecting libvirt, QMP block jobs, local state files, and local event logs.
Cloud management must not duplicate that logic or directly perform libvirt
operations.

## State Flow

1. `ablestack_vm_ftctl reconcile` runs on the compute host and records runtime
   state under `/run/ablestack-vm-ftctl/state`.
2. The mold agent exposes that state through `FtctlStatusCommand`, which executes
   the qemu-side controller's read-only status path.
3. Cloud management periodically scans active `ftctl_protection` rows and sends
   `FtctlStatusCommand` through the agent to the primary VM execution host.
4. Cloud persists the returned runtime snapshot into `ftctl_protection` and
   `vm_instance_details`.
5. The UI reads Cloud DB/API state only. UI polling must not be required to make
   runtime state advance.

## Operational Contract

- Cloud may send agent commands that read server-side FTCTL state.
- Cloud must not inspect libvirt, QMP, block jobs, RBD maps, or FTCTL host files
  directly.
- State transitions such as `syncing/copying` to `protected/mirroring` are driven
  by the qemu-side controller and propagated to Cloud by the runtime sync timer.
- `getFtctlProtection` returns the persisted Cloud view. Runtime refresh remains
  a diagnostic path, not the normal UI update mechanism.

## Configuration

- `cloud.ftctl.runtime.state.sync.enabled`: enables the background sync loop.
- `cloud.ftctl.runtime.state.sync.interval`: sync interval in seconds. Values
  below five seconds are clamped to five seconds.
