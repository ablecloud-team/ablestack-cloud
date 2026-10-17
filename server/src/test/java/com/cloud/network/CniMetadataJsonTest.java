// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.network;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class CniMetadataJsonTest {
    @Test public void jsonPreservesCommasEqualsNewlinesAndLegacyStillWorks() {
        NetworkModelImpl model=new NetworkModelImpl();List<String[]> rows=new ArrayList<>();
        model.addUserDataDetailsToCommand(rows,"{\"VALUE\":\"a,b=c\\nnext\",\"EMPTY\":\"\"}");
        assertEquals(2,rows.size());assertArrayEquals(new String[]{NetworkModel.METATDATA_DIR,"VALUE","a,b=c\nnext"},rows.get(0));
        assertArrayEquals(new String[]{NetworkModel.METATDATA_DIR,"EMPTY",""},rows.get(1));
        rows.clear();model.addUserDataDetailsToCommand(rows,"{}");assertTrue(rows.isEmpty());
        model.addUserDataDetailsToCommand(rows,"{A=one, B=two=three}");
        assertEquals(2,rows.size());assertEquals("one",rows.get(0)[2]);assertEquals("two=three",rows.get(1)[2]);
    }
}
