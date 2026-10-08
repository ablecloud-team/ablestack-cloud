# 볼륨별 공유 경로 모듈 배포

Source 1dad2501b5cf3ee4528389ef73a7b61181cf6678의 별도 Git archive에서 정상 Checkstyle을 포함한
server/KVM/storagevm -am 빌드를 수행했다. 98 classes / 588 tests,
failures/errors/skipped 모두 0, Java 9855개 NUL source SHA
993a8658fc12bb5aaa8925a284fa21a9b51db068064a13ef1dd08234d383c04c가 전후 동일했다. 공통 coordinator/profile 작업트리는 포함하지 않았다.

관리 서버에는 누적 검증 모듈 175 entries를 현재 JAR에 반영했다.
baseline a2b5200c233516d7e7c9df52d89664c46bc725ed0668903996bae14a59af56c5,
배포 JAR e99a3ca3bea20e04635edaa7ec007f47058979aa30b39cbf6e370f55c00fc31b, PID 1171342,
기동 2026-10-08T23:44:47+09:00, 백업 /root/epic898-backup-20261008-234320이다.
다른 entry bytes는 보존했다. 기존 RuntimeUpgradeManagerImpl 두 클래스는 유지했고,
새 strict Runtime family 세 entries는 전부 제외했다. bootstrap SHA161c4726...이며
호스트 agent와 기능 UI는 재배포하지 않았다.

HTTP200 이후 새 정상 API 로그인, 기존 SharedFS 일곱 개 Running,
Routing host 세 개 Up/Enabled 및 기존 OFF control policy 일곱 개가 전후 동일함을 확인했다.
이는 서비스 데이터 전체 인수나 partial10TiB의 건강 회복을 의미하지 않는다.

별도 coordinator/profile snapshot 덮어쓰기와 commit receipt 중단 복구 보완은 소스 단계다.
실제 rendered profile/import/activation은 0이며, namespace 정상 요청 재회귀를 이어간다.
최종 UI #1275는 착수하지 않았다.
