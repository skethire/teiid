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

package org.teiid.metadata.index;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.teiid.connector.metadata.runtime.AbstractMetadataRecord;
import org.teiid.connector.metadata.runtime.ColumnRecordImpl;
import org.teiid.connector.metadata.runtime.ColumnSetRecordImpl;
import org.teiid.connector.metadata.runtime.DatatypeRecordImpl;
import org.teiid.connector.metadata.runtime.ForeignKeyRecordImpl;
import org.teiid.connector.metadata.runtime.KeyRecord;
import org.teiid.connector.metadata.runtime.MetadataStore;
import org.teiid.connector.metadata.runtime.ModelRecordImpl;
import org.teiid.connector.metadata.runtime.ProcedureParameterRecordImpl;
import org.teiid.connector.metadata.runtime.ProcedureRecordImpl;
import org.teiid.connector.metadata.runtime.TableRecordImpl;
import org.teiid.core.index.IEntryResult;
import org.teiid.internal.core.index.Index;
import org.teiid.metadata.TransformationMetadata;

import com.metamatrix.api.exception.query.QueryMetadataException;
import com.metamatrix.core.MetaMatrixCoreException;
import com.metamatrix.core.MetaMatrixRuntimeException;
import com.metamatrix.core.id.UUID;
import com.metamatrix.core.util.ArgCheck;
import com.metamatrix.core.util.StringUtil;
import com.metamatrix.metadata.runtime.api.MetadataSource;

/**
 * Loads MetadataRecords from index files.  
 */
public class IndexMetadataFactory {

	private Index[] indexes;
    private Map<String, DatatypeRecordImpl> datatypeCache;
    private Map<String, KeyRecord> primaryKeyCache = new HashMap<String, KeyRecord>();
    private MetadataStore store = new MetadataStore();
    
    public IndexMetadataFactory(MetadataSource source) throws IOException {
    	ArrayList<Index> tmp = new ArrayList<Index>();
		for (String fileName : source.getEntries()) {
			if (SimpleIndexUtil.isIndexFile(fileName)) {
				File f = source.getFile(fileName);
	            tmp.add( new Index(f.getAbsolutePath(), true) );
	        } 
		}
		this.indexes = tmp.toArray(new Index[tmp.size()]);
		getDatatypeCache();
		getModels();
		getTables();
		getProcedures();
    }

    public MetadataStore getMetadataStore() {
		return store;
	}
    
    public void getModels() {
    	Collection<ModelRecordImpl> records = findMetadataRecords(MetadataConstants.RECORD_TYPE.MODEL, null, false);
    	for (ModelRecordImpl modelRecord : records) {
			store.addModel(modelRecord);
		}
    }
    
