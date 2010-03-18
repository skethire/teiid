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

package org.teiid.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
//## JDBC4.0-begin ##
import java.sql.SQLClientInfoException;
import java.sql.NClob;
import java.sql.SQLXML;
//## JDBC4.0-end ##

/*## JDBC3.0-JDK1.5-begin ##
import com.metamatrix.core.jdbc.SQLXML; 
## JDBC3.0-JDK1.5-end ##*/
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.transaction.xa.Xid;

import org.teiid.plan.api.ExecutionProperties;

import com.metamatrix.common.api.MMURL;
import com.metamatrix.common.comm.api.ServerConnection;
import com.metamatrix.common.comm.exception.CommunicationException;
import com.metamatrix.common.comm.platform.socket.client.SocketServerConnection;
import com.metamatrix.common.util.SqlUtil;
import com.metamatrix.common.xa.XidImpl;
import com.metamatrix.common.xa.XATransactionException;
import com.metamatrix.dqp.client.DQP;
import com.metamatrix.dqp.client.ResultsFuture;

public class ConnectionImpl extends WrapperImpl implements Connection {
	private static Logger logger = Logger.getLogger("org.teiid.jdbc"); //$NON-NLS-1$

	// constant value giving product name
    private final static String SERVER_NAME = "Teiid Server"; //$NON-NLS-1$
    private final static String EMBEDDED_NAME = "Teiid Embedded"; //$NON-NLS-1$

    // Unique request ID generator
    private long requestIDGenerator;

    // url used to create the connection
    private String url;
 
    // properties object containing the connection properties.
    protected Properties propInfo;

    // status of connection object
    private boolean closed = false;
    // determines if a statement executed should be immediately committed.
    private boolean autoCommitFlag = true;

    // collection of all open statements on this connection
    private Collection<StatementImpl> statements = new ArrayList<StatementImpl>();
    // cached DatabaseMetadata
    private DatabaseMetaData dbmm;

   //Xid for participating in TXN
    private XidImpl transactionXid;
            
    //  Flag to represent if the connection state needs to be readOnly, default value false.
    private boolean readOnly = false;
    
    private boolean disableLocalTransactions = false;
    private DQP dqp;
    protected ServerConnection serverConn;
    private int transactionIsolation = Connection.TRANSACTION_READ_COMMITTED;
        
    public ConnectionImpl(ServerConnection serverConn, Properties info, String url) {        
    	this.serverConn = serverConn;
        this.url = url;
        this.dqp = serverConn.getService(DQP.class);
        
        // set default properties if not overridden
        String overrideProp = info.getProperty(ExecutionProperties.PROP_TXN_AUTO_WRAP);
        if ( overrideProp == null || overrideProp.trim().length() == 0 ) {
            info.put(ExecutionProperties.PROP_TXN_AUTO_WRAP, ExecutionProperties.TXN_WRAP_DETECT);
        }

        // Get default fetch size
        String defaultFetchSize = info.getProperty(ExecutionProperties.PROP_FETCH_SIZE);
        if (defaultFetchSize != null) {
            info.put(ExecutionProperties.PROP_FETCH_SIZE, defaultFetchSize);
        } else {
            info.put(ExecutionProperties.PROP_FETCH_SIZE, ""+BaseDataSource.DEFAULT_FETCH_SIZE); //$NON-NLS-1$
        }

        // Get partial results mode
        String partialResultsMode = info.getProperty(ExecutionProperties.PROP_PARTIAL_RESULTS_MODE);
        if (partialResultsMode != null) {
            info.put(ExecutionProperties.PROP_PARTIAL_RESULTS_MODE, partialResultsMode);
        } else {
            info.put(ExecutionProperties.PROP_PARTIAL_RESULTS_MODE, BaseDataSource.DEFAULT_PARTIAL_RESULTS_MODE);
        }
        
        // Get result set cache mode
        String resultSetCacheMode = info.getProperty(ExecutionProperties.RESULT_SET_CACHE_MODE);
        if (resultSetCacheMode != null) {
            info.put(ExecutionProperties.RESULT_SET_CACHE_MODE, resultSetCacheMode);
        } else {
            info.put(ExecutionProperties.RESULT_SET_CACHE_MODE, BaseDataSource.DEFAULT_RESULT_SET_CACHE_MODE);
        }

        String ansiQuotes = info.getProperty(ExecutionProperties.ANSI_QUOTED_IDENTIFIERS);
        if (ansiQuotes != null) {
            info.put(ExecutionProperties.ANSI_QUOTED_IDENTIFIERS, ansiQuotes);
        } else {
            info.put(ExecutionProperties.ANSI_QUOTED_IDENTIFIERS, Boolean.TRUE.toString());
        }
                
        logger.fine(JDBCPlugin.Util.getString("MMConnection.Session_success")); //$NON-NLS-1$
        logConnectionProperties(url, info);
                
        // properties object used in obtaining connection
        this.propInfo = info;
        
        this.disableLocalTransactions = Boolean.valueOf(this.propInfo.getProperty(ExecutionProperties.DISABLE_LOCAL_TRANSACTIONS)).booleanValue();
    }
    
