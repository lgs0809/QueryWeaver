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

import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Minimal OpenAI-compatible embedding adapter.
 *
 * <p>The Spring AI 1.1.0 OpenAI embedding request DTO is a generic record. In the packaged SemEvoSQL runtime we observed
 * that request serializing to an empty JSON object even though the same client behaved correctly in an isolated unit
 * test. SemEvoSQL only needs the stable OpenAI-compatible contract ({@code input}, {@code model}, optional
 * {@code dimensions}), so this adapter sends that contract explicitly as a map and parses the standard
 * {@code data[].embedding} response. This also keeps provider compatibility independent of Spring AI's internal DTO
 * representation.
 */
final class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {

	private static final String DEFAULT_EMBEDDINGS_PATH = "/v1/embeddings";

	private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingModel.class);

	private final RestClient restClient;

	private final String embeddingsPath;

	private final String defaultModel;

	OpenAiCompatibleEmbeddingModel(RestClient.Builder builder, String baseUrl, String apiKey, String embeddingsPath,
			String defaultModel) {
		ModelEndpointResolver.Endpoint endpoint = ModelEndpointResolver.resolve(baseUrl, embeddingsPath,
				DEFAULT_EMBEDDINGS_PATH);
		RestClient.Builder configured = builder.baseUrl(endpoint.baseUrl())
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		if (StringUtils.hasText(apiKey)) {
			configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
		}
		this.restClient = configured.build();
		this.embeddingsPath = endpoint.path();
		this.defaultModel = defaultModel;
	}

	@Override
	public float[] embed(Document document) {
		EmbeddingResponse response = call(new EmbeddingRequest(List.of(document.getText()), EmbeddingOptions.builder().build()));
		if (response.getResults().isEmpty()) {
			return new float[0];
		}
		return response.getResults().get(0).getOutput();
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		if (request == null || request.getInstructions() == null || request.getInstructions().isEmpty()) {
			return new EmbeddingResponse(List.of());
		}
		// Embedding is an optional retrieval channel. Do not apply the generic model retry policy here: a stalled
		// embedding provider must yield quickly so SemanticRetrievalIndexService can fall back to Exact/BM25 and keep
		// MCP search responsive.
		return invoke(request);
	}

	private EmbeddingResponse invoke(EmbeddingRequest request) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("input", List.copyOf(request.getInstructions()));
		String model = request.getOptions() != null && StringUtils.hasText(request.getOptions().getModel())
				? request.getOptions().getModel() : defaultModel;
		body.put("model", model);
		if (request.getOptions() != null && request.getOptions().getDimensions() != null) {
			body.put("dimensions", request.getOptions().getDimensions());
		}
		String payload;
		try {
			payload = JsonUtil.getObjectMapper().writeValueAsString(body);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize OpenAI-compatible embedding request", ex);
		}
		log.debug("OpenAI-compatible embedding request: inputs={}, keys={}", request.getInstructions().size(),
				body.keySet());
		String response = restClient.post()
			.uri(embeddingsPath)
			.contentType(MediaType.APPLICATION_JSON)
			.body(payload)
			.retrieve()
			.body(String.class);
		EmbeddingResponse parsed = parse(response);
		log.debug("OpenAI-compatible embedding response: vectors={}", parsed.getResults().size());
		return parsed;
	}

	private EmbeddingResponse parse(String response) {
		try {
			JsonNode root = JsonUtil.getObjectMapper().readTree(response);
			JsonNode data = root.path("data");
			if (!data.isArray()) {
				throw new IllegalStateException("OpenAI-compatible embedding response has no data array");
			}
			List<Embedding> embeddings = new ArrayList<>();
			for (JsonNode item : data) {
				JsonNode vector = item.path("embedding");
				if (!vector.isArray() || vector.isEmpty()) {
					throw new IllegalStateException("OpenAI-compatible embedding response contains an empty vector");
				}
				float[] output = new float[vector.size()];
				for (int index = 0; index < vector.size(); index++) {
					output[index] = (float) vector.get(index).asDouble();
				}
				embeddings.add(new Embedding(output, item.path("index").asInt(embeddings.size())));
			}
			embeddings.sort(Comparator.comparingInt(Embedding::getIndex));
			return new EmbeddingResponse(List.copyOf(embeddings));
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to parse OpenAI-compatible embedding response", ex);
		}
	}

}
