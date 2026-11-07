mod Calc {
    /// Doubles.
    pub def double(x: Int32): Int32 = x * 2

    pub def classify(x: Int32): String =
        if (x > 10) "big" else if (x > 5) "mid" else "small"
}

mod App {
    pub def main(): Unit \ IO =
        println(Calc.classify(Calc.double(4)))
}
