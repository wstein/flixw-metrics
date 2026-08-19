package dev.flixw.metrics.flix075

import dev.flixw.metrics.sdk.CompilerModel
import dev.flixw.metrics.sdk.CompilerModel.{DefInfo, LineInfo, Model, ModelFailure, ModuleInfo}

import ca.uwaterloo.flix.api.{Bootstrap, Flix}
import ca.uwaterloo.flix.language.ast.shared.{Input, Source}
import ca.uwaterloo.flix.language.phase.Lexer
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, Type, TypedAst}
import ca.uwaterloo.flix.util.{Formatter, Options}

import java.nio.file.Path
import scala.jdk.CollectionConverters._

/**
 * The one place that knows what Flix's AST looks like.
 *
 * ==Why this file is Scala==
 *
 * Flix's AST is a sealed hierarchy, so a match over it is '''checked'''. Stock 0.75.3 has 76
 * `Expr` constructs; `-Xfatal-warnings` makes the build name every one an exhaustive match
 * forgets. The reflective predecessor classified nodes by simple class name and ignored the rest
 * in silence, which cost exactly what it sounds like: it named a `TypeMatchRule` that does not
 * exist and missed the `ExtMatchRule` that does, so every extensible match was undercounted and
 * nothing said so.
 *
 * ==What it costs==
 *
 * A hard dependency on one Flix AST. This engine links against the release in `plugin/lib`, and
 * against a materially different compiler it does not load at all — which
 * [[dev.flixw.metrics.sdk.Adapters]] turns into a sentence rather than a `NoSuchMethodError`.
 * For a measurement tool, refusing to run beats a number that is quietly wrong.
 */
final class Flix075Adapter extends CompilerModel {

  override def targets: String = "Flix 0.75.x"

  @throws(classOf[ModelFailure])
  override def measure(projectRoot: Path): Model = {
    val flix = new Flix()
    flix.setOptions(Options.Default)

    // Formatter and the stream are implicit parameters, not positional ones. Erasure makes an
    // implicit parameter list look like any other, which is why the reflective predecessor
    // passed four arguments to a two-argument method and could not have known.
    val bootstrap = Bootstrap.bootstrap(projectRoot, None)(Formatter.getDefault, System.err) match {
      case ca.uwaterloo.flix.util.Result.Ok(b) => b
      case ca.uwaterloo.flix.util.Result.Err(e) =>
        throw new ModelFailure("cannot load the project: " + e)
    }
    // `unsafeGet` throws a Scala exception carrying the compiler's whole rendered error, stack
    // trace and all. That is the compiler talking to its own developers; a plugin that lets it
    // through has turned "your project does not compile" into a crash report.
    bootstrap.check(flix) match {
      case ca.uwaterloo.flix.util.Result.Ok(_) => ()
      case ca.uwaterloo.flix.util.Result.Err(e) =>
        // The compiler's own rendering, not a summary of it. Saying "errors are above" while
        // discarding them was worse than saying nothing: it named a place to look that was empty.
        throw new ModelFailure("the project does not compile\n" + e.message(Formatter.getDefault))
    }
    val root = flix.check()._1.getOrElse(
      throw new ModelFailure("the project does not compile; the compiler's errors are above"))

    val defs = ofProject(root.defs.values, projectRoot)(_.loc)
    // Self-edges dropped: a module calling itself is not coupling.
    val edges: Set[(String, String)] =
      defs.flatMap(references).toSet.filter(e => e._1 != e._2)
    val sources = root.sources.keys.filter(src => projectSource(src, projectRoot)).toList
    val tokens = tokensPerLine(sources)
    val infos = defs.map(measureDef(_, projectRoot, tokens))

    new Model(
      infos.asJava, modules(infos, edges).asJava, lineInfo(sources),
      ofProject(root.traits.values, projectRoot)(_.loc).size,
      ofProject(root.instances.values, projectRoot)(_.loc).size,
      ofProject(root.enums.values, projectRoot)(_.loc).size,
      ofProject(root.structs.values, projectRoot)(_.loc).size,
      ofProject(root.effects.values, projectRoot)(_.loc).size,
      ofProject(root.typeAliases.values, projectRoot)(_.loc).size)
  }

