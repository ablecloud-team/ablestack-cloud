# 작업 큐·스토리지 VM 안전 경계 배포

소스fb2ecb0faabb6b06f1befd52369a8752cbef9191는 SharedFS 역할 VM의 일반 lifecycle gate, 공통 StorageServiceInstance queue, storage writer47개의 callingAccount owner, generic desired-state의 논리 임대·취소 CAS·heartbeat loss 경계 및 POSIX token 민감 요청 보호를 포함한다. 다른 일반 VM 경로는 유지한다.

- server/KVM/storagevm package:85개 실제 실행 클래스/500개 테스트, 실패·오류·생략0.
- 최신 API/Manager/UserVM/SharedFS/provider와 실제 oldb38 runtime family의 mixed검증:28개 클래스/115개 테스트 및 Runtime14/UserVmService/SharedFSService/StorageService interface ABI 통과.
- Java9840개 pathNULbytesNUL SHA:11db53d1b1216fb138390bcf2cafe121fd8f5304d0f66b2703b8aeace16b5a3a.
- fullsource144관리 항목payload61f5b7584f913ce64d9d437377a271b380f0b9a479a04651f9fe6418411d23ba. oldRuntime2개 제외한 selected142payload1a56966783b51b3a17a76b3cce15592aa12f12b2f3e78a62c52c491419bd838c.
- 신규 DDL0/DAO XMLb73bb0보존/신규hostagentdelta0.
- 실제 외부clientmount0와 nativeWRITER_IDLE을 확인한 metadata단계에서 배포했다. sourceWIP 전체를 포함하지 않았고 배포 대상 외 JAR바이트를 보존했다.
- 관리서버backup /root/epic898-backup-20261008-193650, actualactivation19:37:50KST, 새PID1142754.
- 이전JAR9c0f16ce...→새JAR7d2d1aa7d04db6aabaa32fd7dcabc6c148ed80aa722522eb5fc2683a8c7da5a8.
- 새PID기동 뒤HTTP200 및10:38:37UTC 신규admin login/API,7Running/3UpEnabled 확인. 실제lazyProvider주입이 포함된 전체운영서버 시작도 통과했다.

일반VMSTOP은 실제UI로 partial51의 미완료 writer 전에 거절되며 Running과DATA/boot/header/counter를 보존했다. 다른파괴적lifecycle의 실제데이터시험은 하지 않고 disposable회귀를 계속한다. 논리임대는RAM선점이 아니며 realdrainSupported=false를 유지한다. 별도ROOT/Runtime/Scale전용lease,전체4renderedatomic/AD/templateRoot 검증은 미완료다. oldb38Runtime2개는 최종fresh완전템플릿 준비전 byte-identical로보존하는 임시조합이다. 최종단일소스호환검증 성공으로 해석하지 않는다.

부모·자식SMB실제I/O구간에서는 후속MGTagentnative재시작을 보류하며 이슈별 진행·완료 증거를 계속 기록한다. 최종UI표준화#1275는 사용자지시대로 착수직전전체작업중단·보고·추가지시대기 경계를 유지한다.
