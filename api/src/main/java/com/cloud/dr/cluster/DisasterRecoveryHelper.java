package com.cloud.dr.cluster;

import com.cloud.utils.component.Adapter;

public interface DisasterRecoveryHelper extends Adapter {

    void checkVmCanBeDestroyed(long vmId);
    void checkVmCanBeStarted(long vmId);

}
