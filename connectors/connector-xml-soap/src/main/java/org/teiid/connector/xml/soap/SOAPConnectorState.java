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



package org.teiid.connector.xml.soap;

import org.teiid.connector.api.Connection;
import org.teiid.connector.api.ConnectorException;
import org.teiid.connector.api.ConnectorLogger;
import org.teiid.connector.xml.StatefulConnector;
import org.teiid.connector.xml.http.HTTPConnectorState;
import org.teiid.connector.xml.http.HTTPManagedConnectionFactory;

/**
 * This class copies the the name of the interface because it is remaining
 *  backwardly compatable with old bindings. 
 */
public class SOAPConnectorState extends HTTPConnectorState implements
		org.teiid.connector.xml.SOAPConnectorState {

	org.teiid.connector.xml.SOAPConnectorState soapState;
	SOAPManagedConnectionFactory config;
	/**
	 * 
	 */
	public SOAPConnectorState() {
		super();
		soapState = new SOAPConnectorStateImpl();
	}

	
	@Override
	public void setState(HTTPManagedConnectionFactory env) throws ConnectorException {
		super.setState(env);
		soapState.setState(env);
		this.config = (SOAPManagedConnectionFactory)env;
	}

	@Override
	public void setLogger(ConnectorLogger logger) {
		super.setLogger(logger);
		soapState.setLogger(logger);
	}

	/* (non-Javadoc)
	 * @see com.metamatrix.connector.xml.SOAPConnectorState#isEncoded()
	 */
	public boolean isEncoded() {
		return soapState.isEncoded();
	}

	/* (non-Javadoc)
	 * @see com.metamatrix.connector.xml.SOAPConnectorState#isRPC()
	 */
	public boolean isRPC() {
		return soapState.isRPC();
	}

	/* (non-Javadoc)
	 * @see com.metamatrix.connector.xml.SOAPConnectorState#isExceptionOnFault()
	 */
	public boolean isExceptionOnFault() {
		return soapState.isExceptionOnFault();
	}
	
    public Connection getConnection(StatefulConnector connector)
            throws ConnectorException {
        return new SOAPConnectionImpl(connector, this.config);
    }
}