    public void getTables() {
		Collection<TableRecordImpl> records = findMetadataRecords(MetadataConstants.RECORD_TYPE.TABLE, null, false);
		for (TableRecordImpl tableRecord : records) {
	    	List<ColumnRecordImpl> columns = new ArrayList<ColumnRecordImpl>(findChildRecords(tableRecord, MetadataConstants.RECORD_TYPE.COLUMN));
	        for (ColumnRecordImpl columnRecordImpl : columns) {
	    		columnRecordImpl.setDatatype(getDatatypeCache().get(columnRecordImpl.getDatatypeUUID()));
			}
	        Collections.sort(columns);
	        tableRecord.setColumns(columns);
	        tableRecord.setAccessPatterns(findChildRecords(tableRecord, MetadataConstants.RECORD_TYPE.ACCESS_PATTERN));
	        Map<String, ColumnRecordImpl> uuidColumnMap = new HashMap<String, ColumnRecordImpl>();
	        for (ColumnRecordImpl columnRecordImpl : columns) {
				uuidColumnMap.put(columnRecordImpl.getUUID(), columnRecordImpl);
			}
	        for (KeyRecord columnSetRecordImpl : tableRecord.getAccessPatterns()) {
				loadColumnSetRecords(columnSetRecordImpl, uuidColumnMap);
				columnSetRecordImpl.setTable(tableRecord);
			}
	        tableRecord.setForiegnKeys(findChildRecords(tableRecord, MetadataConstants.RECORD_TYPE.FOREIGN_KEY));
	        for (ForeignKeyRecordImpl foreignKeyRecord : tableRecord.getForeignKeys()) {
	        	foreignKeyRecord.setPrimaryKey(getPrimaryKey(foreignKeyRecord.getUniqueKeyID()));
	        	loadColumnSetRecords(foreignKeyRecord, uuidColumnMap);
	        	foreignKeyRecord.setTable(tableRecord);
			}
	        tableRecord.setUniqueKeys(findChildRecords(tableRecord, MetadataConstants.RECORD_TYPE.UNIQUE_KEY));
	        for (KeyRecord columnSetRecordImpl : tableRecord.getUniqueKeys()) {
				loadColumnSetRecords(columnSetRecordImpl, uuidColumnMap);
				columnSetRecordImpl.setTable(tableRecord);
			}
	        tableRecord.setIndexes(findChildRecords(tableRecord, MetadataConstants.RECORD_TYPE.INDEX));
	        for (KeyRecord columnSetRecordImpl : tableRecord.getIndexes()) {
				loadColumnSetRecords(columnSetRecordImpl, uuidColumnMap);
				columnSetRecordImpl.setTable(tableRecord);
			}
	        if (tableRecord.getPrimaryKey() != null) {
	        	KeyRecord primaryKey = getPrimaryKey(tableRecord.getPrimaryKey().getUUID());
	        	loadColumnSetRecords(primaryKey, uuidColumnMap);
	        	primaryKey.setTable(tableRecord);
	        	tableRecord.setPrimaryKey(primaryKey);
	        }
	        String groupName = tableRecord.getFullName();
	        if (tableRecord.isVirtual()) {
	        	TransformationRecordImpl update = (TransformationRecordImpl)getRecordByType(groupName, MetadataConstants.RECORD_TYPE.UPDATE_TRANSFORM,false);
		        if (update != null) {
		        	tableRecord.setUpdatePlan(update.getTransformation());
		        }
		        TransformationRecordImpl insert = (TransformationRecordImpl)getRecordByType(groupName, MetadataConstants.RECORD_TYPE.INSERT_TRANSFORM,false);
		        if (insert != null) {
		        	tableRecord.setInsertPlan(insert.getTransformation());
		        }
		        TransformationRecordImpl delete = (TransformationRecordImpl)getRecordByType(groupName, MetadataConstants.RECORD_TYPE.DELETE_TRANSFORM,false);
		        if (delete != null) {
		        	tableRecord.setDeletePlan(delete.getTransformation());
		        }
		        TransformationRecordImpl select = (TransformationRecordImpl)getRecordByType(groupName, MetadataConstants.RECORD_TYPE.SELECT_TRANSFORM,false);
		        // this group may be an xml document            
		        if(select == null) {
			        select = (TransformationRecordImpl)getRecordByType(groupName, MetadataConstants.RECORD_TYPE.MAPPING_TRANSFORM,false);
		        }
		        if (select != null) {
			        tableRecord.setSelectTransformation(select.getTransformation());
			        tableRecord.setBindings(select.getBindings());
			        tableRecord.setSchemaPaths(select.getSchemaPaths());
			        tableRecord.setResourcePath(select.getResourcePath());
		        }
	        }
	        if (tableRecord.isMaterialized()) {
	        	tableRecord.setMaterializedStageTableName(((TableRecordImpl)getRecordByType(tableRecord.getMaterializedStageTableID(), MetadataConstants.RECORD_TYPE.TABLE)).getFullName());
	        	tableRecord.setMaterializedTableName(((TableRecordImpl)getRecordByType(tableRecord.getMaterializedTableID(), MetadataConstants.RECORD_TYPE.TABLE)).getFullName());
	        }
			this.store.addTable(tableRecord);
		}
    }

	private KeyRecord getPrimaryKey(String uuid) {
		KeyRecord pk = this.primaryKeyCache.get(uuid);
		if (pk == null) {
			pk = (KeyRecord)this.getRecordByType(uuid, MetadataConstants.RECORD_TYPE.PRIMARY_KEY);
			this.primaryKeyCache.put(uuid, pk);
		}
		return pk;
	}
	
    public Map<String, DatatypeRecordImpl> getDatatypeCache() {
		if (this.datatypeCache == null) {
			this.datatypeCache = new HashMap<String, DatatypeRecordImpl>();
			Collection<DatatypeRecordImpl> dataTypes = findMetadataRecords(MetadataConstants.RECORD_TYPE.DATATYPE, null, false);
			for (DatatypeRecordImpl datatypeRecordImpl : dataTypes) {
				datatypeCache.put(datatypeRecordImpl.getUUID(), datatypeRecordImpl);
				this.store.addDatatype(datatypeRecordImpl);
			}
		}
		return datatypeCache;
	}
	
