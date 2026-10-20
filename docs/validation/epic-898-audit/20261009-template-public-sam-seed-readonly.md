# Epic #898 과거 프로토타입의 공개 SAM seed 읽기 검증

기존 fc3e 프로토타입 qcow SHA-256 bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c를 qemu-nbd read-only 및 ext4 ro,noload로 열었다. 실제 DOS partition node p6의 offset 912261120, size 4329570304를 관찰해 읽었다. 첫 lsblk partition 부재와 ordinal partition 가정 실패는 mount 전에 종료했고 NBD를 정리한 뒤 실제 sfdisk mapping으로 수정했다.

secrets.tdb 430,080 B와 passdb.tdb 421,888 B가 root 0600으로 존재했다. 이후 O_RDONLY TDB 조회에서 공개 SECRETS/SID/SYSTEMVM 키와 유효한 SAM SID 형식을 확인했다. 비공개 record 값은 읽지 않았고 실제 SID 값도 로그·문서에 저장하지 않았다. keytab·AD state·private machine config·winbind idmap은 없었다.

모든 검사 후 원본 qcow SHA·size·mtime가 동일했고 mount·loop·NBD를 정리했다. 운영 VM·원본 DATA·이미지 바이트는 변경하지 않았다.

이 결과는 과거 이미지에 공통 공개 SAM seed가 남아 있다는 증거다. 두 새 게스트의 실제 SID 충돌을 재현했거나 최종 이미지 신원 인수를 통과한 증거는 아니다. 소스의 STOR+instance UUID 이름 구분도 확인했다. 새 빌드는 고정된 신원 seed 경로만 정리하고 manifest writer/validator의 읽기 전용 부재 검증을 추가하며, 첫 게스트 bootstrap 및 새 인스턴스의 SID 고유성을 실제 검증한다. 기존 게스트의 SID와 passdb는 유지한다.

읽기 증빙은 /root/work/epic898-preparation/fc3e-readonly-sam-seed-audit/readonly-proof.json 및 public-sam-readonly-proof.json이다. 과거 artifact를 새 최종 source로 재표시하지 않는다.
