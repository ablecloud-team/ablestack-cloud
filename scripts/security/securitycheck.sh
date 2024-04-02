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

# Security Check 
# JAVA로 작성된 Test 코드를 실행하는 Junit.jar 파일을 사용하여 Utils, API, Framework 모듈이 정상적으로 작동하는지 확인
# 저장된 scripts 파일이 정상적으로 동작하는지 확인
# 실행되는 프로세스가 정상적으로 동작하는지 확인
# return : {테스트 파일의 클래스 명 또는 파일 명 또는 프로세스 명, 결과}

jarfile='/usr/share/cloudstack-common/lib/'


# utils 55 count
utils_cmd=${jarfile}junit-4.13.2.jar:${jarfile}hamcrest-all-1.3.jar:${jarfile}cloudstack-utils-test.jar:${jarfile}cloudstack-utils.jar:${jarfile}commons-lang-2.6.jar:${jarfile}commons-io-2.8.0.jar:${jarfile}bcprov-jdk15on-1.70.jar:${jarfile}guava-testlib-18.0.jar:${jarfile}guava-31.1-jre.jar:${jarfile}httpcore-4.4.16.jar:${jarfile}httpclient-4.5.14.jar:${jarfile}mockito-core-3.12.4.jar:${jarfile}byte-buddy-1.10.5.jar:${jarfile}byte-buddy-agent-1.10.5.jar:${jarfile}commons-validator-1.6.jar:${jarfile}commons-net-3.7.2.jar:${jarfile}commons-lang3-3.11.jar:${jarfile}java-ipv6-0.17.jar:${jarfile}objenesis-3.2.jar:${jarfile}commons-collections4-4.4.jar:${jarfile}commons-collections-3.2.2.jar:${jarfile}spring-core-5.3.26.jar:${jarfile}commons-logging-1.2.jar:${jarfile}gson-1.7.2.jar:${jarfile}jackson-core-2.13.3.jar:${jarfile}jackson-databind-2.13.3.jar:${jarfile}jackson-annotations-2.13.3.jar:${jarfile}trilead-ssh2-1.0.0-build217.jar:${jarfile}joda-time-2.12.5.jar:${jarfile}jsch-0.1.55.jar:${jarfile}commons-compress-1.21.jar:${jarfile}reflections-0.10.2.jar:${jarfile}commons-httpclient-3.1.jar:${jarfile}xercesImpl-2.12.2.jar:${jarfile}nashorn-core-15.3.jar:${jarfile}activation-1.1.1.jar:${jarfile}mail-1.5.0-b01.jar:${jarfile}bcpkix-jdk15on-1.70.jar:${jarfile}bctls-jdk15on-1.70.jar:${jarfile}bcutil-jdk15on-1.70.jar:${jarfile}aws-java-sdk-core-1.12.439.jar:${jarfile}junit-dataprovider-1.13.1.jar:${jarfile}javax.servlet-api-4.0.1.jar:${jarfile}spring-test-5.3.26.jar

