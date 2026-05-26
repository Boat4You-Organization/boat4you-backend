import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("org.springframework.boot") version "3.5.10"
    id("io.spring.dependency-management") version "1.1.7"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
    id("org.openapi.generator") version "7.19.0"
    kotlin("plugin.jpa") version "2.3.0"
}

group = "hr.workspace"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        sourceSets["main"].java {
            srcDir("build/generated/swagger/src")
        }
    }
}

repositories {
    mavenCentral()
}

val springDocVersion = "2.8.15"
val bouncyCastleVersion = "1.83"
val jjwtVersion = "0.12.7"
val apacheCommonsValidatorVersion = "1.10.1"
val apacheCommonsTextVersion = "1.15.0"
val libPhoneNumberVersion = "9.0.23"
val stripeSdkVersion = "31.1.0"
val eCacheVersion = "3.10.9"
val opencvVersion = "4.9.0-0"
val koTestVersion = "6.0.7"
// ShedLock — distributed lock for @Scheduled cron methods so VM2 and VM3
// (and any future replica) do not both fire the same job at once
// (F4-002 prod-blocker for 2-VM deploy). 5.16.0 is the latest stable
// line, supports Spring Boot 3.x + JdbcTemplate provider.
val shedlockVersion = "5.16.0"

dependencies {
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.hibernate.orm:hibernate-envers")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springDocVersion")
    implementation("org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion")
    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    implementation("commons-validator:commons-validator:$apacheCommonsValidatorVersion") {
        exclude("commons-logging", "commons-logging")
        exclude("commons-collections", "commons-collections")
        exclude("commons-beanutils", "commons-beanutils")
        exclude("commons-digester", "commons-digester")
    }
    implementation("com.googlecode.libphonenumber:libphonenumber:$libPhoneNumberVersion")
    implementation("com.stripe:stripe-java:$stripeSdkVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.retry:spring-retry")
    // ShedLock — see version comment above
    implementation("net.javacrumbs.shedlock:shedlock-spring:$shedlockVersion")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:$shedlockVersion")
    // cache
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.ehcache:ehcache:$eCacheVersion")
    // webp
    implementation("org.openpnp:opencv:$opencvVersion")
    // jaro-winkler distance for extras matching
    implementation("org.apache.commons:commons-text:$apacheCommonsTextVersion")

    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // openhtmltopdf — Thymeleaf-rendered HTML → PDF for charter agreement
    // attachments on reservation confirmation emails. 1.0.10 is the latest
    // stable release on Maven Central.
    implementation("com.openhtmltopdf:openhtmltopdf-core:1.0.10")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.kotest:kotest-assertions-core:$koTestVersion")
    testImplementation("io.kotest:kotest-runner-junit5:$koTestVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        javaParameters = true
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

ktlint {
    version.set("1.8.0")
    filter {
        exclude { it.file.path.toString().contains("generated") }
    }
    additionalEditorconfig.put("max_line_length", "180")
    additionalEditorconfig.put("ktlint_standard_function-expression-body", "disabled")
    additionalEditorconfig.put(
        "ktlint_chain_method_rule_force_multiline_when_chain_operator_count_greater_or_equal_than",
        "unset",
    )
    additionalEditorconfig.put("ktlint_function_naming_ignore_when_annotated_with", "Suppress")
    additionalEditorconfig.put("ktlint_standard_blank-line-between-when-conditions", "disabled")
    additionalEditorconfig.put("ktlint_standard_when-entry-bracing", "disabled")
}

detekt {
    buildUponDefaultConfig = true
    config.from(files("$rootDir/detekt_config.yml"))
}

configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.0.21")
        }
    }
}

typealias GenerateApiSpecTask = org.openapitools.generator.gradle.plugin.tasks.GenerateTask

