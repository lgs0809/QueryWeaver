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

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.ValidatedQueryExampleService;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintGenerationService.PlanningDecision;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintGenerationService.PlannerProfile;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService.PlanningRecall;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Application pipeline for governed semantic planning.
 *
 * <p>Each stage has an immutable output so candidate-recall failures, LLM binding failures and
 * deterministic plan-resolution failures remain attributable instead of collapsing into one
 * generic NL2SQL error.
 */
@Service
public class SemanticBlueprintPipeline {

	private final SemanticCatalogApplicationService catalogService;

	private final SemanticBlueprintGenerationService llmPlanningService;

	private final ValidatedQueryExampleService queryExampleService;

	public SemanticBlueprintPipeline(SemanticCatalogApplicationService catalogService,
			SemanticBlueprintGenerationService llmPlanningService, ValidatedQueryExampleService queryExampleService) {
		this.catalogService = catalogService;
		this.llmPlanningService = llmPlanningService;
		this.queryExampleService = queryExampleService;
	}

	public PlanningResult plan(PlanningRequest request) {
		String planningId = UUID.randomUUID().toString();
		long started = System.nanoTime();
		if (request == null || request.projectId() == null || request.projectVersionId() == null
				|| !StringUtils.hasText(request.query())) {
			throw new SemanticPlanningRejectedException("INVALID_REQUEST", "Semantic planning request is incomplete");
		}

		long recallStarted = System.nanoTime();
		PlanningRecall recall = catalogService.recallPlanning(request.projectId(), request.projectVersionId(),
				request.effectiveRetrievalQuery(), request.recallLimit());
		Set<String> candidateTables = new LinkedHashSet<>(recall.physicalTables());
		candidateTables.addAll(safe(request.additionalPhysicalTables()));
		if (candidateTables.isEmpty()) {
			throw new SemanticPlanningRejectedException("RETRIEVAL_MISS",
					"No governed candidate table was recalled for semantic planning");
		}
		long recallMs = elapsedMillis(recallStarted);

		long candidateStarted = System.nanoTime();
		SemanticCandidateSet candidates = llmPlanningService.candidates(request.projectId(), request.projectVersionId(),
				candidateTables, recall.hits());
		if (candidates.empty()) {
			throw new SemanticPlanningRejectedException("CANDIDATE_BUILD_EMPTY",
					"Candidate recall did not resolve to an enabled semantic model");
		}
		long candidateMs = elapsedMillis(candidateStarted);

		long examplesStarted = System.nanoTime();
		QueryCaseHints historicalHints = StringUtils.hasText(request.contextHash())
				? queryExampleService.recallHints(request.projectId(), request.projectVersionId(), request.catalogHash(),
						request.query(), request.contextHash(), request.exampleLimit())
				: queryExampleService.recallHints(request.projectId(), request.projectVersionId(), request.catalogHash(),
						request.query(), request.exampleLimit());
		long exampleRecallMs = elapsedMillis(examplesStarted);

		long bindingStarted = System.nanoTime();
		PlanningDecision planningDecision = llmPlanningService.planDecision(request.query(), candidates, recall.hits(),
				historicalHints, request.requiredHints(), PlannerProfile.CONFIGURED);
		if (planningDecision == null) {
			SemanticPlanningOutcome compatibilityOutcome = llmPlanningService.planOutcome(request.query(), candidates,
					recall.hits(), historicalHints, request.requiredHints());
			planningDecision = new PlanningDecision(compatibilityOutcome, List.of());
		}
		SemanticPlanningOutcome outcome = planningDecision.outcome();
		if (outcome instanceof SemanticPlanningOutcome.ClarificationRequired clarification) {
			throw new SemanticPlanningClarificationRequiredException(clarification);
		}
		if (outcome instanceof SemanticPlanningOutcome.Rejected rejected) {
			throw new SemanticPlanningRejectedException(rejected.errorCode(), rejected.reason());
		}
		QueryCaseHints binding = ((SemanticPlanningOutcome.Resolved) outcome).binding();
		long bindingMs = elapsedMillis(bindingStarted);

		long resolutionStarted = System.nanoTime();
		SemanticBlueprint plan = catalogService.buildBlueprint(request.projectId(), request.projectVersionId(), request.query(),
				candidateTables, binding);
		if (!plan.isExecutable()) {
			throw new SemanticPlanningRejectedException("PLAN_RESOLUTION_ERROR",
					"Resolved governed semantic plan is not executable: " + String.join("; ", plan.getValidationErrors()));
		}
		long resolutionMs = elapsedMillis(resolutionStarted);
		int modelCallCount = planningDecision.modelCalls().size();
		boolean nativeReasoningUsed = planningDecision.modelCalls()
			.stream()
			.anyMatch(call -> call.invocationProfile().reasoningApplied());
		PlanningTrace trace = new PlanningTrace(planningId, candidates.catalogHash(), candidateTables, recall.hits().size(),
				candidates.models().size(), candidates.metrics().size(), candidates.dimensions().size(), recallMs, candidateMs,
				exampleRecallMs, bindingMs, resolutionMs, elapsedMillis(started), modelCallCount, nativeReasoningUsed);
		return new PlanningResult(plan, candidates, historicalHints, binding, trace);
	}

