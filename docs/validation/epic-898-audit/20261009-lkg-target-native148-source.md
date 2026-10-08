# Epic #898 LKG TARGET 신원 체크포인트 소스 검증

1bf9a1d7326f15ee9c9c9ed9e1ef4af3a0213867의 clean 9개 파일을 반영했다. clean148 및 working148 테스트 통과, LOCAL NFS/SMB 6개 artifact 바이트 동일, sealed RAM synthetic key의 서명 bundle 검증을 확인했다. clean 로그 SHA-256 e5e83821ae33bb0684ba00aca1237230506bcc735ced757c1bf4e5f13f68d8d1, CLI SHA 2a276135d49f40a32fb9c0bbf648427bf17e79e48be4f82ba703227916549a30이다.

현재 TARGET의 exact operation/revision·세대·구성 SHA·public all4 검증 후 독립 capture/stop journal을 만들고 알려진 holder가 없는 상태에서 TARGET RAW를 암호화한다. native codec만 독립 cipher를 발급하며 원래 batch key를 검증한다. 동일 키 권한은 보호된 SERVICE SOURCE 기록 또는 정확한 stage 원본 ref·이전 manifest 증거에서만 인정한다.

실제 signed CLI dispatcher의 잘못된 범위 거부와 signed codec export-target branch의 real RSA/AEAD·publisher·cache 재사용을 검증했다. SOURCE 세 기록과 TARGET 캡처·암호문은 resume/retry/fault에서 불변이다. SOURCE/TARGET 교환·잘못된 키·PENDING·실패 resume를 거부하고 응답 유실 재개는 cached cipher와 fresh all4 관측으로 처리한다. 공개 collector를 임의 issuer RPC로 노출하지 않았다.

endpoint/systemd 관찰은 합성 fixture다. 실제 AD·13번·ROOT 효과는 0, production AD/fullFour는 false다. 새 ROOT의 SAMEVM opaque AD 신원 검증·retained 복구와 소유가 확인된 외부 JOIN 역동작, 최종 새 이미지·API/UI/AD fixture 인수는 남았다. ROOT scope 완화·capsule retain·arbitrary retained callback WIP는 이 커밋에서 제외했다. 최종 UI #1275는 미착수다.
