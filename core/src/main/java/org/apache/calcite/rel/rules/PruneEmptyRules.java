/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.rules;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Intersect;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Minus;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;

import java.util.List;
import java.util.function.Predicate;

import static org.apache.calcite.plan.RelOptRule.any;
import static org.apache.calcite.plan.RelOptRule.none;
import static org.apache.calcite.plan.RelOptRule.operand;
import static org.apache.calcite.plan.RelOptRule.operandJ;
import static org.apache.calcite.plan.RelOptRule.some;
import static org.apache.calcite.plan.RelOptRule.unordered;

/**
 * Collection of rules which remove sections of a query plan known never to
 * produce any rows.
 *
 * <p>Conventionally, the way to represent an empty relational expression is
 * with a {@link Values} that has no tuples.
 *
 * @see LogicalValues#createEmpty
 */
public abstract class PruneEmptyRules {
  //~ Static fields/initializers ---------------------------------------------

  /**
   * Rule that removes empty children of a
   * {@link org.apache.calcite.rel.core.Union}.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>Union(Rel, Empty, Rel2) becomes Union(Rel, Rel2)
   * <li>Union(Rel, Empty, Empty) becomes Rel
   * <li>Union(Empty, Empty) becomes Empty
   * </ul>
   */
  public static final RelOptRule UNION_INSTANCE =
      ImmutableUnionEmptyPruneRuleConfig.of()
          .withOperandSupplier(b0 ->
              b0.operand(Union.class).unorderedInputs(b1 ->
                  b1.operand(Values.class)
                      .predicate(Values::isEmpty).noInputs()))
          .withDescription("Union")
          .toRule();


  /**
   * Rule that removes empty children of a
   * {@link org.apache.calcite.rel.core.Minus}.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>Minus(Rel, Empty, Rel2) becomes Minus(Rel, Rel2)
   * <li>Minus(Empty, Rel) becomes Empty
   * </ul>
   */
  public static final RelOptRule MINUS_INSTANCE =
      ImmutableMinusEmptyPruneRuleConfig.of()
          .withOperandSupplier(b0 ->
              b0.operand(Minus.class).unorderedInputs(b1 ->
                  b1.operand(Values.class).predicate(Values::isEmpty)
                      .noInputs()))
          .withDescription("Minus")
          .toRule();

  /**
   * Rule that converts a
   * {@link org.apache.calcite.rel.core.Intersect} to
   * empty if any of its children are empty.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>Intersect(Rel, Empty, Rel2) becomes Empty
   * <li>Intersect(Empty, Rel) becomes Empty
   * </ul>
   */
  public static final RelOptRule INTERSECT_INSTANCE =
      ImmutableIntersectEmptyPruneRuleConfig.of()
          .withOperandSupplier(b0 ->
              b0.operand(Intersect.class).unorderedInputs(b1 ->
                  b1.operand(Values.class).predicate(Values::isEmpty)
                      .noInputs()))
          .withDescription("Intersect")
          .toRule();