	private long elapsedMillis(long startedNanos) {
		return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
	}

	private <T> Collection<T> safe(Collection<T> value) {
		return value == null ? List.of() : value;
	}

	public record PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
			String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
			int exampleLimit, String retrievalQuery) {
		public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
				String contextHash, Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit,
				int exampleLimit) {
			this(projectId, projectVersionId, catalogHash, query, contextHash, additionalPhysicalTables, requiredHints,
					recallLimit, exampleLimit, null);
		}

		public PlanningRequest(Long projectId, Long projectVersionId, String catalogHash, String query,
				Collection<String> additionalPhysicalTables, QueryCaseHints requiredHints, int recallLimit, int exampleLimit) {
			this(projectId, projectVersionId, catalogHash, query, null, additionalPhysicalTables, requiredHints, recallLimit,
					exampleLimit, null);
		}

		public PlanningRequest {
			additionalPhysicalTables = List.copyOf(additionalPhysicalTables == null ? List.of() : additionalPhysicalTables);
			requiredHints = requiredHints == null ? QueryCaseHints.empty() : requiredHints;
			recallLimit = Math.max(1, recallLimit);
			exampleLimit = Math.max(1, exampleLimit);
		}

		public String effectiveRetrievalQuery() {
			return StringUtils.hasText(retrievalQuery) ? retrievalQuery.trim() : query;
		}
	}

	public record PlanningResult(SemanticBlueprint plan, SemanticCandidateSet candidateSet,
			QueryCaseHints historicalHints, QueryCaseHints binding, PlanningTrace trace) {
	}

	public record PlanningTrace(String planningId, String catalogHash, Set<String> candidatePhysicalTables,
			int retrievalHitCount, int candidateModelCount, int candidateMetricCount, int candidateDimensionCount,
			long recallMs, long candidateBuildMs, long historicalExampleRecallMs, long modelBindingMs,
			long planResolutionMs, long totalMs, int modelCallCount, boolean nativeReasoningUsed) {
		public PlanningTrace(String planningId, String catalogHash, Set<String> candidatePhysicalTables,
				int retrievalHitCount, int candidateModelCount, int candidateMetricCount, int candidateDimensionCount,
				long recallMs, long candidateBuildMs, long historicalExampleRecallMs, long modelBindingMs,
				long planResolutionMs, long totalMs) {
			this(planningId, catalogHash, candidatePhysicalTables, retrievalHitCount, candidateModelCount,
					candidateMetricCount, candidateDimensionCount, recallMs, candidateBuildMs, historicalExampleRecallMs,
					modelBindingMs, planResolutionMs, totalMs, 0, false);
		}

		public PlanningTrace {
			candidatePhysicalTables = Set.copyOf(candidatePhysicalTables == null ? Set.of() : candidatePhysicalTables);
		}
	}
}
