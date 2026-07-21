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
package cn.lgs.queryweaver.service.aimodelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cn.lgs.queryweaver.semantic.retrieval.RerankModel.RerankScore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpRerankModelTest {

	@Test
	void sendsCommonRerankRequestAndParsesRelevanceScore() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		HttpRerankModel model = new HttpRerankModel(builder, "https://rerank.example.test/", "secret-token", null,
				"rerank-v1");

		server.expect(requestTo("https://rerank.example.test/v1/rerank"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret-token"))
			.andExpect(jsonPath("$.model").value("rerank-v1"))
			.andExpect(jsonPath("$.query").value("payment amount"))
			.andExpect(jsonPath("$.documents.length()").value(2))
			.andExpect(jsonPath("$.top_n").value(2))
			.andRespond(withSuccess("""
					{"results":[{"index":1,"relevance_score":0.91},{"index":0,"relevance_score":0.42}]}
					""", MediaType.APPLICATION_JSON));

		List<RerankScore> scores = model.rerank("payment amount", List.of("gross amount", "paid amount"), 99);

		assertThat(scores).containsExactly(new RerankScore(1, 0.91d), new RerankScore(0, 0.42d));
		server.verify();
	}

	@Test
	void acceptsDataScoreShapeAndIgnoresInvalidIndexesWithoutAuthorization() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		HttpRerankModel model = new HttpRerankModel(builder, "https://rerank.example.test", "", "rerank", "custom");

		server.expect(requestTo("https://rerank.example.test/rerank"))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
					{"data":[{"index":0,"score":0.7},{"index":9,"score":1.0},{"index":1,"score":0.3}]}
					""", MediaType.APPLICATION_JSON));

		List<RerankScore> scores = model.rerank("query", List.of("a", "b"), 2);

		assertThat(scores).containsExactly(new RerankScore(0, 0.7d), new RerankScore(1, 0.3d));
		server.verify();
	}

}
