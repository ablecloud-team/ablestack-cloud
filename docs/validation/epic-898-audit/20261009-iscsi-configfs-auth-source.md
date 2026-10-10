# iSCSI CHAP configfs 입력 보호 소스

커밋9a832ada774dce6be1d5aea6cedcf7beaa05f191은 committed baseline 위
AD WIP를 제외한6파일 독립 단위다. iSCSI10/Runtime9/credential18/Inline5,
집중42 tests가 통과했고 bash syntax·source SHA·cached diff check를 확인했다.
실제 kernel login이나13 배포는0이다.

기존 보호된 private vault를 재사용하며 네 configfs auth 속성에 메모리 값을
직접 쓰고 다시 읽어 대조한다. secret targetcli argv와 추가 targetcli saveconfig
dump를 제거했다. 모든 private auth 입력을 먼저 검증해 target 제거·vault 공개
앞에서 실패할 수 있게 했다.

실제 FD9의 named inode/owner/mode와 fdinfo의 exclusive FLOCK WRITE를 확인한다.
단순 open FD9 및 다른 프로세스가 같은 inode lock을 가진 fake FD9는 쓰기 전에
거절됐다. descriptor를 통해 속성을 열고 부모·속성 교체를 쓰기 전후 검증한다.

공식 Linux6.12 configfs와 rtslib 의미를 대조해 NULL unset sentinel 및255byte
상한을 적용한다. 기존 API가 허용한 leading/trailing secret 공간은 그대로
쓰기·readback하고, cold runtime 관찰도 newline만 제거해 정확히 보존한다.

원래 합성42 테스트를 실제 로그인·mutual CHAP·재부팅 인수로 확대하지 않는다.
finalized fc3e prototype의 fullSPARSE 복사본·새SPARSE 소형 DATA에서만 독립
kernel 검증을 준비한다. 원래 prototype/13 VM/DATA/partial은 변경하지 않는다.
production AD/fullFour=false와 최종 UI #1275 착수 전 중단 조건을 유지한다.
