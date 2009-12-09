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

package org.teiid.test.client.ctc;

import java.io.File;
import java.io.IOException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.jdom.JDOMException;
import org.teiid.test.client.ExpectedResults;
import org.teiid.test.client.QueryScenario;
import org.teiid.test.client.ResultsGenerator;
import org.teiid.test.client.TestResult;
import org.teiid.test.framework.ConfigPropertyLoader;
import org.teiid.test.framework.ConfigPropertyNames;
import org.teiid.test.framework.TestLogger;
import org.teiid.test.framework.exception.QueryTestFailedException;

import com.metamatrix.core.util.StringUtil;



public class XMLExpectedResults implements ExpectedResults {
     
    protected Properties props;
    protected int resultMode = -1;
    protected String generateDir = null;
    protected String querySetIdentifier = null;
    protected String results_dir_loc = null;
     
    protected Map<String, ResultsHolder> loadedResults = new HashMap<String, ResultsHolder>();
    
    
    public XMLExpectedResults(Properties properties, String querySetIdentifier) {
    	this.props = properties;
    	this.querySetIdentifier = querySetIdentifier;
     	
    	this.results_dir_loc = props.getProperty(
				PROP_EXPECTED_RESULTS_DIR_LOC, ""); 
    	
	String expected_root_loc = this.props
		.getProperty(PROP_EXPECTED_RESULTS_ROOT_DIR);

	if (expected_root_loc != null) {
	    File dir = new File(expected_root_loc, results_dir_loc);
	    this.results_dir_loc = dir.getAbsolutePath();
	}

    	
    	TestLogger.logInfo("Expected results loc: " + this.results_dir_loc);
    }


	@Override
	public boolean isExceptionExpected(String queryidentifier) throws QueryTestFailedException {
       		ResultsHolder expectedResults = (ResultsHolder) getResults(queryidentifier);

		return (expectedResults.getExceptionMsg() == null ? false : true);
	}



	@Override
	public String getQuerySetID() {
	    return this.querySetIdentifier;
	}



	@Override
	public synchronized File getResultsFile(String queryidentifier) throws QueryTestFailedException {
		return findExpectedResultsFile(queryidentifier, this.querySetIdentifier);
		
	}
	
	private ResultsHolder getResults(String queryidentifier) throws QueryTestFailedException {
		ResultsHolder rh = null;
		
		if (!loadedResults.containsKey(queryidentifier)) {
			rh = loadExpectedResults( findExpectedResultsFile(queryidentifier, this.querySetIdentifier));
		} else {
			rh = loadedResults.get(queryidentifier);
		}
				
		return rh;
	}
	
