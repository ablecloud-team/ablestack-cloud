//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.DeleteACfileToFencedHostCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.LibvirtConnection;
import com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolDef;
import com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolDef.PoolType;
import com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolXMLParser;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = DeleteACfileToFencedHostCommand.class)
public final class LibvirtDeleteACfileToFencedHostCommandWrapper extends CommandWrapper<DeleteACfileToFencedHostCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(final DeleteACfileToFencedHostCommand command, final LibvirtComputingResource libvirtComputingResource) {
        final StorageFilerTO pool = command.getPool();
        final KVMStoragePoolManager storagePoolMgr = libvirtComputingResource.getStoragePoolMgr();

        KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(pool.getType(), pool.getUuid());
        if (primaryPool.isPoolSupportHA()){

            logger.info("RBD Pool or GFS Pool Setting...");
            Connect conn = null;
            try {
                conn = LibvirtConnection.getConnection();
            } catch (LibvirtException e) {
                throw new CloudRuntimeException(e.toString());
            }

            try {
                String[] poolnames = conn.listStoragePools();
                if (poolnames.length == 0) {
                    logger.info("Didn't find an existing storage pool");
                }
                for (String poolname : poolnames) {
                    logger.debug("Checking path of existing pool " + poolname + " against pool we want to create");
                    StoragePool sp = conn.storagePoolLookupByName(poolname);
                    String poolDefXML = sp.getXMLDesc(0);
                    LibvirtStoragePoolXMLParser parser = new LibvirtStoragePoolXMLParser();
                    LibvirtStoragePoolDef pdef =  parser.parseStoragePoolXML(poolDefXML);
                    if (pdef == null) {
                        throw new CloudRuntimeException("Unable to parse the storage pool definition for storage pool " + poolname);
                    }
                    if (pdef.getPoolType() == PoolType.RBD) {
                        logger.debug(String.format("RBD Pool name [%s] auth name [%s]", pdef.getSourceDir(), pdef.getAuthUserName()));
                        Script.runSimpleBashScript("rbd -p " + pdef.getSourceDir() + " --id " + pdef.getAuthUserName() + " -m " + pdef.getSourceHost() + " -K /var/lib/libvirt/images/"+ pdef.getPoolName()+ " rm MOLD-HB");
                        Script.runSimpleBashScript("rbd -p " + pdef.getSourceDir() + " --id " + pdef.getAuthUserName() + " -m " + pdef.getSourceHost() + " -K /var/lib/libvirt/images/"+ pdef.getPoolName()+ " rm MOLD-AC");
                    }
                    if (pdef.getPoolType() == PoolType.DIR || pdef.getPoolType() == PoolType.NETFS) {
                        logger.debug(String.format("SharedMountPoint Pool source path [%s]", pdef.getTargetPath()));

                        Script.runSimpleBashScript("rm -rf " + pdef.getTargetPath() + "/MOLD-AC");
                    }
                }
                return new Answer(command, true, "success");
            } catch (LibvirtException e) {
                logger.error("Failure in attempting to see if an existing storage pool might be using the path of the pool to be created:" + e);
            }
        }
        return new Answer(command, true, "success");
    }
}
