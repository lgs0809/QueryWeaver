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
package cn.lgs.queryweaver.workflow.node;

import static cn.lgs.queryweaver.constant.Constant.*;
import static cn.lgs.queryweaver.prompt.PromptHelper.buildMixMacSqlDbPrompt;
import static cn.lgs.queryweaver.util.PlanProcessUtil.getCurrentExecutionStepInstruction;

import cn.lgs.queryweaver.dto.datasource.SqlRetryDto;
import cn.lgs.queryweaver.dto.prompt.SemanticConsistencyDTO;
import cn.lgs.queryweaver.dto.schema.SchemaDTO;
import cn.lgs.queryweaver.operations.SemanticCatalogCache;
import cn.lgs.queryweaver.review.PostExecutionReview.Decision;
import cn.lgs.queryweaver.review.QueryRepairPolicy;
import cn.lgs.queryweaver.review.QueryRepairPolicy.BudgetDecision;
import cn.lgs.queryweaver.review.QueryRepairPolicy.RepairBudget;
import cn.lgs.queryweaver.run.QueryRunService;
import cn.lgs.queryweaver.semantic.compiler.QueryPreflightException;
import cn.lgs.queryweaver.semantic.compiler.QueryPreflightService;
import cn.lgs.queryweaver.semantic.compiler.QueryPreflightService.PreflightResult;
import cn.lgs.queryweaver.semantic.domain.SemanticBlueprint;
import cn.lgs.queryweaver.service.nl2sql.Nl2SqlService;
import cn.lgs.queryweaver.sql.application.BlueprintSqlConstraintValidator;
import cn.lgs.queryweaver.sql.application.BlueprintSqlConstraintValidator.ValidationResult;
import cn.lgs.queryweaver.util.ChatResponseUtil;
import cn.lgs.queryweaver.util.FluxUtil;
import cn.lgs.queryweaver.util.JsonUtil;
import cn.lgs.queryweaver.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Semantic consistency validation node. LLM-generated Semantic SQL passes Query Preflight here before any
 * database call so model/field mistakes are repaired locally and only physical SQL can reach the
 * execution node.
 */
@Slf4j
@Component
@AllArgsConstructor
public class SemanticConsistencyNode implements NodeAction {

	private final Nl2SqlService nl2SqlService;

	private final BlueprintSqlConstraintValidator constraintValidator;

	private final SemanticCatalogCache semanticCatalogCache;

	private final QueryPreflightService queryPreflightService;

	private final QueryRepairPolicy repairPolicy;

