#!/bin/bash

# mold-monitoring 서비스는 4시간 간격으로 매일 자체시험 (대상 : java, script, service)을 실행

# 변수
monitoring_file="/etc/cloudstack/management/monitoring.properties"
jar_file='/usr/share/cloudstack-common/lib/cloudstack-utils.jar'
securityjarfile='/usr/share/cloudstack-common/lib/'
scriptpath='/usr/share/cloudstack-common/scripts'

kek_pass=$(echo $1 | base64 --decode) > /dev/null 2>&1
host_ip=$(hostname -i)
cnt=0
fail=0
interval=4

# 자체시험 실행
function securitycheck {
        if [ -e "$monitoring_file" ]; then
                rm -rf $monitoring_file
        fi
        failed_files=""
        subject=""
        key=$(openssl enc -aria-256-cbc -a -d -pbkdf2 -k $kek_pass -saltlen 16 -md sha256 -iter 100000 -in /etc/cloudstack/management/key.enc)
        openssl enc -aes-256-cbc -d -K $key -pass pass:$kek_pass -saltlen 16 -md sha256 -iter 100000 -in /etc/cloudstack/management/db.properties.enc -out $monitoring_file
        db_enc_password=$(sed '/^\#/d' $monitoring_file | grep 'db.cloud.password'  | tail -n 1 | cut -d "=" -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'i | sed 's/^ENC(\(.*\))/\1/')
        enc_version=$(sed '/^\#/d' $monitoring_file | grep 'db.cloud.encryptor.version'  | tail -n 1 | cut -d "=" -f2-)
        enc_secret=$(sed '/^\#/d' $monitoring_file | grep 'db.cloud.encrypt.secret'  | tail -n 1 | cut -d "=" -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'i | sed 's/^ENC(\(.*\))/\1/')
        if [ -n "$key" ]; then
                echo "키 파일 복호화 완료--------------------------------------"
                database_password=$(java -classpath $jar_file com.cloud.utils.crypt.EncryptionCLI -d -i "$db_enc_password" -p $key $enc_version)
                secret=$(java -classpath $jar_file com.cloud.utils.crypt.EncryptionCLI -d -i "$enc_secret" -p $key $enc_version)
                if [ ! $database_password ] || [ ! $secret ]; then
                        echo "DB 설정 파일 및 DB 비밀키 복호화 실패--------------------------------------"
                        removeVariable
                else
                        echo "DB 설정 파일 및 DB 비밀키 복호화 완료--------------------------------------"
                        cnt=$((cnt+1))
                        mysql --user=root --password=$database_password -e "use cloud; SET GLOBAL foreign_key_checks=0;" > /dev/null 2>&1
                        mysql --user=root --password=$database_password -e "use cloud; CREATE TABLE IF NOT EXISTS security_check (id bigint unsigned NOT NULL AUTO_INCREMENT, mshost_id bigint unsigned NOT NULL COMMENT 'the ID of the mshost', check_result tinyint(1) default 1 not null comment 'check executions success or failure', check_date datetime DEFAULT NULL COMMENT 'the last security check time', check_failed_list mediumtext null, type varchar(32) null, service varchar(32) null, PRIMARY KEY (id), KEY i_security_checks__mshost_id (mshost_id), CONSTRAINT fk_security_checks__mshost_id FOREIGN KEY (mshost_id) REFERENCES mshost (id) ON DELETE CASCADE) ENGINE=InnoDB CHARSET=utf8mb3;" > /dev/null 2>&1
                        value="$(mysql --user=root --password=$database_password -se "use cloud; SELECT value FROM configuration WHERE name='security.check.interval';")" > /dev/null 2>&1
                        if [ -n "$value" ]; then
                                interval=$value
                        fi
                        if [ $cnt -eq 1 ]; then
                                echo "Monitoring Execution 자체시험 시작 감사기록 생성-------------------------"
                                uuid=$(cat /proc/sys/kernel/random/uuid)
                                mysql --user=root --password=$database_password -e "use cloud; INSERT INTO event (uuid, type, state, description, user_id, account_id, domain_id, resource_id, created, level, start_id, archived, display, client_ip) VALUES ('$uuid', 'SECURITY.CHECK', 'Started', '제품 실행 시 모니터링 서비스에 대한 주기적인 보안점검을 실시합니다.', '1', '1', '1', '0', DATE_SUB(NOW(), INTERVAL 9 HOUR), 'INFO', '0', '0', '1', '$host_ip');" > /dev/null 2>&1
                        else    
                                echo "Monitoring Routine 자체시험 시작 감사기록 생성-------------------------"
                                uuid=$(cat /proc/sys/kernel/random/uuid)
                                mysql --user=root --password=$database_password -e "use cloud; INSERT INTO event (uuid, type, state, description, user_id, account_id, domain_id, resource_id, created, level, start_id, archived, display, client_ip) VALUES ('$uuid', 'SECURITY.CHECK', 'Started', '제품 작동 시 모니터링 서비스에 대한 주기적인 보안점검을 실시합니다.', '1', '1', '1', '0', DATE_SUB(NOW(), INTERVAL 9 HOUR), 'INFO', '0', '0', '1', '$host_ip');" > /dev/null 2>&1
                        fi

                        echo "자체시험 시작-------------------------------------------------------"
                        # utils 55 count
                        utils_cmd=${securityjarfile}junit-4.13.2.jar:${securityjarfile}hamcrest-all-1.3.jar:${securityjarfile}cloudstack-utils-test.jar:${securityjarfile}cloudstack-utils.jar:${securityjarfile}commons-lang-2.6.jar:${securityjarfile}commons-io-2.8.0.jar:${securityjarfile}bcprov-jdk15on-1.70.jar:${securityjarfile}guava-testlib-18.0.jar:${securityjarfile}guava-31.1-jre.jar:${securityjarfile}httpcore-4.4.16.jar:${securityjarfile}httpclient-4.5.14.jar:${securityjarfile}mockito-core-3.12.4.jar:${securityjarfile}byte-buddy-1.10.5.jar:${securityjarfile}byte-buddy-agent-1.10.5.jar:${securityjarfile}commons-validator-1.6.jar:${securityjarfile}commons-net-3.7.2.jar:${securityjarfile}commons-lang3-3.11.jar:${securityjarfile}java-ipv6-0.17.jar:${securityjarfile}objenesis-3.2.jar:${securityjarfile}commons-collections4-4.4.jar:${securityjarfile}commons-collections-3.2.2.jar:${securityjarfile}spring-core-5.3.26.jar:${securityjarfile}commons-logging-1.2.jar:${securityjarfile}gson-1.7.2.jar:${securityjarfile}jackson-core-2.13.3.jar:${securityjarfile}jackson-databind-2.13.3.jar:${securityjarfile}jackson-annotations-2.13.3.jar:${securityjarfile}trilead-ssh2-1.0.0-build217.jar:${securityjarfile}joda-time-2.12.5.jar:${securityjarfile}jsch-0.1.55.jar:${securityjarfile}commons-compress-1.21.jar:${securityjarfile}reflections-0.10.2.jar:${securityjarfile}commons-httpclient-3.1.jar:${securityjarfile}xercesImpl-2.12.2.jar:${securityjarfile}nashorn-core-15.3.jar:${securityjarfile}activation-1.1.1.jar:${securityjarfile}mail-1.5.0-b01.jar:${securityjarfile}bcpkix-jdk15on-1.70.jar:${securityjarfile}bctls-jdk15on-1.70.jar:${securityjarfile}bcutil-jdk15on-1.70.jar:${securityjarfile}aws-java-sdk-core-1.12.439.jar:${securityjarfile}junit-dataprovider-1.13.1.jar:${securityjarfile}javax.servlet-api-4.0.1.jar:${securityjarfile}spring-test-5.3.26.jar

                        utils_class_name=("com.cloud.utils.backoff.impl.ConstantTimeBackoffTest" "com.cloud.utils.compression.CompressionUtilTest" "com.cloud.utils.crypt.EncryptionSecretKeyCheckerTest" "com.cloud.utils.crypto.EncryptionSecretKeyCheckerTest" "com.cloud.utils.crypto.RSAHelperTest" "com.cloud.utils.encoding.UrlEncoderTest" "com.cloud.utils.exception.ExceptionUtilTest" "com.cloud.utils.net.Ip4AddressTest" "com.cloud.utils.net.IpTest" "com.cloud.utils.net.MacAddressTest" "com.cloud.utils.net.NetUtilsTest" "com.cloud.utils.rest.BasicRestClientTest" "com.cloud.utils.rest.HttpClientHelperTest" "com.cloud.utils.rest.HttpStatusCodeHelperTest" "com.cloud.utils.rest.HttpUriRequestBuilderTest" "com.cloud.utils.rest.RESTServiceConnectorTest" "com.cloud.utils.security.SSLUtilsTest" "com.cloud.utils.ssh.SshHelperTest" "com.cloud.utils.ssh.SSHKeysHelperTest" "com.cloud.utils.storage.QCOW2UtilsTest" "com.cloud.utils.testcase.NioTest" "com.cloud.utils.validation.ChecksumUtilTest" "com.cloud.utils.xmlobject.TestXmlObject" "com.cloud.utils.xmlobject.TestXmlObject2" "com.cloud.utils.DateUtilTest" "com.cloud.utils.FileUtilTest" "com.cloud.utils.HttpUtilsTest" "com.cloud.utils.HumanReadableJsonTest" "com.cloud.utils.LogUtilsTest" "com.cloud.utils.NumbersUtilTest" "com.cloud.utils.PasswordGeneratorTest" "com.cloud.utils.ProcessUtilTest" "com.cloud.utils.PropertiesUtilsTest" "com.cloud.utils.ReflectUtilTest" "com.cloud.utils.ScriptTest" "com.cloud.utils.StringUtilsTest" "com.cloud.utils.SwiftUtilTest" "com.cloud.utils.TernaryTest" "com.cloud.utils.TestProfiler" "com.cloud.utils.UriUtilsParametrizedTest" "com.cloud.utils.UriUtilsTest" "com.cloud.utils.UuidUtilsTest" "org.apache.cloudstack.utils.bytescale.ByteScaleUtilsTest" "org.apache.cloudstack.utils.hypervisor.HypervisorUtilsTest" "org.apache.cloudstack.utils.imagestore.ImageStoreUtilTest" "org.apache.cloudstack.utils.jsinterpreter.JsInterpreterTest" "org.apache.cloudstack.utils.mailing.SMTPMailSenderTest" "org.apache.cloudstack.utils.process.ProcessTest" "org.apache.cloudstack.utils.redfish.RedfishClientTest" "org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtilsTest" "org.apache.cloudstack.utils.security.CertUtilsTest" "org.apache.cloudstack.utils.security.DigestHelperTest" "org.apache.cloudstack.utils.security.ParserUtilsTest" "org.apache.cloudstack.utils.volume.VirtualMachineDiskInfoTest" "org.apache.cloudstack.utils.CloudStackVersionTest")

                        for i in  "${utils_class_name[@]}"
                        do
                                utils_class=$(echo $i)
                                utils_result=$(java -classpath $utils_cmd org.junit.runner.JUnitCore $utils_class | grep -i OK) > /dev/null 2>&1
                                if [ -n "$utils_result" ]; then
                                echo "$utils_class,true" > /dev/null 2>&1
                                else
                                echo "$utils_class,false" > /dev/null 2>&1
                                failed_files+="$utils_class, "
                                fail=$((fail+1))
                                fi
                        done

                        # api 75 count
                        api_cmd=${securityjarfile}junit-4.13.2.jar:${securityjarfile}hamcrest-all-1.3.jar:${securityjarfile}cloudstack-utils.jar:${securityjarfile}cloudstack-api-test.jar:${securityjarfile}cloudstack-api.jar:${securityjarfile}commons-lang3-3.11.jar:${securityjarfile}commons-collections-3.2.2.jar:${securityjarfile}log4j-api-2.23.1.jar:${securityjarfile}guava-31.1-jre.jar:${securityjarfile}mockito-core-3.12.4.jar:${securityjarfile}byte-buddy-1.10.5.jar:${securityjarfile}cloudstack-framework-config.jar:${securityjarfile}spring-test-5.3.26.jar:${securityjarfile}commons-logging-1.2.jar:${securityjarfile}spring-core-5.3.26.jar:${securityjarfile}cloudstack-framework-direct-download.jar:${securityjarfile}cloudstack-framework-managed-context.jar:${securityjarfile}xercesImpl-2.12.2.jar:${securityjarfile}commons-lang-2.6.jar:${securityjarfile}java-ipv6-0.17.jar:${securityjarfile}objenesis-3.2.jar:${securityjarfile}commons-collections4-4.4.jar

                        api_class_name=("com.cloud.agent.api.storage.OVFHelperTest" "com.cloud.agent.api.to.LoadBalancerTOTest" "com.cloud.deploy.DataCenterDeploymentTest" "com.cloud.host.ControlStateTest" "com.cloud.network.as.AutoScalePolicyTest" "com.cloud.network.as.AutoScaleVmGroupTest" "com.cloud.network.router.VirtualRouterAutoScaleTest" "com.cloud.network.IsolationMethodTest" "com.cloud.network.NetworksTest" "com.cloud.storage.StorageTest" "com.cloud.user.AccountTypeTest" "org.apache.cloudstack.acl.RoleTypeTest" "org.apache.cloudstack.acl.RuleTest" "org.apache.cloudstack.api.command.admin.account.CreateAccountCmdTest" "org.apache.cloudstack.api.command.admin.annotation.AddAnnotationCmdTest" "org.apache.cloudstack.api.command.admin.offering.CreateDiskOfferingCmdTest" "org.apache.cloudstack.api.command.admin.offering.CreateNetworkOfferingCmdTest" "org.apache.cloudstack.api.command.admin.offering.CreateServiceOfferingCmdTest" "org.apache.cloudstack.api.command.admin.storage.CreateSecondaryStagingStoreCmdTest" "org.apache.cloudstack.api.command.admin.storage.FindStoragePoolsForMigrationCmdTest" "org.apache.cloudstack.api.command.admin.systemvm.PatchSystemVMCmdTest" "org.apache.cloudstack.api.command.admin.user.CreateUserCmdTest" "org.apache.cloudstack.api.command.admin.vlan.UpdateVlanIpRangeCmdTest" "org.apache.cloudstack.api.command.admin.vm.MigrateVirtualMachineWithVolumeCmdTest" "org.apache.cloudstack.api.command.admin.vpc.CreateVPCOfferingCmdTest" "org.apache.cloudstack.api.command.admin.zone.CreateZoneCmdTest" "org.apache.cloudstack.api.command.test.ActivateProjectCmdTest" "org.apache.cloudstack.api.command.test.AddAccountToProjectCmdTest" "org.apache.cloudstack.api.command.test.AddClusterCmdTest" "org.apache.cloudstack.api.command.test.AddHostCmdTest" "org.apache.cloudstack.api.command.test.AddIpToVmNicTest" "org.apache.cloudstack.api.command.test.AddNetworkServiceProviderCmdTest" "org.apache.cloudstack.api.command.test.AddSecondaryStorageCmdTest" "org.apache.cloudstack.api.command.test.AddVpnUserCmdTest" "org.apache.cloudstack.api.command.test.CreateAutoScaleVmProfileCmdTest" "org.apache.cloudstack.api.command.test.CreateRoleCmdTest" "org.apache.cloudstack.api.command.test.CreateSnapshotCmdTest" "org.apache.cloudstack.api.command.test.ImportRoleCmdTest" "org.apache.cloudstack.api.command.test.ListCfgCmdTest" "org.apache.cloudstack.api.command.test.RegionCmdTest" "org.apache.cloudstack.api.command.test.ResetVMUserDataCmdTest" "org.apache.cloudstack.api.command.test.ScaleVMCmdTest" "org.apache.cloudstack.api.command.test.UpdateAutoScaleVmProfileCmdTest" "org.apache.cloudstack.api.command.test.UpdateCfgCmdTest" "org.apache.cloudstack.api.command.test.UpdateConditionCmdTest" "org.apache.cloudstack.api.command.test.UpdateHostPasswordCmdTest" "org.apache.cloudstack.api.command.test.UpdateRoleCmdTest" "org.apache.cloudstack.api.command.test.UpdateVmNicIpTest" "org.apache.cloudstack.api.command.test.UsageCmdTest" "org.apache.cloudstack.api.command.user.firewall.CreateEgressFirewallRuleCmdTest" "org.apache.cloudstack.api.command.user.iso.RegisterIsoCmdTest" "org.apache.cloudstack.api.command.user.network.CreateNetworkCmdTest" "org.apache.cloudstack.api.command.user.network.UpdateNetworkCmdTest" "org.apache.cloudstack.api.command.user.project.CreateProjectCmdTest" "org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotPolicyCmdTest" "org.apache.cloudstack.api.command.user.template.CopyTemplateCmdByAdminTest" "org.apache.cloudstack.api.command.user.template.CopyTemplateCmdTest" "org.apache.cloudstack.api.command.user.template.RegisterTemplateCmdByAdminTest" "org.apache.cloudstack.api.command.user.template.RegisterTemplateCmdTest" "org.apache.cloudstack.api.command.user.userdata.DeleteUserDataCmdTest" "org.apache.cloudstack.api.command.user.userdata.LinkUserDataToTemplateCmdTest" "org.apache.cloudstack.api.command.user.userdata.ListUserDataCmdTest" "org.apache.cloudstack.api.command.user.userdata.RegisterUserDataCmdTest" "org.apache.cloudstack.api.command.user.vm.CreateVMScheduleCmdTest" "org.apache.cloudstack.api.command.user.vm.DeleteVMScheduleCmdTest" "org.apache.cloudstack.api.command.user.vm.ListVMScheduleCmdTest" "org.apache.cloudstack.api.command.user.vm.UpdateVMScheduleCmdTest" "org.apache.cloudstack.api.command.user.vpc.CreateVPCCmdTest" "org.apache.cloudstack.api.command.user.vpc.UpdateVPCCmdTest" "org.apache.cloudstack.api.response.HostResponseTest" "org.apache.cloudstack.api.response.StatsResponseTest" "org.apache.cloudstack.api.ApiCommandResourceTypeTest" "org.apache.cloudstack.api.BaseCmdTest" "org.apache.cloudstack.context.CallContextTest" "org.apache.cloudstack.usage.UsageUnitTypesTest")

                        for i in  "${api_class_name[@]}"
                        do
                                api_class=$(echo $i)
                                api_result=$(java -classpath $api_cmd org.junit.runner.JUnitCore $api_class | grep -i OK) > /dev/null 2>&1
                                if [ -n "$api_result" ]; then
                                echo "$api_class,true" > /dev/null 2>&1
                                else
                                echo "$api_class,false" > /dev/null 2>&1
                                failed_files+="$api_class, "
                                fail=$((fail+1))
                                fi
                        done

                        # framework 29 count
                        fw_cmd=${securityjarfile}junit-4.13.2.jar:${securityjarfile}hamcrest-all-1.3.jar:${securityjarfile}cloudstack-utils.jar:${securityjarfile}cloudstack-framework-cluster.jar:${securityjarfile}cloudstack-framework-cluster-test.jar:${securityjarfile}mockito-core-3.12.4.jar:${securityjarfile}byte-buddy-1.10.5.jar:${securityjarfile}cloudstack-framework-db.jar:${securityjarfile}cloudstack-framework-config.jar:${securityjarfile}cloudstack-api.jar:${securityjarfile}objenesis-3.2.jar:${securityjarfile}httpcore-4.4.16.jar:${securityjarfile}cloudstack-framework-config-test.jar:${securityjarfile}commons-lang-2.6.jar:${securityjarfile}guava-testlib-18.0.jar:${securityjarfile}guava-31.1-jre.jar:${securityjarfile}cloudstack-framework-db-test.jar:${securityjarfile}gson-1.7.2.jar:${securityjarfile}cloudstack-engine-schema.jar:${securityjarfile}cloudstack-engine-api.jar:${securityjarfile}commons-configuration-1.10.jar:${securityjarfile}spring-test-5.3.26.jar:${securityjarfile}commons-logging-1.2.jar:${securityjarfile}spring-core-5.3.26.jar:${securityjarfile}reflections-0.10.2.jar:${securityjarfile}cglib-nodep-3.3.0.jar:${securityjarfile}javax.persistence-2.2.1.jar:${securityjarfile}commons-lang3-3.11.jar:${securityjarfile}cloudstack-framework-ipc-test.jar:${securityjarfile}cloudstack-framework-ipc.jar:${securityjarfile}cloudstack-framework-jobs.jar:${securityjarfile}cloudstack-framework-jobs-test.jar:${securityjarfile}cloudstack-framework-managed-context.jar:${securityjarfile}cloudstack-framework-managed-context-test.jar:${securityjarfile}cloudstack-framework-quota-test.jar:${securityjarfile}cloudstack-framework-quota.jar:${securityjarfile}mail-1.5.0-b01.jar:${securityjarfile}commons-dbcp2-2.9.0.jar:${securityjarfile}commons-pool2-2.9.0.jar:${securityjarfile}commons-collections-3.2.2.jar:${securityjarfile}cloudstack-usage.jar:${securityjarfile}nashorn-core-15.3.jar:${securityjarfile}jackson-core-2.13.3.jar:${securityjarfile}jackson-databind-2.13.3.jar:${securityjarfile}jackson-module-jaxb-annotations-2.13.3.jar:${securityjarfile}cloudstack-framework-rest-test.jar:${securityjarfile}cloudstack-framework-rest.jar:${securityjarfile}cloudstack-framework-spring-module-test.jar:${securityjarfile}cloudstack-framework-spring-module.jar:${securityjarfile}spring-beans-5.3.26.jar:${securityjarfile}spring-context-5.3.26.jar:${securityjarfile}log4j-api-2.23.1.jar:${securityjarfile}spring-expression-5.3.26.jar:${securityjarfile}spring-aop-5.3.26.jar:${securityjarfile}commons-logging-1.2.jar:${securityjarfile}commons-io-2.8.0.jar

                        fw_class_name=("com.cloud.cluster.ClusterServiceServletAdapterTest" "org.apache.cloudstack.framework.config.impl.ConfigDepotAdminTest" "org.apache.cloudstack.framework.config.impl.ConfigDepotImplTest" "org.apache.cloudstack.framework.config.ConfigKeyTest" "com.cloud.utils.crypt.EncryptionSecretKeyChangerTest" "com.cloud.utils.db.ElementCollectionTest" "com.cloud.utils.db.FilterTest" "com.cloud.utils.db.GenericDaoBaseTest" "org.apache.cloudstack.framework.jobs.AsyncJobManagerTest" "org.apache.cloudstack.managed.context.impl.DefaultManagedContextTest" "org.apache.cloudstack.quota.activationrule.presetvariables.AccountTest" "org.apache.cloudstack.quota.activationrule.presetvariables.BackupOfferingTest" "org.apache.cloudstack.quota.activationrule.presetvariables.ComputeOfferingTest" "org.apache.cloudstack.quota.activationrule.presetvariables.ComputingResourcesTest" "org.apache.cloudstack.quota.activationrule.presetvariables.DomainTest" "org.apache.cloudstack.quota.activationrule.presetvariables.GenericPresetVariableTest" "org.apache.cloudstack.quota.activationrule.presetvariables.HostTest" "org.apache.cloudstack.quota.activationrule.presetvariables.PresetVariableHelperTest" "org.apache.cloudstack.quota.activationrule.presetvariables.ResourceTest" "org.apache.cloudstack.quota.activationrule.presetvariables.RoleTest" "org.apache.cloudstack.quota.activationrule.presetvariables.StorageTest" "org.apache.cloudstack.quota.activationrule.presetvariables.ValueTest" "org.apache.cloudstack.quota.constant.QuotaTypesTest" "org.apache.cloudstack.quota.vo.QuotaTariffVOTest" "org.apache.cloudstack.quota.QuotaManagerImplTest" "org.apache.cloudstack.framework.ws.jackson.CSJacksonAnnotationTest" "org.apache.cloudstack.spring.module.factory.ModuleBasedContextFactoryTest" "org.apache.cloudstack.spring.module.locator.impl.ClasspathModuleDefinitionSetLocatorTest" "org.apache.cloudstack.spring.module.model.impl.DefaultModuleDefinitionTest")

                        for i in  "${fw_class_name[@]}"
                        do
                                fw_class=$(echo $i)
                                fw_result=$(java -classpath $fw_cmd org.junit.runner.JUnitCore $fw_class | grep -i OK) > /dev/null 2>&1
                                if [ -n "$fw_result" ]; then
                                echo "$fw_class,true" > /dev/null 2>&1
                                else
                                echo "$fw_class,false" > /dev/null 2>&1
                                failed_files+="$fw_class, "
                                fail=$((fail+1))
                                fi
                        done

                        # scripts 115 count
                        script_cmds=("$scriptpath/installer/createtmplt.sh" "$scriptpath/installer/createvolume.sh" "$scriptpath/installer/export-templates.sh" "$scriptpath/installer/installcentos.sh" "$scriptpath/installer/installdomp.sh" "$scriptpath/installer/run_installer.sh" "$scriptpath/network/domr/router_proxy.sh" "$scriptpath/network/exdhcp/dhcpd_edithosts.py" "$scriptpath/network/exdhcp/dnsmasq_edithosts.sh" "$scriptpath/network/exdhcp/prepare_dhcpd.sh" "$scriptpath/network/exdhcp/prepare_dnsmasq.sh" "$scriptpath/network/ping/baremetal_user_data.py" "$scriptpath/network/ping/prepare_kickstart_bootfile.py" "$scriptpath/network/ping/prepare_kickstart_kernel_initrd.py" "$scriptpath/network/ping/prepare_tftp_bootfile.py" "$scriptpath/storage/checkchildren.sh" "$scriptpath/storage/installIso.sh" "$scriptpath/storage/qcow2/create_private_template.sh" "$scriptpath/storage/qcow2/createtmplt.sh" "$scriptpath/storage/qcow2/createvm.sh" "$scriptpath/storage/qcow2/createvolume.sh" "$scriptpath/storage/qcow2/delvm.sh" "$scriptpath/storage/qcow2/get_domr_kernel.sh" "$scriptpath/storage/qcow2/get_iqn.sh" "$scriptpath/storage/qcow2/importmpl.sh" "$scriptpath/storage/qcow2/listvmdisk.sh" "$scriptpath/storage/qcow2/listvmdisksize.sh" "$scriptpath/storage/qcow2/listvmtmplt.sh" "$scriptpath/storage/qcow2/listvolume.sh" "$scriptpath/storage/qcow2/managesnapshot.sh" "$scriptpath/storage/qcow2/managevolume.sh" "$scriptpath/storage/qcow2/resizevolume.sh" "$scriptpath/storage/secondary/cloud-install-sys-tmplt.py" "$scriptpath/storage/secondary/create_privatetemplate_from_snapshot_xen.sh" "$scriptpath/storage/secondary/createtmplt.sh" "$scriptpath/storage/secondary/createvolume.sh" "$scriptpath/storage/secondary/installIso.sh" "$scriptpath/storage/secondary/listvmtmplt.sh" "$scriptpath/storage/secondary/listvolume.sh" "$scriptpath/util/create-kubernetes-binaries-iso.sh" "$scriptpath/util/ipmi.py" "$scriptpath/util/macgen.py" "$scriptpath/util/migrate-dynamicroles.py" "$scriptpath/util/prepare_linmin.sh" "$scriptpath/vm/hypervisor/kvm/kvmheartbeat_clvm.sh" "$scriptpath/vm/hypervisor/kvm/kvmheartbeat_rbd.py" "$scriptpath/vm/hypervisor/kvm/kvmheartbeat_rbd.sh" "$scriptpath/vm/hypervisor/kvm/kvmheartbeat.sh" "$scriptpath/vm/hypervisor/kvm/kvmvmactivity_clvm.sh" "$scriptpath/vm/hypervisor/kvm/kvmvmactivity_rbd.py" "$scriptpath/vm/hypervisor/kvm/kvmvmactivity_rbd.sh" "$scriptpath/vm/hypervisor/kvm/kvmvmactivity.sh" "$scriptpath/vm/hypervisor/kvm/nsrkvmbackup.sh" "$scriptpath/vm/hypervisor/kvm/nsrkvmrestore.sh" "$scriptpath/vm/hypervisor/kvm/patch.sh" "$scriptpath/vm/hypervisor/kvm/setup_agent.sh" "$scriptpath/vm/hypervisor/ovm3/cloudstack.py" "$scriptpath/vm/hypervisor/ovm3/storagehealth.py" "$scriptpath/vm/hypervisor/update_host_passwd.sh" "$scriptpath/vm/hypervisor/versions.sh" "$scriptpath/vm/hypervisor/vmware/discover_networks.py" "$scriptpath/vm/hypervisor/xenserver/add_to_vcpus_params_live.sh" "$scriptpath/vm/hypervisor/xenserver/check_heartbeat.sh" "$scriptpath/vm/hypervisor/xenserver/cloud-clean-vlan.sh" "$scriptpath/vm/hypervisor/xenserver/cloud-prepare-upgrade.sh" "$scriptpath/vm/hypervisor/xenserver/cloud-propagate-vlan.sh" "$scriptpath/vm/hypervisor/xenserver/cloud-setup-bonding.sh" "$scriptpath/vm/hypervisor/xenserver/cloudstack_pluginlib.py" "$scriptpath/vm/hypervisor/xenserver/copy_vhd_from_secondarystorage.sh" "$scriptpath/vm/hypervisor/xenserver/copy_vhd_to_secondarystorage.sh" "$scriptpath/vm/hypervisor/xenserver/create_privatetemplate_from_snapshot.sh" "$scriptpath/vm/hypervisor/xenserver/kill_copy_process.sh" "$scriptpath/vm/hypervisor/xenserver/launch_hb.sh" "$scriptpath/vm/hypervisor/xenserver/make_migratable.sh" "$scriptpath/vm/hypervisor/xenserver/mockxcpplugin.py" "$scriptpath/vm/hypervisor/xenserver/network_info.sh" "$scriptpath/vm/hypervisor/xenserver/ovs-get-bridge.sh" "$scriptpath/vm/hypervisor/xenserver/ovs-get-dhcp-iface.sh" "$scriptpath/vm/hypervisor/xenserver/ovs-vif-flows.py" "$scriptpath/vm/hypervisor/xenserver/perfmon.py" "$scriptpath/vm/hypervisor/xenserver/setup_heartbeat_file.sh" "$scriptpath/vm/hypervisor/xenserver/setup_heartbeat_sr.sh" "$scriptpath/vm/hypervisor/xenserver/setup_iscsi.sh" "$scriptpath/vm/hypervisor/xenserver/setupxenserver.sh" "$scriptpath/vm/hypervisor/xenserver/upgrade_snapshot.sh" "$scriptpath/vm/hypervisor/xenserver/upgrade_vnc_config.sh" "$scriptpath/vm/hypervisor/xenserver/xcposs/NFSSR.py" "$scriptpath/vm/hypervisor/xenserver/xcpserver/NFSSR.py" "$scriptpath/vm/hypervisor/xenserver/xenheartbeat.sh" "$scriptpath/vm/hypervisor/xenserver/xenserver56/InterfaceReconfigure.py" "$scriptpath/vm/hypervisor/xenserver/xenserver56/NFSSR.py" "$scriptpath/vm/hypervisor/xenserver/xenserver56fp1/NFSSR.py" "$scriptpath/vm/hypervisor/xenserver/xenserver60/NFSSR.py" "$scriptpath/vm/hypervisor/xenserver/xs_cleanup.sh" "$scriptpath/vm/network/ovs-pvlan-cleanup.sh" "$scriptpath/vm/network/ovs-pvlan-dhcp-host.sh" "$scriptpath/vm/network/ovs-pvlan-kvm-dhcp-host.sh" "$scriptpath/vm/network/ovs-pvlan-kvm-vm.sh" "$scriptpath/vm/network/ovs-pvlan-vm.sh" "$scriptpath/vm/network/security_group.py" "$scriptpath/vm/network/tungsten/create_tap_device.sh" "$scriptpath/vm/network/tungsten/delete_tap_device.sh" "$scriptpath/vm/network/tungsten/setup_tungsten_vrouter.sh" "$scriptpath/vm/network/tungsten/update_tungsten_loadbalancer_ssl.sh" "$scriptpath/vm/network/tungsten/update_tungsten_loadbalancer_stats.sh" "$scriptpath/vm/network/vnet/cloudstack_pluginlib.py" "$scriptpath/vm/network/vnet/modifyvlan.sh" "$scriptpath/vm/network/vnet/modifyvxlan.sh" "$scriptpath/vm/network/vnet/ovstunnel.py" "$scriptpath/vm/pingtest.sh" "$scriptpath/vm/systemvm/injectkeys.py" "$scriptpath/vm/systemvm/injectkeys.sh" "$SCRIPT_PATH/security/securitycheck.sh arg" "$SCRIPT_PATH/security/moldmonitoring.sh" "$SCRIPT_PATH/security/mail_send.py")

                        for i in  "${script_cmds[@]}"
                        do
                                utils_cmd=$(echo $i)
                                UTILS_RESULT=$($utils_cmd 2>&1)
                                case "${UTILS_RESULT,,}" in
                                        *usage* | *syntax* | *print* | *device* \
                                                | *type* | *cloud* | *xe* | *ovs* \
                                                | *operation* | *find* | *host* \
                                        )
                                                echo "$utils_cmd,true" > /dev/null 2>&1
                                                ;;
                                        *)
                                                echo "$utils_cmd,false" > /dev/null 2>&1
                                                failed_files+="$utils_cmd, "
                                                fail=$((fail+1))
                                                ;;
                                esac

                        done

                        # process check
                        count1=$(systemctl status mold-monitoring.service | grep -i running | wc -l) > /dev/null 2>&1
                        if [ "$count1" -eq 0 ]; then
                                echo "monitoring.service,false" > /dev/null 2>&1
                                failed_files+="monitoring.service, "
                                fail=$((fail+1))
                        else
                                echo "monitoring.service,true" > /dev/null 2>&1
                        fi
                        count2=$(systemctl status cloudstack-management.service | grep -i running | wc -l) > /dev/null 2>&1
                        if [ "$count2" -eq 0 ]; then
                                echo "mold.service,false" > /dev/null 2>&1
                                failed_files+="mold.service, "
                                fail=$((fail+1))
                        else
                                echo "mold.service,true" > /dev/null 2>&1
                        fi

                        failed_files="${failed_files%, }"
                        if [ $fail -eq 0 ]; then
                                if [ $cnt -eq 1 ]; then
                                        echo "Monitoring Execution 자체시험 성공 감사기록 생성-------------------------"
                                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO security_check (mshost_id, check_result, check_date, check_failed_list, type, service) VALUES ('1', '1', DATE_SUB(NOW(), INTERVAL 9 HOUR), '', 'Execution', 'Monitoring')"  > /dev/null 2>&1
                                        uuid=$(cat /proc/sys/kernel/random/uuid)
                                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO event (uuid, type, state, description, user_id, account_id, domain_id, resource_id, created, level, start_id, archived, display, client_ip) VALUES ('$uuid', 'SECURITY.CHECK', 'Completed', '제품 실행 시 모니터링 서비스에 대한 보안 점검이 성공적으로 완료되었습니다.', '1', '1', '1', '0', DATE_SUB(NOW(), INTERVAL 9 HOUR), 'INFO', '0', '0', '1', '$host_ip');" > /dev/null 2>&1
                                else
                                        echo "Monitoring Routine 자체시험 성공 감사기록 생성-------------------------"
                                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO security_check (mshost_id, check_result, check_date, check_failed_list, type, service) VALUES ('1', '1', DATE_SUB(NOW(), INTERVAL 9 HOUR), '', 'Routine', 'Monitoring')"  > /dev/null 2>&1
                                        uuid=$(cat /proc/sys/kernel/random/uuid)
                                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO event (uuid, type, state, description, user_id, account_id, domain_id, resource_id, created, level, start_id, archived, display, client_ip) VALUES ('$uuid', 'SECURITY.CHECK', 'Completed', '제품 작동 시 모니터링 서비스에 대한 보안 검사를 성공적으로 완료했습니다.', '1', '1', '1', '0', DATE_SUB(NOW(), INTERVAL 9 HOUR), 'INFO', '0', '0', '1', '$host_ip');" > /dev/null 2>&1
                                fi
                        else
                                echo "자체시험 실패 리스트 : $failed_files -----------------"
                                if [ $cnt -eq 1 ]; then
                                        echo "Monitoring Execution 자체시험 실패 감사기록 및 알림 생성-------------------------"
                                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO security_check (mshost_id, check_result, check_date, check_failed_list, type, service) VALUES ('1', '0', DATE_SUB(NOW(), INTERVAL 9 HOUR), '$failed_files', 'Execution', 'Monitoring')"  > /dev/null 2>&1
                                        uuid=$(cat /proc/sys/kernel/random/uuid)
                                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO alert (uuid, type, pod_id, data_center_id, subject, sent_count, created, last_sent, archived, name) VALUES ('$uuid', '14', '0', '0', '제품을 실행하는 중 모니터링 서비스 보안 검사에 실패했습니다.', '1', DATE_SUB(NOW(), INTERVAL 9 HOUR), DATE_SUB(NOW(), INTERVAL 9 HOUR), '0', 'ALERT.MANAGEMENT');" > /dev/null 2>&1
                                        uuid=$(cat /proc/sys/kernel/random/uuid)
                                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO event (uuid, type, state, description, user_id, account_id, domain_id, resource_id, created, level, start_id, archived, display, client_ip) VALUES ('$uuid', 'SECURITY.CHECK', 'Completed', '제품 실행 시, 모니터링 서비스에 대한 보안 검사를 실행하지 못했습니다.', '1', '1', '1', '0', DATE_SUB(NOW(), INTERVAL 9 HOUR), 'ERROR', '0', '0', '1', '$host_ip');" > /dev/null 2>&1
                                        subject='제품 실행 중 모니터링 서비스 보안 점검에 실패했습니다.'
                                else
                                        echo "Monitoring Routine 자체시험 실패 감사기록 및 알림 생성-------------------------"
                                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO security_check (mshost_id, check_result, check_date, check_failed_list, type, service) VALUES ('1', '0', DATE_SUB(NOW(), INTERVAL 9 HOUR), '$failed_files', 'Routine', 'Monitoring')"  > /dev/null 2>&1
                                        uuid=$(cat /proc/sys/kernel/random/uuid)
                                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO alert (uuid, type, pod_id, data_center_id, subject, sent_count, created, last_sent, archived, name) VALUES ('$uuid', '14', '0', '0', '제품 작동 중 모니터링 서비스 보안 검사에 실패했습니다.', '1', DATE_SUB(NOW(), INTERVAL 9 HOUR), DATE_SUB(NOW(), INTERVAL 9 HOUR), '0', 'ALERT.MANAGEMENT');" > /dev/null 2>&1
                                        uuid=$(cat /proc/sys/kernel/random/uuid)
                                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO event (uuid, type, state, description, user_id, account_id, domain_id, resource_id, created, level, start_id, archived, display, client_ip) VALUES ('$uuid', 'SECURITY.CHECK', 'Completed', '제품 작동 시, 모니터링 서비스에 대한 보안 검사를 실행하지 못했습니다.', '1', '1', '1', '0', DATE_SUB(NOW(), INTERVAL 9 HOUR), 'ERROR', '0', '0', '1', '$host_ip');" > /dev/null 2>&1
                                        subject='제품 작동 중 모니터링 서비스 보안 점검에 실패했습니다.'
                                fi
                                sendAlertMail
                        fi
                        mysql --user=root --password=$database_password -e "use cloud; SET GLOBAL foreign_key_checks=1;" > /dev/null 2>&1
                        removeVariable
                fi
        else
                echo "키 파일 복호화 살패--------------------------------------"
                removeVariable
        fi
}

