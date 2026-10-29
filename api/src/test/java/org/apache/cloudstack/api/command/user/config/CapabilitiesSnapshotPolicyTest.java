// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package org.apache.cloudstack.api.command.user.config;

import com.cloud.storage.Snapshot;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.cloudstack.api.response.CapabilitiesResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class CapabilitiesSnapshotPolicyTest {
    @Test public void responseUsesAppliedValuesAndRetainsZeroWithoutHardcodedDefault() {
        Snapshot.Type[] types = {Snapshot.Type.HOURLY, Snapshot.Type.DAILY, Snapshot.Type.WEEKLY, Snapshot.Type.MONTHLY};
        int[] before = new int[4]; int[] values = {3, 0, 11, 5};
        for (int i = 0; i < 4; i++) { before[i] = types[i].getMax(); types[i].setMax(values[i]); }
        try {
            CapabilitiesResponse response = new CapabilitiesResponse();
            response.setSnapshotPolicyMax(ListCapabilitiesCmd.snapshotPolicyMaxima());
            JsonObject limits = new Gson().toJsonTree(response).getAsJsonObject().getAsJsonObject("snapshotpolicymax");
            assertEquals(3, limits.get("hourly").getAsInt()); assertEquals(0, limits.get("daily").getAsInt());
            assertEquals(11, limits.get("weekly").getAsInt()); assertEquals(5, limits.get("monthly").getAsInt());
        } finally { for (int i = 0; i < 4; i++) types[i].setMax(before[i]); }
    }
    @Test public void absentMetadataRemainsOptional() {
        assertFalse(new Gson().toJsonTree(new CapabilitiesResponse()).getAsJsonObject().has("snapshotpolicymax"));
    }
}