	private final QueryRunService runService;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		String dialect = StateUtil.getStringValue(state, DB_DIALECT_TYPE);
		String semanticSql = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT);
		String userQuery = StateUtil.getCanonicalQuery(state);
		String semanticModel = StateUtil.getStringValue(state, GENEGRATED_SEMANTIC_MODEL_PROMPT, "");
		SemanticBlueprint semanticPlan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		List<Object> compiledParameters = StateUtil.getObjectValue(state, SQL_COMPILED_PARAMETERS, List.class,
				List.of());
		String compilerMode = StateUtil.getStringValue(state, SQL_COMPILER_MODE, "CONSTRAINED_GENERATION");

		String physicalSql = semanticSql;
		Map<String, Object> preflightSummary = Map.of("status", "NOT_REQUIRED", "compilerMode", compilerMode);
		String preflightFailure = null;
		if ("SEMANTIC_SQL".equalsIgnoreCase(compilerMode)) {
			try {
				Long projectId = StateUtil.getObjectValue(state, PROJECT_ID, Long.class);
				Long projectVersionId = StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class);
				Integer datasourceId = StateUtil.getObjectValue(state, DATASOURCE_ID, Integer.class);
				PreflightResult preflight = queryPreflightService.preflight(semanticSql,
						semanticCatalogCache.get(projectId, projectVersionId), semanticPlan, datasourceId, dialect);
				physicalSql = preflight.physicalSql();
				Map<String, Object> summary = new LinkedHashMap<>();
				summary.put("status", "PASS");
				summary.put("semanticModels", preflight.semanticModelCodes());
				summary.put("physicalTables", preflight.physicalTables());
				summary.put("legacyPhysicalPassthrough", preflight.legacyPhysicalPassthrough());
				summary.put("warnings", preflight.warnings());
				preflightSummary = Map.copyOf(summary);
				log.info("Query Preflight passed, models={}, physicalTables={}, passthrough={}",
						preflight.semanticModelCodes(), preflight.physicalTables(), preflight.legacyPhysicalPassthrough());
			}
			catch (QueryPreflightException ex) {
				preflightFailure = "不通过。Query Preflight失败 [" + ex.code() + "]: " + ex.getMessage();
				preflightSummary = Map.of("status", "FAIL", "code", ex.code(), "message", ex.getMessage());
				physicalSql = "";
				log.warn("Query Preflight rejected: {} - {}", ex.code(), ex.getMessage());
			}
		}

		persistPreflightEvidence(state, compilerMode, semanticSql, physicalSql, preflightSummary);

		boolean advancedExecution = state.value(ADVANCED_EXECUTION_FALLBACK, false)
				|| "SEMANTIC_SQL".equalsIgnoreCase(compilerMode);
		String executionDescription = getCurrentExecutionStepInstruction(state);
		SemanticConsistencyDTO semanticConsistencyDTO = SemanticConsistencyDTO.builder()
			.dialect(dialect)
			.sql(physicalSql)
			.executionDescription(executionDescription)
			.schemaInfo(buildMixMacSqlDbPrompt(schemaDTO, true))
			.semanticModel(semanticModel)
			.semanticPlan(serializeSemanticPlan(semanticPlan, advancedExecution))
			.userQuery(userQuery)
			.evidence(evidence)
			.build();
		log.info("Starting semantic consistency validation - Semantic SQL: {}", semanticSql);

		Flux<ChatResponse> validationResultFlux;
		if (preflightFailure != null) {
			validationResultFlux = Flux.just(ChatResponseUtil.createPureResponse(preflightFailure));
		}
		else {
			ValidationResult deterministicResult = constraintValidator.validate(physicalSql, compiledParameters, semanticPlan);
			List<String> executionStructureErrors = advancedExecutionStructureErrors(userQuery + "\n" + executionDescription,
					semanticSql);
			if (!deterministicResult.valid()) {
				String reason = "不通过。确定性语义约束校验失败: " + String.join("; ", deterministicResult.errors());
				log.warn("{}", reason);
				validationResultFlux = Flux.just(ChatResponseUtil.createPureResponse(reason));
			}
			else if (!executionStructureErrors.isEmpty()) {
				String reason = "不通过。执行计划结构校验失败: " + String.join("; ", executionStructureErrors);
				log.warn("{}", reason);
				validationResultFlux = Flux.just(ChatResponseUtil.createPureResponse(reason));
			}
			else if ("DETERMINISTIC".equalsIgnoreCase(compilerMode) || "PATTERN_TEMPLATE".equalsIgnoreCase(compilerMode)) {
				validationResultFlux = Flux
					.just(ChatResponseUtil.createPureResponse("PATTERN_TEMPLATE".equalsIgnoreCase(compilerMode)
							? "通过。已验证 Query Pattern 模板与当前确定性语义计划一致" : "通过。确定性编译器输出与冻结语义计划一致"));
			}
			else {
				validationResultFlux = nl2SqlService.performSemanticConsistency(semanticConsistencyDTO);
			}
		}

		String resolvedPhysicalSql = physicalSql;
		Map<String, Object> resolvedPreflightSummary = preflightSummary;
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "开始语义一致性校验", "语义一致性校验完成", validationResult -> {
					boolean isPassed = !validationResult.startsWith("不通过");
					Map<String, Object> result = buildValidationResult(state, isPassed, validationResult, resolvedPhysicalSql,
							resolvedPreflightSummary);
					log.info("[{}] Semantic consistency validation result: {}, passed: {}",
							this.getClass().getSimpleName(), validationResult, isPassed);
					return result;
				}, validationResultFlux);

		return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, generator);
	}

	static List<String> advancedExecutionStructureErrors(String executionDescription, String semanticSql) {
		String plan = executionDescription == null ? "" : executionDescription.toUpperCase(java.util.Locale.ROOT);
		String sql = semanticSql == null ? "" : semanticSql.toUpperCase(java.util.Locale.ROOT);
		List<String> errors = new java.util.ArrayList<>();
		for (String operator : List.of("LAG", "LEAD", "ROW_NUMBER", "DENSE_RANK")) {
			if (plan.contains(operator) && !sql.contains(operator + "(")) {
				errors.add("Planner requires " + operator + " but generated SQL does not contain " + operator + "(...)");
			}
		}
		if (plan.matches("(?s).*\\bRANK\\b.*") && !plan.contains("DENSE_RANK") && !sql.contains("RANK(")) {
			errors.add("Planner requires RANK but generated SQL does not contain RANK(...)");
		}
		if (plan.contains("PARTITION BY") && !sql.contains("PARTITION BY")) {
			errors.add("Planner requires PARTITION BY but generated SQL does not contain it");
		}
		return List.copyOf(errors);
	}

	private void persistPreflightEvidence(OverAllState state, String compilerMode, String semanticSql, String physicalSql,
			Map<String, Object> preflightSummary) {
		if (!"SEMANTIC_SQL".equalsIgnoreCase(compilerMode)) {
			return;
		}
		String runId = StateUtil.getStringValue(state, RUN_ID, "");
		if (runId.isBlank()) {
			return;
		}
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("compilerMode", compilerMode);
			payload.put("semanticSql", Objects.toString(semanticSql, ""));
			payload.put("physicalSql", Objects.toString(physicalSql, ""));
			payload.put("dryPlan", preflightSummary == null ? Map.of() : preflightSummary);
			String payloadJson = JsonUtil.getObjectMapper().writeValueAsString(payload);
			String evidenceKey = Integer.toUnsignedString(Objects.hash(semanticSql, physicalSql, preflightSummary), 16);
			runService.appendEvent(runId, "SEMANTIC_SQL_DRY_PLAN", "semantic-consistency", payloadJson,
					"Query Preflight evidence persisted", "semantic-sql-dry-plan:" + runId + ":" + evidenceKey);
		}
		catch (RuntimeException ex) {
			log.warn("Unable to persist Query Preflight evidence for run {}: {}", runId, ex.getMessage());
		}
		catch (Exception ex) {
			log.warn("Unable to serialize Query Preflight evidence for run {}: {}", runId, ex.getMessage());
		}
	}

	private Map<String, Object> buildValidationResult(OverAllState state, boolean passed, String validationResult,
			String physicalSql, Map<String, Object> preflightSummary) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put(SEMANTIC_CONSISTENCY_NODE_OUTPUT, passed);
		result.put(SQL_DRY_PLAN_OUTPUT, preflightSummary == null ? Map.of() : preflightSummary);
		result.put(SQL_PHYSICAL_OUTPUT, passed ? physicalSql : "");
		if (passed) {
			return result;
		}

		RepairBudget currentBudget = StateUtil.getObjectValue(state, QUERY_REPAIR_BUDGET, RepairBudget.class,
				RepairBudget.empty());
		BudgetDecision retryDecision = repairPolicy.consumeTransition(currentBudget, Decision.RETRY_SQL);
		if (retryDecision.allowed()) {
			result.put(QUERY_REPAIR_BUDGET, retryDecision.budget());
			result.put(SQL_REGENERATE_REASON, SqlRetryDto.semantic(validationResult));
			return result;
		}

		BudgetDecision replanDecision = repairPolicy.consumeTransition(currentBudget, Decision.REPLAN_EXECUTION);
		if (replanDecision.allowed()) {
			result.put(QUERY_REPAIR_BUDGET, replanDecision.budget());
			result.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
			result.put(PLAN_CURRENT_STEP, 1);
			result.put(PLAN_VALIDATION_ERROR,
					"EXECUTION_REPLAN_REQUIRED: Semantic SQL repair budget exhausted; " + validationResult);
			return result;
		}

		result.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
		throw new IllegalStateException("Semantic SQL repair and execution replan budgets exhausted: "
				+ replanDecision.reason());
	}

	static String serializeSemanticPlan(SemanticBlueprint semanticPlan, boolean advancedExecution) {
		if (semanticPlan == null) {
			return "{}";
		}
		try {
			var node = JsonUtil.getObjectMapper().valueToTree(semanticPlan);
			if (advancedExecution && node.isObject()) {
				var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
				object.remove("orderBy");
				object.remove("limit");
				object.remove("expectedResult");
			}
			return JsonUtil.getObjectMapper().writeValueAsString(node);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize Semantic Blueprint", ex);
		}
	}

}
