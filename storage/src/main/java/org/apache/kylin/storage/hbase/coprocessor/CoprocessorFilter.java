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

package org.apache.kylin.storage.hbase.coprocessor;

import java.util.Collection;
import java.util.Set;

import org.apache.hadoop.hbase.util.Bytes;
import org.apache.kylin.common.util.BytesUtil;
import org.apache.kylin.cube.kv.RowKeyColumnIO;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.dict.ISegment;
import org.apache.kylin.metadata.filter.ColumnTupleFilter;
import org.apache.kylin.metadata.filter.CompareTupleFilter;
import org.apache.kylin.metadata.filter.ConstantTupleFilter;
import org.apache.kylin.metadata.filter.IEvaluatableTuple;
import org.apache.kylin.metadata.filter.TupleFilter;
import org.apache.kylin.metadata.filter.TupleFilter.FilterOperatorEnum;
import org.apache.kylin.metadata.filter.TupleFilterSerializer;
import org.apache.kylin.metadata.filter.TupleFilterSerializer.Decorator;
import org.apache.kylin.metadata.model.TblColRef;

import com.google.common.collect.Sets;

/**
 * @author yangli9
 */
public class CoprocessorFilter {

    private static class FilterDecorator implements Decorator {

        private RowKeyColumnIO columnIO;
        private Set<TblColRef> unstrictlyFilteredColumns;

        public FilterDecorator(ISegment seg) {
            this.columnIO = new RowKeyColumnIO(seg);
            this.unstrictlyFilteredColumns = Sets.newHashSet();
        }

        public Set<TblColRef> getUnstrictlyFilteredColumns() {
            return unstrictlyFilteredColumns;
        }

        @Override
        @SuppressWarnings("unchecked")
        public TupleFilter onSerialize(TupleFilter filter) {
            if (filter == null)
                return null;

            // In case of NOT(unEvaluatableFilter), we should immediately replace it as TRUE,
            // Otherwise, unEvaluatableFilter will later be replace with TRUE and NOT(unEvaluatableFilter) will
            // always return FALSE
            if (filter.getOperator() == FilterOperatorEnum.NOT && !TupleFilter.isEvaluableRecursively(filter)) {
                TupleFilter.collectColumns(filter, unstrictlyFilteredColumns);
                return ConstantTupleFilter.TRUE;
            }

            if (!(filter instanceof CompareTupleFilter))
                return filter;

            if (!TupleFilter.isEvaluableRecursively(filter)) {
                TupleFilter.collectColumns(filter, unstrictlyFilteredColumns);
                return ConstantTupleFilter.TRUE;
            }

            // extract ColumnFilter & ConstantFilter
            CompareTupleFilter compf = (CompareTupleFilter) filter;
            TblColRef col = compf.getColumn();

            if (col == null) {
                return filter;
            }

            Collection<String> constValues = (Collection<String>) compf.getValues();
            if (constValues == null || constValues.isEmpty()) {
                return filter;
            }

            TupleFilter result;
            CompareTupleFilter newComp = new CompareTupleFilter(compf.getOperator());
            newComp.addChild(new ColumnTupleFilter(col));
            String v;

            // translate constant into rowkey ID
            String firstValue = constValues.iterator().next();
            switch (newComp.getOperator()) {
            case EQ:
            case IN:
                Set<String> newValues = Sets.newHashSet();
                for (String value : constValues) {
                    v = translate(col, value, 0);
                    if (!isDictNull(v))
                        newValues.add(v);
                }
                if (newValues.isEmpty()) {
                    result = ConstantTupleFilter.FALSE;
                } else {
                    newComp.addChild(new ConstantTupleFilter(newValues));
                    result = newComp;
                }
                break;
            case NEQ:
                v = translate(col, firstValue, 0);
                if (isDictNull(v)) {
                    result = ConstantTupleFilter.TRUE;
                } else {
                    newComp.addChild(new ConstantTupleFilter(v));
                    result = newComp;
                }
                break;
            case LT:
                v = translate(col, firstValue, 1);
                if (isDictNull(v)) {
                    result = ConstantTupleFilter.TRUE;
                } else {
                    newComp.addChild(new ConstantTupleFilter(v));
                    result = newComp;
                }
                break;
            case LTE:
                v = translate(col, firstValue, -1);
                if (isDictNull(v)) {
                    result = ConstantTupleFilter.FALSE;
                } else {
                    newComp.addChild(new ConstantTupleFilter(v));
                    result = newComp;
                }
                break;
            case GT:
                v = translate(col, firstValue, -1);
                if (isDictNull(v)) {
                    result = ConstantTupleFilter.TRUE;
                } else {
                    newComp.addChild(new ConstantTupleFilter(v));
                    result = newComp;
                }
                break;
            case GTE:
                v = translate(col, firstValue, 1);
                if (isDictNull(v)) {
                    result = ConstantTupleFilter.FALSE;
                } else {
                    newComp.addChild(new ConstantTupleFilter(v));
                    result = newComp;
                }
                break;
            default:
                throw new IllegalStateException("Cannot handle operator " + newComp.getOperator());
            }
            return result;
        }

        private boolean isDictNull(String v) {
            return DictCodeSystem.INSTANCE.isNull(v);
        }

        private String translate(TblColRef column, String v, int roundingFlag) {
            byte[] value = Bytes.toBytes(v);
            byte[] id = new byte[columnIO.getColumnLength(column)];
            columnIO.writeColumn(column, value, value.length, roundingFlag, Dictionary.NULL, id, 0);
            return Dictionary.dictIdToString(id, 0, id.length);
        }
    }

    public static CoprocessorFilter fromFilter(final ISegment seg, TupleFilter rootFilter) {
        // translate constants into dictionary IDs via a serialize copy
        FilterDecorator filterDecorator = new FilterDecorator(seg);
        byte[] bytes = TupleFilterSerializer.serialize(rootFilter, filterDecorator, DictCodeSystem.INSTANCE);
        TupleFilter copy = TupleFilterSerializer.deserialize(bytes, DictCodeSystem.INSTANCE);
        return new CoprocessorFilter(copy, filterDecorator.getUnstrictlyFilteredColumns());
    }

    public static byte[] serialize(CoprocessorFilter o) {
        return (o.filter == null) ? BytesUtil.EMPTY_BYTE_ARRAY : TupleFilterSerializer.serialize(o.filter, DictCodeSystem.INSTANCE);
    }

    public static CoprocessorFilter deserialize(byte[] filterBytes) {
        TupleFilter filter = (filterBytes == null || filterBytes.length == 0) ? null //
                : TupleFilterSerializer.deserialize(filterBytes, DictCodeSystem.INSTANCE);
        return new CoprocessorFilter(filter, null);
    }

    // ============================================================================

    private final TupleFilter filter;
    private final Set<TblColRef> unstrictlyFilteredColumns;

    public CoprocessorFilter(TupleFilter filter, Set<TblColRef> unstrictlyFilteredColumns) {
        this.filter = filter;
        this.unstrictlyFilteredColumns = unstrictlyFilteredColumns;
    }

    public TupleFilter getFilter() {
        return filter;
    }

    public Set<TblColRef> getUnstrictlyFilteredColumns() {
        return unstrictlyFilteredColumns;
    }

    public boolean evaluate(IEvaluatableTuple tuple) {
        if (filter == null)
            return true;
        else
            return filter.evaluate(tuple, DictCodeSystem.INSTANCE);
    }

}
