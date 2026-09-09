# #961: boot compatibility and target tuning

Source observation identity is separate from target boot compatibility. DrHardwareCompatibilityPolicy selects boot/security/controller details rather than treating every copied source detail as an immutable target contract. Materialization reuse validates first and never overwrites/removes existing target details based on a legacy copy manifest. Only diagnostic source fingerprint metadata advances after successful validation.

New materialization applies resolved target io.policy and iothreads after initial source copying. Agent currently interprets iothreads by key presence: explicit false is serialized by omitting the key. Existing false strings are preserved and diagnostic events report effective key-presence semantics rather than claiming false disables the Agent. Boolean parsing rejects unsupported strings. I/O policies use the shared ApiConstants enum. Transient VNC/message/runtime details are excluded from initial copying.

Mismatch of boot/security/controller requirements still fails. Performance differences do not. Existing target tuning differences are recorded as WARN DR events with requested/stored/effective values and are not Run errors. Existing ownership, generation, artifact and disk-map checks are unchanged.

## Runtime compatibility

Paired qemu change: source_boot_hardware_version=1 plus allowlisted source_boot_hardware JSON extracted from the current profile, including old profiles. Cloud normalizes and compares this evidence; unsupported evidence versions fail. Existing v2 full fingerprints retain their exact definition and remain fallback when boot evidence is absent. No placement information is part of boot comparison.

Refinement from the issue design: use inspectable boot field evidence, not a second hash with two implementations. No destructive profile/manifest migration or schema upgrade is required. Profile evidence is not target XML or guest boot proof. Existing security-state transfer/guestprep and target-validity checks remain necessary; this change does not add TPM/NVRAM migration support or compute offering selection.

## Validation

Cloud DR module package in WSL ext4: 395 tests passing, including nine true/false/missing tuning combinations, firmware/security/controller negatives, source-target policy precedence, explicit false Agent serialization, target preservation and rejection before DAO mutation, and runtime fingerprint drift with equal/different boot evidence.

qemu branch codex/fix-961-boot-compatibility, commit 292c35f. GitHub Actions 34356201076 succeeded with boot evidence smoke and existing full lifecycle/release tombstone/action regression gates. Test RPM SHA256: 732123ea17f21073450a8efb41a2568f282d5198cdd29c2d9e098e1e9bd63b95.

Cluster evidence below distinguishes automated regression coverage from the physical UI test scope.

## UI validation finding: nested JSON serialization

The reason text containing `io.policy=threads` exposed an existing ApiResponseSerializer.unescape defect: Unicode escapes in nested JSON strings were decoded twice, yielding invalid outer JSON with a backslash followed by equals. listDrRuns and embedded plan history became unreadable. Preserve escaped literal sequences and JSON-special/control characters while unescaping ordinary Unicode characters. Three server tests cover nested equals, literal Unicode sequences and escaped controls/quotes/backslashes. The server module package passed. Historical Run data remains intact.

Initial physical test uses u26-base DR Plan on source site 13 and target site 31. The live worker was resolved to 10.10.13.2 (SSH 10022), not inferred from old profile copies on 31.1/13.1. Current full-seed checkpoint 2026-09-09 22:29:26 and durable target 22:30:13 were observed there. Target VM 225 retains missing iothreads and io.policy=threads; Plan requests true/io_uring. SYNC Run f2edefe7-7980-47b5-89f0-8d00580d8f44 succeeded and emitted TARGET_TUNING_DIFFERENCE WARN. Subsequent UI results follow below.


## 2026-09-09 physical UI validation (KST)

Source site 13 -> target site 31, SharedMountPoint qcow2 -> qcow2; Plan 9a20b190-d202-4b66-9358-b509756f9751. Two disks: 50 GiB root and 100 GiB data. Actions were performed through Mold UI; DB, runtime and libvirt/QGA reads corroborated results. No Run result or Plan state was repaired in DB.

