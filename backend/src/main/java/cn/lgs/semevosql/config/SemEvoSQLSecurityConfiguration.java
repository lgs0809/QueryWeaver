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

import cn.lgs.semevosql.common.OperatorRole;
import cn.lgs.semevosql.common.SemEvoSQLSecurityProperties;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

/**
 * JWT authentication boundary. Production enables it and fails closed without a trusted
 * issuer.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@EnableConfigurationProperties(SemEvoSQLSecurityProperties.class)
public class SemEvoSQLSecurityConfiguration {

	private static final String ADMIN = "ROLE_SEMEVOSQL_ADMIN";

	private static final String[] WRITE_AUTHORITIES = { "ROLE_SEMEVOSQL_EDITOR", "ROLE_SEMEVOSQL_REVIEWER",
			"ROLE_SEMEVOSQL_PUBLISHER", ADMIN };

	private static final String[] RUNTIME_AUTHORITIES = { "ROLE_SEMEVOSQL_VIEWER", "ROLE_SEMEVOSQL_EDITOR",
			"ROLE_SEMEVOSQL_REVIEWER", "ROLE_SEMEVOSQL_PUBLISHER", ADMIN };

	@Bean
	@Order(0)
	SecurityWebFilterChain projectMcpSecurityWebFilterChain(ServerHttpSecurity http) {
		return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/mcp", "/mcp/**"))
			.csrf(ServerHttpSecurity.CsrfSpec::disable)
			.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
			.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
			.logout(ServerHttpSecurity.LogoutSpec::disable)
			.authorizeExchange(exchange -> exchange.anyExchange().permitAll())
			.build();
	}

	@Bean
	@Order(1)
	SecurityWebFilterChain semEvoSQLSecurityWebFilterChain(ServerHttpSecurity http,
			SemEvoSQLSecurityProperties properties) {
		http.csrf(ServerHttpSecurity.CsrfSpec::disable)
			.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
			.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
			.logout(ServerHttpSecurity.LogoutSpec::disable);
		if (!properties.isEnabled()) {
			return http.authorizeExchange(exchange -> exchange.anyExchange().permitAll()).build();
		}
		return http
			.authorizeExchange(exchange -> exchange.pathMatchers("/actuator/health/**")
				.permitAll()
				.pathMatchers("/actuator/info", "/actuator/prometheus", "/actuator/metrics/**", "/v3/api-docs/**",
						"/swagger-ui/**", "/swagger-ui.html")
				.hasAuthority(ADMIN)
				.pathMatchers("/api/model-config/**")
				.hasAuthority(ADMIN)
				.pathMatchers(HttpMethod.GET, "/api/datasource/**")
				.hasAnyAuthority(WRITE_AUTHORITIES)
				.pathMatchers("/api/stream/search", "/api/agent/*/sessions/stream")
				.hasAuthority(ADMIN)
				.pathMatchers(HttpMethod.POST, "/api/semevosql/projects/*/conversations",
						"/api/semevosql/projects/*/conversations/*/messages",
						"/api/semevosql/projects/*/conversations/*/runs/*/human-review",
						"/api/semevosql/projects/*/conversations/*/runs/*/sync",
						"/api/semevosql/projects/*/conversations/*/runs/*/corrections/binding",
						"/api/semevosql/runs/*/cancel", "/api/semevosql/runs/*/resume",
						"/api/semevosql/runs/*/clarification/*/answer",
						"/api/semevosql/operations/episodes/*/feedback")
				.hasAnyAuthority(RUNTIME_AUTHORITIES)
				.pathMatchers(HttpMethod.POST, "/api/**")
				.hasAnyAuthority(WRITE_AUTHORITIES)
				.pathMatchers(HttpMethod.PUT, "/api/**")
				.hasAnyAuthority(WRITE_AUTHORITIES)
				.pathMatchers(HttpMethod.PATCH, "/api/**")
				.hasAnyAuthority(WRITE_AUTHORITIES)
				.pathMatchers(HttpMethod.DELETE, "/api/**")
				.hasAnyAuthority(WRITE_AUTHORITIES)
				.anyExchange()
				.authenticated())
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(properties))))
			.build();
	}

	Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter(
			SemEvoSQLSecurityProperties properties) {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setPrincipalClaimName(properties.getPrincipalClaim());
		converter.setJwtGrantedAuthoritiesConverter(jwt -> authorities(jwt, properties));
		return new ReactiveJwtAuthenticationConverterAdapter(converter);
	}

	private Collection<GrantedAuthority> authorities(Jwt jwt, SemEvoSQLSecurityProperties properties) {
		Set<GrantedAuthority> authorities = new LinkedHashSet<>();
		JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
		authorities.addAll(scopes.convert(jwt));
		addRoles(authorities, jwt.getClaim(properties.getRoleClaim()), properties.getAuthorityPrefix());
		Object realmAccess = jwt.getClaim("realm_access");
		if (realmAccess instanceof Map<?, ?> values) {
			addRoles(authorities, values.get("roles"), properties.getAuthorityPrefix());
		}
		return List.copyOf(authorities);
	}

	private void addRoles(Set<GrantedAuthority> authorities, Object claim, String prefix) {
		if (claim instanceof Collection<?> values) {
			values.forEach(value -> addRole(authorities, value, prefix));
		}
		else if (claim != null) {
			for (String value : String.valueOf(claim).split("[ ,]")) {
				if (!value.isBlank()) {
					addRole(authorities, value, prefix);
				}
			}
		}
	}

	private void addRole(Set<GrantedAuthority> authorities, Object value, String prefix) {
		String normalized = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
		if (normalized.startsWith("ROLE_")) {
			normalized = normalized.substring("ROLE_".length());
		}
		if (normalized.startsWith("SEMEVOSQL_")) {
			normalized = normalized.substring("SEMEVOSQL_".length());
		}
		try {
			OperatorRole.valueOf(normalized);
			authorities.add(new SimpleGrantedAuthority(prefix + normalized));
		}
		catch (IllegalArgumentException ignored) {
			// Ignore unrelated identity-provider roles.
		}
	}

}