# 변수 및 설정파일 파기
function removeVariable {
        echo "변수 및 설정파일 01 덮어쓰기 후 설정파일 파기------------------------------------------"
        for var in {1..5}
        do
                key=01010101
                secret=01010101
                database_password=01010101
                smtp_password=01010101
        done

        if [ -e "$monitoring_file" ]; then
                for var in {1..5} ; do echo 01010101 > $monitoring_file ; done
                rm -rf $monitoring_file
        fi 
}

# 메일 전송
function sendAlertMail {
        smtp_server="$(mysql --user=root --password=$database_password -se "use cloud; SELECT value FROM configuration WHERE name='alert.smtp.host';")" > /dev/null 2>&1
        smtp_port="$(mysql --user=root --password=$database_password -se "use cloud; SELECT value FROM configuration WHERE name='alert.smtp.port';")" > /dev/null 2>&1
        smtp_username="$(mysql --user=root --password=$database_password -se "use cloud; SELECT value FROM configuration WHERE name='alert.smtp.username';")" > /dev/null 2>&1
        smtp_enc_password="$(mysql --user=root --password=$database_password -se "use cloud; SELECT value FROM configuration WHERE name='alert.smtp.password';")" > /dev/null 2>&1
        smtp_password=$(java -classpath $jar_file com.cloud.utils.crypt.EncryptionCLI -d -i "$smtp_enc_password" -p $secret $enc_version)
        smtp_sender="$(mysql --user=root --password=$database_password -se "use cloud; SELECT value FROM configuration WHERE name='alert.email.sender';")" > /dev/null 2>&1
        smtp_recipient="$(mysql --user=root --password=$database_password -se "use cloud; SELECT value FROM configuration WHERE name='alert.email.addresses';")" > /dev/null 2>&1
        if [ -n "$smtp_server" ] && [ -n "$smtp_port" ] && [ -n "$smtp_username" ] && [ -n "$smtp_enc_password" ] && [ -n "$smtp_password" ] && [ -n "$smtp_sender" ] && [ -n "$smtp_recipient" ]; then
                echo "SMTP 설정으로 알림 메일 전송--------------------------------------"
                python "$scriptpath/security/mail_send.py" --smtp-server $smtp_server --smtp-port $smtp_port --from-email-addr $smtp_sender --from-email-pw $smtp_password --to-email-addr $smtp_recipient --subject "$subject"
                res=$?
                if [ $res -eq 0 ]; then
                        echo "알림 메일 전송 성공 감사기록 생성--------------------------------------"
                        uuid=$(cat /proc/sys/kernel/random/uuid)
                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO event (uuid, type, state, description, user_id, account_id, domain_id, resource_id, created, level, start_id, archived, display, client_ip) VALUES ('$uuid', 'ALERT.MAIL', 'Completed', 'Successfully alert email has been sent : $subject', '1', '1', '1', '0', DATE_SUB(NOW(), INTERVAL 9 HOUR), 'INFO', '0', '0', '1', '$host_ip');" > /dev/null 2>&1
                else
                        echo "알림 메일 전송 실패 감사기록 생성--------------------------------------"
                        uuid=$(cat /proc/sys/kernel/random/uuid)
                        mysql --user=root --password=$database_password -e "use cloud; INSERT INTO event (uuid, type, state, description, user_id, account_id, domain_id, resource_id, created, level, start_id, archived, display, client_ip) VALUES ('$uuid', 'ALERT.MAIL', 'Completed', 'Failed to alert email sending : $subject', '1', '1', '1', '0', DATE_SUB(NOW(), INTERVAL 9 HOUR), 'ERROR', '0', '0', '1', '$host_ip');" > /dev/null 2>&1
                fi
        else
                echo "SMTP 미설정으로 알림 메일 전송 skip--------------------------------------"
        fi
}

if [ $# -gt 0 ]; then
        while :
        do
                securitycheck
                sleep "$interval"h 
        done
else
        echo "usage"
fi
