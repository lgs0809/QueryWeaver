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

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses generated SQL before execution and enforces QueryWeaver's system-level safety
 * boundary. Business correctness is handled by the Semantic Query Plan; this guard is
 * intentionally limited to deterministic execution safety and table scope.
 */
@Component
public class SqlExecutionGuard {

	private static final Pattern DANGEROUS_FUNCTION_PATTERN = Pattern
		.compile("(?i)\\b(sleep|benchmark|load_file|pg_sleep|dblink|xp_cmdshell|sys_eval|sys_exec)\\s*\\(");

	private static final Pattern WRITE_SELECT_PATTERN = Pattern.compile(
			"(?i)\\b(into\\s+(outfile|dumpfile)|for\\s+update|lock\\s+in\\s+share\\s+mode|copy\\b.+\\bto\\s+program)\\b",
			Pattern.DOTALL);

	private static final Pattern GENERIC_SELECT_INTO_PATTERN = Pattern.compile("(?i)\\bselect\\b.+\\binto\\b",
			Pattern.DOTALL);

	private static final Pattern CTE_PATTERN = Pattern
		.compile("(?i)(?:\\bwith\\b|,)\\s*([`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?)\\s+as\\s*\\(");

	private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "mysql", "performance_schema", "sys",
			"pg_catalog", "pg_toast");

	public GuardResult validate(String sql, String dialect, Collection<String> allowedPhysicalTables,
			String expectedSchema) {
		if (sql == null || sql.isBlank()) {
			throw new SqlGuardViolationException("SQL cannot be blank");
		}
		String dbType = resolveDbType(dialect);
		String normalizedSql = sql.trim();
		assertNoDangerousSelectFeatures(normalizedSql);

		List<SQLStatement> statements;
		try {
			statements = SQLUtils.parseStatements(normalizedSql, dbType);
		}
		catch (RuntimeException ex) {
			throw new SqlGuardViolationException("SQL cannot be parsed for dialect " + dialect + ": " + ex.getMessage(),
					ex);
		}
		if (statements.size() != 1) {
			throw new SqlGuardViolationException("Exactly one SQL statement is required");
		}
		SQLStatement statement = statements.get(0);
		if (!(statement instanceof SQLSelectStatement)) {
			throw new SqlGuardViolationException("Only SELECT statements are allowed");
		}

		Set<String> allowedTables = normalizeAllowedTables(allowedPhysicalTables);
		if (allowedTables.isEmpty()) {
			throw new SqlGuardViolationException("The published semantic plan does not expose any executable table");
		}
		Set<String> cteNames = extractCteNames(normalizedSql);
		Set<String> referencedTables = extractReferencedTables(statement, dbType);
		List<String> violations = new ArrayList<>();
		for (String referencedTable : referencedTables) {
			validateReferencedTable(referencedTable, allowedTables, cteNames, expectedSchema, violations);
		}
		if (!violations.isEmpty()) {
			throw new SqlGuardViolationException(String.join("; ", violations));
		}
		return new GuardResult(dbType, Set.copyOf(referencedTables));
	}

	private Set<String> extractReferencedTables(SQLStatement statement, String dbType) {
		SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(DbType.of(dbType));
		statement.accept(visitor);
		Set<String> tables = new LinkedHashSet<>();
		for (TableStat.Name name : visitor.getTables().keySet()) {
			String normalized = normalizeQualifiedIdentifier(name.getName());
			if (!normalized.isBlank()) {
				tables.add(normalized);
			}
		}
		return tables;
	}

	private void validateReferencedTable(String referencedTable, Set<String> allowedTables, Set<String> cteNames,
			String expectedSchema, List<String> violations) {
		String unqualified = unqualifiedName(referencedTable);
		if (cteNames.contains(unqualified)) {
			return;
		}
		String qualifier = qualifier(referencedTable);
		if (SYSTEM_SCHEMAS.contains(qualifier)) {
			violations.add("System schema access is forbidden: " + referencedTable);
			return;
		}

		String normalizedExpectedSchema = normalizeIdentifier(expectedSchema);
		boolean explicitlyAllowed = allowedTables.contains(referencedTable);
		boolean unqualifiedAllowed = allowedTables.contains(unqualified);
		if (!qualifier.isBlank() && !explicitlyAllowed && (!unqualifiedAllowed || normalizedExpectedSchema.isBlank()
				|| !qualifier.equals(normalizedExpectedSchema))) {
			violations.add("Cross-schema table is outside the published semantic project: " + referencedTable);
			return;
		}
		if (!explicitlyAllowed && !unqualifiedAllowed) {
			violations.add("Table is outside the published semantic project: " + referencedTable);
		}
	}

	private void assertNoDangerousSelectFeatures(String sql) {
		if (DANGEROUS_FUNCTION_PATTERN.matcher(sql).find()) {
			throw new SqlGuardViolationException("Dangerous database function is not allowed");
		}
		if (WRITE_SELECT_PATTERN.matcher(sql).find() || GENERIC_SELECT_INTO_PATTERN.matcher(sql).find()) {
			throw new SqlGuardViolationException("SELECT variants that write, lock or invoke programs are not allowed");
		}
	}

	private Set<String> normalizeAllowedTables(Collection<String> allowedPhysicalTables) {
		Set<String> result = new LinkedHashSet<>();
		if (allowedPhysicalTables == null) {
			return result;
		}
		for (String table : allowedPhysicalTables) {
			String normalized = normalizeQualifiedIdentifier(table);
			if (!normalized.isBlank()) {
				result.add(normalized);
				result.add(unqualifiedName(normalized));
			}
		}
		return result;
	}

	private Set<String> extractCteNames(String sql) {
		Set<String> names = new LinkedHashSet<>();
		Matcher matcher = CTE_PATTERN.matcher(sql);
		while (matcher.find()) {
			names.add(normalizeIdentifier(matcher.group(1)));
		}
		return names;
	}

	private String resolveDbType(String dialect) {
		String normalized = dialect == null ? "" : dialect.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "mysql" -> "mysql";
			case "postgresql", "postgres", "hologress" -> "postgresql";
			case "sqlserver", "sql_server", "mssql" -> "sqlserver";
			case "oracle", "dameng" -> "oracle";
			case "hive" -> "hive";
			case "sqlite" -> "sqlite";
			case "h2" -> "h2";
			default -> throw new SqlGuardViolationException("Unsupported SQL dialect for AST guard: " + dialect);
		};
	}

	private String normalizeQualifiedIdentifier(String value) {
		if (value == null) {
			return "";
		}
		String[] parts = value.trim().split("\\.");
		List<String> normalized = new ArrayList<>();
		for (String part : parts) {
			String identifier = normalizeIdentifier(part);
			if (!identifier.isBlank()) {
				normalized.add(identifier);
			}
		}
		return String.join(".", normalized);
	}

	private String normalizeIdentifier(String value) {
		if (value == null) {
			return "";
		}
		String normalized = value.trim();
		while (normalized.length() >= 2 && ((normalized.startsWith("`") && normalized.endsWith("`"))
				|| (normalized.startsWith("\"") && normalized.endsWith("\""))
				|| (normalized.startsWith("[") && normalized.endsWith("]")))) {
			normalized = normalized.substring(1, normalized.length() - 1).trim();
		}
		return normalized.toLowerCase(Locale.ROOT);
	}

	private String unqualifiedName(String tableName) {
		int index = tableName.lastIndexOf('.');
		return index < 0 ? tableName : tableName.substring(index + 1);
	}

	private String qualifier(String tableName) {
		int index = tableName.lastIndexOf('.');
		return index < 0 ? "" : tableName.substring(0, index);
	}

	public record GuardResult(String dbType, Set<String> referencedTables) {
	}

}
