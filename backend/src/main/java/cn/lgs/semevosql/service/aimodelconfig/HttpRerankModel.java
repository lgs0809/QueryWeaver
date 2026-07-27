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
package cn.lgs.semevosql.service.aimodelconfig;

import cn.lgs.semevosql.semantic.retrieval.RerankModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** HTTP adapter for the common query/documents rerank API used by Cohere/Jina/SiliconFlow-style services. */
final class HttpRerankModel implements RerankModel {

	private static final String DEFAULT_RERANK_PATH = "/v1/rerank";

	private final RestClient restClient;

	private final String rerankPath;

	private final String modelName;

	HttpRerankModel(RestClient.Builder builder, String baseUrl, String apiKey, String rerankPath, String modelName) {
		ModelEndpointResolver.Endpoint endpoint = ModelEndpointResolver.resolve(baseUrl, rerankPath, DEFAULT_RERANK_PATH);
		RestClient.Builder configured = builder.baseUrl(endpoint.baseUrl());
		if (StringUtils.hasText(apiKey)) {
			configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
		}
		this.restClient = configured.build();
		this.rerankPath = endpoint.path();
		this.modelName = modelName;
	}

	@Override
	public List<RerankScore> rerank(String query, List<String> documents, int topN) {
		if (!StringUtils.hasText(query) || documents == null || documents.isEmpty() || topN <= 0) {
			return List.of();
		}
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("model", modelName);
		request.put("query", query);
		request.put("documents", documents);
		request.put("top_n", Math.min(topN, documents.size()));
		JsonNode body = restClient.post().uri(rerankPath).body(request).retrieve().body(JsonNode.class);
		if (body == null) {
			throw new IllegalStateException("Rerank service returned an empty response");
		}
		JsonNode results = body.path("results");
		if (!results.isArray()) {
			results = body.path("data");
		}
		if (!results.isArray()) {
			throw new IllegalStateException("Rerank response does not contain a results array");
		}
		List<RerankScore> scores = new ArrayList<>();
		for (JsonNode result : results) {
			int index = result.path("index").asInt(-1);
			JsonNode scoreNode = result.has("relevance_score") ? result.get("relevance_score") : result.get("score");
			if (index >= 0 && index < documents.size() && scoreNode != null && scoreNode.isNumber()) {
				scores.add(new RerankScore(index, scoreNode.asDouble()));
			}
		}
		if (scores.isEmpty()) {
			throw new IllegalStateException("Rerank response contains no usable scores");
		}
		return List.copyOf(scores);
	}

}
