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

package org.teiid.query.tempdata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.teiid.api.exception.query.ExpressionEvaluationException;
import org.teiid.common.buffer.BlockedException;
import org.teiid.common.buffer.BufferManager;
import org.teiid.common.buffer.STree;
import org.teiid.common.buffer.TupleBrowser;
import org.teiid.common.buffer.TupleBuffer;
import org.teiid.common.buffer.TupleSource;
import org.teiid.common.buffer.BufferManager.TupleSourceType;
import org.teiid.core.TeiidComponentException;
import org.teiid.core.TeiidException;
import org.teiid.core.TeiidProcessingException;
import org.teiid.core.types.DataTypeManager;
import org.teiid.query.QueryPlugin;
import org.teiid.query.eval.Evaluator;
import org.teiid.query.metadata.TempMetadataID;
import org.teiid.query.processor.CollectionTupleSource;
import org.teiid.query.processor.relational.RelationalNode;
import org.teiid.query.processor.relational.SortUtility;
import org.teiid.query.processor.relational.SortUtility.Mode;
import org.teiid.query.sql.lang.Criteria;
import org.teiid.query.sql.lang.OrderBy;
import org.teiid.query.sql.lang.OrderByItem;
import org.teiid.query.sql.lang.SetClauseList;
import org.teiid.query.sql.symbol.Constant;
import org.teiid.query.sql.symbol.ElementSymbol;
import org.teiid.query.sql.symbol.Expression;
import org.teiid.query.sql.symbol.SingleElementSymbol;

/**
 * A Teiid Temp Table
 * TODO: in this implementation blocked exceptions will not happen
 *       allowing for subquery evaluation though would cause pauses
 */
class TempTable {
	
	private final class QueryTupleSource extends TupleBrowserTupleSource {
		private final Evaluator eval;
		private final List<SingleElementSymbol> projectedCols;
		private final Criteria condition;
		private final boolean project;
		private final int[] indexes;

		private QueryTupleSource(TupleBrowser browser, Map map,
				List<SingleElementSymbol> projectedCols, Criteria condition) {
			super(browser);
			this.indexes = RelationalNode.getProjectionIndexes(map, projectedCols);
			this.eval = new Evaluator(map, null, null);
			this.projectedCols = projectedCols;
			this.condition = condition;
			this.project = shouldProject();
		}

		@Override
		public List<?> nextTuple() throws TeiidComponentException,
				TeiidProcessingException {
			for (;;) {
				List<?> next = super.nextTuple();
				if (next == null) {
					return null;
				}
				if (rowId != null) {
					next = next.subList(1, next.size());
				}
				if (condition != null && !eval.evaluate(condition, next)) {
					continue;
				}
				if (project) {
					next = RelationalNode.projectTuple(indexes, next);
				}
				return next;
			}
		}
		
		private boolean shouldProject() {
			if (indexes.length == getColumns().size()) {
				for (int i = 0; i < indexes.length; i++) {
					if (indexes[i] != i) {
						return true;
					}
				}
				return false;
			} 
			return true;
		}

		@Override
		public List<? extends Expression> getSchema() {
			return projectedCols;
		}
	}

	private class TupleBrowserTupleSource implements TupleSource {
		private final TupleBrowser browser;

		private TupleBrowserTupleSource(TupleBrowser browser) {
			this.browser = browser;
		}

		@Override
		public List<?> nextTuple() throws TeiidComponentException,
				TeiidProcessingException {
			return browser.next();
		}

		@Override
		public List<? extends Expression> getSchema() {
			return columns;
		}

		@Override
		public void closeSource() {
			
		}

		@Override
		public int available() {
			return 0;
		}
	}

	private abstract class UpdateProcessor {
		private TupleSource ts;
		protected final Map lookup;
		protected final Evaluator eval;
		private final Criteria crit;
		protected int updateCount = 0;
		private List currentTuple;
		
		protected TupleBuffer undoLog;