utils_class_name=("com.cloud.utils.backoff.impl.ConstantTimeBackoffTest" "com.cloud.utils.compression.CompressionUtilTest" "com.cloud.utils.crypt.EncryptionSecretKeyCheckerTest" "com.cloud.utils.crypto.EncryptionSecretKeyCheckerTest" "com.cloud.utils.crypto.RSAHelperTest" "com.cloud.utils.encoding.UrlEncoderTest" "com.cloud.utils.exception.ExceptionUtilTest" "com.cloud.utils.net.Ip4AddressTest" "com.cloud.utils.net.IpTest" "com.cloud.utils.net.MacAddressTest" "com.cloud.utils.net.NetUtilsTest" "com.cloud.utils.rest.BasicRestClientTest" "com.cloud.utils.rest.HttpClientHelperTest" "com.cloud.utils.rest.HttpStatusCodeHelperTest" "com.cloud.utils.rest.HttpUriRequestBuilderTest" "com.cloud.utils.rest.RESTServiceConnectorTest" "com.cloud.utils.security.SSLUtilsTest" "com.cloud.utils.ssh.SshHelperTest" "com.cloud.utils.ssh.SSHKeysHelperTest" "com.cloud.utils.storage.QCOW2UtilsTest" "com.cloud.utils.testcase.NioTest" "com.cloud.utils.validation.ChecksumUtilTest" "com.cloud.utils.xmlobject.TestXmlObject" "com.cloud.utils.xmlobject.TestXmlObject2" "com.cloud.utils.DateUtilTest" "com.cloud.utils.FileUtilTest" "com.cloud.utils.HttpUtilsTest" "com.cloud.utils.HumanReadableJsonTest" "com.cloud.utils.LogUtilsTest" "com.cloud.utils.NumbersUtilTest" "com.cloud.utils.PasswordGeneratorTest" "com.cloud.utils.ProcessUtilTest" "com.cloud.utils.PropertiesUtilsTest" "com.cloud.utils.ReflectUtilTest" "com.cloud.utils.ScriptTest" "com.cloud.utils.StringUtilsTest" "com.cloud.utils.SwiftUtilTest" "com.cloud.utils.TernaryTest" "com.cloud.utils.TestProfiler" "com.cloud.utils.UriUtilsParametrizedTest" "com.cloud.utils.UriUtilsTest" "com.cloud.utils.UuidUtilsTest" "org.apache.cloudstack.utils.bytescale.ByteScaleUtilsTest" "org.apache.cloudstack.utils.hypervisor.HypervisorUtilsTest" "org.apache.cloudstack.utils.imagestore.ImageStoreUtilTest" "org.apache.cloudstack.utils.jsinterpreter.JsInterpreterTest" "org.apache.cloudstack.utils.mailing.SMTPMailSenderTest" "org.apache.cloudstack.utils.process.ProcessTest" "org.apache.cloudstack.utils.redfish.RedfishClientTest" "org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtilsTest" "org.apache.cloudstack.utils.security.CertUtilsTest" "org.apache.cloudstack.utils.security.DigestHelperTest" "org.apache.cloudstack.utils.security.ParserUtilsTest" "org.apache.cloudstack.utils.volume.VirtualMachineDiskInfoTest" "org.apache.cloudstack.utils.CloudStackVersionTest")

for i in  "${utils_class_name[@]}"
do
    utils_class=$(echo $i)
    utils_result=$(java -classpath $utils_cmd org.junit.runner.JUnitCore $utils_class | grep -i OK)
    if [ -n "$utils_result" ]; then
        echo "$utils_class,true"
    else
        echo "$utils_class,false"
    fi
done

# api 75 count
api_cmd=${jarfile}junit-4.13.2.jar:${jarfile}hamcrest-all-1.3.jar:${jarfile}cloudstack-utils.jar:${jarfile}cloudstack-api-test.jar:${jarfile}cloudstack-api.jar:${jarfile}commons-lang3-3.11.jar:${jarfile}commons-collections-3.2.2.jar:${jarfile}log4j-api-2.23.0.jar:${jarfile}guava-31.1-jre.jar:${jarfile}mockito-core-3.12.4.jar:${jarfile}byte-buddy-1.10.5.jar:${jarfile}cloudstack-framework-config.jar:${jarfile}spring-test-5.3.26.jar:${jarfile}commons-logging-1.2.jar:${jarfile}spring-core-5.3.26.jar:${jarfile}cloudstack-framework-direct-download.jar:${jarfile}cloudstack-framework-managed-context.jar:${jarfile}xercesImpl-2.12.2.jar:${jarfile}commons-lang-2.6.jar:${jarfile}java-ipv6-0.17.jar:${jarfile}objenesis-3.2.jar:${jarfile}commons-collections4-4.4.jar

