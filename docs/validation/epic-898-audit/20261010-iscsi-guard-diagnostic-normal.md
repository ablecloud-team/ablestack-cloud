# iSCSI 고정 guard 단계 진단 정상 검증

자동 managed IQN의 정상 UI target261은 ROLLED_BACK/530으로 끝났지만 민감 출력 생략/exit1만 남았다. 현재 실제 원인은 UNKNOWN이다. 소스8de98e는 targetcli 밖의 검사를 고정 phase로 한정해 공개하고 민감 message/path/payload/stdout/stderr를 반환하지 않는 진단 보완이다. 실제 기능 원인을 해결했다고 주장하지 않는다.

첫 hint는 input/vault 전에 한 번 초기화해 INPUT/VAULT_STATE/DEPENDENCY/LISTENER/DEVICE/AUTH/READINESS와 고정9분류만 exact5 JSON으로 전달한다. guard returnCode는 null, 기존 targetcli5 enum/return 규칙은 유지한다. unknown 첫 예외는 비공개 sentinel로 예약해 뒤 cleanup 오류로 바뀌지 않는다. literal false·unknown/중복/추가/다중 JSON·trailing·kind/enum/null pair를 strict consumer가 확인한다.

기존 disabled target/acls:null·SystemExit0 의미를 보존했고 인증 생성자와 원 Configfs 호출은 작은 callback으로 AUTH 경계 안에 함께 둔다. LOCAL_SOURCE3·STOP·PIN·crypto·opaque replay 및 protected stdin은 변경하지 않았다. 직접 require_chap_value와 Bash ingress 등 일부 generic 경계는 현재 제한으로 구분한다. prefix 정책·API/UI/DB schema 변경은 이번 진단에 포함하지 않는다.

집중 native13/Java25와 실제 producer11→compiled waitForGuestCommand를 확인했다. 최초 정상354d native411/F1/E10는 실패 이력으로 보존했다. E10은 unqualified fixture import의 PYTHONPATH 누락이고, F1은 inline exact auth call 검사였다. callback을 보완해 Lifecycle13+Inline16 집중29를 확인한 뒤 정상8de native50 selector를 올바른 PYTHONPATH로 실행해470 tests/FES0을 확인했다.

KVM 정상354d는75 tests/7 suites/FES0·Checkstyle 성공이다. 8de의 CLI-only callback 보완에 Java/API bytes가 같아 이 compiler 산출물을 재사용하며 8de 재컴파일로 표시하지 않는다. exact7 XML만 수집하고 저장소 전체 XML glob의 지연·중복 위험은 제거했다. 전체tracked15,574 파일/symlink의 전후NUL 및 원archive+변경CLIblob 동등을 확인했고 immutable3,046파일을 보존했다.

배포 제품은 wrapper1(45e→95c522)이며 기존 public/protected ABI와 외부 참조가 같다. 새 external reference0이다. ffef snapshot과의 API2 delta는 이미 배포한8a allocation interface이며 이번 API source/deploy delta0으로 분리한다. Native ENTRY는 CLIc265/bootf292/monitoraf923다.

[정상 소스·테스트·산출물 proof](iscsi-guard-diagnostic-normal/normal-source-proof.json). 기존 actual45e provider4/ref27 계보는 ABI/newref0 근거로 재사용하며 fresh deploy 전에 실제 oldJAR/class/provider를 확인한다. 실제 새 guard CODE·wrapper 적용과 UI 재시험은 아직0이고 #1275는 미착수다.
