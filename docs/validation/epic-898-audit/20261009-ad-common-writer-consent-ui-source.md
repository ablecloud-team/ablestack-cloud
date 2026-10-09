# JOINED AD 공통 writer 중단 승인 UI 기능

현재 source 6 파일은 신규 28 개 포함 216 tests / 14 suites · no-fix lint PASS 뒤 동결됐으며 독립 운영 빌드도 정상 완료됐다. 실제 Cloud / JOINED writer / 새 VM / DATA 효과는 0 이다. production UI 성공 또는 전체 AD 완료를 선판정하지 않는다.

## 정상 계약과 공통 입력

JOINED AD 서비스의 CRUD / protocol 작업은 원본 RAW identity checkpoint · SOURCE capture / STOP · TARGET 보존이 필요하므로 서비스 중단에 대한 별도 사용자 승인을 받는다. 새 Base API 의 optional `admaintenancewindow` / `adconfirmation` 은 기본 미승인이며 exact instance.name 을 요구한다. 기존 JOIN / backup / restore 의 maintenancewindow / confirmation 및 resource 삭제 확인 문자열을 재사용하거나 덮어쓰지 않는다. backend Base 2 파일과 공통 writer 구현은 별도 후속 단위이며 아직 이 UI source 의 실제 효과 인수 근거가 아니다.

기존 stateless MoldDialog 는 편집하지 않았다. `StorageAdMutationConsent` 한 컴포넌트에서 기존 checkbox / name input / warning / modal 을 재사용하고 instance/name/action/request review 및 stable receipt binding 을 명시 scope 로 받는다. 기본 checkbox 는 false, 이름은 빈 문자열이다. 자동 확인·다른 resource.name fallback·private source authorization/key 입력은 없다.

## SharedFSTab 요청 경계

공통 `runStorageAction` 에서 현재 JOINED domain 을 확인한 경우에만 다음 순서를 사용한다.

1. exact instance/domain 과 API metadata 의 admaintenancewindow / adconfirmation / fresh 지원을 확인한다.
2. 정상 `listStorageServiceDomainStatus(instanceid, fresh:true)` 를 60 초 범위로 읽고 typed JOINED / OK / literal verification / current scope / boot / freshness receipt 를 확인한다. config receipt 를 fallback 으로 쓰지 않는다.
3. 현재 instance/name/action 및 공개 request review + receipt binding 을 고정하고 사용자 checkbox / exact name 승인을 기다린다. password / CHAP / DHCHAP controller key / credential / private key 값을 공개 review scope 에 포함하지 않는다.
4. 승인 후 fresh receipt 를 다시 읽어 stable native revision/boot/domain/realm/machine/domain SID/idmap binding 과 검토 scope 를 대조한다. read observation UUID 및 generatedEpoch 는 매번 달라질 수 있으므로 같음을 요구하지 않고 freshness 는 별도 확인한다.
5. 요청에 admaintenancewindow=true / adconfirmation 과 fresh expectedrevision 을 붙인 뒤만 POST 한다. 이전 desired revision 이 이미 다른 요청은 POST 전에 거절한다. delete / restore 의 기존 confirmation 은 그대로다.
6. 실제 $pollJob 반환 jobstatus 1 을 await 한 뒤 동일 service 의 상태를 갱신한다. 실패나 navigation 뒤 새 service 를 갱신하지 않고 자동 retry 하지 않는다. grant 와 secret request 참조는 finally 에서 폐기한다. JOINED transport error 의 원문 / Axios request body 는 알림에 전달하지 않는다.

일반 non-AD 는 추가 fresh 조회·AD 필드 없이 기존 asynchronous refresh callback / 즉시 UI 흐름을 유지한다. service/domain/form/action/visible scope 변화 및 dispose 에서는 pending consent 를 null 로 settle 하며 숨은 승인·orphan promise 를 남기지 않는다. 승인 기다리는 동안 서버 mutation 요청은 없다.

## 초기 Create 의 별도 lifecycle

기존 Create 는 createSharedFileSystem jobid 수신 직후 화면을 닫고 설정을 비동기로 진행했다. AD branch 는 이후 새로운 action 승인이 필요한데 unmounted view 에 대화상자를 연결하면 사용자 입력을 받을 수 없다. 따라서 일반 non-AD 의 기존 동작은 유지하고 AD branch 만 설정 terminal / cancel / failure 까지 view 를 유지한다.

초기 사용자 승인과 JOIN 후 fresh receipt 는 동일한 새 instance 의 SMB share + inline AD ACL 에만 연결한다. 새 ad API metadata 를 지원하지 않으면 VM 생성 요청 전에 차단한다. JOIN 후 iSCSI / NVMe 의 지원되는 조합을 hard-deny 하지 않는다. 각각의 다른 action 에서 같은 reusable 입력으로 새 명시 승인을 받은 뒤 fresh binding 을 대조하여 수행한다. 초기 SMB grant 를 다른 protocol/action/instance 에 자동 확장하지 않는다.

