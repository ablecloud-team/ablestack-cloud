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

# Cross-Hypervisor DR Failback Success Metadata Storage Convergence Design

Date: 2026-08-11

## 1. Purpose

Ensure that a successful Failback is terminally consistent in the Cloud DB as
well as in FTCTL, Run history, API responses, and the UI. A completed session
must not retain `failure_phase`, `failed_component`, `error_code`, or
`error_message` from an earlier runtime sample.

## 2. Verified Failure

The live plan `ef73f5f3-9740-4bbd-8c9a-74a972e5f19f` completed Sync,
Failover, and Failback successfully. FTCTL reported an empty failure component,
the terminal details JSON contained no failure keys, and the API correctly
omitted failure fields. The `dr_failback_session` row nevertheless retained
`failed_component=ftctl`.

Two write paths permit this mismatch:

1. `completeLifecycle` relies on ordinary entity setters for NULL assignment
   instead of an explicit nullable `UpdateBuilder` update.
2. `reconcile` updates runtime evidence before it checks whether the persisted
   session is already terminal, allowing a late READY sample or a stale caller
   to retain failure metadata.

## 3. Storage Contract

For `state=COMPLETED`:

- `failure_phase IS NULL`;
- `failed_component IS NULL`;
- `error_code IS NULL`;
- `error_message IS NULL`;
- the terminal Run is `SUCCEEDED` with no errors;
- the persisted terminal details JSON contains no failure keys.

Violation of this contract prevents terminal completion from being committed.

## 4. Code-Level Design

### 4.1 DAO

Add `DrFailbackSessionDao.clearFailureMetadata(long sessionId)`. The DAO uses a
fresh update entity plus `UpdateBuilder.set(..., null)` for all four nullable
columns. This guarantees SQL NULL assignment rather than depending on ordinary
entity dirty tracking.

### 4.2 Lifecycle Service

`reconcile` checks terminal state immediately after locating the session and
before `updateRuntimeEvidence`. A completed session is sanitized through the
DAO and returned without consuming late runtime failure metadata.

`completeLifecycle` performs these operations in one transaction:

1. persist the completed session, successful Run, READY plan, and replica;
2. explicitly clear all four failure columns through the DAO;
3. re-read the session;
4. throw and roll back if any failure metadata remains.

The in-memory object is also cleared so the caller cannot return stale fields.

### 4.3 API And UI

No additional API or UI code is required. Snapshot version 11 already omits
failure-only fields for a successful session. This change makes the storage
source agree with that projection.

### 4.4 Agent And FTCTL

No Agent or FTCTL change is required. FTCTL already publishes empty failure
metadata after successful completion and remains the authority for runtime
state.

### 4.5 Database

No schema migration is required. Test-environment cleanup is restricted to the
verified plan and rows that satisfy all of the following:

- `state='COMPLETED'`;
- `error_code` and `error_message` are NULL or blank;
- one or more failure metadata columns are nonblank.

## 5. Test Design

Backend tests cover:

- a completed session followed by a late runtime sample containing stale
  failure metadata;
- terminal guard execution before runtime evidence update;
- explicit DAO clear invocation;
- local returned object sanitization;
- terminal runtime JSON without failure keys.

Deployment verification covers:

- module tests and package build from the WSL ext4 clone;
- changed-class-only Cloud deployment;
- bounded cleanup of existing completed rows;
- API, DB, FTCTL, Run history, VM power state, and service health agreement.

## 6. Retest Gate

The operator may run Failover and Failback only after:

1. the plan is READY and authority is SOURCE;
2. the scheduler is RUNNING/HEALTHY and replication is IDLE;
3. latest completed cycle metrics match between FTCTL, DB, and API;
4. no active Run exists;
5. the latest completed Failback session has no failure metadata.

## 7. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
|---|---|---|
| DB NULL update | Ordinary setter can leave prior text in storage | Explicit `UpdateBuilder` writes SQL NULL |
| Late projection | Runtime evidence is consumed before terminal check | Terminal guard runs before evidence update |
| Completion assertion | Success is committed without storage read-back | Failure metadata is re-read and asserted in transaction |
| Existing rows | Successful sessions can retain `ftctl` | Only verified successful rows are cleaned |
| API/UI | Failure fields are hidden by projection | Projection and durable DB source agree |
| Engine | Correct success evidence already exists | No Agent/FTCTL behavior change |
