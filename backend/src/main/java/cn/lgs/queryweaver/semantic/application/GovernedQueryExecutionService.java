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
package cn.lgs.queryweaver.semantic.application;

import cn.lgs.queryweaver.bo.schema.ResultSetBO;
import cn.lgs.queryweaver.multisource.MultiSourceRunService;
import cn.lgs.queryweaver.multisource.MultiSourceRunService.ResultArtifact;
import cn.lgs.queryweaver.multisource.MultiSourceRunService.SourceSubRun;
import cn.lgs.queryweaver.multisource.MultiSourceRunService.SourceSubRunStatus;
import cn.lgs.queryweaver.multisource.MultiSourceSqlExecutionService;
import cn.lgs.queryweaver.operations.SemanticCatalogCache;
import cn.lgs.queryweaver.semantic.compiler.CompiledSemanticQuery;
import cn.lgs.queryweaver.semantic.compiler.CompiledSemanticQuery.CompiledSourceQuery;
import cn.lgs.queryweaver.semantic.compiler.SemanticSqlCompiler;
import cn.lgs.queryweaver.semantic.compiler.SqlDialect;
import cn.lgs.queryweaver.semantic.domain.SemanticQueryPlan;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Deterministic data-plane execution shared by the built-in Agent and external BYO-Agent adapters.
 * This service intentionally has no ChatModel/LLM dependency.
 */
@Service
@RequiredArgsConstructor
public class GovernedQueryExecutionService {

	private final SemanticCatalogCache semanticCatalogCache;
	private final SemanticSqlCompiler semanticSqlCompiler;
	private final MultiSourceSqlExecutionService sqlExecutionService;
	private final MultiSourceRunService multiSourceRunService;

