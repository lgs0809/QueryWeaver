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
package cn.lgs.queryweaver.operations;

import cn.lgs.queryweaver.common.OperatorContextProperties;
import cn.lgs.queryweaver.common.SecretEncryptionProperties;
import cn.lgs.queryweaver.concurrency.QueryWeaverConcurrencyProperties;
import cn.lgs.queryweaver.learning.QueryCaseGovernanceProperties;
import cn.lgs.queryweaver.retention.QueryWeaverRetentionProperties;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties({ QueryWeaverConcurrencyProperties.class, QueryCaseGovernanceProperties.class,
		QueryWeaverRetentionProperties.class, OperatorContextProperties.class, SecretEncryptionProperties.class })
public class QueryWeaverJobConfiguration {

	@Bean("queryWeaverInteractiveExecutor")
	public Executor interactiveExecutor(QueryWeaverConcurrencyProperties properties) {
		return executor("queryweaver-interactive-", properties.getInteractiveQuery());
	}

	@Bean("queryWeaverInitializationExecutor")
	public Executor initializationExecutor(QueryWeaverConcurrencyProperties properties) {
		return executor("queryweaver-initialization-", properties.getInitialization());
	}

	@Bean({ "queryWeaverEvaluationExecutor", "queryWeaverJobExecutor" })
	public Executor evaluationExecutor(QueryWeaverConcurrencyProperties properties) {
		return executor("queryweaver-evaluation-", properties.getEvaluation());
	}

	@Bean("queryWeaverSqlExecutor")
	public Executor sqlExecutor(QueryWeaverConcurrencyProperties properties) {
		return executor("queryweaver-sql-", properties.getSqlExecution());
	}

	private Executor executor(String prefix, QueryWeaverConcurrencyProperties.Pool pool) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(pool.getMaxConcurrent());
		executor.setMaxPoolSize(pool.getMaxConcurrent());
		executor.setQueueCapacity(pool.getQueueCapacity());
		executor.setThreadNamePrefix(prefix);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		executor.initialize();
		return executor;
	}

}
