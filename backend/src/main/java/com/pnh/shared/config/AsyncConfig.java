package com.pnh.shared.config;

import com.pnh.shared.logging.SensitiveValues;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executors for asynchronous module listeners (R6): a dedicated {@code otpExecutor} so OTP never waits behind
 * STANDARD backlog, a {@code standardExecutor}, and the default {@code taskExecutor}. All propagate MDC, the
 * sensitive-value holder and the Micrometer observation/trace context (constitution V, T026).
 */
@Configuration(proxyBeanMethods = false)
public class AsyncConfig {

    public static final String OTP_EXECUTOR = "otpExecutor";
    public static final String STANDARD_EXECUTOR = "standardExecutor";

    @Bean
    TaskDecorator contextPropagatingDecorator() {
        ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();
        return runnable -> {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            Set<String> sensitive = SensitiveValues.snapshot();
            ContextSnapshot snapshot = snapshots.captureAll();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    }
                    SensitiveValues.restore(sensitive);
                    runnable.run();
                } finally {
                    SensitiveValues.clear();
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }

    @Bean(name = OTP_EXECUTOR)
    ThreadPoolTaskExecutor otpExecutor(TaskDecorator decorator, @Value("${pnh.async.otp-threads:16}") int threads) {
        return executor("pnh-otp-", threads, 1_000, decorator);
    }

    @Bean(name = STANDARD_EXECUTOR)
    ThreadPoolTaskExecutor standardExecutor(
            TaskDecorator decorator, @Value("${pnh.async.standard-threads:32}") int threads) {
        return executor("pnh-std-", threads, 100_000, decorator);
    }

    @Bean(name = {"taskExecutor", "applicationTaskExecutor"})
    @Primary
    ThreadPoolTaskExecutor taskExecutor(TaskDecorator decorator) {
        return executor("pnh-async-", 16, 100_000, decorator);
    }

    private static ThreadPoolTaskExecutor executor(String prefix, int threads, int queue, TaskDecorator decorator) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix(prefix);
        ex.setCorePoolSize(threads);
        ex.setMaxPoolSize(threads);
        ex.setQueueCapacity(queue);
        ex.setTaskDecorator(decorator);
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }
}
