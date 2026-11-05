package com.pnh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Project Notification Hub — one deployable, Spring Modulith application modules under {@code com.pnh}. */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@ConfigurationPropertiesScan
@Modulithic(sharedModules = "shared")
public class PnhApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(PnhApplication.class, args);
        if (context.getEnvironment().matchesProfiles("cli")) {
            // one-shot provisioning command: close the context (Redisson/scheduler threads are non-daemon) and return
            // the command's exit code to the shell (ProvisioningRunner is the ExitCodeGenerator)
            System.exit(SpringApplication.exit(context));
        }
    }
}
