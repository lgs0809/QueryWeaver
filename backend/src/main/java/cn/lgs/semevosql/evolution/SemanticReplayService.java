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
package cn.lgs.semevosql.evolution;

import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.CandidateStatus;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.Mutation;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.ChangeSet;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.Status;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.QueryCaseHints.EnumBindingHint;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintGenerationService;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.semantic.compiler.CompiledSemanticQuery;
import cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler;
import cn.lgs.semevosql.semantic.compiler.SqlDialect;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticIssueType;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.DatabaseUtil;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Runs affected-case and representative regression against an evolved Draft. */
@Service
@RequiredArgsConstructor
public class SemanticReplayService {

	private static final int MAX_CASES = 200;

	private final JdbcTemplate jdbc;

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticCatalogApplicationService catalogApplicationService;

	private final SemanticPatchApplicationService patchApplicationService;

	private final SemanticChangeSetApplicationService changeSetService;

	private final SemanticBlueprintGenerationService semanticPlanningService;

	private final SemanticSqlCompiler sqlCompiler;

	private final DatabaseUtil databaseUtil;

	private final SemanticReplayExecutor replayExecutor;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	private final VersionedJson versionedJson = new VersionedJson();

	private final ThreadLocal<ReplayProgress> activeProgress = new ThreadLocal<>();

	private final ThreadLocal<ReplayExecutionContext> activeExecution = new ThreadLocal<>();

	private final ThreadLocal<GoldenReplayContext> activeGoldenContext = new ThreadLocal<>();

	private final ThreadLocal<ReplayTargetContext> activeTarget = new ThreadLocal<>();

	private final GoldenReplayResultValidator goldenResultValidator;

	private final ReplayDatasetVersionResolver datasetVersionResolver;

	private final SemanticEvolutionStateMachine stateMachine;

	public ImpactPreview previewImpact(String candidateId) {
		Map<String, Object> candidate = one("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ?", candidateId);
		Long sourceVersionId = number(candidate.get("source_version_id"));
		Set<String> affected = affectedCaseIds(text(candidate.get("patch_json")));
		List<Map<String, Object>> selected = selectedCases(candidate, sourceVersionId);
		long directAffected = selected.stream().filter(value -> affected.contains(text(value.get("id")))).count();
		return new ImpactPreview(candidateId, affected.size(), directAffected, selected.size() - directAffected,
				selected.size(), MAX_CASES);
	}

	public ChangeSetReplaySummary replayChangeSet(String changeSetId) {
		ChangeSet changeSet = changeSetService.get(changeSetId);
		if (changeSet.status() != Status.REPLAYING) {
			throw new IllegalStateException("ChangeSet replay requires REPLAYING status; actual=" + changeSet.status());
		}
		SemanticPatch patch = changeSetService.semanticPatch(changeSetId);
		Long projectId = changeSet.projectId();
		Long sourceVersionId = changeSet.baseSemanticVersionId();
		SemanticCatalogSnapshot targetCatalog = patchApplicationService.previewPatch(projectId, sourceVersionId, patch);
		String replayExecutionId = StringUtils.hasText(changeSet.replayRunId()) ? changeSet.replayRunId()
				: UUID.randomUUID().toString();
		boolean planningPolicyReplay = planningPolicyPatch(patch, targetCatalog);
		List<Map<String, Object>> cases = selectedCases(sourceVersionId, patch);
		int goldenTotal = jdbc.queryForObject(
				"SELECT COUNT(*) FROM qw_golden_case WHERE project_id = ? AND enabled = TRUE", Integer.class, projectId);
		ReplayProgress progress = ReplayProgress.noop();
		progress.total(cases.size() + goldenTotal);
		activeProgress.set(progress);
		activeExecution.set(new ReplayExecutionContext(replayExecutionId));
		activeTarget.set(new ReplayTargetContext(changeSetId, null, sourceVersionId, targetCatalog));
		try {
			int passed = 0;
			int failed = 0;
			int reviewRequired = 0;
			int independentPassed = 0;
			List<Map<String, Object>> failures = new ArrayList<>();
			for (Map<String, Object> queryCase : cases) {
				String caseId = text(queryCase.get("id"));
				progress.caseStarted(caseId, "ASSET");
				CaseReplay replay = existingReplay(null, caseId, "EXECUTION")
					.orElseGet(() -> replayCase(null, projectId, sourceVersionId, targetCatalog, queryCase,
							planningPolicyReplay));
				progress.caseCompleted(caseId);
				if (replay.failed()) {
					failed++;
					failures.add(failure(replay.caseId(), replay.failedLevel(), replay.message()));
				}
				else if (replay.reviewRequired()) {
					reviewRequired++;
					failures.add(failure(replay.caseId(), "SQL", "Constrained SQL requires reviewed replay"));
				}
				else {
					passed++;
					if (independentQueryCase(changeSet, queryCase)) {
						independentPassed++;
					}
				}
			}
			GoldenReplay golden = replayGoldenCases(null, projectId, sourceVersionId, targetCatalog,
					planningPolicyReplay);
			passed += golden.passed();
			failed += golden.failed();
			reviewRequired += golden.reviewRequired();
			independentPassed += golden.passed();
			failures.addAll(golden.failures());
			boolean allPassed = failed == 0 && reviewRequired == 0;
			int evaluated = cases.size() + golden.total();
			boolean broadReplay = evaluated >= 10;
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("semanticChangeSetId", changeSetId);
			summary.put("replayExecutionId", replayExecutionId);
			summary.put("queryCases", cases.size());
			summary.put("goldenCases", golden.total());
			summary.put("passed", passed);
			summary.put("failed", failed);
			summary.put("reviewRequired", reviewRequired);
			summary.put("independentEvidenceCount", independentPassed);
			summary.put("broadReplay", broadReplay);
			summary.put("failures", failures);
			persistChangeSetReplaySummary(changeSetId, replayExecutionId, summary);
			return new ChangeSetReplaySummary(changeSetId, replayExecutionId, evaluated, passed, failed, reviewRequired,
					independentPassed, broadReplay, allPassed, List.copyOf(failures), Map.copyOf(summary));
		}
		finally {
			activeProgress.remove();
			activeExecution.remove();
			activeTarget.remove();
		}
	}

	public ReplaySummary replayCandidate(String candidateId) {
		Map<String, Object> candidate = one("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ?", candidateId);
		String currentStatus = text(candidate.get("status"));
		if (!List.of("PATCH_APPLIED", "REPLAY_FAILED").contains(currentStatus)) {
			throw new IllegalStateException("Replay requires PATCH_APPLIED or REPLAY_FAILED; current=" + currentStatus);
		}
		String replayExecutionId = UUID.randomUUID().toString();
		stateMachine.transition(candidateId, CandidateStatus.valueOf(currentStatus), number(candidate.get("revision")),
				CandidateStatus.REPLAY_RUNNING, Mutation.none());
		return resumeCandidate(candidateId, ReplayProgress.noop(), replayExecutionId);
	}

	/**
	 * Resumes a persisted replay without taking a long transaction or Candidate row lock.
	 * Every replay-level result is an independent checkpoint.
	 */
	public ReplaySummary resumeCandidate(String candidateId, ReplayProgress progress) {
		return resumeCandidate(candidateId, progress, UUID.randomUUID().toString());
	}

