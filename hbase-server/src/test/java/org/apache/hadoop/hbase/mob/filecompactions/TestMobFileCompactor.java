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
package org.apache.hadoop.hbase.mob.filecompactions;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.LargeTests;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.mob.MobConstants;
import org.apache.hadoop.hbase.mob.MobUtils;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.StoreFileInfo;
import org.apache.hadoop.hbase.regionserver.TestMobCompaction;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(LargeTests.class)
public class TestMobFileCompactor {
  static final Log LOG = LogFactory.getLog(TestMobCompaction.class.getName());
  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private Configuration conf = null;
  private String tableName;
  private static HTable hTable;
  private static Admin admin;
  private static HTableDescriptor desc;
  private static HColumnDescriptor hcd;
  private static FileSystem fs;
  private final static String row = "row_";
  private final static String family = "family";
  private final static String column = "column";

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TEST_UTIL.getConfiguration().setInt("hbase.master.info.port", 0);
    TEST_UTIL.getConfiguration().setBoolean("hbase.regionserver.info.port.auto", true);
    TEST_UTIL.startMiniCluster(1);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Before
  public void setUp() throws Exception {
    fs = TEST_UTIL.getTestFileSystem();
    conf = TEST_UTIL.getConfiguration();
    long tid = System.currentTimeMillis();
    tableName = "testMob" + tid;
    desc = new HTableDescriptor(TableName.valueOf(tableName));
    hcd = new HColumnDescriptor(family);
    hcd.setMobEnabled(true);
    hcd.setMobThreshold(0L);
    hcd.setMaxVersions(4);
    desc.addFamily(hcd);
    admin = TEST_UTIL.getHBaseAdmin();
    admin.createTable(desc);
    hTable = new HTable(conf, tableName);
    hTable.setAutoFlush(false, false);
  }

  @After
  public void tearDown() throws Exception {
    admin.disableTable(TableName.valueOf(tableName));
    admin.deleteTable(TableName.valueOf(tableName));
    admin.close();
    fs.delete(TEST_UTIL.getDataTestDir(), true);
  }

  @Test
  public void testCompactionWithoutDelFiles() throws Exception {
    int count = 10;
    //create table and generate 10 mob files
    generateMobTable(count, 1);

    assertEquals("Before compaction: mob rows", count, countMobRows(hTable));
    assertEquals("Before compaction: mob file count", count, countMobFiles());
    assertEquals("Before compaction: del file count", 0, countDelFiles());

    MobFileCompactor compactor = new PartitionedMobFileCompactor(conf, fs, desc.getTableName(), hcd);
    compactor.compact();

    assertEquals("After compaction: mob rows", count, countMobRows(hTable));
    assertEquals("After compaction: mob file count", 1, countMobFiles());
    assertEquals("After compaction: del file count", 0, countDelFiles());
  }

  @Test
  public void testCompactionWithDelFiles() throws Exception {
    int count = 8;
    //create table and generate 8 mob files
    generateMobTable(count, 1);

    //get mob files
    assertEquals("Before compaction: mob file count", count, countMobFiles());
    assertEquals(count, countMobRows(hTable));

    // now let's delete one cell
    Delete delete = new Delete(Bytes.toBytes(row + 0));
    delete.deleteFamily(Bytes.toBytes(family));
    hTable.delete(delete);
    hTable.flushCommits();
    admin.flush(TableName.valueOf(tableName));

    List<HRegion> regions = TEST_UTIL.getHBaseCluster().getRegions(Bytes.toBytes(tableName));
    for(HRegion region : regions) {
      region.waitForFlushesAndCompactions();
      region.compactStores(true);
    }

    assertEquals("Before compaction: mob rows", count-1, countMobRows(hTable));
    assertEquals("Before compaction: mob file count", count, countMobFiles());
    assertEquals("Before compaction: del file count", 1, countDelFiles());

    // do the mob file compaction
    MobFileCompactor compactor = new PartitionedMobFileCompactor(conf, fs, desc.getTableName(), hcd);
    compactor.compact();

    assertEquals("After compaction: mob rows", count-1, countMobRows(hTable));
    assertEquals("After compaction: mob file count", 1, countMobFiles());
    assertEquals("After compaction: del file count", 0, countDelFiles());
  }

