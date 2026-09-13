package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.ModuleInfo;

import java.util.List;

/** Checks named construction of the compiler-neutral definition model. */
public final class CompilerModelTest {
    private CompilerModelTest() { }

    public static void main(String[] args) {
        DefInfo def = DefInfo.builder("A.f", "A", "src/A.flix", 7)
            .lines(30).codeLines(20).parameters(2).maxLocalParameters(3).localDefs(4)
            .nesting(5).cognitive(6).maxLineTokens(40).maxLineTokensLine(9)
            .maxLineTokensOwner("A.f.loop").datalogRules(8).datalogFacts(10).returnWidth(11)
            .isPublic(true).isTest(false).hasDoc(true).effects(List.of("IO"))
            .flixdocParameterCharacters(41).formalParameterNames(List.of("x", "y"))
            .docText("docs").datalogDependencies(List.of("Edge", "Path"))
            .datalogDependencyDepth(3).recursiveDatalogPredicates(List.of("Path"))
            .flixdocResultCharacters(17).build();

        require(def.line() == 7 && def.lines() == 30 && def.codeLines() == 20,
            "source span fields retain their named values");
        require(def.parameters() == 2 && def.maxLocalParameters() == 3 && def.localDefs() == 4,
            "parameter and local-definition fields cannot exchange positions");
        require(def.maxLineTokens() == 40 && def.maxLineTokensLine() == 9
                && def.maxLineTokensOwner().equals("A.f.loop"),
            "crammed-line value, location and owner retain their named values");
        require(def.datalogRules() == 8 && def.datalogFacts() == 10,
            "Datalog rule and fact counts retain their named values");
        require(def.datalogDependencyDepth() == 3
                && def.recursiveDatalogPredicates().equals(List.of("Path"))
                && def.flixdocResultCharacters() == 17,
            "new tail fields remain explicit rather than constructor-position dependent");
        ModuleInfo module = new ModuleInfo("A", 2, 30, 2, 1,
            List.of("Z", "B"), List.of("C", "B"));
        require(module.dependencies().equals(List.of("B", "Z"))
                && module.dependents().equals(List.of("B", "C")),
            "module dependency context is retained in stable order");
        System.out.println("CompilerModelTest: ok");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
