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
package cn.lgs.semevosql.project.security;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.OperatorRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Server-side project membership and authorization boundary. */
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

	private final JdbcTemplate jdbc;

	@Transactional
	public void grantOwner(Long projectId, OperatorContext operator) {
		requireIdentity(operator);
		upsert(projectId, operator.operator(), ProjectAccessRole.OWNER, operator.operator());
	}

	public ProjectAccessRole requireAccess(Long projectId, OperatorContext operator, ProjectAccessRole required) {
		requireIdentity(operator);
		if (operator.role() == OperatorRole.ADMIN) {
			return ProjectAccessRole.OWNER;
		}
		ProjectAccessRole actual = membership(projectId, operator.operator()).orElse(null);
		if (actual == null || !actual.atLeast(required)) {
			throw new ProjectAccessDeniedException(projectId, operator.operator(), required, actual);
		}
		return actual;
	}

	public Set<Long> accessibleProjectIds(OperatorContext operator) {
		requireIdentity(operator);
		if (operator.role() == OperatorRole.ADMIN) {
			return Set.of();
		}
		return jdbc.queryForList("SELECT project_id FROM qw_project_member WHERE operator_id = ?", Long.class,
				operator.operator()).stream().collect(Collectors.toUnmodifiableSet());
	}

	public boolean isGlobalAdmin(OperatorContext operator) {
		return operator != null && operator.role() == OperatorRole.ADMIN;
	}

	public List<ProjectMembership> listMembers(Long projectId, OperatorContext operator) {
		requireAccess(projectId, operator, ProjectAccessRole.OWNER);
		return jdbc.query("""
				SELECT project_id, operator_id, access_role, granted_by, create_time, update_time
				FROM qw_project_member
				WHERE project_id = ?
				ORDER BY access_role DESC, operator_id ASC
				""", (rs, rowNum) -> new ProjectMembership(rs.getLong("project_id"), rs.getString("operator_id"),
				ProjectAccessRole.valueOf(rs.getString("access_role")), rs.getString("granted_by"),
				rs.getTimestamp("create_time").toLocalDateTime(), rs.getTimestamp("update_time").toLocalDateTime()),
				projectId);
	}

	@Transactional
	public ProjectMembership grant(Long projectId, String member, ProjectAccessRole role, OperatorContext operator) {
		requireAccess(projectId, operator, ProjectAccessRole.OWNER);
		String normalizedMember = normalizeMember(member);
		if (role == null) {
			throw new IllegalArgumentException("Project access role is required");
		}
		upsert(projectId, normalizedMember, role, operator.operator());
		return membershipView(projectId, normalizedMember)
			.orElseThrow(() -> new IllegalStateException("Project membership was not persisted"));
	}

	@Transactional
	public void revoke(Long projectId, String member, OperatorContext operator) {
		requireAccess(projectId, operator, ProjectAccessRole.OWNER);
		String normalizedMember = normalizeMember(member);
		ProjectAccessRole current = membership(projectId, normalizedMember).orElse(null);
		if (current == ProjectAccessRole.OWNER && ownerCount(projectId) <= 1) {
			throw new IllegalStateException("A project must retain at least one owner");
		}
		jdbc.update("DELETE FROM qw_project_member WHERE project_id = ? AND operator_id = ?", projectId,
				normalizedMember);
	}

	public Optional<Long> projectForRun(String runId) {
		return oneLong("SELECT project_id FROM qw_query_run WHERE run_id = ?", runId);
	}

	public Optional<Long> projectForGap(Long gapId) {
		return oneLong("SELECT project_id FROM qw_semantic_gap WHERE id = ?", gapId);
	}

	public Optional<Long> projectForEvolutionCandidate(String candidateId) {
		return oneLong("SELECT project_id FROM qw_semantic_evolution_candidate WHERE id = ?", candidateId);
	}

	public Optional<Long> projectForSemanticChangeSet(String changeSetId) {
		return oneLong("SELECT project_id FROM qw_semantic_change_set WHERE id = ?", changeSetId);
	}

	public Optional<Long> projectForOptimizationCandidate(String candidateId) {
		return oneLong("SELECT project_id FROM qw_runtime_optimization_candidate WHERE id = ?", candidateId);
	}

	public Optional<Long> projectForEvaluationJob(String jobId) {
		return oneLong("SELECT project_id FROM qw_evaluation_job WHERE id = ?", jobId);
	}

	public Optional<Long> projectForRelease(String releaseId) {
		return oneLong("SELECT project_id FROM qw_release WHERE id = ?", releaseId);
	}

	public Optional<Long> projectForEpisode(String episodeId) {
		return oneLong("SELECT project_id FROM qw_episode WHERE id = ?", episodeId);
	}

	public Optional<Long> projectForAttempt(String attemptId) {
		return oneLong("""
				SELECT e.project_id
				FROM qw_attempt a
				JOIN qw_episode e ON e.id = a.episode_id
				WHERE a.id = ?
				""", attemptId);
	}

	public Optional<Long> projectForTrajectoryPattern(String patternId) {
		return oneLong("SELECT project_id FROM qw_query_pattern WHERE id = ?", patternId);
	}

	private Optional<ProjectAccessRole> membership(Long projectId, String operator) {
		return jdbc
			.query("SELECT access_role FROM qw_project_member WHERE project_id = ? AND operator_id = ?",
					(rs, rowNum) -> ProjectAccessRole.valueOf(rs.getString(1)), projectId, operator)
			.stream()
			.findFirst();
	}

	private Optional<ProjectMembership> membershipView(Long projectId, String operator) {
		return jdbc
			.query("""
					SELECT project_id, operator_id, access_role, granted_by, create_time, update_time
					FROM qw_project_member WHERE project_id = ? AND operator_id = ?
					""", (rs, rowNum) -> new ProjectMembership(rs.getLong("project_id"), rs.getString("operator_id"),
					ProjectAccessRole.valueOf(rs.getString("access_role")), rs.getString("granted_by"),
					rs.getTimestamp("create_time").toLocalDateTime(), rs.getTimestamp("update_time").toLocalDateTime()),
					projectId, operator)
			.stream()
			.findFirst();
	}

	private void upsert(Long projectId, String member, ProjectAccessRole role, String grantedBy) {
		int updated = jdbc.update("""
				UPDATE qw_project_member
				SET access_role = ?, granted_by = ?, update_time = CURRENT_TIMESTAMP
				WHERE project_id = ? AND operator_id = ?
				""", role.name(), grantedBy, projectId, member);
		if (updated > 0) {
			return;
		}
		try {
			jdbc.update("""
					INSERT INTO qw_project_member(project_id, operator_id, access_role, granted_by)
					VALUES (?, ?, ?, ?)
					""", projectId, member, role.name(), grantedBy);
		}
		catch (DuplicateKeyException ignored) {
			jdbc.update("""
					UPDATE qw_project_member
					SET access_role = ?, granted_by = ?, update_time = CURRENT_TIMESTAMP
					WHERE project_id = ? AND operator_id = ?
					""", role.name(), grantedBy, projectId, member);
		}
	}

	private int ownerCount(Long projectId) {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM qw_project_member WHERE project_id = ? AND access_role = 'OWNER'", Integer.class,
				projectId);
		return count == null ? 0 : count;
	}

	private Optional<Long> oneLong(String sql, Object value) {
		return jdbc.query(sql, (rs, rowNum) -> {
			long projectId = rs.getLong(1);
			return rs.wasNull() ? null : projectId;
		}, value).stream().filter(projectId -> projectId != null).findFirst();
	}

	private void requireIdentity(OperatorContext operator) {
		if (operator == null || !StringUtils.hasText(operator.operator()) || operator.role() == null) {
			throw new SecurityException("Authenticated operator identity is required");
		}
	}

	private String normalizeMember(String member) {
		if (!StringUtils.hasText(member)) {
			throw new IllegalArgumentException("Project member identity is required");
		}
		String normalized = member.trim();
		if (normalized.length() > 128) {
			throw new IllegalArgumentException("Project member identity exceeds 128 characters");
		}
		return normalized;
	}

	public record ProjectMembership(Long projectId, String operatorId, ProjectAccessRole accessRole, String grantedBy,
			LocalDateTime createTime, LocalDateTime updateTime) {
	}

	public static class ProjectAccessDeniedException extends SecurityException {

		public ProjectAccessDeniedException(Long projectId, String operator, ProjectAccessRole required,
				ProjectAccessRole actual) {
			super("Operator " + operator + " cannot access project " + projectId + "; required=" + required
					+ ", actual=" + actual);
		}

	}

}
