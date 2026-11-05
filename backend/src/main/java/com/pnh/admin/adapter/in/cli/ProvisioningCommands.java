package com.pnh.admin.adapter.in.cli;

import com.pnh.admin.application.OperatorProvisioningService;
import com.pnh.coreapi.ClientProvisioningApi;
import com.pnh.shared.web.PnhException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Provisioning commands (T187; docs/operations.md). Run with
 * {@code java -jar pnh.jar --spring.profiles.active=cli <command> [--option=value ...]}. Generated secrets are printed
 * exactly once; the audit actor is {@code cli:<os-user>}. Returns a process exit code (0 = ok).
 */
@Component
public class ProvisioningCommands {

    static final String USAGE = """
            Usage: <command> [--option=value ...]
              create-client --client-id=ID [--name=NAME] [--rate-limit=1000] [--scopes=SEND,TRACK] [--allow-direct-content=true]
              update-client --client-id=ID [--rate-limit=N] [--scopes=CSV] [--allow-direct-content=true|false]
              rotate-client-secret --client-id=ID
              rotate-kafka-key --client-id=ID
              deactivate-client --client-id=ID
              create-operator --username=U --role=ADMIN|TEMPLATE_EDITOR|SUPPORT_VIEWER [--display-name=NAME] [--password=PW]
              reset-operator-password --username=U
              deactivate-operator --username=U""";

    private final ClientProvisioningApi clients;
    private final OperatorProvisioningService operators;

    public ProvisioningCommands(ClientProvisioningApi clients, OperatorProvisioningService operators) {
        this.clients = clients;
        this.operators = operators;
    }

    public static String defaultActor() {
        return "cli:" + System.getProperty("user.name", "unknown");
    }

    public int execute(List<String> args, PrintStream out, String actor) {
        if (args.isEmpty()) {
            out.println(USAGE);
            return 2;
        }
        String command = args.get(0);
        Map<String, String> o = options(args.subList(1, args.size()));
        try {
            switch (command) {
                case "create-client" -> {
                    var issued = clients.create(
                            actor,
                            o.get("client-id"),
                            o.get("name"),
                            Integer.parseInt(o.getOrDefault("rate-limit", "1000")),
                            scopes(o.getOrDefault("scopes", "SEND,TRACK")),
                            Boolean.parseBoolean(o.getOrDefault("allow-direct-content", "true")));
                    out.println("client_id=" + issued.clientId());
                    out.println("client_secret=" + issued.clientSecret());
                    out.println("kafka_signing_key=" + issued.kafkaSigningKey());
                    out.println("Store these values now — they are not shown again.");
                }
                case "update-client" -> {
                    clients.update(
                            actor,
                            required(o, "client-id"),
                            o.containsKey("rate-limit") ? Integer.valueOf(o.get("rate-limit")) : null,
                            o.containsKey("scopes") ? scopes(o.get("scopes")) : null,
                            o.containsKey("allow-direct-content")
                                    ? Boolean.valueOf(o.get("allow-direct-content"))
                                    : null);
                    out.println("updated " + o.get("client-id"));
                }
                case "rotate-client-secret" ->
                    out.println("client_secret=" + clients.rotateSecret(actor, required(o, "client-id")));
                case "rotate-kafka-key" ->
                    out.println("kafka_signing_key=" + clients.rotateKafkaKey(actor, required(o, "client-id")));
                case "deactivate-client" -> {
                    clients.deactivate(actor, required(o, "client-id"));
                    out.println("deactivated " + o.get("client-id"));
                }
                case "create-operator" -> {
                    String pw = operators.create(
                            actor,
                            required(o, "username"),
                            o.get("display-name"),
                            OperatorProvisioningService.parseRole(o.get("role")),
                            o.get("password"));
                    out.println("username=" + o.get("username"));
                    if (!o.containsKey("password")) {
                        out.println("password=" + pw);
                        out.println("Store the password now — it is not shown again.");
                    }
                }
                case "reset-operator-password" ->
                    out.println("password=" + operators.resetPassword(actor, required(o, "username")));
                case "deactivate-operator" -> {
                    operators.deactivate(actor, required(o, "username"));
                    out.println("deactivated " + o.get("username"));
                }
                default -> {
                    out.println("Unknown command: " + command);
                    out.println(USAGE);
                    return 2;
                }
            }
            return 0;
        } catch (PnhException e) {
            out.println("error: " + e.code() + ": " + e.getMessage());
            return 1;
        } catch (NumberFormatException e) {
            out.println("error: invalid number: " + e.getMessage());
            return 1;
        }
    }

    private static java.util.Set<String> scopes(String csv) {
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static String required(Map<String, String> o, String key) {
        String v = o.get(key);
        if (v == null || v.isBlank()) {
            throw PnhException.field(com.pnh.shared.domain.ErrorCode.MISSING_FIELD, key, "--" + key + " is required");
        }
        return v;
    }

    private static Map<String, String> options(List<String> raw) {
        Map<String, String> o = new HashMap<>();
        for (String a : raw) {
            if (a.startsWith("--") && !a.startsWith("--spring.")) {
                int eq = a.indexOf('=');
                o.put(eq < 0 ? a.substring(2) : a.substring(2, eq), eq < 0 ? "true" : a.substring(eq + 1));
            }
        }
        return o;
    }
}
