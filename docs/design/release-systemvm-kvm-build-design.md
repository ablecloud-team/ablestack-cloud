# Release System VM KVM Build Design

## Purpose

This document defines how the ABLESTACK release workflow must build and
publish the Storage Service capable System VM template together with source
and RPM release artifacts.

The release policy is intentionally fixed:

- Release package mode defaults to `noredist`.
- The bootable System VM template is built for KVM only.
- The published System VM format is compressed QCOW2 (`qcow2.bz2`).
- VMware OVA/VMDK, Xen, OVM, and Hyper-V template exports are out of scope.
- A release must not be published if the System VM image build or validation
  fails.

## Current State

The current release path has four relevant workflows:

- `.github/workflows/release.yml`
- `.github/workflows/dev-release.yml`
- `.github/workflows/branch-dev-release.yml`
- `.github/workflows/rocky97-rpm.yml`

Current behavior:

1. `release.yml` defaults `pack` to `oss`.
2. `release.yml` invokes `rocky97-rpm.yml` and publishes source, RPM, and SRPM
   artifacts.
3. The `templates` input only adds Maven `-Dsystemvm-*` packaging flags. It
   does not invoke the Packer appliance build.
4. `tools/appliance/build.sh` is the actual bootable System VM image builder,
   but no release workflow calls it.
5. The validation build uses `-Dnoredist`, while the published RPM build
   defaults to `oss`. The two paths therefore do not have the same package
   policy.
6. `tools/appliance/build.sh` currently invokes only `kvm_export` from its main
   path. VMware and other export functions are not invoked.

## Target State

| Concern | As-Is | To-Be |
| --- | --- | --- |
| Release package mode | `oss` default | `noredist` default in every release entry point |
| System VM image | Not built | KVM System VM built for every release |
| Image format | None | One `qcow2.bz2` artifact |
| VMware image | Export function exists but is not called | Remains disabled and is rejected from release artifacts |
| Build ref | RPM uses prepared release commit | RPM and System VM both use the same prepared release commit |
| Validation | Maven/RPM validation only | QCOW2, image contents, and compressed round-trip validation |
| Publication | Source and RPM artifacts | Source, RPM, SRPM, and KVM System VM template |
| Failure policy | System VM is outside the release gate | System VM failure blocks release publication |

## Workflow Changes

### 1. `.github/workflows/release.yml`

Change the release input contract:

```yaml
pack:
  description: Package variant
  required: false
  default: noredist
  type: choice
  options:
    - noredist
    - oss
```

The default and first visible choice must both be `noredist`. `oss` remains an
explicit exception for operators who intentionally request an OSS-only build.

Remove the generic operator-facing `templates` input from the production
release workflow. It is ambiguous because it controls Maven package flags, not
Packer image generation. The release workflow must pass a fixed value of
`kvm` to the RPM workflow:

```yaml
templates: kvm
```

Add a metadata output for the System VM artifact name:

```yaml
systemvm_artifact_name=systemvm-kvm-${{ inputs.release_id }}
```

Add an unconditional reusable job after the release source commit is prepared:

```yaml
build-systemvm-kvm:
  needs:
    - metadata
    - prepare-source-release
    - validate-build
  uses: ./.github/workflows/systemvm-kvm.yml
  with:
    ref: ${{ needs.prepare-source-release.outputs.source_commit_sha }}
    version: ${{ needs.metadata.outputs.package_version }}
    build_number: ${{ github.run_number }}
    artifact_name: ${{ needs.metadata.outputs.systemvm_artifact_name }}
```

`publish-release` must depend on both `build-rpm` and
`build-systemvm-kvm`. It must download the System VM artifact into
`download/systemvm`, copy it into `release-assets`, and regenerate the global
`SHA256SUMS` file after all artifacts are assembled.

The release summary must include:

- Package mode: `noredist` or the explicitly selected exception.
- System VM architecture: `x86_64`.
- System VM hypervisor: `KVM`.
- System VM format: `qcow2.bz2`.
- Source commit used by both RPM and System VM jobs.

### 2. `.github/workflows/dev-release.yml`

Keep development releases consistent with production releases:

- Change the `pack` default and fallback to `noredist`.
- Pass the fixed RPM template selector `kvm`.
- Invoke the same `systemvm-kvm.yml` reusable workflow.
- Include the KVM System VM artifact in the development release assets.
- Make System VM build failure block development release publication.

Using the same reusable workflow prevents production and development release
images from drifting.

### 3. `.github/workflows/rocky97-rpm.yml`

Change all package-mode defaults and expression fallbacks:

```yaml
default: noredist
BUILD_PACK: ${{ inputs.pack || github.event.inputs.pack || 'noredist' }}
```

This applies to:

- `workflow_dispatch.inputs.pack`
- `workflow_call.inputs.pack`
- the `BUILD_PACK` environment fallback

Keep the reusable `templates` input for standalone RPM packaging compatibility,
but the release workflows must pass the fixed value `kvm`. This input must not
be presented as a bootable System VM image selector.

### 4. `.github/workflows/branch-dev-release.yml`

Branch test releases must build the operator-provided `source_ref` without
switching to the base product branch. They follow the same artifact contract as
the normal development release:

- Default `pack` to `noredist`.
- Remove the free-form `templates` input and pass `templates: kvm` to RPM
  packaging.
- Invoke `systemvm-kvm.yml` with the exact commit resolved from `source_ref`.
- Include the KVM System VM image, manifest, and checksums in the branch
  development release.
