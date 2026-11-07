// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.
package org.apache.cloudstack.api.response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

public class NetworkOfferingLbResponseTest {
    @Test public void booleanFalseAndZeroAreExplicitApiValues() {
        NetworkOfferingResponse response = new NetworkOfferingResponse();
        response.setConcurrentConnections(0);
        response.setKeepAliveEnabled(false);
        JsonObject json = new Gson().toJsonTree(response).getAsJsonObject();
        assertEquals(0, json.get("maxconnections").getAsInt());
        assertFalse(json.get("keepaliveenabled").getAsBoolean());
        assertFalse(json.has("concurrentconnections"));
    }
    @Test public void trueIsReadableAndUnsetIsNotFalse() {
        NetworkOfferingResponse response = new NetworkOfferingResponse();
        assertFalse(new Gson().toJsonTree(response).getAsJsonObject().has("keepaliveenabled"));
        response.setKeepAliveEnabled(true);
        assertTrue(new Gson().toJsonTree(response).getAsJsonObject().get("keepaliveenabled").getAsBoolean());
    }
}
