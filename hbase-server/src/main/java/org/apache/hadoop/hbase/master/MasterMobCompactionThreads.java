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
package org.apache.hadoop.hbase.master;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellComparator;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.MetaTableAccessor;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.TableNotEnabledException;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.TableState;
import org.apache.hadoop.hbase.io.HFileLink;
import org.apache.hadoop.hbase.io.crypto.Encryption;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.master.TableLockManager.TableLock;
import org.apache.hadoop.hbase.mob.MobConstants;
import org.apache.hadoop.hbase.mob.MobUtils;
import org.apache.hadoop.hbase.procedure.MasterProcedureManager;
import org.apache.hadoop.hbase.procedure.ProcedureCoordinator;
import org.apache.hadoop.hbase.procedure.ProcedureCoordinatorRpcs;
import org.apache.hadoop.hbase.procedure.ZKProcedureCoordinatorRpcs;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.ProcedureDescription;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.regionserver.HStore;
import org.apache.hadoop.hbase.regionserver.ScanInfo;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.ScannerContext;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.StoreFile.Writer;
import org.apache.hadoop.hbase.regionserver.StoreFileInfo;
import org.apache.hadoop.hbase.regionserver.StoreFileScanner;
import org.apache.hadoop.hbase.regionserver.StoreScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.MD5Hash;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.zookeeper.KeeperException;

/**
 * The mob compaction thread used in {@link HMaster}
 */
public class MasterMobCompactionThreads extends MasterProcedureManager implements Stoppable {

  static final Log LOG = LogFactory.getLog(MasterMobCompactionThread.class);
  private HMaster master;
  private Configuration conf;
  private ThreadPoolExecutor mobCompactionPool;
  private Set<String> ongoingCompactions;
  private int delFileMaxCount;
  private int compactionBatchSize;
  protected int compactionKVMax;
  private Path tempPath;
  private CacheConfig compactionCacheConfig;
  private String compactionBaseZNode;
  private boolean stopped;
  private FileSystem fs;
  public static final String MOB_COMPACTION_ZNODE_NAME = "mobCompaction";

  public static final String MOB_COMPACTION_PROCEDURE_SIGNATURE = "mob-compaction-proc";

  private static final String MOB_COMPACTION_TIMEOUT_MILLIS_KEY = "hbase.mob.compaction.master.timeoutMillis";
  private static final int MOB_COMPACTION_TIMEOUT_MILLIS_DEFAULT = 1800000; // 30 min
  private static final String MOB_COMPACTION_WAKE_MILLIS_KEY = "hbase.mob.compaction.master.wakeMillis";
  private static final int MOB_COMPACTION_WAKE_MILLIS_DEFAULT = 500;

  private static final String MOB_COMPACTION_PROC_POOL_THREADS_KEY = "hbase.mob.compaction.procedure.master.threads";
  private static final int MOB_COMPACTION_PROC_POOL_THREADS_DEFAULT = 2;

  private ProcedureCoordinator coordinator;
  private Map<TableName, Future<Void>> compactions = new HashMap<TableName, Future<Void>>();

  public MasterMobCompactionThreads(HMaster master, ThreadPoolExecutor mobCompactionPool) {
    this.master = master;
    this.fs = master.getFileSystem();
    this.conf = master.getConfiguration();
    this.ongoingCompactions = new HashSet<String>();
    delFileMaxCount = conf.getInt(MobConstants.MOB_DELFILE_MAX_COUNT,
      MobConstants.DEFAULT_MOB_DELFILE_MAX_COUNT);
    compactionBatchSize = conf.getInt(MobConstants.MOB_COMPACTION_BATCH_SIZE,
      MobConstants.DEFAULT_MOB_COMPACTION_BATCH_SIZE);
    compactionKVMax = this.conf.getInt(HConstants.COMPACTION_KV_MAX,
      HConstants.COMPACTION_KV_MAX_DEFAULT);
    tempPath = new Path(MobUtils.getMobHome(conf), MobConstants.TEMP_DIR_NAME);
    Configuration copyOfConf = new Configuration(conf);
    copyOfConf.setFloat(HConstants.HFILE_BLOCK_CACHE_SIZE_KEY, 0f);
    compactionCacheConfig = new CacheConfig(copyOfConf);
    compactionBaseZNode = ZKUtil.joinZNode(master.getZooKeeper().getBaseZNode(),
      MOB_COMPACTION_ZNODE_NAME);
    // this pool is used to run the mob compaction
    if (mobCompactionPool != null) {
      this.mobCompactionPool = mobCompactionPool;
    } else {
      int threads = conf.getInt(MOB_COMPACTION_PROC_POOL_THREADS_KEY,
        MOB_COMPACTION_PROC_POOL_THREADS_DEFAULT);
      this.mobCompactionPool = ProcedureCoordinator.defaultPool("MasterMobCompaction", threads);
    }
  }

