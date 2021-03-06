/**
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
package org.apache.hadoop.hdfs;

import junit.framework.TestCase;
import java.io.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.datanode.SimulatedFSDataset;

//TODO: Simulated Storage should be solved later.@TestInjectionForSimulatedStorage
/**
 * This class tests the replication and injection of blocks of a DFS file for simulated storage.
 */
public class TestMNInjection extends TestCase {
  private int checksumSize = 16;
  private int blockSize = checksumSize*2;
  private int numBlocks = 4;
  private int filesize = blockSize*numBlocks;
  private int numDataNodes = 4;
  private static final Log LOG = LogFactory.getLog("org.apache.hadoop.hdfs.TestMNInjection");

  
  private void writeFile(FileSystem fileSys, Path name, int repl) throws IOException {
    // create and write a file that contains three blocks of data
    FSDataOutputStream stm = fileSys.create(name, true,
                                            fileSys.getConf().getInt("io.file.buffer.size", 4096),
                                            (short)repl, blockSize);
    byte[] buffer = new byte[filesize];
    for (int i=0; i<buffer.length; i++) {
      buffer[i] = '1';
    }
    stm.write(buffer);
    stm.close();
  }
  
  // Waits for all of the blocks to have expected replication
  private void waitForBlockReplication(String filename, 
                                       ClientProtocol namenode,
                                       int expected, long maxWaitSec) 
                                       throws IOException {
    long start = System.currentTimeMillis();
    
    //wait for all the blocks to be replicated;
    LOG.info("Checking for block replication for " + filename);  
    LocatedBlocks blocks = namenode.getBlockLocations(filename, 0, Long.MAX_VALUE);
    assertEquals(numBlocks, blocks.locatedBlockCount());
    for (int i = 0; i < numBlocks; ++i) {
      LOG.info("Checking for block:" + (i+1));
      while (true) { // Loop to check for block i (usually when 0 is done all will be done
        blocks = namenode.getBlockLocations(filename, 0, Long.MAX_VALUE);
        assertEquals(numBlocks, blocks.locatedBlockCount());
        LocatedBlock block = blocks.get(i);
        int actual = block.getLocations().length;
        if ( actual == expected ) {
          LOG.info("Got enough replicas for " + (i+1) + "th block " + block.getBlock() +
              ", got " + actual + ".");
          break;
        }
        LOG.info("Not enough replicas for " + (i+1) + "th block " + 
                 block.getBlock() + " yet. Expecting " + expected + 
                 ", got " + actual + ".");
      
        if (maxWaitSec > 0 && (System.currentTimeMillis() - start) > (maxWaitSec * 1000)) {
          throw new IOException("Timedout while waiting for all blocks to " + " be replicated for " + filename);
        }
      
        try {
          Thread.sleep(500);
        } catch (InterruptedException ignored) {}
      }
    }
  }
 
  
  
  /** This test makes sure that NameNode retries all the available blocks 
   * for under replicated blocks. This test uses simulated storage and one
   * of its features to inject blocks,
   * 
   * It creates a file with several blocks and replication of 4. 
   * The cluster is then shut down - NN retains its state but the DNs are 
   * all simulated and hence loose their blocks. 
   * The blocks are then injected in one of the DNs. The  expected behaviour is
   * that the NN will arrange for the missing replica will be copied from a valid source.
   */
  public void testInjection() throws IOException {
    
    MiniMNDFSCluster cluster = null;
    String testFile = "/replication-test-file";
    Path testPath = new Path(testFile);
    
    byte buffer[] = new byte[1024];
    for (int i=0; i<buffer.length; i++) {
      buffer[i] = '1';
    }
    
    try {
      Configuration conf = new Configuration();
      conf.set("dfs.replication", Integer.toString(numDataNodes));
      conf.setInt("io.bytes.per.checksum", checksumSize);
      conf.setBoolean(SimulatedFSDataset.CONFIG_PROPERTY_SIMULATED, false);
      conf.setStrings("dfs.namenode.port.list", "0,0");
      conf.setInt("dfs.heartbeat.interval", 2);
      
      //first time format
      cluster = new MiniMNDFSCluster(conf, numDataNodes, true, null);
      cluster.waitActive();
      cluster.waitDatanodeDie();
      
      DFSClient dfsClient = null;
      FileSystem fs = cluster.getFileSystemCToFreeNamenode(-1);
      writeFile(fs, testPath, numDataNodes);
      DistributedFileSystem dfs = (DistributedFileSystem)fs;
      dfsClient = dfs.dfs;
      waitForBlockReplication(testFile, dfsClient.namenode, numDataNodes, 20);
    
      cluster.shutdown(false);
      cluster = null;
            
      /* 
       * Start the MiniMNDFSCluster with more datanodes since once a writeBlock
       * to a datanode node fails, same block can not be written to it
       * immediately. In our case some replication attempts will fail.
       */
      
      LOG.info("Restarting minicluster");
      conf = new Configuration();
      conf.setBoolean(SimulatedFSDataset.CONFIG_PROPERTY_SIMULATED, false);
      conf.setStrings("dfs.namenode.port.list", "0");
      
      for (int i = 3; i <= 2*numDataNodes; i++) {
        String filestr = "dfs/data/data" + i + "/current";
        File data_dir = new File(System.getProperty("test.build.data", "build/test/data"), filestr);
        File[] blocks = data_dir.listFiles();
        for (int idx = 0; idx < blocks.length; idx++) {
          if (!blocks[idx].getName().startsWith("blk_")) {
            continue;
          }
          System.out.println("Deliberately removing file "+blocks[idx].getName());
          assertTrue("Cannot remove file.", blocks[idx].delete());
        }
      }
      
      cluster = new MiniMNDFSCluster( conf, numDataNodes*2, false, null, false);
      cluster.waitActive();
      cluster.waitDatanodeDie();
      
      DistributedFileSystem disfs = (DistributedFileSystem)cluster.getFileSystemCToFreeNamenode(-1);
      waitForBlockReplication(testFile, disfs.dfs.namenode, numDataNodes, -1);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }  
}