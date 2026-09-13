package io.github.wanhkjd.cloudnovel;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/** 自动守住三层依赖方向，防止后续把 SQL 或业务实现重新写回 Controller。 */
@AnalyzeClasses(
        packages = "io.github.wanhkjd.cloudnovel",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule CONTROLLERS_USE_SERVICE_CONTRACTS =
            noClasses()
                    .that()
                    .resideInAPackage("..controller..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..mapper..",
                            "..entity..",
                            "..service.impl..",
                            "..storage..",
                            "..parser..",
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
                            "org.springframework.web..",
                            "org.springframework.http..",
                            "org.springframework.security..",
                            "org.springframework.jdbc..",
                            "java.sql..",
                            "jakarta.servlet..");

    @ArchTest
    static final ArchRule MAPPERS_ARE_DATA_ONLY =
            noClasses()
                    .that()
                    .resideInAPackage("..mapper..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..controller..",
                            "..service..",
                            "..dto..",
                            "..security..",
                            "..storage..",
                            "..parser..");

    @ArchTest
    static final ArchRule MAPPERS_ARE_MYBATIS_INTERFACES =
            classes()
                    .that()
                    .resideInAPackage("..mapper..")
                    .should()
                    .beInterfaces()
                    .andShould()
                    .beAnnotatedWith(Mapper.class);

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
}
