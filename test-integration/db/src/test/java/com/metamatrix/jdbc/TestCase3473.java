/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package com.metamatrix.jdbc;

import static junit.framework.Assert.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;

import com.metamatrix.core.util.UnitTestUtil;
import com.metamatrix.jdbc.MMDatabaseMetaData;
import com.metamatrix.jdbc.api.AbstractMMQueryTestCase;
import com.metamatrix.jdbc.util.ResultSetUtil;

/**
 */
public class TestCase3473 extends AbstractMMQueryTestCase {

    private static final String DQP_CONFIG_FILE = UnitTestUtil.getTestDataPath() + "/3473/3473.properties;user=test"; //$NON-NLS-1$

    private MMDatabaseMetaData dbmd = null;
 
    ////////////////////Query Related Methods///////////////////////////

    public static final short ALWAYS_NULL = 0;
    public static final short NEVER_NULL = 1;
    public static final short MAY_BE_NULL = 2;
    
    private static final boolean REPLACE_EXPECTED = false;
    private static final boolean WRITE_ACTUAL_RESULTS_TO_FILE = false;
    private static final boolean PRINT_RESULTSETS_TO_CONSOLE = false;
    
    private static final int MAX_COL_WIDTH = 65;

    private FileOutputStream actualOut = null;
    private BufferedReader expectedIn = null;
    private PrintStream stream = null;
    
    @Before public void setUp() throws SQLException {
    	Connection conn = getConnection("test", DQP_CONFIG_FILE); //$NON-NLS-1$
    	dbmd = (MMDatabaseMetaData)conn.getMetaData();
    }
    
