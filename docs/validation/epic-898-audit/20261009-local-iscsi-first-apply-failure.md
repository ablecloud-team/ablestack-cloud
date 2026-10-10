# 로컬 실제 iSCSI 첫 적용 실패

fc3e exact SPARSE ROOT 전체 복사본과 NEW64MiB metadata DATA를 사용한 자기
no-NIC QEMU guest에서 실제6.12.95 kernel/QGA/ROOT ancestor 제외/빈 RAW를
확인했다. 원본 이미지 SHA bfa006c0…는 같고 13번·Cloud 설정 변경은0이다.
9a832ada signed runtime은 정상 updater transfer/verify/preflight/activate/
fresh installed READBACK를 통과했으며 CLI f7031bf4…와 support 두 파일은
pin과 일치한다. 원래 template source와 적용 runtime source를 구분한다.

첫 pinned 정상 CLI의 iSCSI target apply는 exit1이며 configfs auth attribute
changed while opening 신원 검사에서 거절됐다. 이후 로그인0, RAW 쓰기0,
matrix 완료false다. 일반 파일 mock과 실제 configfs의 stat/fstat 차이를
비밀 값 없이 관측한 뒤 보호 검사를 유지하는 source 회귀로 보완한다.
자동 재적용이나 불확실한 partial 성공 승격은 하지 않는다.

targetcli 기본 autosave 설정에 대한 초기 grep 해석은 실제 제어 흐름으로
정정했다. 설치본 2.1.53은 noninteractive 분기에서 sys.exit(0)가 autosave
분기보다 먼저 실행되고 실제 autoUseDaemon=false/daemoninactive다.
추가 saveconfig 파일은 없고 운영 자격 증명·비밀 argv 사용은0이다.
이 우려만으로 불필요한 설정/소스 변경을 추가하지 않았다.

credentials는 폐기했다. 자기 target rollback 및 guest cleanup 관측은
이후 결과에서 별도로 기록한다. 실제 커널 로그인과 CHAP/mutual I/O는 아직
완료하지 않았으며 이슈를 닫지 않는다. 최종 UI #1275는 미착수다.