  // ---- lines ----------------------------------------------------------------------------

  /**
   * Classifies every line of the project's own sources, using the compiler's lexer.
   *
   * Lexed here rather than read from `Root.tokens`: by the time `check` returns that map holds
   * only what later phases still needed, so a file of any size arrives with a handful of tokens
   * and every line after the first would be counted blank. This is the fork's finding, and it is
   * the kind of thing only someone who tried the obvious way first would know.
   *
   * A line is code if any code token touches it, a comment if only comment tokens do, and blank
   * otherwise -- so a line of code with a trailing comment is code, which is what a reader has to
   * treat it as.
   */
  /** Tokens per line, per file, from the same lex that classifies lines. */
  private def tokensPerLine(sources: List[Source]): Map[String, Map[Int, Int]] =
    sources.map { src =>
      val counts = scala.collection.mutable.Map.empty[Int, Int]
      val (tokens, _) = Lexer.lex(src)
      tokens.foreach { t =>
        if (t.kind != ca.uwaterloo.flix.language.ast.TokenKind.Eof && !t.kind.isComment) {
          // Counted against the line it starts on. A token spanning lines is one token to read,
          // and charging every line it touches would make a long string literal look like the
          // densest code in the file.
          counts.update(t.start.lineOneIndexed, counts.getOrElse(t.start.lineOneIndexed, 0) + 1)
        }
      }
      src.name -> counts.toMap
    }.toMap

  private def lineInfo(sources: List[Source]): LineInfo = {
    var total = 0; var code = 0; var comment = 0; var doc = 0; var blank = 0
    sources.foreach { src =>
      val text = new String(src.data)
      val split = if (text.isEmpty) Array.empty[String] else text.split("\n", -1)
      // A trailing newline leaves a final empty element that is not a line of the file.
      val count = if (split.nonEmpty && split.last.isEmpty) split.length - 1 else split.length
      val codeLines = scala.collection.mutable.Set.empty[Int]
      val commentLines = scala.collection.mutable.Set.empty[Int]
      val docLines = scala.collection.mutable.Set.empty[Int]
      val (tokens, _) = Lexer.lex(src)
      tokens.foreach { t =>
        if (t.kind != ca.uwaterloo.flix.language.ast.TokenKind.Eof) {
          val target =
            if (!t.kind.isComment) codeLines
            else if (t.text.startsWith("///")) docLines
            else commentLines
          for (line <- t.start.lineOneIndexed to t.end.lineOneIndexed) target += line
        }
      }
      total += count
      for (line <- 1 to count) {
        if (split(line - 1).trim.isEmpty) blank += 1
        else if (codeLines.contains(line)) code += 1
        else if (docLines.contains(line)) doc += 1
        else if (commentLines.contains(line)) comment += 1
        else blank += 1
      }
    }
    new LineInfo(total, code, comment, doc, blank)
  }

  private def projectSource(src: Source, projectRoot: Path): Boolean = src.input match {
    case Input.RealFile(path, _) => path.toAbsolutePath.normalize.startsWith(projectRoot)
    case _ => false
  }

  // ---- one definition -------------------------------------------------------------------

  private def measureDef(d: TypedAst.Def, projectRoot: Path,
                         tokens: Map[String, Map[Int, Int]]): DefInfo = {
    val tally = new Tally
    walk(d.exp, tally)
    val onLines = tokens.getOrElse(d.loc.source.name, Map.empty)
    // The densest line this definition spans, and who actually owns it.
    val (crammedLine, crammedTokens) =
      (d.loc.startLine to d.loc.endLine)
        .map(line => (line, onLines.getOrElse(line, 0)))
        .maxByOption(_._2).getOrElse((d.loc.startLine, 0))
    val owner = tally.locals
      .filter { case (_, loc) => loc.startLine <= crammedLine && crammedLine <= loc.endLine }
      // Innermost wins: nested locals all contain the line, and the smallest span is the one
      // whose body a reader would actually be looking at.
      .minByOption { case (_, loc) => loc.endLine - loc.startLine }
      .map { case (name, _) => d.sym.toString + "." + name }
      .getOrElse(d.sym.toString)
    new DefInfo(
      d.sym.toString, moduleOf(d.sym), relativise(d.loc, projectRoot),
      d.loc.startLine, spannedLines(d.loc), declaredParameters(d.spec.fparams.toList),
      tally.widestLocalParams, tally.localDefs, deepestChain(tally.branches.toList),
      cognitive(tally), crammedTokens, crammedLine, owner,
      d.spec.mod.isPublic, d.spec.ann.isTest, hasDoc(d),
      effectsOf(d.spec.eff).asJava)
  }

