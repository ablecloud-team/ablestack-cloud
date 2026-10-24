# #1476 스냅샷 복원 크기·오퍼링 계약

- Epic: #1473 / 브랜치: dhslove/epic-1473-storage-snapshots / PR: #1492
- 스냅샷 기반 새 볼륨은 ROOT/DATADISK 모두 생성 당시 snapshots.size와 disk_offering_id를 상속한다. 원본의 현재 크기 또는 첫 번째 조회 오퍼링을 기준으로 삼지 않는다.
- UI는 조회 전용 크기·오퍼링과 상속 안내를 표시한다. size/diskofferingid/IOPS/KMS/storageid 입력을 제출하지 않는다. 용량 변경은 복원 후 기존 resizeVolume 경로에서 별도로 수행한다.
- includesnapshotcontext=true 응답의 snapshotcontext.restore는 캡처된 크기(bytes), 오퍼링 UUID/이름, 조회 가능 여부를 제공한다. 이 값도 기존 최신 상태 fingerprint에 포함된다. 조회 불완전 시 제출을 막고 재시도를 제공한다.
- listSnapshots와 생성 응답의 virtualsize도 원본 볼륨의 현재 크기가 아닌 스냅샷 크기를 사용한다.
- API는 size를 지정하면 스냅샷 크기와 같은 정수 GiB만 허용한다. ROOT의 명시적 오퍼링 경로도 다른 최종 용량을 수락하지 않는다. 오퍼링 없는 IOPS 재정의와 KMS 요청은 조용히 무시하지 않고 거부한다. DATADISK의 명시적 오퍼링 금지, 스냅샷·대상 소유자 권한, 암호화·할당 정책 검증을 유지한다.
- 원본이 정상 삭제/Expunged된 경우 남은 메타데이터로 기존 암호화·할당 제약을 확인하고 Secondary 사본에서 복원한다. 원본 메타데이터까지 소실된 경우에는 제약을 검증할 수 없으므로 명확히 거부한다.
- 비동기 복원 실패 때 폼을 닫지 않아 입력·대상과 오류 이유를 유지한다. 불확실한 응답은 기존 작업 lease와 조회 경로로 처리하며 자동 재제출하지 않는다.
- 가상머신 연결 전환 시 VM 선택 필수 규칙을 적용하고, 끄면 해제한다. 장치 ID의 자동 배정은 유지한다.
- VM 대화상자의 MoldDialog(md), 헤더·본문 스크롤·하단 작업 영역과 색상 토큰을 사용한다. 상세 상단의 작업 패널을 추가하지 않는다.

## 검증 구성

31 GFS2 / 32 RBD에 고유 접두어 SNAPR1476-31/32의 1GiB custom/fixed DATADISK와 ROOT VM을 생성한다. 원본 DATADISK의 처음 1MiB=0x5a, 마지막 1MiB=0xa5를 기록하고 스냅샷 생성 후 custom 원본을 2GiB로 확장한다. ROOT는 실제 서비스 오퍼링에 따라 100GiB이며 전체 논리 SHA256을 보존한다. 원본 세 볼륨을 모두 삭제하고 DB 및 실물 부재를 확인한 뒤 API/UI 복원을 검증한다.

잘못된 크기/IOPS/오퍼링 거부, 같은 크기 요청, API/UI/DB/실물 크기·오퍼링, 데이터 패턴 및 ROOT 전체 SHA, VM 미선택 필드 오류, Secondary 시험 파일 일시 누락에 따른 실제 job 실패·동일 입력 재시도, 더블 클릭·재조회 보호, 다크/라이트·좁은 폭의 겹침·가독성을 기록한다. 파일 누락 주입은 레지스트리의 고유 시험 스냅샷 파일만 이동하고 SHA 검증 후 되돌린다.

WSL ext4에서 변경 api/server Maven 모듈만 빌드한다. UI 검사·빌드는 별도로 수행한다. 전체 Cloud 빌드와 GitHub Actions, qemu 패키지 빌드는 실행하지 않는다. 배포는 변경 관리 클래스와 정적 UI 파일만 갱신하며 WEB-INF/config 및 다른 JAR 엔트리를 보존한다. SSVM/호스트 코드는 변경하지 않는다.
