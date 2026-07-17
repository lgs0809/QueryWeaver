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
package cn.lgs.queryweaver.service.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class GraphServiceImplFailureClassificationTest {

	@Test
	void transientProviderFailuresAreRecoverableAndHaveStableCode() {
		WebClientResponseException unavailable = WebClientResponseException.create(503, "Service Unavailable",
				HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

		assertThat(GraphServiceImpl.isRecoverableModelFailure(unavailable)).isTrue();
		assertThat(GraphServiceImpl.streamErrorCode(unavailable)).isEqualTo("MODEL_PROVIDER_UNAVAILABLE");
	}

	@Test
	void deterministicProviderFourHundredsAreNotAutoRecovered() {
		WebClientResponseException badRequest = WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY,
				new byte[0], StandardCharsets.UTF_8);

		assertThat(GraphServiceImpl.isRecoverableModelFailure(badRequest)).isFalse();
		assertThat(GraphServiceImpl.streamErrorCode(badRequest)).isEqualTo("BadRequest");
	}
}
