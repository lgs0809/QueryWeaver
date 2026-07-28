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
package cn.lgs.semevosql.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget;
import org.junit.jupiter.api.Test;

class QueryRepairPolicyTest {

	@Test
	void executionReplanConsumesBoundedReplanBudgetWithoutResettingSqlRepairs() {
		QueryRepairPolicy policy = new QueryRepairPolicy();
		policy.setMaxSemanticReplans(1);
		policy.setMaxTotalTransitions(3);
		RepairBudget exhaustedSqlStrategy = new RepairBudget(2, 0, 0, 0, 0, 2);

		var decision = policy.consumeTransition(exhaustedSqlStrategy, Decision.REPLAN_EXECUTION);

		assertTrue(decision.allowed());
		assertEquals(2, decision.budget().sqlRepairsUsed());
		assertEquals(1, decision.budget().semanticReplansUsed());
		assertEquals(3, decision.budget().totalTransitions());
	}

	@Test
	void semanticRebindAndExecutionReplanShareOneDurableReplanBudget() {
		QueryRepairPolicy policy = new QueryRepairPolicy();
		policy.setMaxSemanticReplans(1);
		policy.setMaxTotalTransitions(3);
		RepairBudget budget = RepairBudget.empty();

		var first = policy.consumeTransition(budget, Decision.REBIND_SEMANTIC);
		var second = policy.consumeTransition(first.budget(), Decision.REPLAN_EXECUTION);

		assertTrue(first.allowed());
		assertFalse(second.allowed());
		assertEquals("SEMANTIC_REPLAN_BUDGET_EXHAUSTED", second.reason());
	}
}
