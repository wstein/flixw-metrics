package dev.flixw.metrics;

import java.util.HashSet;
import java.util.Set;

/** Exercises graph sizes that made pairwise recursive reachability unsafe. */
public final class DatalogGraphTest {
    private DatalogGraphTest() { }
    public static void main(String[] args) {
        Set<DatalogGraph.Edge> chain = new HashSet<>();
        for (int i = 0; i < 2_000; i++) chain.add(new DatalogGraph.Edge("p" + i, "p" + (i + 1)));
        var linear = DatalogGraph.analyze(chain);
        require(linear.depth() == 2_001 && linear.recursive().isEmpty(),
            "a long acyclic chain is iterative and retains its exact depth");

        Set<DatalogGraph.Edge> cycle = new HashSet<>();
        for (int i = 0; i < 2_000; i++) cycle.add(new DatalogGraph.Edge("c" + i, "c" + ((i + 1) % 2_000)));
        var recursive = DatalogGraph.analyze(cycle);
        require(recursive.depth() == 1 && recursive.recursive().size() == 2_000,
            "a large cycle collapses to one recursive component");
        require(DatalogGraph.analyze(Set.of(new DatalogGraph.Edge("self", "self")))
            .recursive().equals(java.util.List.of("self")), "a self-loop is recursive");
        System.out.println("DatalogGraphTest: ok");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