  /**
   * A definition with a doc comment, asked of the doc's text rather than its presence.
   *
   * Every declaration carries a `Doc`; an undocumented one carries an empty one. Testing for the
   * field would report full coverage on a project with no documentation at all.
   */
  private def hasDoc(d: TypedAst.Def): Boolean = d.spec.doc.text.trim.nonEmpty

  /**
   * The effects a signature declares, as names.
   *
   * Strings, because they cross into the SDK where compiler types may not go — and because what
   * a reader wants from an effect here is which one it is, not its internal structure. `Pure` is
   * the absence of an effect and is reported as the empty list, so purity is `isEmpty` rather
   * than a comparison against a magic name.
   */
  private def effectsOf(eff: Type): List[String] = eff match {
    case Type.Cst(ca.uwaterloo.flix.language.ast.TypeConstructor.Pure, _) => Nil
    case _ => eff.effects.toList.map(_.name).sorted
  }

  private def declaredParameters(fparams: List[TypedAst.FormalParam]): Int = fparams match {
    // A nullary definition is written `def f(): T` and reaches here as one Unit parameter, which
    // is a spelling and not an argument anyone passes.
    case one :: Nil if one.tpe == Type.Unit => 0
    case ps => ps.length
  }

  private def spannedLines(loc: SourceLocation): Int = loc.endLine - loc.startLine + 1

  private def moduleOf(sym: Symbol.DefnSym): String = sym.namespace.mkString(".")

  // ---- the walk -------------------------------------------------------------------------

  /** What one pass over a definition yields. */
  private final class Tally {
    val branches = scala.collection.mutable.ListBuffer.empty[SourceLocation]
    /** Name and span of each local definition, for attributing a crammed line to the right one. */
    val locals = scala.collection.mutable.ListBuffer.empty[(String, SourceLocation)]
    var localDefs = 0
    var widestLocalParams = 0
    var booleans = 0
    var guards = 0
  }

  /**
   * Each branch weighted by how many branches enclose it, plus boolean operators and guards.
   *
   * Five nested conditions are harder to hold in the head than five consecutive ones, and a
   * flat count says they are the same. This is the fork's `cognitiveComplexity`, ported.
   */
  private def cognitive(tally: Tally): Int = {
    val locs = tally.branches.toList
    locs.map(loc => locs.count(other => contains(other, loc))).sum + tally.booleans + tally.guards
  }

  /** The longest chain of locations each contained in the last. */
  private def deepestChain(locs: List[SourceLocation]): Int =
    if (locs.isEmpty) 0 else locs.map(loc => locs.count(other => contains(other, loc))).max

  private def contains(outer: SourceLocation, inner: SourceLocation): Boolean =
    outer.source == inner.source &&
      before(outer.startLine, outer.startCol, inner.startLine, inner.startCol) &&
      before(inner.endLine, inner.endCol, outer.endLine, outer.endCol)

  private def before(line1: Int, col1: Int, line2: Int, col2: Int): Boolean =
    line1 < line2 || (line1 == line2 && col1 <= col2)

