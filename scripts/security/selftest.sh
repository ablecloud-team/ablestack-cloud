#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# mysql 서비스 확인
systemctl status mysqld

Active 항목이 active (running)

# firewalld 서비스, 포트 open 확인
systemctl status firewalld

Active 항목이 active (running)

firewall-cmd --list-all
-service mysql, nfs, nfs3, roc-bind
서비스 체크 mysql, nfs, nfs3, roc-bind
-ports 8080,8250,8443,3306,4444,4567,4568,4569,20048,20048,2049,875,32803,32769,892,600,662 tcp   20048 udp

# management 서비스 확인
systemctl status cloudstack-management

Active 항목이 active (running)

# DB 결과 넣기
Event 테이블에 자체시험 이력 저장
    자체시험 실행 결과
        security.check
        Successfully completed the management server's self-test.
        Error while the management server's self-test : ____ service security check failed
        
Alert 테이블에 자체시험 실패 시 결과 저장
    자체시험 실패
    	alert.management 
        Management server's self-test failed: _______ service security check failed
        
자체시험 실행 API

탭 - Security 

시험 항목 
firewalld service
mysql service
management service

