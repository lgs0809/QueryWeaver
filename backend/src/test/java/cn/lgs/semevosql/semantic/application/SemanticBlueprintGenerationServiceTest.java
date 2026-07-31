/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.semantic.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.CrossSourceRelationship;
import cn.lgs.semevosql.semantic.domain.RelationshipCardinality;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticBlueprintGenerationServiceTest {

	@Test
	void genericTemporalGroupingWithMultipleGovernedTimeAxesRequiresClarification() {
		SemanticCandidateSet candidates = candidates(time("created_at", "created_at"), time("paid_at", "paid_at"));

		SemanticPlanningOutcome outcome = SemanticBlueprintGenerationService.unresolvedGenericTimeAxis("按时间统计订单数量。",
				candidates, QueryCaseHints.empty());

		assertThat(outcome).isInstanceOf(SemanticPlanningOutcome.ClarificationRequired.class);
		SemanticPlanningOutcome.ClarificationRequired clarification = (SemanticPlanningOutcome.ClarificationRequired) outcome;
		assertThat(clarification.options()).extracting(SemanticPlanningOutcome.Option::assetKey)
			.containsExactly("created_at", "paid_at");
	}

	@Test
	void plannerSelectedTimeDimensionCannotSilentlyResolveGenericUserAmbiguity() {
		SemanticCandidateSet candidates = candidates(time("created_at", "created_at"), time("paid_at", "paid_at"));
		QueryCaseHints binding = new QueryCaseHints(Set.of("orders"), Set.of("order_count"), Set.of("paid_at"), Set.of(),
				Set.of(), Set.of(), List.of(), "CURRENT_QUERY", List.of(), 1.0d, Map.of());

		SemanticPlanningOutcome outcome = SemanticBlueprintGenerationService.unresolvedGenericTimeAxis("按时间统计订单数量。",
				candidates, binding);

		assertThat(outcome).isInstanceOf(SemanticPlanningOutcome.ClarificationRequired.class);
	}

	@Test
	void explicitBusinessTimeAxisDoesNotTriggerGenericFallback() {
		SemanticCandidateSet candidates = candidates(time("created_at", "created_at"), time("paid_at", "paid_at"));

		SemanticPlanningOutcome outcome = SemanticBlueprintGenerationService.unresolvedGenericTimeAxis("按 paid_at 日期统计订单数量。",
				candidates, QueryCaseHints.empty());

		assertThat(outcome).isNull();
	}

	@Test
	void scalarCompositionAcceptsPlannerDeclaredGovernedMetricCalculation() {
		QueryCaseHints.ResultCompositionHint composition = SemanticBlueprintGenerationService.validateResultComposition("SCALAR",
				"difference = ABS(order_count - golden_order_count)", Set.of("order_count", "golden_order_count"));

		assertThat(composition.type()).isEqualTo("SCALAR");
		assertThat(composition.calculationExpression()).isEqualTo("difference=ABS(order_count-golden_order_count)");
	}

	@Test
	void scalarCompositionRejectsMetricsThatWereNotSelectedByPlanner() {
		assertThatThrownBy(() -> SemanticBlueprintGenerationService.validateResultComposition("SCALAR",
				"difference=order_count-unknown_metric", Set.of("order_count", "golden_order_count")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("only selected metric codes");
	}

	@Test
	void scalarCompositionRejectsArbitraryFunctionsAndOperators() {
		assertThatThrownBy(() -> SemanticBlueprintGenerationService.validateResultComposition("SCALAR",
				"ratio=order_count/golden_order_count", Set.of("order_count", "golden_order_count")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("only one binary + or - expression");
	}

	@Test
	void crossSourceRelationshipBecomesGovernedPlannerRelationshipWithoutPhysicalJoinSemantics() {
		CrossSourceRelationship crossSource = CrossSourceRelationship.builder()
			.relationshipCode("pay_to_order")
			.leftModelCode("pay_order")
			.leftKey("user_id")
			.rightModelCode("orders")
			.rightKey("customer_id")
			.cardinality(RelationshipCardinality.MANY_TO_MANY)
			.evidence("published multi-source policy")
			.status(SemanticAssetStatus.ENABLED)
			.build();

		SemanticCatalogSnapshot.Relationship relationship = SemanticBlueprintGenerationService.plannerRelationship(1L, 2L,
				crossSource);

		assertThat(relationship.getRelationshipCode()).isEqualTo("pay_to_order");
		assertThat(relationship.getSourceModelCode()).isEqualTo("pay_order");
		assertThat(relationship.getTargetModelCode()).isEqualTo("orders");
		assertThat(relationship.getJoinType()).isEqualTo("CROSS_SOURCE_MERGE");
		assertThat(relationship.getJoinCondition()).isEqualTo("pay_order.user_id = orders.customer_id");
	}

	private SemanticCandidateSet candidates(SemanticCatalogSnapshot.Dimension... dimensions) {
		return new SemanticCandidateSet(1L, 1L, "hash", Set.of("orders"), List.of(), List.of(), List.of(dimensions),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
	}

	private SemanticCatalogSnapshot.Dimension time(String code, String column) {
		return SemanticCatalogSnapshot.Dimension.builder()
			.modelCode("orders")
			.dimensionCode(code)
			.businessName(code)
			.columnName(column)
			.dimensionType("TIME")
			.status(SemanticAssetStatus.ENABLED)
			.build();
	}
}
