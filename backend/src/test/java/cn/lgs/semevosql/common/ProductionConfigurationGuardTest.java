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
package cn.lgs.semevosql.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lgs.semevosql.enums.CodePoolExecutorEnum;
import cn.lgs.semevosql.properties.CodeExecutorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationGuardTest {

	@Test
	void fullyConfiguredApplicationRuntimePassesGuard() {
		MockEnvironment environment = baseEnvironment();
		CodeExecutorProperties executor = remoteExecutor();
		ProductionConfigurationGuard guard = new ProductionConfigurationGuard(new OperatorContextProperties(), executor,
				environment);

		assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
	}

	@Test
	void missingIssuerIsReportedByProductionGuard() {
		MockEnvironment environment = baseEnvironment(false);
		ProductionConfigurationGuard guard = new ProductionConfigurationGuard(new OperatorContextProperties(),
				remoteExecutor(), environment);

		assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("spring.security.oauth2.resourceserver.jwt.issuer-uri is required");
	}

	@Test
	void executionWorkerRejectsUnpinnedImage() {
		MockEnvironment environment = baseEnvironment();
		environment.setProperty("semevosql.runtime-role", "execution-worker");
		CodeExecutorProperties executor = new CodeExecutorProperties();
		executor.setCodePoolExecutor(CodePoolExecutorEnum.DOCKER);
		executor.setInternalToken("t".repeat(32));
		executor.setImageName("example/python-runner:dev");
		ProductionConfigurationGuard guard = new ProductionConfigurationGuard(new OperatorContextProperties(), executor,
				environment);

		assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("release-pinned semevosql/python-runner:1.0.0");
	}

	private CodeExecutorProperties remoteExecutor() {
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setCodePoolExecutor(CodePoolExecutorEnum.REMOTE);
		properties.setRemoteUrl("http://execution-worker:8066");
		properties.setInternalToken("t".repeat(32));
		return properties;
	}

	private MockEnvironment baseEnvironment() {
		return baseEnvironment(true);
	}

	private MockEnvironment baseEnvironment(boolean includeIssuer) {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("semevosql.runtime-role", "application");
		environment.setProperty("semevosql.security.enabled", "true");
		environment.setProperty("semevosql.mcp.enabled", "false");
		environment.setProperty("spring.ai.mcp.server.enabled", "false");
		if (includeIssuer) {
			environment.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.test");
		}
		environment.setProperty("semevosql.secrets.encryption-key", "k".repeat(32));
		environment.setProperty("spring.flyway.enabled", "true");
		environment.setProperty("spring.sql.init.mode", "never");
		environment.setProperty("spring.datasource.url",
				"jdbc:postgresql://metadata-db:5432/semevosql?sslmode=require");
		environment.setProperty("spring.datasource.username", "semevosql");
		environment.setProperty("spring.datasource.password", "p".repeat(24));
		return environment;
	}
}
