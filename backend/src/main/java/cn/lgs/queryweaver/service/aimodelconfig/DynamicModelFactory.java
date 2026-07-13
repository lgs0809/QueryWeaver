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

import cn.lgs.queryweaver.dto.ModelConfigDTO;
import cn.lgs.queryweaver.properties.ModelClientProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicModelFactory {

	private final ModelClientProperties modelClientProperties;

	/**
	 * 统一使用 OpenAiChatModel，通过 baseUrl 实现多厂商兼容
	 */
	public ChatModel createChatModel(ModelConfigDTO config) {

		log.info("Creating NEW ChatModel instance. Provider: {}, Model: {}, BaseUrl: {}", config.getProvider(),
				config.getModelName(), config.getBaseUrl());
		// 1. 验证参数
		checkBasic(config);

		// 2. 构建 OpenAiApi (核心通讯对象)
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
			.apiKey(apiKey)
			.baseUrl(config.getBaseUrl())
			.restClientBuilder(getProxiedRestClientBuilder(config))
			.webClientBuilder(getProxiedWebClientBuilder(config));

		if (StringUtils.hasText(config.getCompletionsPath())) {
			apiBuilder.completionsPath(config.getCompletionsPath());
		}
		OpenAiApi openAiApi = apiBuilder.build();

		// 3. 构建运行时选项 (设置默认的模型名称，如 "deepseek-chat" 或 "gpt-4")
		OpenAiChatOptions openAiChatOptions = OpenAiChatOptions.builder()
			.model(config.getModelName())
			.temperature(config.getTemperature())
			.maxTokens(config.getMaxTokens())
			.streamUsage(true)
			.build();
		// 4. 返回统一的 OpenAiChatModel
		return OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(openAiChatOptions).build();
	}

	/**
	 * Embedding 同理
	 */
	public EmbeddingModel createEmbeddingModel(ModelConfigDTO config) {
		log.info("Creating NEW EmbeddingModel instance. Provider: {}, Model: {}, BaseUrl: {}", config.getProvider(),
				config.getModelName(), config.getBaseUrl());
		checkBasic(config);

		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		return new OpenAiCompatibleEmbeddingModel(getEmbeddingRestClientBuilder(config), config.getBaseUrl(), apiKey,
				config.getEmbeddingsPath(), config.getModelName());
	}

	private static void checkBasic(ModelConfigDTO config) {
		Assert.hasText(config.getBaseUrl(), "baseUrl must not be empty");
		if (!"custom".equalsIgnoreCase(config.getProvider())) {
			Assert.hasText(config.getApiKey(), "apiKey must not be empty");
		}
		Assert.hasText(config.getModelName(), "modelName must not be empty");
	}

	private RestClient.Builder getProxiedRestClientBuilder(ModelConfigDTO config) {
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			return RestClient.builder();
		}

		// 打印同步代理日志
		log.info("【Proxy-Init】Model [{}] is using SYNC proxy -> {}:{}", config.getModelName(), config.getProxyHost(),
				config.getProxyPort());

		BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
		if (StringUtils.hasText(config.getProxyUsername())) {
			log.info("【Proxy-Auth】Enabling Basic Auth for SYNC proxy, user: {}", config.getProxyUsername());
			credsProvider.setCredentials(new AuthScope(config.getProxyHost(), config.getProxyPort()),
					new UsernamePasswordCredentials(config.getProxyUsername(),
							config.getProxyPassword().toCharArray()));
		}

		CloseableHttpClient httpClient = HttpClients.custom()
			.setProxy(new HttpHost(config.getProxyHost(), config.getProxyPort()))
			.setDefaultCredentialsProvider(credsProvider)
			.build();

		return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
	}

	private RestClient.Builder getEmbeddingRestClientBuilder(ModelConfigDTO config) {
		Duration timeout = modelClientProperties.getEmbeddingRequestTimeout();
		long timeoutMillis = timeout == null ? 5000L : Math.max(1L, timeout.toMillis());
		if (timeoutMillis > Integer.MAX_VALUE) {
			timeoutMillis = Integer.MAX_VALUE;
		}
		int timeoutValue = (int) timeoutMillis;

		CloseableHttpClient httpClient;
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			httpClient = HttpClients.createDefault();
		}
		else {
			log.info("【Proxy-Init】Embedding model [{}] is using SYNC proxy -> {}:{}", config.getModelName(),
					config.getProxyHost(), config.getProxyPort());
			BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
			if (StringUtils.hasText(config.getProxyUsername())) {
				credsProvider.setCredentials(new AuthScope(config.getProxyHost(), config.getProxyPort()),
						new UsernamePasswordCredentials(config.getProxyUsername(),
								config.getProxyPassword().toCharArray()));
			}
			httpClient = HttpClients.custom()
					.setProxy(new HttpHost(config.getProxyHost(), config.getProxyPort()))
					.setDefaultCredentialsProvider(credsProvider)
					.build();
		}

		HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
		requestFactory.setConnectTimeout(timeoutValue);
		requestFactory.setConnectionRequestTimeout(timeoutValue);
		requestFactory.setReadTimeout(timeoutValue);
		return RestClient.builder().requestFactory(requestFactory);
	}

	private WebClient.Builder getProxiedWebClientBuilder(ModelConfigDTO config) {
		WebClient.Builder builder;
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			builder = WebClient.builder();
		}
		else {
			log.info("【Proxy-Init】Model [{}] is using ASYNC (Netty) proxy -> {}:{}", config.getModelName(),
					config.getProxyHost(), config.getProxyPort());

			HttpClient nettyClient = HttpClient.create().responseTimeout(java.time.Duration.ofMinutes(3)).proxy(p -> {
				ProxyProvider.Builder proxyBuilder = p.type(ProxyProvider.Proxy.HTTP)
					.host(config.getProxyHost())
					.port(config.getProxyPort());

				if (StringUtils.hasText(config.getProxyUsername())) {
					log.info("【Proxy-Auth】Enabling Basic Auth for ASYNC proxy, user: {}", config.getProxyUsername());
					proxyBuilder.username(config.getProxyUsername()).password(s -> config.getProxyPassword());
				}
			});
			builder = WebClient.builder().clientConnector(new ReactorClientHttpConnector(nettyClient));
		}
		return configureConnectionRetry(builder, config.getModelName());
	}

	WebClient.Builder configureConnectionRetry(WebClient.Builder builder, String modelName) {
		if (modelClientProperties.getConnectionMaxRetries() <= 0) {
			return builder;
		}
		Retry retry = Retry
			.backoff(modelClientProperties.getConnectionMaxRetries(),
					modelClientProperties.getConnectionInitialBackoff())
			.maxBackoff(modelClientProperties.getConnectionMaxBackoff())
			.jitter(modelClientProperties.getConnectionRetryJitter())
			.filter(WebClientRequestException.class::isInstance)
			.doBeforeRetry(
					signal -> log.warn("Retrying model {} connection before response headers after {}: attempt {}/{}",
							modelName, signal.failure().getClass().getSimpleName(), signal.totalRetries() + 1,
							modelClientProperties.getConnectionMaxRetries()))
			.onRetryExhaustedThrow((spec, signal) -> signal.failure());
		return builder.filter(
				(request, next) -> reactor.core.publisher.Mono.defer(() -> next.exchange(request)).retryWhen(retry));
	}

}