	private ColumnRecordImpl findElement(String fullName) {
        ColumnRecordImpl columnRecord = (ColumnRecordImpl)getRecordByType(fullName, MetadataConstants.RECORD_TYPE.COLUMN);
    	columnRecord.setDatatype(getDatatypeCache().get(columnRecord.getDatatypeUUID()));
        return columnRecord;
    }
	    
    private AbstractMetadataRecord getRecordByType(final String entityName, final char recordType) {
    	return getRecordByType(entityName, recordType, true);
    }
    
    private AbstractMetadataRecord getRecordByType(final String entityName, final char recordType, boolean mustExist) {
    	// Query the index files
		final Collection results = findMetadataRecords(recordType,entityName,false);
        
		int resultSize = results.size();
        if(resultSize == 1) {
            // get the columnset record for this result            
            return (AbstractMetadataRecord) results.iterator().next();
        }
        if(resultSize == 0) {
        	if (mustExist) {
			// there should be only one for the UUID
	            throw new MetaMatrixRuntimeException(entityName+TransformationMetadata.NOT_EXISTS_MESSAGE);
        	} 
        	return null;
		} 
        throw new MetaMatrixRuntimeException(RuntimeMetadataPlugin.Util.getString("TransformationMetadata.0", entityName)); //$NON-NLS-1$
    }
    
    public void getProcedures() {
		Collection<ProcedureRecordImpl> procedureRecordImpls = findMetadataRecords(MetadataConstants.RECORD_TYPE.CALLABLE, null, false);
		for (ProcedureRecordImpl procedureRecord : procedureRecordImpls) {
	    	procedureRecord.setParameters(new ArrayList<ProcedureParameterRecordImpl>(procedureRecord.getParameterIDs().size()));
	    	
	        // get the parameter metadata info
	        for (String paramID : procedureRecord.getParameterIDs()) {
	            ProcedureParameterRecordImpl paramRecord = (ProcedureParameterRecordImpl) this.getRecordByType(paramID, MetadataConstants.RECORD_TYPE.CALLABLE_PARAMETER);
	            paramRecord.setDatatype(getDatatypeCache().get(paramRecord.getDatatypeUUID()));
	            procedureRecord.getParameters().add(paramRecord);
	        }
	    	
	        String resultID = procedureRecord.getResultSetID();
	        if(resultID != null) {
	            ColumnSetRecordImpl resultRecord = (ColumnSetRecordImpl) getRecordByType(resultID, MetadataConstants.RECORD_TYPE.RESULT_SET, false);
	            if (resultRecord != null) {
		            loadColumnSetRecords(resultRecord, null);
		            procedureRecord.setResultSet(resultRecord);
	            }
	            //it is ok to be null here.  it will happen when a 
	            //virtual stored procedure is created from a
	            //physical stored procedrue without a result set
	            //TODO: find a better fix for this
	        }

	        // if this is a virtual procedure get the procedure plan
	        if(procedureRecord.isVirtual()) {
	    		TransformationRecordImpl transformRecord = (TransformationRecordImpl)getRecordByType(procedureRecord.getFullName(), MetadataConstants.RECORD_TYPE.PROC_TRANSFORM, false);
	    		if(transformRecord != null) {
	    			procedureRecord.setQueryPlan(transformRecord.getTransformation());
	    		}
	        }
			this.store.addProcedure(procedureRecord);
		}
    }
    
    /**
     * Finds children by parent uuid - note that this is not the best way to query for columns,
     * but it removes the need to store the parent uuid
     * @param parentRecord
     * @param childRecordType
     * @return
     */
    private List findChildRecords(final AbstractMetadataRecord parentRecord, final char childRecordType) {
    	// construct the pattern string
        String patternStr = getUUIDMatchPattern(childRecordType, parentRecord.getUUID(), true);
		// Query the model index files
		IEntryResult[] results = queryIndex(childRecordType, patternStr.toCharArray(), false, true, false);

		return loadRecords(results);        
    }
    
	private void loadColumnSetRecords(ColumnSetRecordImpl indexRecord, Map<String, ColumnRecordImpl> columns) {
		for (int i = 0; i < indexRecord.getColumns().size(); i++) {
			String uuid = indexRecord.getColumns().get(i).getUUID();
			if (columns != null) {
				indexRecord.getColumns().set(i, columns.get(uuid));
			} else {
				indexRecord.getColumns().set(i, findElement(uuid));
			}
		}
	}
    