api_class_name=("com.cloud.agent.api.storage.OVFHelperTest" "com.cloud.agent.api.to.LoadBalancerTOTest" "com.cloud.deploy.DataCenterDeploymentTest" "com.cloud.host.ControlStateTest" "com.cloud.network.as.AutoScalePolicyTest" "com.cloud.network.as.AutoScaleVmGroupTest" "com.cloud.network.router.VirtualRouterAutoScaleTest" "com.cloud.network.IsolationMethodTest" "com.cloud.network.NetworksTest" "com.cloud.storage.StorageTest" "com.cloud.user.AccountTypeTest" "org.apache.cloudstack.acl.RoleTypeTest" "org.apache.cloudstack.acl.RuleTest" "org.apache.cloudstack.api.command.admin.account.CreateAccountCmdTest" "org.apache.cloudstack.api.command.admin.annotation.AddAnnotationCmdTest" "org.apache.cloudstack.api.command.admin.offering.CreateDiskOfferingCmdTest" "org.apache.cloudstack.api.command.admin.offering.CreateNetworkOfferingCmdTest" "org.apache.cloudstack.api.command.admin.offering.CreateServiceOfferingCmdTest" "org.apache.cloudstack.api.command.admin.storage.CreateSecondaryStagingStoreCmdTest" "org.apache.cloudstack.api.command.admin.storage.FindStoragePoolsForMigrationCmdTest" "org.apache.cloudstack.api.command.admin.systemvm.PatchSystemVMCmdTest" "org.apache.cloudstack.api.command.admin.user.CreateUserCmdTest" "org.apache.cloudstack.api.command.admin.vlan.UpdateVlanIpRangeCmdTest" "org.apache.cloudstack.api.command.admin.vm.MigrateVirtualMachineWithVolumeCmdTest" "org.apache.cloudstack.api.command.admin.vpc.CreateVPCOfferingCmdTest" "org.apache.cloudstack.api.command.admin.zone.CreateZoneCmdTest" "org.apache.cloudstack.api.command.test.ActivateProjectCmdTest" "org.apache.cloudstack.api.command.test.AddAccountToProjectCmdTest" "org.apache.cloudstack.api.command.test.AddClusterCmdTest" "org.apache.cloudstack.api.command.test.AddHostCmdTest" "org.apache.cloudstack.api.command.test.AddIpToVmNicTest" "org.apache.cloudstack.api.command.test.AddNetworkServiceProviderCmdTest" "org.apache.cloudstack.api.command.test.AddSecondaryStorageCmdTest" "org.apache.cloudstack.api.command.test.AddVpnUserCmdTest" "org.apache.cloudstack.api.command.test.CreateAutoScaleVmProfileCmdTest" "org.apache.cloudstack.api.command.test.CreateRoleCmdTest" "org.apache.cloudstack.api.command.test.CreateSnapshotCmdTest" "org.apache.cloudstack.api.command.test.ImportRoleCmdTest" "org.apache.cloudstack.api.command.test.ListCfgCmdTest" "org.apache.cloudstack.api.command.test.RegionCmdTest" "org.apache.cloudstack.api.command.test.ResetVMUserDataCmdTest" "org.apache.cloudstack.api.command.test.ScaleVMCmdTest" "org.apache.cloudstack.api.command.test.UpdateAutoScaleVmProfileCmdTest" "org.apache.cloudstack.api.command.test.UpdateCfgCmdTest" "org.apache.cloudstack.api.command.test.UpdateConditionCmdTest" "org.apache.cloudstack.api.command.test.UpdateHostPasswordCmdTest" "org.apache.cloudstack.api.command.test.UpdateRoleCmdTest" "org.apache.cloudstack.api.command.test.UpdateVmNicIpTest" "org.apache.cloudstack.api.command.test.UsageCmdTest" "org.apache.cloudstack.api.command.user.firewall.CreateEgressFirewallRuleCmdTest" "org.apache.cloudstack.api.command.user.iso.RegisterIsoCmdTest" "org.apache.cloudstack.api.command.user.network.CreateNetworkCmdTest" "org.apache.cloudstack.api.command.user.network.UpdateNetworkCmdTest" "org.apache.cloudstack.api.command.user.project.CreateProjectCmdTest" "org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotPolicyCmdTest" "org.apache.cloudstack.api.command.user.template.CopyTemplateCmdByAdminTest" "org.apache.cloudstack.api.command.user.template.CopyTemplateCmdTest" "org.apache.cloudstack.api.command.user.template.RegisterTemplateCmdByAdminTest" "org.apache.cloudstack.api.command.user.template.RegisterTemplateCmdTest" "org.apache.cloudstack.api.command.user.userdata.DeleteUserDataCmdTest" "org.apache.cloudstack.api.command.user.userdata.LinkUserDataToTemplateCmdTest" "org.apache.cloudstack.api.command.user.userdata.ListUserDataCmdTest" "org.apache.cloudstack.api.command.user.userdata.RegisterUserDataCmdTest" "org.apache.cloudstack.api.command.user.vm.CreateVMScheduleCmdTest" "org.apache.cloudstack.api.command.user.vm.DeleteVMScheduleCmdTest" "org.apache.cloudstack.api.command.user.vm.ListVMScheduleCmdTest" "org.apache.cloudstack.api.command.user.vm.UpdateVMScheduleCmdTest" "org.apache.cloudstack.api.command.user.vpc.CreateVPCCmdTest" "org.apache.cloudstack.api.command.user.vpc.UpdateVPCCmdTest" "org.apache.cloudstack.api.response.HostResponseTest" "org.apache.cloudstack.api.response.StatsResponseTest" "org.apache.cloudstack.api.ApiCommandResourceTypeTest" "org.apache.cloudstack.api.BaseCmdTest" "org.apache.cloudstack.context.CallContextTest" "org.apache.cloudstack.usage.UsageUnitTypesTest")

