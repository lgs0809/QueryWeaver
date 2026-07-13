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
package cn.lgs.queryweaver.clarification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exact project-scoped per-user phrase -> governed semantic asset preferences. */
@Service
public class UserSemanticPreferenceService {

	private static final Set<String> PERSONAL_BINDING_TYPES = Set.of("METRIC", "DIMENSION", "ENUM_VALUE", "TIME_COLUMN");

	private final JdbcTemplate jdbc;

	private final SemanticBindingTargetValidator targetValidator;

	public UserSemanticPreferenceService(JdbcTemplate jdbc, SemanticBindingTargetValidator targetValidator) {
		this.jdbc = jdbc;
		this.targetValidator = targetValidator;
	}

	public Optional<UserSemanticPreference> find(Long projectId, String userId, String rawPhrase) {
		String normalized = normalizePhrase(rawPhrase);
		if (projectId == null || !hasText(userId) || normalized.isBlank()) {
			return Optional.empty();
		}
		return jdbc.query("""
				SELECT * FROM qw_user_semantic_preference
				WHERE project_id = ? AND user_id = ? AND normalized_phrase = ?
				""", this::map, projectId, userId.trim(), normalized).stream().findFirst();
	}

	public List<UserSemanticPreference> applicable(Long projectId, String userId, String query) {
		String normalizedQuery = normalizePhrase(query);
		if (projectId == null || !hasText(userId) || normalizedQuery.isBlank()) {
			return List.of();
		}
		return jdbc.query("""
				SELECT * FROM qw_user_semantic_preference
				WHERE project_id = ? AND user_id = ?
				ORDER BY LENGTH(normalized_phrase) DESC, id
				""", this::map, projectId, userId.trim())
			.stream()
			.filter(value -> !value.normalizedPhrase().isBlank() && normalizedQuery.contains(value.normalizedPhrase()))
			.toList();
	}

	@Transactional
	public UserSemanticPreference save(Long projectId, String userId, String rawPhrase, String assetType,
			String assetKey, String businessLabel) {
		requirePersonalType(assetType);
		String normalized = requiredNormalize(rawPhrase);
		String principal = required(userId, "userId");
		String targetKey = required(assetKey, "assetKey");
		targetValidator.requireActiveAsset(projectId, assetType, targetKey);
		String label = required(businessLabel, "businessLabel");
		UserSemanticPreference existing = find(projectId, principal, rawPhrase).orElse(null);
		if (existing == null) {
			try {
				jdbc.update("""
						INSERT INTO qw_user_semantic_preference
						(project_id, user_id, normalized_phrase, display_phrase, asset_type, asset_key, business_label,
						 hit_count, correction_count, next_upgrade_prompt_at, upgrade_prompt_pending, upgrade_dismissed)
						VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 10, FALSE, FALSE)
						""", projectId, principal, normalized, rawPhrase.trim(), assetType, targetKey, label);
			}
			catch (DuplicateKeyException ignored) {
				return save(projectId, principal, rawPhrase, assetType, targetKey, label);
			}
		}
		else {
			boolean changedTarget = !existing.assetType().equals(assetType) || !existing.assetKey().equals(targetKey);
			jdbc.update("""
					UPDATE qw_user_semantic_preference
					SET display_phrase = ?, asset_type = ?, asset_key = ?, business_label = ?,
					    hit_count = CASE WHEN ? THEN 0 ELSE hit_count END,
					    correction_count = correction_count + CASE WHEN ? THEN 1 ELSE 0 END,
					    next_upgrade_prompt_at = CASE WHEN ? THEN 10 ELSE next_upgrade_prompt_at END,
					    upgrade_prompt_pending = CASE WHEN ? THEN FALSE ELSE upgrade_prompt_pending END,
					    upgrade_dismissed = CASE WHEN ? THEN FALSE ELSE upgrade_dismissed END,
					    update_time = CURRENT_TIMESTAMP
					WHERE id = ?
					""", rawPhrase.trim(), assetType, targetKey, label, changedTarget, changedTarget, changedTarget,
					changedTarget, changedTarget, existing.id());
			if (changedTarget) {
				jdbc.update("""
						UPDATE qw_user_semantic_preference_usage
						SET valid = FALSE, update_time = CURRENT_TIMESTAMP
						WHERE preference_id = ? AND valid = TRUE
						""", existing.id());
			}
		}
		return find(projectId, principal, rawPhrase).orElseThrow();
	}

