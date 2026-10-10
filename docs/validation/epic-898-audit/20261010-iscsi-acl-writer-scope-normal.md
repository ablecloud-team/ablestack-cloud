# 블록 ACL의 공통 작업 범위 조회 수정

실제 C1 ACL 변경 job f0216e93-8642-406e-a957-71394e007b59는 status2/result431로 실패했다. 정상 API에서 기존 C1/C2 ACL이 같은 UUID로 Ready/BLOCK_TARGET임을 확인했고, 오류의 NFS 조회 실패 접두어=true / block 접두어=false를 확인했다. 게스트9b1·GEN13·pending 없음·빈 세션·기존 데이터와 WP0/0은 유지했다.

공통 getStorageServiceSyncId는 모든 ACL 명령에 FILE_SHARE 전용 requireAcl을 호출하고 뒤에 BLOCK_TARGET 분기를 두고 있었다. 정상 블록 ACL은 이 첫 호출에서 거절되므로 뒤의 처리에 도달하지 못한다. 해당 경계는 DesiredStateChange 생성·checkpoint 전에 실행되며, 이후 permission 또는 CHAP 검증의 실패로 해석하지 않는다.

소스 6f830e4b6d04528d01eed50133bf9d37a4f66dd2는 정확한 ACL ID로 DAO 조회 후 FILE_SHARE는 기존 파일 공유 경로, BLOCK_TARGET은 정확한 자원 ID의 블록 대상 경로로 연결한다. 누락·알 수 없는 자원은 거절하며 기존 writableStorageInstanceId의 UseEntry 및 OperateEntry 접근 검사를 유지한다. 프로토콜별 변경 검증은 그대로다.

제품은 Manager 한 파일, 테스트는 기존 StorageServiceRuntimeQueueScopeTest 한 파일만 변경했다. 실제 iSCSI/NVMe/NFS/SMB 수정·삭제 API 명령과 누락 ACL/대상, 알 수 없는 유형, 외부 계정 거절을 검증했다. 핵심 7개 테스트가 통과했다. 최초 직접 컴파일은 오래된 혼합 classpath의 JUnit 선택 때문에 실패했고, 이전 정상 모듈의 정확한 surefire classpath와 JUnit4.13.2를 사용해 소스 변경 없이 통과했다.

해당 테스트 그룹은 이전 128개 그룹에 포함되지 않았으므로 한 개를 추가해 정상 129개 그룹, 979개 테스트를 실행했다. 실패·오류·skip 0, Checkstyle 및 빌드가 성공했다. 관련 소스5040의 archive와 전후 NUL 해시가 같고 immutable 산출물을 보존했다. Native480 및 UI373은 변경되지 않아 다시 실행하지 않았다.

정상 바이트 차이는 Manager 본체와 내부 클래스 $4/$5의 3개다. 실제 제한 배포를 위해 내부 클래스의 실행 코드·descriptor·참조 및 기존 런타임 ABI를 별도 확인한다. 정상 본체 SHA-256은 cde7bbe3f6cef4c07084d35b4ef25a8c9098abb936d5c4fff63f509d243fad68이다.

실제 배포 및 같은 ACL 재적용·읽기 전용 클라이언트 거절은 후속이다. #892는 열린 상태이고 최종 UI 표준 #1275는 미착수다.

[핵심 검증](iscsi-acl-writer-scope/focused-final-proof.json), [독립 검토](iscsi-acl-writer-scope/independent-peer-public.json), [정상 모듈](iscsi-acl-writer-scope/manager-normal-core-proof.json), [최종 소스·산출물](iscsi-acl-writer-scope/final-normal-source-proof.json), [클래스 차분](iscsi-acl-writer-scope/production-class-delta-vs-ff1.json).