for i in  "${api_class_name[@]}"
do
    api_class=$(echo $i)
    api_result=$(java -classpath $api_cmd org.junit.runner.JUnitCore $api_class | grep -i OK)
    if [ -n "$api_result" ]; then
        echo "$api_class,true"
    else
        echo "$api_class,false"
    fi
done

# framework 29 count
fw_cmd=${jarfile}junit-4.13.2.jar:${jarfile}hamcrest-all-1.3.jar:${jarfile}cloudstack-utils.jar:${jarfile}cloudstack-framework-cluster.jar:${jarfile}cloudstack-framework-cluster-test.jar:${jarfile}mockito-core-3.12.4.jar:${jarfile}byte-buddy-1.10.5.jar:${jarfile}cloudstack-framework-db.jar:${jarfile}cloudstack-framework-config.jar:${jarfile}cloudstack-api.jar:${jarfile}objenesis-3.2.jar:${jarfile}httpcore-4.4.16.jar:${jarfile}cloudstack-framework-config-test.jar:${jarfile}commons-lang-2.6.jar:${jarfile}guava-testlib-18.0.jar:${jarfile}guava-31.1-jre.jar:${jarfile}cloudstack-framework-db-test.jar:${jarfile}gson-1.7.2.jar:${jarfile}cloudstack-engine-schema.jar:${jarfile}cloudstack-engine-api.jar:${jarfile}commons-configuration-1.10.jar:${jarfile}spring-test-5.3.26.jar:${jarfile}commons-logging-1.2.jar:${jarfile}spring-core-5.3.26.jar:${jarfile}reflections-0.10.2.jar:${jarfile}cglib-nodep-3.3.0.jar:${jarfile}javax.persistence-2.2.1.jar:${jarfile}commons-lang3-3.11.jar:${jarfile}cloudstack-framework-ipc-test.jar:${jarfile}cloudstack-framework-ipc.jar:${jarfile}cloudstack-framework-jobs.jar:${jarfile}cloudstack-framework-jobs-test.jar:${jarfile}cloudstack-framework-managed-context.jar:${jarfile}cloudstack-framework-managed-context-test.jar:${jarfile}cloudstack-framework-quota-test.jar:${jarfile}cloudstack-framework-quota.jar:${jarfile}mail-1.5.0-b01.jar:${jarfile}commons-dbcp2-2.9.0.jar:${jarfile}commons-pool2-2.9.0.jar:${jarfile}commons-collections-3.2.2.jar:${jarfile}cloudstack-usage.jar:${jarfile}nashorn-core-15.3.jar:${jarfile}jackson-core-2.13.3.jar:${jarfile}jackson-databind-2.13.3.jar:${jarfile}jackson-module-jaxb-annotations-2.13.3.jar:${jarfile}cloudstack-framework-rest-test.jar:${jarfile}cloudstack-framework-rest.jar:${jarfile}cloudstack-framework-spring-module-test.jar:${jarfile}cloudstack-framework-spring-module.jar:${jarfile}spring-beans-5.3.26.jar:${jarfile}spring-context-5.3.26.jar:${jarfile}log4j-api-2.23.0.jar:${jarfile}spring-expression-5.3.26.jar:${jarfile}spring-aop-5.3.26.jar:${jarfile}commons-logging-1.2.jar:${jarfile}commons-io-2.8.0.jar