    protected Properties getConnectionProperties() {
        return this.propInfo;
    }
    
    DQP getDQP() {
    	return this.dqp;
    }
    
    /**
     * Remove password & trusted token and log all other properties
     * @param connUrl - URL used to connect to server
     * @param info - properties object supplied
     */
    private void logConnectionProperties(String connUrl, Properties info) {
        StringBuffer modifiedUrl = new StringBuffer();

        // If we have valid URL
        if (connUrl != null) {
	        // We need wipe out the password here, before we write to the log
	        int startIndex = connUrl.indexOf("password="); //$NON-NLS-1$
	        if (startIndex != -1) {
	            modifiedUrl.append(connUrl.substring(0, startIndex));
	            modifiedUrl.append("password=***"); //$NON-NLS-1$
	            int endIndex = connUrl.indexOf(";", startIndex+9); //$NON-NLS-1$
	            if (endIndex != -1) {
	                modifiedUrl.append(";").append(connUrl.substring(endIndex)); //$NON-NLS-1$
	            }
	        }
	        logger.fine("Connection Url="+modifiedUrl); //$NON-NLS-1$
        }
        
        // Now clone the properties object and remove password and trusted token
        if (info != null) {
            Enumeration enumeration = info.keys();
            while (enumeration.hasMoreElements()) {
                String key = (String)enumeration.nextElement();
                Object anObj = info.get(key);
                // Log each property except for password and token.
                if (!MMURL.CONNECTION.PASSWORD.equalsIgnoreCase(key)) { 
                    logger.fine(key+"="+anObj); //$NON-NLS-1$
                }
            }
        }                              
    }
        
    String getUrl() {
        return this.url;
    }
    
    /**
     * Connection identifier of this connection 
     * @return identifier
     * @throws SQLException 
     */
    public String getConnectionId() {
    	return String.valueOf(this.serverConn.getLogonResult().getSessionID());
    }
    
    long currentRequestId() {
        return requestIDGenerator;
    }
    
    /**
     * Generate the next unique requestID for matching up requests with responses.
     * These IDs should be unique only in the context of a ServerConnection instance.
     * @return Request ID
     */
    long nextRequestID() {
        return requestIDGenerator++;
    }

    public void clearWarnings() throws SQLException {
        // do nothing
    }

    public void close() throws SQLException {
    	Throwable firstException = null;

    	if(closed) {
            return;
        }

        try {
            // close any statements that were created on this connection
        	try {
        		closeStatements();
        	} catch (SQLException se) {
        		firstException = se;
        	} finally {
        		this.serverConn.close();
                if ( firstException != null )
                	throw (SQLException)firstException;
        	}
        } catch (SQLException se) {
            throw TeiidSQLException.create(se, JDBCPlugin.Util.getString("MMConnection.Err_connection_close", se.getMessage())); //$NON-NLS-1$
        } finally {
            logger.fine(JDBCPlugin.Util.getString("MMConnection.Connection_close_success")); //$NON-NLS-1$
            // set the status of the connection to closed
            closed = true;            
        }
    }