    private void initResultSetStreams(String testName) {
        if (REPLACE_EXPECTED) {
            File actual = new File(UnitTestUtil.getTestDataPath() + "/3473/"+testName+".expected"); //$NON-NLS-1$ //$NON-NLS-2$
            try {
                actualOut = new FileOutputStream(actual);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        } else {
            if (WRITE_ACTUAL_RESULTS_TO_FILE) {
                File actual = new File(UnitTestUtil.getTestDataPath() + "/3473/"+testName+".actual"); //$NON-NLS-1$ //$NON-NLS-2$
                try {
                    actualOut = new FileOutputStream(actual);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            File expected = new File(UnitTestUtil.getTestDataPath() + "/3473/"+testName+".expected"); //$NON-NLS-1$ //$NON-NLS-2$
            try {
                expectedIn = new BufferedReader(new FileReader(expected));
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        PrintStream defaultStream = null;
        if (PRINT_RESULTSETS_TO_CONSOLE) {
            defaultStream = new PrintStream(System.out) {
                // System.out should be protected from being closed.
                public void close() {}
            };
        }
        stream = ResultSetUtil.getPrintStream(actualOut, expectedIn, defaultStream);

    }
    
    private void closeResultSetTestStreams() throws IOException {
        stream.close();
        if (actualOut != null) {
            actualOut.close();
        }
        if (expectedIn != null) {
            expectedIn.close();
        }
    }
    
    public void checkColumn(String expectedName, short nullable, Class type, String metadataName, Object value) {
        if(nullable == ALWAYS_NULL) {
            assertNull("Column " + expectedName + " should always be null but was: " + value, value);             //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        if(nullable == NEVER_NULL) {
            assertNotNull("Column " + expectedName + " should never be null but was", value);             //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        if(value != null) {
            assertTrue("Column " + expectedName + " is of wrong type", value.getClass().isAssignableFrom(type)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        assertEquals("Got incorrect column name", expectedName, metadataName); //$NON-NLS-1$
    }
    


    @Test public void testGetCrossReference() throws Exception {
        initResultSetStreams("testGetCrossReference"); //$NON-NLS-1$
        ResultSet rs = null;
        try { 
            stream.println("getCrossReference1"); //$NON-NLS-1$
            rs = dbmd.getCrossReference(null,"test", "all_databases", null,"test", "all_models");//$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        } finally { 
            if(rs != null) {
                rs.close();    
            }
        }
        
        // Check for empty
        stream.println("getCrossReference2"); //$NON-NLS-1$
        rs = dbmd.getCrossReference(null, "Foo%", "%", null, null, "%"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        rs.close();   
        
        // Check for empty
        stream.println("getCrossReference3"); //$NON-NLS-1$
        rs = dbmd.getCrossReference("foo", "Foo%", "%", null, null, "%"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        rs.close();          
 
        
        // Check for all cross references
        stream.println("getCrossReference4"); //$NON-NLS-1$
        rs = dbmd.getCrossReference(null, null, null, null, null, null); 
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        assertEquals("Actual data did not match expected", //$NON-NLS-1$
                     Collections.EMPTY_LIST,
                     ResultSetUtil.getUnequalLines(stream));
        rs.close();          
        closeResultSetTestStreams();  
    }
        
    @Test public void testGetImportedKeys() throws Exception {
        initResultSetStreams("testGetImportedKeys"); //$NON-NLS-1$
        ResultSet rs = null;
        try { 
            stream.println("getImportedKeys1"); //$NON-NLS-1$
            rs = dbmd.getImportedKeys(null,"test", "all_models"); //$NON-NLS-1$ //$NON-NLS-2$
            ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        } finally { 
            if(rs != null) {
                rs.close();    
            }
        }
        
        stream.println("getImportedKeys2"); //$NON-NLS-1$
        rs = dbmd.getImportedKeys(null, "Foo%", "%"); //$NON-NLS-1$ //$NON-NLS-2$ 
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        rs.close(); 

        stream.println("getImportedKeys3"); //$NON-NLS-1$
        rs = dbmd.getImportedKeys("foo", "Foo%", "%"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        assertEquals("Actual data did not match expected", //$NON-NLS-1$
                     Collections.EMPTY_LIST,
                     ResultSetUtil.getUnequalLines(stream));
        rs.close();        
        closeResultSetTestStreams();
    }

    @Test public void testGetExportedKeys() throws Exception {
        initResultSetStreams("testGetExportedKeys"); //$NON-NLS-1$
        ResultSet rs = null;
        try {
            rs = dbmd.getExportedKeys(null,"test", "all_models"); //$NON-NLS-1$ //$NON-NLS-2$ 
            ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        } finally { 
            if(rs != null) {
                rs.close();    
            }
        }
        
        rs = dbmd.getExportedKeys(null, "Foo%", "%"); //$NON-NLS-1$ //$NON-NLS-2$ 
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        rs.close();
        
        rs = dbmd.getExportedKeys("foo", "Foo%", "%"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        assertEquals("Actual data did not match expected", //$NON-NLS-1$
                     Collections.EMPTY_LIST,
                     ResultSetUtil.getUnequalLines(stream));
        rs.close();
        closeResultSetTestStreams();
    }
       
        
    @Test public void testGetPrimaryKeys() throws Exception {
        initResultSetStreams("testGetPrimaryKeys"); //$NON-NLS-1$
        ResultSet rs = null;
        try {
            rs = dbmd.getPrimaryKeys(null,"test", "all_models"); //$NON-NLS-1$ //$NON-NLS-2$
            ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        } finally { 
            if(rs != null) {
                rs.close();    
            }
        }
        
        // Check for empty
        rs = dbmd.getPrimaryKeys(null, "Foo%", "test.all_models"); //$NON-NLS-1$ //$NON-NLS-2$ 
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        rs.close();  
        
        rs = dbmd.getPrimaryKeys("foo", "Foo%", "test.all_models"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        assertEquals("Actual data did not match expected", //$NON-NLS-1$
                     Collections.EMPTY_LIST,
                     ResultSetUtil.getUnequalLines(stream));
        rs.close();
        closeResultSetTestStreams();
    }
    
    @Test public void testGetTables() throws Exception{
        initResultSetStreams("testGetTables"); //$NON-NLS-1$
        ResultSet rs = dbmd.getTables(null, null, null, null); 
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        rs.close();
        
        rs = dbmd.getTables(null, "%foo", null, null); //$NON-NLS-1$ 
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        rs.close();   
        
        rs = dbmd.getTables("foo", "%foo", null, null); //$NON-NLS-1$ //$NON-NLS-2$
        ResultSetUtil.printResultSet(rs, MAX_COL_WIDTH, true, stream);
        assertEquals("Actual data did not match expected", //$NON-NLS-1$
                     Collections.EMPTY_LIST,
                     ResultSetUtil.getUnequalLines(stream));
        rs.close();
        closeResultSetTestStreams();
    }
}