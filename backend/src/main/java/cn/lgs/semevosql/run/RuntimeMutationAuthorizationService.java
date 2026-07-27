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
package cn.lgs.semevosql.run;

import cn.lgs.semevosql.clarification.RuntimePrincipalResolver;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.OperatorRole;
import cn.lgs.semevosql.project.security.ProjectAccessRole;
import cn.lgs.semevosql.project.security.ProjectAccessService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Authorizes user-owned mutations of an already-created interactive Query Run. */
@Service
@RequiredArgsConstructor
public class RuntimeMutationAuthorizationService {

	private final QueryRunService runService;

	private final RuntimePrincipalResolver principalResolver;

	private final ProjectAccessService projectAccessService;

	private final JdbcTemplate jdbc;

	public QueryRun requireRunOwnerOrAdmin(String runId, OperatorContext operator) {
		if (operator == null) {
			throw new SecurityException("A server-resolved OperatorContext is required for runtime mutation");
		}
		QueryRun run = runService.get(runId);
		projectAccessService.requireAccess(run.projectId(), operator, ProjectAccessRole.VIEWER);
		if (operator.role() == OperatorRole.ADMIN) {
			return run;
		}
		String principal = principalResolver.resolve(run);
		if (!StringUtils.hasText(principal) || RuntimePrincipalResolver.ANONYMOUS.equals(principal)) {
			throw new SecurityException("Runtime mutation requires a durable authenticated Run owner");
		}
		if (!Objects.equals(principal, operator.operator())) {
			throw new SecurityException("Runtime mutation is only allowed for the Run owner");
		}
		return run;
	}

	public QueryRun requireEpisodeOwnerOrAdmin(String episodeId, OperatorContext operator) {
		String runId = jdbc.query("""
				SELECT run_id FROM qw_query_run
				WHERE episode_id = ?
				ORDER BY create_time DESC
				LIMIT 1
				""", (rs, rowNum) -> rs.getString(1), episodeId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("No Query Run found for episode: " + episodeId));
		return requireRunOwnerOrAdmin(runId, operator);
	}

}
