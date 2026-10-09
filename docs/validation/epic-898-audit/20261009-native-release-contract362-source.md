# 최종 native 릴리스 계약의 정상362+direct83 검증

생산 native는 da02b446014/CLI4aba2eb2cdebd73b50b8bbda2b222055345be28381dd110937f20611419bf50d를 유지한다. d6f0037f021e의 immutable snapshot 및 기존 workflow의25개 release contract 모듈과172 검증 selector를 중복 제거한43개에서362 테스트 PASS를 확인했다. direct config-generation20/identity-capsule30/writer-lock7/POSIX20/Ganesha6도 모두 PASS(합계83)다.

archive에는 Git metadata가 없어 template manifest producer의 git HEAD fallback8개가 처음 실패했다. 지원 env ABLESTACK_STORAGE_RUNTIME_BUILD_COMMIT에 실제 snapshot commit d6f0037f021efc001b05b46f82d139b7bceddc9c를 고정해 코드·guard 변경 없이 다시362개를 통과했다.

늘어난 실제 identity_capsule_command 함수를 기존 test가 bash -c의 단일 argv로 전달하면 Linux E2BIG가 발생했다. ef7ed4f82597ef1af8f107b552fedc8c4eecde2d는 해당1개 기존 test harness만 sealed RAM script fd≥16으로 실행하도록 바꿔 실제 payload stdin을 보존한다. real named writer flock FD9를 전달한 추가 identity-capsule30 재실행도 PASS다. payload/private key 임시 파일0, 생산 native12 전후 동일, 제품 guard 변경0이다.

선택896 source의 정렬 path NUL length NUL raw bytes NUL SHA efbaa274f7d4962233a96b0041021834a8ceea1bb59707371732b84f20baba19와 perfile 목록을 고정했다. failed logs와 수정1 patch SHAeaab16470f050a8a2c7805fe1f979d1e98c13b2d2ddf93c333484cd0ad87fa4d도 보존한다. source-proof/parent-pin-proof는 /root/work/epic898-preparation/native-release-contract-d6f0037f021e/다.

이 검증은 backend825 actual 배포·AD·ROOT 교체·디스크 생성 또는 API/UI all4 인수 PASS가 아니다. 해당 실제 효과0이며 마지막 서버 소비자와 동일S 산출물 및 클러스터 인수를 계속한다. 이슈 OPEN·최종 UI#1275 미착수다.
