# Epic #898 일반 AD 가입·탈퇴 및 SAM 초기화 소스 검증

3fbed7edd6b721cdd6c11cd05fc1bcd801b3da9c의 clean 12개 파일을 검증해 반영했다. 117개 focused 테스트 통과, 작업 트리 focused 51개 통과, bash 구문·patch 적용 검증 및 소스/후보 해시 불변을 확인했다. 최종 clean 로그 SHA-256은 ead1e63d1b1a92a68798c034b9a1ebb641d3d506e565365fb0c93486f5ca16dc, 후보 CLI SHA는 4034107e1eec6904c1bcf7827cc53de8b3388d3dec34fa4d834eef0dd48b54b3이다.

- 일반 JOIN은 정확한 SERVICE4·가입 전 원본 암호문·부팅·원본 구성과 중지 증빙을 요구하고, JOIN_EXISTING의 기존 로컬 SAM을 보존한다.
- LEAVE는 효과 전 컴퓨터 object SID·SPN 소유 SID·DNS의 정확한 A 및 외부 AAAA 부재를 확인한다. 이후 LDAP 컴퓨터/SPN 부재·DNS 부재, 닫힌 3개 owned artifact 정리·원래 공개 설정 복원·fresh NOT_JOINED 및 로컬 SAM 보존을 요구한다.
- net ads leave가 exit 0이어도 disabled account가 남으면 완료로 판단하지 않고 RECOVERY_REQUIRED를 유지한다. 공개 설정 작성 중 실패도 복구 필요 상태로 보존한다.
- 읽기 전용 SAM 조회는 신원을 만들지 않는다. 명시 bootstrap만 실제 FD9 FLOCK·settled generation·부팅·정확한 이름·빈 private 상태를 확인하고 초기화한다. 응답 유실 재호출은 planned SID를 유지하며 다른 SID로 교체됐으면 쓰기 전에 거부한다.
- 실제 CLI·libtdb·mount namespace 검증을 포함했으나 net writer는 합성이다. 외부 AD 가입·탈퇴는 이 시험에서 0이다.
- 기존 로컬 NFS/SMB 렌더링 6개 artifact와 serializer AST는 committed baseline과 byte 단위로 동일하다.
- synthetic RSA signing key를 sealed RAM에서 사용해 bundle 생성·검증을 통과했다. 운영 AD/fullFour는 false, code handler만 선언한다.

ROOT scope 완화·identity capsule retain·renderer retain의 별도 WIP는 이 커밋에서 제외했다. plain sourceIdentity는 NEW_INSTANCE 권한으로 사용하지 않는다. 인증된 원본 MAC+AEAD consumer, 새 target SAM의 원본 대비 고유성과 가입 전후 보존, domain-FIRST 복원·LOCAL passdb SID rebase·외부 AD 역동작, 새 sanitized image와 실제 클러스터 API/UI/Windows 인수는 남아 있다. #902는 OPEN, #1275 최종 UI는 미착수다.

[Samba 4.17의 DNS 전체 IP 제거 및 leave 처리 원본](https://github.com/samba-team/samba/blob/v4-17-stable/source3/utils/net_ads.c)을 확인해 부정 테스트 범위를 정했다. 명령 성공 코드만으로 외부 정리를 추론하지 않는다.
