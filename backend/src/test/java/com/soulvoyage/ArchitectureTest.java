package com.soulvoyage;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.freeze.TextFileBasedViolationStore;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * B-18：结构守卫。存量 Controller→Repository、api→domain 依赖先冻结（不再新增），
 * 新增代码一律走"Controller → Service → Repository"；字段注入全局禁止。
 * 冻结清单位于 archunit_store/（首次运行生成，需随代码提交）。
 */
@AnalyzeClasses(packages = "com.soulvoyage", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule controllersMustNotDependOnRepositories = FreezingArchRule.freeze(
                    noClasses().that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository"))
            .persistIn(new TextFileBasedViolationStore());

    @ArchTest
    static final ArchRule apiLayerMustNotDependOnDomainLayer = FreezingArchRule.freeze(
                    noClasses().that().resideInAPackage("..api..")
                            .should().dependOnClassesThat().resideInAPackage("..domain.."))
            .persistIn(new TextFileBasedViolationStore());

    @ArchTest
    static final ArchRule noFieldInjection = fields()
            .should().notBeAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .because("统一构造器注入，避免运行期不可变性与循环依赖问题");
}