	@Transactional
	public void delete(Long projectId, String userId, String rawPhrase) {
		jdbc.update(
				"DELETE FROM qw_user_semantic_preference WHERE project_id = ? AND user_id = ? AND normalized_phrase = ?",
				projectId, required(userId, "userId"), requiredNormalize(rawPhrase));
	}

	@Transactional
	public void recordApplied(Long preferenceId, String runId) {
		if (preferenceId == null || !hasText(runId)) {
			return;
		}
		String key = "preference-applied:" + preferenceId + ":" + runId;
		jdbc.update("""
				INSERT INTO qw_user_semantic_preference_usage
				(preference_id, run_id, event_type, valid, idempotency_key)
				VALUES (?, ?, 'APPLIED', TRUE, ?)
				ON CONFLICT (idempotency_key) DO NOTHING
				""", preferenceId, runId, key);
	}

	@Transactional
	public List<UpgradePrompt> finalizeSuccessfulRun(String runId) {
		if (!hasText(runId)) {
			return List.of();
		}
		String status = jdbc
			.query("SELECT status FROM qw_query_run WHERE run_id = ?", (rs, rowNum) -> rs.getString(1), runId)
			.stream()
			.findFirst()
			.orElse(null);
		if (!"SUCCEEDED".equals(status)) {
			return List.of();
		}
		Set<Long> preferenceIds = new LinkedHashSet<>(jdbc.query("""
				SELECT DISTINCT preference_id FROM qw_user_semantic_preference_usage
				WHERE run_id = ? AND event_type = 'APPLIED' AND valid = TRUE
				""", (rs, rowNum) -> rs.getLong(1), runId));
		List<UpgradePrompt> prompts = new java.util.ArrayList<>();
		for (Long preferenceId : preferenceIds) {
			int newlyCounted = jdbc.update("""
					UPDATE qw_user_semantic_preference_usage u
					SET event_type = 'COUNTED', update_time = CURRENT_TIMESTAMP
					FROM qw_query_run r
					WHERE u.run_id = r.run_id
					  AND u.preference_id = ?
					  AND u.event_type = 'APPLIED'
					  AND u.valid = TRUE
					  AND r.status = 'SUCCEEDED'
					""", preferenceId);
			if (newlyCounted > 0) {
				jdbc.update(
						"""
								UPDATE qw_user_semantic_preference
								SET hit_count = hit_count + ?, last_used_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
								WHERE id = ?
								""",
						newlyCounted, preferenceId);
			}
			UserSemanticPreference preference = refreshPromotionState(preferenceId);
			if (preference.upgradePromptPending() && !preference.upgradeDismissed()) {
				prompts.add(new UpgradePrompt(preference.id(), preference.displayPhrase(), preference.businessLabel(),
						preference.hitCount()));
			}
		}
		return List.copyOf(prompts);
	}

	@Transactional
	public void invalidateRunUsage(String runId, Long preferenceId) {
		if (!hasText(runId)) {
			return;
		}
		List<Map<String, Object>> affected;
		if (preferenceId == null) {
			affected = jdbc.queryForList("""
					SELECT preference_id, event_type
					FROM qw_user_semantic_preference_usage
					WHERE run_id = ? AND event_type IN ('APPLIED', 'COUNTED') AND valid = TRUE
					""", runId);
		}
		else {
			affected = jdbc.queryForList("""
					SELECT preference_id, event_type
					FROM qw_user_semantic_preference_usage
					WHERE run_id = ? AND preference_id = ?
					  AND event_type IN ('APPLIED', 'COUNTED') AND valid = TRUE
					""", runId, preferenceId);
		}
		if (affected.isEmpty()) {
			return;
		}
		if (preferenceId == null) {
			jdbc.update("""
					UPDATE qw_user_semantic_preference_usage
					SET valid = FALSE, update_time = CURRENT_TIMESTAMP
					WHERE run_id = ? AND event_type IN ('APPLIED', 'COUNTED') AND valid = TRUE
					""", runId);
		}
		else {
			jdbc.update("""
					UPDATE qw_user_semantic_preference_usage
					SET valid = FALSE, update_time = CURRENT_TIMESTAMP
					WHERE run_id = ? AND preference_id = ?
					  AND event_type IN ('APPLIED', 'COUNTED') AND valid = TRUE
					""", runId, preferenceId);
		}
		Map<Long, Long> countedByPreference = affected.stream()
			.filter(row -> "COUNTED".equals(row.get("event_type")))
			.collect(java.util.stream.Collectors.groupingBy(row -> ((Number) row.get("preference_id")).longValue(),
					java.util.stream.Collectors.counting()));
		for (Long affectedPreferenceId : affected.stream()
			.map(row -> ((Number) row.get("preference_id")).longValue())
			.distinct()
			.toList()) {
			long counted = countedByPreference.getOrDefault(affectedPreferenceId, 0L);
			jdbc.update("""
					UPDATE qw_user_semantic_preference
					SET hit_count = GREATEST(0, hit_count - ?), correction_count = correction_count + 1,
					    update_time = CURRENT_TIMESTAMP
					WHERE id = ?
					""", counted, affectedPreferenceId);
			refreshPromotionState(affectedPreferenceId);
		}
	}

