package dev.flixw.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Linear-time strongly-connected components and depth for Datalog predicate graphs. */
public final class DatalogGraph {
    private DatalogGraph() { }

    public record Edge(String head, String body) { }
    public record Analysis(List<String> dependencies, int depth, List<String> recursive) { }
    private record Visit(String node, boolean expanded) { }

    public static Analysis analyze(Set<Edge> edges) {
        if (edges.isEmpty()) return new Analysis(List.of(), 0, List.of());
        Set<String> nodes = new TreeSet<>();
        Map<String, Set<String>> graph = new HashMap<>(), reverse = new HashMap<>();
        for (Edge edge : edges) {
            nodes.add(edge.head()); nodes.add(edge.body());
            graph.computeIfAbsent(edge.head(), ignored -> new HashSet<>()).add(edge.body());
            reverse.computeIfAbsent(edge.body(), ignored -> new HashSet<>()).add(edge.head());
        }
        List<String> order = finishOrder(nodes, graph);
        Map<String, Integer> component = new HashMap<>();
        List<List<String>> members = new ArrayList<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            String start = order.get(i);
            if (component.containsKey(start)) continue;
            int id = members.size();
            List<String> group = new ArrayList<>();
            ArrayDeque<String> stack = new ArrayDeque<>();
            stack.push(start);
            component.put(start, id);
            while (!stack.isEmpty()) {
                String node = stack.pop(); group.add(node);
                for (String next : reverse.getOrDefault(node, Set.of()))
                    if (component.putIfAbsent(next, id) == null) stack.push(next);
            }
            members.add(group);
        }

        List<Set<Integer>> dag = new ArrayList<>(), parents = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) { dag.add(new HashSet<>()); parents.add(new HashSet<>()); }
        for (Edge edge : edges) {
            int from = component.get(edge.head()), to = component.get(edge.body());
            if (from != to && dag.get(from).add(to)) parents.get(to).add(from);
        }
        int[] remaining = new int[members.size()], depth = new int[members.size()];
        ArrayDeque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < members.size(); i++) {
            remaining[i] = dag.get(i).size();
            if (remaining[i] == 0) { depth[i] = 1; ready.add(i); }
        }
        while (!ready.isEmpty()) {
            int child = ready.removeFirst();
            for (int parent : parents.get(child)) {
                depth[parent] = Math.max(depth[parent], depth[child] + 1);
                if (--remaining[parent] == 0) ready.add(parent);
            }
        }
        TreeSet<String> recursive = new TreeSet<>(), dependencies = new TreeSet<>();
        for (Edge edge : edges) dependencies.add(edge.body());
        for (int i = 0; i < members.size(); i++)
            if (members.get(i).size() > 1) recursive.addAll(members.get(i));
        for (Edge edge : edges)
            if (edge.head().equals(edge.body())) recursive.add(edge.head());
        int maximum = 0;
        for (int value : depth) maximum = Math.max(maximum, value);
        return new Analysis(List.copyOf(dependencies), maximum, List.copyOf(recursive));
    }

    private static List<String> finishOrder(Set<String> nodes, Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        List<String> order = new ArrayList<>();
        for (String start : nodes) {
            ArrayDeque<Visit> stack = new ArrayDeque<>(); stack.push(new Visit(start, false));
            while (!stack.isEmpty()) {
                Visit visit = stack.pop();
                if (visit.expanded()) { order.add(visit.node()); continue; }
                if (!visited.add(visit.node())) continue;
                stack.push(new Visit(visit.node(), true));
                for (String next : graph.getOrDefault(visit.node(), Set.of()))
                    if (!visited.contains(next)) stack.push(new Visit(next, false));
            }
        }
        return order;
    }
}