	private Collection findMetadataRecords(final char recordType,
			final String entityName, final boolean isPartialName) {
		IEntryResult[] results = queryIndex(recordType, entityName, isPartialName);
		Collection<AbstractMetadataRecord> records = loadRecords(results);
		return records;
	}

	private List<AbstractMetadataRecord> loadRecords(
			IEntryResult[] results) {
		List<AbstractMetadataRecord> records = RecordFactory.getMetadataRecord(results);
		
		for (AbstractMetadataRecord metadataRecord : records) {
			String uuid = metadataRecord.getUUID();
			
			String prefixString  = getUUIDMatchPattern(MetadataConstants.RECORD_TYPE.ANNOTATION, uuid, false);
			IEntryResult[] annotations = queryIndex(MetadataConstants.RECORD_TYPE.ANNOTATION, prefixString.toCharArray(), false, true, true);
			if (annotations.length > 0) {
				metadataRecord.setAnnotation(RecordFactory.createAnnotationRecord(annotations[0].getWord()));
			}
			
			prefixString = String.valueOf(MetadataConstants.RECORD_TYPE.PROPERTY) + IndexConstants.RECORD_STRING.RECORD_DELIMITER + uuid.trim() + IndexConstants.RECORD_STRING.RECORD_DELIMITER;
			IEntryResult[] properties = queryIndex(MetadataConstants.RECORD_TYPE.PROPERTY, prefixString.toCharArray(), true, true, true);
			metadataRecord.setProperties(RecordFactory.createPropertyRecord(properties));
		}
		return records;
	}
    
    /**
     * Return the pattern match string that could be used to match a UUID in 
     * an index record. All index records contain a header portion of the form:  
     * recordType|pathInModel|UUID|nameInSource|parentObjectID|
     * @param uuid The UUID for which the pattern match string is to be constructed.
     * @return The pattern match string of the form: recordType|*|uuid|*
     */
    private String getUUIDMatchPattern(final char recordType, String uuid, boolean parent) {
        ArgCheck.isNotNull(uuid);
        // construct the pattern string
        String patternStr = String.valueOf(recordType) + IndexConstants.RECORD_STRING.RECORD_DELIMITER + IndexConstants.RECORD_STRING.MATCH_CHAR + IndexConstants.RECORD_STRING.RECORD_DELIMITER;
        if (parent) {
        	for (int i = 0; i < 3;  i++) {
        		patternStr += String.valueOf(IndexConstants.RECORD_STRING.MATCH_CHAR) + IndexConstants.RECORD_STRING.RECORD_DELIMITER;
        	}
        }
        patternStr += uuid.toLowerCase() + IndexConstants.RECORD_STRING.RECORD_DELIMITER + IndexConstants.RECORD_STRING.MATCH_CHAR;                    
        return patternStr;        
    }
        
    /** 
     * @see com.metamatrix.modeler.core.index.IndexSelector#getIndexes()
     * @since 4.2
     */
    public synchronized Index[] getIndexes() {
    	return this.indexes;
    }

	/**
	 * Return all index file records that match the specified entity name  
	 * @param indexName
	 * @param entityName the name to match
	 * @param isPartialName true if the entity name is a partially qualified
	 * @return results
	 * @throws QueryMetadataException
	 */
	private IEntryResult[] queryIndex(final char recordType, final String entityName, final boolean isPartialName) {

		IEntryResult[] results = null;

		// Query based on UUID
		if (StringUtil.startsWithIgnoreCase(entityName,UUID.PROTOCOL)) {
            String patternString = null;
            if (recordType == MetadataConstants.RECORD_TYPE.DATATYPE) {
                patternString = getDatatypeUUIDMatchPattern(entityName);
            } else {
                patternString = getUUIDMatchPattern(recordType,entityName, false);
            }
			results = queryIndex(recordType, patternString.toCharArray(), false, true, true);
		}

		// Query based on partially qualified name
		else if (isPartialName) {
			String patternString = getMatchPattern(recordType,entityName);
			results = queryIndex(recordType, patternString.toCharArray(), false, true, false);
		}

		// Query based on fully qualified name
		else {
			String prefixString  = getPrefixPattern(recordType,entityName);
			results = queryIndex(recordType, prefixString.toCharArray(), true, true, true);
		}

		return results;
	}
	
