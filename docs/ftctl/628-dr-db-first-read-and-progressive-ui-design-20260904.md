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

# DR DB-First Read and Progressive UI Design

## 1. Decision

DR Plan list and detail APIs are read models. They return persisted Cloud DB
state and must not synchronously call a remote Mold, Agent, or FTCTL process.
This rule applies to VMware-to-RBD, RBD-to-RBD, and SharedMountPoint
qcow2-to-qcow2.

Live Agent or FTCTL access is allowed only for an explicit action preflight,
the action dispatch itself, or a bounded background reconciliation job. The
background job persists its result before the UI consumes it.

## 2. Failure

The previous `listDrPlans` and `getDrPlan` flow performed these steps in one
HTTP request:

1. read Plans from the DB;
2. evaluate readiness and action availability for every Plan;
3. resolve a remote source worker;
4. call the source Mold `executeFtctlDrSiteAgentCommand` API;
5. wait for an Agent `CAPABILITIES` answer before returning the page data.

The 31-to-13 SharedMountPoint test measured about 45 seconds for one
`listDrPlans` or `getDrPlan` request. The detail UI called `getDrPlan` twice,
so one page load could take about 90 seconds. Concurrent page reads generated
repeated source-site capability calls and coincided with exhaustion of all 250
source management DB connections.

The same failed capability probe was expanded into
`DR_ACTION_CAPABILITY_UNAVAILABLE`. A newly created `NEW / EXECUTION_READY`
Plan therefore displayed the correct initial-sync guidance while disabling
Full Resync. Page-read capability failure must not disable that action. The DB
eligibility snapshot enables Full Resync, and the live action preflight remains
the authoritative capability gate when the user submits it.

## 3. Read Contract

| Endpoint | Authoritative source | Remote calls allowed |
| --- | --- | --- |
| `listDrPlans` | `dr_plan` and persisted runtime/authority tables | none |
| `getDrPlan` | persisted Plan, Run, Runtime, Cycle and Replica rows | none |
| `getDrProtectionView` | `dr_plan_view_cache`, with DB-only rebuild fallback | none |
| `listDrRuns` | persisted Run and Step rows | none |
| UI runtime polling | persisted protection view | none |
| explicit action preflight | DB snapshot plus minimum action-specific evidence | bounded Agent call |
| action dispatch | validated command path | Agent call |
| background projection refresh | runtime evidence producer | bounded Agent call |

Read responses may report the last persisted check time and stale state. They
must not delay or fail because the source site, a worker Agent, or FTCTL is
unreachable.

## 4. Backend Separation

`DrPlanService` exposes two evaluation paths:

```java
DrPlanActionEvaluation getDatabaseActionEvaluation(long planId);
DrPlanActionEvaluation getActionEvaluation(long planId);
```

The database method evaluates Plan state, active Runs, persisted Runtime,
authority, checkpoint and placement policy only. It never invokes
`DrFtctlActionCapabilityService`.

The live method retains the capability probe for an actual action preflight.
Provider capability mismatch therefore blocks command submission without
turning page rendering into a distributed transaction.

Protection-view cache rebuild is also DB-only. `refreshDrProtectionView`
remains an explicit asynchronous mutation: it may collect runtime evidence in
the background, persists the result, and does not participate in initial page
rendering.

## 5. UI Loading

The list starts `listDrSites` and `listDrPlans` independently. Plan rows render
as soon as the Plan response arrives; site labels hydrate afterward.

The detail view performs one `getDrPlan` and one `getDrProtectionView` request
in parallel. It applies the current DB Plan after the cached snapshot so an
older snapshot cannot regress terminal state. It does not issue the previous
second `getDrPlan` request.

Protection polling reads only the persisted view. An unavailable or stale
runtime producer is represented as data freshness, not as a blocking page
spinner.

## 6. Regression Rules

1. `listDrPlans`, `getDrPlan`, and `getDrProtectionView` must produce zero
   `executeFtctlDrSiteAgentCommand` requests.
2. Source Mold and source Agents may be offline while list and detail pages
   remain readable.
3. An explicit DR action must still perform the existing live capability and
   action-contract validation.
4. Detail loading must issue one `getDrPlan` request per refresh.
5. List loading must not wait for `listDrSites`.
6. Runtime polling must query only Cloud DB-backed APIs.
7. The rule must pass for VMware-to-RBD, RBD-to-RBD, and SharedMountPoint
   qcow2-to-qcow2.
8. A `NEW / EXECUTION_READY / ENABLED` Plan exposes Full Resync even if a
   previous read-time capability probe failed; submission still runs the live
   capability check.

## 7. Acceptance

- list and detail DB APIs complete within one second at p95 in the test
  environment;
- a source-site outage does not prevent Plan navigation;
- server logs show no remote capability or status command caused by page
  reads;
- the action menu is projected from persisted state and an actual action is
  rejected safely if its live preflight discovers a capability mismatch;
- active Run progress continues to update through persisted projection data.

## 8. Implementation and Test Deployment

The implementation was built from commit `9451429776` on a WSL ext4
worktree. The DR module tests passed with 19 tests, zero failures and zero
errors. The production UI build and the changed DR module package both
completed successfully.

The same backend classes and UI dist were deployed to the 13 and 31 test
management servers. The existing aggregate Cloud JAR and `index.html` were
backed up before deployment. Static UI files were overlaid without deleting
the webapp root; `WEB-INF` remained present. On both servers the `mold`
service PID matched the process listening on port 8080 and `/client/`
returned HTTP 200 after restart.

### 8.1 Measured Result

| Check | Before | After |
| --- | --- | --- |
| `listDrPlans` server time | about 45 seconds | 28-36 ms |
| `getDrPlan` server time | about 45 seconds | 27-28 ms |
| `getDrProtectionView` server time | 4-7 ms | 4-9 ms |
| detail `getDrPlan` calls | two sequential calls | one call in parallel with the protection view |
| UI list render | blocked by sites and remote capability | 1.68 seconds from reload to visible Plan row |
| UI detail render | up to about 90 seconds | 3.11 seconds from list selection to visible detail data |
| initial Full Resync | disabled by read-time capability error | visible and enabled for `NEW / EXECUTION_READY` |

The 31 management log confirms that the browser verification generated one
`getDrPlan` and one parallel `getDrProtectionView` request. The source 13
management log contains no `CAPABILITIES` request after 10:17:25, while the
DB-first UI reads were performed from 10:19 through 10:21. The capability
requests at 10:17 were service-start background activity, not page reads.

### 8.2 Source Cluster Health Finding

Before deployment, the 13 management process repeatedly reported Hikari
pool state `total=250, active=250, idle=0` and timed out waiting for DB
connections. At the same time, MySQL itself had only 21 connected threads and
two running threads, and all three routing hosts remained `Up / Enabled`.
This distinguishes management-process connection retention from a database
server or compute-cluster outage.

After the DB-first patch and management restart:

- the 13 server load average was `0.13 / 0.27 / 0.15`;
- 9.2 GiB memory was available and the root filesystem was 63% used;
- `mold` and `mysqld` were active and all routing hosts were `Up / Enabled`;
- MySQL showed 30 connected threads and two running threads;
- no new `Connection is not available` message was recorded;
- `/client/` returned HTTP 200 in about 2 ms locally.

The source cluster therefore had an application-level management outage
amplified by synchronous remote capability checks. It was not a source VM,
compute-host, storage, or MySQL capacity failure. Removing page-read fan-out
and restarting the exhausted management process restored normal operation.