	   /**
     * Compare the results of a query with those that were expected.
     * 
     * @param expectedResults
     *            The expected results.
     * @param results
     *            The actual results - may be null if <code>actualException</code>.
     * @param actualException
     *            The actual exception recieved durring query execution - may be null if <code>results</code>.
     * @param isOrdered
     *            Are the actual results ordered?
     * @param batchSize
     *            Size of the batch(es) used in determining when the first batch of results were read.
     * @return The response time for comparing the first batch (sizes) of resutls.
     * @throws QueryTestFailedException
     *             If comparison fails.
     */
    public void compareResults(  final String queryIdentifier,
    								  final String sql,
                                      final ResultSet resultSet,
                                      final Throwable actualException,
                                      final int testStatus,
                                      final boolean isOrdered,
                                      final int batchSize) throws QueryTestFailedException {

        final String eMsg = "CompareResults Error: "; //$NON-NLS-1$
   	
	   	ResultsHolder expectedResults = (ResultsHolder) getResults(queryIdentifier);
        ResultsHolder actualResults;
        String actualExceptionClass = null;

	       
        switch (testStatus) {
		case TestResult.RESULT_STATE.TEST_EXCEPTION:
			
			
			break;
			
		case TestResult.RESULT_STATE.TEST_EXPECTED_EXCEPTION:
		    
		           actualExceptionClass = actualException.getClass().getName();

		            if (!expectedResults.isException()) {
		                // The actual exception was expected, but the expected results was not
		                throw new QueryTestFailedException(eMsg + "TThe actual exception was expected, but the Expected results wasn't an exception.  Actual exception: '" //$NON-NLS-1$
		                                                   + actualException.getMessage() + "'"); //$NON-NLS-1$
		            }
		            // We got an exception that we expected - convert actual exception to ResultsHolder
		            actualResults = new ResultsHolder(TagNames.Elements.EXCEPTION);
		            actualResults.setQueryID(expectedResults.getQueryID());

		            actualResults = convertException(actualException, actualResults);
		            
		            compareExceptions(actualResults, expectedResults, eMsg);

			
			break;

			// default success
		default:
			
			
			// is an expected exception
			if (actualException != null) {
				
		           actualExceptionClass = actualException.getClass().getName();

		            if (!expectedResults.isException()) {
		                // We didn't get results but there was no expected exception either
		                throw new QueryTestFailedException(eMsg + "Actual was an exception, but expected results was not. '" //$NON-NLS-1$
		                	 + actualException.getMessage() + "'"); //$NON-NLS-1$
		            }
		            // We got an exception that we expected - convert actual exception to ResultsHolder
		            actualResults = new ResultsHolder(TagNames.Elements.EXCEPTION);
		            actualResults.setQueryID(expectedResults.getQueryID());

		            actualResults = convertException(actualException, actualResults);
		            
		            compareExceptions(actualResults, expectedResults, eMsg);


				
			} else {
				
		           actualResults = new ResultsHolder(TagNames.Elements.QUERY_RESULTS);
		            actualResults.setQueryID(expectedResults.getQueryID());
		          long firstBatchResponseTime = 
		            	convertResults(resultSet, batchSize, actualResults);
			
		           compareResultSets(actualResults.getRows(),
		        	   actualResults.getTypes(),
		        	   actualResults.getIdentifiers(),
		        	   expectedResults.getRows(),
		        	   expectedResults.getTypes(),
		        	   expectedResults.getIdentifiers(),
		        	   eMsg);

				
			}
			
			break;
		}

    	
    }
    
    private ResultsHolder convertException(final Throwable actualException,
			final ResultsHolder actualResults) {
		actualResults.setExceptionClassName(actualException.getClass()
				.getName());
		actualResults.setExceptionMsg(actualException.getMessage());
		return actualResults;
	}
    
    /**
     * Helper to convert results into records and record first batch response time.
     * 
     * @param results
     * @param batchSize
     * @param resultsHolder
     *            Modified - results added by this method.
     * @return List of sorted results.
     * @throws QueryTestFailedException
     *             replaced SQLException.
     */
    private final long convertResults(final ResultSet results,
                                             final int batchSize,
                                             ResultsHolder resultsHolder) throws QueryTestFailedException {

        long firstBatchResponseTime = 0;
        final List records = new ArrayList();
        final List columnTypeNames = new ArrayList();
        final List columnTypes = new ArrayList();

        final ResultSetMetaData rsMetadata;
        final int colCount;

        // Get column info
        try {
            rsMetadata = results.getMetaData();
            colCount = rsMetadata.getColumnCount();
            // Read types of all columns
            for (int col = 1; col <= colCount; col++) {
                columnTypeNames.add(rsMetadata.getColumnName(col));
                columnTypes.add(rsMetadata.getColumnTypeName(col));
            }
        } catch (SQLException qre) {
            throw new QueryTestFailedException("Can't get results metadata: " + qre.getMessage()); //$NON-NLS-1$
        }

        // Get rows
        try {
            // Read all the rows
            for (int row = 0; results.next(); row++) {
                final List currentRecord = new ArrayList(colCount);
                // Read values for this row
                for (int col = 1; col <= colCount; col++) {
                    currentRecord.add(results.getObject(col));
                }
                records.add(currentRecord);
                // If this row is the (fetch size - 1)th row, record first batch response time
                if (row == batchSize) {
                    firstBatchResponseTime = System.currentTimeMillis();
                }
            }
        } catch (SQLException qre) {
            throw new QueryTestFailedException("Can't get results: " + qre.getMessage()); //$NON-NLS-1$
        }

        // Set info on resultsHolder
        resultsHolder.setRows(records);
        resultsHolder.setIdentifiers(columnTypeNames);
        resultsHolder.setTypes(columnTypes);

        return firstBatchResponseTime;
    }
    
