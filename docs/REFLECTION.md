# Reflection compatibility contract

`flixw-metrics` uses only the compiler JAR flixw selected and verified for the current
project. It does not download a compiler, choose a version, or load an arbitrary JAR from a
project setting.

The initial native backend opens that JAR in an isolated `URLClassLoader` without class
initialization. It requires both `ca.uwaterloo.flix.api.Flix` and
`ca.uwaterloo.flix.tools.Metrics$`, then delegates to the compiler's `metric` command.
This is intentionally conservative: a compiler without those classes is unsupported, not
approximated by a source scanner.

The planned reflective engine will retain that capability gate and add explicit adapters for
verified compiler builds. It must never claim that a class name or a version string alone
proves API compatibility; every adapter will be tested against the exact release artifact it
supports.
