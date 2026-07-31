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
package cn.lgs.semevosql.semantic.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A deliberately thin description of the computation the answer requires.
 *
 * <p>This is not a SQL AST or executable DSL. It describes required capabilities only;
 * the deterministic SQL generator or constrained Semantic SQL generator remains free to
 * choose the physical SQL structure.</p>
 */
public record ComputationIntent(Set<Capability> capabilities) {

	public ComputationIntent {
		capabilities = Set.copyOf(capabilities == null ? Set.of() : new LinkedHashSet<>(capabilities));
	}

	public static ComputationIntent empty() {
		return new ComputationIntent(Set.of());
	}

	public boolean requires(Capability capability) {
		return capability != null && capabilities.contains(capability);
	}

	public boolean requiresExplicitTimeAxis() {
		return capabilities.stream().anyMatch(Capability::requiresExplicitTimeAxis);
	}

	public enum Capability {
		PROJECTION(false),
		FILTER(false),
		AGGREGATION(false),
		GROUPING(false),
		ORDERING(false),
		LIMIT(false),
		JOIN(false),
		TIME_FILTER(true),
		TIME_BUCKET(true),
		CONDITIONAL_AGGREGATION(false),
		PERIOD_COMPARISON(true),
		WINDOW_ANALYTICS(true),
		PARTITION_RANKING(false),
		MULTI_STAGE_AGGREGATION(false),
		SET_OPERATION(false),
		RECURSIVE_QUERY(false),
		COHORT_ANALYSIS(true),
		MULTI_SOURCE(false),
		CROSS_SOURCE_MERGE(false),
		SCALAR_COMPOSITION(false);

		private final boolean requiresExplicitTimeAxis;

		Capability(boolean requiresExplicitTimeAxis) {
			this.requiresExplicitTimeAxis = requiresExplicitTimeAxis;
		}

		public boolean requiresExplicitTimeAxis() {
			return requiresExplicitTimeAxis;
		}
	}
}
