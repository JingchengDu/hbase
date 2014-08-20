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
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.Tag;
import org.apache.hadoop.hbase.TagType;
import org.apache.hadoop.hbase.mob.MobUtils;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * An implementation of the StoreFlusher. It extends the DefaultStoreFlusher.
 * If the store is not a mob store, the flusher flushes the MemStore the same with
 * DefaultStoreFlusher, 
 * If the store is a mob store, the flusher flushes the MemStore into two places.
 * One is the store files of HBase, the other is the mob files.
 * <ol>
 * <li>Cells that are not PUT type or have the delete mark will be directly flushed to HBase.</li>
 * <li>If the size of a cell value is larger than a threshold, it'll be flushed
 * to a mob file, another cell with the path of this file will be flushed to HBase.</li>
 * <li>If the size of a cell value is smaller than or equal with a threshold, it'll be flushed to
 * HBase directly.</li>
 * </ol>
 * 
 */
public class DefaultMobStoreFlusher extends DefaultStoreFlusher {

  private static final Log LOG = LogFactory.getLog(DefaultMobStoreFlusher.class);
  private final Object flushLock = new Object();
  private boolean isMob = false;
  private long mobCellValueSizeThreshold = 0;
  private Path targetPath;
  private MobFileStore mobFileStore;

  public DefaultMobStoreFlusher(Configuration conf, Store store) throws IOException{
    super(conf, store);
    isMob = MobUtils.isMobFamily(store.getFamily());
    mobCellValueSizeThreshold = MobUtils.getMobThreshold(store.getFamily());
    this.targetPath = MobUtils.getMobFamilyPath(conf, store.getTableName(),
        store.getColumnFamilyName());
    if (!this.store.getFileSystem().exists(targetPath)) {
      this.store.getFileSystem().mkdirs(targetPath);
    }
    mobFileStore = MobFileStore.create(conf, this.store.getFileSystem(),
        this.store.getTableName(), this.store.getFamily());
  }

  /**
   * Flushes the snapshot of the MemStore. 
   * If this store is not a mob store, flush the cells in the snapshot to store files of HBase. 
   * If the store is a mob one, the flusher flushes the MemStore into two places.
   * One is the store files of HBase, the other is the mob files.
   * <ol>
   * <li>Cells that are not PUT type or have the delete mark will be directly flushed to
   * HBase.</li>
   * <li>If the size of a cell value is larger than a threshold, it'll be
   * flushed to a mob file, another cell with the path of this file will be flushed to HBase.</li>
   * <li>If the size of a cell value is smaller than or equal with a threshold, it'll be flushed to
   * HBase directly.</li>
   * </ol>
   */
  @Override
  public List<Path> flushSnapshot(MemStoreSnapshot snapshot, long cacheFlushId,
      MonitoredTask status) throws IOException {
    ArrayList<Path> result = new ArrayList<Path>();
    int cellsCount = snapshot.getCellsCount();
    if (cellsCount == 0) return result; // don't flush if there are no entries

    // Use a store scanner to find which rows to flush.
    long smallestReadPoint = store.getSmallestReadPoint();
    InternalScanner scanner = createScanner(snapshot.getScanner(), smallestReadPoint);
    if (scanner == null) {
      return result; // NULL scanner returned from coprocessor hooks means skip normal processing
    }
    StoreFile.Writer writer;
    try {
      // TODO: We can fail in the below block before we complete adding this flush to
      // list of store files. Add cleanup of anything put on filesystem if we fail.
      synchronized (flushLock) {
        status.setStatus("Flushing " + store + ": creating writer");
        // Write the map out to the disk
        writer = store.createWriterInTmp(cellsCount, store.getFamily().getCompression(),
            false, true, true);
        writer.setTimeRangeTracker(snapshot.getTimeRangeTracker());
        try {
          if (!isMob) {
            // It's not a mob store, flush the cells in a normal way
            performFlush(scanner, writer, smallestReadPoint);
          } else {
            // It's a mob store, flush the cells in a mob way. This is the difference of flushing
            // between a normal and a mob store.
            performMobFlush(snapshot, cacheFlushId, scanner, writer, status);
          }
        } finally {
          finalizeWriter(writer, cacheFlushId, status);
        }
      }
    } finally {
      scanner.close();
    }
    LOG.info("Flushed, sequenceid=" + cacheFlushId + ", memsize="
        + snapshot.getSize() + ", hasBloomFilter=" + writer.hasGeneralBloom()
        + ", into tmp file " + writer.getPath());
    result.add(writer.getPath());
    return result;
  }

