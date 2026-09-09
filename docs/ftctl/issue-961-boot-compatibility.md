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

Cluster evidence and UI outcomes will be appended after verification. No PASS is inferred from a completed build or unchanged inventory.

## UI validation finding: nested JSON serialization

The reason text containing `io.policy=threads` exposed an existing ApiResponseSerializer.unescape defect: Unicode escapes in nested JSON strings were decoded twice, yielding invalid outer JSON with a backslash followed by equals. listDrRuns and embedded plan history became unreadable. Preserve escaped literal sequences and JSON-special/control characters while unescaping ordinary Unicode characters. Three server tests cover nested equals, literal Unicode sequences and escaped controls/quotes/backslashes. The server module package passed. Historical Run data remains intact.

Initial physical test uses u26-base DR Plan on source site 13 and target site 31. The live worker was resolved to 10.10.13.2 (SSH 10022), not inferred from old profile copies on 31.1/13.1. Current full-seed checkpoint 2026-09-09 22:29:26 and durable target 22:30:13 were observed there. Target VM 225 retains missing iothreads and io.policy=threads; Plan requests true/io_uring. SYNC Run f2edefe7-7980-47b5-89f0-8d00580d8f44 succeeded and emitted TARGET_TUNING_DIFFERENCE WARN. UI boot/negative/recovery validation is still pending at this entry.
