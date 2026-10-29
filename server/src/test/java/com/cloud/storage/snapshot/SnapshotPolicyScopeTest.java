// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.storage.SnapshotPolicyVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotPolicyDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import org.apache.cloudstack.api.command.user.snapshot.ListSnapshotPoliciesCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SnapshotPolicyScopeTest {
    @SuppressWarnings("unchecked")
    private void exercise(Boolean display, boolean ignoreProject, Long project, Long domain, String account, Account.Type type) {
        SnapshotManagerImpl manager = new SnapshotManagerImpl();
        manager._snapshotPolicyDao = mock(SnapshotPolicyDao.class);
        manager._volsDao = mock(VolumeDao.class);
        manager._accountMgr = mock(AccountManager.class);
        SearchBuilder<SnapshotPolicyVO> builder = mock(SearchBuilder.class);
        SearchCriteria<SnapshotPolicyVO> criteria = mock(SearchCriteria.class);
        SearchBuilder<VolumeVO> volumes = mock(SearchBuilder.class);
        when(manager._snapshotPolicyDao.createSearchBuilder()).thenReturn(builder);
        when(builder.entity()).thenReturn(mock(SnapshotPolicyVO.class));
        when(builder.create()).thenReturn(criteria);
        when(manager._volsDao.createSearchBuilder()).thenReturn(volumes);
        when(volumes.entity()).thenReturn(mock(VolumeVO.class));
        when(manager._snapshotPolicyDao.searchAndCount(eq(criteria), any())).thenReturn(new Pair<>(Collections.emptyList(), 0));
        ListSnapshotPoliciesCmd cmd = mock(ListSnapshotPoliciesCmd.class);
        when(cmd.getDisplay()).thenReturn(display); when(cmd.isIgnoreProject()).thenReturn(ignoreProject);
        when(cmd.getProjectId()).thenReturn(project); when(cmd.getDomainId()).thenReturn(domain); when(cmd.getAccountName()).thenReturn(account);
        when(cmd.listAll()).thenReturn(true);
        CallContext context = mock(CallContext.class); Account caller = mock(Account.class);
        when(context.getCallingAccount()).thenReturn(caller); when(caller.getType()).thenReturn(type);
        when(caller.getDomainId()).thenReturn(7L);
        try (MockedStatic<CallContext> current = mockStatic(CallContext.class)) {
            current.when(CallContext::current).thenReturn(context);
            manager.listSnapshotPolicies(cmd);
        }
        if (display == null) verify(criteria, never()).setParameters(eq("display"), any(Object[].class));
        else verify(criteria).setParameters("display", display);
        if (ignoreProject) verify(manager._accountMgr).buildACLSearchBuilder(eq(builder), isNull(), eq(false), anyList(), eq(ListProjectResourcesCriteria.SkipProjectResources));
        if (Long.valueOf(-1).equals(project) && type == Account.Type.DOMAIN_ADMIN) verify(manager._accountMgr).buildACLSearchBuilder(eq(builder), eq(7L), eq(true), eq(Collections.emptyList()), eq(ListProjectResourcesCriteria.ListAllIncludingProjectResources));
        verify(manager._snapshotPolicyDao).searchAndCount(eq(criteria), any());
    }
    @Test public void omittedDisplayKeepsBothAndPersonalScopeStillUsesAcl() { exercise(null, true, null, null, null, Account.Type.ADMIN); }
    @Test public void falseAndTrueAreRealSearchConditions() { exercise(false, false, null, null, null, Account.Type.ADMIN); exercise(true, false, null, null, null, Account.Type.ADMIN); }
    @Test public void projectAndPersonalScopeConflict() { assertThrows(InvalidParameterValueException.class, () -> exercise(null, true, 9L, null, null, Account.Type.ADMIN)); }
    @Test public void projectAndOwnerFiltersConflict() { assertThrows(InvalidParameterValueException.class, () -> exercise(null, false, 9L, 7L, null, Account.Type.ADMIN)); assertThrows(InvalidParameterValueException.class, () -> exercise(null, false, 9L, null, "owner", Account.Type.ADMIN)); }
    @Test public void nonRootCannotFilterDisplay() { for (Account.Type type : new Account.Type[]{Account.Type.NORMAL, Account.Type.DOMAIN_ADMIN}) assertThrows(PermissionDeniedException.class, () -> exercise(false, false, null, null, null, type)); }
    @Test public void domainAdminAllViewUsesOwnDomainBoundaryInsteadOfProjectMembershipUnion() { exercise(null, false, -1L, null, null, Account.Type.DOMAIN_ADMIN); }
}
