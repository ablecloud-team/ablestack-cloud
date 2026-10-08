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

import java.util.Set;
import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;

/** Fresh native and immutable pointer observations decide which side of commit an interrupted writer owns. */
public final class StorageRenderedRecoveryState {
    public enum Decision { UNCOMMITTED, COMMITTED }
    private StorageRenderedRecoveryState() { }
    private static String text(JsonObject value,String key){return value.has(key)&&value.get(key).isJsonPrimitive()?value.get(key).getAsString():null;}
    private static boolean yes(JsonObject value,String key){return value.has(key)&&value.get(key).isJsonPrimitive()&&value.get(key).getAsJsonPrimitive().isBoolean()&&value.get(key).getAsBoolean();}
    private static JsonObject object(JsonObject value,String key){if(!value.has(key)||!value.get(key).isJsonObject())throw new CloudRuntimeException("Rendered recovery observation is incomplete: "+key);return value.getAsJsonObject(key);}
    private static boolean scoped(JsonObject actual,JsonObject scope){for(String key:Set.of("instanceUuid","operationUuid","revision"))if(!java.util.Objects.equals(actual.get(key),scope.get(key)))return false;return true;}
    public static Decision observe(JsonObject scope,JsonObject receipt,JsonObject nativeState,JsonObject rendered) {
        if(!yes(nativeState,"success")||!yes(nativeState,"generationSupported")||!yes(rendered,"success")||!rendered.has("bootHeld")
                ||!rendered.get("bootHeld").isJsonPrimitive()||!rendered.get("bootHeld").getAsJsonPrimitive().isBoolean())throw new CloudRuntimeException("Rendered recovery lacks fresh native observations");
        JsonObject generation=object(nativeState,"generation"),current=object(rendered,"current"),currentScope=object(current,"scope"),previous=object(receipt,"previousGeneration");
        String pending=text(nativeState,"pendingOperationUuid"),operation=text(scope,"operationUuid");
        if(!nativeState.has("pendingOperationUuid")||pending!=null&&!operation.equals(pending))throw new CloudRuntimeException("Foreign native pending writer blocks rendered recovery");
        JsonObject staged=receipt.has("staged")&&receipt.get("staged").isJsonObject()?receipt.getAsJsonObject("staged"):null;
        JsonObject activation=rendered.has("activation")&&rendered.get("activation").isJsonObject()?rendered.getAsJsonObject("activation"):null;
        boolean ownActivation=activation!=null&&scope.equals(activation.get("scope"));
        boolean previousPointer=java.util.Objects.equals(text(receipt,"previousRenderedSha256"),text(current,"manifestSha256"));
        boolean targetPointer=staged!=null&&scoped(currentScope,scope)&&java.util.Objects.equals(text(staged,"renderedManifestSha256"),text(current,"manifestSha256"))
                &&java.util.Objects.equals(text(staged,"configurationSha256"),text(current,"configurationSha256"));
        if(!previousPointer&&!targetPointer)throw new CloudRuntimeException("Rendered recovery current pointer belongs to a third generation");
        if(activation!=null&&!ownActivation&&!"COMPLETE".equals(text(activation,"phase")))throw new CloudRuntimeException("Foreign rendered activation blocks recovery");
        if(scoped(generation,scope)) {
            if(staged==null||!targetPointer||!ownActivation||!Set.of("VERIFIED","COMPLETE").contains(text(activation,"phase"))
                    ||!java.util.Objects.equals(text(staged,"configurationSha256"),text(generation,"configurationSha256"))
                    ||!java.util.Objects.equals(text(generation,"configurationSha256"),text(nativeState,"configurationSha256"))
                    ||!java.util.Objects.equals(text(staged,"renderedManifestSha256"),text(activation,"targetSha256"))
                    ||!java.util.Objects.equals(pending==null?"IN_SYNC":"PENDING",text(nativeState,"generationStatus")))throw new CloudRuntimeException("Committed rendered generation has unresolved drift or scope mismatch");
            return Decision.COMMITTED;
        }
        if(!generation.equals(previous)||!scoped(currentScope,previous)&&previousPointer)throw new CloudRuntimeException("Native previous generation differs from its frozen checkpoint");
        if(ownActivation&&staged==null)throw new CloudRuntimeException("Interrupted activation lacks its durable staged pin");
        if(ownActivation&&(!java.util.Objects.equals(text(staged,"renderedManifestSha256"),text(activation,"targetSha256"))
                ||!java.util.Objects.equals(text(receipt,"previousRenderedSha256"),text(activation,"previousSha256"))))throw new CloudRuntimeException("Rendered activation pins differ from its durable staged receipt");
        if(targetPointer&&!ownActivation)throw new CloudRuntimeException("Target pointer has no matching activation journal");
        return Decision.UNCOMMITTED;
    }
}
