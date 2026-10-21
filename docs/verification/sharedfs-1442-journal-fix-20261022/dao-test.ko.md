# #1442 실제 DAO 저장·재조회 회귀 시험

대상은 `SharedFSCreationDaoRoundTripTest`다. DAO를 mock으로 바꾸지 않고 실제 `SharedFSDaoImpl`의 persist/findById/update/생성 요청 키 조회를 실행한다. 운영 데이터 없이 별도 로컬 MariaDB의 최소 SharedFS 매핑 테이블을 사용한다. JDBC 바인딩과 TEXT 저장·재조회가 검증 범위이며, 운영 DB의 모든 FK/서비스 기능 인수를 대체하지 않는다.

- 255자를 넘는 XFS/EXT4 생성 기록의 최초 저장과 READY/job ID 갱신 후 재조회
- UTF-8 JSON 65,535바이트의 정확한 저장·재조회
- 저장 후 255자로 잘린 행을 명시적으로 주입했을 때 다른 정상 행의 응답까지 실패하지 않는지 확인

시험 URL이 없으면 이 통합 시험은 skip된다. 이번 인수에서는 아래와 같이 별도 MariaDB를 실행하고 URL을 전달해 **4개 모두 실행·통과, skip 0개**를 확인했다. 다른 백엔드 시험을 포함한 10개 시험 모음의 합계는 109개다.

## 재현

WSL ext4 소스에서 실행한다. MariaDB 서버·클라이언트와 Java/Maven이 설치되어 있어야 한다. 13342 포트는 이 시험 전용이다. 운영 DB나 13번 관리 DB의 주소를 넣지 않는다. 시험 자체가 전용 loopback URL 이외의 접속을 거절한다.

```bash
cd /root/work/ablestack-cloud
testdir=$(mktemp -d /tmp/sharedfs1442-dao.XXXXXXXX)
mariadb-install-db --no-defaults --datadir="$testdir/data" \
  --auth-root-authentication-method=normal --skip-test-db > "$testdir/init.log" 2>&1
mariadbd --no-defaults --user="$(id -un)" --datadir="$testdir/data" \
  --socket="$testdir/mysql.sock" --pid-file="$testdir/mysql.pid" \
  --log-error="$testdir/server.log" --bind-address=127.0.0.1 \
  --port=13342 --skip-name-resolve --character-set-server=utf8mb4 &
trap 'mysqladmin --no-defaults --socket="$testdir/mysql.sock" -uroot shutdown' EXIT
# mysqladmin --socket="$testdir/mysql.sock" -uroot ping이 성공한 뒤 진행
mysql --no-defaults --socket="$testdir/mysql.sock" -uroot \
  -e 'CREATE DATABASE sharedfs1442_dao_test CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'
mvn -B -pl engine/schema -am \
  -Dtest=SharedFSCreationDaoRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dsharedfs.dao.test.url=jdbc:mysql://127.0.0.1:13342/sharedfs1442_dao_test?useSSL=false&characterEncoding=UTF-8&allowPublicKeyRetrieval=true' \
  -Dcheckstyle.skip=true -Drat.skip=true test
```

시험은 자신이 연 TransactionLegacy와 DataSource를 종료·복원한다. 이번 실행에 사용한 임시 MariaDB도 시험 종료 후 정상 종료했다. 운영 계정 파일, 관리 서버 암호, 암호화 키 또는 운영 schema를 이 시험 준비에 사용하지 않았다.
