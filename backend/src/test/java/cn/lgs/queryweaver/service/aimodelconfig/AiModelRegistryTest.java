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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cn.lgs.queryweaver.enums.ModelType;
import org.junit.jupiter.api.Test;

class AiModelRegistryTest {

	@Test
	void missingRerankConfigurationIsNegativelyCachedUntilRefresh() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDataService configDataService = mock(ModelConfigDataService.class);
		when(configDataService.getActiveConfigByType(ModelType.RERANK)).thenReturn(null);
		AiModelRegistry registry = new AiModelRegistry(modelFactory, configDataService);

		assertThat(registry.currentRerankModel()).isEmpty();
		assertThat(registry.currentRerankModel()).isEmpty();
		verify(configDataService).getActiveConfigByType(ModelType.RERANK);
		verifyNoMoreInteractions(configDataService, modelFactory);

		registry.refreshRerank();
		assertThat(registry.currentRerankModel()).isEmpty();
		verify(configDataService, org.mockito.Mockito.times(2)).getActiveConfigByType(ModelType.RERANK);
	}

}
