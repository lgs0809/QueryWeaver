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
package cn.lgs.queryweaver.clarification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.queryweaver.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeClarificationServiceTest {

	@Test
	void explicitSpecificDimensionBeatsGenericObjectTermEvenAtSameTokenLength() {
		SemanticCatalogSnapshot.Dimension province = dimension("省份", "province", "province");

		assertTrue(RuntimeClarificationService.hasUniqueSpecificDimensionMatch("按客户省份统计有效支付金额", List.of(province),
				Set.of("客户")));
	}

	@Test
	void genericTermItselfDoesNotCountAsSpecificDimension() {
		SemanticCatalogSnapshot.Dimension customer = dimension("客户", "customer", "customer_id");

		assertFalse(RuntimeClarificationService.hasUniqueSpecificDimensionMatch("按客户统计金额", List.of(customer), Set.of("客户")));
	}

	private SemanticCatalogSnapshot.Dimension dimension(String businessName, String dimensionCode, String columnName) {
		return SemanticCatalogSnapshot.Dimension.builder()
			.businessName(businessName)
			.dimensionCode(dimensionCode)
			.columnName(columnName)
			.build();
	}

}
