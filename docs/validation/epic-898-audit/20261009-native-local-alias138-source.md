# Epic #898 로컬 신원 복원·소유권 별칭 소스 검증

4c4947724a15fb04454a8a8fde9030f6d2d7752a의 clean 14개 파일을 반영했다. 138개 테스트·bash·patch 및 exact candidate 소스 SHA 검증을 통과했다. CLI SHA-256은 7a1ce5543d979100e47f3b723058bf6eb745a85754694777bc11421db118690b이다.

- 암호화 원본 권한·SERVICE4·부팅·원본 암호문·native 구성 SHA에 연결된 LOCAL 복원 RPC와 guard를 구현했다.
- 공개 사용자 5개 필드, 그룹 2개 필드, 관리 별칭 8개 필드를 닫힌 계약으로 검증한다. 복제 연쇄의 원본 네임스페이스와 검토한 대상 공유 UUID를 연결해 순수·실제 렌더러가 같은 계정 매핑을 사용한다.
- tdbsam의 RID-backed 계정 형식을 사용하며 원본 secrets/keytab을 복사하거나 target SAM을 교체하지 않는다. 비공개 USER record tail은 RAM·암호문 안에서 보존하며 출력하지 않는다.
- network=none의 격리 Rocky 9.7 / Samba 4.22.4 실제 시험에서 원본과 다른 target SAM, 동일 RID의 target SID 읽기, UID12000/GID12001 보존, target secrets bytes 보존, private tail 일치를 확인했다. 호스트 DNF와 canonical source의 쓰기 마운트는 0이다.
- testparm의 잘못된 --configfile 전달을 positional config 경로로 수정했다. 공백 경로·잘못된 config 및 실제 configured idmap readback을 검증했다. net의 configfile 인자는 유지한다.
- 첫 SAM 생성 응답은 sideEffects=true, 기존 보존은 false를 literal로 반환한다. 실제 CLI 생성 분기를 확인해 Java의 첫 JOIN 소비자와 연결할 수 있게 했다.

실제 binary 시험은 Samba 4.22.4이며 Debian 4.17 실제 실행으로 확대하지 않는다. 4.17의 저장 형식·인자 parser는 원본 소스 근거와 연결했다. 외부 AD·13번·ROOT 효과는 0, production AD/fullFour는 false다.

LKG TARGET 신원의 독립 암호화 체크포인트, all4 복원 신원·ROOT 역동작 및 새 sanitized 이미지의 실제 API/UI/Windows 인수는 남았다. 별도 ROOT scope/capsule retain/Driver retain WIP는 이 커밋에서 제외했다. #902/#909는 OPEN, 최종 UI #1275는 미착수다.
