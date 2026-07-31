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
package cn.lgs.semevosql.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.common.SemEvoSQLSecurityProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SemEvoSQLSecurityConfigurationTest {

	private final SemEvoSQLSecurityConfiguration configuration = new SemEvoSQLSecurityConfiguration();

	@Test
	void mapsConfiguredAndRealmRolesToSemEvoSQLAuthorities() {
		SemEvoSQLSecurityProperties properties = new SemEvoSQLSecurityProperties();
		Jwt jwt = Jwt.withTokenValue("test")
			.header("alg", "none")
			.subject("operator-1")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(300))
			.claim("roles", List.of("viewer", "ROLE_SEMEVOSQL_REVIEWER", "unrelated"))
			.claim("realm_access", Map.of("roles", List.of("editor", "SEMEVOSQL_ADMIN")))
			.build();

		var authentication = configuration.jwtAuthenticationConverter(properties).convert(jwt).block();

		assertThat(authentication).isNotNull();
		assertThat(authentication.getName()).isEqualTo("operator-1");
		assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
			.contains("ROLE_SEMEVOSQL_VIEWER", "ROLE_SEMEVOSQL_EDITOR", "ROLE_SEMEVOSQL_REVIEWER",
					"ROLE_SEMEVOSQL_ADMIN")
			.doesNotContain("ROLE_SEMEVOSQL_UNRELATED");
	}

	@Test
	void supportsSpaceSeparatedRoleClaimsAndCustomPrincipalClaim() {
		SemEvoSQLSecurityProperties properties = new SemEvoSQLSecurityProperties();
		properties.setPrincipalClaim("preferred_username");
		Jwt jwt = Jwt.withTokenValue("test")
			.header("alg", "none")
			.subject("subject-value")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(300))
			.claim("preferred_username", "alice")
			.claim("roles", "publisher admin")
			.build();

		var authentication = configuration.jwtAuthenticationConverter(properties).convert(jwt).block();

		assertThat(authentication).isNotNull();
		assertThat(authentication.getName()).isEqualTo("alice");
		assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
			.contains("ROLE_SEMEVOSQL_PUBLISHER", "ROLE_SEMEVOSQL_ADMIN");
	}
}
