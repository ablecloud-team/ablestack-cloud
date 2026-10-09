# Epic 898의 범위별 완료 기록

고유 추적 이슈는 기본29개와 추가 의존#1333을 합친30개다. 2026-10-10 05:17 KST 기준 기존#911은 역사적CLOSED이고, #1333을 별도 범위 완료로 닫아 OPEN28/CLOSED2다. Epic 전체·관련 복원/연결 기능의 완료로 확대하지 않는다. 최종 UI#1275는 미착수다.

#1333은 path 생략 시 기존 volumes.path를 보존하는 범위다. 초기검증소스627727226723과 통합df253b5acd4의 stable patch-id dbdd04eb98dbda8db2f60803a6e4273d39b514e4가 같고, df253은 통합HEAD의 조상이다. 정상515/88·Checkstyle·실제family13ABI/제한배포와Mold UI의경로입력OFF 이름변경·정상API의name/display/protection변경/복원에서path/pool/format/size/offering/owner/첨부를보존했다. 원래파일/inode/SPARSE할당·원설정복원도확인했다. DB직접수정·연결/포맷/삭제0이다.

[닫기 전 완료 근거 댓글](https://github.com/ablecloud-team/ablestack-cloud/issues/1333#issuecomment-6088547654)을 기록하고2026-10-09 20:17:18UTC에completed로닫았다. [초기모듈·ABI·실제UI](../epic-898-ui-20261007/20261008-volume1333-path-preservation.md)와[후속메타데이터API·물리SPARSE](20261009-raw-sparse-preparation-and-details-regression.md)를 연결했다. 통합PR#1271에구현과기록이포함돼있다.

#1269는 실제원래경로의기존/새인증·목록/직접/새로고침·초기spinner1→0·light/dark를확인했지만 실제부분오류/수동재시도와requestcount는미확인으로OPEN이다. #892 원fef관리복구는HOST LAUNCH RPC 실패로RECOVERY_REQUIRED에남고native캐시/데이터보존을유지한다. 그기능의잔여검증을이완료기록으로대체하지않는다.