fun createServerApiGenerationTask(inputSpecLocation: String): Action<GenerateApiSpecTask> =
    Action<GenerateApiSpecTask> {
        generatorName.set("kotlin-spring")

        generateApiDocumentation.set(false)
        generateApiTests.set(false)
        generateModelTests.set(false)
        generateModelDocumentation.set(false)

        inputSpec.set(inputSpecLocation)
        outputDir.set("$projectDir/build/generated/swagger")

        apiPackage.set("org.openapitools.api")
        modelPackage.set("org.openapitools.model")
        skipValidateSpec.set(false)

        additionalProperties.put("removeEnumValuePrefix", "false")

        configOptions.set(
            mapOf(
                "interfaceOnly" to "true",
                "library" to "spring-boot",
                "serializableModel" to "true",
                "useSpringBoot3" to "true",
                "documentationProvider" to "none",
                "exceptionHandler" to "false",
                "enumPropertyNaming" to "UPPERCASE",
            ),
        )
    }

fun createClientApiGenerationTask(
    inputSpecLocation: String,
    vendor: Provider,
): Action<GenerateApiSpecTask> =
    Action<GenerateApiSpecTask> {
        generatorName.set("kotlin")

        generateApiDocumentation.set(false)
        generateApiTests.set(false)
        generateModelTests.set(false)
        generateModelDocumentation.set(false)

        inputSpec.set(inputSpecLocation)
        outputDir.set("$projectDir/build/generated/swagger")

        apiPackage.set("org.openapitools.client.${vendor.name.lowercase()}.api")
        modelPackage.set("org.openapitools.client.${vendor.name.lowercase()}.model")
        skipValidateSpec.set(false)

        additionalProperties.put("removeEnumValuePrefix", "false")

        if (vendor == Provider.NAUSYS) {
            typeMappings.set(
                mapOf(
                    "string+date" to "NauSysDateWrapper",
                    "string+date-time" to "NauSysDateTimeWrapper",
                    "string+time" to "NauSysTimeWrapper",
                ),
            )

            importMappings.set(
                mapOf(
                    "NauSysDateWrapper" to "hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper",
                    "NauSysDateTimeWrapper" to "hr.workspace.boat4you.domains.external.nausys.model.NauSysDateTimeWrapper",
                    "NauSysTimeWrapper" to "hr.workspace.boat4you.domains.external.nausys.model.NauSysTimeWrapper",
                ),
            )
        }

        if (vendor == Provider.MMK) {
            typeMappings.set(
                mapOf(
                    "string+date-time" to "MmkDateTimeWrapper",
                ),
            )

            importMappings.set(
                mapOf(
                    "MmkDateTimeWrapper" to "hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper",
                ),
            )
        }

        configOptions.set(
            mapOf(
                "interfaceOnly" to "true",
                "library" to "jvm-spring-restclient",
                "serializableModel" to "true",
                "useSpringBoot3" to "true",
                "documentationProvider" to "none",
                "exceptionHandler" to "false",
                "enumPropertyNaming" to "UPPERCASE",
                "serializationLibrary" to "jackson",
                "additionalModelTypeAnnotations" to "@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)",
            ),
        )
    }

val swaggerFolder = "$rootDir/src/main/resources/static/"

tasks.register(
    "buildEntitiesCrudApiModels",
    GenerateApiSpecTask::class,
    createServerApiGenerationTask("$swaggerFolder/boat4you_ws_entities_crud.openapi.yaml"),
)

enum class Provider {
    NAUSYS,
    MMK,
}

tasks.register(
    "buildNauSysApiModels",
    GenerateApiSpecTask::class,
    createClientApiGenerationTask("$swaggerFolder/nausys_v6.openapi.yaml", Provider.NAUSYS),
)

tasks.register(
    "buildMmkModels",
    GenerateApiSpecTask::class,
    createClientApiGenerationTask("$swaggerFolder/mmk_api_2_1_5.yaml", Provider.MMK),
)

tasks.compileKotlin {
    dependsOn(
        tasks.getByName("buildEntitiesCrudApiModels"),
        tasks.getByName("buildNauSysApiModels"),
        tasks.getByName("buildMmkModels"),
    )
}

tasks.runKtlintFormatOverMainSourceSet {
    dependsOn(
        tasks.getByName("buildEntitiesCrudApiModels"),
        tasks.getByName("buildNauSysApiModels"),
        tasks.getByName("buildMmkModels"),
    )
}

tasks.runKtlintCheckOverMainSourceSet {
    dependsOn(
        tasks.getByName("buildEntitiesCrudApiModels"),
        tasks.getByName("buildNauSysApiModels"),
        tasks.getByName("buildMmkModels"),
    )
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    ignoreFailures = true
}
