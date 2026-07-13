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

import cn.lgs.queryweaver.semantic.domain.SemanticQueryPlan;
import cn.lgs.queryweaver.sql.application.TypedPlanSqlConstraintValidator;
import cn.lgs.queryweaver.sql.application.TypedPlanSqlConstraintValidator.ValidationResult;
import cn.lgs.queryweaver.util.ChatResponseUtil;
import cn.lgs.queryweaver.util.FluxUtil;
import cn.lgs.queryweaver.util.JsonUtil;
import cn.lgs.queryweaver.util.StateUtil;
import cn.lgs.queryweaver.dto.datasource.SqlRetryDto;
import cn.lgs.queryweaver.dto.prompt.SemanticConsistencyDTO;
import cn.lgs.queryweaver.dto.schema.SchemaDTO;
import cn.lgs.queryweaver.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.List;

import static cn.lgs.queryweaver.constant.Constant.*;
import static cn.lgs.queryweaver.util.PlanProcessUtil.getCurrentExecutionStepInstruction;
import static cn.lgs.queryweaver.prompt.PromptHelper.buildMixMacSqlDbPrompt;

/**
 * Semantic consistency validation node that checks SQL query semantic consistency.
 *
 * This node is responsible for: - Validating SQL query semantic consistency against
 * schema and evidence - Providing validation results for query refinement - Handling
 * validation failures with recommendations - Managing step progression in execution plan
 *
 * @author zhangshenghang
 */
@Slf4j
@Component
@AllArgsConstructor
public class SemanticConsistencyNode implements NodeAction {

	private final Nl2SqlService nl2SqlService;

	private final TypedPlanSqlConstraintValidator constraintValidator;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {

		// Get necessary input parameters
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		String dialect = StateUtil.getStringValue(state, DB_DIALECT_TYPE);
		// Get current execution step and SQL query
		String sql = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT);
		String userQuery = StateUtil.getCanonicalQuery(state);
		String semanticModel = StateUtil.getStringValue(state, GENEGRATED_SEMANTIC_MODEL_PROMPT, "");
		SemanticQueryPlan semanticPlan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticQueryPlan.class,
				(SemanticQueryPlan) null);
		List<Object> compiledParameters = StateUtil.getObjectValue(state, SQL_COMPILED_PARAMETERS, List.class,
				List.of());
		String compilerMode = StateUtil.getStringValue(state, SQL_COMPILER_MODE, "CONSTRAINED_GENERATION");

		SemanticConsistencyDTO semanticConsistencyDTO = SemanticConsistencyDTO.builder()
			.dialect(dialect)
			.sql(sql)
			.executionDescription(getCurrentExecutionStepInstruction(state))
			.schemaInfo(buildMixMacSqlDbPrompt(schemaDTO, true))
			.semanticModel(semanticModel)
			.semanticPlan(serializeSemanticPlan(semanticPlan))
			.userQuery(userQuery)
			.evidence(evidence)
			.build();
		log.info("Starting semantic consistency validation - SQL: {}", sql);
		ValidationResult deterministicResult = constraintValidator.validate(sql, compiledParameters, semanticPlan);
		Flux<ChatResponse> validationResultFlux;
		if (!deterministicResult.valid()) {
			String reason = "不通过。确定性语义约束校验失败: " + String.join("; ", deterministicResult.errors());
			log.warn("{}", reason);
			validationResultFlux = Flux.just(ChatResponseUtil.createPureResponse(reason));
		}
		else if ("DETERMINISTIC".equalsIgnoreCase(compilerMode) || "PATTERN_TEMPLATE".equalsIgnoreCase(compilerMode)) {
			validationResultFlux = Flux
				.just(ChatResponseUtil.createPureResponse("PATTERN_TEMPLATE".equalsIgnoreCase(compilerMode)
						? "通过。已验证 Query Pattern 模板与当前确定性语义计划一致" : "通过。确定性编译器输出与冻结语义计划一致"));
		}
		else {
			validationResultFlux = nl2SqlService.performSemanticConsistency(semanticConsistencyDTO);
		}

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "开始语义一致性校验", "语义一致性校验完成", validationResult -> {
					boolean isPassed = !validationResult.startsWith("不通过");
					Map<String, Object> result = buildValidationResult(isPassed, validationResult);
					log.info("[{}] Semantic consistency validation result: {}, passed: {}",
							this.getClass().getSimpleName(), validationResult, isPassed);
					return result;
				}, validationResultFlux);

		return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, generator);
	}

	/**
	 * Build validation result
	 */
	private Map<String, Object> buildValidationResult(boolean passed, String validationResult) {
		if (passed) {
			return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, true);
		}
		else {
			return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, false, SQL_REGENERATE_REASON,
					SqlRetryDto.semantic(validationResult));
		}
	}

	private String serializeSemanticPlan(SemanticQueryPlan semanticPlan) {
		if (semanticPlan == null) {
			return "{}";
		}
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(semanticPlan);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize typed semantic plan", ex);
		}
	}

}
