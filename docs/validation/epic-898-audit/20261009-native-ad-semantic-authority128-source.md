# Epic #898 AD 복원 원본 권한 및 대상 신원 검증

c987d5458d1147a1c2a97caa9d7d1a1e39322cbc의 clean 6개 파일을 반영했다. 128개 테스트·bash·patch 검증 통과, source/candidate before-after 불변, 기존 LOCAL NFS/SMB 6개 artifact 바이트 및 serializer AST 동일을 확인했다. clean 로그 SHA-256은 79eeb38b4f2e825d9ac9cf7bbd6b429ecd11054b4f652e2e9a69c864dfa49807, CLI SHA는 783a53e2ef05fee5d227d070d3b91021b69f17e586ea419f9514b5e83c5410b5이다.

- exact 8-field descriptor와 원래 instance:operation scope, 원본 구성 SHA 및 RSA OAEP·AEAD 암호문을 검증한다. 참조 MAC은 원본 checkpoint RSA PKCS8 DER에서 계산하며, 관리 master 저장 보호 키와 섞으면 거부한다.
- 실제 sealed input → CLI decoder → real crypto 검증을 포함했다. 셸 FD3가 memfd 요청을 덮던 문제는 요청 descriptor를 16 이상으로 복제해 해결했고 writer FD9는 유지했다.
- NEW 대상은 정상 STOR+target UUID 이름과 해당 SAM key를 사용한다. 가입 전후 target SAM은 같고 원본 SAM/컴퓨터 계정과는 다르며 원본 domain/idmap은 동일, alias/SPN은 겹치지 않아야 한다.
- SAMEVM의 공개 신원 권한도 정확히 비교한다. 공개 caller sourceIdentity dict만으로 복원 권한을 발급하지 않는다.
- synthetic signing key는 sealed RAM에서만 사용했고 bundle 검증을 통과했다. 운영 AD/fullFour는 false다.

이 시험의 directory 효과는 합성이다. 실제 AD 가입·ROOT 복원·Cloud fixture 인수는 0이다. LOCAL RID-backed passdb 소비와 Unix UID/GID 증명, SAME/root의 외부 역동작, 전체 domain-FIRST/POSIX/all4 연결 및 sanitized 새 이미지의 실제 API/UI/Windows 인수는 남았다. 별도 ROOT scope 완화·capsule retain·renderer retain·미완 LOCAL module WIP는 이 커밋에 포함하지 않았다. #902는 OPEN, 최종 UI #1275는 미착수다.
