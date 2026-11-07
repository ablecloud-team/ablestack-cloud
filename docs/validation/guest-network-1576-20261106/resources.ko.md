<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->

# 확인용 자원

이전 #1575 자원은 정리했다. 아래의 전용 오퍼링·망과 연결을 확인하기 위한 VM을 사용자 확인용으로 남겼다. 계정 비밀번호는 기록하지 않는다.

## 31번 GFS2

| 유형 | 오퍼링 | 연결 망 |
| --- | --- | --- |
| Isolated | [qa-gn1576-31-isolated-ui](http://10.10.31.10:8080/client/#/networkoffering/6ead1117-db83-444c-ae96-b445e55b699b) | [qa-gn1576-31-isolated](http://10.10.31.10:8080/client/#/guestnetwork/07c19a00-d2da-4bac-9c8d-80bc644c5f0d) |
| Shared | [qa-gn1576-31-shared-ui](http://10.10.31.10:8080/client/#/networkoffering/86fb701c-ad86-4faa-88c7-17e700d0e06f) | [qa-gn1576-31-shared](http://10.10.31.10:8080/client/#/guestnetwork/7434c06f-9770-4512-9976-77d389894354) |
| L2 | [qa-gn1576-31-l2-ui](http://10.10.31.10:8080/client/#/networkoffering/e09911a9-560c-4cd1-a5b5-b70410cc8a4a) | [qa-gn1576-31-l2](http://10.10.31.10:8080/client/#/guestnetwork/95a94cd4-acf4-47af-a9fb-c7a4511e6c76) |

[확인용 VM](http://10.10.31.10:8080/client/#/vm/244de1e7-c916-4f8e-ac2f-37b787117c16)

도메인 `qa-gn1576-31-scope`와 Zone으로 제한했다. 격리형·공유형 복제 원본도 유지한다.

## 32번 Glue RBD

| 유형 | 오퍼링 | 연결 망 |
| --- | --- | --- |
| Isolated | [qa-gn1576-32-isolated-ui](http://10.10.32.10:8080/client/#/networkoffering/08777c92-e5c9-4661-b411-0e982a3684f3) | [qa-gn1576-32-isolated](http://10.10.32.10:8080/client/#/guestnetwork/dd8309c5-d92e-4a14-ab51-02ca97033b90) |
| Shared | [qa-gn1576-32-shared-ui](http://10.10.32.10:8080/client/#/networkoffering/f4ad08ec-637f-49d5-bd54-7334fec404a2) | [qa-gn1576-32-shared](http://10.10.32.10:8080/client/#/guestnetwork/f52dc644-67cb-4643-b106-b86fd70ece66) |
| L2 | [qa-gn1576-32-l2-ui](http://10.10.32.10:8080/client/#/networkoffering/3b711ed0-157c-4f67-a81f-7b19aefa8f78) | [qa-gn1576-32-l2](http://10.10.32.10:8080/client/#/guestnetwork/31541c7d-1240-44ff-a6a7-0f4c1d6c646b) |

[확인용 VM](http://10.10.32.10:8080/client/#/vm/51253734-b0a5-4a37-92f2-013a99ee9110)

도메인 `qa-gn1576-32-scope`와 Zone으로 제한했다. 격리형·공유형 복제 원본도 유지한다.