		UpdateProcessor(Criteria crit, TupleSource ts) throws TeiidComponentException {
			this.ts = ts;
			this.lookup = RelationalNode.createLookupMap(columns);
			this.eval = new Evaluator(lookup, null, null);
			this.crit = crit;
			this.undoLog = bm.createTupleBuffer(columns, sessionID, TupleSourceType.PROCESSOR);
		}
		
		int process() throws ExpressionEvaluationException, TeiidComponentException, TeiidProcessingException {
			boolean success = false;
			try {
				while (currentTuple != null || (currentTuple = ts.nextTuple()) != null) {
					if (crit == null || eval.evaluate(crit, currentTuple)) {
						tuplePassed(currentTuple);
						updateCount++;
						undoLog.addTuple(currentTuple);
					}
					currentTuple = null;
				}
				success();
				success = true;
			} finally {
				if (!success) {
					TupleSource undoTs = undoLog.createIndexedTupleSource();
					List<?> tuple = null;
					try {
						while ((tuple = undoTs.nextTuple()) != null) {
							undo(tuple);
						}
					} catch (TeiidException e) {
						
					}
				}
				close();
			}
			return updateCount;
		}
		
		@SuppressWarnings("unused")
		void success() throws TeiidComponentException, ExpressionEvaluationException, TeiidProcessingException {}

		protected abstract void tuplePassed(List tuple) throws BlockedException, TeiidComponentException, TeiidProcessingException;
		
		protected abstract void undo(List tuple) throws TeiidComponentException, TeiidProcessingException;
		
		public void close() {
			ts.closeSource();
			undoLog.remove();
		}
		
	}
	
	private STree tree;
	private AtomicInteger rowId;
	private List<ElementSymbol> columns;
	private BufferManager bm;
	private String sessionID;
	private TempMetadataID tid;

	TempTable(TempMetadataID tid, BufferManager bm, List<ElementSymbol> columns, int primaryKeyLength, String sessionID) {
		this.tid = tid;
		this.bm = bm;
		if (primaryKeyLength == 0) {
            ElementSymbol id = new ElementSymbol("rowId"); //$NON-NLS-1$
    		id.setType(DataTypeManager.DefaultDataClasses.INTEGER);
    		columns.add(0, id);
    		rowId = new AtomicInteger();
        	tree = bm.createSTree(columns, sessionID, TupleSourceType.PROCESSOR, 1);
        } else {
        	tree = bm.createSTree(columns, sessionID, TupleSourceType.PROCESSOR, primaryKeyLength);
        }
		this.columns = columns;
		this.sessionID = sessionID;
	}

	public TupleSource createTupleSource(final List<SingleElementSymbol> projectedCols, final Criteria condition, OrderBy orderBy) throws TeiidComponentException, TeiidProcessingException {
		Map map = RelationalNode.createLookupMap(getColumns());
		
		Boolean direction = null;
		boolean orderByUsingIndex = false;
		if (orderBy != null && rowId == null) {
			int[] orderByIndexes = RelationalNode.getProjectionIndexes(map, orderBy.getSortKeys());
			if (orderByIndexes.length < tree.getKeyLength()) {
				orderByUsingIndex = false;
			} else {
				orderByUsingIndex = true;
				for (int i = 0; i < tree.getKeyLength(); i++) {
					if (orderByIndexes[i] != i) {
						orderByUsingIndex = false;
						break;
					}
				}
				if (orderByUsingIndex) {
					for (int i = 0; i < tree.getKeyLength(); i++) {
						OrderByItem item = orderBy.getOrderByItems().get(i);
						if (item.getNullOrdering() != null) {
							orderByUsingIndex = false;
							break;
						}
						if (item.isAscending()) {
							if (direction == null) {
								direction = OrderBy.ASC;
							} else if (direction != OrderBy.ASC) {
								orderByUsingIndex = false;
								break;
							}
						} else if (direction == null) {
							direction = OrderBy.DESC;
						} else if (direction != OrderBy.DESC) {
							orderByUsingIndex = false;
							break;
						}
					}
				}
			}
		}
		if (!orderByUsingIndex) {
			direction = OrderBy.ASC;
		}
		
		TupleBrowser browser = createTupleBrower(condition, direction);
		TupleSource ts = new QueryTupleSource(browser, map, projectedCols, condition);

		TupleBuffer tb = null;
		if (!orderByUsingIndex && orderBy != null) {
			SortUtility sort = new SortUtility(ts, orderBy.getOrderByItems(), Mode.SORT, bm, sessionID);
			tb = sort.sort();
		} else {
			tb = bm.createTupleBuffer(projectedCols, sessionID, TupleSourceType.PROCESSOR);
			List next = null;
			while ((next = ts.nextTuple()) != null) {
				tb.addTuple(next);
			}
		}
		tb.close();
		tb.setForwardOnly(true);
		return tb.createIndexedTupleSource(true);
	}

