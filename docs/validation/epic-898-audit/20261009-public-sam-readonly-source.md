# 기존 SAM SID 읽기 전용 조회 source 교정

13f5e30c4ea의clean8파일은exactcandidate/staged/committed SHA와같다.
clean109focused/bashedsyntax/diff 실패·오류·skip0이며canonicalWIP49와
구분했다. lifecycle/winbind/ROOTbootstrap/bundle선언은미포함이다.

Samba netgetlocalsid는없는신원을생성할수있어항상readonly라는가정을
제거했다. 서비스공개SID조회는testparm의검증Netbios이름을받아보호된기존
secrets.tdb의pinned FD/O_RDONLY libtdb로SECRETS/SID/이름 키한개만읽는다.
68byte dom_sid LE uint32구조를검증하고없는DB/key·symlink/mode·변형/
namedreplace를net호출/SID생성전에거절한다.

real libtdb 합성TDB에공개SID2개+secretmarker를넣은5meaningful회귀로
조회키1개와전체DBbytes/mtime보존을검증했다. 실제운영TDBsecretdump나
AD테스트가아니다. ADreadonlyRPC도보호machineSID일치전net/winbind0을
요구한다. 새absent/foreignSAM회귀1개를포함한다.

actualCLIJOIN/LEAVEtypedproducer없는상태effect0/files0/privateoutput0,
actual13/AD/ROOT0/prodADfull4false다. 실제JOIN/LEAVE및암호checkpointstage
producer연결은후속이다. 최종UI1275는미착수다.

primary근거는Samba4.17 net.c/machine_account_secrets.c/util_tdb.c이며,
proof=native-public-sam-clean-c55546881102/source-proof.json,
logSHA1748b3322968606d5869cefc0b201bcfd6af3607870931361016dfb5e34714e8,
patchSHAf74e1914737dca9318bccebcff7251578f313e959654079f8be818505b59df47이다.