	public ReplaySummary resumeCandidate(String candidateId, ReplayProgress progress, String replayExecutionId) {
		if (!StringUtils.hasText(replayExecutionId)) {
			throw new IllegalArgumentException("replayExecutionId is required");
		}
		Map<String, Object> candidate = one("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ?", candidateId);
		if (!"REPLAY_RUNNING".equals(text(candidate.get("status")))) {
			throw new IllegalStateException("Replay resume requires REPLAY_RUNNING candidate");
		}
		Long projectId = number(candidate.get("project_id"));
		Long sourceVersionId = number(candidate.get("source_version_id"));
		String changeSetId = text(candidate.get("semantic_change_set_id"));
		boolean changeSetReplay = StringUtils.hasText(changeSetId);
		Long targetVersionId = changeSetReplay ? sourceVersionId : number(candidate.get("target_draft_version_id"));
		Long persistedTargetVersionId = changeSetReplay ? null : targetVersionId;
		SemanticCatalogSnapshot targetCatalog = changeSetReplay
				? patchApplicationService.previewPatch(projectId, sourceVersionId, parsePatch(text(candidate.get("patch_json"))))
				: catalogRepository.loadCatalog(projectId, targetVersionId);
		boolean planningPolicyReplay = planningPolicyCandidate(candidate);
		List<Map<String, Object>> cases = selectedCases(candidate, sourceVersionId);
		int goldenTotal = jdbc.queryForObject(
				"SELECT COUNT(*) FROM qw_golden_case WHERE project_id = ? AND enabled = TRUE", Integer.class,
				projectId);
		progress.total(cases.size() + goldenTotal);
		activeProgress.set(progress);
		activeExecution.set(new ReplayExecutionContext(replayExecutionId));
		activeTarget.set(new ReplayTargetContext(changeSetId, persistedTargetVersionId, targetVersionId, targetCatalog));
		try {
			int passed = 0;
			int failed = 0;
			int reviewRequired = 0;
			List<Map<String, Object>> failures = new ArrayList<>();
			for (Map<String, Object> queryCase : cases) {
				checkCancelled(progress);
				String caseId = text(queryCase.get("id"));
				progress.caseStarted(caseId, "ASSET");
				CaseReplay replay = existingReplay(candidateId, caseId, "EXECUTION")
					.orElseGet(() -> replayCase(candidateId, projectId, targetVersionId, targetCatalog, queryCase,
							planningPolicyReplay));
				progress.caseCompleted(caseId);
				if (replay.failed()) {
					failed++;
					failures.add(failure(replay.caseId(), replay.failedLevel(), replay.message()));
				}
				else if (replay.reviewRequired()) {
					reviewRequired++;
					failures.add(failure(replay.caseId(), "SQL", "Constrained SQL requires reviewed replay"));
				}
				else {
					passed++;
				}
			}
			GoldenReplay golden = replayGoldenCases(candidateId, projectId, targetVersionId, targetCatalog,
					planningPolicyReplay);
			passed += golden.passed();
			failed += golden.failed();
			reviewRequired += golden.reviewRequired();
			failures.addAll(golden.failures());
			boolean allPassed = failed == 0 && reviewRequired == 0;
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("queryCases", cases.size());
			summary.put("goldenCases", golden.total());
			summary.put("passed", passed);
			summary.put("failed", failed);
			summary.put("reviewRequired", reviewRequired);
			summary.put("failures", failures);
			summary.put("targetVersionId", persistedTargetVersionId);
			summary.put("semanticChangeSetId", changeSetReplay ? changeSetId : null);
			summary.put("replayExecutionId", replayExecutionId);
			String summaryJson = versionedJson.write(JsonPayloadRegistry.REPLAY_SUMMARY, summary);
			SemanticEvolutionStateMachine.CandidateState running = stateMachine.state(candidateId);
			if (running.status() != CandidateStatus.REPLAY_RUNNING) {
				throw new ReplayCancelledException("Replay Candidate changed before completion");
			}
			stateMachine.transition(candidateId, CandidateStatus.REPLAY_RUNNING, running.revision(),
					allPassed ? CandidateStatus.REPLAY_PASSED : CandidateStatus.REPLAY_FAILED,
					Mutation.replayCompleted(summaryJson));
			return new ReplaySummary(candidateId, replayExecutionId, persistedTargetVersionId,
					cases.size() + golden.total(), passed, failed, reviewRequired, allPassed, List.copyOf(failures));
		}
		finally {
			activeProgress.remove();
			activeExecution.remove();
			activeTarget.remove();
		}
	}

	public List<Map<String, Object>> results(String candidateId) {
		return jdbc.queryForList("""
				SELECT * FROM qw_semantic_replay_result WHERE candidate_id = ? ORDER BY case_id, replay_level
				""", candidateId);
	}

	private CaseReplay replayCase(String candidateId, Long projectId, Long targetVersionId,
			SemanticCatalogSnapshot targetCatalog, Map<String, Object> queryCase, boolean planningPolicyReplay) {
		String caseId = text(queryCase.get("id"));
		List<Map<String, Object>> refs = jdbc.queryForList("""
				SELECT asset_type, asset_key FROM qw_query_example_asset_ref
				WHERE query_example_id = ? ORDER BY asset_type, asset_key
				""", caseId);
		List<String> missing = missingAssets(targetCatalog, refs);
		persist(candidateId, targetVersionId, caseId, "ASSET", missing.isEmpty() ? "PASSED" : "FAILED",
				Map.of("referenceCount", refs.size()), Map.of("missingAssets", missing), nullIfEmpty(missing));
		if (!missing.isEmpty()) {
			return CaseReplay.failed(caseId, "ASSET", "Missing assets: " + String.join(",", missing));
		}

		SemanticBlueprint sourcePlan = readPlan(text(queryCase.get("typed_ir_json"))).orElse(null);
		if (sourcePlan == null) {
			persist(candidateId, targetVersionId, caseId, "IR", "REVIEW_REQUIRED", Map.of(),
					Map.of("reason", "Historical case has no Semantic Blueprint"), null);
			return CaseReplay.reviewRequired(caseId);
		}
		SemanticBlueprint targetPlan;
		try {
			List<String> tables = sourcePlan.getModels()
				.stream()
				.map(SemanticBlueprint.ModelSelection::getPhysicalTable)
				.filter(StringUtils::hasText)
				.toList();
			QueryCaseHints replayHints = planningPolicyReplay
					? semanticPlanningService.plan(projectId, targetVersionId, text(queryCase.get("normalized_question")), tables,
							List.of(), QueryCaseHints.empty(), QueryCaseHints.empty())
					: hints(sourcePlan, caseId);
			targetPlan = buildBlueprintForReplay(projectId, targetVersionId, targetCatalog,
					text(queryCase.get("normalized_question")), tables, replayHints);
		}
		catch (RuntimeException ex) {
			persist(candidateId, targetVersionId, caseId, "IR", "FAILED", planShape(sourcePlan), Map.of(),
					ex.getMessage());
			return CaseReplay.failed(caseId, "IR", ex.getMessage());
		}
		List<String> irErrors = planningPolicyReplay ? comparePlanningPolicyPlans(sourcePlan, targetPlan)
				: comparePlans(sourcePlan, targetPlan);
		persist(candidateId, targetVersionId, caseId, "IR", irErrors.isEmpty() ? "PASSED" : "FAILED",
				planShape(sourcePlan), Map.of("target", planShape(targetPlan), "errors", irErrors),
				nullIfEmpty(irErrors));
		if (!irErrors.isEmpty()) {
			return CaseReplay.failed(caseId, "IR", String.join("; ", irErrors));
		}
		if (!"DETERMINISTIC".equalsIgnoreCase(targetPlan.getCompilerMode())) {
			persist(candidateId, targetVersionId, caseId, "SQL", "REVIEW_REQUIRED",
					Map.of("compilerMode", targetPlan.getCompilerMode()), Map.of(), null);
			return CaseReplay.reviewRequired(caseId);
		}
		try {
			CompiledSemanticQuery compiled = sqlCompiler.compile(targetPlan, targetCatalog, dialects(targetPlan),
					Clock.systemUTC(), ZoneId.of("UTC"));
			persist(candidateId, targetVersionId, caseId, "SQL", "PASSED",
					Map.of("historicalSql", text(queryCase.get("sql_text"))),
					Map.of("sources", compiled.sources(), "compilerVersion", compiled.compilerVersion()), null);
			List<Map<String, Object>> proof = replayExecutor.execute(projectId, targetCatalog, targetPlan,
					compiled.sources(), cancellationKey());
			persist(candidateId, targetVersionId, caseId, "EXECUTION", "PASSED", Map.of(), Map.of("sources", proof),
					null);
			return CaseReplay.passed(caseId);
		}
		catch (RuntimeException ex) {
			persist(candidateId, targetVersionId, caseId, "EXECUTION", "FAILED", Map.of(), Map.of(), ex.getMessage());
			return CaseReplay.failed(caseId, "EXECUTION", ex.getMessage());
		}
	}

