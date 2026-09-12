//Licensed to the Apache Software Foundation (ASF) under one
//or more contributor license agreements.  See the NOTICE file
//distributed with this work for additional information
//regarding copyright ownership.  The ASF licenses this file
//to you under the Apache License, Version 2.0 (the
//"License"); you may not use this file except in compliance
//the License.  You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing,
//software distributed under the License is distributed on an
//"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//KIND, either express or implied.  See the License for the
//specific language governing permissions and limitations
//under the License.
package org.apache.cloudstack.backup;

import java.util.List;

import com.cloud.utils.Pair;
import com.cloud.vm.VirtualMachine;

public interface BackupProvider {

    Boolean crossZoneInstanceCreationEnabled(BackupOffering backupOffering);

    /**
     * Returns the unique name of the provider
     * @return returns provider name
     */
    String getName();

    /**
     * Returns description about the backup and recovery provider plugin
     * @return returns description
     */
    String getDescription();

    /**
     * Returns the list of existing backup policies on the provider
     * @return backup policies list
     */
    List<BackupOffering> listBackupOfferings(Long zoneId);

    /**
     * True if a backup offering exists on the backup provider
     */
    boolean isValidProviderOffering(Long zoneId, String uuid);

    /**
     * Assign a VM to a backup offering or policy
     * @param vm the machine to back up
     * @param backupOffering the SLA definition for the backup
     * @return succeeded?
     */
    boolean assignVMToBackupOffering(VirtualMachine vm, BackupOffering backupOffering);

    /**
     * Removes a VM from a backup offering or policy
     * @param vm the machine to stop backing up
     * @return succeeded?
     */
    boolean removeVMFromBackupOffering(VirtualMachine vm);


    /**
     * Whether the provider will delete backups on removal of VM from the offering
     * @return boolean result
     */
    boolean willDeleteBackupsOnOfferingRemoval();

    /**
     * Starts and creates an adhoc backup process
     * for a previously registered VM backup
     *
     * @param vm
     *         the machine to make a backup of
     * @param quiesceVM
     *         instance will be quiesced for checkpointing for backup. Applicable only to NAS plugin.
     * @param isolated
     * @return the result and {code}Backup{code} {code}Object{code}
     */
    Pair<Boolean, Backup> takeBackup(VirtualMachine vm, Boolean quiesceVM, boolean isolated, Long backupScheduleId);

    default Pair<Boolean, Backup> takeBackup(VirtualMachine vm, Boolean quiesceVM, boolean isolated) {
        return takeBackup(vm, quiesceVM, isolated, null);
    }

    default Pair<Boolean, Backup> takeBackup(VirtualMachine vm, Boolean quiesceVM) {
        return takeBackup(vm, quiesceVM, false, null);
    }

    default Pair<Boolean, Backup> takeBackup(VirtualMachine vm, Boolean quiesceVM, Long backupScheduleId) {
        return takeBackup(vm, quiesceVM, false, backupScheduleId);
    }

    default Pair<Boolean, Backup> takeBackup(VirtualMachine vm, Boolean quiesceVM, Long backupScheduleId, String veeamJobName) {
        return takeBackup(vm, quiesceVM, backupScheduleId);
    }

    default Pair<Boolean, Backup> takeNetBackup(VirtualMachine vm, String policyName) {
        throw new UnsupportedOperationException("NetBackup is not supported by provider " + getName());
    }

    default String getCatalogBackupTime(Long zoneId, String backupId) {
        return null;
    }

    /**
     * Import a Veeam restore point as a local backup seed (Ablestack Veeam provider only).
     */
    default Pair<Boolean, Backup> importAblestackVeeamBackupSeed(VirtualMachine vm, String veeamRestorePointId,
            List<String> stagingDiskPaths, String sourceDiskFormat, Boolean bootstrapCheckpoint) {
        throw new UnsupportedOperationException("Provider " + getName() + " does not support Veeam seed import");
    }

    /**
     * Delete an existing backup
     * @param backup The backup to exclude
     * @param forced Indicates if backup will be force removed or not
     * @return succeeded?
     */
    boolean deleteBackup(Backup backup, boolean forced);

    /**
     * Whether {@link #deleteBackup(Backup, boolean)} owns DB-row removal and resource-count /
     * usage accounting for every backup it physically removes. Providers that manage incremental
     * chains (e.g. NAS) delete several backups per call — the leaf plus swept delete-pending
     * ancestors — and decrement once per removed backup themselves, so the manager must NOT
     * decrement or remove the row again. Defaults to {@code false}: the manager does the
     * single-backup accounting (the historical behaviour for non-chain providers).
     */
    default boolean handlesChainDeleteResourceAccounting() {
        return false;
    }

    Pair<Boolean, String> restoreBackupToVM(VirtualMachine vm, Backup backup, String hostIp, String dataStoreUuid, boolean quickrestore);

    /**
     * Restore VM from BX backup
     */
    default Pair<Boolean, String> restoreBackupToVM(Long backupId, String vmName) {
        throw new UnsupportedOperationException("Restore by backup ID is not supported by provider " + getName());
    }

