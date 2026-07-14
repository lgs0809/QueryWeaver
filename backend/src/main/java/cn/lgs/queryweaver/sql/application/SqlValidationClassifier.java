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
package cn.lgs.queryweaver.sql.application;

import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransactionRollbackException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Maps SQL validation/execution outcomes to one bounded runtime decision. */
@Service
public class SqlValidationClassifier {

	public SqlValidationResult pass() {
		return new SqlValidationResult(SqlValidationDecision.PASS, null, null, null, 0, 0);
	}

	public SqlValidationResult classify(Throwable error, int retriesUsed) {
		Throwable root = root(error);
		String message = Objects.toString(root.getMessage(), root.getClass().getSimpleName());
		String normalized = message.toLowerCase(Locale.ROOT);
		String errorType = root.getClass().getSimpleName();
		if (contains(normalized, "ambiguous", "clarification", "歧义", "请明确")) {
			return result(SqlValidationDecision.REQUIRE_CLARIFICATION, errorType, message, "runtime-clarification", 0,
					retriesUsed);
		}
		if (contains(normalized, "human review", "require review", "人工审核", "审批")) {
			return result(SqlValidationDecision.REQUIRE_REVIEW, errorType, message, "human-feedback", 0, retriesUsed);
		}
		if (root instanceof SqlCostGuardViolationException) {
			// QueryRepairPolicy is the durable source of truth for retry/replan budgets. Do not
			// pre-empt it with the classifier's legacy local retry counter.
			return boundedRetry(errorType, "QUERY_COST_EXCEEDED: " + message, "sql-generate", Integer.MAX_VALUE,
					retriesUsed);
		}
		if (contains(normalized, "guard", "unsafe", "denies", "forbidden", "not allowed", "security", "sensitive",
				"read-only", "readonly", "drop ", "delete ", "update ", "insert ", "truncate ")) {
			return result(SqlValidationDecision.FATAL, errorType, message, null, 0, retriesUsed);
		}
		if (root instanceof SQLSyntaxErrorException
				|| contains(normalized, "syntax", "unknown column", "column not found", "unknown table",
						"table not found", "doesn't exist", "does not exist", "invalid identifier")) {
			return boundedRetry(errorType, message, "sql-generate", 2, retriesUsed);
		}
		if (root instanceof SQLTimeoutException || root instanceof SQLTransientConnectionException
				|| root instanceof SQLTransactionRollbackException || contains(normalized, "timeout", "timed out",
						"deadlock", "connection reset", "connection closed", "temporarily unavailable")) {
			return boundedRetry(errorType, message, "sql-execute", 1, retriesUsed);
		}
		return result(SqlValidationDecision.FATAL, errorType, message, null, 0, retriesUsed);
	}

	private SqlValidationResult boundedRetry(String errorType, String message, String node, int budget,
			int retriesUsed) {
		SqlValidationDecision decision = retriesUsed < budget ? SqlValidationDecision.RETRYABLE
				: SqlValidationDecision.FATAL;
		return result(decision, errorType, message, decision == SqlValidationDecision.RETRYABLE ? node : null, budget,
				retriesUsed);
	}

	private SqlValidationResult result(SqlValidationDecision decision, String errorType, String message, String node,
			int budget, int retriesUsed) {
		return new SqlValidationResult(decision, errorType, message, node, budget, retriesUsed);
	}

	private boolean contains(String value, String... terms) {
		return List.of(terms).stream().anyMatch(value::contains);
	}

	private Throwable root(Throwable error) {
		Throwable current = Objects.requireNonNull(error, "error is required");
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}

}
