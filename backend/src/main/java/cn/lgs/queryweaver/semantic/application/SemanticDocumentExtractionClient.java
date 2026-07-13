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
package cn.lgs.queryweaver.semantic.application;

import cn.lgs.queryweaver.model.ModelCallPurpose;
import cn.lgs.queryweaver.model.QueryWeaverModelGateway;
import cn.lgs.queryweaver.model.QueryWeaverModelGateway.ModelCallResult;
import cn.lgs.queryweaver.service.llm.LlmInvocationOptions;
import cn.lgs.queryweaver.service.llm.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Semantic-model adapter. Transport resilience lives exclusively in {@link QueryWeaverModelGateway};
 * semantic callers only declare the purpose of the call.
 */
@Component
public class SemanticDocumentExtractionClient {

	private final QueryWeaverModelGateway modelGateway;

	@Autowired
	public SemanticDocumentExtractionClient(QueryWeaverModelGateway modelGateway) {
		this.modelGateway = modelGateway;
	}

	/** Lightweight constructor retained for focused tests and non-Spring tools. */
	public SemanticDocumentExtractionClient(LlmService llmService) {
		this(new QueryWeaverModelGateway(llmService));
	}

	public String complete(String systemPrompt, String userPrompt) {
		return complete(ModelCallPurpose.OTHER, systemPrompt, userPrompt).response();
	}

	public ModelCallResult complete(ModelCallPurpose purpose, String systemPrompt, String userPrompt) {
		return modelGateway.complete(purpose, systemPrompt, userPrompt);
	}

	public ModelCallResult complete(ModelCallPurpose purpose, String systemPrompt, String userPrompt,
			LlmInvocationOptions invocationOptions) {
		return modelGateway.complete(purpose, systemPrompt, userPrompt, invocationOptions);
	}
}