    /**
     * Added primarily for public access to the compare code for testing.
     * 
     * @param actualResults
     * @param expectedResults
     * @param eMsg
     * @param isOrdered
     * @throws QueryTestFailedException
     */
     protected void compareResults(final ResultsHolder actualResults,
                                      final ResultsHolder expectedResults,
                                      final String eMsg,
                                      boolean isOrdered) throws QueryTestFailedException {
        if (actualResults.isException() && expectedResults.isException()) {
            // Compare exceptions
            compareExceptions(actualResults, expectedResults, eMsg);
        } else if (actualResults.isResult() && expectedResults.isResult()) {
            // Compare results
            if (isOrdered == false && actualResults.hasRows() && expectedResults.hasRows()) {
                // If the results are not ordered, we can sort both
                // results and expected results to compare record for record
                // Otherwise, actual and expected results are already assumed
                // to be in same order

                //sort the sortedResults in ascending order
                final List actualRows = actualResults.getRows();
                sortRecords(actualRows, true);
                actualResults.setRows(actualRows);

                //sort the expectedResults with ascending order
                final List expectedRows = expectedResults.getRows();
                sortRecords(expectedRows, true);
                expectedResults.setRows(expectedRows);
            }

            compareResultSets(actualResults.getRows(),
                              actualResults.getTypes(),
                              actualResults.getIdentifiers(),
                              expectedResults.getRows(),
                              expectedResults.getTypes(),
                              expectedResults.getIdentifiers(),
                              eMsg);
        } else if (actualResults.isResult() && expectedResults.isException()) {
            // Error - expected exception but got result
        } else if (actualResults.isException() && expectedResults.isResult()) {
            // Error - expected result but got exception
        }
    }
     
     /**
      * sort one result that is composed of records of all columns
      */
     private static void sortRecords(List records,
                                     boolean ascending) {
         //if record's size == 0, don't need to sort
         if (records.size() != 0) {
             int nFields = ((List)records.get(0)).size();
             for (int k = 0; k < nFields; k++) {
                 for (int m = k; m < nFields; m++) {
                     int[] params = new int[m - k + 1];

                     for (int n = k, j = 0; n <= m; n++, j++) {
                         params[j] = n;
                     }

                     Collections.sort(records, new ListNestedSortComparator(params, ascending));
                 }
             }
         }
     }
    
    private void compareExceptions(final ResultsHolder actualResults,
			final ResultsHolder expectedResults, String eMsg)
			throws QueryTestFailedException {

		final String expectedExceptionClass = expectedResults
				.getExceptionClassName();
		final String expectedExceptionMsg = expectedResults.getExceptionMsg().toLowerCase();
		final String actualExceptionClass = actualResults
				.getExceptionClassName();
		final String actualExceptionMsg = actualResults.getExceptionMsg().toLowerCase();

		if (actualExceptionClass == null) {
			// We didn't get an actual exception, we should have
			throw new QueryTestFailedException(eMsg + "Expected exception: " //$NON-NLS-1$
					+ expectedExceptionClass + " but got none."); //$NON-NLS-1$
		}
		// Compare exception classes
		if (!expectedExceptionClass.equals(actualExceptionClass)) {
			throw new QueryTestFailedException(eMsg
					+ "Got wrong exception, expected \"" //$NON-NLS-1$
					+ expectedExceptionClass + "\" but got \"" + //$NON-NLS-1$
					actualExceptionClass + "\""); //$NON-NLS-1$
		}
		// Compare exception messages
		if (!expectedExceptionMsg.equals(actualExceptionMsg)) {
			// Give it another chance by comparing w/o line separators
			if (!compareStrTokens(expectedExceptionMsg, actualExceptionMsg)) {
				throw new QueryTestFailedException(
						eMsg
								+ "Got expected exception but with wrong message. Got " + actualExceptionMsg); //$NON-NLS-1$
			}
		}
	}

    private boolean compareStrTokens(String expectedStr, String gotStr) {
		String newline = System.getProperty("line.separator"); //$NON-NLS-1$
		List expectedTokens = StringUtil.split(expectedStr, newline);
		List gotTokens = StringUtil.split(gotStr, newline);
		for (int i = 0; i < expectedTokens.size(); i++) {
			String expected = (String) expectedTokens.get(i);
			String got = (String) gotTokens.get(i);
			if (!expected.equals(got)) {
				return false;
			}
		}
		return true;
	}
    