    /**
     * Restore VM from backup
     */
    boolean restoreVMFromBackup(VirtualMachine vm, Backup backup, boolean quickRestore, Long hostId);

    default boolean restoreVMFromBackup(VirtualMachine vm, Backup backup) {
        return restoreVMFromBackup(vm, backup, false, null);
    }

    default Pair<Boolean, String> restoreBackupToVM(VirtualMachine vm, Backup backup, String hostIp, String dataStoreUuid) {
        return restoreBackupToVM(vm, backup, hostIp, dataStoreUuid, false);
    }

    default Pair<Boolean, String> restoreBackedUpVolume(Backup backup, Backup.VolumeInfo backupVolumeInfo, String hostIp, String dataStoreUuid,
            Pair<String, VirtualMachine.State> vmNameAndState) {
        return restoreBackedUpVolume(backup, backupVolumeInfo, hostIp, dataStoreUuid, vmNameAndState, null, false);
    }

    default void cleanupPreparedRestore(VirtualMachine vm, Backup backup, String restoreHostName) {
    }

    /**
     * Restore a volume from a backup
     */
    Pair<Boolean, String> restoreBackedUpVolume(Backup backup, Backup.VolumeInfo backupVolumeInfo, String hostIp, String dataStoreUuid,
            Pair<String, VirtualMachine.State> vmNameAndState, VirtualMachine vm, boolean quickRestore);

    /**
     * Syncs backup metrics (backup size, protected size) from the plugin and stores it within the provider
     * @param zoneId the zone for which to return metrics
     */
    void syncBackupMetrics(Long zoneId);

    /**
     * Returns a list of Backup.RestorePoint
     * @param vm the machine to get the restore points for
     */
    List<Backup.RestorePoint> listRestorePoints(VirtualMachine vm);

    /**
     * Restore points from the external backup catalog (Veeam Disk, NetBackup, etc.).
     * Default is {@link #listRestorePoints(VirtualMachine)}. Providers whose Mold
     * {@code backups.external_id} is a local staging path (not a catalog id) should
     * return catalog GUIDs here and Mold-local points from {@code listRestorePoints}.
     */
    default List<Backup.RestorePoint> listCatalogRestorePoints(VirtualMachine vm) {
        return listRestorePoints(vm);
    }

    /**
     * Creates and returns an entry in the backups table by getting the information from restorePoint and vm.
     *
     * @param restorePoint the restore point to create a backup for
     * @param vm           The machine for which to create a backup
     */
    Backup createNewBackupEntryForRestorePoint(Backup.RestorePoint restorePoint, VirtualMachine vm);

    /**
     * Returns if the backup provider supports creating new instance from backup
     */
    boolean supportsInstanceFromBackup();

    default boolean supportsMemoryVmSnapshot() {
        return true;
    }

    /**
     * Returns the backup storage usage (Used, Total) for a backup provider
     * @param zoneId the zone for which to return metrics
     * @return a pair of Used size and Total size for the backup storage
     */
    Pair<Long, Long> getBackupStorageStats(Long zoneId);

    /**
     * Gets the backup storage usage (Used, Total) from the plugin and stores it in db
     * @param zoneId the zone for which to return metrics
     */
    void syncBackupStorageStats(Long zoneId);

    /**
     * sync commvault backup
     */
    default void syncBackups(VirtualMachine vm) {

    }

    /**
     * check commvault backup agent
     */
    default boolean checkBackupAgent(Long zoneId) {
        return false;
    }

    /**
     * install commvault backup agent
     */
    default boolean installBackupAgent(Long zoneId) {
        return false;
    }

    /**
     * import commvault backup plan
     */
    default boolean importBackupPlan(Long zoneId, String retentionPeriod, String externalId) {
        return false;
    }

    /**
     * update commvault backup plan
     */
    default boolean updateBackupPlan(Long zoneId, String retentionPeriod, String externalId) {
        return false;
    }

    default boolean supportsBackgroundSync() {
        return true;
    }

    default boolean supportsBackupMetricsSync() {
        return true;
    }

    default boolean supportsOutOfBandBackupSync() {
        return true;
    }

    default boolean supportsProviderManagedBackupAgents() {
        return false;
    }

    default boolean supportsRetentionPlanUpdate() {
        return false;
    }

    default boolean supportsVolumeLevelChainState() {
        return false;
    }

    default boolean supportsRestorePlan() {
        return false;
    }

    default boolean supportsRestoreChainValidation() {
        return false;
    }

    default String getRestoreJobState(Long zoneId, String recoveryJobId) {
        return null;
    }

    default boolean supportsPostRestoreMaintenance() {
        return false;
    }

    default void runPostRestoreMaintenance(VirtualMachine vm, Backup backup, boolean volumeOnly) {
    }

    default boolean supportsBackgroundChainValidation() {
        return false;
    }

    default void validateChains(Long zoneId) {
    }
}
