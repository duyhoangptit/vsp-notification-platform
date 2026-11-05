package com.pnh.admin.adapter.in.cli;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Runs one provisioning command under profile {@code cli} and exits with its code. */
@Component
@Profile("cli")
class ProvisioningRunner implements ApplicationRunner, ExitCodeGenerator {

    private final ProvisioningCommands commands;
    private int exitCode;

    ProvisioningRunner(ProvisioningCommands commands) {
        this.commands = commands;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> raw = args.getNonOptionArgs().isEmpty()
                ? List.of()
                : java.util.stream.Stream.concat(
                                args.getNonOptionArgs().stream().limit(1),
                                java.util.Arrays.stream(args.getSourceArgs()).filter(a -> a.startsWith("--")))
                        .toList();
        exitCode = commands.execute(raw, System.out, ProvisioningCommands.defaultActor());
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