	private TupleBrowser createTupleBrower(Criteria condition, boolean direction) throws TeiidComponentException {
		List<Object> lower = null;
		List<Object> upper = null;
		List<List<Object>> values = null;
		if (condition != null && rowId == null) {
			IndexCondition[] indexConditions = IndexCondition.getIndexConditions(condition, columns.subList(0, tree.getKeyLength()));
			if (indexConditions.length > 1 && indexConditions[indexConditions.length - 1] != null) {
				List<Object> value = new ArrayList<Object>(indexConditions.length);
				for (IndexCondition indexCondition : indexConditions) {
					value.add(indexCondition.valueSet.iterator().next().getValue());
				}
				values = new ArrayList<List<Object>>(1);
				values.add(value);
				//TODO: support other composite key lookups
			} else {
				if (indexConditions[0].lower != null) {
					lower = Arrays.asList(indexConditions[0].lower.getValue());
				}
				if (indexConditions[0].upper != null) {
					upper = Arrays.asList(indexConditions[0].upper.getValue());
				}
				if (!indexConditions[0].valueSet.isEmpty()) {
					values = new ArrayList<List<Object>>();
					for (Constant constant : indexConditions[0].valueSet) {
						values.add(Arrays.asList(constant.getValue()));
					}
				}
			}
		}
		if (values != null) {
			return new TupleBrowser(this.tree, values, direction);
		}
		return new TupleBrowser(this.tree, lower, upper, direction);
	}
	
	public int getRowCount() {
		return tree.getRowCount();
	}
	
	public int truncate() {
		return tree.truncate();
	}
	
	public void remove() {
		tree.remove();
	}
	
	public List<ElementSymbol> getColumns() {
		if (rowId != null) {
			return columns.subList(1, columns.size());
		}
		return columns;
	}
	
	public TupleSource insert(List<List<Object>> tuples) throws TeiidComponentException, ExpressionEvaluationException, TeiidProcessingException {
        UpdateProcessor up = new UpdateProcessor(null, new CollectionTupleSource(tuples.iterator(), getColumns())) {
        	
        	protected void tuplePassed(List tuple) 
        	throws BlockedException, TeiidComponentException, TeiidProcessingException {
        		if (rowId != null) {
        			tuple.add(0, rowId.getAndIncrement());
        		}
        		insertTuple(tuple);
        	}
        	
        	@Override
        	protected void undo(List tuple) throws TeiidComponentException {
        		deleteTuple(tuple);
        	}
        	
        };
        int updateCount = up.process();
        tid.setCardinality(tree.getRowCount());
        return CollectionTupleSource.createUpdateCountTupleSource(updateCount);
    }
	
