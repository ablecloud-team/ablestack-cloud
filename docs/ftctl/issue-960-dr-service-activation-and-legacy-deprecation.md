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

# Issue #960: DR service activation and legacy cluster deprecation

## Contract

The cloud.dr.service.enabled Boolean enables current DR Site/Plan APIs and their existing schedulers. Saving it must not query local storage or require RBD. Storage, worker and provider readiness remains specific to each DR action.

Legacy DisasterRecoveryCluster APIs are deprecated and never registered, even when this setting is true or RBD exists. All 22 legacy service entrypoints reject before accessing commands, DAOs, files or remote clients with LEGACY_DR_CLUSTER_DEPRECATED. Existing VM protection helpers and data are retained. The shared service bean and current Dr* APIs remain available.

The key, false default and non-dynamic/restart behavior remain unchanged. No schema, qemu profile or UI bundle change is needed. The current UI routes already contain only DrSite and DrPlan.

## Implementation

- Remove validateDisasterRecoveryValues and its invocation from ConfigurationManagerImpl; preserve ordinary Boolean, permission, scope and range validation.
- Restrict DisasterRecoveryClusterServiceImpl.getCommands to the current API allowlist.
- Reject direct and queued legacy service calls independently of the enable flag.
- Update ConfigKey description to clarify current services and legacy unavailability.

## Verification

WSL ext4 module tests: ConfigurationManagerImplTest 138 passing; disaster-recovery module 386 passing, including new command registration and all legacy entrypoint tests. Both changed modules package successfully; no full Cloud build performed.

Configuration tests assert storage DAO is never queried for true/false and invalid Boolean/scope validation remains enforced. Legacy tests use no injected dependencies and assert both enabled/disabled direct calls fail with the deprecation error before any dependency is touched.

## Deployment contract

Target: 10.10.31.10. Existing management uses a shaded cloudstack JAR. Deploy only the changed class families from the two built modules, after backing up the active JAR. Verify all other archive entries are unchanged, class file version matches, and resulting ZIP is intact. Preserve WEB-INF and all static assets.

After restart, validate /client HTTP 200, UI login and configuration save, current APIs present, legacy APIs absent/rejected, existing VM inventory and plans preserved. No DR data operations are required for this configuration fix. Detailed run evidence is maintained separately during validation.

## 2026-09-09 cluster 31 validation

- Management: 10.10.31.10, SSH 22, mold service, management HTTP /client/. Credentials are not included.
- Preflight: no RBD pools; 2 SharedMountPoint and 3 Filesystem pools. One management server; 3 compute hosts Up. VM inventory: 16 Running, 8 Stopped, 1 Expunging. Five active DR plans READY; no active DR Run at preflight.
- Tests: cloud-server ConfigurationManagerImplTest 138/138; entire disaster-recovery module 386/386. Changed-module package build successful.
- Installed layout: shaded cloudstack-4.23.0.0-Mold.Europa-202609080713.jar. Overlay contained 31 Java 11 class entries; 29 differed from installed bytes. All 202,804 entry names and every entry outside the overlay were preserved; ZIP integrity passed.
- Original JAR SHA256: b47a7ced57e72884b3ae1dd77b69b3aca39a0c04d2facbb66e98e2eca4166760.
- Patched JAR SHA256: a595b209db4d4d141449d118aff2cc46e1db7152c8d3fda0df1bc4c39e4e22f8.
- Backup/manifest: /root/issue960-20260909/ on management. WEB-INF and static UI assets preserved.
- First restart: HTTP 200, current APIs registered, no legacy cluster APIs in listApis; direct getDisasterRecoveryClusterList returned HTTP/API 432 Unknown API command.
- Current API smoke: listDrSites=4, listDrPlans=5, disasterRecoveryEnabled=true. Updated description confirms legacy APIs remain unavailable.
- UI: authenticated through the real login form, searched cloud.dr.service.enabled, saved false then true through the switch and check button. Both saves displayed the successful update toast and management restart instruction; no RBD error. Final switch true.
- Runtime: DrProjectionScheduler issued FtctlDrStatusCommand and received OK after deployment. Host/VM/Plan inventory unchanged.
- Existing environment alerts (extension path readiness/OOBM) predate deployment; not asserted fixed by #960.
- This is a changed-module deployment into the current shaded package, not a rebuilt distribution RPM. A future full release must include the source commit; package reinstall would otherwise replace the patch.

- Final restart after UI saves: reauthenticated in UI and confirmed switch=true and updated description. API smoke repeated successfully (1084 APIs, no legacy APIs, Site=4, Plan=5). mold active, /client HTTP 200, WEB-INF preserved, patched JAR checksum unchanged; VM counts, five READY plans and three Up hosts match preflight.
