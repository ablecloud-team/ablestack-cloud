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

# Network Namespace installation runtime

`cloudstack-network-runtime` owns the host wrapper and requires all supported
service dependencies, including HAProxy, Apache HTTP Server and radvd. Both
Cloud Agent RPM specifications require this package. The management RPM keeps
the proxy and documentation but excludes the host wrapper from its file list.
Management also requires sshpass for the existing password authentication mode.

## Package build (no Maven or UI build)

Run in a Linux/WSL ext4 checkout:

```bash
bash packaging/network-runtime/build.sh
bash packaging/network-runtime/test.sh dist/network-runtime-rpmbuild/RPMS/noarch/cloudstack-network-runtime-1.0.0-2.el9.noarch.rpm
```

The regular `packaging/package.sh` RPM path builds this runtime into the same
`dist/rpmbuild/RPMS` artifact tree. Publish it with Agent RPMs. A new Agent install
or package-manager upgrade resolves this mandatory dependency. Copying JARs does
not process RPM dependencies: existing installations using module-only updates
must install the runtime once through their package manager.

## Offline installations

The builder needs `dnf-plugins-core` and `createrepo_c`. Build on the matching EL
major version and select repositories matching the target minor version. Do not
silently combine a newer OS repository with an older installation image.

```bash
bash packaging/network-runtime/offline-bundle.sh runtime.rpm new-bundle-directory [dnf repository options...]
```

The result includes all dependency RPMs, repository metadata and SHA256SUMS.
Include this repository in the installation medium and publish the runtime with
the Agent. Use the runtime RPM as the transaction target, not `*.rpm`: installing
every bundled package could unnecessarily upgrade existing OS packages.
Preserve vendor signature verification; production runtime RPMs must be signed
with the installation repository's trusted signing key.

## Preparation, not activation

No runtime scriptlet starts services, registers networks or changes VM settings.
The extension later starts services inside its namespace. Existing host services
are never disabled by this package. Run `cloudstack-network-runtime-check` to
check binaries, wrapper syntax and Apache modules without changing networking.
This check does not claim packet-level or application-level functionality.

The target product installer/ISO must include the runtime and dependency
repository. The Cloud repository supplies RPM integration and an offline bundle;
it does not publish or modify an external installer image automatically.

Increment the runtime version/release whenever the wrapper or packaging changes.
Keep the minimum Agent dependency aligned when newer runtime behavior is needed.
