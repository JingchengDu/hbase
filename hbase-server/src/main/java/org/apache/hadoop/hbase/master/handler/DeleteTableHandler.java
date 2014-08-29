/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master.handler;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.CoordinatedStateException;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.backup.HFileArchiver;
import org.apache.hadoop.hbase.MetaTableAccessor;
import org.apache.hadoop.hbase.executor.EventType;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.master.AssignmentManager;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.master.MasterCoprocessorHost;
import org.apache.hadoop.hbase.master.MasterFileSystem;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.RegionStates;
import org.apache.hadoop.hbase.master.RegionState.State;
import org.apache.hadoop.hbase.mob.MobConstants;
import org.apache.hadoop.hbase.mob.MobUtils;

@InterfaceAudience.Private
public class DeleteTableHandler extends TableEventHandler {
  private static final Log LOG = LogFactory.getLog(DeleteTableHandler.class);

  protected HTableDescriptor hTableDescriptor = null;

  public DeleteTableHandler(TableName tableName, Server server,
      final MasterServices masterServices) {
    super(EventType.C_M_DELETE_TABLE, tableName, server, masterServices);
  }

  @Override
  protected void prepareWithTableLock() throws IOException {
    // The next call fails if no such table.
    hTableDescriptor = getTableDescriptor();
  }

  protected void waitRegionInTransition(final List<HRegionInfo> regions)
      throws IOException, CoordinatedStateException {
    AssignmentManager am = this.masterServices.getAssignmentManager();
    RegionStates states = am.getRegionStates();
    long waitTime = server.getConfiguration().
      getLong("hbase.master.wait.on.region", 5 * 60 * 1000);
    for (HRegionInfo region : regions) {
      long done = System.currentTimeMillis() + waitTime;
      while (System.currentTimeMillis() < done) {
        if (states.isRegionInState(region, State.FAILED_OPEN)) {
          am.regionOffline(region);
        }
        if (!states.isRegionInTransition(region)) break;
        try {
          Thread.sleep(waitingTimeForEvents);
        } catch (InterruptedException e) {
          LOG.warn("Interrupted while sleeping");
          throw (InterruptedIOException)new InterruptedIOException().initCause(e);
        }
        LOG.debug("Waiting on region to clear regions in transition; "
          + am.getRegionStates().getRegionTransitionState(region));
      }
      if (states.isRegionInTransition(region)) {
        throw new IOException("Waited hbase.master.wait.on.region (" +
          waitTime + "ms) for region to leave region " +
          region.getRegionNameAsString() + " in transitions");
      }
    }
  }

  @Override
  protected void handleTableOperation(List<HRegionInfo> regions)
      throws IOException, CoordinatedStateException {
    MasterCoprocessorHost cpHost = ((HMaster) this.server).getMasterCoprocessorHost();
    if (cpHost != null) {
      cpHost.preDeleteTableHandler(this.tableName);
    }

    // 1. Wait because of region in transition
    waitRegionInTransition(regions);

    try {
      // 2. Remove table from hbase:meta and HDFS
      removeTableData(regions);
    } finally {
      // 3. Update table descriptor cache
      LOG.debug("Removing '" + tableName + "' descriptor.");
      this.masterServices.getTableDescriptors().remove(tableName);

      AssignmentManager am = this.masterServices.getAssignmentManager();

      // 4. Clean up regions of the table in RegionStates.
      LOG.debug("Removing '" + tableName + "' from region states.");
      am.getRegionStates().tableDeleted(tableName);

      // 5. If entry for this table in zk, and up in AssignmentManager, remove it.
      LOG.debug("Marking '" + tableName + "' as deleted.");
      am.getTableStateManager().setDeletedTable(tableName);
    }

    if (cpHost != null) {
      cpHost.postDeleteTableHandler(this.tableName);
    }
  }

  /**
   * Removes the table from hbase:meta and archives the HDFS files.
   */
  protected void removeTableData(final List<HRegionInfo> regions)
      throws IOException, CoordinatedStateException {
    // 1. Remove regions from META
    LOG.debug("Deleting regions from META");
    MetaTableAccessor.deleteRegions(this.server.getShortCircuitConnection(), regions);

    // -----------------------------------------------------------------------
    // NOTE: At this point we still have data on disk, but nothing in hbase:meta
    //       if the rename below fails, hbck will report an inconsistency.
    // -----------------------------------------------------------------------

    // 2. Move the table in /hbase/.tmp
    MasterFileSystem mfs = this.masterServices.getMasterFileSystem();
    Path tempTableDir = mfs.moveTableToTemp(tableName);

    // 3. Archive regions from FS (temp directory)
    FileSystem fs = mfs.getFileSystem();
    for (HRegionInfo hri: regions) {
      LOG.debug("Archiving region " + hri.getRegionNameAsString() + " from FS");
      HFileArchiver.archiveRegion(fs, mfs.getRootDir(),
          tempTableDir, HRegion.getRegionDir(tempTableDir, hri.getEncodedName()));
    }

    // Archive the mob data if there is a mob-enabled column
    HColumnDescriptor[] hcds = hTableDescriptor.getColumnFamilies();
    boolean hasMob = false;
    for (HColumnDescriptor hcd : hcds) {
      if (MobUtils.isMobFamily(hcd)) {
        hasMob = true;
        break;
      }
    }
    Path regionDir = null;
    if (hasMob) {
      // Archive mob data
      Path mobTableDir = FSUtils.getTableDir(new Path(mfs.getRootDir(), MobConstants.MOB_DIR_NAME),
          tableName);
      regionDir = new Path(mobTableDir, MobUtils.getMobRegionInfo(tableName).getEncodedName());
      if (fs.exists(regionDir)) {
        HFileArchiver.archiveRegion(fs, mfs.getRootDir(), tempTableDir, regionDir);
      }
    }
    // 4. Delete table directory from FS (temp directory)
    if (!fs.delete(tempTableDir, true)) {
      LOG.error("Couldn't delete " + tempTableDir);
    }

    LOG.debug("Table '" + tableName + "' archived!");
  }

  @Override
  protected void releaseTableLock() {
    super.releaseTableLock();
    try {
      masterServices.getTableLockManager().tableDeleted(tableName);
    } catch (IOException ex) {
      LOG.warn("Received exception from TableLockManager.tableDeleted:", ex); //not critical
    }
  }

  @Override
  public String toString() {
    String name = "UnknownServerName";
    if(server != null && server.getServerName() != null) {
      name = server.getServerName().toString();
    }
    return getClass().getSimpleName() + "-" + name + "-" + getSeqid() + "-" + tableName;
  }
}