fw_class_name=("com.cloud.cluster.ClusterServiceServletAdapterTest" "org.apache.cloudstack.framework.config.impl.ConfigDepotAdminTest" "org.apache.cloudstack.framework.config.impl.ConfigDepotImplTest" "org.apache.cloudstack.framework.config.ConfigKeyTest" "com.cloud.utils.crypt.EncryptionSecretKeyChangerTest" "com.cloud.utils.db.ElementCollectionTest" "com.cloud.utils.db.FilterTest" "com.cloud.utils.db.GenericDaoBaseTest" "org.apache.cloudstack.framework.jobs.AsyncJobManagerTest" "org.apache.cloudstack.managed.context.impl.DefaultManagedContextTest" "org.apache.cloudstack.quota.activationrule.presetvariables.AccountTest" "org.apache.cloudstack.quota.activationrule.presetvariables.BackupOfferingTest" "org.apache.cloudstack.quota.activationrule.presetvariables.ComputeOfferingTest" "org.apache.cloudstack.quota.activationrule.presetvariables.ComputingResourcesTest" "org.apache.cloudstack.quota.activationrule.presetvariables.DomainTest" "org.apache.cloudstack.quota.activationrule.presetvariables.GenericPresetVariableTest" "org.apache.cloudstack.quota.activationrule.presetvariables.HostTest" "org.apache.cloudstack.quota.activationrule.presetvariables.PresetVariableHelperTest" "org.apache.cloudstack.quota.activationrule.presetvariables.ResourceTest" "org.apache.cloudstack.quota.activationrule.presetvariables.RoleTest" "org.apache.cloudstack.quota.activationrule.presetvariables.StorageTest" "org.apache.cloudstack.quota.activationrule.presetvariables.ValueTest" "org.apache.cloudstack.quota.constant.QuotaTypesTest" "org.apache.cloudstack.quota.vo.QuotaTariffVOTest" "org.apache.cloudstack.quota.QuotaManagerImplTest" "org.apache.cloudstack.framework.ws.jackson.CSJacksonAnnotationTest" "org.apache.cloudstack.spring.module.factory.ModuleBasedContextFactoryTest" "org.apache.cloudstack.spring.module.locator.impl.ClasspathModuleDefinitionSetLocatorTest" "org.apache.cloudstack.spring.module.model.impl.DefaultModuleDefinitionTest")

for i in  "${fw_class_name[@]}"
do
    fw_class=$(echo $i)
    fw_result=$(java -classpath $fw_cmd org.junit.runner.JUnitCore $fw_class | grep -i OK)
    if [ -n "$fw_result" ]; then
        echo "$fw_class,true"
    else
        echo "$fw_class,false"
    fi
done

