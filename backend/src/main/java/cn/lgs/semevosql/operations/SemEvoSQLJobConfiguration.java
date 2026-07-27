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
package cn.lgs.semevosql.operations;

import cn.lgs.semevosql.common.OperatorContextProperties;
import cn.lgs.semevosql.common.SecretEncryptionProperties;
import cn.lgs.semevosql.concurrency.SemEvoSQLConcurrencyProperties;
import cn.lgs.semevosql.learning.QueryCaseGovernanceProperties;
import cn.lgs.semevosql.retention.SemEvoSQLRetentionProperties;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties({ SemEvoSQLConcurrencyProperties.class, QueryCaseGovernanceProperties.class,
		SemEvoSQLRetentionProperties.class, OperatorContextProperties.class, SecretEncryptionProperties.class })
public class SemEvoSQLJobConfiguration {

	@Bean("semEvoSQLInteractiveExecutor")
	public Executor interactiveExecutor(SemEvoSQLConcurrencyProperties properties) {
		return executor("semevosql-interactive-", properties.getInteractiveQuery());
	}

	@Bean("semEvoSQLInitializationExecutor")
	public Executor initializationExecutor(SemEvoSQLConcurrencyProperties properties) {
		return executor("semevosql-initialization-", properties.getInitialization());
	}

	@Bean({ "semEvoSQLEvaluationExecutor", "semEvoSQLJobExecutor" })
	public Executor evaluationExecutor(SemEvoSQLConcurrencyProperties properties) {
		return executor("semevosql-evaluation-", properties.getEvaluation());
	}

	@Bean("semEvoSQLSqlExecutor")
	public Executor sqlExecutor(SemEvoSQLConcurrencyProperties properties) {
		return executor("semevosql-sql-", properties.getSqlExecution());
	}

	private Executor executor(String prefix, SemEvoSQLConcurrencyProperties.Pool pool) {
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
