/**
 * Copyright The Apache Software Foundation
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
package org.apache.hadoop.hbase.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.testclassification.ClientTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.apache.hadoop.io.IOUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;

@Category({MediumTests.class, ClientTests.class})
public class TestFlushFromClient {
  private static final Log LOG = LogFactory.getLog(TestFlushFromClient.class);
  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static AsyncConnection asyncConn;
  private static final byte[][] SPLITS = new byte[][]{Bytes.toBytes("3"), Bytes.toBytes("7")};
  private static final List<byte[]> ROWS = Arrays.asList(
    Bytes.toBytes("1"),
    Bytes.toBytes("4"),
    Bytes.toBytes("8"));
  private static final byte[] FAMILY = Bytes.toBytes("f1");

  @Rule
  public TestName name = new TestName();

  public TableName tableName;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TEST_UTIL.startMiniCluster(ROWS.size());
    asyncConn = ConnectionFactory.createAsyncConnection(TEST_UTIL.getConfiguration()).get();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    IOUtils.cleanup(null, asyncConn);
    TEST_UTIL.shutdownMiniCluster();
  }

  @Before
  public void setUp() throws Exception {
    tableName = TableName.valueOf(name.getMethodName());
    try (Table t = TEST_UTIL.createTable(tableName, FAMILY, SPLITS)) {
      List<Put> puts = ROWS.stream().map(r -> new Put(r)).collect(Collectors.toList());
      for (int i = 0; i != 20; ++i) {
        byte[] value = Bytes.toBytes(i);
        puts.forEach(p -> p.addColumn(FAMILY, value, value));
      }
      t.put(puts);
    }
    assertFalse(getRegionInfo().isEmpty());
    assertTrue(getRegionInfo().stream().allMatch(r -> r.getMemStoreSize() != 0));
  }

  @After
  public void tearDown() throws Exception {
    for (TableDescriptor htd : TEST_UTIL.getAdmin().listTableDescriptors()) {
      LOG.info("Tear down, remove table=" + htd.getTableName());
      TEST_UTIL.deleteTable(htd.getTableName());
    }
  }

  @Test
  public void testFlushTable() throws Exception {
    try (Admin admin = TEST_UTIL.getAdmin()) {
      admin.flush(tableName);
      assertFalse(getRegionInfo().stream().anyMatch(r -> r.getMemStoreSize() != 0));
    }
  }

  @Test
  public void testAsyncFlushTable() throws Exception {
    AsyncAdmin admin = asyncConn.getAdmin();
    admin.flush(tableName).get();
    assertFalse(getRegionInfo().stream().anyMatch(r -> r.getMemStoreSize() != 0));
  }

  @Test
  public void testFlushRegion() throws Exception {
    try (Admin admin = TEST_UTIL.getAdmin()) {
      for (HRegion r : getRegionInfo()) {
        admin.flushRegion(r.getRegionInfo().getRegionName());
        TimeUnit.SECONDS.sleep(1);
        assertEquals(0, r.getMemStoreSize());
      }
    }
  }

  @Test
  public void testAsyncFlushRegion() throws Exception {
    AsyncAdmin admin = asyncConn.getAdmin();
    for (HRegion r : getRegionInfo()) {
      admin.flushRegion(r.getRegionInfo().getRegionName()).get();
      TimeUnit.SECONDS.sleep(1);
      assertEquals(0, r.getMemStoreSize());
    }
  }

  @Test
  public void testFlushRegionServer() throws Exception {
    try (Admin admin = TEST_UTIL.getAdmin()) {
      for (HRegionServer rs : TEST_UTIL.getHBaseCluster()
            .getLiveRegionServerThreads()
            .stream().map(JVMClusterUtil.RegionServerThread::getRegionServer)
            .collect(Collectors.toList())) {
        admin.flushRegionServer(rs.getServerName());
        assertFalse(getRegionInfo(rs).stream().anyMatch(r -> r.getMemStoreSize() != 0));
      }
    }
  }

  @Test
  public void testAsyncFlushRegionServer() throws Exception {
    AsyncAdmin admin = asyncConn.getAdmin();
    for (HRegionServer rs : TEST_UTIL.getHBaseCluster()
      .getLiveRegionServerThreads()
      .stream().map(JVMClusterUtil.RegionServerThread::getRegionServer)
      .collect(Collectors.toList())) {
      admin.flushRegionServer(rs.getServerName()).get();
      assertFalse(getRegionInfo(rs).stream().anyMatch(r -> r.getMemStoreSize() != 0));
    }
  }

  private List<HRegion> getRegionInfo() {
    return TEST_UTIL.getHBaseCluster().getLiveRegionServerThreads().stream()
      .map(JVMClusterUtil.RegionServerThread::getRegionServer)
      .flatMap(r -> r.getRegions().stream())
      .filter(r -> r.getTableDescriptor().getTableName().equals(tableName))
      .collect(Collectors.toList());
  }

  private List<HRegion> getRegionInfo(HRegionServer rs) {
    return rs.getRegions().stream()
      .filter(v -> v.getTableDescriptor().getTableName().equals(tableName))
      .collect(Collectors.toList());
  }
}
