/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import org.apache.druid.error.InvalidInput;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.math.expr.ExprEval;
import org.apache.druid.math.expr.ExpressionType;
import org.apache.druid.query.cache.CacheKeyBuilder;
import org.apache.druid.query.filter.vector.VectorValueMatcher;
import org.apache.druid.query.filter.vector.VectorValueMatcherColumnProcessorFactory;
import org.apache.druid.segment.BaseDoubleColumnValueSelector;
import org.apache.druid.segment.BaseFloatColumnValueSelector;
import org.apache.druid.segment.BaseLongColumnValueSelector;
import org.apache.druid.segment.BaseObjectColumnValueSelector;
import org.apache.druid.segment.ColumnInspector;
import org.apache.druid.segment.ColumnProcessorFactory;
import org.apache.druid.segment.ColumnProcessors;
import org.apache.druid.segment.ColumnSelector;
import org.apache.druid.segment.ColumnSelectorFactory;
import org.apache.druid.segment.DimensionSelector;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnIndexSupplier;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.TypeSignature;
import org.apache.druid.segment.column.TypeStrategy;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.filter.Filters;
import org.apache.druid.segment.filter.PredicateValueMatcherFactory;
import org.apache.druid.segment.filter.ValueMatchers;
import org.apache.druid.segment.index.AllFalseBitmapColumnIndex;
import org.apache.druid.segment.index.BitmapColumnIndex;
import org.apache.druid.segment.index.semantic.StringValueSetIndexes;
import org.apache.druid.segment.index.semantic.ValueIndexes;
import org.apache.druid.segment.nested.StructuredData;
import org.apache.druid.segment.vector.VectorColumnSelectorFactory;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class EqualityFilter extends AbstractOptimizableDimFilter implements Filter
{
  private final String column;
  private final ColumnType matchValueType;
  private final Object matchValue;
  private final ExprEval<?> matchValueEval;

  @Nullable
  private final FilterTuning filterTuning;
  private final DruidPredicateFactory predicateFactory;

  @JsonCreator
  public EqualityFilter(
      @JsonProperty("column") String column,
      @JsonProperty("matchValueType") ColumnType matchValueType,
      @JsonProperty("matchValue") Object matchValue,
      @JsonProperty("filterTuning") @Nullable FilterTuning filterTuning
  )
  {
    if (column == null) {
      throw InvalidInput.exception("Invalid equality filter, column cannot be null");
    }
    this.column = column;
    if (matchValueType == null) {
      throw InvalidInput.exception("Invalid equality filter on column [%s], matchValueType cannot be null", column);
    }
    this.matchValueType = matchValueType;
    this.matchValue = matchValue;
    this.matchValueEval = ExprEval.ofType(ExpressionType.fromColumnTypeStrict(matchValueType), matchValue);
    if (matchValueEval.value() == null) {
      throw InvalidInput.exception("Invalid equality filter on column [%s], matchValue cannot be null", column);
    }
    this.filterTuning = filterTuning;
    this.predicateFactory = new EqualityPredicateFactory(matchValueEval);
  }

  @Override
  public byte[] getCacheKey()
  {
    final TypeStrategy<Object> typeStrategy = matchValueEval.type().getStrategy();
    final int size = typeStrategy.estimateSizeBytes(matchValueEval.value());
    final ByteBuffer valueBuffer = ByteBuffer.allocate(size);
    typeStrategy.write(valueBuffer, matchValueEval.value(), size);
    return new CacheKeyBuilder(DimFilterUtils.EQUALS_CACHE_ID)
        .appendByte(DimFilterUtils.STRING_SEPARATOR)
        .appendString(column)
        .appendByte(DimFilterUtils.STRING_SEPARATOR)
        .appendString(matchValueType.asTypeString())
        .appendByte(DimFilterUtils.STRING_SEPARATOR)
        .appendByteArray(valueBuffer.array())
        .build();
  }

  @Override
  public DimFilter optimize()
  {
    return this;
  }

  @Override
  public Filter toFilter()
  {
    return this;
  }

  @JsonProperty
  public String getColumn()
  {
    return column;
  }

  @JsonProperty
  public ColumnType getMatchValueType()
  {
    return matchValueType;
  }

  @JsonProperty
  public Object getMatchValue()
  {
    return matchValue;
  }

  @Nullable
  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public FilterTuning getFilterTuning()
  {
    return filterTuning;
  }

  @Override
  public String toString()
  {
    DimFilter.DimFilterToStringBuilder bob =
        new DimFilter.DimFilterToStringBuilder().appendDimension(column, null)
                                                .append(" = ")
                                                .append(matchValueEval.value());

    if (!ColumnType.STRING.equals(matchValueType)) {
      bob.append(" (" + matchValueType.asTypeString() + ")");
    }
    return bob.appendFilterTuning(filterTuning).build();
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    EqualityFilter that = (EqualityFilter) o;
    if (!column.equals(that.column)) {
      return false;
    }
    if (!Objects.equals(matchValueType, that.matchValueType)) {
      return false;
    }
    if (!Objects.equals(filterTuning, that.filterTuning)) {
      return false;
    }
    if (matchValueType.isArray()) {
      return Arrays.deepEquals(matchValueEval.asArray(), that.matchValueEval.asArray());
    } else {
      return Objects.equals(matchValueEval.value(), that.matchValueEval.value());
    }
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(column, matchValueType, matchValueEval.value(), filterTuning);
  }

  @Override
  public RangeSet<String> getDimensionRangeSet(String dimension)
  {
    if (!Objects.equals(getColumn(), dimension)) {
      return null;
    }
    RangeSet<String> retSet = TreeRangeSet.create();
    if (matchValueEval.isArray()) {
      retSet.add(Range.singleton(Arrays.deepToString(matchValueEval.asArray())));
    } else {
      retSet.add(Range.singleton(matchValueEval.asString()));
    }
    return retSet;
  }

  @Nullable
  @Override
  public BitmapColumnIndex getBitmapColumnIndex(ColumnIndexSelector selector)
  {
    if (!Filters.checkFilterTuningUseIndex(column, selector, filterTuning)) {
      return null;
    }

    final ColumnIndexSupplier indexSupplier = selector.getIndexSupplier(column);
    if (indexSupplier == null) {
      return new AllFalseBitmapColumnIndex(selector);
    }

    final ValueIndexes valueIndexes = indexSupplier.as(ValueIndexes.class);
    if (valueIndexes != null) {
      // matchValueEval.value() cannot be null here due to check in the constructor
      //noinspection DataFlowIssue
      return valueIndexes.forValue(matchValueEval.value(), matchValueType);
    }

    if (matchValueType.isPrimitive()) {
      final StringValueSetIndexes stringValueSetIndexes = indexSupplier.as(StringValueSetIndexes.class);
      if (stringValueSetIndexes != null) {

        return stringValueSetIndexes.forValue(matchValueEval.asString());
      }
    }
    // column exists, but has no indexes we can use
    return null;
  }

  @Override
  public ValueMatcher makeMatcher(ColumnSelectorFactory factory)
  {
    return ColumnProcessors.makeProcessor(
        column,
        new TypedConstantValueMatcherFactory(matchValueEval, predicateFactory),
        factory
    );
  }

  @Override
  public VectorValueMatcher makeVectorMatcher(VectorColumnSelectorFactory factory)
  {
    final ColumnCapabilities capabilities = factory.getColumnCapabilities(column);

    if (matchValueType.isPrimitive() && (capabilities == null || capabilities.isPrimitive())) {
      return ColumnProcessors.makeVectorProcessor(
          column,
          VectorValueMatcherColumnProcessorFactory.instance(),
          factory
      ).makeMatcher(matchValueEval.value(), matchValueType);
    }
    return ColumnProcessors.makeVectorProcessor(
        column,
        VectorValueMatcherColumnProcessorFactory.instance(),
        factory
    ).makeMatcher(new EqualityPredicateFactory(matchValueEval));
  }

  @Override
  public boolean supportsSelectivityEstimation(ColumnSelector columnSelector, ColumnIndexSelector indexSelector)
  {
    return Filters.supportsSelectivityEstimation(this, column, columnSelector, indexSelector);
  }

  @Override
  public boolean canVectorizeMatcher(ColumnInspector inspector)
  {
    return true;
  }

  @Override
  public Set<String> getRequiredColumns()
  {
    return ImmutableSet.of(column);
  }

  @Override
  public boolean supportsRequiredColumnRewrite()
  {
    return true;
  }

  @Override
  public Filter rewriteRequiredColumns(Map<String, String> columnRewrites)
  {
    String rewriteDimensionTo = columnRewrites.get(column);

    if (rewriteDimensionTo == null) {
      throw new IAE(
          "Received a non-applicable rewrite: %s, filter's dimension: %s",
          columnRewrites,
          columnRewrites
      );
    }

    return new EqualityFilter(
        rewriteDimensionTo,
        matchValueType,
        matchValue,
        filterTuning
    );
  }

  private static class EqualityPredicateFactory implements DruidPredicateFactory
  {
    private final ExprEval<?> matchValue;
    private final Supplier<Predicate<String>> stringPredicateSupplier;
    private final Supplier<DruidLongPredicate> longPredicateSupplier;
    private final Supplier<DruidFloatPredicate> floatPredicateSupplier;
    private final Supplier<DruidDoublePredicate> doublePredicateSupplier;
    private final ConcurrentHashMap<TypeSignature<ValueType>, Predicate<Object[]>> arrayPredicates;
    private final Supplier<Predicate<Object[]>> typeDetectingArrayPredicateSupplier;
    private final Supplier<Predicate<Object>> objectPredicateSupplier;

    public EqualityPredicateFactory(ExprEval<?> matchValue)
    {
      this.matchValue = matchValue;
      this.stringPredicateSupplier = makeStringPredicateSupplier();
      this.longPredicateSupplier = makeLongPredicateSupplier();
      this.floatPredicateSupplier = makeFloatPredicateSupplier();
      this.doublePredicateSupplier = makeDoublePredicateSupplier();
      this.objectPredicateSupplier = makeObjectPredicateSupplier();
      this.arrayPredicates = new ConcurrentHashMap<>();
      this.typeDetectingArrayPredicateSupplier = makeTypeDetectingArrayPredicate();
    }

    @Override
    public Predicate<String> makeStringPredicate()
    {
      return stringPredicateSupplier.get();
    }

    @Override
    public DruidLongPredicate makeLongPredicate()
    {
      return longPredicateSupplier.get();
    }

    @Override
    public DruidFloatPredicate makeFloatPredicate()
    {
      return floatPredicateSupplier.get();
    }

    @Override
    public DruidDoublePredicate makeDoublePredicate()
    {
      return doublePredicateSupplier.get();
    }

    @Override
    public Predicate<Object[]> makeArrayPredicate(@Nullable TypeSignature<ValueType> arrayType)
    {
      if (arrayType == null) {
        // fall back to per row detection if input array type is unknown
        return typeDetectingArrayPredicateSupplier.get();
      }

      return arrayPredicates.computeIfAbsent(arrayType, (existing) -> makeArrayPredicateInternal(arrayType));
    }

    @Override
    public Predicate<Object> makeObjectPredicate()
    {
      return objectPredicateSupplier.get();
    }

    private Supplier<Predicate<String>> makeStringPredicateSupplier()
    {
      return Suppliers.memoize(() -> Predicates.equalTo(matchValue.castTo(ExpressionType.STRING).asString()));
    }

    private Supplier<DruidLongPredicate> makeLongPredicateSupplier()
    {
      return Suppliers.memoize(() -> {
        final Long valueAsLong = (Long) matchValue.castTo(ExpressionType.LONG).valueOrDefault();

        if (valueAsLong == null) {
          return DruidLongPredicate.ALWAYS_FALSE;
        } else {
          // store the primitive, so we don't unbox for every comparison
          final long unboxedLong = valueAsLong;
          return input -> input == unboxedLong;
        }
      });
    }

    private Supplier<DruidFloatPredicate> makeFloatPredicateSupplier()
    {
      return Suppliers.memoize(() -> {
        final Double doubleValue = (Double) matchValue.castTo(ExpressionType.DOUBLE).valueOrDefault();

        if (doubleValue == null) {
          return DruidFloatPredicate.ALWAYS_FALSE;
        } else {
          // Compare with floatToIntBits instead of == to canonicalize NaNs.
          final int floatBits = Float.floatToIntBits(doubleValue.floatValue());
          return input -> Float.floatToIntBits(input) == floatBits;
        }
      });
    }

    private Supplier<DruidDoublePredicate> makeDoublePredicateSupplier()
    {
      return Suppliers.memoize(() -> {
        final Double aDouble = (Double) matchValue.castTo(ExpressionType.DOUBLE).valueOrDefault();

        if (aDouble == null) {
          return DruidDoublePredicate.ALWAYS_FALSE;
        } else {
          // Compare with doubleToLongBits instead of == to canonicalize NaNs.
          final long bits = Double.doubleToLongBits(aDouble);
          return input -> Double.doubleToLongBits(input) == bits;
        }
      });
    }

    private Supplier<Predicate<Object>> makeObjectPredicateSupplier()
    {
      return Suppliers.memoize(() -> {
        if (matchValue.type().equals(ExpressionType.NESTED_DATA)) {
          return input -> Objects.equals(StructuredData.unwrap(input), StructuredData.unwrap(matchValue.value()));
        }
        return Predicates.equalTo(matchValue.valueOrDefault());
      });
    }

    private Supplier<Predicate<Object[]>> makeTypeDetectingArrayPredicate()
    {
      return Suppliers.memoize(() -> input -> {
        final ExprEval<?> eval = ExprEval.bestEffortOf(input);
        final Comparator<Object[]> arrayComparator = eval.type().getNullableStrategy();
        final Object[] matchArray = matchValue.castTo(eval.type()).asArray();
        return arrayComparator.compare(input, matchArray) == 0;
      });
    }
    private Predicate<Object[]> makeArrayPredicateInternal(TypeSignature<ValueType> arrayType)
    {
      final ExpressionType expressionType = ExpressionType.fromColumnTypeStrict(arrayType);
      final Comparator<Object[]> arrayComparator = arrayType.getNullableStrategy();
      final Object[] matchArray = matchValue.castTo(expressionType).asArray();
      return input -> arrayComparator.compare(input, matchArray) == 0;
    }

    @Override
    public boolean equals(Object o)
    {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      EqualityPredicateFactory that = (EqualityPredicateFactory) o;
      if (!Objects.equals(matchValue.type(), that.matchValue.type())) {
        return false;
      }
      if (matchValue.isArray()) {
        return Arrays.deepEquals(matchValue.asArray(), that.matchValue.asArray());
      }
      return Objects.equals(matchValue.value(), that.matchValue.value());
    }


    @Override
    public int hashCode()
    {
      return Objects.hash(matchValue);
    }
  }

  private static class TypedConstantValueMatcherFactory implements ColumnProcessorFactory<ValueMatcher>
  {
    private final ExprEval<?> matchValue;
    private final PredicateValueMatcherFactory predicateMatcherFactory;

    public TypedConstantValueMatcherFactory(
        ExprEval<?> matchValue,
        DruidPredicateFactory predicateFactory
    )
    {
      this.matchValue = matchValue;
      this.predicateMatcherFactory = new PredicateValueMatcherFactory(predicateFactory);
    }

    @Override
    public ColumnType defaultType()
    {
      return ColumnType.UNKNOWN_COMPLEX;
    }

    @Override
    public ValueMatcher makeDimensionProcessor(DimensionSelector selector, boolean multiValue)
    {
      return ValueMatchers.makeStringValueMatcher(
          selector,
          matchValue.castTo(ExpressionType.STRING).asString(),
          multiValue
      );
    }

    @Override
    public ValueMatcher makeFloatProcessor(BaseFloatColumnValueSelector selector)
    {
      return ValueMatchers.makeFloatValueMatcher(selector, (float) matchValue.castTo(ExpressionType.DOUBLE).asDouble());
    }

    @Override
    public ValueMatcher makeDoubleProcessor(BaseDoubleColumnValueSelector selector)
    {
      return ValueMatchers.makeDoubleValueMatcher(selector, matchValue.castTo(ExpressionType.DOUBLE).asDouble());
    }

    @Override
    public ValueMatcher makeLongProcessor(BaseLongColumnValueSelector selector)
    {
      return ValueMatchers.makeLongValueMatcher(selector, matchValue.castTo(ExpressionType.LONG).asLong());
    }

    @Override
    public ValueMatcher makeArrayProcessor(
        BaseObjectColumnValueSelector<?> selector,
        @Nullable ColumnCapabilities columnCapabilities
    )
    {
      return predicateMatcherFactory.makeArrayProcessor(selector, columnCapabilities);
    }

    @Override
    public ValueMatcher makeComplexProcessor(BaseObjectColumnValueSelector<?> selector)
    {
      return predicateMatcherFactory.makeComplexProcessor(selector);
    }
  }
}