  /**
   * Requests mob compaction
   * @param conf The Configuration
   * @param fs The file system
   * @param tableName The table the compact
   * @param columns The column descriptors
   * @param tableLockManager The tableLock manager
   * @param allFiles Whether add all mob files into the compaction.
   */
  public void requestMobCompaction(TableName tableName,
    List<HColumnDescriptor> columns, boolean allFiles)
    throws IOException {
    compactions.put(tableName, mobCompactionPool.submit(new CompactionRunner(fs, tableName,
      columns, master.getTableLockManager(), allFiles)));
    if (LOG.isDebugEnabled()) {
      LOG.debug("The mob compaction is requested for the columns " + columns + " of the table "
        + tableName.getNameAsString());
    }
  }

  private class CompactionRunner implements Callable<Void> {
    private FileSystem fs;
    private TableName tableName;
    private List<HColumnDescriptor> columns;
    private TableLockManager tableLockManager;
    private boolean allFiles;

    public CompactionRunner(FileSystem fs, TableName tableName, List<HColumnDescriptor> columns,
      TableLockManager tableLockManager, boolean allFiles) {
      super();
      this.fs = fs;
      this.tableName = tableName;
      this.columns = columns;
      this.tableLockManager = tableLockManager;
      this.allFiles = allFiles;
    }

    @Override
    public Void call() throws Exception{
      // TODO 1. check if there is another compaction is in progress and add it to memory
      // TODO 2. report the compaction start
      // TODO 3. acquire lock to sync with major compaction
      // TODO 4. collect del files.
      // TODO 5. send the request to region servers.
      // TODO 6. wait until the procedure is finished.
      // TODO 7. release lock.
      // TODO 8. remove this compaction from memory.
      // TODO 9. report the compaction stop

      // only compact enabled table
      if (!master.getAssignmentManager().getTableStateManager()
        .isTableState(tableName, TableState.State.ENABLED)) {
        LOG.warn("The table " + tableName + " is not enabled");
        throw new TableNotEnabledException(tableName);
      }
      // TODO consider to add sync here.
      if (ongoingCompactions.contains(tableName.getNameAsString())) {
        LOG.warn("Another mob compaction on table " + tableName.getNameAsString()
          + " is in progress");
        throw new IOException("Another mob compaction on table " + tableName.getNameAsString()
          + " is in progress");
      }
      try {
        master.reportMobCompactionStart(tableName);
        for (HColumnDescriptor column : columns) {
          doCompaction(conf, fs, tableName, column, tableLockManager, allFiles);
        }
      } catch (IOException e) {
        LOG.error("Failed to perform the mob compaction", e);
      } finally {
        ongoingCompactions.remove(tableName.getNameAsString());
        try {
          master.reportMobCompactionEnd(tableName);
        } catch (IOException e) {
          LOG.error("Failed to mark end of mob compation", e);
        }
      }
      return null;
    }

