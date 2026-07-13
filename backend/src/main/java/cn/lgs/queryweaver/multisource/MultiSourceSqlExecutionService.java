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
package cn.lgs.queryweaver.multisource;

import cn.lgs.queryweaver.bo.DbConfigBO;
import cn.lgs.queryweaver.bo.schema.ResultSetBO;
import cn.lgs.queryweaver.connector.DbQueryParameter;
import cn.lgs.queryweaver.connector.accessor.Accessor;
import cn.lgs.queryweaver.properties.QueryWeaverProperties;
import cn.lgs.queryweaver.operations.SemanticCatalogCache;
import cn.lgs.queryweaver.semantic.compiler.SqlDialect;
import cn.lgs.queryweaver.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.queryweaver.semantic.domain.SemanticQueryPlan;
import cn.lgs.queryweaver.sql.application.SensitiveResultSanitizer;
import cn.lgs.queryweaver.sql.application.SqlCostGuard;
import cn.lgs.queryweaver.sql.application.SqlExecutionAdmissionControl;
import cn.lgs.queryweaver.sql.application.SqlExecutionGuard;
import cn.lgs.queryweaver.sql.application.SqlPreflightPlanner;
import cn.lgs.queryweaver.sql.application.SqlResultValidator;
import cn.lgs.queryweaver.service.nl2sql.Nl2SqlService;
import cn.lgs.queryweaver.util.DatabaseUtil;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MultiSourceSqlExecutionService {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

	private final DatabaseUtil databaseUtil;

	private final Nl2SqlService nl2SqlService;

	private final QueryWeaverProperties properties;

	private final SqlExecutionGuard sqlExecutionGuard;

	private final SqlExecutionAdmissionControl admissionControl;

	private final SqlCostGuard sqlCostGuard;

	private final SqlPreflightPlanner sqlPreflightPlanner;

	private final SensitiveResultSanitizer sensitiveResultSanitizer;

	private final SqlResultValidator sqlResultValidator;

	private final SemanticCatalogCache semanticCatalogCache;

	public SqlDialect dialect(Integer datasourceId) {
		if (datasourceId == null || datasourceId <= 0) {
			throw new IllegalArgumentException("datasourceId must be positive");
		}
		return SqlDialect.from(databaseUtil.getDatasourceDbConfig(datasourceId).getDialectType());
	}

	public ResultSetBO execute(Long projectId, Long projectVersionId, String executionOwner, Integer datasourceId,
			Set<String> allowedTables, String sql, SemanticQueryPlan semanticPlan) throws Exception {
		return execute(projectId, projectVersionId, executionOwner, executionOwner, datasourceId, allowedTables, sql,
				List.of(), semanticPlan);
	}

	public ResultSetBO execute(Long projectId, Long projectVersionId, String principalId, String executionOwner,
			Integer datasourceId, Set<String> allowedTables, String sql, List<Object> parameters,
			SemanticQueryPlan semanticPlan) throws Exception {
		if (datasourceId == null || datasourceId <= 0) {
			throw new IllegalArgumentException("datasourceId must be positive");
		}
		Set<String> normalizedAllowedTables = allowedTables == null ? Set.of()
				: allowedTables.stream()
					.filter(value -> value != null && !value.isBlank())
					.collect(Collectors.toCollection(LinkedHashSet::new));
		if (normalizedAllowedTables.isEmpty()) {
			throw new IllegalArgumentException("Source subplan must expose at least one physical table");
		}
		String normalizedSql = nl2SqlService.sqlTrim(sql);
		if (normalizedSql == null || normalizedSql.isBlank()) {
			throw new IllegalArgumentException("Generated SQL is empty");
		}
		if (normalizedSql.length() > properties.getSqlExecution().getMaxSqlLength()) {
			throw new IllegalArgumentException(
					"SQL text exceeds configured max length: " + properties.getSqlExecution().getMaxSqlLength());
		}
		DbConfigBO dbConfig = databaseUtil.getDatasourceDbConfig(datasourceId);
		SemanticCatalogSnapshot catalog = semanticCatalogCache.get(projectId, projectVersionId);
		String effectiveExecutionOwner = executionOwner == null || executionOwner.isBlank() ? "multi-source"
				: executionOwner;
		SqlExecutionAdmissionControl.Permit permit = admissionControl.acquire(projectId, datasourceId, principalId);
		try {
			SqlExecutionGuard.GuardResult guard = sqlExecutionGuard.validate(normalizedSql, dbConfig.getDialectType(),
					normalizedAllowedTables, dbConfig.getSchema());
			SqlCostGuard.CostAssessment staticCost = sqlCostGuard.validateSql(normalizedSql, guard.referencedTables(),
					semanticTimeColumns(semanticPlan), properties.getSqlExecution());
			Accessor accessor = databaseUtil.getDatasourceAccessor(datasourceId);
			runPreflight(accessor, dbConfig, normalizedSql, parameters, staticCost.tableCount(), catalog,
					effectiveExecutionOwner);
			ResultSetBO result = accessor.executeSqlAndReturnObject(dbConfig,
					queryParameter(normalizedSql, dbConfig.getSchema(), properties.getSqlExecution().getMaxRows(),
							properties.getSqlExecution().getQueryTimeoutSeconds(), effectiveExecutionOwner, parameters));
			sensitiveResultSanitizer.sanitize(result, catalog);
			SqlResultValidator.ValidationResult validation = sqlResultValidator.validate(result, semanticPlan,
					properties.getSqlExecution().getMaxRows());
			if (!validation.valid()) {
				throw new IllegalStateException(
						"SQL result validation failed: " + String.join("; ", validation.errors()));
			}
			permit.success();
			return result;
		}
		catch (Exception ex) {
			permit.failure();
			throw ex;
		}
		finally {
			permit.close();
		}
	}

	public String readFreshnessWatermark(Long projectId, Integer datasourceId, String executionOwner,
			SemanticQueryPlan.SourceSubPlan sourcePlan, SemanticQueryPlan.FreshnessNotice freshness) throws Exception {
		if (freshness == null || freshness.getBusinessDateField() == null
				|| freshness.getBusinessDateField().isBlank()) {
			throw new IllegalStateException("Freshness policy is missing for datasource " + datasourceId);
		}
		if (sourcePlan == null || sourcePlan.getPhysicalTables() == null || sourcePlan.getPhysicalTables().isEmpty()) {
			throw new IllegalStateException("Freshness watermark requires a physical source table");
		}
		String table = sourcePlan.getPhysicalTables().get(0);
		String column = freshness.getBusinessDateField();
		DbConfigBO dbConfig = databaseUtil.getDatasourceDbConfig(datasourceId);
		String watermarkSql = "SELECT MAX(" + quoteIdentifier(column, dbConfig.getDialectType())
				+ ") AS qw_freshness_as_of FROM " + quoteQualifiedIdentifier(table, dbConfig.getDialectType());
		SqlExecutionAdmissionControl.Permit permit = admissionControl.acquire(projectId, datasourceId,
				executionOwner == null ? "freshness-watermark" : executionOwner + ":freshness");
		try {
			Accessor accessor = databaseUtil.getDatasourceAccessor(datasourceId);
			ResultSetBO result = accessor.executeSqlAndReturnObject(dbConfig,
					queryParameter(watermarkSql, dbConfig.getSchema(), 1,
							properties.getSqlExecution().getPreflightTimeoutSeconds(), executionOwner + ":freshness"));
			List<Map<String, String>> rows = result == null || result.getData() == null ? List.of() : result.getData();
			if (rows.isEmpty()) {
				throw new IllegalStateException(
						"Freshness watermark query returned no row for datasource " + datasourceId);
			}
			String value = rows.get(0)
				.entrySet()
				.stream()
				.filter(entry -> "qw_freshness_as_of".equalsIgnoreCase(entry.getKey()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
			if (value == null || value.isBlank()) {
				throw new IllegalStateException("Freshness watermark is empty for datasource " + datasourceId);
			}
			permit.success();
			return value;
		}
		catch (Exception ex) {
			permit.failure();
			throw ex;
		}
		finally {
			permit.close();
		}
	}

	private String quoteQualifiedIdentifier(String value, String dialect) {
		String[] parts = value.split("\\.");
		return java.util.Arrays.stream(parts)
			.map(part -> quoteIdentifier(part, dialect))
			.collect(Collectors.joining("."));
	}

	private String quoteIdentifier(String value, String dialect) {
		if (value == null || !IDENTIFIER.matcher(value).matches()) {
			throw new IllegalArgumentException("Unsafe SQL identifier in freshness policy: " + value);
		}
		String quote = dialect != null && dialect.toLowerCase().contains("mysql") ? "`" : "\"";
		return quote + value + quote;
	}

	private void runPreflight(Accessor accessor, DbConfigBO dbConfig, String sql, List<Object> parameters, int tableCount,
			SemanticCatalogSnapshot catalog, String cancellationKey) throws Exception {
		QueryWeaverProperties.SqlExecutionPolicy policy = properties.getSqlExecution();
		if (policy.isExplainEnabled()) {
			String explainSql = sqlPreflightPlanner.explainSql(sql, dbConfig.getDialectType()).orElse(null);
			if (explainSql != null) {
				ResultSetBO explain = accessor.executeSqlAndReturnObject(dbConfig,
						queryParameter(explainSql, dbConfig.getSchema(), Math.max(1, policy.getPreviewRows()),
								policy.getPreflightTimeoutSeconds(), cancellationKey + ":explain", parameters));
				sqlCostGuard.validateExplain(explain, tableCount, policy);
			}
		}
		if (policy.isPreviewEnabled()) {
			ResultSetBO preview = accessor.executeSqlAndReturnObject(dbConfig,
					queryParameter(sql, dbConfig.getSchema(), Math.max(1, policy.getPreviewRows()),
							policy.getPreflightTimeoutSeconds(), cancellationKey + ":preview", parameters));
			sensitiveResultSanitizer.sanitize(preview, catalog);
		}
	}

	private Set<String> semanticTimeColumns(SemanticQueryPlan semanticPlan) {
		if (semanticPlan == null) {
			return Set.of();
		}
		Set<String> columns = semanticPlan.getMetrics()
			.stream()
			.map(SemanticQueryPlan.MetricSelection::getTimeColumn)
			.filter(value -> value != null && !value.isBlank())
			.collect(Collectors.toCollection(LinkedHashSet::new));
		semanticPlan.getGrains()
			.stream()
			.map(SemanticQueryPlan.GrainSelection::getTimeColumn)
			.filter(value -> value != null && !value.isBlank())
			.forEach(columns::add);
		return Set.copyOf(columns);
	}

	private DbQueryParameter queryParameter(String sql, String schema, int maxRows, int timeoutSeconds,
			String cancellationKey) {
		return queryParameter(sql, schema, maxRows, timeoutSeconds, cancellationKey, List.of());
	}

	private DbQueryParameter queryParameter(String sql, String schema, int maxRows, int timeoutSeconds,
			String cancellationKey, List<Object> parameters) {
		return new DbQueryParameter().setSql(sql)
			.setSchema(schema)
			.setParameters(parameters == null ? List.of() : List.copyOf(parameters))
			.setMaxRows(maxRows)
			.setQueryTimeoutSeconds(timeoutSeconds)
			.setCancellationKey(cancellationKey);
	}

}
