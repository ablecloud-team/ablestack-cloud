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

package com.cloud.kubernetes.cluster.actionworkers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.commons.codec.binary.Base64;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KubernetesManagedAddonPreparationTest {
    private static class Worker extends KubernetesClusterUpgradeWorker {
        final List<String> calls = new ArrayList<>();
        boolean providerReady = true;
        boolean resourceMissing;
        Worker() { super(Mockito.mock(KubernetesCluster.class), null, new KubernetesClusterManagerImpl(), null); }
        @Override protected String readResourceFile(String resource) throws IOException {
            assertEquals("/script/managed-addon-placement.py", resource);
            if (resourceMissing) { throw new IOException("fixture missing"); }
            return "print('managed placement')";
        }
        @Override protected void copyScriptFile(String address, int port, File file, String filename) { calls.add("copy:" + filename); }
        @Override protected boolean deployProvider() { calls.add("prepare"); return providerReady; }
    }
    @Test public void scriptsAndUserDataUseTheSameEncodedResource() throws Exception {
        Worker w = new Worker();
        String rendered = w.prepareManagedAddonPlacement("before @@MOLD_MANAGED_ADDON_PLACEMENT@@ after");
        String encoded = rendered.substring(7, rendered.length() - 6);
        assertEquals("print('managed placement')", new String(Base64.decodeBase64(encoded), StandardCharsets.UTF_8));
        assertFalse(rendered.contains("@@MOLD_"));
        assertEquals("unchanged script", w.prepareManagedAddonPlacement("unchanged script"));
    }
    @Test public void missingNormalizerFailsBeforeRenderingOrCopying() throws Exception {
        Worker w = new Worker();w.resourceMissing = true;
        try { w.prepareManagedAddonPlacement("@@MOLD_MANAGED_ADDON_PLACEMENT@@"); fail("missing helper"); }
        catch (IOException expected) { assertTrue(w.calls.isEmpty()); }
    }
    @Test public void refreshesProviderBeforeWaitingForRollout() {
        Worker w = new Worker();w.prepareUpgradeProvider();
        assertEquals(java.util.Arrays.asList("copy:deploy-provider", "prepare"), w.calls);
    }
    @Test public void providerFailureRejectsBeforeDrain() {
        Worker w = new Worker();w.providerReady = false;
        try { w.prepareUpgradeProvider(); fail("unready provider"); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("before upgrade drain")); }
    }
}
