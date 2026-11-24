package com.pnh.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Every console API handler declares the identity permission it needs (method- or class-level @PreAuthorize).
 * A handler without one would be open to any signed-in user — e.g. a new POST added next to guarded GETs.
 */
class ConsoleAuthorizationArchTest {

    /** Open to every signed-in console user by design. */
    private static final Set<String> PUBLIC_CONTROLLERS =
            Set.of("com.pnh.admin.adapter.in.web.AuthController", "com.pnh.admin.adapter.in.web.ClockController");

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.pnh");

    @Test
    void everyConsoleHandlerRequiresAPermission() {
        methods()
                .that()
                .areAnnotatedWith(GetMapping.class)
                .or()
                .areAnnotatedWith(PostMapping.class)
                .or()
                .areAnnotatedWith(PutMapping.class)
                .or()
                .areAnnotatedWith(PatchMapping.class)
                .or()
                .areAnnotatedWith(DeleteMapping.class)
                .should(new ArchCondition<>("declare @PreAuthorize when serving /console/api") {
                    @Override
                    public void check(JavaMethod m, ConditionEvents events) {
                        var owner = m.getOwner();
                        if (PUBLIC_CONTROLLERS.contains(owner.getName()) || !isConsole(m)) {
                            return;
                        }
                        boolean guarded =
                                m.isAnnotatedWith(PreAuthorize.class) || owner.isAnnotatedWith(PreAuthorize.class);
                        if (!guarded) {
                            events.add(SimpleConditionEvent.violated(m, m.getFullName() + " has no @PreAuthorize"));
                        }
                    }
                })
                .check(classes);
    }

    /** Class- or method-level *Mapping path starts with /console/api. */
    private static boolean isConsole(JavaMethod m) {
        return java.util.stream.Stream.concat(m.getOwner().getAnnotations().stream(), m.getAnnotations().stream())
                .filter(a -> a.getRawType().getSimpleName().endsWith("Mapping"))
                .flatMap(a -> a.get("value").stream())
                .flatMap(v -> v instanceof String[] arr
                        ? java.util.Arrays.stream(arr)
                        : java.util.stream.Stream.of(String.valueOf(v)))
                .anyMatch(p -> p.startsWith("/console/api"));
    }
}