    /**
     * Return the pattern match string that could be used to match a UUID in 
     * a datatype index record. The RECORD_TYPE.DATATYPE records contain a header portion of the form:  
     * recordType|datatypeID|basetypeID|fullName|objectID|nameInSource|...
     * @param uuid The UUID for which the pattern match string is to be constructed.
     * @return The pattern match string of the form: recordType|*|*|*|uuid|*
     */
    private String getDatatypeUUIDMatchPattern(final String uuid) {
        ArgCheck.isNotNull(uuid);
        String uuidString = uuid;
        if (StringUtil.startsWithIgnoreCase(uuid,UUID.PROTOCOL)) {
            uuidString = uuid.toLowerCase();
        }
        // construct the pattern string
        String patternStr = "" //$NON-NLS-1$
                          + MetadataConstants.RECORD_TYPE.DATATYPE            //recordType
                          + IndexConstants.RECORD_STRING.RECORD_DELIMITER
                          + IndexConstants.RECORD_STRING.MATCH_CHAR        //datatypeID 
                          + IndexConstants.RECORD_STRING.RECORD_DELIMITER
                          + IndexConstants.RECORD_STRING.MATCH_CHAR        //basetypeID 
                          + IndexConstants.RECORD_STRING.RECORD_DELIMITER
                          + IndexConstants.RECORD_STRING.MATCH_CHAR        //fullName 
                          + IndexConstants.RECORD_STRING.RECORD_DELIMITER
                          + uuidString                                     //objectID
                          + IndexConstants.RECORD_STRING.RECORD_DELIMITER
                          + IndexConstants.RECORD_STRING.MATCH_CHAR;                    
        return patternStr;        
    }
	
    /**
     * Return the prefix match string that could be used to exactly match a fully 
     * qualified entity name in an index record. All index records 
     * contain a header portion of the form:  
     * recordType|pathInModel|UUID|nameInSource|parentObjectID|
     * @param name The fully qualified name for which the prefix match 
     * string is to be constructed.
     * @return The pattern match string of the form: recordType|name|
     */
    private String getPrefixPattern(final char recordType, final String name) {

        // construct the pattern string
        String patternStr = "" //$NON-NLS-1$
                          + recordType
                          + IndexConstants.RECORD_STRING.RECORD_DELIMITER;
        if(name != null) {                          
            patternStr = patternStr + name.trim().toUpperCase() + IndexConstants.RECORD_STRING.RECORD_DELIMITER;
        }                    

        return patternStr;
    }
	
    /**
     * Return the pattern match string that could be used to match a 
     * partially/fully qualified entity name in an index record. All index records 
     * contain a header portion of the form:  
     * recordType|pathInModel|UUID|nameInSource|parentObjectID|
     * @param name The partially/fully qualified name for which
     * the pattern match string is to be constructed.
     * @return The pattern match string of the form: recordType|*name|* 
     */
    private String getMatchPattern(final char recordType, final String name) {
        ArgCheck.isNotNull(name);

        // construct the pattern string
        String patternStr = "" //$NON-NLS-1$
                          + recordType
                          + IndexConstants.RECORD_STRING.RECORD_DELIMITER
                          + IndexConstants.RECORD_STRING.MATCH_CHAR;
        if(name != null) {
            patternStr =  patternStr + name.trim().toUpperCase()
                          + IndexConstants.RECORD_STRING.RECORD_DELIMITER
                          + IndexConstants.RECORD_STRING.MATCH_CHAR;
        }                    
        return patternStr;        
    }

    /**
     * Return all index file records that match the specified record pattern.
     * @param indexes the array of MtkIndex instances to query
     * @param pattern
     * @return results
     * @throws QueryMetadataException
     */
    private IEntryResult[] queryIndex(char recordType, final char[] pattern, boolean isPrefix, boolean isCaseSensitive, boolean returnFirstMatch) {
    	// The the index file name for the record type
        final String indexName = SimpleIndexUtil.getIndexFileNameForRecordType(recordType);
        Index[] search = SimpleIndexUtil.getIndexes(indexName, this.getIndexes());            

    	try {
            return SimpleIndexUtil.queryIndex(null, search, pattern, isPrefix, isCaseSensitive, returnFirstMatch);
        } catch (MetaMatrixCoreException e) {
            throw new MetaMatrixRuntimeException(e);
        }
    }
    
}