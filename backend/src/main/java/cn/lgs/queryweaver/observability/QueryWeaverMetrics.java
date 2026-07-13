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
package cn.lgs.queryweaver.observability;

import cn.lgs.queryweaver.clarification.RuntimeClarification;
import cn.lgs.queryweaver.model.ModelCallPurpose;
import cn.lgs.queryweaver.run.QueryRun;
import cn.lgs.queryweaver.run.QueryRun.RunStatus;
import cn.lgs.queryweaver.run.QueryRun.RunType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Low-cardinality QueryWeaver business metrics. Never tag project, run, user, or
 * datasource identifiers.
 */
@Slf4j
@Component
public class QueryWeaverMetrics {

	private static final Set<String> CAPACITY_SCOPES = Set.of("global", "datasource", "project", "user", "thread",
			"interactive-query");

	private static final Set<String> RETENTION_STATUSES = Set.of("DRY_RUN", "SUCCEEDED", "PARTIAL", "FAILED",
			"ABANDONED");

	private static final Set<String> MCP_ACTIONS = Set.of("authenticate", "deploy", "enable", "disable", "revoke",
			"rotate", "recover", "search_semantics", "get_semantic_context", "validate_query_plan",
			"execute_query_plan", "get_query_result");

	private final MeterRegistry registry;

	public QueryWeaverMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	private QueryWeaverMetrics() {
		this.registry = null;
	}

	public static QueryWeaverMetrics noop() {
		return new QueryWeaverMetrics();
	}