	private GoldenReplay replayGoldenCases(String candidateId, Long projectId, Long targetVersionId,
			SemanticCatalogSnapshot catalog, boolean planningPolicyReplay) {
		List<Map<String, Object>> cases = jdbc.queryForList(
				"SELECT * FROM qw_golden_case WHERE project_id = ? AND enabled = TRUE ORDER BY case_code", projectId);
		int passed = 0;
		int failed = 0;
		int reviewRequired = 0;
		List<Map<String, Object>> failures = new ArrayList<>();
		for (Map<String, Object> golden : cases) {
			ReplayProgress progress = activeProgress.get();
			checkCancelled(progress);
			String caseId = "golden:" + text(golden.get("id"));
			if (progress != null) {
				progress.caseStarted(caseId, "GOLDEN_ASSET");
			}
			GoldenReplayContext context = goldenContext(golden);
			activeGoldenContext.set(context);
			CaseReplay replay;
			try {
				replay = existingReplay(candidateId, caseId, "GOLDEN_EXECUTION")
					.orElseGet(() -> replayGoldenCase(candidateId, projectId, targetVersionId, catalog, golden,
							planningPolicyReplay));
			}
			finally {
				activeGoldenContext.remove();
			}
			if (progress != null) {
				progress.caseCompleted(caseId);
			}
			if (replay.failed()) {
				failed++;
				failures.add(failure(replay.caseId(), replay.failedLevel(), replay.message()));
			}
			else if (replay.reviewRequired()) {
				reviewRequired++;
				failures.add(failure(replay.caseId(), "GOLDEN_SQL", "Constrained SQL requires reviewed replay"));
			}
			else {
				passed++;
			}
		}
		return new GoldenReplay(cases.size(), passed, failed, reviewRequired, List.copyOf(failures));
	}

