// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import org.junit.Assert;
import org.junit.Test;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

public class PosixDirectoryPolicyTest {
    @Test public void rejectsTraversalAndMalformedRelativePathsBeforeAnyRuntimeWrite() {
        for (String value : new String[] {"", "/etc", ".", "a/..", "a//b", "a/", "a\\b", "a\nb"}) {
            Assert.assertThrows(InvalidParameterValueException.class, () -> PosixDirectoryPolicy.relativePath(value));
        }
        Assert.assertEquals("export/shared/project-a", PosixDirectoryPolicy.relativePath("export/shared/project-a"));
    }
    @Test public void sameVolumeAndPathHaveOneStableProtocolNeutralKey() {
        String volume = "ca9bcef3-8881-4b3b-84be-a989e654fd6a";
        Assert.assertEquals(PosixDirectoryPolicy.pathKey(volume, "shared"), PosixDirectoryPolicy.pathKey(volume.toUpperCase(), "shared"));
        Assert.assertNotEquals(PosixDirectoryPolicy.pathKey(volume, "shared"), PosixDirectoryPolicy.pathKey(volume, "shared/a"));
        Assert.assertEquals("2775", PosixDirectoryPolicy.directoryMode("2775"));
        Assert.assertThrows(InvalidParameterValueException.class, () -> PosixDirectoryPolicy.directoryMode("888"));
    }
    @Test public void preservesStructuredAclValuesAndRejectsDuplicatesOrShellTokens() {
        JsonArray input = JsonParser.parseString("[{\"principalType\":\"NUMERIC_GID\",\"principal\":\"010006\",\"permission\":\"READ_WRITE\"}]").getAsJsonArray();
        Assert.assertEquals("10006", PosixDirectoryPolicy.aclEntries(input).get(0).getAsJsonObject().get("principal").getAsString());
        input.add(input.get(0).deepCopy());
        Assert.assertThrows(InvalidParameterValueException.class, () -> PosixDirectoryPolicy.aclEntries(input));
        JsonArray shell = JsonParser.parseString("[{\"principalType\":\"LOCAL_USER\",\"principal\":\"user; root\",\"permission\":\"READ_WRITE\"}]").getAsJsonArray();
        Assert.assertThrows(InvalidParameterValueException.class, () -> PosixDirectoryPolicy.aclEntries(shell));
        Assert.assertThrows(InvalidParameterValueException.class, () -> PosixDirectoryPolicy.numericId(2147483648L));
    }
}