    private void doCompaction(Configuration conf, FileSystem fs, TableName tableName,
      HColumnDescriptor column, TableLockManager tableLockManager, boolean allFiles) {
      boolean tableLocked = false;
      TableLock lock = null;
      try {
        // the tableLockManager might be null in testing. In that case, it is lock-free.
        if (tableLockManager != null) {
          lock = tableLockManager.writeLock(MobUtils.getTableLockName(tableName),
            "Run MobCompactor");
          lock.acquire();
        }
        tableLocked = true;
        // merge del files
        Encryption.Context cryptoContext = MobUtils.createEncryptionContext(conf, column);
        Path mobTableDir = FSUtils.getTableDir(MobUtils.getMobHome(conf), tableName);
        Path mobFamilyDir = MobUtils.getMobFamilyPath(conf, tableName, column.getNameAsString());
        List<Path> delFilePaths = compactDelFiles(column, selectDelFiles(mobFamilyDir),
          EnvironmentEdgeManager.currentTime(), cryptoContext, mobTableDir, mobFamilyDir);
        // dispatch mob compaction
        boolean isAllFiles = dispatchMobCompaction(tableName, column);
        // delete the del files if it is a major compaction
        if (isAllFiles && !delFilePaths.isEmpty()) {
          LOG.info("After a mob compaction with all files selected, archiving the del files "
            + delFilePaths);
          List<StoreFile> delFiles = new ArrayList<StoreFile>();
          for (Path delFilePath : delFilePaths) {
            StoreFile sf = new StoreFile(fs, delFilePath, conf, compactionCacheConfig, BloomType.NONE);
            delFiles.add(sf);
          }
          try {
            MobUtils.removeMobFiles(conf, fs, tableName, mobTableDir, column.getName(), delFiles);
          } catch (IOException e) {
            LOG.error("Failed to archive the del files " + delFilePaths, e);
          }
        }
      } catch (Exception e) {
        LOG.error("Failed to compact the mob files for the column " + column.getNameAsString()
          + " in the table " + tableName.getNameAsString(), e);
      } finally {
        if (lock != null && tableLocked) {
          try {
            lock.release();
          } catch (IOException e) {
            LOG.error(
              "Failed to release the write lock for the table " + tableName.getNameAsString(), e);
          }
        }
      }
    }

    private boolean dispatchMobCompaction(TableName tableName, HColumnDescriptor column) throws IOException {
      // use procedure to dispatch the compaction to region servers.
      // Find all the online regions
      boolean allRegionsOnline = true;
      List<Pair<HRegionInfo, ServerName>> regionsAndLocations = MetaTableAccessor
        .getTableRegionsAndLocations(master.getConnection(), tableName, false);
      Map<String, List<String>> regionServers = new HashMap<String, List<String>>();
      for (Pair<HRegionInfo, ServerName> region : regionsAndLocations) {
        if (region != null && region.getFirst() != null && region.getSecond() != null) {
          HRegionInfo hri = region.getFirst();
          if (hri.isOffline() && (hri.isSplit() || hri.isSplitParent())) {
            allRegionsOnline = false;
            continue;
          }
          String serverName = region.getSecond().toString();
          List<String> regionNames = regionServers.get(serverName);
          if (regionNames != null) {
            regionNames.add(MD5Hash.getMD5AsHex(hri.getStartKey()));
          } else {
            regionNames = new ArrayList<String>();
            regionNames.add(MD5Hash.getMD5AsHex(hri.getStartKey()));
            regionServers.put(serverName, regionNames);
          }
        }
      }
      boolean success = false;
      if(allRegionsOnline && !regionServers.isEmpty()) {
        // add the map to zookeeper
        String compactionZNode = ZKUtil.joinZNode(compactionBaseZNode, tableName.getNameAsString());
        try {
          // clean the node if it exists
          ZKUtil.deleteNodeRecursively(master.getZooKeeper(), compactionZNode);
          ZKUtil.createEphemeralNodeAndWatch(master.getZooKeeper(), compactionZNode, null);
          for (Entry<String, List<String>> entry : regionServers.entrySet()) {
            String serverName = entry.getKey();
            List<String> startKeysOfOnlineRegions = entry.getValue();
            String compactionServerZNode = ZKUtil.joinZNode(compactionZNode, serverName);
            ZKUtil.createEphemeralNodeAndWatch(master.getZooKeeper(), compactionServerZNode,
              Bytes.toBytes(false));
            for (String startKeyOfOnlineRegion : startKeysOfOnlineRegions) {
              String compactionStartKeyZNode = ZKUtil.joinZNode(compactionServerZNode,
                startKeyOfOnlineRegion);
              ZKUtil.createEphemeralNodeAndWatch(master.getZooKeeper(), compactionStartKeyZNode,
                null);
            }
          }
        } catch (KeeperException e) {
          throw new IOException(e);
        }
      }
      // start the procedure
      
      // return if all the compactions are finished successfully
      return allRegionsOnline && success;
    }