	private CaseReplay replayGoldenCase(String candidateId, Long projectId, Long targetVersionId,
			SemanticCatalogSnapshot catalog, Map<String, Object> golden, boolean planningPolicyReplay) {
		String caseId = "golden:" + text(golden.get("id"));
		Map<String, Object> expected = versionedJson.readMap(text(golden.get("expected_json")),
				JsonPayloadRegistry.GOLDEN_CASE_EXPECTED);
		GoldenReplayContext context = requireGoldenContext();
		String datasetError = validateDatasetContext(projectId, context, expected);
		if (StringUtils.hasText(datasetError)) {
			persist(candidateId, targetVersionId, caseId, "GOLDEN_DATASET", "FAILED", expected, Map.of("replayMode",
					context.mode().name(), "datasetVersion", Objects.toString(context.datasetVersion(), "")),
					datasetError);
			return CaseReplay.failed(caseId, "GOLDEN_DATASET", datasetError);
		}
		String expectedOutcome = expectedOutcome(expected);
		List<String> missing = missingExpected(catalog, expected);
		persist(candidateId, targetVersionId, caseId, "GOLDEN_ASSET", missing.isEmpty() ? "PASSED" : "FAILED", expected,
				Map.of("missingAssets", missing), nullIfEmpty(missing));
		if (!missing.isEmpty()) {
			return CaseReplay.failed(caseId, "GOLDEN_ASSET", String.join(",", missing));
		}
		SemanticBlueprint plan;
		try {
			Set<String> expectedModels = stringSet(expected.get("modelCodes"));
			List<String> tables = catalog.getModels()
				.stream()
				.filter(model -> expectedModels.contains(model.getModelCode()))
				.map(SemanticCatalogSnapshot.Model::getPhysicalTable)
				.filter(StringUtils::hasText)
				.toList();
			QueryCaseHints replayHints = planningPolicyReplay
					? semanticPlanningService.plan(projectId, targetVersionId, text(golden.get("question")), tables, List.of(),
							QueryCaseHints.empty(), QueryCaseHints.empty())
					: goldenHints(expected, caseId);
			plan = buildBlueprintForReplay(projectId, targetVersionId, catalog, text(golden.get("question")), tables,
					replayHints);
		}
		catch (RuntimeException ex) {
			if (expectedPlanFailure(expectedOutcome, ex)) {
				persist(candidateId, targetVersionId, caseId, "GOLDEN_IR", "PASSED", expected,
						Map.of("expectedOutcome", expectedOutcome, "observedError", message(ex)), null);
				return CaseReplay.passed(caseId);
			}
			persist(candidateId, targetVersionId, caseId, "GOLDEN_IR", "FAILED", expected, Map.of(), ex.getMessage());
			return CaseReplay.failed(caseId, "GOLDEN_IR", ex.getMessage());
		}
		if ("REQUIRE_CLARIFICATION".equals(expectedOutcome) && !plan.isExecutable()) {
			String expectedType = text(expected.get("expectedClarificationType"));
			String observed = String.join(";", plan.getValidationErrors());
			if (StringUtils.hasText(expectedType)
					&& !observed.toUpperCase(Locale.ROOT).contains(expectedType.toUpperCase(Locale.ROOT))) {
				String error = "Clarification type did not match expectedClarificationType " + expectedType;
				persist(candidateId, targetVersionId, caseId, "GOLDEN_IR", "FAILED", expected,
						Map.of("validationErrors", plan.getValidationErrors()), error);
				return CaseReplay.failed(caseId, "GOLDEN_IR", error);
			}
			persist(candidateId, targetVersionId, caseId, "GOLDEN_IR", "PASSED", expected,
					Map.of("expectedOutcome", expectedOutcome, "validationErrors", plan.getValidationErrors()), null);
			return CaseReplay.passed(caseId);
		}
		if (Set.of("REQUIRE_CLARIFICATION", "OUT_OF_SCOPE", "PERMISSION_DENIED").contains(expectedOutcome)
				&& plan.isExecutable()) {
			String error = "Expected outcome " + expectedOutcome + " was not observed; executable IR is fail-closed";
			persist(candidateId, targetVersionId, caseId, "GOLDEN_IR", "FAILED", expected,
					Map.of("plan", planShape(plan)), error);
			return CaseReplay.failed(caseId, "GOLDEN_IR", error);
		}
		List<String> planErrors = compareGoldenPlan(expected, plan);
		persist(candidateId, targetVersionId, caseId, "GOLDEN_IR", planErrors.isEmpty() ? "PASSED" : "FAILED", expected,
				Map.of("plan", planShape(plan), "errors", planErrors), nullIfEmpty(planErrors));
		if (!planErrors.isEmpty()) {
			return CaseReplay.failed(caseId, "GOLDEN_IR", String.join("; ", planErrors));
		}
		if (!"DETERMINISTIC".equalsIgnoreCase(plan.getCompilerMode())) {
			if (Set.of("REQUIRE_REVIEW", "CONSTRAINED_GENERATION_REQUIRED").contains(expectedOutcome)) {
				persist(candidateId, targetVersionId, caseId, "GOLDEN_SQL", "PASSED", expected,
						Map.of("compilerMode", plan.getCompilerMode(), "expectedOutcome", expectedOutcome), null);
				return CaseReplay.passed(caseId);
			}
			persist(candidateId, targetVersionId, caseId, "GOLDEN_SQL", "REVIEW_REQUIRED",
					Map.of("compilerMode", plan.getCompilerMode()), Map.of(), null);
			return CaseReplay.reviewRequired(caseId);
		}
		if (Set.of("REQUIRE_REVIEW", "CONSTRAINED_GENERATION_REQUIRED").contains(expectedOutcome)) {
			String error = "Expected outcome " + expectedOutcome
					+ " was not observed; deterministic compilation is not accepted as the expected review gate";
			persist(candidateId, targetVersionId, caseId, "GOLDEN_SQL", "FAILED", expected,
					Map.of("compilerMode", plan.getCompilerMode()), error);
			return CaseReplay.failed(caseId, "GOLDEN_SQL", error);
		}
		try {
			CompiledSemanticQuery compiled = sqlCompiler.compile(plan, catalog, dialects(plan), Clock.systemUTC(),
					ZoneId.of("UTC"));
			persist(candidateId, targetVersionId, caseId, "GOLDEN_SQL", "PASSED", expected,
					Map.of("sources", compiled.sources(), "compilerVersion", compiled.compilerVersion()), null);
			SemanticReplayExecutor.ReplayExecution execution = replayExecutor.executeDetailed(projectId, catalog, plan,
					compiled.sources(), cancellationKey());
			if (!"SUCCEED".equals(expectedOutcome)) {
				String error = "Expected outcome " + expectedOutcome + " but replay succeeded";
				persist(candidateId, targetVersionId, caseId, "GOLDEN_EXECUTION", "FAILED", expected,
						Map.of("sourcesAndMerge", execution.proof()), error);
				return CaseReplay.failed(caseId, "GOLDEN_EXECUTION", error);
			}
			GoldenReplayResultValidator.AssertionReport assertions = goldenResultValidator.validate(expected,
					execution.finalResult(), execution.latencyMs(), execution.estimatedRows(), context.mode());
			Map<String, Object> executionProof = new LinkedHashMap<>();
			executionProof.put("sourcesAndMerge", execution.proof());
			executionProof.put("assertions", assertions.proof());
			executionProof.put("warnings", execution.warnings());
			persist(candidateId, targetVersionId, caseId, "GOLDEN_EXECUTION", assertions.passed() ? "PASSED" : "FAILED",
					expected, executionProof, assertions.passed() ? null : String.join("; ", assertions.errors()));
			if (!assertions.passed()) {
				return CaseReplay.failed(caseId, "GOLDEN_EXECUTION", String.join("; ", assertions.errors()));
			}
			return CaseReplay.passed(caseId);
		}
		catch (RuntimeException ex) {
			if (expectedExecutionFailure(expectedOutcome, expected, ex)) {
				persist(candidateId, targetVersionId, caseId, "GOLDEN_EXECUTION", "PASSED", expected,
						Map.of("expectedOutcome", expectedOutcome, "observedError", message(ex)), null);
				return CaseReplay.passed(caseId);
			}
			persist(candidateId, targetVersionId, caseId, "GOLDEN_EXECUTION", "FAILED", expected, Map.of(),
					ex.getMessage());
			return CaseReplay.failed(caseId, "GOLDEN_EXECUTION", ex.getMessage());
		}
	}

	private boolean planningPolicyCandidate(Map<String, Object> candidate) {
		String candidateType = text(candidate.get("candidate_type"));
		return (SemanticIssueType.PLANNING_POLICY_GAP.name().equals(candidateType)
				|| "USER_PLANNING_CORRECTION".equals(candidateType))
				&& "RULE".equalsIgnoreCase(text(candidate.get("asset_type")));
	}

	private boolean planningPolicyPatch(SemanticPatch patch, SemanticCatalogSnapshot targetCatalog) {
		for (SemanticPatch.Operation operation : patch.operations()) {
			if (!"RULE".equalsIgnoreCase(operation.assetType())) {
				continue;
			}
			String explicitType = Objects.toString(operation.values().get("ruleType"), "");
			if ("PLANNING_POLICY".equalsIgnoreCase(explicitType)) {
				return true;
			}
			boolean existingPlanningRule = targetCatalog.getRules().stream()
				.filter(rule -> Objects.equals(rule.getRuleCode(), operation.assetKey()))
				.anyMatch(rule -> "PLANNING_POLICY".equalsIgnoreCase(rule.getRuleType()));
			if (existingPlanningRule) {
				return true;
			}
		}
		return false;
	}

	private boolean independentQueryCase(ChangeSet changeSet, Map<String, Object> queryCase) {
		return !"EPISODE".equalsIgnoreCase(changeSet.originType())
				|| !Objects.equals(changeSet.originRef(), text(queryCase.get("episode_id")));
	}

	private void persistChangeSetReplaySummary(String changeSetId, String replayExecutionId,
			Map<String, Object> summary) {
		jdbc.update("""
				UPDATE qw_semantic_change_set
				SET replay_run_id = ?, replay_summary_json = CAST(? AS JSONB), update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status = 'REPLAYING'
				""", replayExecutionId, json(summary), changeSetId);
	}

	private List<Map<String, Object>> selectedCases(Map<String, Object> candidate, Long sourceVersionId) {
		return selectedCases(sourceVersionId, parsePatch(text(candidate.get("patch_json"))));
	}

