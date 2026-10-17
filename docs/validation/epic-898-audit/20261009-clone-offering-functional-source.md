# CREATE_NEW clone 오퍼링 조회와 계획 요청의 기능 검증

읽기 감사에서 기존 clone 옵션은 SPARSE / FAT 단독 필터만 사용하고 CPU / HA / scaling 제약과 linked ROOT DO UUID 를 확인하지 않음을 발견했다. zone / owner 변경 및 조회 실패 때 이전 옵션이 남는 경로도 확인했다. 부모 승인으로 CREATE_NEW 에만 이 기능 갭을 보완했다.

조회 시작, owner / project / zone 변경, 닫힘 및 dispose 때 이전 배열과 기본 선택, pending token 및 review 승인을 무효화한다. SO 의 literal SPARSE / FAT, canonical linked ROOT DO UUID 와 정상 서버 compute compatibility 를 함께 확인한 후보만 옵션과 default 로 사용한다. generic scale / existing-target restore 는 그대로다.

preparePlan POST 직전에 현재 scope, 정상 조회, 선택한 ID 및 provisioning 을 다시 대조한다. missing / string compatibility, foreign ID, 선택 이후 변화, failed / stale / old-owner 응답에서 계획 요청이 없다. 새 일반 VM 또는 disk 생성은 수행하지 않았다. UI 에 source authorization JSON 또는 private key 입력을 추가하지 않았으며 CSS, HTML template, locale 및 최종 #1275 QA 를 바꾸지 않았다.

신규 12 개 포함 173 개 테스트 / 12 개 suite 와 no-fix lint 가 완료 상태로 통과했다. 기존 volume mapping 테스트는 관련 없는 오퍼링 guard 의 fixture 를 명시하고, 실제 검증은 신규 discovery / default / preplan fault 테스트에서 수행했다. source 3 파일의 검증 전후 해시는 동일하다.

- 소스 검증: `/root/work/epic898-preparation/ui-clone-offering-source-validation.json`
- 테스트 로그 SHA: `217ca122dd9c3fc4ad868092e39a9c167f6dc350cba6cdbf3e226c785aaed724`
- 운영 빌드 후보: `/root/work/epic898-preparation/ui-clone-offering-production-candidate`
- 운영 빌드 로그: `/root/work/epic898-preparation/ui-clone-offering-production-build.log`

| 소스 | SHA-256 |
| --- | --- |
| `ui/src/views/storage/StorageServiceConfiguration.vue` | `6d1c0d1cd17de62ebfda3f0cb49e640059e6f9aebad9e7f0a5f5e9fcfe0e5824` |
| `ui/tests/unit/views/storage/StorageCloneOffering.spec.js` | `3e5604de5511ea99ae6fc077429cdf0ca6391119d746973f82e15f0ad2f61fa2` |
| `ui/tests/unit/views/storage/StorageServiceConfiguration.spec.js` | `dd0e4a34880b44495a3eb53fc1738760dd2cd9ab342daa1e6ccb9ae15a7b5b38` |

후보는 committed UI `641bb2d6bf2267339b47c324c4ad573beace1d60` 에 위 source 3 개만 덧씌웠다. Java / native WIP 를 제외하고 Node 14.21.3 / heap 12 GiB 를 사용한다. 운영 빌드는 정상 종료했다. 출력 850 파일, index SHA `fec6a6106cc327228f87580dd050a8b16f3a542a7fcabfe48f40c4fda8690b70`, config SHA `44d080ee12cd291185d65c81a605e71e455dada1a295b46d51e7de86c877b860` 및 전체 파일 manifest SHA `57de74518f713819465131170668e072dee98f8e110f51112cd163fcaace528b` 를 기록했다. 후보와 canonical source 3 파일의 빌드 후 해시도 동일하다. 소스 핀은 `4df89b232e6e33f5536f83be405f04b978e16d07` 이며 실제 Cua plan 옵션 / guard 확인은 부모 배포 뒤 수행한다. 유효한 새 image / runtime / source authority 가 없는 old archive / LKG 를 clone 생성 성공으로 보고하지 않는다.
