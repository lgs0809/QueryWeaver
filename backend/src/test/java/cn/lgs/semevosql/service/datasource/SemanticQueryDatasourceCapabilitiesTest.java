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
package cn.lgs.semevosql.service.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lgs.semevosql.enums.BizDataSourceTypeEnum;
import cn.lgs.semevosql.exception.InvalidInputException;
import org.junit.jupiter.api.Test;

class SemanticQueryDatasourceCapabilitiesTest {

	@Test
	void onlyEndToEndSupportedDatasourceTypesAreAdvertised() {
		assertThat(SemanticQueryDatasourceCapabilities.supportedTypes())
			.containsExactly(BizDataSourceTypeEnum.MYSQL, BizDataSourceTypeEnum.POSTGRESQL);
		assertThat(SemanticQueryDatasourceCapabilities.supports("mysql")).isTrue();
		assertThat(SemanticQueryDatasourceCapabilities.supports("postgresql")).isTrue();
	}

	@Test
	void connectOnlyDialectsAreRejectedUntilSemanticCompilerSupportsThem() {
		for (String type : new String[] { "dameng", "sqlserver", "oracle", "hive" }) {
			assertThatThrownBy(() -> SemanticQueryDatasourceCapabilities.requireSupported(type))
				.isInstanceOf(InvalidInputException.class)
				.hasMessage("当前版本仅支持 MySQL 和 PostgreSQL 数据源的端到端问数能力");
		}
	}

}