닫힘 / dispose 는 interactive state 를 폐기하고 pending promise 를 종료한다. AD 설정의 나중 POST 는 close 이후 차단하며 이미 요청한 job 또는 만든 자원에 자동 삭제 / 재시도 / 강제 종료를 하지 않는다. terminal 에서 view 를 닫고 form/snapshot 의 AD 및 block secret 참조를 정리한다. 일반 non-AD background setup 은 닫힌 뒤에도 이전처럼 진행한다.

## 검증 범위

신규 28 tests 는 기본 false / exact name / unsupported API / fresh read order / missing·stale·foreign·string receipt / cancel·review change / nonce 차이 허용과 stable binding 변경 거절 / desired revision / job await·failure / transport secrecy / same-instance SMB 전용 grant / 다른 protocol 새 승인 / close·dispose·no later POST / non-AD background 유지 및 request 참조 cleanup 을 포함한다. 기존 13 suites 와 함께 전체 14 suites 가 완료 상태로 통과했다. 실제 plugin `$pollJob` 는 tracker.track 의 jobstatus result 를 반환하는 기존 계약을 사용한다.

source 검증 전후 SHA 와 immutable 후보 SHA 를 고정한다. SharedFSTab / CreateSharedFS 의 기존 HTML 은 consent host 한 줄 외 동일하고 style block / 기존 footer 는 같다. MoldDialog wrapper 전체 bytes 도 같다. #1275 최종 스타일·테마·버튼 정렬·대화상자/키보드 QA 를 시작하지 않았다.

- 소스 검증: `/root/work/epic898-preparation/ui-ad-mutation-source-validation.json`
- 테스트 로그: `/root/work/epic898-preparation/ui-ad-mutation-final-tests.log`
- lint 로그: `/root/work/epic898-preparation/ui-ad-mutation-final-lint.log`
- 운영 후보: `/root/work/epic898-preparation/ui-ad-mutation-production-candidate`
- 운영 로그: `/root/work/epic898-preparation/ui-ad-mutation-production-build.log`

| 소스 | SHA-256 |
| --- | --- |
| `ui/src/views/storage/SharedFSTab.vue` | `25660c66c199c8fd6cf9816500b90e5636e94ce4c5bef83d9af29fb45c3666a6` |
| `ui/src/views/storage/CreateSharedFS.vue` | `c398740665bd5fde864520473864f9c4912f53c0858c66159c9aaf42c2d81069` |
| `ui/src/views/storage/StorageAdMutationConsent.vue` | `2ffed9b54c94f0a127f2b4d1511ad5584a33911cc498ab84027362e330a424ec` |
| `ui/src/utils/storageAdIdentity.js` | `0369aafdfc8da3e835bb407aec20b4cb53b0d302ec993740a9b57737b0334359` |
| `ui/tests/unit/views/storage/StorageAdMutationConsent.spec.js` | `c3bb4438e7cf9c134cf0eaf68c19361821b46a55f71686590d3cf9f8974ec7c7` |
| `ui/tests/unit/views/storage/StorageAdMaintenance.spec.js` | `8482b45f89a8fce3b1cd173bbe595ed03626d3bb013b886fff6b50218190f5f1` |

운영 후보는 committed UI `f016968ce7f84c0c82cb106e1a5aeb4ad12cae5b` 에 위 6 source 파일만 적용한 snapshot 이다. Java/native WIP 를 제외하고 Node 14.21.3 / heap 12 GiB 의 정규 production command 를 사용한다. 운영 빌드는 850 파일로 통과했고 config 생성도 exit 0 이다. index SHA `200ddc5f3243bdc4809496c8f49148e05174a5152fdc8b200a58345c96624590`, config SHA `44d080ee12cd291185d65c81a605e71e455dada1a295b46d51e7de86c877b860`, 전체 manifest SHA `c0df2af5c4a02b706d7b053bc3c4a1d449581b75d217df5a4ce95c9103a736df`, build log SHA `c6952d4e8b06e1be07febfab6f60fd1d6bb8b107eecb790304265cf1f0a7dbd2` 이다. 후보와 canonical 6 source 파일의 빌드 전후 SHA 는 모두 같다. 실제 old ADE707 부정 UI 및 새 aligned backend/native JOINED 긍정 UI는 부모의 별도 후속 단계다.

## 연결 범위와 다음 한 경로

빌드 중 direct POST coverage 를 실제 Manager entry 와 대조했다. PosixDirectoryPolicies 의 preview:true 는 읽기이며 preview:false 실제 apply 만 공통 AD 승인이 필요하다. 이 컴포넌트는 SharedFSTab.runStorageAction 을 통과하지 않으므로 현재 source 6 파일 뒤의 별도 좁은 재사용 단위가 필요하다. source 6 결과를 모든 AD writer UI 완료로 확대하지 않는다. StorageVolumePreparation.resume 는 read/reconcile-only, StorageSmbIdentityRepair.repair 는 자체 explicit maintenance + no-config-change 이므로 새 필드를 추가하지 않고 기존 계약을 유지한다. 부모가 POSIX 실제 apply 한 경로의 후속을 승인했으며 현재 후보의 source freeze 는 유지한다.
