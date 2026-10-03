// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.vm.snapshot;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.Filter;
import org.apache.cloudstack.api.command.user.vmsnapshot.ListVMSnapshotCmd;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VMSnapshotListFilterTest {
    @Test
    public void defaultAndAllSupportedSortsHaveStableIdTieBreak() {
        ListVMSnapshotCmd cmd = mock(ListVMSnapshotCmd.class);
        when(cmd.getStartIndex()).thenReturn(20L);
        when(cmd.getPageSizeVal()).thenReturn(20L);
        Filter filter = VMSnapshotManagerImpl.snapshotListFilter(cmd);
        assertTrue(filter.getOrderBy().contains("created DESC"));
        assertTrue(filter.getOrderBy().contains("id DESC"));
        assertEquals(Long.valueOf(20), filter.getOffset());
        for (String field : new String[] { "created", "displayname", "name", "state", "type", "current" }) {
            when(cmd.getSortKey()).thenReturn(field);
            when(cmd.getSortOrder()).thenReturn("asc");
            assertTrue(VMSnapshotManagerImpl.snapshotListFilter(cmd).getOrderBy().contains("id ASC"));
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void sqlCannotBeUsedAsSortKey() {
        ListVMSnapshotCmd cmd = mock(ListVMSnapshotCmd.class);
        when(cmd.getSortKey()).thenReturn("created desc; delete from vm_snapshots");
        VMSnapshotManagerImpl.snapshotListFilter(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void invalidDirectionIsRejected() {
        ListVMSnapshotCmd cmd = mock(ListVMSnapshotCmd.class);
        when(cmd.getSortOrder()).thenReturn("up");
        VMSnapshotManagerImpl.snapshotListFilter(cmd);
    }
}
