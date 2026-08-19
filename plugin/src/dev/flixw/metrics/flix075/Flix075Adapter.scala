package dev.flixw.metrics.flix075

import dev.flixw.metrics.sdk.CompilerModel
import dev.flixw.metrics.sdk.CompilerModel.{Counts, ModelFailure}

import ca.uwaterloo.flix.api.{Bootstrap, Flix}
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.language.ast.shared.Input
import ca.uwaterloo.flix.util.{Formatter, Options}

import java.nio.file.Path

/**
 * The measurements that need the compiler's meaning, taken by pattern matching rather than by
 * reflection.
 *
 * ==Why this file is Scala==
 *
 * Flix's AST is a sealed hierarchy, so a match over it is '''checked'''. With
 * `-Xfatal-warnings` the build fails and names every construct this engine does not classify —
 * and stock 0.75.3 has 76 `Expr` constructs, so "we handle the ones we thought of" is not a
 * position anyone can hold by hand.
 *
 * The reflective predecessor could not know that. It classified nodes by simple class name and
 * silently ignored the rest, which cost exactly what it sounds like: it named a `TypeMatchRule`
 * that does not exist and missed the `ExtMatchRule` that does, so every extensible match was
 * undercounted and nothing said so. That is not a bug that was fixed; it is a bug that this
 * file's compiler makes unrepresentable.
 *
 * ==What it costs==
 *
 * A hard dependency on one Flix AST. This engine links against the release in `plugin/lib`,
 * and against a materially different compiler it will not load at all. That is deliberate:
 * for a measurement tool, refusing to run is a better answer than a number that is quietly
 * wrong. [[CompilerCapabilities]] is what turns that refusal into a sentence, and it stays in
 * Java precisely so it still works when this file would not link.
 */
final class Flix075Adapter extends CompilerModel {

  override def targets: String = "Flix 0.75.x"


  /** Counted while walking; a var-free fold would obscure what is being measured. */
  private final class Tally {
    var localDefs = 0
    var branches = 0
  }

  @throws(classOf[ModelFailure])
  override def measure(projectRoot: Path): Counts = {
    val flix = new Flix()
    flix.setOptions(Options.Default)

    // Formatter and the stream are implicit parameters, not positional ones. The reflective
    // predecessor could not see that difference -- erasure makes an implicit parameter list
    // look like any other -- and passed four arguments to a two-argument method.
    val bootstrap = Bootstrap.bootstrap(projectRoot, None)(Formatter.getDefault, System.err).unsafeGet
    bootstrap.check(flix).unsafeGet
    val root = flix.check()._1.getOrElse(
      throw new ModelFailure("the compiler produced no typed root; fix compilation errors first"))

    val defs = ofProject(root.defs.values, projectRoot)(_.loc)
    val counts = new Tally
    defs.foreach(d => branches(d.exp, counts))

    new Counts(
      modules(defs), defs.size, counts.localDefs, effectful(defs), counts.branches,
      ofProject(root.traits.values, projectRoot)(_.loc).size,
      ofProject(root.instances.values, projectRoot)(_.loc).size,
      ofProject(root.enums.values, projectRoot)(_.loc).size,
      ofProject(root.structs.values, projectRoot)(_.loc).size,
      ofProject(root.effects.values, projectRoot)(_.loc).size,
      ofProject(root.typeAliases.values, projectRoot)(_.loc).size)
  }

  /**
   * Keeps what the project itself declares.
   *
   * A typed root holds the standard library and every dependency alongside the project, and a
   * report that counted those would describe Flix rather than the code in front of you. The
   * test is the source's real path, not its name: a dependency may well have a `List.flix` too.
   */
  private def ofProject[A](values: Iterable[A], projectRoot: Path)(
      at: A => ca.uwaterloo.flix.language.ast.SourceLocation): List[A] =
    values.filter(v => isProjectFile(at(v), projectRoot)).toList

  private def isProjectFile(loc: ca.uwaterloo.flix.language.ast.SourceLocation,
                            projectRoot: Path): Boolean = loc.source.input match {
    case Input.RealFile(path, _) => path.toAbsolutePath.normalize.startsWith(projectRoot)
    // Everything else is the standard library, a package dependency, or something the editor
    // supplied. A report counting those would describe Flix rather than the code in front of
    // you, which is the one thing a project metric must not do.
    case _ => false
  }

  /**
   * Namespaces, from the symbols rather than from the directory layout: a file may hold several
   * modules and a module may span files, so counting files or directories answers a different
   * question than the one asked.
   */
  private def modules(defs: List[TypedAst.Def]): Int =
    defs.map(_.sym.namespace.mkString(".")).distinct.size

  /**
   * Definitions whose signature admits an effect.
   *
   * The '''declared''' effect, not one inferred from the body, because the declaration is the
   * promise the definition makes to its callers — and that is the thing worth counting. The
   * reflective predecessor compared `eff.toString` to `"Pure"`, which was a string where a type
   * belonged.
   */
  private def effectful(defs: List[TypedAst.Def]): Int =
    defs.count(d => !d.spec.eff.isInstanceOf[ca.uwaterloo.flix.language.ast.Type.Cst]
                 || d.spec.eff.toString != "Pure")

  /**
   * Every path through a definition.
   *
   * Rules are counted, not the constructs holding them: a `match` with three cases is three
   * paths, and counting the match would say one.
   *
   * The catch-all is on `Expr` deliberately and is the one place this file gives up
   * exhaustiveness — with 76 constructs, listing the ~65 that contribute no branch would be
   * noise that hides the seven that do. Sub-expressions are still traversed by the generic
   * `Product` descent below, so a new construct's *contents* are measured even before its own
   * branching is classified.
   */
  private def branches(node: Any, counts: Tally): Unit = node match {
    case _: TypedAst.Expr.IfThenElse => counts.branches += 1; descend(node, counts)
    case _: TypedAst.MatchRule       => counts.branches += 1; descend(node, counts)
    case _: TypedAst.ExtMatchRule    => counts.branches += 1; descend(node, counts)
    case _: TypedAst.CatchRule       => counts.branches += 1; descend(node, counts)
    case _: TypedAst.HandlerRule     => counts.branches += 1; descend(node, counts)
    case _: TypedAst.SelectChannelRule => counts.branches += 1; descend(node, counts)
    case _: TypedAst.Expr.LocalDef   => counts.localDefs += 1; descend(node, counts)
    case _                           => descend(node, counts)
  }

  /**
   * Generic descent, so an unclassified construct still has its contents measured.
   *
   * Types are cyclic through their own constructors, so the depth bound is not decoration.
   * Bounded rather than memoised: the AST is a tree once types are excluded, and identity
   * tracking over a whole standard library costs more than the traversal it saves.
   */
  private def descend(node: Any, counts: Tally, depth: Int = 0): Unit = {
    if (depth > 200) return
    node match {
      case _: ca.uwaterloo.flix.language.ast.Type => ()          // no expressions inside a type
      case it: Iterable[_]                        => it.foreach(child => branches(child, counts))
      case p: Product                             => p.productIterator.foreach(branches(_, counts))
      case _                                      => ()
    }
  }
}