	private List<Map<String, Object>> selectedCases(Long sourceVersionId, SemanticPatch patch) {
		List<Map<String, Object>> all = jdbc.queryForList("""
				SELECT * FROM qw_query_example WHERE project_version_id = ? AND status = 'APPROVED'
				ORDER BY reviewed_time DESC, create_time DESC LIMIT ?
				""", sourceVersionId, MAX_CASES);
		Set<String> affected = affectedCaseIds(patch);
		Map<String, Map<String, Object>> selected = new LinkedHashMap<>();
		all.stream()
			.filter(value -> affected.contains(text(value.get("id"))))
			.forEach(value -> selected.put(text(value.get("id")), value));
		Set<String> representativeShapes = new LinkedHashSet<>();
		for (Map<String, Object> value : all) {
			String shape = text(value.get("canonical_shape_hash"));
			if (representativeShapes.add(shape)) {
				selected.putIfAbsent(text(value.get("id")), value);
			}
		}
		return selected.values().stream().limit(MAX_CASES).toList();
	}

	private Set<String> affectedCaseIds(String patchJson) {
		try {
			return affectedCaseIds(versionedJson.read(patchJson, JsonPayloadRegistry.SEMANTIC_PATCH,
					SemanticPatch.class));
		}
		catch (Exception ex) {
			return Set.of();
		}
	}

	private Set<String> affectedCaseIds(SemanticPatch patch) {
		Set<String> ids = new LinkedHashSet<>();
		for (SemanticPatch.Operation operation : patch.operations()) {
			ids.addAll(jdbc.queryForList("""
					SELECT query_example_id FROM qw_query_example_asset_ref
					WHERE asset_type = ? AND asset_key = ?
					""", String.class, operation.assetType().toUpperCase(), operation.assetKey()));
		}
		return Set.copyOf(ids);
	}

	private QueryCaseHints goldenHints(Map<String, Object> expected, String caseId) {
		return new QueryCaseHints(stringSet(expected.get("modelCodes")), stringSet(expected.get("metricCodes")),
				stringSet(expected.get("dimensionCodes")), stringSet(expected.get("grainCodes")),
				stringSet(expected.get("relationshipCodes")), stringSet(expected.get("ruleCodes")),
				expectedEnumBindings(expected.get("enumBindings"), caseId), text(expected.get("intentType")),
				List.of(caseId), 1, Map.of("golden", 1d));
	}

	private List<String> compareGoldenPlan(Map<String, Object> expected, SemanticBlueprint plan) {
		List<String> errors = new ArrayList<>(plan.getValidationErrors());
		requireContained(errors, "model", new ArrayList<>(stringSet(expected.get("modelCodes"))),
				plan.getModels()
					.stream()
					.map(SemanticBlueprint.ModelSelection::getModelCode)
					.collect(Collectors.toSet()));
		requireContained(errors, "metric", new ArrayList<>(stringSet(expected.get("metricCodes"))),
				plan.getMetrics()
					.stream()
					.map(SemanticBlueprint.MetricSelection::getMetricCode)
					.collect(Collectors.toSet()));
		requireContained(errors, "dimension", new ArrayList<>(stringSet(expected.get("dimensionCodes"))),
				plan.getDimensions()
					.stream()
					.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
					.collect(Collectors.toSet()));
		requireContained(errors, "relationship", new ArrayList<>(stringSet(expected.get("relationshipCodes"))),
				plan.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.collect(Collectors.toSet()));
		for (EnumBindingHint binding : expectedEnumBindings(expected.get("enumBindings"), "golden")) {
			boolean found = plan.getEnumResolutions()
				.stream()
				.anyMatch(actual -> Objects.equals(binding.modelCode(), actual.getModelCode())
						&& Objects.equals(binding.columnName(), actual.getColumnName())
						&& Objects.equals(binding.valueCode(), actual.getValueCode()));
			if (!found) {
				errors.add("Missing enum binding: " + binding.modelCode() + ":" + binding.columnName() + ":"
						+ binding.valueCode());
			}
		}
		if (!plan.isExecutable()) {
			errors.add("Golden IR is not executable");
		}
		return List.copyOf(errors);
	}

