/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.assistant.agent.extension.trigger.backend;

import com.alibaba.assistant.agent.extension.trigger.model.ExecutionStatus;
import com.alibaba.assistant.agent.extension.trigger.model.ScheduleMode;
import com.alibaba.assistant.agent.extension.trigger.model.TriggerDefinition;
import com.alibaba.assistant.agent.extension.trigger.model.TriggerExecutionRecord;
import com.alibaba.assistant.agent.extension.trigger.repository.TriggerExecutionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 基于Spring TaskScheduler的执行后端实现
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class SpringSchedulerExecutionBackend implements ExecutionBackend {

	private static final Logger log = LoggerFactory.getLogger(SpringSchedulerExecutionBackend.class);

	private final TaskScheduler taskScheduler;

	private final TriggerExecutionLogRepository executionLogRepository;

	private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

	private final Map<String, TriggerDefinition> taskDefinitions = new ConcurrentHashMap<>();

	public SpringSchedulerExecutionBackend(TaskScheduler taskScheduler,
			TriggerExecutionLogRepository executionLogRepository) {
		this.taskScheduler = taskScheduler;
		this.executionLogRepository = executionLogRepository;
	}

	@Override
	public String schedule(TriggerDefinition definition) {
		log.info("SpringSchedulerExecutionBackend.schedule 注册调度任务: triggerId={}, scheduleMode={}",
				definition.getTriggerId(), definition.getScheduleMode());

		String backendTaskId = generateTaskId(definition);
		taskDefinitions.put(backendTaskId, definition);

		Runnable task = () -> executeTask(backendTaskId, definition);

		ScheduledFuture<?> scheduledFuture = scheduleByMode(definition, task);
		scheduledTasks.put(backendTaskId, scheduledFuture);

		log.info("SpringSchedulerExecutionBackend.schedule 调度任务注册成功: backendTaskId={}", backendTaskId);
		return backendTaskId;
	}

	@Override
	public void cancel(String backendTaskId) {
		log.info("SpringSchedulerExecutionBackend.cancel 取消调度任务: backendTaskId={}", backendTaskId);

		ScheduledFuture<?> future = scheduledTasks.remove(backendTaskId);
		if (future != null) {
			future.cancel(false);
			log.info("SpringSchedulerExecutionBackend.cancel 任务已取消: backendTaskId={}", backendTaskId);
		}
		else {
			log.warn("SpringSchedulerExecutionBackend.cancel 任务不存在: backendTaskId={}", backendTaskId);
		}

		taskDefinitions.remove(backendTaskId);
	}

	@Override
	public boolean isRunning(String backendTaskId) {
		ScheduledFuture<?> future = scheduledTasks.get(backendTaskId);
		return future != null && !future.isDone() && !future.isCancelled();
	}

	private ScheduledFuture<?> scheduleByMode(TriggerDefinition definition, Runnable task) {
		ScheduleMode mode = definition.getScheduleMode();
		String scheduleValue = definition.getScheduleValue();

		switch (mode) {
			case CRON:
				log.debug("SpringSchedulerExecutionBackend.scheduleByMode CRON调度: expression={}", scheduleValue);
				return taskScheduler.schedule(task, new CronTrigger(scheduleValue));

			case FIXED_DELAY:
				long fixedDelay = parseDuration(scheduleValue);
				log.debug("SpringSchedulerExecutionBackend.scheduleByMode FIXED_DELAY调度: delay={}ms", fixedDelay);
				return taskScheduler.scheduleWithFixedDelay(task, Duration.ofMillis(fixedDelay));

			case FIXED_RATE:
				long fixedRate = parseDuration(scheduleValue);
				log.debug("SpringSchedulerExecutionBackend.scheduleByMode FIXED_RATE调度: rate={}ms", fixedRate);
				return taskScheduler.scheduleAtFixedRate(task, Duration.ofMillis(fixedRate));

			case ONE_TIME:
				Instant executeTime = parseInstant(scheduleValue);
				log.debug("SpringSchedulerExecutionBackend.scheduleByMode ONE_TIME调度: time={}", executeTime);
				return taskScheduler.schedule(task, executeTime);

			default:
				throw new IllegalArgumentException("不支持的调度模式: " + mode);
		}
	}

	private void executeTask(String backendTaskId, TriggerDefinition definition) {
		String executionId = UUID.randomUUID().toString();
		log.info("SpringSchedulerExecutionBackend.executeTask 开始执行任务: executionId={}, triggerId={}",
				executionId, definition.getTriggerId());

		// 创建执行记录
		TriggerExecutionRecord record = new TriggerExecutionRecord(executionId, definition.getTriggerId());
		record.setBackendTaskId(backendTaskId);
		record.setStartTime(Instant.now());
		record.setStatus(ExecutionStatus.RUNNING);
		record.setThreadId(Thread.currentThread().getName());
		executionLogRepository.save(record);

		try {
			// TODO: 实际执行业务逻辑
			// 这里需要集成CodeAct的执行能力
			log.info("SpringSchedulerExecutionBackend.executeTask 执行函数: function={}, params={}",
					definition.getExecuteFunction(), definition.getParameters());

			// 模拟执行成功
			executionLogRepository.updateStatus(executionId, ExecutionStatus.SUCCESS, null, null);
			log.info("SpringSchedulerExecutionBackend.executeTask 任务执行成功: executionId={}", executionId);

		}
		catch (Exception e) {
			log.error("SpringSchedulerExecutionBackend.executeTask 任务执行失败: executionId={}", executionId, e);
			executionLogRepository.updateStatus(executionId, ExecutionStatus.FAILED, e.getMessage(), null);
		}
	}

	private String generateTaskId(TriggerDefinition definition) {
		return "task_" + definition.getTriggerId() + "_" + System.currentTimeMillis();
	}

	private long parseDuration(String value) {
		// 支持ISO-8601格式（如PT3H）或直接毫秒数
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException e) {
			return Duration.parse(value).toMillis();
		}
	}

	private Instant parseInstant(String value) {
		// 支持ISO-8601格式或时间戳
		try {
			return Instant.ofEpochMilli(Long.parseLong(value));
		}
		catch (NumberFormatException e) {
			return Instant.parse(value);
		}
	}

}