  private static boolean isEmpty(RelNode node) {
    if (node instanceof Values) {
      return ((Values) node).getTuples().isEmpty();
    }
    if (node instanceof HepRelVertex) {
      return isEmpty(((HepRelVertex) node).getCurrentRel());
    }
    // Note: relation input might be a RelSubset, so we just iterate over the relations
    // in order to check if the subset is equivalent to an empty relation.
    if (!(node instanceof RelSubset)) {
      return false;
    }
    RelSubset subset = (RelSubset) node;
    for (RelNode rel : subset.getRels()) {
      if (isEmpty(rel)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Rule that converts a {@link org.apache.calcite.rel.core.TableScan}
   * to empty if the table has no rows in it.
   *
   * The rule exploits the {@link org.apache.calcite.rel.metadata.RelMdMaxRowCount} to derive if
   * the table is empty or not.
   */
  public static final RelOptRule EMPTY_TABLE_INSTANCE =
      ImmutableZeroMaxRowsRuleConfig.of()
          .withOperandSupplier(b0 -> b0.operand(TableScan.class).noInputs())
          .withDescription("PruneZeroRowsTable")
          .toRule();

  /**
   * Rule that converts a {@link org.apache.calcite.rel.core.Project}
   * to empty if its child is empty.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>Project(Empty) becomes Empty
   * </ul>
   */
  public static final RelOptRule PROJECT_INSTANCE =
      new RemoveEmptySingleRule(Project.class,
          (Predicate<Project>) project -> true, RelFactories.LOGICAL_BUILDER,
          "PruneEmptyProject");

  /**
   * Rule that converts a {@link org.apache.calcite.rel.core.Filter}
   * to empty if its child is empty.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>Filter(Empty) becomes Empty
   * </ul>
   */
  public static final RelOptRule FILTER_INSTANCE =
      new RemoveEmptySingleRule(Filter.class, "PruneEmptyFilter");

  /**
   * Rule that converts a {@link org.apache.calcite.rel.core.Sort}
   * to empty if its child is empty.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>Sort(Empty) becomes Empty
   * </ul>
   */
  public static final RelOptRule SORT_INSTANCE =
      new RemoveEmptySingleRule(Sort.class, "PruneEmptySort");

  /**
   * Rule that converts a {@link org.apache.calcite.rel.core.Sort}
   * to empty if it has {@code LIMIT 0}.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>Sort(Empty) becomes Empty
   * </ul>
   */
  public static final RelOptRule SORT_FETCH_ZERO_INSTANCE =
      new RelOptRule(
          operand(Sort.class, any()), "PruneSortLimit0") {
        @Override public void onMatch(RelOptRuleCall call) {
          Sort sort = call.rel(0);
          if (sort.fetch != null
              && !(sort.fetch instanceof RexDynamicParam)
              && RexLiteral.intValue(sort.fetch) == 0) {
            call.transformTo(call.builder().push(sort).empty().build());
          }
        }
      };

  /**
   * Rule that converts an {@link org.apache.calcite.rel.core.Aggregate}
   * to empty if its child is empty.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>{@code Aggregate(key: [1, 3], Empty)} &rarr; {@code Empty}
   *
   * <li>{@code Aggregate(key: [], Empty)} is unchanged, because an aggregate
   * without a GROUP BY key always returns 1 row, even over empty input
   * </ul>
   *
   * @see AggregateValuesRule
   */
  public static final RelOptRule AGGREGATE_INSTANCE =
      new RemoveEmptySingleRule(Aggregate.class,
          (Predicate<Aggregate>) Aggregate::isNotGrandTotal,
          RelFactories.LOGICAL_BUILDER, "PruneEmptyAggregate");

  /**
   * Rule that converts a {@link org.apache.calcite.rel.core.Join}
   * to empty if its left child is empty.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>Join(Empty, Scan(Dept), INNER) becomes Empty
   * </ul>
   */
  public static final RelOptRule JOIN_LEFT_INSTANCE =
      new RelOptRule(
          operand(Join.class,
              some(
                  operandJ(Values.class, null, Values::isEmpty, none()),
                  operand(RelNode.class, any()))),
              "PruneEmptyJoin(left)") {
        @Override public void onMatch(RelOptRuleCall call) {
          Join join = call.rel(0);
          if (join.getJoinType().generatesNullsOnLeft()) {
            // "select * from emp right join dept" is not necessarily empty if
            // emp is empty
            return;
          }
          call.transformTo(call.builder().push(join).empty().build());
        }
      };

  /**
   * Rule that converts a {@link org.apache.calcite.rel.core.Join}
   * to empty if its right child is empty.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>Join(Scan(Emp), Empty, INNER) becomes Empty
   * </ul>
   */
  public static final RelOptRule JOIN_RIGHT_INSTANCE =
      new RelOptRule(
          operand(Join.class,
              some(
                  operand(RelNode.class, any()),
                  operandJ(Values.class, null, Values::isEmpty, none()))),
              "PruneEmptyJoin(right)") {
        @Override public void onMatch(RelOptRuleCall call) {
          Join join = call.rel(0);
          if (join.getJoinType().generatesNullsOnRight()) {
            // "select * from emp left join dept" is not necessarily empty if
            // dept is empty
            return;
          }
          call.transformTo(call.builder().push(join).empty().build());
        }
      };

  /** Planner rule that converts a single-rel (e.g. project, sort, aggregate or
   * filter) on top of the empty relational expression into empty. */
  public static class RemoveEmptySingleRule extends RelOptRule {
    /** Creates a simple RemoveEmptySingleRule. */
    public <R extends SingleRel> RemoveEmptySingleRule(Class<R> clazz,
        String description) {
      this(clazz, (Predicate<R>) project -> true, RelFactories.LOGICAL_BUILDER,
          description);
    }

    /** Creates a RemoveEmptySingleRule. */
    public <R extends SingleRel> RemoveEmptySingleRule(Class<R> clazz,
        Predicate<R> predicate, RelBuilderFactory relBuilderFactory,
        String description) {
      super(
          operandJ(clazz, null, predicate,
              operandJ(Values.class, null, Values::isEmpty, none())),
          relBuilderFactory, description);
    }

    @SuppressWarnings("Guava")
    @Deprecated // to be removed before 2.0
    public <R extends SingleRel> RemoveEmptySingleRule(Class<R> clazz,
        com.google.common.base.Predicate<R> predicate,
        RelBuilderFactory relBuilderFactory, String description) {
      this(ImmutableRemoveEmptySingleRuleConfig.of().withRelBuilderFactory(relBuilderFactory)
          .withDescription(description)
          .as(ImmutableRemoveEmptySingleRuleConfig.class)
          .withOperandFor(clazz, predicate::apply));
    }

    @Override public void onMatch(RelOptRuleCall call) {
      SingleRel singleRel = call.rel(0);
      RelNode emptyValues = call.builder().push(singleRel).empty().build();
      RelTraitSet traits = singleRel.getTraitSet();
      // propagate all traits (except convention) from the original singleRel into the empty values
      if (emptyValues.getConvention() != null) {
        traits = traits.replace(emptyValues.getConvention());
      }
      emptyValues = emptyValues.copy(traits, Collections.emptyList());
      call.transformTo(emptyValues);
    }

    /** Rule configuration. */
    @Value.Immutable
    public interface RemoveEmptySingleRuleConfig extends PruneEmptyRule.Config {
      @Override default RemoveEmptySingleRule toRule() {
        return new RemoveEmptySingleRule(this);
      }

      /** Defines an operand tree for the given classes. */
      default <R extends RelNode> RemoveEmptySingleRuleConfig withOperandFor(Class<R> relClass,
          Predicate<R> predicate) {
        return withOperandSupplier(b0 ->
            b0.operand(relClass).predicate(predicate).oneInput(b1 ->
                b1.operand(Values.class).predicate(Values::isEmpty).noInputs()))
            .as(RemoveEmptySingleRuleConfig.class);
      }
    }
  }

  /** Configuration for a rule that prunes empty inputs from a Minus. */
  @Value.Immutable
  public interface UnionEmptyPruneRuleConfig extends PruneEmptyRule.Config {
    @Override default PruneEmptyRule toRule() {
      return new PruneEmptyRule(this) {
        @Override public void onMatch(RelOptRuleCall call) {
          final Union union = call.rel(0);
          final List<RelNode> inputs = union.getInputs();
          assert inputs != null;
          final RelBuilder builder = call.builder();
          int nonEmptyInputs = 0;
          for (RelNode input : inputs) {
            if (!isEmpty(input)) {
              builder.push(input);
              nonEmptyInputs++;
            }
          }
          assert nonEmptyInputs < inputs.size()
              : "planner promised us at least one Empty child: "
              + RelOptUtil.toString(union);
          if (nonEmptyInputs == 0) {
            builder.push(union).empty();
          } else {
            builder.union(union.all, nonEmptyInputs);
            builder.convert(union.getRowType(), true);
          }
          call.transformTo(builder.build());
        }
      };
    }
  }

  /** Configuration for a rule that prunes empty inputs from a Minus. */
  @Value.Immutable
  public interface MinusEmptyPruneRuleConfig extends PruneEmptyRule.Config {
    @Override default PruneEmptyRule toRule() {
      return new PruneEmptyRule(this) {
        @Override public void onMatch(RelOptRuleCall call) {
          final Minus minus = call.rel(0);
          final List<RelNode> inputs = minus.getInputs();
          assert inputs != null;
          int nonEmptyInputs = 0;
          final RelBuilder builder = call.builder();
          for (RelNode input : inputs) {
            if (!isEmpty(input)) {
              builder.push(input);
              nonEmptyInputs++;
            } else if (nonEmptyInputs == 0) {
              // If the first input of Minus is empty, the whole thing is
              // empty.
              break;
            }
          }
          assert nonEmptyInputs < inputs.size()
              : "planner promised us at least one Empty child: "
              + RelOptUtil.toString(minus);
          if (nonEmptyInputs == 0) {
            builder.push(minus).empty();
          } else {
            builder.minus(minus.all, nonEmptyInputs);
            builder.convert(minus.getRowType(), true);
          }
          call.transformTo(builder.build());
        }
      };
    }

  /** Configuration for a rule that prunes an Intersect if any of its inputs
   * is empty. */
  @Value.Immutable
  public interface IntersectEmptyPruneRuleConfig extends PruneEmptyRule.Config {
    @Override default PruneEmptyRule toRule() {
      return new PruneEmptyRule(this) {
        @Override public void onMatch(RelOptRuleCall call) {
          Intersect intersect = call.rel(0);
          final RelBuilder builder = call.builder();
          builder.push(intersect).empty();
          call.transformTo(builder.build());
        }
      };
    }
  }

  /** Configuration for a rule that prunes a Sort if it has limit 0. */
  @Value.Immutable
  public interface SortFetchZeroRuleConfig extends PruneEmptyRule.Config {
    @Override default PruneEmptyRule toRule() {
      return new PruneEmptyRule(this) {
        @Override public void onMatch(RelOptRuleCall call) {
          Sort sort = call.rel(0);
          if (sort.fetch != null
              && !(sort.fetch instanceof RexDynamicParam)
              && RexLiteral.intValue(sort.fetch) == 0) {
            RelNode emptyValues = call.builder().push(sort).empty().build();
            RelTraitSet traits = sort.getTraitSet();
            // propagate all traits (except convention) from the original sort into the empty values
            if (emptyValues.getConvention() != null) {
              traits = traits.replace(emptyValues.getConvention());
            }
            emptyValues = emptyValues.copy(traits, Collections.emptyList());
            call.transformTo(emptyValues);
          }
        }

      };
    }
  }

  /** Configuration for rule that prunes a join it its left input is
   * empty. */
  @Value.Immutable
  public interface JoinLeftEmptyRuleConfig extends PruneEmptyRule.Config {
    @Override default PruneEmptyRule toRule() {
      return new PruneEmptyRule(this) {
        @Override public void onMatch(RelOptRuleCall call) {
          Join join = call.rel(0);
          if (join.getJoinType().generatesNullsOnLeft()) {
            // "select * from emp right join dept" is not necessarily empty if
            // emp is empty
            return;
          }
          call.transformTo(call.builder().push(join).empty().build());
        }
      };
    }
  }

  /** Configuration for rule that prunes a join it its right input is
   * empty. */
  @Value.Immutable
  public interface JoinRightEmptyRuleConfig extends PruneEmptyRule.Config {
    @Override default PruneEmptyRule toRule() {
      return new PruneEmptyRule(this) {
        @Override public void onMatch(RelOptRuleCall call) {
          Join join = call.rel(0);
          if (join.getJoinType().generatesNullsOnRight()) {
            // "select * from emp left join dept" is not necessarily empty if
            // dept is empty
            return;
          }
          if (join.getJoinType() == JoinRelType.ANTI) {
            // In case of anti join: Join(X, Empty, ANTI) becomes X
            call.transformTo(join.getLeft());
            return;
          }
          call.transformTo(call.builder().push(join).empty().build());
        }
      };
    }
  }

  /** Configuration for rule that transforms an empty relational expression into an empty values.
   *
   * It relies on {@link org.apache.calcite.rel.metadata.RelMdMaxRowCount} to derive if the relation
   * is empty or not. If the stats are not available then the rule is a noop. */
  @Value.Immutable
  public interface ZeroMaxRowsRuleConfig extends PruneEmptyRule.Config {

    @Override default PruneEmptyRule toRule() {
      return new PruneEmptyRule(this) {
        @Override public boolean matches(RelOptRuleCall call) {
          RelNode node = call.rel(0);
          Double maxRowCount = call.getMetadataQuery().getMaxRowCount(node);
          return maxRowCount != null && maxRowCount == 0.0;
        }

        @Override public void onMatch(RelOptRuleCall call) {
          RelNode node = call.rel(0);
          RelNode emptyValues = call.builder().push(node).empty().build();
          RelTraitSet traits = node.getTraitSet();
          // propagate all traits (except convention) from the original tableScan
          // into the empty values
          if (emptyValues.getConvention() != null) {
            traits = traits.replace(emptyValues.getConvention());
          }
          emptyValues = emptyValues.copy(traits, Collections.emptyList());
          call.transformTo(emptyValues);
        }
      };
    }
  }
}

// End PruneEmptyRules.java