	public void afterCommit(Runnable action) {
		if (registry == null || action == null) {
			return;
		}
		Runnable safeAction = () -> recordSafely(action);
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			safeAction.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				safeAction.run();
			}
		});
	}

	public void runCreated(RunType runType) {
		recordSafely(() -> Counter.builder("queryweaver.run.created")
			.description("Durable QueryWeaver runs created")
			.tag("run_type", enumTag(runType))
			.register(registry)
			.increment());
	}

	public void runTerminal(QueryRun run) {
		if (run == null || !terminal(run.status())) {
			return;
		}
		recordSafely(() -> {
			String runType = enumTag(run.runType());
			String status = enumTag(run.status());
			Counter.builder("queryweaver.run.terminal")
				.description("Durable QueryWeaver runs entering a terminal status")
				.tag("run_type", runType)
				.tag("status", status)
				.register(registry)
				.increment();
			if (run.startTime() == null || run.finishTime() == null || run.finishTime().isBefore(run.startTime())) {
				return;
			}
			Timer.builder("queryweaver.run.duration")
				.description("Durable QueryWeaver run execution duration")
				.tag("run_type", runType)
				.tag("status", status)
				.register(registry)
				.record(Duration.between(run.startTime(), run.finishTime()));
		});
	}

	public void clarificationRequired(RuntimeClarification clarification) {
		if (clarification == null) {
			return;
		}
		recordSafely(() -> Counter.builder("queryweaver.clarification.required")
			.description("Runtime clarifications created")
			.tag("issue_type", enumTag(clarification.issueType()))
			.register(registry)
			.increment());
	}

	public void clarificationAnswered(RuntimeClarification clarification, boolean cancelled) {
		if (clarification == null) {
			return;
		}
		recordSafely(() -> {
			String issueType = enumTag(clarification.issueType());
			String outcome = cancelled ? "cancelled" : "resumed";
			Counter.builder("queryweaver.clarification.answered")
				.description("Runtime clarifications answered")
				.tag("issue_type", issueType)
				.tag("outcome", outcome)
				.register(registry)
				.increment();
			LocalDateTime created = clarification.createTime();
			LocalDateTime answered = clarification.updateTime();
			if (created == null || answered == null || answered.isBefore(created)) {
				return;
			}
			Timer.builder("queryweaver.clarification.wait")
				.description("Time spent waiting for a runtime clarification answer")
				.tag("issue_type", issueType)
				.tag("outcome", outcome)
				.register(registry)
				.record(Duration.between(created, answered));
		});
	}

	public void capacityRejected(String scope, int httpStatus) {
		recordSafely(() -> {
			String normalizedScope = normalize(scope);
			Counter.builder("queryweaver.capacity.rejected")
				.description("User-visible QueryWeaver capacity rejections")
				.tag("scope", CAPACITY_SCOPES.contains(normalizedScope) ? normalizedScope : "other")
				.tag("http_status", Integer.toString(httpStatus))
				.register(registry)
				.increment();
		});
	}

	public void mcpRequest(String action, String outcome) {
		recordSafely(() -> {
			String normalizedAction = normalize(action);
			String safeAction = MCP_ACTIONS.contains(normalizedAction) ? normalizedAction : "other";
			String safeOutcome = "success".equals(normalize(outcome)) ? "success" : "failure";
			Counter.builder("queryweaver.mcp.requests")
				.description("Authenticated Project MCP management and tool requests")
				.tag("action", safeAction)
				.tag("outcome", safeOutcome)
				.register(registry)
				.increment();
		});
	}

	public void modelCall(ModelCallPurpose purpose, boolean success, int retryCount, long latencyMs, String errorType,
			long promptTokens, long completionTokens) {
		recordSafely(() -> {
			String purposeTag = enumTag(purpose);
			String outcome = success ? "success" : "failure";
			String safeError = normalize(errorType);
			Counter.builder("queryweaver.model.calls")
				.description("Governed QueryWeaver model calls")
				.tag("purpose", purposeTag)
				.tag("outcome", outcome)
				.tag("error_type", safeError)
				.register(registry)
				.increment();
			Timer.builder("queryweaver.model.latency")
				.description("Governed QueryWeaver model call latency")
				.tag("purpose", purposeTag)
				.tag("outcome", outcome)
				.register(registry)
				.record(Duration.ofMillis(Math.max(0L, latencyMs)));
			DistributionSummary.builder("queryweaver.model.retries")
				.description("Retries used by governed QueryWeaver model calls")
				.tag("purpose", purposeTag)
				.register(registry)
				.record(Math.max(0, retryCount));
			DistributionSummary.builder("queryweaver.model.prompt.tokens")
				.description("Prompt tokens reported by governed QueryWeaver model calls")
				.tag("purpose", purposeTag)
				.register(registry)
				.record(Math.max(0L, promptTokens));
			DistributionSummary.builder("queryweaver.model.completion.tokens")
				.description("Completion tokens reported by governed QueryWeaver model calls")
				.tag("purpose", purposeTag)
				.register(registry)
				.record(Math.max(0L, completionTokens));
		});
	}

	public void retentionBatch(String batchStatus, boolean batchDryRun, int candidateCount, int archivedCount,
			int failureCount) {
		recordSafely(() -> {
			String normalizedStatus = batchStatus == null ? "" : batchStatus.trim().toUpperCase(Locale.ROOT);
			String status = RETENTION_STATUSES.contains(normalizedStatus) ? normalizedStatus.toLowerCase(Locale.ROOT)
					: "other";
			String dryRun = Boolean.toString(batchDryRun);
			Counter.builder("queryweaver.retention.batch")
				.description("Durable run retention batches completed")
				.tag("status", status)
				.tag("dry_run", dryRun)
				.register(registry)
				.increment();
			recordSummary("queryweaver.retention.candidates", "Retention candidate runs per batch", status, dryRun,
					candidateCount);
			recordSummary("queryweaver.retention.archived", "Archived durable runs per retention batch", status, dryRun,
					archivedCount);
			recordSummary("queryweaver.retention.failures", "Failed durable runs per retention batch", status, dryRun,
					failureCount);
		});
	}

	private void recordSafely(Runnable action) {
		if (registry == null || action == null) {
			return;
		}
		try {
			action.run();
		}
		catch (RuntimeException ex) {
			log.warn("Unable to record QueryWeaver business metric: {}", ex.toString());
		}
	}

	private void recordSummary(String name, String description, String status, String dryRun, int amount) {
		DistributionSummary.builder(name)
			.description(description)
			.tag("status", status)
			.tag("dry_run", dryRun)
			.register(registry)
			.record(Math.max(0, amount));
	}

	private static boolean terminal(RunStatus status) {
		return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.CANCELLED
				|| status == RunStatus.EXPIRED;
	}

	private static String enumTag(Enum<?> value) {
		return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT);
	}

	private static String normalize(String value) {
		return value == null ? "other" : value.trim().toLowerCase(Locale.ROOT);
	}

}