    /**
     * <p>
     * Close all the statements open on this connection
     * </p>
     * 
     * @throws SQLException
     *             server statement object could not be closed.
     */
    void closeStatements() throws SQLException {
        // Closing the statement will cause the
        // MMConnection.closeStatement() method to be called,
        // which will modify this.statements.  So, we do this iteration
        // in a separate safe copy of the list
        List statementsSafe = new ArrayList(this.statements);
        Iterator statementIter = statementsSafe.iterator();
        SQLException ex = null;
        while (statementIter.hasNext ()) {
            Statement statement = (Statement) statementIter.next();
            try {
            	statement.close();
            } catch (SQLException e) {
            	ex = e;
            }
        }
        if (ex != null) {
            throw TeiidSQLException.create(ex, JDBCPlugin.Util.getString("MMConnection.Err_closing_stmts")); //$NON-NLS-1$
        }
    }

    /**
     * Called by MMStatement to notify the connection that the
     * statement has been closed.
     * @param statement
     */
    void closeStatement(Statement statement) {
        this.statements.remove(statement);
    }

    /**
     * <p>This method makes any changes involved in a transaction permanent and releases
     * any locks held by the connection object.  This is only used when auto-commit
     * is set to false.</p>
     * @throws SQLException if the transaction had been rolled back or marked to roll back.
     */
    public void commit() throws SQLException {
        checkConnection();
        if (!autoCommitFlag) {
            try {
                directCommit();
            } finally {
                beginLocalTxn(); 
            }
        }
    }

    private void directCommit() throws SQLException {
        try {
			ResultsFuture<?> future = this.dqp.commit();
			future.get();
		} catch (Exception e) {
			throw TeiidSQLException.create(e);
		}
        logger.fine(JDBCPlugin.Util.getString("MMConnection.Commit_success")); //$NON-NLS-1$
    }

    private void beginLocalTxn() throws SQLException {
        if (this.transactionXid == null) {
        	if (disableLocalTransactions) {
        		this.autoCommitFlag = true;
        		return;
        	}
            boolean txnStarted = false;
            try {
            	try {
            		this.dqp.begin();
        		} catch (XATransactionException e) {
        			throw TeiidSQLException.create(e);
        		} 
                txnStarted = true;
            } finally {
                if (!txnStarted) {
                    autoCommitFlag = true;
                }
            }
        }
    }
    
    /**
     * <p>This creates a MMStatement object for sending SQL statements to the MetaMatrix
     * server.  This should be used for statements without parameters.  For statements
     * that are executed many times, use the PreparedStatement object.</p>
     * @return a Statement object.
     * @throws a SQLException if a MetaMatrix server access error occurs.
     */
    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    /**
     * <p>Creates a Statement object that will produce ResultSet objects of the type
     * resultSetType and concurrency level resultSetConcurrency.</p>
     * @param intvalue indicating the ResultSet's type
     * @param intValue indicating the ResultSet's concurrency
     * @return Statement object.
     */
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        //Check to see the connection is open
        checkConnection();

        validateResultSetType(resultSetType);
        validateResultSetConcurrency(resultSetConcurrency);

        // add the statement object to the map
        StatementImpl newStatement = StatementImpl.newInstance(this, resultSetType, resultSetConcurrency);
        statements.add(newStatement);