  @Test
  public void testCompactionWithDelFilesAndNotMergeAllFiles() throws Exception {
    int mergeSize = 5000;
    // change the mob compaction merge size
    conf.setLong(MobConstants.MOB_FILEL_COMPACTION_MERGEABLE_THRESHOLD, mergeSize);

    int count = 8;
    // create table and generate 8 mob files
    generateMobTable(count, 1);

    // get mob files
    assertEquals("Before compaction: mob file count", count, countMobFiles());
    assertEquals(count, countMobRows(hTable));

    int largeFilesCount = countLargeFiles(mergeSize);;

    // now let's delete one cell
    Delete delete = new Delete(Bytes.toBytes(row + 0));
    delete.deleteFamily(Bytes.toBytes(family));
    hTable.delete(delete);
    hTable.flushCommits();
    admin.flush(TableName.valueOf(tableName));

    List<HRegion> regions = TEST_UTIL.getHBaseCluster().getRegions(Bytes.toBytes(tableName));
    for(HRegion region : regions) {
      region.waitForFlushesAndCompactions();
      region.compactStores(true);
    }
    assertEquals("Before compaction: mob rows", count-1, countMobRows(hTable));
    assertEquals("Before compaction: mob file count", count, countMobFiles());
    assertEquals("Before compaction: del file count", 1, countDelFiles());

    // do the mob file compaction
    MobFileCompactor compactor = new PartitionedMobFileCompactor(conf, fs, desc.getTableName(), hcd);
    compactor.compact();

    assertEquals("After compaction: mob rows", count-1, countMobRows(hTable));
    // After the compaction, the files smaller than the mob compaction merge size is merge to one file
    assertEquals("After compaction: mob file count", largeFilesCount + 1, countMobFiles());
    assertEquals("After compaction: del file count", 1, countDelFiles());

    // reset the conf the the default
    conf.setLong(MobConstants.MOB_FILEL_COMPACTION_MERGEABLE_THRESHOLD,
        MobConstants.DEFAULT_MOB_FILE_COMPACTION_MERGEABLE_THRESHOLD);
  }

  /**
   * count the number of rows in the given table.
   * @param table
   * @return the number of rows
   */
  private int countMobRows(final HTable table) throws IOException {
    Scan scan = new Scan();
    // Do not retrieve the mob data when scanning
    scan.setAttribute(MobConstants.MOB_SCAN_RAW, Bytes.toBytes(Boolean.TRUE));
    ResultScanner results = table.getScanner(scan);
    int count = 0;
    for (Result res : results) {
      count++;
    }
    results.close();
    return count;
  }

  /**
   * count the number of mob files in the mob path.
   * @return the number of the mob files
   */
  private int countMobFiles() throws IOException {
    Path mobDirPath = MobUtils.getMobFamilyPath(MobUtils.getMobRegionPath(conf,
        TableName.valueOf(tableName)), family);
    int count = 0;
    if (fs.exists(mobDirPath)) {
      FileStatus[] files = fs.listStatus(mobDirPath);
      for(FileStatus file : files) {
        if(!StoreFileInfo.isDelFile(file.getPath())) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * count the number of del files in the mob path.
   * @return the number of the del files
   */
  private int countDelFiles() throws IOException {
    Path mobDirPath = MobUtils.getMobFamilyPath(MobUtils.getMobRegionPath(conf,
        TableName.valueOf(tableName)), family);
    int count = 0;
    if (fs.exists(mobDirPath)) {
      FileStatus[] files = fs.listStatus(mobDirPath);
      for(FileStatus file : files) {
        if(StoreFileInfo.isDelFile(file.getPath())) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * count the number of files.
   * @param size the size of the file
   * @return the number of files large than the size
   */
  private int countLargeFiles(int size) throws IOException {
    Path mobDirPath = MobUtils.getMobFamilyPath(MobUtils.getMobRegionPath(
        TEST_UTIL.getConfiguration(), TableName.valueOf(tableName)), family);
    int count = 0;
    if (fs.exists(mobDirPath)) {
      FileStatus[] files = fs.listStatus(mobDirPath);
      for(FileStatus file : files) {
        // ignore the del files in the mob path
        if((!StoreFileInfo.isDelFile(file.getPath())) && (file.getLen() > size)) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * generate the mob table and insert some data in the table.
   * @param count the mob file number
   * @param flushStep the number of flush step
   */
  private void generateMobTable(int count, int flushStep)
      throws IOException, InterruptedException {
    if (count <= 0 || flushStep <= 0)
      return;
    int index = 0;
    for (int i = 0; i < count; i++) {
      byte[] mobVal = makeDummyData(100*(i+1));
      Put put = new Put(Bytes.toBytes(row + i));
      put.setDurability(Durability.SKIP_WAL);
      put.add(Bytes.toBytes(family), Bytes.toBytes(column), mobVal);
      hTable.put(put);
      if (index++ % flushStep == 0) {
        hTable.flushCommits();
        admin.flush(TableName.valueOf(tableName));
      }
    }
  }

  /**
   * make the dummy data with the size.
   * @param the size of data
   * @return the dummy data
   */
  private byte[] makeDummyData(int size) {
    byte[] dummyData = new byte[size];
    new Random().nextBytes(dummyData);
    return dummyData;
  }
}
