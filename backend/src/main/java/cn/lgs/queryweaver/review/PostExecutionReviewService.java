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
package cn.lgs.queryweaver.review;

import cn.lgs.queryweaver.bo.schema.ResultSetBO;
import cn.lgs.queryweaver.review.PostExecutionReview.Decision;
import cn.lgs.queryweaver.review.PostExecutionReview.IssueType;
import cn.lgs.queryweaver.semantic.domain.SemanticQueryPlan;
import cn.lgs.queryweaver.sql.application.SqlResultValidator;
import cn.lgs.queryweaver.sql.application.SqlResultValidator.ValidationResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Deterministic-first result acceptance followed by an optional constrained semantic reviewer. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostExecutionReviewService {

	private final SqlResultValidator resultValidator;

	private final SemanticResultReviewer semanticReviewer;

	private final PostExecutionReviewProperties properties;

	public PostExecutionReview review(String question, SemanticQueryPlan plan, String sql, ResultSetBO resultSet,
			int configuredMaxRows) {
		return review(question, plan, sql, resultSet, configuredMaxRows, ReviewMode.CONFIGURED);
	}

	public PostExecutionReview review(String question, SemanticQueryPlan plan, String sql, ResultSetBO resultSet,
			int configuredMaxRows, ReviewMode mode) {
		ValidationResult deterministic = resultValidator.validate(resultSet, plan, configuredMaxRows);
		PostExecutionReview preliminary = deterministic.valid()
				? PostExecutionReview.deterministicPass(deterministic.warnings())
				: PostExecutionReview.deterministicRetry(deterministic.errors(), deterministic.warnings());
		if (!shouldRunSemanticReviewer(mode, deterministic, plan)) {
			return preliminary;
		}
		try {
			PostExecutionReview reviewed = semanticReviewer.review(question, plan, sql, resultSet, deterministic.errors(),
					deterministic.warnings());
			return normalize(reviewed, deterministic);
		}
		catch (RuntimeException ex) {
			log.warn("Semantic post-execution reviewer was ignored because its constrained result was unavailable/invalid: {}",
					ex.getMessage());
			List<String> warnings = new ArrayList<>(preliminary.deterministicWarnings());
			warnings.add("Semantic reviewer unavailable or invalid; deterministic decision retained");
			return new PostExecutionReview(preliminary.decision(), preliminary.issueType(), preliminary.confidence(),
					preliminary.suspectedAssetKeys(), preliminary.evidence(), preliminary.deterministicErrors(), warnings,
					false, null);
		}
	}

	public boolean shouldRunSemanticReviewer(ReviewMode mode, ValidationResult deterministic, SemanticQueryPlan plan) {
		ReviewMode effective = mode == null ? ReviewMode.CONFIGURED : mode;
		if (effective == ReviewMode.DETERMINISTIC_ONLY) {
			return false;
		}
		if (effective == ReviewMode.SEMANTIC_ALWAYS) {
			return true;
		}
		if (!properties.isSemanticEnabled()) {
			return false;
		}
		if (properties.isAlwaysSemanticReview() || !deterministic.valid() || !deterministic.warnings().isEmpty()) {
			return true;
		}
		return complexPlan(plan);
	}

	private boolean complexPlan(SemanticQueryPlan plan) {
		if (plan == null) {
			return false;
		}
		return plan.getMetrics().size() > 1 || plan.getDimensions().size() > 1 || !plan.getRelationships().isEmpty()
				|| !plan.getRules().isEmpty() || plan.getSourceSubPlans().size() > 1;
	}

	private PostExecutionReview normalize(PostExecutionReview reviewed, ValidationResult deterministic) {
		if (!deterministic.valid() && reviewed.decision() == Decision.PASS) {
			return PostExecutionReview.deterministicRetry(deterministic.errors(), deterministic.warnings());
		}
		if (reviewed.decision() == Decision.PASS && reviewed.issueType() != IssueType.NONE) {
			return new PostExecutionReview(Decision.PASS, IssueType.NONE, reviewed.confidence(), SetSupport.empty(),
					reviewed.evidence(), deterministic.errors(), deterministic.warnings(), true, reviewed.modelEvidence());
		}
		return reviewed;
	}

	public enum ReviewMode {
		CONFIGURED,
		DETERMINISTIC_ONLY,
		SEMANTIC_ALWAYS
	}

	private static final class SetSupport {
		private static java.util.Set<String> empty() {
			return java.util.Set.of();
		}
	}

}
