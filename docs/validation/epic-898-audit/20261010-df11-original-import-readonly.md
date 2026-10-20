# df11 원 import 실패의 제한된 진단과 보존

원 df11의 정상 UI SOURCE 복구는 import-local-source의 HOST_NONZERO exit1에서 실패했고, native journal은 RECOVERY_REQUIRED/importedfalse다. 이 문서는 현재 읽기·사전 점검의 확인 범위와 미확인 원인을 구분한다. 원 CLI19ad·GEN10·pending11·BOOT·key/cipher/ref를 유지하며 강제 terminal·journal 재해시·호환 면제·포맷·RAW I/O는0이다.

## 원 코드의 쓰기 전 경계

원 signed CLI19ad의 정의와 stop rollback body를 disposable child RAM에 불러오고, 첫 journal QUIESCING 쓰기와 모든 신호 전에 sticky BaseException guard를 적용했다. TCP를 생략한 FF94는 first-write boundary까지 통과했고 pidfd3개를 모두 닫았다. 실제 STOP·import·decrypt는0이며 원 실패 원인이 배제된다는 뜻이 아니다.

TCP 포함 첫 변형은 외부 서비스 IP10.10.13.243을 원 readiness 주소로 잘못 가정해 TCP_NOT_EXACT_ENDPOINT에서 거절됐다. 실제 연결·socket·pidfd는0이고 전후12항목이같다. 이것은 진단 helper의 주소 문제이며 원 제품 실패로 해석하지 않는다.

원 df11 journal의 prior.ownedEndpoints를 한 번 선택해 listenIp0.0.0.0(str)/port445(int)와 주소 hash key675fcbc172f7cb71e442afce를 확인했다. 원 inspect는 이를127.0.0.1:445로 probe한다. helper의 주소 한 곳만 이 값으로 맞추고 이전 증거를 보존했다.

수정된 loopback 검사4ae31은 실제 connect/close1·readiness true·원 observe의 session-empty true와 first-write guard를 확인했다. socket1/pidfd3 모두닫힘, journalwrite/signal/import/decrypt0, 전후12메타 동일이었다. 현재 원 TCP 포함 prewrite 경계만 확인했으며, 이후 STOP/opaque restore가 가능하다거나 원 실패 시점의 내부 원인이 확인됐다고 표시하지 않는다.

## 기존 공개 오류 정보의 로그 확인

원 CLI는 실패 시 success=false/errorCode=LOCAL_SOURCE_CHECKPOINT_REJECTED/reason=예외종류의 고정3필드만 반환한다. 정상 HostAnswer는 stdout을 resultJson에 남기지만 현재 Manager는 false일 때 일반 오류로 덮는다. 원 메시지·키·SID·payload를 노출하는 방향으로 확장하지 않는다.

known MGT 로그의09:25:13~27 구간을16MiB bounded tail로 한 번 읽었다. mold/PID1331803·unit user UID/proc UID·소유자·모드·NOFOLLOW를 검증하고, 동일작업 UUID+Seq anchor와 실제 HostAnswer.resultJson 답변의 정확3필드만 허용했다.401 timestamp행 중 direct anchor0/고정 답변 JSON0이어서 UNKNOWN이다. 계획대로 agent 로그는 읽지 않았고 rotation/넓은검색/재읽기/guest 호출은0이다. 이는 제한된 범위의 관측이며 로그 전체에 원인이 없다고 주장하지 않는다.

원 signed CLI와 writer/lease/STOP/resume 계약을 바꾸지 않고 기존 공개3필드를 관리 서버에서 엄격하게 보존하는 최소 보완을 준비한다. 새 복구 제출은 그 source 검증·정상 모듈·제한배포 후 별도 단계로 진행한다. 최종 UI #1275는 미착수다.

[원 loopback 쓰기 전 관측](df11-original-import-readonly/original-loopback-prewrite-proof.json), [실제 선언 주소](df11-original-import-readonly/exact-owned-readiness-endpoint.json), [기존 로그의 제한된 UNKNOWN 결과](df11-original-import-readonly/existing-fixed-reason-log-proof.json).