        return newStatement;
    }

    /** 
     * @param resultSetType
     * @throws TeiidSQLException
     * @since 4.3
     */
    private void validateResultSetType(int resultSetType) throws TeiidSQLException {
        if (resultSetType == ResultSet.TYPE_SCROLL_SENSITIVE ) {
            String msg = JDBCPlugin.Util.getString("MMConnection.Scrollable_type_not_supported", "ResultSet.TYPE_SCROLL_SENSITIVE"); //$NON-NLS-1$ //$NON-NLS-2$
            throw new TeiidSQLException(msg);
        }
    }

    /** 
     * @param resultSetConcurrency
     * @throws TeiidSQLException
     * @since 4.3
     */
    private void validateResultSetConcurrency(int resultSetConcurrency) throws TeiidSQLException {
        if (resultSetConcurrency == ResultSet.CONCUR_UPDATABLE) {
            String msg = JDBCPlugin.Util.getString("MMConnection.Concurrency_type_not_supported", "ResultSet.CONCUR_UPDATABLE"); //$NON-NLS-1$ //$NON-NLS-2$
            throw new TeiidSQLException(msg);
        }
    }

    /**
     * <p>This method returns the current status of the connection in regards to it's
     * auto-commit state.  By default, the auto-commit is set to true.  Meaning that
     * any transaction that occurs is automatically commited to the MetaMatrix server.
     * #See corresponding setAutoCommit() method.</p>
     * @return true if the statements on this connection get committed on execution.
     * @throws SQLException should never happen
     */
    public boolean getAutoCommit() throws SQLException {
        //Check to see the connection is open
       checkConnection();
       return autoCommitFlag;
    }

    public String getCatalog() throws SQLException {
        //Check to see the connection is open
        checkConnection();
        //catalogs are not supported
        return this.serverConn.getLogonResult().getVdbName();
    }

    /**
     * <p>This method gets the ServerConnection object wrapped by this object.</p>
     * @return ServerConnection object
     */
    ServerConnection getServerConnection() throws SQLException {
        //Check to see the connection is open
        checkConnection();
        return serverConn;
    }

    String getVDBName() throws SQLException {
        //Check to see the connection is open
        checkConnection();
        //get the virtual database name to which we are connected.

        return this.serverConn.getLogonResult().getVdbName();
    }
    
    public int getVDBVersion() throws SQLException {
    	checkConnection();
        return this.serverConn.getLogonResult().getVdbVersion();
    }

    /**
     * Get's the name of the user who got this connection.
     * @return Sring object giving the user name
     * @throws SQLException if the connection is closed
     */
    String getUserName() throws SQLException {
        checkConnection();

        return this.serverConn.getLogonResult().getUserName();
    }
    
    public DatabaseMetaData getMetaData() throws SQLException {
        //Check to see the connection is open
        checkConnection();
        
        if (dbmm == null) {
            dbmm = new DatabaseMetaDataImpl(this);
        }       
        return dbmm;
    }

    /**
     * Get the database name that this connection is representing 
     * @return String name of the database
     */
    public String getDatabaseName() {
    	if (this.serverConn instanceof SocketServerConnection) {
    		return SERVER_NAME;
    	}
    	return EMBEDDED_NAME;
    }
    
    /**
     * Retrieves the current holdability of ResultSet objects created using this Connection object.
     * @param holdability int indicating the holdability
     * @return int holdability
     * @throws SQLException
     */
    public int getHoldability() throws SQLException {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    public int getTransactionIsolation() throws SQLException {
    	return this.transactionIsolation;
    }

    /**
     * Retreives the type map associated with this Connection object. The type map
     * contains entries for undefined types. This method always returns an empty
     * map since it is not possible to add entries to this type map
     * @return map containing undefined types(empty)
     * @throws SQLException, should never occur
     */
    public Map getTypeMap() throws SQLException {
        //Check to see the connection is open
        checkConnection();
        return new HashMap();
    }

    /**
     * <p>This method will return the first warning reported by calls on this connection,
     * or null if none exist.</p>
     * @return A SQLWarning object if there are any warnings.
     * @throws SQLException, should never occur
     */
    public SQLWarning getWarnings() throws SQLException {
        //Check to see the connection is open
        checkConnection();
        return null;  // we don't have any warnings
    }

    /**
     * <p>This method will return whether this connection is closed or not.</p>
     * @return booleanvalue indicating if the connection is closed
     * @throws SQLException, should never occur
     */
    public boolean isClosed() throws SQLException {
        return closed;
    }

    /**
     * <p>This method will return whether this connection is read only or not.
     * It will throw a SQLException if a MetaMatrix server access error occurs.
     * @return boolean value indication if connection is readonly
     * @throws SQLException, should never occur
     */
    public boolean isReadOnly() throws SQLException {
         return readOnly; 
    }

    /**
     * <p>This method will convert the given SQL String into a MetaMatrix SQL Request.
     * This will convert any date escape sequences into the appropriate MetaMatrix
     * type, and any kind of data transformations that the MetaMatrix server would
     * expect.  This method returns the native form of the statement that the driver
     * would have sent.</p>
     * @param sql string to be coverted into SQL understood by metamatrix
     * @return uncoverted sql string(escape parsing takesplace in metamatrix)
     * @throws SQLException, should never occur
     */
    public String nativeSQL(String sql) throws SQLException {
        // return the string argument without any modifications.
        // escape syntaxes are directly supported in the server
        return sql;
    }

    /**
     * <p>Creates a CallableStatement object that contains sql and that will produce
     * ResultSet objects that are non-scrollable and non-updatable. A SQL stored
     * procedure call statement is handled by creating a CallableStatement for it.</p>
     * @param sql String(escape syntax) for invoking a stored procedure.
     * @return CallableStatement object that can be used to execute the storedProcedure
     * @throws SQLException if there is an error creating the callable statement object
     */
    public CallableStatement prepareCall(String sql) throws SQLException {
    	//there is a problem setting the result set type to be non-scrollable
    	//See defect 17768
        return prepareCall(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    }

    /**
     * <p>Creates a CallableStatement object that contains a sql and that will produce
     * ResultSet objects of the type resultSetType and with a concurrency level of
     * resultSetConcurrency. A SQL stored procedure call statement is handled by
     * creating a CallableStatement for it.</p>
     * @param sql String(escape syntax) for invoking a stored procedure.
     * @param intvalue indicating the ResultSet's type
     * @param intValue indicating the ResultSet's concurrency
     * @return CallableStatement object that can be used to execute the storedProcedure
     */
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        //Check to see the connection is open
        checkConnection();
        
        validateResultSetType(resultSetType);
        validateResultSetConcurrency(resultSetConcurrency);
        validateSQL(sql);
        
        // add the statement object to the map
        CallableStatementImpl newStatement = CallableStatementImpl.newInstance(this, sql, resultSetType, resultSetConcurrency);
        statements.add(newStatement);
        return newStatement;
    }

    /** 
     * @param sql
     * @throws TeiidSQLException
     * @since 4.3
     */
    private void validateSQL(String sql) throws TeiidSQLException {
        if (sql == null) {
            String msg = JDBCPlugin.Util.getString("MMConnection.SQL_cannot_be_null"); //$NON-NLS-1$
            throw new TeiidSQLException(msg);
        }
    }

    /**
     * <p>This method creates a MMPreparedStatement which is used for sending parameterized
     * SQL statements to the MetaMatrix server.  A statement with or without IN parameters
     * can be pre-compiled and stored in a MMPreparedStatement object.  Since the MetaMatrix
     * server does not pre-compile statements, a sql statement will be constructed using the
     * parameters supplied which would be used for execution of this preparedStatement object.</p>
     * @param sql string representing a prepared statement
     * @return a PreparedStatement object
     * @throws SQLException if there is an error creating a prepared statement object
     */
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    /**
     * <p>Creates a PreparedStatement object that contains a sql and that will produce
     * ResultSet objects of the type resultSetType and with a concurrency level of
     * resultSetConcurrency.</p>
     * @param sql string representing a prepared statement
     * @param intvalue indicating the ResultSet's type
     * @param intValue indicating the ResultSet's concurrency
     * @return a PreparedStatement object
     */
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        //Check to see the connection is open
        checkConnection();
        
        validateResultSetType(resultSetType);
        validateResultSetConcurrency(resultSetConcurrency);
        validateSQL(sql);
        
        // add the statement object to the map
        PreparedStatementImpl newStatement = PreparedStatementImpl.newInstance(this, sql, resultSetType, resultSetConcurrency);
        statements.add(newStatement);
        return newStatement;
    }

    /**
     * <p>This method creates a MMPreparedStatement which is used for sending parameterized
     * SQL statements to the MetaMatrix server and it has the capability to retrieve auto-generated keys.</p>
     * @param sql string representing a prepared statement
     * @param intValue indicating the result set Type
     * @param intValue indicating the result set concurrency
     * @param intValue indicating the result set holdability
     * @return a PreparedStatement object
     * @throws SQLException if there is an error creating a prepared statement object
     */
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
        int resultSetHoldability ) throws SQLException {
    	throw SqlUtil.createFeatureNotSupportedException();
    }

    /**
     * <p>This method will drop all changes made since the beginning of the transaction
     * and release any MetaMatrix server locks currently held by this connection. This
     * method rolls back transactions on all the statements currently open on this connection.
     * This is used when then auto-commit has been disabled.</p>
     * @see setAutoCommit(boolean) method for more information.
     * @throws SQLException if there is an error rolling back.
     */
    public void rollback() throws SQLException {
        rollback(true);
    }
    
    void rollback(boolean startTxn) throws SQLException {

        //Check to see the connection is open
        checkConnection();
        if (!autoCommitFlag) {
            try {
            	try {
            		ResultsFuture<?> future = this.dqp.rollback();
            		future.get();
        		} catch (Exception e) {
        			throw TeiidSQLException.create(e);
        		}
                logger.fine(JDBCPlugin.Util.getString("MMConnection.Rollback_success")); //$NON-NLS-1$
            } finally {
                if (startTxn) {
                    beginLocalTxn();
                }
                else {
                    this.autoCommitFlag = true;
                }
            }
        }
    }

    /**
     * <p>This method will set the connection's auto commit mode accordingly.  By
     * default this is set to true (auto-commit is turned on).  An auto-commit
     * value of true means any statements will automatically be made permanent if
     * they are successful after the last row of the ReulstSet has been retrieved
     * or the next execute occurs, whichever comes first.  If set to false, changes
     * can be either be committed (using the commit() method) or rolled back ("undo
     * the changes" by using the rollback() method).</p>
     * @param boolean value indicating if autoCommit is turned on
     * @throws SQLException is metamatrix access error occurs.
     */
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        //Check to see the connection is open
        checkConnection();

        if (autoCommit == this.autoCommitFlag) {
            return;
        }
        
        this.autoCommitFlag = autoCommit;

        if (autoCommit) {
            directCommit();   
        } else {
            beginLocalTxn();
        }        
    }

    /**
     * <p>Metamatrix does not allow setting a catalog through a connection. This
     * method silently ignores the request as per the specification.</p>
     * @param The string values which sets the catalog name on the connection.
     * @throws SQLException This should never occur.
     */
    public void setCatalog(String catalog) throws SQLException {
        // do nothing, silently ignore the request
    }

    /**
     * @param A boolean value specifying whether the connection is readonly.
     * @throws throws SQLException.
     */
    public void setReadOnly(boolean readOnly) throws SQLException {
        if (this.readOnly == readOnly) {
            return;
        }
        // During transaction do not allow to change this flag
        if (!autoCommitFlag || this.transactionXid != null) {
            throw new TeiidSQLException(JDBCPlugin.Util.getString("MMStatement.Invalid_During_Transaction", "setReadOnly(" + readOnly + ")"));//$NON-NLS-1$ //$NON-NLS-2$//$NON-NLS-3$
        }
        this.readOnly = readOnly;
    }

    /**
     * <p> This utility method checks if the jdbc connection is closed and
     * throws an exception if it is closed. </p>
     * @throws SQLException if the connection object is closed.
     */
    void checkConnection() throws SQLException{
        //Check to see the connection is closed and proceed if it is not
       if (closed) {
            throw new TeiidSQLException(JDBCPlugin.Util.getString("MMConnection.Cant_use_closed_connection")); //$NON-NLS-1$
        }
     }

    protected void commitTransaction(XidImpl arg0, boolean arg1) throws SQLException {
        checkConnection();
        transactionXid = null;
        this.autoCommitFlag = true;
        try {
        	ResultsFuture<?> future = this.dqp.commit(arg0, arg1);
        	future.get();
		} catch (Exception e) {
			throw TeiidSQLException.create(e);
		}
    }

    protected void endTransaction(XidImpl arg0, int arg1) throws SQLException {
        checkConnection();
        this.autoCommitFlag = true;
        try {
        	ResultsFuture<?> future = this.dqp.end(arg0, arg1);
        	future.get();
		} catch (Exception e) {
			throw TeiidSQLException.create(e);
		}
    }

    protected void forgetTransaction(XidImpl arg0) throws SQLException {
        checkConnection();
        try {
        	ResultsFuture<?> future = this.dqp.forget(arg0);
        	future.get();
		} catch (Exception e) {
			throw TeiidSQLException.create(e);
		}
    }

    protected int prepareTransaction(XidImpl arg0) throws SQLException  {
        checkConnection();
        transactionXid = null;
        try {
        	ResultsFuture<Integer> future = this.dqp.prepare(arg0);
        	return future.get();
		} catch (Exception e) {
			throw TeiidSQLException.create(e);
		}
    }

    protected Xid[] recoverTransaction(int arg0) throws SQLException  {
        checkConnection();
        try {
			ResultsFuture<Xid[]> future = this.dqp.recover(arg0);
			return future.get();
		} catch (Exception e) {
			throw TeiidSQLException.create(e);
		}
    }

    protected void rollbackTransaction(XidImpl arg0) throws SQLException {
        checkConnection();
        transactionXid = null;
        this.autoCommitFlag = true;
        try {
        	ResultsFuture<?> future = this.dqp.rollback(arg0);
        	future.get();
		} catch (Exception e) {
			throw TeiidSQLException.create(e);
		}
    }

    protected void startTransaction(XidImpl arg0, int arg1, int timeout) throws SQLException {
        checkConnection();
        try {
        	ResultsFuture<?> future = this.dqp.start(arg0, arg1, timeout);
        	future.get();
		} catch (Exception e) {
			throw TeiidSQLException.create(e);
		}
        transactionXid = arg0;
        this.autoCommitFlag = false;
    }

    protected XidImpl getTransactionXid() {
        return transactionXid;
    }
    
	public boolean isValid(int timeout) throws SQLException {
		Statement statement = null;
		try {
			statement = createStatement();
			statement.setQueryTimeout(timeout);
			statement.execute("select 1"); //$NON-NLS-1$
			return true;
		} catch (SQLException e) {
			return false;
		} finally {
			if (statement != null) {
				try {
					statement.close();
				} catch (SQLException e) {
				}
			}
		}
	}
	
	public void recycleConnection() {
        try {
        	//close all open statements
        	this.closeStatements();
        } catch (SQLException e) {
            logger.log(Level.WARNING, JDBCPlugin.Util.getString("MMXAConnection.rolling_back_error"), e); //$NON-NLS-1$
        }
        try {
            //rollback if still in a transaction
            if (!this.getAutoCommit()) {
                logger.warning(JDBCPlugin.Util.getString("MMXAConnection.rolling_back")); //$NON-NLS-1$
                
                if (this.getTransactionXid() == null) {
                    this.rollback(false);
                } else {
                	this.rollbackTransaction(getTransactionXid());
                }
            }
        } catch (SQLException e) {
        	logger.log(Level.WARNING, JDBCPlugin.Util.getString("MMXAConnection.rolling_back_error"), e); //$NON-NLS-1$
        }
        
		//perform load balancing
		if (this.serverConn instanceof SocketServerConnection) {
			((SocketServerConnection)this.serverConn).selectNewServerInstance(this.getDQP());
		}
	}
	
	public boolean isSameProcess(ConnectionImpl conn) throws CommunicationException {
		return this.serverConn.isSameInstance(conn.serverConn);
	}
	
	//## JDBC4.0-begin ##
	public void setClientInfo(Properties properties)
		throws SQLClientInfoException {
	}

	public void setClientInfo(String name, String value)
		throws SQLClientInfoException {
	}
	//## JDBC4.0-end ##
	
	public Properties getClientInfo() throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

	public String getClientInfo(String name) throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

	public Array createArrayOf(String typeName, Object[] elements)
			throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

	public Blob createBlob() throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

	public Clob createClob() throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

	//## JDBC4.0-begin ##
	public NClob createNClob() throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}
	//## JDBC4.0-end ##

	public SQLXML createSQLXML() throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

	public Statement createStatement(int resultSetType,
			int resultSetConcurrency, int resultSetHoldability)
			throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();

	}

	public Struct createStruct(String typeName, Object[] attributes)
			throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

	public CallableStatement prepareCall(String sql, int resultSetType,
			int resultSetConcurrency, int resultSetHoldability)
			throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();

	}

	public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
			throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();

	}

	public PreparedStatement prepareStatement(String sql, int[] columnIndexes)
			throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();

	}

	public PreparedStatement prepareStatement(String sql, String[] columnNames)
			throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();

	}

	public void releaseSavepoint(Savepoint savepoint) throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();

	}

	public void rollback(Savepoint savepoint) throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();

	}

	public void setHoldability(int holdability) throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

	public Savepoint setSavepoint() throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

	public Savepoint setSavepoint(String name) throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

	public void setTransactionIsolation(int level) throws SQLException {
		this.transactionIsolation = level;
	}

	public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
		throw SqlUtil.createFeatureNotSupportedException();
	}

}