/// A counter effect.
pub eff Tick {
    /// Ticks once.
    def tick(): Unit
}

mod Counter {
    /// Ignores ticks.
    pub def silence(f: Unit -> a \ ef): a \ (ef - Tick) =
        run {
            f()
        } with handler Tick {
            def tick(k) = k()
        }

    pub def ticking(): Unit \ Tick = Tick.tick()

    pub def demo(): Unit = silence(ticking)
}
