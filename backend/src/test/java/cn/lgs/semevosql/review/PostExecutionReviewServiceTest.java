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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.review.PostExecutionReviewService.ReviewMode;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.sql.application.SqlResultValidator;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationMode;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostExecutionReviewServiceTest {

	@Test
	void advancedWindowShapeCanBeVerifiedFromPlannerRequirementAndSql() {
		assertThat(PostExecutionReviewService.requiredAdvancedShapeIsObservable(
				"Use LAG and PARTITION BY created_at_month to get previous paid week",
				"SELECT LAG(amount) OVER (PARTITION BY created_at_month ORDER BY paid_at_week) FROM t"))
			.isTrue();
		assertThat(PostExecutionReviewService.requiredAdvancedShapeIsObservable(
				"Use LAG and PARTITION BY created_at_month to get previous paid week",
				"SELECT amount FROM t"))
			.isFalse();
	}

	@Test
	void deterministicOnlyModeCannotBeBypassedByContextualWarnings() {
		SqlResultValidator validator = mock(SqlResultValidator.class);
		SemanticResultReviewer reviewer = mock(SemanticResultReviewer.class);
		PostExecutionReviewProperties properties = new PostExecutionReviewProperties();
		PostExecutionReviewService service = new PostExecutionReviewService(validator, reviewer, properties);
		SemanticBlueprint plan = new SemanticBlueprint();
		ResultSetBO resultSet = ResultSetBO.builder().column(List.of("value")).data(List.of(Map.of("value", "1"))).build();
		when(validator.validate(any(ResultSetBO.class), any(SemanticBlueprint.class), anyInt(), any(ValidationMode.class)))
			.thenReturn(ValidationResult.accepted(List.of()));

		PostExecutionReview review = service.review("question", plan, "select 1", resultSet, 1000,
				"execution plan", ReviewMode.DETERMINISTIC_ONLY, ValidationMode.ADVANCED_EXECUTION,
				List.of("NULLABLE_COLUMN_REFERENCED model=orders column=paid_at role=TIME"));

		assertThat(review.decision()).isEqualTo(PostExecutionReview.Decision.PASS);
		assertThat(review.semanticReviewerUsed()).isFalse();
		verify(reviewer, never()).review(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
	}

	@Test
	void governedMultiSourceEmptyResultIsDataFactNotSqlRepair() {
		SqlResultValidator validator = mock(SqlResultValidator.class);
		SemanticResultReviewer reviewer = mock(SemanticResultReviewer.class);
		PostExecutionReviewProperties properties = new PostExecutionReviewProperties();
		PostExecutionReviewService service = new PostExecutionReviewService(validator, reviewer, properties);
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.sourceSubPlans(List.of(
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).modelCodes(List.of("left")).build(),
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(2).modelCodes(List.of("right")).build()))
			.mergePlan(SemanticBlueprint.MergePlan.builder().policyCode("governed_lookup").build())
			.build();
		ResultSetBO resultSet = ResultSetBO.builder().column(List.of("value")).data(List.of()).build();
		when(validator.validate(any(ResultSetBO.class), any(SemanticBlueprint.class), anyInt(), any(ValidationMode.class)))
			.thenReturn(ValidationResult.accepted(List.of("SQL completed successfully but returned no rows")));
		when(reviewer.review(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
			.thenReturn(new PostExecutionReview(PostExecutionReview.Decision.RETRY_SQL,
					PostExecutionReview.IssueType.SQL_REPAIRABLE, 0.97d, java.util.Set.of("relationship:governed"),
					List.of("The governed merge returned no rows"), List.of(),
					List.of("SQL completed successfully but returned no rows"), true, null));

		PostExecutionReview review = service.review("question", plan, "source sql", resultSet, 1000,
				"governed merge", ReviewMode.CONFIGURED, ValidationMode.STRICT_SEMANTIC_PLAN, List.of());

		assertThat(review.decision()).isEqualTo(PostExecutionReview.Decision.PASS);
		assertThat(review.issueType()).isEqualTo(PostExecutionReview.IssueType.NONE);
		assertThat(review.deterministicWarnings())
			.anyMatch(warning -> warning.contains("empty result was retained instead of repair/replan"));
		assertThat(review.semanticReviewerUsed()).isTrue();
	}

	@Test
	void governedMultiSourceEmptyResultDoesNotReplanForIntermediateShape() {
		SqlResultValidator validator = mock(SqlResultValidator.class);
		SemanticResultReviewer reviewer = mock(SemanticResultReviewer.class);
		PostExecutionReviewProperties properties = new PostExecutionReviewProperties();
		PostExecutionReviewService service = new PostExecutionReviewService(validator, reviewer, properties);
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.sourceSubPlans(List.of(
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).modelCodes(List.of("left")).build(),
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(2).modelCodes(List.of("right")).build()))
			.mergePlan(SemanticBlueprint.MergePlan.builder().policyCode("governed_lookup").build())
			.build();
		ResultSetBO resultSet = ResultSetBO.builder()
			.column(List.of("effective_paid_amount", "order_count"))
			.data(List.of())
			.build();
		when(validator.validate(any(ResultSetBO.class), any(SemanticBlueprint.class), anyInt(), any(ValidationMode.class)))
			.thenReturn(ValidationResult.accepted(List.of("SQL completed successfully but returned no rows")));
		when(reviewer.review(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
			.thenReturn(new PostExecutionReview(PostExecutionReview.Decision.REPLAN_EXECUTION,
					PostExecutionReview.IssueType.RESULT_SHAPE_MISMATCH, 0.96d,
					java.util.Set.of("relationship:governed"),
					List.of("Source-level merge keys are grouped before the governed merge"), List.of(),
					List.of("SQL completed successfully but returned no rows"), true, null));

		PostExecutionReview review = service.review("question", plan, "source sql", resultSet, 1000,
				"governed cross-source merge", ReviewMode.CONFIGURED, ValidationMode.STRICT_SEMANTIC_PLAN, List.of());

		assertThat(review.decision()).isEqualTo(PostExecutionReview.Decision.PASS);
		assertThat(review.issueType()).isEqualTo(PostExecutionReview.IssueType.NONE);
		assertThat(review.deterministicWarnings())
			.anyMatch(warning -> warning.contains("deterministic final-result validation passed"));
	}
}