    /**
     * Compare actual results, identifiers and types with expected. <br>
     * <strong>Note </strong>: result list are expected to match element for element.</br>
     * 
     * @param actualResults
     * @param actualDatatypes
     * @param actualIdentifiers
     * @param expectedResults
     * @param expectedDatatypes
     * @param expectedIdentifiers
     * @param eMsg
     * @throws QueryTestFailedException
     *             If comparison fails.
     */
    protected void compareResultSets(final List actualResults,
                                          final List actualDatatypes,
                                          final List actualIdentifiers,
                                          final List expectedResults,
                                          final List expectedDatatypes,
                                          final List expectedIdentifiers,
                                          final String eMsg) throws QueryTestFailedException {
        // Compare column names and types
        compareIdentifiers(actualIdentifiers, expectedIdentifiers, actualDatatypes, expectedDatatypes);

        // Walk through records and compare actual against expected
        final int actualRowCount = actualResults.size();
        final int expectedRowCount = expectedResults.size();
        final int actualColumnCount = actualIdentifiers.size();

        // Check for less records than in expected results
        if (actualRowCount < expectedRowCount) {
            throw new QueryTestFailedException(eMsg + "Expected " + expectedRowCount + //$NON-NLS-1$
                                               " records but received only " + actualRowCount); //$NON-NLS-1$
        } else if (actualRowCount > expectedRowCount) {
            // Check also for more records than expected
            throw new QueryTestFailedException(eMsg + "Expected " + expectedRowCount + //$NON-NLS-1$
                                               " records but received " + actualRowCount); //$NON-NLS-1$
        }

        //      DEBUG:
        //        debugOut.println("================== Compariing Rows ===================");

        // Loop through rows
        for (int row = 0; row < actualRowCount; row++) {

            // Get actual record
            final List actualRecord = (List)actualResults.get(row);

            // Get expected record
            final List expectedRecord = (List)expectedResults.get(row);

            //          DEBUG:
            //            debugOut.println("Row: " + (row + 1));
            //            debugOut.println(" expectedRecord: " + expectedRecord);
            //            debugOut.println(" actualRecord: " + actualRecord);
            // Loop through columns
            // Compare actual elements with expected elements column by column in this row
            for (int col = 0; col < actualColumnCount; col++) {
                // Get actual value
                final Object actualValue = actualRecord.get(col);
                // Get expected value
                final Object expectedValue = expectedRecord.get(col);

                //              DEBUG:
                //                debugOut.println(" Col: " +(col +1) + ": expectedValue:[" + expectedValue + "] actualValue:[" + actualValue +
                // "]");

                // Compare these values
                if (expectedValue == null) {
                    // Compare nulls
                    if (actualValue != null) {
                        throw new QueryTestFailedException(eMsg + "Value mismatch at row " + (row + 1) //$NON-NLS-1$
                                                           + " and column " + (col + 1) //$NON-NLS-1$
                                                           + ": expected = [" //$NON-NLS-1$
                                                           + expectedValue + "], actual = [" //$NON-NLS-1$
                                                           + actualValue + "]"); //$NON-NLS-1$

                    }
                } else {
                    // Compare values with equals
                    if (!expectedValue.equals(actualValue)) {
                        // DEBUG:
                        //                        debugOut.println(" ExpectedType: " + expectedValue.getClass() + " ActualType: " +
                        // actualValue.getClass());
                        if (expectedValue instanceof String) {
                            final String expectedString = (String)expectedValue;
                            if (actualValue instanceof Blob || actualValue instanceof Clob || actualValue instanceof SQLXML) {
                                // LOB types are special case - metadata says they're Object types so
                                // expected results are of type String. Actual object type is MMBlob, MMClob.
                                // Must compare w/ String verion of actual!
                                if (!expectedValue.equals(actualValue.toString())) {
                                    throw new QueryTestFailedException(eMsg + "LOB Value mismatch at row " + (row + 1) //$NON-NLS-1$
                                                                       + " and column " + (col + 1) //$NON-NLS-1$
                                                                       + ": expected = [" //$NON-NLS-1$
                                                                       + expectedValue + "], actual = [" //$NON-NLS-1$
                                                                       + actualValue + "]"); //$NON-NLS-1$
                                }
                            } else if (!(actualValue instanceof String)) {
                                throw new QueryTestFailedException(eMsg + "Value mismatch at row " + (row + 1) //$NON-NLS-1$
                                                                   + " and column " + (col + 1) //$NON-NLS-1$
                                                                   + ": expected = [" //$NON-NLS-1$
                                                                   + expectedValue + "], actual = [" //$NON-NLS-1$
                                                                   + actualValue + "]"); //$NON-NLS-1$
                            } else if (expectedString.length() > 0) {
                                // Check for String difference
                                assertStringsMatch(expectedString, (String)actualValue, (row + 1), (col + 1), eMsg);
                            }
                        }
                    }
                }
            } // end loop through columns
        } // end loop through rows
    }
    
 
    
