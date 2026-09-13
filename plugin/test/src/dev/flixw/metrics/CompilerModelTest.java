package dev.flixw.metrics;

import dev.flixw.metrics.sdk.CompilerModel.DefInfo;
import dev.flixw.metrics.sdk.CompilerModel.DefInfo.EffectDetail;
import dev.flixw.metrics.sdk.CompilerModel.EffectInfo;
import dev.flixw.metrics.sdk.CompilerModel.EffectOperationInfo;
import dev.flixw.metrics.sdk.CompilerModel.ModuleInfo;

import java.util.List;

/** Checks named construction of the compiler-neutral definition model. */
public final class CompilerModelTest {
    private CompilerModelTest() { }

    public static void main(String[] args) {
        DefInfo def = DefInfo.builder("A.f", "A", "src/A.flix", 7)
            .lines(30).codeLines(20).parameters(2).maxLocalParameters(3)
            .maxLocalParametersOwner("A.f.loop").maxLocalParametersLine(12).localDefs(4)
            .nesting(5).cognitive(6).maxLineTokens(40).maxLineTokensLine(9)
            .maxLineTokensOwner("A.f.loop").datalogRules(8).datalogFacts(10).returnWidth(11)
            .isPublic(true).isTest(false).hasDoc(true).effects(List.of("IO"))
            .flixdocParameterCharacters(41).formalParameterNames(List.of("x", "y"))
            .docText("docs").datalogDependencies(List.of("Edge", "Path"))
            .datalogDependencyDepth(3).recursiveDatalogPredicates(List.of("Path"))
            .flixdocResultCharacters(17).handlers(2).handledOperations(5)
            .maxHandlerOperations(3).resumptions(4)
            .effectDetails(List.of(new EffectDetail("State", List.of("Int32")))).build();

        require(def.line() == 7 && def.lines() == 30 && def.codeLines() == 20,
            "source span fields retain their named values");
        require(def.parameters() == 2 && def.maxLocalParameters() == 3 && def.localDefs() == 4,
            "parameter and local-definition fields cannot exchange positions");
        require(def.maxLocalParametersOwner().equals("A.f.loop")
                && def.maxLocalParametersLine() == 12,
            "the widest local retains its edit-ready owner and declaration line");
        require(def.maxLineTokens() == 40 && def.maxLineTokensLine() == 9
                && def.maxLineTokensOwner().equals("A.f.loop"),
            "crammed-line value, location and owner retain their named values");
        require(def.datalogRules() == 8 && def.datalogFacts() == 10,
            "Datalog rule and fact counts retain their named values");
        require(def.datalogDependencyDepth() == 3
                && def.recursiveDatalogPredicates().equals(List.of("Path"))
                && def.flixdocResultCharacters() == 17,
            "new tail fields remain explicit rather than constructor-position dependent");
        require(def.handlers() == 2 && def.handledOperations() == 5
                && def.maxHandlerOperations() == 3 && def.resumptions() == 4,
            "effect-handler fields retain their named values");
        require(def.effectDetails().equals(List.of(
                new EffectDetail("State", List.of("Int32"))))
                && def.effectDetails().get(0).argumentCount() == 1,
            "instantiated effect details retain typed arguments");
        EffectInfo effect = new EffectInfo("A.Console", "src/A.flix", 2, 1,
            List.of(new EffectOperationInfo("print", 1),
                new EffectOperationInfo("format", 3)));
        require(effect.operationCount() == 2 && effect.maxOperationArity() == 3,
            "effect operation shape is derived from stable operation records");
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