	public TupleSource update(Criteria crit, final SetClauseList update) throws TeiidComponentException, ExpressionEvaluationException, TeiidProcessingException {
		final boolean primaryKeyChangePossible = canChangePrimaryKey(update);
		final TupleBrowser browser = createTupleBrower(crit, OrderBy.ASC);
		UpdateProcessor up = new UpdateProcessor(crit, new TupleBrowserTupleSource(browser)) {
			
			protected TupleBuffer changeSet;
			protected UpdateProcessor changeSetProcessor;
			
			@Override
			protected void tuplePassed(List tuple)
					throws ExpressionEvaluationException,
					BlockedException, TeiidComponentException {
				List<Object> newTuple = new ArrayList<Object>(tuple);
    			for (Map.Entry<ElementSymbol, Expression> entry : update.getClauseMap().entrySet()) {
    				newTuple.set((Integer)lookup.get(entry.getKey()), eval.evaluate(entry.getValue(), tuple));
    			}
    			if (primaryKeyChangePossible) {
    				browser.removed();
    				deleteTuple(tuple);
    				if (changeSet == null) {
    					changeSet = bm.createTupleBuffer(columns, sessionID, TupleSourceType.PROCESSOR);
    				}
    				changeSet.addTuple(newTuple);
    			} else {
    				browser.update(newTuple);
    			}
			}
			
			@Override
			protected void undo(List tuple) throws TeiidComponentException, TeiidProcessingException {
				if (primaryKeyChangePossible) {
					insertTuple(tuple);
				} else {
					updateTuple(tuple);
				}
			}
			
			@Override
			void success() throws TeiidComponentException, ExpressionEvaluationException, TeiidProcessingException {
				//existing tuples have been removed
				//changeSet contains possible updates
				if (primaryKeyChangePossible) {
					if (changeSetProcessor == null) {
						changeSetProcessor = new UpdateProcessor(null, changeSet.createIndexedTupleSource(true)) {
							@Override
							protected void tuplePassed(List tuple) throws BlockedException,
									TeiidComponentException, TeiidProcessingException {
								insertTuple(tuple);
							}
							
							@Override
							protected void undo(List tuple) throws TeiidComponentException,
									TeiidProcessingException {
								deleteTuple(tuple);
							}
							
						};
					}
					changeSetProcessor.process(); //when this returns, we're up to date
				}
			}
			
			@Override
			public void close() {
				super.close();
				if (changeSetProcessor != null) {
					changeSetProcessor.close(); // causes a revert of the change set
				}
				if (changeSet != null) {
					changeSet.remove();
				}
			}
			
		};
		int updateCount = up.process();
		return CollectionTupleSource.createUpdateCountTupleSource(updateCount);
	}

	private boolean canChangePrimaryKey(final SetClauseList update) {
		if (rowId == null) {
			Set<ElementSymbol> affectedColumns = new HashSet<ElementSymbol>(update.getClauseMap().keySet());
			affectedColumns.retainAll(columns.subList(0, tree.getKeyLength()));
			if (!affectedColumns.isEmpty()) {
				return true;
			}
		}
		return false;
	}
	
	public TupleSource delete(Criteria crit) throws TeiidComponentException, ExpressionEvaluationException, TeiidProcessingException {
		final TupleBrowser browser = createTupleBrower(crit, OrderBy.ASC);
		UpdateProcessor up = new UpdateProcessor(crit, new TupleBrowserTupleSource(browser)) {
			@Override
			protected void tuplePassed(List tuple)
					throws ExpressionEvaluationException,
					BlockedException, TeiidComponentException {
				browser.removed();
				deleteTuple(tuple);
			}
			
			@Override
			protected void undo(List tuple) throws TeiidComponentException, TeiidProcessingException {
				insertTuple(tuple);
			}
		};
		int updateCount = up.process();
		tid.setCardinality(tree.getRowCount());
		return CollectionTupleSource.createUpdateCountTupleSource(updateCount);
	}
	
	private void insertTuple(List<Object> list) throws TeiidComponentException, TeiidProcessingException {
		if (tree.insert(list, false) != null) {
			throw new TeiidProcessingException(QueryPlugin.Util.getString("TempTable.duplicate_key")); //$NON-NLS-1$
		}
	}
	
	private void deleteTuple(List<?> tuple) throws TeiidComponentException {
		if (tree.remove(tuple) == null) {
			throw new AssertionError("Delete failed"); //$NON-NLS-1$
		}
	}
	
	private void updateTuple(List<?> tuple) throws TeiidComponentException {
		if (tree.insert(tuple, true) == null) {
			throw new AssertionError("Update failed"); //$NON-NLS-1$
		}
	}
		
}