  /**
   * Records what each construct contributes, then descends.
   *
   * The catch-all is the one place this file gives up exhaustiveness, and deliberately: with 76
   * constructs, listing the ~65 that contribute nothing would bury the handful that do.
   * Sub-expressions are still traversed, so a construct added by a later Flix has its
   * '''contents''' measured before anyone teaches this match about it.
   */
  private def walk(node: Any, tally: Tally): Unit = {
    node match {
      case e: TypedAst.Expr.IfThenElse => tally.branches += e.loc
      case r: TypedAst.MatchRule =>
        tally.branches += r.exp.loc
        if (r.guard.isDefined) tally.guards += 1
      case r: TypedAst.ExtMatchRule => tally.branches += r.exp.loc
      case r: TypedAst.CatchRule => tally.branches += r.exp.loc
      case r: TypedAst.HandlerRule => tally.branches += r.exp.loc
      case r: TypedAst.SelectChannelRule => tally.branches += r.exp.loc
      case e: TypedAst.Expr.LocalDef =>
        tally.localDefs += 1
        // exp1 is the local's own body; e.loc also covers the continuation after it, so
        // attributing by e.loc would blame a local for lines written past its own end.
        tally.locals += ((e.bnd.sym.text, e.exp1.loc))
        tally.widestLocalParams = tally.widestLocalParams.max(declaredParameters(e.fparams.toList))
      // `and`/`or` add a path through the code without adding a branch construct, which is why
      // a plain branch count reads a long boolean chain as trivial.
      case e: TypedAst.Expr.Binary
        if e.sop == ca.uwaterloo.flix.language.ast.SemanticOp.BoolOp.And
          || e.sop == ca.uwaterloo.flix.language.ast.SemanticOp.BoolOp.Or => tally.booleans += 1
      case _ => ()
    }
    descend(node, tally)
  }

  /**
   * Generic descent, so an unclassified construct still has its contents measured.
   *
   * Types are excluded rather than bounded: no expression lives inside a type, and descending
   * into them walks a cyclic graph the size of the standard library for nothing.
   */
  private def descend(node: Any, tally: Tally): Unit = node match {
    case _: Type => ()
    case _: Symbol => ()
    case it: Iterable[_] => it.foreach(walk(_, tally))
    case p: Product => p.productIterator.foreach(walk(_, tally))
    case _ => ()
  }

  // ---- modules --------------------------------------------------------------------------

  /**
   * Which module depends on which, from resolved references rather than from imports.
   *
   * A reference the type checker resolved is a dependency that exists; an import may be unused
   * and a use may need no import at all.
   */
  private def references(d: TypedAst.Def): List[(String, String)] = {
    val from = moduleOf(d.sym)
    val out = scala.collection.mutable.ListBuffer.empty[(String, String)]
    def scan(node: Any): Unit = {
      node match {
        case e: TypedAst.Expr.ApplyDef => out += ((from, moduleOf(e.symUse.sym)))
        case _ => ()
      }
      node match {
        case _: Type => ()
        case _: Symbol => ()
        case it: Iterable[_] => it.foreach(scan)
        case p: Product => p.productIterator.foreach(scan)
        case _ => ()
      }
    }
    scan(d.exp)
    out.toList
  }

  private def modules(defs: List[DefInfo], edges: Set[(String, String)]): List[ModuleInfo] =
    defs.groupBy(_.module).toList.sortBy(_._1).map { case (name, members) =>
      new ModuleInfo(name, members.size,
        edges.count { case (_, to) => to == name },
        edges.count { case (from, _) => from == name })
    }

  // ---- what belongs to the project ------------------------------------------------------

  private def ofProject[A](values: Iterable[A], projectRoot: Path)(
      at: A => SourceLocation): List[A] =
    values.filter(v => projectFile(at(v), projectRoot).isDefined).toList

  private def relativise(loc: SourceLocation, projectRoot: Path): String =
    projectFile(loc, projectRoot).map(projectRoot.relativize(_).toString).getOrElse(loc.source.name)

  /**
   * The file a location is in, when that file is the project's own.
   *
   * A typed root holds the standard library and every dependency alongside the project, so a
   * report counting those would describe Flix rather than the code in front of you. The test is
   * the real path: a dependency may well have a `List.flix` too.
   */
  private def projectFile(loc: SourceLocation, projectRoot: Path): Option[Path] =
    loc.source.input match {
      case Input.RealFile(path, _) =>
        val real = path.toAbsolutePath.normalize
        if (real.startsWith(projectRoot)) Some(real) else None
      case _ => None
    }
}