    private List<Path> selectDelFiles(Path mobFamilyDir) throws IOException {
      // since all the del files are used in mob compaction, they have to be merged in one time
      // together.
      List<Path> allDelFiles = new ArrayList<Path>();
      for (FileStatus file : fs.listStatus(mobFamilyDir)) {
        if (!file.isFile()) {
          continue;
        }
        // group the del files and small files.
        Path originalPath = file.getPath();
        if (HFileLink.isHFileLink(file.getPath())) {
          HFileLink link = HFileLink.buildFromHFileLinkPattern(conf, file.getPath());
          FileStatus linkedFile = getLinkedFileStatus(link);
          if (linkedFile == null) {
            continue;
          }
          originalPath = link.getOriginPath();
        }
        if (StoreFileInfo.isDelFile(originalPath)) {
          allDelFiles.add(file.getPath());
        }
      }
      return allDelFiles;
    }

    private FileStatus getLinkedFileStatus(HFileLink link) throws IOException {
      Path[] locations = link.getLocations();
      for (Path location : locations) {
        FileStatus file = getFileStatus(location);
        if (file != null) {
          return file;
        }
      }
      return null;
    }

    private FileStatus getFileStatus(Path path) throws IOException {
      try {
        if (path != null) {
          FileStatus file = fs.getFileStatus(path);
          return file;
        }
      } catch (FileNotFoundException e) {
        LOG.warn("The file " + path + " can not be found", e);
      }
      return null;
    }

    /**
     * Compacts the del files in batches which avoids opening too many files.
     * @param request The compaction request.
     * @param delFilePaths
     * @return The paths of new del files after merging or the original files if no merging
     *         is necessary.
     * @throws IOException
     */
    private List<Path> compactDelFiles(HColumnDescriptor column, List<Path> delFilePaths,
      long selectionTime, Encryption.Context cryptoContext, Path mobTableDir, Path mobFamilyDir)
      throws IOException {
      if (delFilePaths.size() <= delFileMaxCount) {
        return delFilePaths;
      }
      // when there are more del files than the number that is allowed, merge it firstly.
      int offset = 0;
      List<Path> paths = new ArrayList<Path>();
      while (offset < delFilePaths.size()) {
        // get the batch
        int batch = compactionBatchSize;
        if (delFilePaths.size() - offset < compactionBatchSize) {
          batch = delFilePaths.size() - offset;
        }
        List<StoreFile> batchedDelFiles = new ArrayList<StoreFile>();
        if (batch == 1) {
          // only one file left, do not compact it, directly add it to the new files.
          paths.add(delFilePaths.get(offset));
          offset++;
          continue;
        }
        for (int i = offset; i < batch + offset; i++) {
          batchedDelFiles.add(new StoreFile(fs, delFilePaths.get(i), conf, compactionCacheConfig,
            BloomType.NONE));
        }
        // compact the del files in a batch.
        paths.add(compactDelFilesInBatch(column, batchedDelFiles, selectionTime, cryptoContext,
          mobTableDir, mobFamilyDir));
        // move to the next batch.
        offset += batch;
      }
      return compactDelFiles(column, paths, selectionTime, cryptoContext, mobTableDir, mobFamilyDir);
    }