  /**
   * Flushes the cells in the mob store.
   * <ol>In the mob store, the cells with PUT type might have or have no mob tags.
   * <li>If a cell does not have a mob tag, flushing the cell to different files depends
   * on the value length. If the length is larger than a threshold, it's flushed to a
   * mob file and the mob file is flushed to a store file in HBase. Otherwise, directly
   * flush the cell to a store file in HBase.</li>
   * <li>If a cell have a mob tag, its value is a mob file name, directly flush it
   * to a store file in HBase.</li>
   * </ol>
   * @param snapshot Memstore snapshot.
   * @param cacheFlushId Log cache flush sequence number.
   * @param scanner The scanner of memstore snapshot.
   * @param writer The store file writer.
   * @param status Task that represents the flush operation and may be updated with status.
   * @throws IOException
   */
  protected void performMobFlush(MemStoreSnapshot snapshot, long cacheFlushId,
      InternalScanner scanner, StoreFile.Writer writer, MonitoredTask status) throws IOException {
    StoreFile.Writer mobFileWriter = null;
    int compactionKVMax = conf.getInt(HConstants.COMPACTION_KV_MAX,
        HConstants.COMPACTION_KV_MAX_DEFAULT);
    long mobKVCount = 0;
    long time = snapshot.getTimeRangeTracker().getMaximumTimestamp();
    mobFileWriter = mobFileStore.createWriterInTmp(new Date(time), snapshot.getCellsCount(), store
        .getFamily().getCompression(), store.getRegionInfo().getStartKey());
    // the target path is {tableName}/.mob/{cfName}/mobFiles
    // the relative path is mobFiles
    String relativePath = mobFileWriter.getPath().getName();
    byte[] referenceValue = Bytes.toBytes(relativePath);
    try {
      List<Cell> kvs = new ArrayList<Cell>();
      boolean hasMore;
      do {
        hasMore = scanner.next(kvs, compactionKVMax);
        if (!kvs.isEmpty()) {
          for (Cell c : kvs) {
            // If we know that this KV is going to be included always, then let us
            // set its memstoreTS to 0. This will help us save space when writing to
            // disk.
            KeyValue kv = KeyValueUtil.ensureKeyValue(c);
            if (kv.getValueLength() <= mobCellValueSizeThreshold || MobUtils.isMobReferenceCell(kv)
                || kv.getTypeByte() != KeyValue.Type.Put.getCode()) {
              writer.append(kv);
            } else {
              // append the original keyValue in the mob file.
              mobFileWriter.append(kv);
              mobKVCount++;

              // append the tags to the KeyValue.
              // The key is same, the value is the filename of the mob file
              List<Tag> existingTags = Tag.asList(kv.getTagsArray(), kv.getTagsOffset(),
                  kv.getTagsLength());
              if (existingTags.isEmpty()) {
                existingTags = new ArrayList<Tag>();
              }
              Tag mobRefTag = new Tag(TagType.MOB_REFERENCE_TAG_TYPE, HConstants.EMPTY_BYTE_ARRAY);
              existingTags.add(mobRefTag);
              long valueLength = kv.getValueLength();
              byte[] newValue = Bytes.add(Bytes.toBytes(valueLength), referenceValue);
              KeyValue reference = new KeyValue(kv.getRowArray(), kv.getRowOffset(),
                  kv.getRowLength(), kv.getFamilyArray(), kv.getFamilyOffset(),
                  kv.getFamilyLength(), kv.getQualifierArray(), kv.getQualifierOffset(),
                  kv.getQualifierLength(), kv.getTimestamp(), KeyValue.Type.Put, newValue, 0,
                  newValue.length, existingTags);
              reference.setSequenceId(kv.getSequenceId());
              writer.append(reference);
            }
          }
          kvs.clear();
        }
      } while (hasMore);
    } finally {
      status.setStatus("Flushing mob file " + store + ": appending metadata");
      mobFileWriter.appendMetadata(cacheFlushId, false);
      status.setStatus("Flushing mob file " + store + ": closing flushed file");
      mobFileWriter.close();
    }

    if (mobKVCount > 0) {
      // commit the mob file from temp folder to target folder.
      mobFileStore.commitFile(mobFileWriter.getPath(), targetPath);
    } else {
      try {
        // If the mob file is empty, delete it instead of committing.
        store.getFileSystem().delete(mobFileWriter.getPath(), true);
      } catch (IOException e) {
        LOG.error("Fail to delete the temp mob file", e);
      }
    }
  }
}
