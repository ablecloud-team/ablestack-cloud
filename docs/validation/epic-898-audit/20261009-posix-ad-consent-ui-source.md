# POSIX 실제 writer 의 AD 중단 승인 재사용

현재 source 3 파일은 신규 13 개 포함 244 tests / 17 suites · no-fix lint PASS 뒤 동결됐으며 독립 운영 빌드도 정상 완료됐다. 실제 POSIX 적용 / 삭제 / Cloud / VM / DATA 효과는 0 이다. 이 소스를 실제 JOINED 서비스 성공이나 전체 AD 인수 완료로 표시하지 않는다.

backend 실제 entry 의 분류를 확인했다. `executeStoragePosixDirectoryPolicy` 는 preview:true 일 때 읽기 경로로 직접 실행하고, 그 밖의 create / update / apply / DELETE 는 executeDesiredChange 를 거친다. 따라서 정책 삭제도 공통 AD 승인의 대상이다. read/reconcile-only volume resume 및 자체 maintenance + no-config-change SMB repair 는 변경하지 않았다.

SharedFSTab 의 NFS / SMB 두 POSIX host 에 실제 instance.name 과 domain status 를 명시 props 로 전달했다. 기존 resource.name fallback 을 사용하지 않는다. child 는 이미 핀된 공통 consent component / fresh receipt helper / scope generator 를 그대로 재사용하며 신규 helper 또는 전역 MoldDialog 수정은 없다.

JOINED 상태의 실제 요청은 API metadata 지원과 exact instance/domain/name 을 확인하고 fresh:true receipt 를 읽은 뒤 사용자에게 기본 false checkbox 와 exact name 입력을 요구한다. 승인 후 다시 fresh receipt 를 읽어 stable revision / boot / domain / SID / idmap 및 request review scope 를 대조한다. read observation UUID / epoch 는 동일하게 강제하지 않고 신선도는 별도 확인한다. 실제 정책 적용에서는 기존 signed preview 가 아직 유효하며 같은 token 인지 POST 직전에 다시 확인한다.

요청에는 별도의 admaintenancewindow / adconfirmation 및 fresh desired expectedrevision 만 추가한다. 기존 expectedpolicyrevision / previewtoken / applyconfirmation 과 삭제 의미는 그대로다. foreign explicit instance, stale desired revision, missing/stale/string receipt, unsupported API, cancel, preview/form/name/service 변경 및 dispose 에서는 POST 가 없다. 동일 resolved 경계는 기존 actual async job 완료를 기다리고 실패하면 자동 retry 없이 승인만 정리한다. JOINED 오류에서는 raw transport / signed preview token 을 표시하지 않는다.

preview:true 와 일반 non-AD 는 추가 fresh 조회·승인 입력·새 AD 필드 없이 기존 요청을 유지한다. service/domain/form/선택 변경과 dispose 에서 pending consent 를 settle 하며 preview 실패 중 일반 적용으로 낮추지 않는다. signed token 은 요청 처리용 RAM 참조이며 문서 / 별도 artifact 에 저장하지 않는다.

기존 style block / footer 와 MoldDialog wrapper 전체 bytes 는 동일하다. HTML 변경은 POSIX 의 consent host 와 SharedFSTab 두 곳의 explicit props 뿐이며 locale / resume / repair / 공통 scale 변경은 0 이다. #1275 최종 style/theme/button/dialog/keyboard QA 는 미착수다.

- 소스 검증: `/root/work/epic898-preparation/ui-posix-ad-source-validation.json`
- 테스트 로그: `/root/work/epic898-preparation/ui-posix-ad-final-tests.log`
- lint 로그: `/root/work/epic898-preparation/ui-posix-ad-final-lint.log`
- 운영 후보: `/root/work/epic898-preparation/ui-posix-ad-production-candidate`
- 운영 로그: `/root/work/epic898-preparation/ui-posix-ad-production-build.log`

| 소스 | SHA-256 |
| --- | --- |
| `ui/src/views/storage/PosixDirectoryPolicies.vue` | `0e24f1fa8179cf017f86afd6296af8371329aff0186520d4667507fbc3b5ceee` |
| `ui/src/views/storage/SharedFSTab.vue` | `a465ef92916ec14d60d6d263ca337f77716e0c49ef801eba378f0ffabd61b660` |
| `ui/tests/unit/views/storage/PosixAdMutationConsent.spec.js` | `1b7be73100615c53579f043f25910443a9a48e6e4a25e28a65e3d8f0677074da` |

후보는 committed UI `78be6d911c551c78d1adfab7d2bb60e32d3c3ca2` 에 위 source 3 파일만 적용한 immutable snapshot 이다. Java/native WIP 를 제외하고 Node 14.21.3 / heap 12 GiB 정규 production command 를 사용한다. 정상 운영 빌드는 850 파일이며 config 생성도 exit 0 이다. index SHA `59818461fe7d9349afa133176a5184fd72b5f1e3cc9aa51ed23074f6a6cf8211`, config SHA `44d080ee12cd291185d65c81a605e71e455dada1a295b46d51e7de86c877b860`, 전체 manifest SHA `32d79f4f3bf5b0070897b9d93ce8d94859672af068375fab7629ca73e0c7bd2f`, build log SHA `1c5eb5184e68e7d20e71a36fbcea20f9c5e087c71ed177cd13e71c8a457d2c62` 를 기록했다. candidate / canonical source 3 파일의 검증 및 빌드 전후 SHA 는 동일하다. 공통 6 파일 단위 4a2a27cfbf4c 는 SharedFSTab / 초기 Create, 이 후속 단위는 외부 POSIX 실제 writer 경계를 닫는 source 범위이며 실제 effects 인수는 새 aligned fixture 뒤 부모가 수행한다.
