# ROOT 신원 및 SERVICE 유지보수 소스 검토·핀

검토한 native 파일29개가 커밋 fc3e7f5c134828288ac89dc98300533ffcb909ff의
Git tree bytes와 모두 일치한다. 변경된 파일은27개이고 이미 동일한 boot/monitor도
검토 SHA에 포함했다. 별도 진행 중인 AD winbind 두 파일은 이 단위에서 제외했다.

ROOT 암호화 신원 캡슐 참조·같은 VM의 이전 operation 복원, 보호된 POSIX
post-apply receipt의 device-only 이관과 DATA 속성 보존, 빈 target ROOT의
network 한 행 bootstrap을 구현했다. SERVICE는 정확한 scope와 source checksum,
소유한 NFS/SMB acceptor의 중지·재개, 실제 all4 검증과 lease 해제 이후 marker
해제를 보호한다. activate 전 취소는 source unchanged 경로로 처리한다.

검증 단계는 구분한다. 이전 소스 단계에서 전체 Storage262/Runtime130이 통과했고,
최종 추가·변경 부분은 RootBootstrap5, Inline4, Driver11, Generation14, Service9,
AD13 focused 검증으로 확인했다. 현재29 최종 소스로267 fullsuite를 실행했다고
표시하지 않는다. owned29 diff check, bash syntax, snapshot 전후 SHA,
sealed RAM key builder 서명과 archive의 실제 세 entrypoint bytes 일치가 통과했다.

Builder review 산출물은 UNCOMMITTED_WORKTREE_REVIEW label/sourceCommit null을
유지한다. 소스를 커밋했다고 그 review archive를 완성 릴리즈로 재표시하지 않는다.

RENDERED_CONFIG_GENERATION_HANDLER는 코드 가용성이다. ProductionFullFour,
실제 ROOT swap, 네 프로토콜 전체 실제 인수 및 AD 기능 완료는 false/0이다.
현재13번 native는 기존38a4이며 이 커밋은 실제 배포하지 않았다.
AD 후속과 최종 단일 SHA의 이미지·runtime·패키지·CI·실환경 검증을 이어간다.
최종 UI 표준화 #1275는 착수 전 중단·보고·추가 지시 대기 조건을 유지한다.
