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
package org.apache.cloudstack.wallAlerts.client;

import java.lang.reflect.Method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class WallApiClientImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WallApiClientImpl client;
    private Method mutateThresholdRaw;

    @Before
    public void setUp() throws Exception {
        client = new WallApiClientImpl("http://localhost", null, 1000, 1000);
        mutateThresholdRaw = WallApiClientImpl.class.getDeclaredMethod("mutateThresholdRaw",
                ObjectNode.class, String.class, String.class, Double.class, Double.class);
        mutateThresholdRaw.setAccessible(true);
    }

    @Test
    public void updatesEvaluatorReferencedByCondition() throws Exception {
        final ObjectNode group = groupWithData("[{\"refId\":\"C\",\"model\":{\"conditions\":["
                + "{\"evaluator\":{\"type\":\"lt\",\"params\":[60]}}]}}]");

        Assert.assertTrue(mutate(group, "gt", 75.0, null));
        Assert.assertEquals("gt", group.at("/rules/0/grafana_alert/data/0/model/conditions/0/evaluator/type").asText());
        Assert.assertEquals(75.0,
                group.at("/rules/0/grafana_alert/data/0/model/conditions/0/evaluator/params/0").asDouble(), 0.0);
    }

    private ObjectNode groupWithData(final String data) throws Exception {
        final String group = "{\"name\":\"WALL_Every_1m\",\"rules\":[{\"title\":\"host cpu\","
                + "\"grafana_alert\":{\"title\":\"host cpu\",\"condition\":\"C\",\"data\":" + data + "}}]}";
        return (ObjectNode) objectMapper.readTree(group);
    }

    private boolean mutate(final ObjectNode group, final String operator,
                           final Double threshold, final Double threshold2) throws Exception {
        return (Boolean) mutateThresholdRaw.invoke(client, group, "host cpu", operator, threshold, threshold2);
    }
}
