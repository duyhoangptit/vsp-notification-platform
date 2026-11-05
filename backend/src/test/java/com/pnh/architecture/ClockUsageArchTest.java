package com.pnh.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Constitution VI / research R29: the injected {@code Clock} bean is the only time source. */
class ClockUsageArchTest {

    private static final String CLOCK_CONFIG = "com.pnh.shared.time.ClockConfig";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.pnh");

    @Test
    void noDirectNowCalls() {
        ArchRule rule = noClasses()
                .that()
                .doNotHaveFullyQualifiedName(CLOCK_CONFIG)
                .should()
                .callMethod(LocalDate.class, "now")
                .orShould()
                .callMethod(LocalDateTime.class, "now")
                .orShould()
                .callMethod(LocalTime.class, "now")
                .orShould()
                .callMethod(Instant.class, "now")
                .orShould()
                .callMethod(ZonedDateTime.class, "now")
                .orShould()
                .callMethod(OffsetDateTime.class, "now")
                .orShould()
                .callMethod(Year.class, "now")
                .orShould()
                .callMethod(YearMonth.class, "now")
                .orShould()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callConstructor(java.util.Date.class)
                .orShould()
                .callMethod(Clock.class, "systemUTC")
                .orShould()
                .callMethod(Clock.class, "systemDefaultZone")
                .because("all current time must come from the injected Clock bean (constitution VI)");
        rule.check(classes);
    }

    @Test
    void noHibernateTimestampAnnotations() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.hibernate.annotations.CreationTimestamp")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.hibernate.annotations.UpdateTimestamp")
                .check(classes);
    }

    @Test
    void noSqlNowInAdapterStrings() throws IOException {
        Pattern stringLiteral = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|\"\"\"[\\s\\S]*?\"\"\"");
        Pattern sqlNow = Pattern.compile("(?i)\\bnow\\s*\\(\\s*\\)|\\bCURRENT_TIMESTAMP\\b");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/pnh"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file);
                Matcher m = stringLiteral.matcher(src);
                while (m.find()) {
                    if (sqlNow.matcher(m.group()).find()) {
                        violations.add(file + ": " + m.group());
                    }
                }
            }
        }
        assertThat(violations)
                .as("SQL must not use now()/CURRENT_TIMESTAMP; bind the Clock instant")
                .isEmpty();
    }
}