SCRIPT_PATH="/usr/share/cloudstack-common/scripts"
# scripts 112 count
script_cmds=("$SCRIPT_PATH/installer/createtmplt.sh" "$SCRIPT_PATH/installer/createvolume.sh" "$SCRIPT_PATH/installer/export-templates.sh" "$SCRIPT_PATH/installer/installcentos.sh" "$SCRIPT_PATH/installer/installdomp.sh" "$SCRIPT_PATH/installer/run_installer.sh" "$SCRIPT_PATH/network/domr/router_proxy.sh" "$SCRIPT_PATH/network/exdhcp/dhcpd_edithosts.py" "$SCRIPT_PATH/network/exdhcp/dnsmasq_edithosts.sh" "$SCRIPT_PATH/network/exdhcp/prepare_dhcpd.sh" "$SCRIPT_PATH/network/exdhcp/prepare_dnsmasq.sh" "$SCRIPT_PATH/network/ping/baremetal_user_data.py" "$SCRIPT_PATH/network/ping/prepare_kickstart_bootfile.py" "$SCRIPT_PATH/network/ping/prepare_kickstart_kernel_initrd.py" "$SCRIPT_PATH/network/ping/prepare_tftp_bootfile.py" "$SCRIPT_PATH/storage/checkchildren.sh" "$SCRIPT_PATH/storage/installIso.sh" "$SCRIPT_PATH/storage/qcow2/create_private_template.sh" "$SCRIPT_PATH/storage/qcow2/createtmplt.sh" "$SCRIPT_PATH/storage/qcow2/createvm.sh" "$SCRIPT_PATH/storage/qcow2/createvolume.sh" "$SCRIPT_PATH/storage/qcow2/delvm.sh" "$SCRIPT_PATH/storage/qcow2/get_domr_kernel.sh" "$SCRIPT_PATH/storage/qcow2/get_iqn.sh" "$SCRIPT_PATH/storage/qcow2/importmpl.sh" "$SCRIPT_PATH/storage/qcow2/listvmdisk.sh" "$SCRIPT_PATH/storage/qcow2/listvmdisksize.sh" "$SCRIPT_PATH/storage/qcow2/listvmtmplt.sh" "$SCRIPT_PATH/storage/qcow2/listvolume.sh" "$SCRIPT_PATH/storage/qcow2/managesnapshot.sh" "$SCRIPT_PATH/storage/qcow2/managevolume.sh" "$SCRIPT_PATH/storage/qcow2/resizevolume.sh" "$SCRIPT_PATH/storage/secondary/cloud-install-sys-tmplt.py" "$SCRIPT_PATH/storage/secondary/create_privatetemplate_from_snapshot_xen.sh" "$SCRIPT_PATH/storage/secondary/createtmplt.sh" "$SCRIPT_PATH/storage/secondary/createvolume.sh" "$SCRIPT_PATH/storage/secondary/installIso.sh" "$SCRIPT_PATH/storage/secondary/listvmtmplt.sh" "$SCRIPT_PATH/storage/secondary/listvolume.sh" "$SCRIPT_PATH/util/create-kubernetes-binaries-iso.sh" "$SCRIPT_PATH/util/ipmi.py" "$SCRIPT_PATH/util/macgen.py" "$SCRIPT_PATH/util/migrate-dynamicroles.py" "$SCRIPT_PATH/util/prepare_linmin.sh" "$SCRIPT_PATH/vm/hypervisor/kvm/kvmheartbeat_clvm.sh" "$SCRIPT_PATH/vm/hypervisor/kvm/kvmheartbeat_rbd.py" "$SCRIPT_PATH/vm/hypervisor/kvm/kvmheartbeat_rbd.sh" "$SCRIPT_PATH/vm/hypervisor/kvm/kvmheartbeat.sh" "$SCRIPT_PATH/vm/hypervisor/kvm/kvmvmactivity_clvm.sh" "$SCRIPT_PATH/vm/hypervisor/kvm/kvmvmactivity_rbd.py" "$SCRIPT_PATH/vm/hypervisor/kvm/kvmvmactivity_rbd.sh" "$SCRIPT_PATH/vm/hypervisor/kvm/kvmvmactivity.sh" "$SCRIPT_PATH/vm/hypervisor/kvm/nsrkvmbackup.sh" "$SCRIPT_PATH/vm/hypervisor/kvm/nsrkvmrestore.sh" "$SCRIPT_PATH/vm/hypervisor/kvm/patch.sh" "$SCRIPT_PATH/vm/hypervisor/kvm/setup_agent.sh" "$SCRIPT_PATH/vm/hypervisor/ovm3/cloudstack.py" "$SCRIPT_PATH/vm/hypervisor/ovm3/storagehealth.py" "$SCRIPT_PATH/vm/hypervisor/update_host_passwd.sh" "$SCRIPT_PATH/vm/hypervisor/versions.sh" "$SCRIPT_PATH/vm/hypervisor/vmware/discover_networks.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/add_to_vcpus_params_live.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/check_heartbeat.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/cloud-clean-vlan.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/cloud-prepare-upgrade.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/cloud-propagate-vlan.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/cloud-setup-bonding.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/cloudstack_pluginlib.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/copy_vhd_from_secondarystorage.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/copy_vhd_to_secondarystorage.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/create_privatetemplate_from_snapshot.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/kill_copy_process.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/launch_hb.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/make_migratable.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/mockxcpplugin.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/network_info.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/ovs-get-bridge.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/ovs-get-dhcp-iface.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/ovs-vif-flows.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/perfmon.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/setup_heartbeat_file.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/setup_heartbeat_sr.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/setup_iscsi.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/setupxenserver.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/upgrade_snapshot.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/upgrade_vnc_config.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/xcposs/NFSSR.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/xcpserver/NFSSR.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/xenheartbeat.sh" "$SCRIPT_PATH/vm/hypervisor/xenserver/xenserver56/InterfaceReconfigure.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/xenserver56/NFSSR.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/xenserver56fp1/NFSSR.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/xenserver60/NFSSR.py" "$SCRIPT_PATH/vm/hypervisor/xenserver/xs_cleanup.sh" "$SCRIPT_PATH/vm/network/ovs-pvlan-cleanup.sh" "$SCRIPT_PATH/vm/network/ovs-pvlan-dhcp-host.sh" "$SCRIPT_PATH/vm/network/ovs-pvlan-kvm-dhcp-host.sh" "$SCRIPT_PATH/vm/network/ovs-pvlan-kvm-vm.sh" "$SCRIPT_PATH/vm/network/ovs-pvlan-vm.sh" "$SCRIPT_PATH/vm/network/security_group.py" "$SCRIPT_PATH/vm/network/tungsten/create_tap_device.sh" "$SCRIPT_PATH/vm/network/tungsten/delete_tap_device.sh" "$SCRIPT_PATH/vm/network/tungsten/setup_tungsten_vrouter.sh" "$SCRIPT_PATH/vm/network/tungsten/update_tungsten_loadbalancer_ssl.sh" "$SCRIPT_PATH/vm/network/tungsten/update_tungsten_loadbalancer_stats.sh" "$SCRIPT_PATH/vm/network/vnet/cloudstack_pluginlib.py" "$SCRIPT_PATH/vm/network/vnet/modifyvlan.sh" "$SCRIPT_PATH/vm/network/vnet/modifyvxlan.sh" "$SCRIPT_PATH/vm/network/vnet/ovstunnel.py" "$SCRIPT_PATH/vm/pingtest.sh" "$SCRIPT_PATH/vm/systemvm/injectkeys.py" "$SCRIPT_PATH/vm/systemvm/injectkeys.sh")

for i in  "${script_cmds[@]}"
do
    utils_cmd=$(echo $i)
    UTILS_RESULT=$($utils_cmd 2>&1)
    case "${UTILS_RESULT,,}" in
            *usage* | *syntax* | *print* | *device* \
                    | *type* | *cloud* | *xe* | *ovs* \
                    | *operation* | *find* | *host* \
            )
                    echo "$utils_cmd,true"
                    ;;
            *)
                    echo "$utils_cmd,false"
                    ;;
    esac

done

systemctl status mysqld | grep -i running &> /dev/null
if [[ $? == 0 ]]; then
    echo "mysql service,true"
else
    echo "mysql service,false"
fi

systemctl status firewalld | grep -i running &> /dev/null
if [[ $? == 0 ]]; then
    echo "firewalld service,true"
else
    echo "firewalld service,false"
fi

systemctl status cloudstack-management | grep -i running &> /dev/null
if [[ $? == 0 ]]; then
    echo "management service,true"
else
    echo "management service,false"
fi
