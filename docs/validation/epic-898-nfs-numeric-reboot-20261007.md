# Epic 898 NFS 숫자 소유자 매핑과 재부팅 검증

13번 클러스터의 전용 시험 서비스 `bc637fae-7b42-4757-b319-f05ccfd13cc3`에서 실제 Mold UI로 숫자 UID/GID 정책을 적용하고 재시작했다. 기존 운영 시험 서비스의 데이터는 변경하지 않았다.

- 설정 작업 `ffbf5dd1-f387-4dc3-b677-8be6badda6b9`: COMPLETE, revision 5.
- 두 리스너 2049/2050: 재부팅 후 모두 LISTEN, UI READY.
- UI: 설정 NUMERIC, 실행 NUMERIC, CONSISTENT.
- 재부팅 후 게스트 boot ID: `ec6e8a8a-189e-4201-8deb-47fae4c03fdd`.
- 파일시스템 UUID: `a87111b2-fa9b-49c1-aa6c-259e42c1b5f8` 유지.
- 서로 다른 두 Linux 클라이언트에서 생성한 파일의 서버 소유자: 모두 `24567:24568`, mode 644 유지.
- 연속 I/O 검증 파일 SHA-256: `b2270bd6e9d24c3ecd30a9438d3509d4c85530890f6cff6a2d54b697beb5b461` 유지.

증거 이미지는 Windows 작업 디렉터리의 `docs/validation/epic-898-ui-20261007/nfs-numeric-ui-reboot-persistence.png`에 저장했다. API 및 게스트 원본 출력은 별도 작업 기록에 보관했다.

두 번째 리스너 부분 실패 주입은 PATH 우선순위 때문에 실행 경로에 걸리지 않았다. 해당 작업은 정상 완료됐으며 **부분 실패 복구 검증의 성공으로 계산하지 않는다**. 임시 주입 파일은 내용 확인 후 제거했다. 이전 설정 트랜잭션의 ROLLED_BACK 검증과 구분한다.

중첩 NFS/SMB 공유 구현은 아직 클러스터에 배포하지 않았다. 관련 경로 테스트와 모듈 빌드는 통과했지만 실제 UI 생성·접근·ACL·삭제 보호 검증이 남았다. Epic 전체 완료와 이슈 종료를 의미하지 않는다. SMB AD는 사용자 지시에 따라 보류한다.