| Case | Evidence | Result |
| --- | --- | --- |
| Existing target differs from Plan tuning | VM 225 iothreads absent, io.policy=threads while Plan requests true/io_uring; Run f2edefe7-7980-47b5-89f0-8d00580d8f44; full seed durable 22:30:13 | PASS, difference preserved with WARN |
| Required boot mismatch | UI changes UEFI LEGACY -> SECURE; Run f6f11655-161d-48de-8066-3da0d4b9a875 fails TARGET_BOOT_CONTRACT_MISMATCH, field=uefi expected=legacy actual=secure; SECURE remains unchanged | PASS negative, no silent overwrite |
| Required value restored | UI restores LEGACY; Run 21cacf29-3c6d-4882-948b-3d932ce99d1e succeeds with tuning still different; resumed replication durable 22:42:51 | PASS |
| Test failover | Run 38381466-161a-4e7f-8d00-abde1009cba9 creates VM 239 / i-2-239-VM from checkpoint 1219 on configured test network; Running and direct QGA response | PASS for actual boot |
| Boot with no iothreads | Newly created test VM initially uses Plan true/io_uring. Separately stop it in UI, remove iothreads, change io.policy=threads, start in UI. Live XML has no iothreads and both qcow2 disks use io=threads. guest-ping succeeds; guest-get-fsinfo reports /, /boot/efi, /boot and /DATA | PASS, actual guest evidence |
| Test cleanup | UI Run da401b57-1120-449e-b0ea-c1f801a561af SUCCEEDED; session 38 CLEANED, cleanup_required=0; VM 239 removed, volumes 326/327 Expunged; test domain and overlay files absent | PASS |
| Restore test edits | Protected VM 225 restored through UI to UEFI LEGACY, iothreads=true, io.policy=io_uring; original VM inventory returns to 16 Running/8 Stopped/1 Expunging | PASS |

Important boundaries: QGA_REQUIRED is accepted by the pre-existing test workflow but DrTargetMaterializationServiceImpl marks POWER_STATE_VALIDATED without executing a QGA probe. Therefore the workflow result is not claimed as an enforced QGA gate; direct host QGA evidence was independently collected. This pre-existing validation-mode defect needs its own follow-up. The test clone uses Plan tuning, so its initial automatic boot does not prove missing-iothreads boot; the separate UI stop/edit/start supplies that proof.

After cleanup the existing scheduler waited for target-export (rc=100). UI full synchronization was requested to restore normal replication; this is not an uninterrupted automatic-cleanup/resume PASS. No direct runtime/DB repair was used. Final recovery evidence is appended below.

Physical tests above cover qcow2 -> qcow2 only. RBD combinations, VMware and full disaster failover/failback/release were covered by existing automated fixtures where available, not newly executed as physical full-chain tests. The broad issue acceptance matrix remains open until those live paths are validated; this report does not mark the upstream issue closed.

## Deployment and rollback

Cloud changed modules disaster-recovery and server built on WSL ext4; 395 DR tests and 3 serializer tests passed. Changed classes were overlaid into the existing management shaded JAR (not a rebuilt distribution RPM). Final SHA256: 08f351cbc1ae1585099a2806e7471efbea2da3e025e1418b61ccc5006a09d8f9. Backups/manifests: /root/issue961-20260909 and /root/issue961-json-20260909 on 31.10. Restore the saved pre-overlay JAR and restart mold for rollback; verify checksum, login and /client before use.

qemu 0.10.0 RPM from Actions run 34356201076 installed with aspkg on all three source 13 and all three target 31 hosts. Installed dr_runtime.sh SHA256 cd57be9e85620a7160ec27aa060e31284e37a9baecb0382f1bceac72faa4f98a; package verification and Agent health passed. Pre-install script/config backups at /root/issue961-backup/ftctl-before.tgz. Active VM inventory did not change during package deployment. Source worker 13.2 was verified live; stale profile copies were not used as current execution evidence.

Management mold active, /client HTTP 200, WEB-INF preserved; legacy DR Cluster remains disabled. Evidence files are retained locally under /home/ablecloud/work/issue961-evidence. No credentials are stored in this document.

## Final convergence

UI full synchronization Run f0bcf61e-46eb-4441-95f2-41cdd338deb2 (260) SUCCEEDED at 22:55:25 KST. Requested FULL_RESEED cycle 1221 COMPLETED at 22:55:15; source checkpoint 22:54:27, target durable 22:55:14. Runtime READY / scheduler RUNNING / control RUNNING, no error. All five existing plans READY. Final history reads return valid JSON (40 Runs / 226 events at the last API smoke sample).
