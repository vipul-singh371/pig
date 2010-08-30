/*
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
package org.apache.pig.test;

import static org.junit.Assert.assertEquals;

import java.util.Iterator;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.pig.ExecType;
import org.apache.pig.PigServer;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.MRCompiler;
import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.Tuple;
import org.apache.pig.test.utils.TestHelper;
import org.apache.pig.tools.pigstats.JobStats;
import org.apache.pig.tools.pigstats.PigStats;
import org.apache.pig.tools.pigstats.PigStats.JobGraph;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestFRJoin2 {

    private static MiniCluster cluster = MiniCluster.buildCluster();
    
    private static final String INPUT_DIR = "frjoin";
    private static final String INPUT_FILE = "input";
    
    private static final int FILE_MERGE_THRESHOLD = 5;
    
    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        FileSystem fs = cluster.getFileSystem();
        fs.mkdirs(new Path(INPUT_DIR));
        int LOOP_SIZE = 2;
        for (int i=0; i<FILE_MERGE_THRESHOLD; i++) {        
            String[] input = new String[2*LOOP_SIZE];
            for (int n=0; n<LOOP_SIZE; n++) {
                for (int j=0; j<LOOP_SIZE;j++) {
                    input[n*LOOP_SIZE + j] = i + "\t" + j;
                }
            }
            Util.createInputFile(cluster, INPUT_DIR + "/part-0000" + i, input);
        }

        String[] input2 = new String[2*(LOOP_SIZE/2)];
        int k = 0;
        for (int i=1; i<=LOOP_SIZE/2; i++) {
            String si = i + "";
            for (int j=0; j<=LOOP_SIZE/2; j++) {
                input2[k++] = si + "\t" + j;
            }
        }
        Util.createInputFile(cluster, INPUT_FILE, input2);
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        cluster.shutDown();
    }

    @Test
    public void testConcatenateJob() throws Exception {
        PigServer pigServer = new PigServer(ExecType.MAPREDUCE, cluster
                .getProperties());
        
        pigServer.registerQuery("A = LOAD '" + INPUT_FILE + "' as (x:int,y:int);");
        pigServer.registerQuery("B = LOAD '" + INPUT_DIR + "' as (x:int,y:int);");
        
        DataBag dbfrj = BagFactory.getInstance().newDefaultBag(), dbshj = BagFactory.getInstance().newDefaultBag();
        {
            pigServer.getPigContext().getProperties().setProperty(
                    MRCompiler.FRJOIN_MERGE_FILES_THRESHOLD, String.valueOf(FILE_MERGE_THRESHOLD));
            
            pigServer.registerQuery("C = join A by y, B by y using 'repl';");
            Iterator<Tuple> iter = pigServer.openIterator("C");
            
            while(iter.hasNext()) {
                dbfrj.add(iter.next());
            }
            
            // In this case, multi-file-combiner is used so there is no need to add
            // a concatenate job
            assertEquals(2, PigStats.get().getJobGraph().size());
        }
        {
            pigServer.getPigContext().getProperties().setProperty(
                    "pig.noSplitCombination", "true");
            
            pigServer.registerQuery("C = join A by y, B by y using 'repl';");
            Iterator<Tuple> iter = pigServer.openIterator("C");
            
            while(iter.hasNext()) {
                dbshj.add(iter.next());
            }
            
            assertEquals(2, PigStats.get().getJobGraph().size());
        }
        
        assertEquals(dbfrj.size(), dbshj.size());
        assertEquals(true, TestHelper.compareBags(dbfrj, dbshj));    
    }
            
    @Test
    public void testTooManyReducers() throws Exception {
        PigServer pigServer = new PigServer(ExecType.MAPREDUCE, cluster
                .getProperties());
        
        pigServer.registerQuery("A = LOAD '" + INPUT_FILE + "' as (x:int,y:int);");
        pigServer.registerQuery("B = group A by x parallel " + FILE_MERGE_THRESHOLD + ";"); 
        pigServer.registerQuery("C = LOAD '" + INPUT_FILE + "' as (x:int,y:int);");
        DataBag dbfrj = BagFactory.getInstance().newDefaultBag(), dbshj = BagFactory.getInstance().newDefaultBag();
        {
            pigServer.getPigContext().getProperties().setProperty(
                    MRCompiler.FRJOIN_MERGE_FILES_THRESHOLD, String.valueOf(FILE_MERGE_THRESHOLD));
            pigServer.registerQuery("D = join C by $0, B by $0 using 'repl';");
            Iterator<Tuple> iter = pigServer.openIterator("D");
            
            while(iter.hasNext()) {
                Tuple t = iter.next();
                dbfrj.add(t);               
            }
            
            JobGraph jGraph = PigStats.get().getJobGraph();
            assertEquals(3, jGraph.size());
            // find added map-only concatenate job 
            JobStats js = (JobStats)jGraph.getSuccessors(jGraph.getSources().get(0)).get(0);
            assertEquals(1, js.getNumberMaps());   
            assertEquals(0, js.getNumberReduces());   
        }
        {
            pigServer.getPigContext().getProperties().setProperty(
                    "pig.noSplitCombination", "true");
            pigServer.registerQuery("D = join C by $0, B by $0 using 'repl';");
            Iterator<Tuple> iter = pigServer.openIterator("D");
            
            while(iter.hasNext()) {
                Tuple t = iter.next();
                dbshj.add(t);                
            }
            assertEquals(2, PigStats.get().getJobGraph().size());
        }        
        assertEquals(dbfrj.size(), dbshj.size());
        assertEquals(true, TestHelper.compareBags(dbfrj, dbshj));    
    }
    
    @Test
    public void testUnknownNumMaps() throws Exception {
        PigServer pigServer = new PigServer(ExecType.MAPREDUCE, cluster.getProperties());
        
        pigServer.registerQuery("A = LOAD '" + INPUT_DIR + "' as (x:int,y:int);");
        pigServer.registerQuery("B = Filter A by x < 50;");
        DataBag dbfrj = BagFactory.getInstance().newDefaultBag(), dbshj = BagFactory.getInstance().newDefaultBag();
        {
            pigServer.getPigContext().getProperties().setProperty(
                    MRCompiler.FRJOIN_MERGE_FILES_THRESHOLD, String.valueOf(FILE_MERGE_THRESHOLD));
            pigServer.registerQuery("C = join A by $0, B by $0 using 'repl';");
            Iterator<Tuple> iter = pigServer.openIterator("C");
            
            while(iter.hasNext()) {
                dbfrj.add(iter.next());
            }
            // In this case, multi-file-combiner is used in grandparent job
            // so there is no need to add a concatenate job
            JobGraph jGraph = PigStats.get().getJobGraph();
            assertEquals(2, jGraph.size());
        }
        {
            pigServer.getPigContext().getProperties().setProperty(
                    "pig.noSplitCombination", "true");
            pigServer.registerQuery("C = join A by $0, B by $0 using 'repl';");
            Iterator<Tuple> iter = pigServer.openIterator("C");
            
            while(iter.hasNext()) {
                dbshj.add(iter.next());
            }
            assertEquals(2, PigStats.get().getJobGraph().size());
        }
        assertEquals(dbfrj.size(), dbshj.size());
        assertEquals(true, TestHelper.compareBags(dbfrj, dbshj));    
    }
    
    @Test
    public void testUnknownNumMaps2() throws Exception {
        PigServer pigServer = new PigServer(ExecType.MAPREDUCE, cluster.getProperties());
        
        pigServer.registerQuery("A = LOAD '" + INPUT_DIR + "' as (x:int,y:int);");
        pigServer.registerQuery("B = LOAD '" + INPUT_FILE + "' as (x:int,y:int);");
        pigServer.registerQuery("C = join A by x, B by x using 'repl';");
        DataBag dbfrj = BagFactory.getInstance().newDefaultBag(), dbshj = BagFactory.getInstance().newDefaultBag();
        {
            pigServer.getPigContext().getProperties().setProperty(
                    MRCompiler.FRJOIN_MERGE_FILES_THRESHOLD, String.valueOf(FILE_MERGE_THRESHOLD));
            pigServer.registerQuery("D = join B by $0, C by $0 using 'repl';");
            Iterator<Tuple> iter = pigServer.openIterator("D");
            
            while(iter.hasNext()) {
                dbfrj.add(iter.next());
            }
            // In this case, multi-file-combiner is used in grandparent job
            // so there is no need to add a concatenate job
            JobGraph jGraph = PigStats.get().getJobGraph();
            assertEquals(3, jGraph.size());
        }
        {
            pigServer.getPigContext().getProperties().setProperty(
                    "pig.noSplitCombination", "true");
            pigServer.registerQuery("D = join B by $0, C by $0 using 'repl';");
            Iterator<Tuple> iter = pigServer.openIterator("D");
            
            while(iter.hasNext()) {
                dbshj.add(iter.next());
            }
            assertEquals(3, PigStats.get().getJobGraph().size());
        }
        assertEquals(dbfrj.size(), dbshj.size());
        assertEquals(true, TestHelper.compareBags(dbfrj, dbshj));    
    }
    
}
