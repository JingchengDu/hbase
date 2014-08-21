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
package org.apache.hadoop.hbase.mob;

import java.util.Date;
import java.util.Random;
import java.util.UUID;

import junit.framework.TestCase;

import org.apache.hadoop.hbase.SmallTests;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SmallTests.class)
public class TestMobFileName extends TestCase {

  private String uuid;
  private Date date;
  private String dateStr;
  private int startKey;

  public void setUp() {
    Random random = new Random();
    uuid = UUID.randomUUID().toString().replaceAll("-", "");
    date = new Date();
    dateStr = MobUtils.formatDate(date);
    startKey = random.nextInt();
  }

  @Test
  public void testHashCode() {
    assertEquals(
      MobFileName.create(MobUtils.int2HexString(startKey),
        dateStr, uuid).hashCode(),
      MobFileName.create(MobUtils.int2HexString(startKey),
        dateStr, uuid).hashCode());
    assertNotSame(
      MobFileName.create(MobUtils.int2HexString(startKey),
        dateStr, uuid).hashCode(),
      MobFileName.create(MobUtils.int2HexString(startKey),
        dateStr, uuid).hashCode());
  }

  @Test
  public void testCreate() {
    MobFileName mobFileName = MobFileName.create(
        MobUtils.int2HexString(startKey), dateStr, uuid);
    assertEquals(mobFileName,
      MobFileName.create(mobFileName.getFileName()));
  }

  @Test
  public void testSwitchHexStringInt() {
    int[] ints = { -1, 0, 123, Integer.MIN_VALUE, Integer.MAX_VALUE };
    for (int i = 0; i < ints.length; i++) {
      assertEquals(ints[i],
          MobUtils.hexString2Int(MobUtils.int2HexString(ints[i])));
    }
  }

  @Test
  public void testGet() {
    MobFileName mobFileName = MobFileName.create(
        MobUtils.int2HexString(startKey), dateStr, uuid);
    assertEquals(MobUtils.int2HexString(startKey),
      mobFileName.getStartKey()); // getStartKey
    assertEquals(dateStr, mobFileName.getDate()); // getDate
    assertEquals(
      mobFileName.getFileName(),
      MobUtils.int2HexString(startKey) + dateStr + uuid); // getFileName
  }

  @Test
  public void testEquals() {
    MobFileName mobFileName = MobFileName.create(
        MobUtils.int2HexString(startKey), dateStr, uuid);
    assertTrue(mobFileName.equals(mobFileName));
    assertFalse(mobFileName.equals(this));
    assertTrue(mobFileName.equals(MobFileName.create(
        MobUtils.int2HexString(startKey), dateStr, uuid)));
  }
}