- Block publication when either the RPM build or System VM build fails.

The branch test release tag remains isolated by a sanitized `source_ref`, so it
does not replace the normal product-line development release.

### 5. New `.github/workflows/systemvm-kvm.yml`

Create a reusable workflow with these inputs:

| Input | Type | Required | Purpose |
| --- | --- | --- | --- |
| `ref` | string | yes | Prepared release commit SHA |
| `version` | string | yes | Product version embedded in the image name |
| `build_number` | string | yes | Release workflow run/build identifier |
| `artifact_name` | string | yes | GitHub Actions artifact name |

The job runs on `ubuntu-24.04`, uses Bash, and has a minimum timeout of 180
minutes. The Packer definition already allows an SSH wait of up to 120 minutes,
so the workflow must not terminate the build during a normal long guest install.

Required steps:

1. Check out the exact `ref` with full source history.
2. Install pinned Packer and host packages:
   - `qemu-system-x86`
   - `qemu-utils`
   - `bzip2`
   - `unzip`
   - `curl`
   - `jq`
   - `sharutils`
3. Configure KVM access for the runner and load NBD support:
   - Verify `/dev/kvm` exists and is accessible.
   - Run `modprobe nbd max_part=8`.
   - Verify the validation NBD device can be created.
   - Fail early with a precise message if the runner cannot provide KVM/NBD.
4. Run the builder with explicit positional values:

   ```bash
   cd tools/appliance
   sudo -E bash ./build.sh \
     systemvmtemplate \
     "${SYSTEMVM_VERSION}" \
     x86_64 \
     "${SYSTEMVM_BUILD_NUMBER}"
   ```

5. Require exactly one compressed KVM image matching:

   ```text
   tools/appliance/dist/systemvmtemplate-*-x86_64-kvm-*.qcow2.bz2
   ```

6. Reject unexpected release image formats:
   - `*.ova`
   - `*.vmdk`
   - `*-xen.*`
   - `*-ovm.*`
   - `*-hyperv.*`
7. Validate the uncompressed QCOW2 and compressed round trip. The existing
   `tools/appliance/build.sh` already calls:
   - `qemu-img check`
   - `tools/appliance/scripts/validate_systemvm_image.sh`
   - decompression and byte comparison
8. Produce a `SYSTEMVM-SHA256SUMS` file and a build manifest containing:
   - commit SHA
   - version
   - build number
   - architecture
   - hypervisor
   - image format
   - Packer version
   - QEMU version
9. Upload only:
   - the KVM `qcow2.bz2`
   - `SYSTEMVM-SHA256SUMS`
   - the build manifest
   - the Packer/build log

Use `if-no-files-found: error`. Do not upload the temporary uncompressed QCOW2
as a release artifact.

## Build and Publication Sequence

```text
metadata
  -> prepare-source-release
       -> validate-build
       -> validate-ui
            -> build-rpm (noredist, KVM package flags)
            -> build-systemvm-kvm (Packer/QEMU)
                 -> publish-release
```

`build-rpm` and `build-systemvm-kvm` may run in parallel after their validation
dependencies are satisfied. `publish-release` starts only when both jobs
succeed.

## Artifact Contract

The release must contain one System VM image with a deterministic product and
architecture prefix:

```text
systemvmtemplate-<version>.<build>-x86_64-kvm-<build-date>.qcow2.bz2
```

The timestamp suffix currently generated by `tools/appliance/build.sh` may
remain. Consumers must use the release manifest rather than parsing the
timestamp as a version.

The workflow must verify that no VMware OVA or VMDK is present before the
release is published. The `noredist` package mode enables VMware-related
management-server resources; it does not imply that a VMware System VM image
must be generated.

## Security and Reproducibility

- Pin the Packer download version and verify its SHA256 checksum.
- Build RPM and System VM artifacts from the same prepared commit SHA.
- Do not persist release credentials or signing keys in build artifacts.
- Preserve full Packer and validation logs for troubleshooting.
- Do not publish an image if any content check reports a missing or invalid
  runtime binary.
- Keep GitHub Release generation fail-closed: an incomplete release is not
  published as successful.

## Files In Scope

| File | Change |
| --- | --- |
| `.github/workflows/release.yml` | Default to `noredist`, fix KVM packaging selector, add System VM job and release asset |
| `.github/workflows/dev-release.yml` | Apply the same defaults and System VM release path |
| `.github/workflows/rocky97-rpm.yml` | Change dispatch/call/fallback defaults to `noredist` |
| `.github/workflows/systemvm-kvm.yml` | New reusable KVM System VM build workflow |
| `tools/appliance/README.md` | Align documentation with the currently supported KVM-only export path |

No VMware OVA build implementation and no appliance export-format expansion
are included in this design.

## Acceptance Criteria

1. Starting `release.yml` without overriding `pack` produces noredist RPMs.
2. The RPM summary reports `Pack: noredist`.
3. The same release commit SHA is used by source, RPM, and System VM jobs.
4. Exactly one KVM `qcow2.bz2` System VM image is produced.
5. `qemu-img`, image-content, and compression round-trip validation pass.
6. No OVA, VMDK, Xen, OVM, or Hyper-V image is present in release assets.
7. System VM build or validation failure prevents GitHub Release publication.
8. The final GitHub Release includes source, RPM, SRPM, KVM System VM image,
   checksums, and build metadata.
