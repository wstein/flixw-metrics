package dev.flixw.metrics

/**
 * Measurement logic every version-specific Flix adapter shares once its own AST values have
 * been reduced to plain data.
 *
 * Each adapter still owns its own AST walk -- pattern-matching Flix's sealed hierarchy directly
 * is what makes that match checked by the compiler, which is the entire reason these adapters
 * are Scala and not reflection. What does not vary by compiler version is what happens
 * afterward: scoring a set of already-extracted spans for nesting and cognitive weight is the
 * same arithmetic whether the compiler called a field `startLine` or `beginLine`. That scoring
 * was duplicated six ways -- once per supported Flix generation -- before this file existed.
 */
object AdapterSupport {

  /**
   * A source span, independent of any particular compiler release's location type.
   *
   * Each adapter converts its own version's `SourceLocation` into one of these exactly once, at
   * the point it records a branch or a local definition's body; everything downstream compares
   * spans, never a compiler type, so the same scoring code runs unchanged regardless of which
   * field names that release's `SourceLocation` happens to use.
   *
   * @param source identifies which compiler source this span belongs to. Carried opaquely and
   *     compared with the adapter's own `==` -- whatever equality the compiler's `Source` type
   *     defines -- never interpreted here.
   */
  final case class Span(source: AnyRef, beginLine: Int, beginCol: Int, endLine: Int, endCol: Int)

  /** What one pass over a definition's body yields, expressed without any compiler type. */
  final class Tally {
    val branches = scala.collection.mutable.ListBuffer.empty[Span]
    /** Name and span of each local definition, for attributing a crammed line to the right one. */
    val locals = scala.collection.mutable.ListBuffer.empty[(String, Span)]
    var localDefs = 0
    var widestLocalParams = 0
    var widestLocalName: String = null
    var widestLocalLine = 0
    var booleans = 0
    var guards = 0
    var datalogRules = 0
    var datalogFacts = 0
    val datalogEdges = scala.collection.mutable.Set.empty[(String, String)]
  }

  /**
   * Each branch weighted by how many branches enclose it, plus boolean operators and guards.
   *
   * Five nested conditions are harder to hold in the head than five consecutive ones, and a
   * flat count says they are the same. This is the fork's `cognitiveComplexity`, ported.
   */
  def cognitive(tally: Tally): Int = {
    val spans = tally.branches.toList
    spans.map(span => spans.count(other => contains(other, span))).sum + tally.booleans + tally.guards
  }

  /** The longest chain of spans each contained in the last. */
  def deepestChain(spans: List[Span]): Int =
    if (spans.isEmpty) 0 else spans.map(span => spans.count(other => contains(other, span))).max

  /** How many source lines a span covers, inclusive of both ends. */
  def spannedLines(span: Span): Int = span.endLine - span.beginLine + 1

  private def contains(outer: Span, inner: Span): Boolean =
    outer.source == inner.source &&
      before(outer.beginLine, outer.beginCol, inner.beginLine, inner.beginCol) &&
      before(inner.endLine, inner.endCol, outer.endLine, outer.endCol)

  private def before(line1: Int, col1: Int, line2: Int, col2: Int): Boolean =
    line1 < line2 || (line1 == line2 && col1 <= col2)
}
