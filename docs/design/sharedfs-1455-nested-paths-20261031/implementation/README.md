# #1455 구현 및 검증 기록

이 디렉터리는 설계 문서의 구현 진행 증거입니다. 최종 실환경 검증 완료 기록이 나오기 전까지 이슈 종료 증거가 아닙니다.

구현 범위는 같은 마운트 DATA의 중첩 NFS·SMB 공유 경로, 상대 경로·외부 별칭 분리, inode 기반 경로 고정, 부모·자식 참조 보호, 생성 영수증과 실패 원복입니다. 기존 요청의 경로·볼륨 처리와 SMB 단순 옵션 reload 경로를 보존하며 공통 Volume API나 DB 스키마는 수정하지 않습니다.

게스트 경로는 `shared_directory_policy.FileSharePathBindings`가 FD 순회, 파일시스템 UUID·root/target inode·device·mount ID를 검증합니다. 새 SMB 경로는 소유한 runtime bind로 서비스 루트를 고정하고, 새 NFS 경로는 그 bind에서 flat alias를 생성합니다. 새 모드는 fstab에 검증 전 DATA 상대 경로를 게시하지 않습니다. durable config에는 UUID·inode만 저장하고 runtime mount ID·device·PID는 저장하지 않습니다.

게스트 journal의 `pathbindings` 영수증은 기존 구성 coordinator의 SQL commit에 병합합니다. 실패 보상은 생성한 inode가 맞는 빈 디렉터리와 소유 bind만 정리하며, 클라이언트 데이터가 들어온 디렉터리는 보존하고 진단에 남깁니다. 기존 디렉터리의 소유자·모드·ACL은 자동 초기화하지 않습니다.

UI는 기존 Mold 대화상자와 AntD descriptions/table을 사용합니다. 실제 경로와 외부 별칭을 별도로 표시하고, 부모 접근이 하위 ACL로 격리되지 않는다는 경고와 관계 목록을 제공합니다. 변경하지 않은 상대 경로와 POSIX 값은 제출하지 않습니다.

배포 전 시험과 실제 SystemVM 주입 결과는 인접 JSON을 참조하세요. UI·API·클라이언트 검증 결과는 별도로 기록하며 native 주입 결과로 대체하지 않습니다.