	public ExecutionResult execute(String runId, String executionKey, Long projectId, Long versionId, String principalId,
			SemanticQueryPlan plan) throws Exception {
		if (plan == null || !plan.isExecutable()) {
			throw new IllegalArgumentException("An executable Typed Semantic Plan is required");
		}
		Map<Integer, SqlDialect> dialects = plan.getSourceSubPlans().stream()
			.filter(source -> source.getDatasourceId() != null)
			.collect(Collectors.toMap(SemanticQueryPlan.SourceSubPlan::getDatasourceId,
					source -> sqlExecutionService.dialect(source.getDatasourceId()), (left, right) -> left,
					LinkedHashMap::new));
		CompiledSemanticQuery compiled = semanticSqlCompiler.compile(plan, semanticCatalogCache.get(projectId, versionId),
				dialects, Clock.systemUTC(), ZoneId.systemDefault());
		multiSourceRunService.initialize(runId, executionKey, projectId, versionId, plan);
		Map<Integer, CompiledSourceQuery> compiledByDatasource = compiled.sources().stream()
			.collect(Collectors.toMap(CompiledSourceQuery::datasourceId, source -> source, (left, right) -> left,
					LinkedHashMap::new));

		for (SemanticQueryPlan.SourceSubPlan sourcePlan : plan.getSourceSubPlans()) {
			CompiledSourceQuery sourceQuery = compiledByDatasource.get(sourcePlan.getDatasourceId());
			if (sourceQuery == null) {
				throw new IllegalStateException("Compiled query missing datasource " + sourcePlan.getDatasourceId());
			}
			SourceSubRun sourceRun = multiSourceRunService.get(runId, executionKey).sourceSubRuns().stream()
				.filter(candidate -> Objects.equals(candidate.datasourceId(), sourcePlan.getDatasourceId()))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Source sub-run missing for datasource "
						+ sourcePlan.getDatasourceId()));
			if (sourceRun.status() == SourceSubRunStatus.COMPLETED) {
				continue;
			}
			if (sourceRun.status() == SourceSubRunStatus.FAILED || sourceRun.status() == SourceSubRunStatus.CANCELLED) {
				throw new IllegalStateException("Source sub-run is terminal and cannot execute: " + sourceRun.subRunId());
			}
			multiSourceRunService.startSource(runId, sourceRun.subRunId(), sourceQuery.sql());
			SemanticQueryPlan sourceSemanticPlan = sourceSemanticPlan(plan, sourcePlan);
			try {
				String executionOwner = runId + ":source:" + sourceRun.subRunId();
				ResultSetBO result = sqlExecutionService.execute(projectId, versionId, principalId, executionOwner,
						sourcePlan.getDatasourceId(), Set.copyOf(sourcePlan.getPhysicalTables()), sourceQuery.sql(),
						sourceQuery.parameters(), sourceSemanticPlan);
				String freshness = freshness(plan, sourcePlan, executionOwner, projectId);
				multiSourceRunService.completeSource(runId, sourceRun.subRunId(), sourceQuery.sql(), result, freshness);
			}
			catch (Exception failure) {
				multiSourceRunService.failSource(runId, sourceRun.subRunId(), failure.getMessage());
				throw failure;
			}
		}

		ResultArtifact artifact = multiSourceRunService.merge(runId, executionKey);
		ResultSetBO merged = multiSourceRunService.resultSet(artifact);
		String allSql = compiled.sources().stream().map(CompiledSourceQuery::sql)
			.collect(Collectors.joining("\n-- next source --\n"));
		return new ExecutionResult(artifact, merged, allSql);
	}

	private String freshness(SemanticQueryPlan plan, SemanticQueryPlan.SourceSubPlan source, String executionOwner,
			Long projectId) throws Exception {
		var notice = plan.getFreshnessNotices().stream()
			.filter(candidate -> Objects.equals(candidate.getDatasourceId(), source.getDatasourceId()))
			.findFirst().orElse(null);
		if (notice == null || notice.getBusinessDateField() == null || notice.getBusinessDateField().isBlank()) {
			return null;
		}
		return sqlExecutionService.readFreshnessWatermark(projectId, source.getDatasourceId(), executionOwner, source,
				notice);
	}

	private SemanticQueryPlan sourceSemanticPlan(SemanticQueryPlan plan, SemanticQueryPlan.SourceSubPlan source) {
		Set<String> modelCodes = Set.copyOf(source.getModelCodes());
		return SemanticQueryPlan.builder()
			.projectId(plan.getProjectId())
			.projectVersionId(plan.getProjectVersionId())
			.canonicalQuery(plan.getCanonicalQuery())
			.compilerMode(plan.getCompilerMode())
			.models(plan.getModels().stream().filter(item -> modelCodes.contains(item.getModelCode())).toList())
			.metrics(plan.getMetrics().stream().filter(item -> modelCodes.contains(item.getModelCode())).toList())
			.dimensions(plan.getDimensions().stream().filter(item -> modelCodes.contains(item.getModelCode())).toList())
			.grains(plan.getGrains().stream().filter(item -> modelCodes.contains(item.getModelCode())).toList())
			.relationships(plan.getRelationships().stream()
				.filter(item -> modelCodes.contains(item.getSourceModelCode())
						&& modelCodes.contains(item.getTargetModelCode())).toList())
			.rules(plan.getRules().stream()
				.filter(item -> item.getModelCode() == null || modelCodes.contains(item.getModelCode())).toList())
			.projections(plan.getProjections().stream()
				.filter(item -> item.getModelCode() == null || modelCodes.contains(item.getModelCode())).toList())
			.preAggregationModelCodes(plan.getPreAggregationModelCodes().stream().filter(modelCodes::contains).toList())
			.sourceSubPlans(List.of(source))
			.freshnessNotices(plan.getFreshnessNotices().stream()
				.filter(item -> Objects.equals(item.getDatasourceId(), source.getDatasourceId())).toList())
			.expectedResult(plan.getExpectedResult())
			.validationWarnings(plan.getValidationWarnings())
			.validationErrors(List.of())
			.executable(true)
			.build();
	}

	public record ExecutionResult(ResultArtifact artifact, ResultSetBO resultSet, String sql) {
	}
}
