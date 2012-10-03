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
package org.teiid.translator.object;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.teiid.language.Select;
import org.teiid.translator.ExecutionContext;
import org.teiid.translator.object.util.TradesCacheSource;
import org.teiid.translator.object.util.VDBUtility;

@SuppressWarnings("nls")
public class TestObjectExecution {
	
	
	private static TradesCacheSource source  = TradesCacheSource.loadCache();
	private static ObjectExecutionFactory factory;
	
	@Mock
	private ExecutionContext context;
	@Mock 
	private ObjectConnection connection;

	@Before public void beforeEach() throws Exception{	
 
		MockitoAnnotations.initMocks(this);
		
	    when(connection.performSearch(any(Select.class))).thenReturn(source.getAll());


		factory = new ObjectExecutionFactory() {		};
		factory.setRootClassName(TradesCacheSource.TRADE_CLASS_NAME);
		
		factory.start();

    }
	

	@Test public void testQueryRootObject() throws Exception {
		execute( createExecution("select * From Trade_Object.Trade"), 3, 4);
	}
	
	@Test	public void testAtomicSelects() throws Exception {

		Thread[] threads = new Thread[20];
		for (int i = 0; i < threads.length; i++) {
		    threads[i] = new Thread() {
				public void run() {
					for (int i=0; i<1000; i++) {
						test();
					}
				}
				public void test() {
					ObjectExecution exec = null;
					try {
						exec =  createExecution("select * From Trade_Object.Trade");
						execute(exec, 3, 1);
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						if (exec != null) exec.close();
					}
				}
		    };
  	
		    threads[i].start();
		}
		for (int i = 0; i < threads.length; i++) {
		    try {
		       threads[i].join();
		    } catch (InterruptedException ignore) {}
		}
	}
	
	private ObjectExecution createExecution(String sql) throws Exception {
		Select command = (Select)VDBUtility.TRANSLATION_UTILITY.parseCommand(sql); //$NON-NLS-1$
				
		ObjectExecution exec = (ObjectExecution) factory.createExecution(command, context, VDBUtility.RUNTIME_METADATA, connection);
		
		return exec;

	}
	
	private void execute(ObjectExecution exec, int expected, int columns) throws Exception {
		
		exec.execute();
		
		int cnt = 0;
		List<?> row = exec.next();
		
		// check the number of columns
		assertEquals("Number of columns is incorrect", columns, row.size());

		
		while (row != null) {
			++cnt;
			row = exec.next();
		}
		assertEquals("Did not get expected number of rows", expected, cnt); //$NON-NLS-1$
		
		     
		exec.close();
	}
	
}