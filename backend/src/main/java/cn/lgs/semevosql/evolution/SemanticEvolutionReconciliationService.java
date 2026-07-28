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

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.OperatorRole;
import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.CandidateStatus;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.Mutation;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Repairs durable EvaluationJob/Candidate projection drift after crashes or duplicate
 * callbacks.
 */
@Service
public class SemanticEvolutionReconciliationService {

	private final JdbcTemplate jdbc;

	private final SemanticEvolutionStateMachine stateMachine;

	private final SemanticEvolutionAuditService auditService;

	private final VersionedJson versionedJson;

	public SemanticEvolutionReconciliationService(JdbcTemplate jdbc, SemanticEvolutionStateMachine stateMachine,
			SemanticEvolutionAuditService auditService, VersionedJson versionedJson) {
		this.jdbc = jdbc;
		this.stateMachine = stateMachine;
		this.auditService = auditService;
		this.versionedJson = versionedJson;
	}

	@Scheduled(fixedDelayString = "${semevosql.evolution.reconciliation-delay-ms:30000}")
	public void scheduledReconcile() {
		reconcile();
	}

	@Transactional
	public int reconcile() {
		int repaired = 0;
		for (Map<String, Object> row : jdbc.queryForList("""
				SELECT j.id job_id, j.run_id, j.status job_status, j.result_json, j.error_message,
				       c.id candidate_id, c.status candidate_status, c.revision,
				       c.source_version_id, c.target_draft_version_id, c.patch_hash
				FROM qw_evaluation_job j
				JOIN qw_semantic_evolution_candidate c ON c.id = j.candidate_id
				WHERE j.job_type = 'SEMANTIC_REPLAY'
				  AND j.status IN ('SUCCEEDED','FAILED','CANCELLED')
				  AND c.status = 'REPLAY_RUNNING'
				ORDER BY j.update_time, j.id
				""")) {
			repair(row);
			repaired++;
		}
		return repaired;
	}

	private void repair(Map<String, Object> row) {
		String jobStatus = text(row.get("job_status"));
		String candidateId = text(row.get("candidate_id"));
		String jobId = text(row.get("job_id"));
		long revision = number(row.get("revision"));
		CandidateStatus target;
		Mutation mutation;
		Map<String, Object> evidence;
		if ("CANCELLED".equals(jobStatus)) {
			target = CandidateStatus.PATCH_APPLIED;
			mutation = Mutation.none();
			evidence = Map.of("jobStatus", jobStatus, "replayRunId", jobId);
		}
		else if ("SUCCEEDED".equals(jobStatus)) {
			String resultJson = text(row.get("result_json"));
			if (!StringUtils.hasText(resultJson)) {
				throw new IllegalStateException("Succeeded Replay Job has no result_json: " + jobId);
			}
			Map<String, Object> summary = versionedJson.readMap(resultJson, JsonPayloadRegistry.REPLAY_SUMMARY);
			boolean allPassed = Boolean.TRUE.equals(summary.get("allPassed"));
			target = allPassed ? CandidateStatus.REPLAY_PASSED : CandidateStatus.REPLAY_FAILED;
			mutation = Mutation.replayCompleted(resultJson);
			evidence = Map.of("jobStatus", jobStatus, "replayRunId", jobId, "allPassed", allPassed);
		}
		else {
			target = CandidateStatus.REPLAY_FAILED;
			String summary = versionedJson.write(JsonPayloadRegistry.REPLAY_SUMMARY, Map.of("allPassed", false,
					"replayRunId", jobId, "error", Objects.toString(row.get("error_message"), "Replay Job failed")));
			mutation = Mutation.replayCompleted(summary);
			evidence = Map.of("jobStatus", jobStatus, "replayRunId", jobId, "error",
					Objects.toString(row.get("error_message"), "Replay Job failed"));
		}
		stateMachine.transition(candidateId, CandidateStatus.REPLAY_RUNNING, revision, target, mutation);
		OperatorContext operator = new OperatorContext("semevosql-system", OperatorRole.ADMIN,
				"RECONCILIATION_WORKER", jobId, "semantic-replay-reconcile:" + jobId + ":" + target.name());
		auditService.append(candidateId, "REPLAY_STATE_RECONCILED", "REPLAY_RUNNING", target.name(), operator,
				nullableNumber(row.get("source_version_id")), nullableNumber(row.get("target_draft_version_id")),
				text(row.get("patch_hash")), text(row.get("run_id")), Map.of("jobId", jobId), evidence);
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private long number(Object value) {
		return value == null ? 0 : ((Number) value).longValue();
	}

	private Long nullableNumber(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

}