    protected void compareIdentifiers(List actualIdentifiers,
			List expectedIdentifiers, List actualDataTypes,
			List expectedDatatypes) throws QueryTestFailedException {

		// Check sizes
		if (expectedIdentifiers.size() != actualIdentifiers.size()) {
			throw new QueryTestFailedException(
					"Got incorrect number of columns, expected = " + expectedIdentifiers.size() + ", actual = " //$NON-NLS-1$ //$NON-NLS-2$
							+ actualIdentifiers.size());
		}

		// Compare identifier lists only by short name
		for (int i = 0; i < actualIdentifiers.size(); i++) {
			String actualIdent = (String) actualIdentifiers.get(i);
			String expectedIdent = (String) expectedIdentifiers.get(i);
			String actualType = (String) actualDataTypes.get(i);
			String expectedType = (String) expectedDatatypes.get(i);

			// Get short name for each identifier
			String actualShort = getShortName(actualIdent);
			String expectedShort = getShortName(expectedIdent);

			if (!expectedShort.equalsIgnoreCase(actualShort)) {
				throw new QueryTestFailedException(
						"Got incorrect column name at column " + i + ", expected = " + expectedShort + " but got = " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
								+ actualShort);
			}
			if (actualType.equalsIgnoreCase("xml")) {//$NON-NLS-1$
				actualType = "string";//$NON-NLS-1$
			}
			if (actualType.equalsIgnoreCase("clob")) {//$NON-NLS-1$
				actualType = "string";//$NON-NLS-1$
			}
			if (!expectedType.equalsIgnoreCase(actualType)) {
				throw new QueryTestFailedException(
						"Got incorrect column type at column " + i + ", expected = " + expectedType + " but got = " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
								+ actualType);
			}
		}
	}


   protected  String getShortName(String ident) {
        int index = ident.lastIndexOf("."); //$NON-NLS-1$
        if (index >= 0) {
            return ident.substring(index + 1);
        }
        return ident;
    }
    
    private static final int MISMATCH_OFFSET = 20;
    private static final int MAX_MESSAGE_SIZE = 50;

 
    
   protected void assertStringsMatch(final String expectedStr, final String actualStr,
			final int row, final int col, final String eMsg)
			throws QueryTestFailedException {
		// TODO: Replace stripCR() with XMLUnit comparison for XML results.
		// stripCR() is a workaround for comparing XML Queries
		// that have '\r'.
		String expected = stripCR(expectedStr);
		String actual = stripCR(actualStr);

		String locationText = ""; //$NON-NLS-1$
		int mismatchIndex = -1;
		if (!expected.equals(actual)) {
			if (expected != null && actual != null) {
				int shortestStringLength = expected.length();
				if (actual.length() < expected.length()) {
					shortestStringLength = actual.length();
				}
				for (int i = 0; i < shortestStringLength; i++) {
					if (expected.charAt(i) != actual.charAt(i)) {
						locationText = "  Strings do not match at character: " + (i + 1) + //$NON-NLS-1$
								". Expected [" + expected.charAt(i)
								+ "] but got [" + actual.charAt(i) + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						mismatchIndex = i;
						break;
					}
				}
			}

			String expectedPartOfMessage = expected;
			String actualPartOfMessage = actual;
			if (expected.length() + actual.length() > MAX_MESSAGE_SIZE) {
				expectedPartOfMessage = safeSubString(expected, mismatchIndex
						- MISMATCH_OFFSET, mismatchIndex + MISMATCH_OFFSET);
				actualPartOfMessage = safeSubString(actual, mismatchIndex
						- MISMATCH_OFFSET, mismatchIndex + MISMATCH_OFFSET);
			}

			String message = eMsg + "Value mismatch at row " + row + //$NON-NLS-1$
					" and column " + col + //$NON-NLS-1$
					". Expected: {0} but was: {1}" + locationText; //$NON-NLS-1$
			message = MessageFormat.format(message, new Object[] {
					expectedPartOfMessage, actualPartOfMessage });
			throw new QueryTestFailedException(message);
		}
	}
    
