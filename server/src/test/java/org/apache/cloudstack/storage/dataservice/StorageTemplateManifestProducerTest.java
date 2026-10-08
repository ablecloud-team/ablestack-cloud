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
package org.apache.cloudstack.storage.dataservice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Storage;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.cpu.CPU;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class StorageTemplateManifestProducerTest {
    private void write(Path root,String name,String content) throws Exception {Path p=root.resolve(name);Files.createDirectories(p.getParent());Files.writeString(p,content);}
    private int run(Path source,String... args) throws Exception {Process process=new ProcessBuilder(args).directory(source.toFile()).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);int exit=process.waitFor();if(exit!=0)System.err.println(output);return exit;}
    @Test public void realProducerAndValidatorMetadataIsAcceptedByJavaAndIdentityKeyOmissionIsRejected() throws Exception {
        Path source=Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!Files.exists(source.resolve("tools/appliance/scripts/write_storage_template_manifest.py")))source=source.getParent();
        Path image=Files.createTempDirectory("storage-template-producer-");JsonObject lock=JsonParser.parseString(Files.readString(source.resolve("tools/appliance/systemvmtemplate/storage-kernel-amd64.json"))).getAsJsonObject();String version=lock.get("kernelVersion").getAsString();StringBuilder config=new StringBuilder();lock.getAsJsonObject("requiredConfig").entrySet().forEach(e->config.append(e.getKey()).append('=').append(e.getValue().getAsString()).append('\n'));
        write(image,"boot/config-"+version,config.toString());write(image,"boot/vmlinuz-"+version,"fixture");write(image,"boot/initrd.img-"+version,"fixture");for(String module:List.of("nvmet","nvmet-tcp","nvme-auth"))write(image,"lib/modules/"+version+"/"+module+".ko","fixture");
        for(String file:List.of("ablestack-storagectl","ablestack-storage-boot-reconcile","ablestack-storage-monitor"))write(image,"usr/local/bin/"+file,Files.readString(source.resolve("systemvm/debian/usr/local/bin/"+file)));
        for(String file:List.of("runtime_updater.py","volume_identity.py","session_auth.py","identity_capsule.py","config_generation.py"))write(image,"usr/local/lib/ablestack-storage/"+file,Files.readString(source.resolve("systemvm/debian/usr/local/lib/ablestack-storage/"+file)));
        Assert.assertEquals(0,run(source,"python3",source.resolve("tools/appliance/scripts/write_storage_template_manifest.py").toString(),"--image-root",image.toString(),"--source-root",source.toString(),"--version","producer-test","--runtime-version","runtime-test"));
        Assert.assertEquals(0,run(source,"python3",source.resolve("tools/appliance/scripts/validate_storage_template.py").toString(),image.toString()));
        Path manifestPath=image.resolve("etc/ablestack-storage/template-manifest.json");JsonObject manifest=JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();Map<String,String> metadata=new HashMap<>();manifest.getAsJsonObject("registrationDetails").entrySet().forEach(e->metadata.put(e.getKey(),e.getValue().getAsString()));
        Assert.assertEquals("true",metadata.get("storage.service.data.identity.inspect"));Assert.assertFalse(metadata.containsKey("storage.service.template.data.identity.inspect"));
        VMTemplateVO template=Mockito.mock(VMTemplateVO.class);Mockito.when(template.getTemplateType()).thenReturn(Storage.TemplateType.SYSTEM);Mockito.when(template.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);Mockito.when(template.getArch()).thenReturn(CPU.CPUArch.amd64);
        Assert.assertTrue(StorageTemplateCompatibility.evaluate(template,template,metadata,true,"4.23.0.0","4.23.0.0",true).get("compatible").getAsBoolean());
        manifest.getAsJsonObject("registrationDetails").remove("storage.service.data.identity.inspect");Files.writeString(manifestPath,manifest.toString());Assert.assertNotEquals(0,run(source,"python3",source.resolve("tools/appliance/scripts/validate_storage_template.py").toString(),image.toString()));
    }
}
