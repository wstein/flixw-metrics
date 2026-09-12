mod Alpha {
    /// Returns both arguments.
    pub def documented(x: Int32, y: Int32): (Int32, Int32) = (x, y)

    pub def nestedRecord():
        { position = { x = Int32, y = Int32 }, size = { x = Int32, y = Int32 } } =
        { position = { x = 0, y = 0 }, size = { x = 1, y = 1 } }

    /// Returns one edge and its derived path.
    pub def datalog(): #{ Edge(Int32, Int32), Path(Int32, Int32) } = #{
        Edge(1, 2).
        Path(x, y) :- Edge(x, y).
    }

    pub def selectValue(x: Int32): Int32 =
        def helper(y, z) =
            if (y > z and y > 0) y else z;
        helper(x, 0)
}

mod Beta {
    pub def call(x: Int32): (Int32, Int32) = Alpha.documented(x, Alpha.selectValue(x))
}
