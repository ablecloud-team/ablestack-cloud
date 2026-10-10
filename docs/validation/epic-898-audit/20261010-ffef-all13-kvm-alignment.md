# 안전 iSCSI 진단 KVM 클래스의 13번 클러스터 정렬

정상 ffef KVM72/7·native457/49 및 검증한 wrapper45e417을 재사용해 13.2→13.1→13.3 각 단일 클래스를 제한 배포했다. 각 호스트의 실제 old JAR·provider4·agent PID/start·VM PID/start를 고정하고, 다른 호스트의 API/provider hash를 차용하지 않았다. 참조27개는 각 실제 provider의 bytes로 확인했고 원 public/protected17개를 보존했다.

13.2는 JAR4758ef/PID3074124와 backup /root/epic898-iscsi-safe-ffef35c-backup-20261010-115638이다. 13.1은 JAR66a7b108/PID2340049와 backup /root/epic898-iscsi-safe-ffef35c-host1-backup-20261010-122752, 13.3은 JARcf01e986/PID1963274와 backup /root/epic898-iscsi-safe-ffef35c-host3-backup-20261010-122942다.

13.1 postcheck 후13.3을 순차 적용했고, 각 own VM10/VM2(ADSvr 포함)의 PID/start 및 provider4가 동일했다. 각 원 ZIP의 다른482/474 file와510/502 raw local record를 보존했다. 정상 새 API 로그인과3host Up/8service Running, 원FS8/VM21/volume33/F1operation17 및 df11 ROLLED_BACK100이 같았다. 13.2는 먼저 완료한 실제8788 proof의 계보를 연결하며 세 호스트의 동시 새 hash probe로 표시하지 않는다.

초기 reader가13.2 API hash를1/3에도 강제한 source 가정 오류와 큰 응답의 EOF를 잘못 처리한 local framing 실패를 보존했다. 첫 실제 failed guard를 그 가정 오류로 단정하지 않는다. 고정frame/length/hash/END/EOF의6.6MB local positive·negative 검증 뒤 host별 공개 파일을 정확히 받아 비교했다. 이 helper 교정은 서비스·클러스터 실패와 구분한다.

[각 호스트 제한 배포와 기존 자원 보존 최종 proof](ffef-all13-kvm-alignment/cluster-all13-actual-postcheck-public.json). 적용은각1회/agent재시작1회이고 rollback·자동재apply·VMrestart·guest/RAW I/O·MGT/UI/DDL 변경은0이다. 실제 iSCSI/all4 인수는 후속이며 최종 UI #1275는 미착수다.