    /**
     * Compacts the del file in a batch.
     * @param request The compaction request.
     * @param delFiles The del files.
     * @return The path of new del file after merging.
     * @throws IOException
     */
    private Path compactDelFilesInBatch(HColumnDescriptor column, List<StoreFile> delFiles,
      long selectionTime, Encryption.Context cryptoContext, Path mobTableDir, Path mobFamilyDir)
      throws IOException {
      // create a scanner for the del files.
      StoreScanner scanner = createScanner(column, delFiles, ScanType.COMPACT_RETAIN_DELETES);
      Writer writer = null;
      Path filePath = null;
      try {
        writer = MobUtils.createDelFileWriter(conf, fs, column,
          MobUtils.formatDate(new Date(selectionTime)), tempPath, Long.MAX_VALUE,
          column.getCompactionCompression(), HConstants.EMPTY_START_ROW, compactionCacheConfig,
          cryptoContext);
        filePath = writer.getPath();
        List<Cell> cells = new ArrayList<Cell>();
        boolean hasMore = false;
        ScannerContext scannerContext = ScannerContext.newBuilder().setBatchLimit(compactionKVMax)
          .build();
        do {
          hasMore = scanner.next(cells, scannerContext);
          for (Cell cell : cells) {
            writer.append(cell);
          }
          cells.clear();
        } while (hasMore);
      } finally {
        scanner.close();
        if (writer != null) {
          try {
            writer.close();
          } catch (IOException e) {
            LOG.error("Failed to close the writer of the file " + filePath, e);
          }
        }
      }
      // commit the new del file
      Path path = MobUtils.commitFile(conf, fs, filePath, mobFamilyDir, compactionCacheConfig);
      // archive the old del files
      try {
        MobUtils.removeMobFiles(conf, fs, tableName, mobTableDir, column.getName(), delFiles);
      } catch (IOException e) {
        LOG.error("Failed to archive the old del files " + delFiles, e);
      }
      return path;
    }

    /**
     * Creates a store scanner.
     * @param filesToCompact The files to be compacted.
     * @param scanType The scan type.
     * @return The store scanner.
     * @throws IOException
     */
    private StoreScanner createScanner(HColumnDescriptor column, List<StoreFile> filesToCompact,
      ScanType scanType) throws IOException {
      List scanners = StoreFileScanner.getScannersForStoreFiles(filesToCompact, false, true, false,
        false, HConstants.LATEST_TIMESTAMP);
      Scan scan = new Scan();
      scan.setMaxVersions(column.getMaxVersions());
      long ttl = HStore.determineTTLFromFamily(column);
      ScanInfo scanInfo = new ScanInfo(column, ttl, 0, CellComparator.COMPARATOR);
      StoreScanner scanner = new StoreScanner(scan, scanInfo, scanType, null, scanners, 0L,
        HConstants.LATEST_TIMESTAMP);
      return scanner;
    }
  }

  @Override
  public void stop(String why) {
    if (this.stopped)
      return;
    this.stopped = true;
    for (Future<Void> compaction : compactions.values()) {
      if (compaction != null) {
        compaction.cancel(true);
      }
    }
    try {
      if (coordinator != null) {
        coordinator.close();
      }
    } catch (IOException e) {
      LOG.error("stop ProcedureCoordinator error", e);
    }
  }

  @Override
  public boolean isStopped() {
    return this.stopped;
  }

  @Override
  public void initialize(MasterServices master, MetricsMaster metricsMaster)
    throws KeeperException, IOException, UnsupportedOperationException {
    // get the configuration for the coordinator
    Configuration conf = master.getConfiguration();
    long wakeFrequency = conf.getInt(MOB_COMPACTION_WAKE_MILLIS_KEY,
      MOB_COMPACTION_WAKE_MILLIS_DEFAULT);
    long timeoutMillis = conf.getLong(MOB_COMPACTION_TIMEOUT_MILLIS_KEY,
      MOB_COMPACTION_TIMEOUT_MILLIS_DEFAULT);

    // setup the procedure coordinator
    String name = master.getServerName().toString();
    ProcedureCoordinatorRpcs comms = new ZKProcedureCoordinatorRpcs(master.getZooKeeper(),
      getProcedureSignature(), name);

    this.coordinator = new ProcedureCoordinator(comms, mobCompactionPool, timeoutMillis,
      wakeFrequency);
  }

  @Override
  public synchronized boolean isProcedureDone(ProcedureDescription desc) throws IOException {
    TableName tableName = TableName.valueOf(desc.getInstance());
    Future<Void> compaction = compactions.get(tableName);
    if (compaction != null) {
      boolean isDone = compaction.isDone();
      if (isDone) {
        compactions.remove(tableName);
      }
      return isDone;
    }
    return true;
  }

  @Override
  public String getProcedureSignature() {
    return MOB_COMPACTION_PROCEDURE_SIGNATURE;
  }

  @Override
  public void execProcedure(ProcedureDescription desc) throws IOException {

    TableName tableName = TableName.valueOf(desc.getInstance());
    
  }
}
