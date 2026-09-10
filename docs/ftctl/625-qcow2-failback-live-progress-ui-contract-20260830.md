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

# qcow2 failback live progress UI contract

## Problem

The failback action can finish its synchronous Cloud dispatch steps before the
FTCTL data worker finishes copying. The operation row then exposes a backend
workflow value of 100 percent even though the authoritative transfer journal is
still active. A version 1 qcow2 journal is also rejected by the UI version 2
guard, so users see neither bytes nor ETA.

## TO-BE

- FTCTL SharedMountPoint qcow2 workers publish transfer schema version 2 with
  plan, run, and cycle ownership.
- Cloud continues to expose the operation-owned live sample on the Run.
- For non-terminal `SYNC`, `RECOVER_SYNC`, and `FAILBACK` operations, a valid
  live transfer sample prevents a stale 100-percent workflow value from being
  displayed as completion.
- The UI maps the transfer into the 70-95 percent workflow range. Only a
  terminal Run may display completed 100 percent.
- The operation table and expanded progress panel use the same shared progress
  resolver. A non-terminal backend value of 100 is reduced to the current
  lifecycle floor until a valid transfer sample arrives, then follows the
  70-95 transfer range.
- The transfer panel shows bytes, throughput, ETA, disk position, and transfer
  mode using the same sample.
- After a Run becomes terminal, the UI applies the cached protection snapshot
  first and the live Plan action contract last. A stale snapshot must not keep
  `cancelRun` visible or suppress the next valid action.
- Protection View snapshot version 4 and later is authoritative for active-run
  ownership. When `activeRun` is empty, the UI must not promote an older
  non-terminal operation-history row back into the current action contract.
  Live history may only confirm or terminalize the active Run named by the
  snapshot.

## Regression gate

- Unit test an active failback with backend workflow 100 and transfer 11.
- Unit test a dispatching Run with backend workflow 100 and require 15 percent
  in both the table and expanded panel.
- Retain the existing monotonic SYNC progress tests.
- Run FTCTL qcow2 SharedMountPoint smoke tests and Cloud UI unit tests.
- Validate the deployed UI from a real failback before marking the menu PASS.
- Verify that terminal failback removes `cancelRun` and restores the source-side
  actions without a page reload.
- Verify that an old `ACCEPTED` history row cannot revive `cancelRun` when the
  authoritative snapshot has no active Run.