	@Transactional
	public UserSemanticPreference continuePersonal(Long preferenceId) {
		UserSemanticPreference current = requireById(preferenceId);
		long next = Math.max(current.nextUpgradePromptAt(), ((current.hitCount() / 10) + 1) * 10);
		jdbc.update("""
				UPDATE qw_user_semantic_preference
				SET upgrade_prompt_pending = FALSE, next_upgrade_prompt_at = ?, update_time = CURRENT_TIMESTAMP
				WHERE id = ?
				""", next, preferenceId);
		return requireById(preferenceId);
	}

	@Transactional
	public UserSemanticPreference dismissUpgrade(Long preferenceId) {
		jdbc.update("""
				UPDATE qw_user_semantic_preference
				SET upgrade_prompt_pending = FALSE, upgrade_dismissed = TRUE, update_time = CURRENT_TIMESTAMP
				WHERE id = ?
				""", preferenceId);
		return requireById(preferenceId);
	}

	public Optional<UserSemanticPreference> findById(Long preferenceId) {
		return jdbc.query("SELECT * FROM qw_user_semantic_preference WHERE id = ?", this::map, preferenceId)
			.stream()
			.findFirst();
	}

	private UserSemanticPreference refreshPromotionState(Long preferenceId) {
		UserSemanticPreference current = requireById(preferenceId);
		boolean pending = !current.upgradeDismissed() && current.hitCount() >= current.nextUpgradePromptAt();
		jdbc.update("""
				UPDATE qw_user_semantic_preference
				SET upgrade_prompt_pending = ?, update_time = CURRENT_TIMESTAMP
				WHERE id = ?
				""", pending, preferenceId);
		return requireById(preferenceId);
	}

	private UserSemanticPreference requireById(Long preferenceId) {
		return findById(preferenceId)
			.orElseThrow(() -> new IllegalArgumentException("User semantic preference not found: " + preferenceId));
	}

	private UserSemanticPreference map(ResultSet rs, int rowNum) throws SQLException {
		return new UserSemanticPreference(rs.getLong("id"), rs.getLong("project_id"), rs.getString("user_id"),
				rs.getString("normalized_phrase"), rs.getString("display_phrase"), rs.getString("asset_type"),
				rs.getString("asset_key"), rs.getString("business_label"), rs.getLong("hit_count"),
				rs.getLong("correction_count"), rs.getLong("next_upgrade_prompt_at"),
				rs.getBoolean("upgrade_prompt_pending"), rs.getBoolean("upgrade_dismissed"),
				time(rs.getTimestamp("create_time")), time(rs.getTimestamp("update_time")),
				time(rs.getTimestamp("last_used_time")));
	}

	public static String normalizePhrase(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "").trim();
	}

	private static String requiredNormalize(String value) {
		String normalized = normalizePhrase(value);
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("rawPhrase is required");
		}
		return normalized;
	}

	private static void requirePersonalType(String assetType) {
		if (!PERSONAL_BINDING_TYPES.contains(assetType)) {
			throw new IllegalArgumentException("Personal preference cannot redefine semantic asset type: " + assetType);
		}
	}

	private static String required(String value, String field) {
		if (!hasText(value)) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static LocalDateTime time(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

	public record UserSemanticPreference(Long id, Long projectId, String userId, String normalizedPhrase,
			String displayPhrase, String assetType, String assetKey, String businessLabel, long hitCount,
			long correctionCount, long nextUpgradePromptAt, boolean upgradePromptPending, boolean upgradeDismissed,
			LocalDateTime createTime, LocalDateTime updateTime, LocalDateTime lastUsedTime) {
	}

	public record UpgradePrompt(Long preferenceId, String phrase, String businessLabel, long hitCount) {
	}

}