    private String safeSubString(String text, int startIndex,
			int endIndex) {
		String prefix = "...'"; //$NON-NLS-1$
		String suffix = "'..."; //$NON-NLS-1$

		int actualStartIndex = startIndex;
		if (actualStartIndex < 0) {
			actualStartIndex = 0;
			prefix = "'"; //$NON-NLS-1$
		}
		int actualEndIndex = endIndex;
		if (actualEndIndex > text.length() - 1) {
			actualEndIndex = text.length() - 1;
			if (actualEndIndex < 0) {
				actualEndIndex = 0;
			}
		}
		if (actualEndIndex == text.length() - 1 || text.length() == 0) {
			suffix = "'"; //$NON-NLS-1$
		}

		return prefix + text.substring(actualStartIndex, actualEndIndex)
				+ suffix;
	}


    
    private String stripCR(final String text) {
        if (text.indexOf('\r') >= 0) {
            StringBuffer stripped = new StringBuffer(text.length());
            int len = text.length();
            for (int i = 0; i < len; i++) {
                char current = text.charAt(i);
                if (current != '\r') {
                    stripped.append(current);
                }
            }
            return stripped.toString();
        }
        return text;
    }


	@Override
	public Object getMetaData(String queryidentifier) {
		// TODO Auto-generated method stub
		return null;
	}


	private ResultsHolder loadExpectedResults(File resultsFile) throws QueryTestFailedException {
        XMLQueryVisitationStrategy jstrat = new XMLQueryVisitationStrategy();
        final ResultsHolder expectedResult;
        try {
            expectedResult = jstrat.parseXMLResultsFile(resultsFile);
        } catch (IOException e) {
            throw new QueryTestFailedException("Unable to load expected results: " + e.getMessage()); //$NON-NLS-1$
        } catch (JDOMException e) {
            throw new QueryTestFailedException("Unable to load expected results: " + e.getMessage()); //$NON-NLS-1$
        }
        return expectedResult;
    }
	
    private File findExpectedResultsFile(String queryIdentifier,
			String querySetIdentifier) throws QueryTestFailedException {
		String resultFileName = queryIdentifier + ".xml"; //$NON-NLS-1$
		File file = new File(results_dir_loc + "/" + querySetIdentifier, resultFileName);
		if (!file.exists()) {
			throw new QueryTestFailedException("Query results file " + file.getAbsolutePath() + " cannot be found");
		}
		
		return file;

	}
    
	public static void main(String[] args) {
		System.setProperty(ConfigPropertyNames.CONFIG_FILE, "ctc-bqt-test.properties");

		ConfigPropertyLoader _instance = ConfigPropertyLoader.getInstance();
		Properties p = _instance.getProperties();
		if (p == null || p.isEmpty()) {
			throw new RuntimeException("Failed to load config properties file");

		}
		
		QueryScenario set = new CTCQueryScenario("testscenario", ConfigPropertyLoader.getInstance().getProperties());

		
		_instance.setProperty(XMLQueryReader.PROP_QUERY_FILES_ROOT_DIR, new File("target/classes/").getAbsolutePath() );
		

		

		try {

		    Iterator<String> it = set.getQuerySetIDs().iterator();
		    while (it.hasNext()) {
			String querySetID = it.next();

			Map queries = set.getQueries(querySetID);
			if (queries.size() == 0l) {
				System.out.println("Failed, didn't load any queries " );
			}
			
				
				ExpectedResults er = set.getExpectedResults(querySetID);
				    //new XMLExpectedResults(_instance.getProperties(), querySetID);
								
				ResultsGenerator gr = set.getResultsGenerator();
				    //new XMLGenerateResults(_instance.getProperties(), "testname", set.getOutputDirectory());

				Iterator qIt = queries.keySet().iterator();
				while(qIt.hasNext()) {
					String qId = (String) qIt.next();
					String sql = (String) queries.get(qId);
					
//					System.out.println("SetID #: " + cnt + "  Qid: " + qId + "   sql: " + sql);
					
					File resultsFile = er.getResultsFile(qId);
					if (resultsFile == null) {
						System.out.println("Failed to get results file for queryID " + qId);
					}
					
	
					
					
				}

		    }
			
			System.out.println("Completed Test");

		} catch (QueryTestFailedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}
    
    

}