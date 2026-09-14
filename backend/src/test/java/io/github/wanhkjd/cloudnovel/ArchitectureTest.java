package io.github.wanhkjd.cloudnovel;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 守住三层依赖和固定包布局，避免旧包、循环依赖及跨层访问重新出现。 */
@AnalyzeClasses(
        packages = "io.github.wanhkjd.cloudnovel",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    private static final String ROOT = "io.github.wanhkjd.cloudnovel";

    @ArchTest
    static final ArchRule PACKAGES_FOLLOW_THE_DOCUMENTED_LAYOUT =
            classes()
                    .should()
                    .resideInAnyPackage(
                            ROOT,
                            ROOT + ".controller",
                            ROOT + ".core.auth",
                            ROOT + ".core.config",
                            ROOT + ".core.exception",
                            ROOT + ".core.parser",
                            ROOT + ".core.storage",
                            ROOT + ".dao.entity",
                            ROOT + ".dao.mapper",
                            ROOT + ".dto.req",
                            ROOT + ".dto.resp",
                            ROOT + ".service",
                            ROOT + ".service.impl");

    @ArchTest
    static final ArchRule ONLY_APPLICATION_ENTRY_POINT_LIVES_IN_ROOT =
            classes()
                    .that()
                    .resideInAPackage(ROOT)
                    .should()
                    .haveSimpleName("CloudNovelApplication");

    @ArchTest
    static final ArchRule TOP_LEVEL_PACKAGES_HAVE_NO_CYCLES =
            slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule CONTROLLERS_USE_SERVICE_CONTRACTS =
            noClasses()
                    .that()
                    .resideInAPackage("..controller..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..dao..",
                            "..service.impl..",
                            "..core.config..",
                            "..core.storage..",
                            "..core.parser..",
                            "org.springframework.jdbc..",
                            "java.sql..");

    @ArchTest
    static final ArchRule SERVICES_DO_NOT_USE_WEB_OR_SQL =
            noClasses()
                    .that()
                    .resideInAPackage("..service..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..controller..",
                            "..core.auth..",
                            "..core.config..",
                            "org.springframework.web..",
                            "org.springframework.http..",
                            "org.springframework.security..",
                            "org.springframework.jdbc..",
                            "java.sql..",
                            "jakarta.servlet..");

    @ArchTest
    static final ArchRule MAPPERS_ARE_DATA_ONLY =
            classes()
                    .that()
                    .resideInAPackage("..dao.mapper")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage(
                            "java..",
                            "..dao.entity",
                            "..dao.mapper",
                            "..dto.resp",
                            "org.apache.ibatis.annotations..");

    @ArchTest
    static final ArchRule MAPPERS_ARE_MYBATIS_INTERFACES =
            classes()
                    .that()
                    .resideInAPackage("..dao.mapper")
                    .should()
                    .beInterfaces()
                    .andShould()
                    .beAnnotatedWith(Mapper.class);

    @ArchTest
    static final ArchRule PERSISTENCE_ENTITIES_DO_NOT_DEPEND_ON_WEB_MODELS =
            classes()
                    .that()
                    .resideInAPackage("..dao.entity")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", "..dao.entity");

    @ArchTest
    static final ArchRule REQUESTS_AND_RESPONSES_DO_NOT_EXPOSE_PERSISTENCE =
            noClasses()
                    .that()
                    .resideInAPackage("..dto..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..dao..", "..service..", "..controller..", "..core..");

    @ArchTest
    static final ArchRule SERVICE_CONTRACTS_ARE_INTERFACES =
            classes().that().resideInAPackage("..service").should().beInterfaces();

    @ArchTest
    static final ArchRule SERVICE_IMPLEMENTATIONS_HAVE_THEIR_OWN_PACKAGE =
            classes()
                    .that()
                    .areAnnotatedWith(Service.class)
                    .should()
                    .resideInAPackage("..service.impl");

    @ArchTest
    static final ArchRule HTTP_ENTRY_POINTS_HAVE_THEIR_OWN_PACKAGE =
            classes()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .should()
                    .resideInAPackage("..controller");

    @ArchTest
    static final ArchRule SPRING_CONFIGURATION_IS_CENTRALIZED =
            classes()
                    .that()
                    .areAnnotatedWith(Configuration.class)
                    .should()
                    .resideInAPackage("..core.config");

    @ArchTest
    static final ArchRule EXCEPTION_TRANSLATION_IS_CENTRALIZED =
            classes()
                    .that()
                    .areAnnotatedWith(RestControllerAdvice.class)
                    .should()
                    .resideInAPackage("..core.exception");
}
