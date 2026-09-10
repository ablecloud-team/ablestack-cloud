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

# Remote KVM Source Worker Identity Resolution

> Superseded on 2026-09-03 by
> `627-dr-dynamic-placement-and-transparent-worker-scheduling-design-20260903.md`.
> The rules below that persist and reuse a VM host UUID as worker authority are
> invalid. They are retained only as failure history and must not guide new
> implementation.

## Problem

An ABLESTACK-to-ABLESTACK Plan may keep the source VM as an external
reference. In that case `source_worker_host_id` is intentionally local-site
only and can be null. A Plan created or refreshed while the source VM is
stopped can also omit `sourceHostUuid` from `mapping_json`.

The runtime status, capability, and action paths previously parsed that static
mapping independently. A missing value therefore changed an otherwise durable
Plan into `DR_ENGINE_UNAVAILABLE`, disabled every UI recovery action, and hid
the fact that the source VM could be started on a valid remote worker.

## Superseded Contract

1. Remote KVM worker identity has one resolver owned by
   `DrRemoteAgentClient`.
2. The resolver checked current Mold VM inventory first. Treating this
   observation as worker authority was incorrect because placement can change
   through live migration.
3. Persisting the resolved UUID into the Plan root, source, or source hardware
   mapping is prohibited. An observed host may be kept only as timestamped
   diagnostic evidence.
4. A stopped VM, unavailable source, or missing host must not globally disable
   DR actions. Action-aware capability and worker scheduling follow design 627.
5. Runtime projection, action dispatch, and capability calculation use this
   same resolver. They must not implement independent JSON parsing.
6. The resolver is active only for remote `KVM_TO_KVM` Plans. VMware-to-RBD and
   local RBD-to-RBD behavior remains unchanged.

## UI Acceptance

A stopped SharedMountPoint VM must remain synchronizable without a host ID.
Disaster Failover, Test Failover, cleanup, and release must remain available
from target capability and durable recovery evidence without source access.
Live migration must cause command-time re-resolution, not Plan mutation.
