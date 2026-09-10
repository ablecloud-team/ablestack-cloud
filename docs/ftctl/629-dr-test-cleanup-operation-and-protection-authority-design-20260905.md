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

# DR Test Cleanup operation and protection authority

## 1. Failure cause

Cross-Mold `KVM_TO_KVM` Test Cleanup finished successfully on the target site,
but the following projection read both statuses from the target coordinator.
The target profile has no source worker by design, so FTCTL mistakenly resumed
a local replication scheduler. That scheduler failed to resolve the remote
source disk and Cloud exposed the unrelated post-cleanup replication error as
if Test Cleanup had failed.

## 2. AS-IS and TO-BE

| Boundary | AS-IS | TO-BE |
|---|---|---|
| Test Cleanup operation | Target removes the test VM, but its result is mixed with Plan health | Target operation status alone determines cleanup success |
| Plan authority | Active Cleanup Run forces Plan polling to the target coordinator | While active side is `SOURCE`, Plan authority is always read through the source Mold |
| Scheduler transition | Target requires a source worker UUID before recognizing `REMOTE_SOURCE` | Explicit `schedulerTransitionScope=REMOTE_SOURCE` is sufficient and forbids local resume |
| UI outcome | A later replication error makes cleanup appear failed | Show cleanup result and continuous-protection resume state separately |

## 3. Status routing contract

- `PLAN_AUTHORITY` describes durable checkpoint ownership and continuous
  protection. With active side `SOURCE`, it is fetched from the source site.
- `OPERATION` describes finite Test Failover or Test Cleanup work. It is fetched
  from the target coordinator that owns the test artifacts.
- A finite operation can be `SUCCEEDED` while the next protection cycle is
  `RESUMING` or `DEGRADED`. Those outcomes must not overwrite each other.
- Worker UUIDs are transient placement observations. They do not decide site
  authority and are not persisted as prerequisites for future operations.

## 4. UI contract

After a successful Test Cleanup the detail view reports one of:

- `Test cleanup completed / continuous protection resumed`
- `Test cleanup completed / continuous protection is resuming`
- `Test cleanup completed / continuous protection needs attention`

The generic stale-protection ribbon is suppressed for the completed cleanup
outcome. A degraded protection reason remains visible in the dedicated cleanup
summary and in protection diagnostics.

## 5. Regression scope

- Cloud unit test: Cross-Mold Test Cleanup sends one `PLAN_AUTHORITY` query to
  the source and one `OPERATION` query to the target.
- FTCTL smoke test: target-only profiles recognize explicit `REMOTE_SOURCE`;
  profiles without that scope do not infer it from workers.
- UI unit tests: successful cleanup is represented independently for resumed,
  resuming, and degraded protection.
- VMware-to-RBD and same-Mold RBD-to-RBD keep their current provider and routing
  behavior; no transfer, checkpoint, Failover, or Failback algorithm changes.

## 6. Operation creation race

Cloud may poll OPERATION after persisting a Test Failover Run and before the
target FTCTL worker publishes that Run file. A run_not_found response is
therefore non-terminal during the runtime-creation grace period. Cloud must
process this boundary before projecting a test session, so Plan-level stale
cycle errors cannot change a new session from REQUESTED to FAILED.

If an earlier build already produced that exact artifact-free failure, a
correlated current Run with TEST_ARTIFACTS_READY is authoritative repair
evidence. Cloud restores the session to ARTIFACTS_READY, clears the stale
error, and resumes normal Cloud volume/VM materialization without direct DB
repair. Recovery is allowed only when there is no Cloud test VM, no persisted
artifact manifest, and cleanup was not required by the failed projection.

After the creation grace period, a still-missing Run is terminalized as
DR_RUNTIME_NOT_CREATED. This prevents an undispatched operation from
remaining RUNNING indefinitely.

## 7. Restart-safe recovery of a soft-closed test session

An early projection failure may have caused the Run executor to soft-close the
artifact-free test session before FTCTL published the Run file. If later
periodic projection proves that the same active Run owns a completed artifact
set, Cloud restores that exact session instead of requiring database repair.

Restoration is allowed only when all of the following are true:

- the Cloud Run is still non-terminal and projectable;
- OPERATION plan/run identity already matched the request;
- FTCTL reports `run_exists=true`, `TEST_ARTIFACTS_READY`, and a successful
  worker;
- the test artifact set is `CREATED` and contains at least one artifact;
- the removed session belongs to the same Run, has no Cloud test VM or
  artifact manifest, and did not require cleanup when it was closed.

Cloud clears the stale failure, removes the logical deletion marker, restores
the session to `ARTIFACTS_READY`, and resumes normal target materialization.
Historical terminal Runs and sessions that ever owned Cloud resources remain
immutable.

The restore uses one conditional SQL update that explicitly writes
`removed=NULL` together with the restored state, cleanup ownership, and
cleared error fields. A generic `UpdateBuilder` must not be used for this
field: CloudStack marks `removed` as DAO-generated, so binding Java `null`
through the generic update path generates the current timestamp and silently
soft-closes the row again. The conditional update is limited to the same Run,
a currently removed row, and a session that has never owned a Cloud VM or
artifact manifest. Cloud re-reads the active row before enqueueing target
materialization.
The retry contract accepts both the original `FAILED` state and a partially
restored `ARTIFACTS_READY` state, provided the session still owns no Cloud
resource. This makes the explicit restore idempotent across process restarts.

## 8. Multi-host status authority

Operation status may be probed through more than one eligible target host.
`DR_STATUS_IDENTITY_MISMATCH` and other status-boundary failures from a host
that does not own the Run are routing observations, not terminal evidence for
the test session. Cloud retains the last-good session and artifact state,
retries another eligible host, and projects `FAILED` only from a correlated
runtime payload that passed the status-boundary contract. This ordering
prevents a valid `TEST_ARTIFACTS_READY` session from being restored by the
owner and soft-closed again by the next non-owner probe.

## 9. Test deployment contract

Iteration builds compile only the changed disaster-recovery Maven module. The
resulting DR classes are applied to one canonical management JAR, its SHA-256
is fixed, and that exact file is deployed to both the source management server
(13 cluster) and the target management server (31 cluster). Deployment is not
considered complete until both installed JAR hashes match, both management
services are active, and both `/client/` endpoints return HTTP 200. A full
release build or full-cluster package deployment is performed only when it is
explicitly requested.
