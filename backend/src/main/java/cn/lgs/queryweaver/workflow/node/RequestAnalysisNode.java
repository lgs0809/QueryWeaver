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

import static cn.lgs.queryweaver.constant.Constant.ACTIVE_QUERY;
import static cn.lgs.queryweaver.constant.Constant.ACTIVE_TODO_ID;
import static cn.lgs.queryweaver.constant.Constant.APPROVED_PLAN_RECOVERY;
import static cn.lgs.queryweaver.constant.Constant.INPUT_KEY;
import static cn.lgs.queryweaver.constant.Constant.SQL_GENERATION_ONLY;
import static cn.lgs.queryweaver.constant.Constant.ORIGINAL_REQUEST;
import static cn.lgs.queryweaver.constant.Constant.REQUEST_ANALYSIS;
import static cn.lgs.queryweaver.constant.Constant.RESULT;
import static cn.lgs.queryweaver.constant.Constant.RUN_ID;
import static cn.lgs.queryweaver.constant.Constant.TODO_ENABLED;

import cn.lgs.queryweaver.run.QueryRunService;
import cn.lgs.queryweaver.task.QueryDecompositionService;
import cn.lgs.queryweaver.task.QueryDecompositionService.RequestAnalysis;
import cn.lgs.queryweaver.task.QueryDecompositionService.RequestType;
import cn.lgs.queryweaver.task.QueryTask;
import cn.lgs.queryweaver.task.QueryTaskRepository;
import cn.lgs.queryweaver.util.JsonUtil;
import cn.lgs.queryweaver.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Single request-level analysis node. It replaces the old separate intent + decomposition passes and only enables
 * Todo state when the user explicitly asks for multiple independent answer goals.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestAnalysisNode implements NodeAction {

	private final QueryDecompositionService queryDecompositionService;

	private final QueryTaskRepository taskRepository;

	private final QueryRunService runService;

	@Override
	public Map<String, Object> apply(OverAllState state) {
		String original = StateUtil.getStringValue(state, INPUT_KEY, "");
		if (!StringUtils.hasText(original)) {
			throw new IllegalArgumentException("Request analysis requires the original query");
		}
		String runId = StateUtil.getStringValue(state, RUN_ID, null);
		RequestAnalysis analysis = loadPersisted(runId);
		if (analysis == null) {
			// Internal source SQL generation and exact approved-plan recovery both represent one already-governed query
			// contract and must not pay another request-decomposition model call.
			analysis = state.value(SQL_GENERATION_ONLY, false) || state.value(APPROVED_PLAN_RECOVERY, false)
					? RequestAnalysis.simpleDataQuery() : queryDecompositionService.analyze(original);
			persist(runId, analysis);
		}

		Map<String, Object> result = new HashMap<>();
		result.put(ORIGINAL_REQUEST, original);
		result.put(REQUEST_ANALYSIS, analysis);
		result.put(TODO_ENABLED, analysis.needsTodo());

		String activeQuery = original;
		if (analysis.needsTodo()) {
			if (!taskRepository.enabled(runId)) {
				taskRepository.initialize(runId, analysis.tasks());
			}
			QueryTask active = taskRepository.activateFirst(runId);
			result.put(ACTIVE_TODO_ID, active.taskId());
			activeQuery = active.question();
		}
		else {
			result.put(ACTIVE_TODO_ID, "");
		}
		result.put(ACTIVE_QUERY, activeQuery);
		result.put(INPUT_KEY, activeQuery);

		if (analysis.requestType() == RequestType.NON_DATA_QUERY) {
			result.put(RESULT, "当前 Project Chat 仅处理项目数据查询与分析请求。");
		}
		log.info("Request analysis completed: runId={}, type={}, todoEnabled={}, todoCount={}", runId,
				analysis.requestType(), analysis.needsTodo(), analysis.tasks().size());
		return result;
	}

	private RequestAnalysis loadPersisted(String runId) {
		if (!StringUtils.hasText(runId)) {
			return null;
		}
		try {
			var event = runService.eventByIdempotency(runId, "request-analysis:" + runId).orElse(null);
			if (event == null || !StringUtils.hasText(event.payload())) {
				return null;
			}
			return JsonUtil.getObjectMapper().readValue(event.payload(), RequestAnalysis.class);
		}
		catch (Exception ex) {
			log.warn("Unable to reuse persisted request analysis for run {}: {}", runId, ex.getMessage());
			return null;
		}
	}

	private void persist(String runId, RequestAnalysis analysis) {
		if (!StringUtils.hasText(runId)) {
			return;
		}
		try {
			String payload = JsonUtil.getObjectMapper().writeValueAsString(analysis);
			runService.appendEvent(runId, "REQUEST_ANALYSIS_COMPLETED", "request-analysis", payload,
					analysis.needsTodo() ? "Multiple independent answer goals detected" : "Simple request fast path selected",
					"request-analysis:" + runId);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to persist request analysis", ex);
		}
	}
}