	private Set<String> stringSet(Object value) {
		if (!(value instanceof List<?> values)) {
			return Set.of();
		}
		return values.stream()
			.map(item -> Objects.toString(item, "").trim())
			.filter(StringUtils::hasText)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private List<EnumBindingHint> expectedEnumBindings(Object value, String caseId) {
		if (!(value instanceof List<?> values)) {
			return List.of();
		}
		List<EnumBindingHint> result = new ArrayList<>();
		for (Object item : values) {
			if (!(item instanceof Map<?, ?> binding)) {
				continue;
			}
			result.add(new EnumBindingHint(text(binding.get("rawText")), text(binding.get("modelCode")),
					text(binding.get("columnName")), text(binding.get("valueCode")), caseId, 1));
		}
		return List.copyOf(result);
	}

	private List<String> comparePlans(SemanticBlueprint source, SemanticBlueprint target) {
		List<String> errors = new ArrayList<>(target.getValidationErrors());
		requireContained(errors, "metric",
				source.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).toList(),
				target.getMetrics()
					.stream()
					.map(SemanticBlueprint.MetricSelection::getMetricCode)
					.collect(Collectors.toSet()));
		requireContained(errors, "dimension",
				source.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode).toList(),
				target.getDimensions()
					.stream()
					.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
					.collect(Collectors.toSet()));
		requireContained(errors, "relationship",
				source.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.toList(),
				target.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.collect(Collectors.toSet()));
		for (SemanticBlueprint.EnumResolution expected : source.getEnumResolutions()) {
			boolean found = target.getEnumResolutions()
				.stream()
				.anyMatch(actual -> Objects.equals(expected.getModelCode(), actual.getModelCode())
						&& Objects.equals(expected.getColumnName(), actual.getColumnName())
						&& Objects.equals(expected.getValueCode(), actual.getValueCode()));
			if (!found) {
				errors.add("Missing enum binding: " + expected.getModelCode() + ":" + expected.getColumnName() + ":"
						+ expected.getValueCode());
			}
		}
		return List.copyOf(errors);
	}

	private List<String> comparePlanningPolicyPlans(SemanticBlueprint source, SemanticBlueprint target) {
		List<String> errors = new ArrayList<>(target.getValidationErrors());
		Map<String, Object> expected = planningPolicyShape(source);
		Map<String, Object> actual = planningPolicyShape(target);
		for (Map.Entry<String, Object> entry : expected.entrySet()) {
			Object observed = actual.get(entry.getKey());
			if (!Objects.equals(entry.getValue(), observed)) {
				errors.add("Planning policy changed " + entry.getKey() + ": expected=" + entry.getValue() + ", actual="
						+ observed);
			}
		}
		return List.copyOf(errors);
	}

	private Map<String, Object> planningPolicyShape(SemanticBlueprint plan) {
		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("models", plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).sorted().toList());
		shape.put("metrics", plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).sorted().toList());
		shape.put("dimensions",
				plan.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode).sorted().toList());
		shape.put("grains", plan.getGrains().stream().map(SemanticBlueprint.GrainSelection::getGrainCode).sorted().toList());
		shape.put("relationships", plan.getRelationships().stream()
			.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode).sorted().toList());
		shape.put("rules", plan.getRules().stream().map(SemanticBlueprint.RuleSelection::getRuleCode).sorted().toList());
		shape.put("enumResolutions", plan.getEnumResolutions().stream()
			.map(value -> Objects.toString(value.getModelCode(), "") + ":" + Objects.toString(value.getColumnName(), "")
					+ ":" + Objects.toString(value.getValueCode(), ""))
			.sorted().toList());
		shape.put("filters", plan.getFilters().stream()
			.map(value -> Objects.toString(value.getModelCode(), "") + ":" + Objects.toString(value.getColumnName(), "")
					+ ":" + Objects.toString(value.getExpression(), "") + ":" + Objects.toString(value.getOperator(), "")
					+ ":" + Objects.toString(value.getValue(), "") + ":" + Objects.toString(value.getValueType(), ""))
			.sorted().toList());
		shape.put("groupBy", plan.getGroupBy().stream()
			.map(value -> Objects.toString(value.getModelCode(), "") + ":" + Objects.toString(value.getColumnName(), "")
					+ ":" + Objects.toString(value.getExpression(), "") + ":" + Objects.toString(value.getAlias(), "")
					+ ":" + Objects.toString(value.getTimeBucketGranularity(), ""))
			.toList());
		shape.put("orderBy", plan.getOrderBy().stream()
			.map(value -> Objects.toString(value.getExpression(), "") + ":" + Objects.toString(value.getDirection(), "")
					+ ":" + Objects.toString(value.getNulls(), ""))
			.toList());
		shape.put("projections", plan.getProjections().stream()
			.map(value -> Objects.toString(value.getModelCode(), "") + ":" + Objects.toString(value.getExpression(), "")
					+ ":" + Objects.toString(value.getAlias(), "") + ":" + Objects.toString(value.getProjectionType(), ""))
			.toList());
		shape.put("timeRange", replayTimeRangeShape(plan.getTimeRange()));
		shape.put("expectedResult", expectedResultShape(plan.getExpectedResult()));
		shape.put("compilerMode", Objects.toString(plan.getCompilerMode(), ""));
		shape.put("limit", plan.getLimit());
		return Map.copyOf(shape);
	}

	private Map<String, Object> replayTimeRangeShape(SemanticBlueprint.TimeRangeSelection value) {
		if (value == null) {
			return Map.of();
		}
		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("modelCode", Objects.toString(value.getModelCode(), ""));
		shape.put("timeColumn", Objects.toString(value.getTimeColumn(), ""));
		shape.put("relativeExpression", Objects.toString(value.getRelativeExpression(), ""));
		shape.put("granularity", Objects.toString(value.getGranularity(), ""));
		shape.put("timeZone", Objects.toString(value.getTimeZone(), ""));
		if (!StringUtils.hasText(value.getRelativeExpression())) {
			shape.put("startInclusive", Objects.toString(value.getStartInclusive(), ""));
			shape.put("endExclusive", Objects.toString(value.getEndExclusive(), ""));
		}
		return Map.copyOf(shape);
	}

	private Map<String, Object> expectedResultShape(SemanticBlueprint.ExpectedResultShape value) {
		if (value == null) {
			return Map.of();
		}
		return Map.of("columns", List.copyOf(value.getColumns() == null ? List.of() : value.getColumns()), "grain",
				Objects.toString(value.getGrain(), ""), "tabular", Objects.toString(value.getTabular(), ""), "chartable",
				Objects.toString(value.getChartable(), ""));
	}

	private void requireContained(List<String> errors, String type, List<String> required, Set<String> actual) {
		required.stream()
			.filter(value -> !actual.contains(value))
			.forEach(value -> errors.add("Missing " + type + ": " + value));
	}

	private QueryCaseHints hints(SemanticBlueprint plan, String caseId) {
		List<QueryCaseHints.FilterBindingHint> literalFilters = plan.getFilters()
			.stream()
			.filter(value -> "LITERAL".equalsIgnoreCase(value.getValueType()))
			.map(value -> new QueryCaseHints.FilterBindingHint("", value.getModelCode(), value.getColumnName(),
					value.getOperator(), value.getValue(), caseId, 1))
			.toList();
		QueryCaseHints.TimeBindingHint timeBinding = timeBinding(plan, caseId);
		return new QueryCaseHints(
				plan.getModels()
					.stream()
					.map(SemanticBlueprint.ModelSelection::getModelCode)
					.collect(Collectors.toSet()),
				plan.getMetrics()
					.stream()
					.map(SemanticBlueprint.MetricSelection::getMetricCode)
					.collect(Collectors.toSet()),
				plan.getDimensions()
					.stream()
					.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
					.collect(Collectors.toSet()),
				plan.getGrains()
					.stream()
					.map(SemanticBlueprint.GrainSelection::getGrainCode)
					.collect(Collectors.toSet()),
				plan.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.collect(Collectors.toSet()),
				plan.getRules().stream().map(SemanticBlueprint.RuleSelection::getRuleCode).collect(Collectors.toSet()),
				plan.getEnumResolutions()
					.stream()
					.map(value -> new EnumBindingHint(value.getInputText(), value.getModelCode(), value.getColumnName(),
							value.getValueCode(), caseId, 1))
					.toList(),
				literalFilters, List.of(), timeBinding, true, intent(plan), List.of(caseId), 1, Map.of());
	}

	private QueryCaseHints.TimeBindingHint timeBinding(SemanticBlueprint plan, String caseId) {
		SemanticBlueprint.GroupSelection timeGroup = plan.getGroupBy()
			.stream()
			.filter(group -> StringUtils.hasText(group.getTimeBucketGranularity()))
			.findFirst()
			.orElse(null);
		SemanticBlueprint.TimeRangeSelection timeRange = plan.getTimeRange();
		if (timeRange == null && timeGroup == null) {
			return null;
		}
		String modelCode = timeRange == null ? timeGroup.getModelCode() : timeRange.getModelCode();
		String columnName = timeRange == null ? timeGroup.getColumnName() : timeRange.getTimeColumn();
		String granularity = timeGroup == null ? null : timeGroup.getTimeBucketGranularity();
		return new QueryCaseHints.TimeBindingHint("", modelCode, columnName, caseId, 1, granularity);
	}

	private String intent(SemanticBlueprint plan) {
		if (plan.getSourceSubPlans().size() > 1) {
			return "MULTI_SOURCE_ANALYTICS";
		}
		if (!plan.getMetrics().isEmpty() && !plan.getDimensions().isEmpty()) {
			return "GROUPED_AGGREGATION";
		}
		return plan.getMetrics().isEmpty() ? "ENTITY_LOOKUP" : "AGGREGATION";
	}

	private Map<Integer, SqlDialect> dialects(SemanticBlueprint plan) {
		Map<Integer, SqlDialect> values = new LinkedHashMap<>();
		for (SemanticBlueprint.SourceSubPlan source : plan.getSourceSubPlans()) {
			if (source.getDatasourceId() != null) {
				values.put(source.getDatasourceId(),
						SqlDialect.from(databaseUtil.getDatasourceDbConfig(source.getDatasourceId()).getDialectType()));
			}
		}
		return Map.copyOf(values);
	}

	private List<String> missingAssets(SemanticCatalogSnapshot catalog, List<Map<String, Object>> refs) {
		Set<String> available = assetKeys(catalog);
		return refs.stream()
			.map(value -> text(value.get("asset_type")) + ":" + text(value.get("asset_key")))
			.filter(value -> !available.contains(value))
			.toList();
	}

	@SuppressWarnings("unchecked")
	private List<String> missingExpected(SemanticCatalogSnapshot catalog, Map<String, Object> expected) {
		List<String> missing = new ArrayList<>();
		checkExpected((List<Object>) expected.getOrDefault("modelCodes", List.of()),
				catalog.getModels()
					.stream()
					.map(SemanticCatalogSnapshot.Model::getModelCode)
					.collect(Collectors.toSet()),
				"MODEL", missing);
		checkExpected((List<Object>) expected.getOrDefault("metricCodes", List.of()),
				catalog.getMetrics()
					.stream()
					.map(SemanticCatalogSnapshot.Metric::getMetricCode)
					.collect(Collectors.toSet()),
				"METRIC", missing);
		checkExpected((List<Object>) expected.getOrDefault("dimensionCodes", List.of()),
				catalog.getDimensions()
					.stream()
					.map(SemanticCatalogSnapshot.Dimension::getDimensionCode)
					.collect(Collectors.toSet()),
				"DIMENSION", missing);
		return missing;
	}

	private void checkExpected(List<Object> expected, Set<String> actual, String type, List<String> missing) {
		expected.stream()
			.map(String::valueOf)
			.filter(value -> !actual.contains(value))
			.forEach(value -> missing.add(type + ":" + value));
	}

	private Set<String> assetKeys(SemanticCatalogSnapshot catalog) {
		Set<String> values = new LinkedHashSet<>();
		catalog.getModels().forEach(item -> values.add("MODEL:" + item.getModelCode()));
		catalog.getMetrics().forEach(item -> values.add("METRIC:" + item.getMetricCode()));
		catalog.getDimensions().forEach(item -> values.add("DIMENSION:" + item.getDimensionCode()));
		catalog.getRelationships().forEach(item -> values.add("RELATIONSHIP:" + item.getRelationshipCode()));
		catalog.getRules().forEach(item -> values.add("RULE:" + item.getRuleCode()));
		catalog.getGrains().forEach(item -> values.add("GRAIN:" + item.getModelCode() + ":" + item.getGrainCode()));
		catalog.getEnumValues()
			.forEach(item -> values
				.add("ENUM_VALUE:" + item.getModelCode() + ":" + item.getColumnName() + ":" + item.getValueCode()));
		return Set.copyOf(values);
	}

	private SemanticBlueprint buildBlueprintForReplay(Long projectId, Long policyVersionId,
			SemanticCatalogSnapshot snapshot, String question, List<String> tables, QueryCaseHints hints) {
		return catalogApplicationService.buildBlueprint(projectId, policyVersionId, snapshot, question, tables, hints);
	}

	private Map<String, Object> planShape(SemanticBlueprint plan) {
		return Map.of("metrics",
				plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).toList(), "dimensions",
				plan.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode).toList(),
				"relationships",
				plan.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.toList(),
				"compilerMode", plan.getCompilerMode(), "executable", plan.isExecutable());
	}

	private void persist(String candidateId, Long versionId, String caseId, String level, String status,
			Object baseline, Object candidate, String error) {
		persist(candidateId, versionId, caseId, level, status, baseline, candidate, error, null, null);
	}

	private void persist(String candidateId, Long versionId, String caseId, String level, String status,
			Object baseline, Object candidate, String error, String replayMode, String datasetVersion) {
		ReplayProgress progress = activeProgress.get();
		checkCancelled(progress);
		if (progress != null) {
			progress.levelStarted(caseId, level);
		}
		String executionId = replayExecutionId();
		GoldenReplayContext goldenContext = activeGoldenContext.get();
		String effectiveReplayMode = replayMode;
		String effectiveDatasetVersion = datasetVersion;
		if (goldenContext != null) {
			effectiveReplayMode = goldenContext.mode().name();
			effectiveDatasetVersion = goldenContext.datasetVersion();
		}
		Map<String, Object> proof = new LinkedHashMap<>();
		proof.put("deterministic", true);
		proof.put("level", level);
		proof.put("replayExecutionId", executionId);
		proof.put("replayMode", effectiveReplayMode);
		proof.put("datasetVersion", effectiveDatasetVersion);
		ReplayTargetContext target = activeTarget.get();
		Long persistedVersionId = target != null && StringUtils.hasText(target.changeSetId()) ? target.resultVersionId()
				: versionId;
		String changeSetId = target == null ? null : target.changeSetId();
		try {
			jdbc.update("""
					INSERT INTO qw_semantic_replay_result
					(id, candidate_id, replay_execution_id, target_version_id, change_set_id, case_id, replay_level,
					 replay_mode, dataset_version, status, baseline_json, candidate_json, proof_json,
					 error_message, create_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
					""", UUID.randomUUID().toString(), candidateId, executionId, persistedVersionId, changeSetId, caseId, level,
					effectiveReplayMode, effectiveDatasetVersion, status, json(baseline), json(candidate), json(proof),
					error);
		}
		catch (DuplicateKeyException ignored) {
			// Durable recovery reuses the same execution id and immutable checkpoint.
		}
		if (progress != null) {
			progress.levelCompleted(caseId, level, status);
		}
	}

	private Optional<CaseReplay> existingReplay(String candidateId, String caseId, String terminalLevel) {
		ReplayTargetContext target = activeTarget.get();
		List<Map<String, Object>> rows;
		if (!StringUtils.hasText(candidateId) && target != null && StringUtils.hasText(target.changeSetId())) {
			rows = jdbc.queryForList("""
					SELECT replay_level, status, error_message FROM qw_semantic_replay_result
					WHERE change_set_id = ? AND replay_execution_id = ? AND case_id = ?
					ORDER BY create_time, replay_level
					""", target.changeSetId(), replayExecutionId(), caseId);
		}
		else {
			rows = jdbc.queryForList("""
					SELECT replay_level, status, error_message FROM qw_semantic_replay_result
					WHERE candidate_id = ? AND replay_execution_id = ? AND case_id = ?
					ORDER BY create_time, replay_level
					""", candidateId, replayExecutionId(), caseId);
		}
		for (Map<String, Object> row : rows) {
			String status = text(row.get("status"));
			if ("FAILED".equals(status)) {
				return Optional
					.of(CaseReplay.failed(caseId, text(row.get("replay_level")), text(row.get("error_message"))));
			}
			if ("REVIEW_REQUIRED".equals(status)) {
				return Optional.of(CaseReplay.reviewRequired(caseId));
			}
			if (terminalLevel.equals(text(row.get("replay_level"))) && "PASSED".equals(status)) {
				return Optional.of(CaseReplay.passed(caseId));
			}
		}
		return Optional.empty();
	}

	private void checkCancelled(ReplayProgress progress) {
		if (progress != null && progress.cancelled()) {
			throw new ReplayCancelledException("Semantic replay cancellation requested");
		}
	}

	private String cancellationKey() {
		ReplayProgress progress = activeProgress.get();
		return progress == null ? "semantic-replay:" + UUID.randomUUID() : progress.cancellationKey();
	}

	private String replayExecutionId() {
		ReplayExecutionContext context = activeExecution.get();
		if (context == null || !StringUtils.hasText(context.replayExecutionId())) {
			throw new IllegalStateException("Semantic Replay execution context is unavailable");
		}
		return context.replayExecutionId();
	}

	private GoldenReplayContext goldenContext(Map<String, Object> golden) {
		GoldenReplayMode mode = GoldenReplayMode.from(golden.get("replay_mode"));
		String datasetVersion = text(golden.get("dataset_version"));
		return new GoldenReplayContext(mode, StringUtils.hasText(datasetVersion) ? datasetVersion : null);
	}

	private GoldenReplayContext requireGoldenContext() {
		GoldenReplayContext context = activeGoldenContext.get();
		if (context == null) {
			throw new IllegalStateException("Golden Replay context is unavailable");
		}
		return context;
	}

	private String validateDatasetContext(Long projectId, GoldenReplayContext context, Map<String, Object> expected) {
		if (context.mode() == GoldenReplayMode.LIVE) {
			return null;
		}
		if (!StringUtils.hasText(context.datasetVersion())) {
			return "FIXTURE Golden Case requires datasetVersion";
		}
		String expectedVersion = text(expected.get("datasetVersion"));
		if (StringUtils.hasText(expectedVersion) && !Objects.equals(expectedVersion, context.datasetVersion())) {
			return "FIXTURE expected_json datasetVersion does not match Golden Case datasetVersion";
		}
		try {
			datasetVersionResolver.requireMatch(projectId, context.datasetVersion());
			return null;
		}
		catch (IllegalStateException ex) {
			return ex.getMessage();
		}
	}

	private SemanticPatch parsePatch(String value) {
		return versionedJson.read(value, JsonPayloadRegistry.SEMANTIC_PATCH, SemanticPatch.class);
	}

	private Optional<SemanticBlueprint> readPlan(String value) {
		if (!StringUtils.hasText(value)) {
			return Optional.empty();
		}
		try {
			return Optional
				.of(versionedJson.read(value, JsonPayloadRegistry.SEMANTIC_QUERY_PLAN, SemanticBlueprint.class));
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}

	private Map<String, Object> readMap(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			return mapper.readValue(value, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid replay expectation JSON", ex);
		}
	}

	private Map<String, Object> one(String sql, Object... args) {
		List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
		if (rows.size() != 1) {
			throw new IllegalArgumentException("Expected one semantic evolution record");
		}
		return rows.get(0);
	}

	private Map<String, Object> failure(String caseId, String level, String message) {
		return Map.of("caseId", caseId, "level", level, "message", Objects.toString(message, ""));
	}

	private String nullIfEmpty(List<String> values) {
		return values.isEmpty() ? null : String.join("; ", values);
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value == null ? Map.of() : value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to encode replay evidence", ex);
		}
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private String expectedOutcome(Map<String, Object> expected) {
		String outcome = text(expected.get("expectedOutcome")).trim().toUpperCase(Locale.ROOT);
		if (!outcome.isEmpty()) {
			return outcome;
		}
		if (Boolean.TRUE.equals(expected.get("expectedReviewRequired"))) {
			return "REQUIRE_REVIEW";
		}
		if (StringUtils.hasText(text(expected.get("expectedClarificationType")))) {
			return "REQUIRE_CLARIFICATION";
		}
		return "SUCCEED";
	}

	private boolean expectedPlanFailure(String expectedOutcome, RuntimeException error) {
		String value = message(error).toUpperCase(Locale.ROOT);
		return switch (expectedOutcome) {
			case "REQUIRE_CLARIFICATION" -> containsAny(value, "CLARIF", "AMBIGU", "澄清", "歧义");
			case "OUT_OF_SCOPE" -> containsAny(value, "OUT_OF_SCOPE", "OUT OF SCOPE", "超出");
			case "PERMISSION_DENIED" -> containsAny(value, "PERMISSION", "DENIED", "FORBIDDEN", "权限");
			case "REQUIRE_REVIEW", "CONSTRAINED_GENERATION_REQUIRED" ->
				containsAny(value, "REVIEW", "CONSTRAINED", "审核");
			default -> false;
		};
	}

	private boolean expectedExecutionFailure(String expectedOutcome, Map<String, Object> expected,
			RuntimeException error) {
		if (!Set.of("REJECT_BY_GUARD", "PERMISSION_DENIED", "OUT_OF_SCOPE").contains(expectedOutcome)) {
			return false;
		}
		String expectedCode = text(expected.get("expectedErrorCode"));
		String observed = error.getClass().getSimpleName() + ":" + message(error);
		return !StringUtils.hasText(expectedCode)
				|| observed.toUpperCase(Locale.ROOT).contains(expectedCode.toUpperCase(Locale.ROOT));
	}

	private boolean containsAny(String value, String... tokens) {
		return java.util.Arrays.stream(tokens).anyMatch(value::contains);
	}

	private String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return Objects.toString(current.getMessage(), current.getClass().getSimpleName());
	}

	private record CaseReplay(String caseId, boolean failed, boolean reviewRequired, String failedLevel,
			String message) {
		static CaseReplay passed(String id) {
			return new CaseReplay(id, false, false, null, null);
		}

		static CaseReplay failed(String id, String level, String message) {
			return new CaseReplay(id, true, false, level, message);
		}

		static CaseReplay reviewRequired(String id) {
			return new CaseReplay(id, false, true, null, null);
		}
	}

	private record GoldenReplay(int total, int passed, int failed, int reviewRequired,
			List<Map<String, Object>> failures) {
	}

	private record ReplayExecutionContext(String replayExecutionId) {
	}

	private record ReplayTargetContext(String changeSetId, Long resultVersionId, Long policyVersionId,
			SemanticCatalogSnapshot snapshot) {
	}

	private record GoldenReplayContext(GoldenReplayMode mode, String datasetVersion) {
	}

	public record ImpactPreview(String candidateId, int referencedAffectedCases, long selectedDirectAffectedCases,
			long selectedRepresentativeCases, int totalSelectedCases, int maxCases) {
	}

	public record ReplaySummary(String candidateId, String replayExecutionId, Long targetVersionId, int total,
			int passed, int failed, int reviewRequired, boolean allPassed, List<Map<String, Object>> failures) {
	}

	public record ChangeSetReplaySummary(String changeSetId, String replayExecutionId, int total, int passed, int failed,
			int reviewRequired, int independentEvidenceCount, boolean broadReplay, boolean allPassed,
			List<Map<String, Object>> failures, Map<String, Object> summary) {
	}

	public interface ReplayProgress {

		default void total(int total) {
		}

		default void caseStarted(String caseId, String level) {
		}

		default void levelStarted(String caseId, String level) {
		}

		default void levelCompleted(String caseId, String level, String status) {
		}

		default void caseCompleted(String caseId) {
		}

		default boolean cancelled() {
			return false;
		}

		default String cancellationKey() {
			return "semantic-replay:" + UUID.randomUUID();
		}

		static ReplayProgress noop() {
			return new ReplayProgress() {
			};
		}

	}

	public static final class ReplayCancelledException extends RuntimeException {

		public ReplayCancelledException(String message) {
			super(message);
		}

	}

}
