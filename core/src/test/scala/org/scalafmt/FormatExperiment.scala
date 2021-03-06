package org.scalafmt

import scala.util.Try
import scalariform.formatter.ScalaFormatter
import scalariform.formatter.preferences.FormattingPreferences
import scalariform.formatter.preferences.IndentSpaces

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import org.scalafmt.util.ExperimentResult
import org.scalafmt.util.ExperimentResult.ParseErr
import org.scalafmt.util.ExperimentResult.SearchStateExploded
import org.scalafmt.util.ExperimentResult.Skipped
import org.scalafmt.util.ExperimentResult.Success
import org.scalafmt.util.ExperimentResult.Timeout
import org.scalafmt.util.FormatAssertions
import org.scalafmt.util.LoggerOps
import org.scalafmt.util.ScalaFile
import org.scalafmt.util.ScalaProjectsExperiment
import org.scalafmt.util.ScalacParser
import org.scalatest.FunSuite
import scala.collection.JavaConversions._
import scala.meta._

trait FormatExperiment extends ScalaProjectsExperiment with FormatAssertions {
  import LoggerOps._
  override val verbose = false

  val okRepos = Set(
      "goose",
      "scala-js",
      "fastparse",
      "scalding",
      "spark",
      "akka",
      "intellij-scala",
      "I wan't trailing commas!!!"
  )
  val badRepos = Set(
      "kafka"
  )

  def okScalaFile(scalaFile: ScalaFile): Boolean = {
    okRepos(scalaFile.repo) && !badFile(scalaFile.filename)
  }

  def badFile(filename: String): Boolean =
    Seq().exists(filename.contains)

  override def runOn(scalaFile: ScalaFile): ExperimentResult = {
    val code = scalaFile.read

    if (!ScalacParser.checkParseFails(code)) {
      val startTime = System.nanoTime()
      Scalafmt.format(code, ScalafmtStyle.default) match {
        case FormatResult.Success(formatted) =>
          val elapsed = System.nanoTime() - startTime
          assertFormatPreservesAst[Source](code, formatted)
          val formattedSecondTime = Scalafmt
            .format(
                code,
                ScalafmtStyle.default.copy(alignStripMarginStrings = false))
            .get
          assertNoDiff(formattedSecondTime, formatted, "Idempotency")
          Success(scalaFile, elapsed)
        case e => e.get; ???
      }
    } else {
      Skipped(scalaFile)
    }
  }

  def scalaFiles = ScalaFile.getAll.filter(okScalaFile)
}

object LinePerMsBenchmark extends FormatExperiment with App {
  case class Result(formatter: String, lineCount: Int, ns: Long) {
    def toCsv: String = s"$formatter, $lineCount, $ns\n"
  }

  val csv = new CopyOnWriteArrayList[Result]()

  def time[T](f: => T): Long = {
    val startTime = System.nanoTime()
    f
    System.nanoTime() - startTime
  }
  val counter = new AtomicInteger()

  scalaFiles.par.foreach { scalaFile =>
    val code = scalaFile.read
    val lineCount = code.lines.length
    Try(Result("scalafmt", lineCount, time(Scalafmt.format(code))))
      .foreach(csv.add)
    Try(Result("scalariform", lineCount, time(ScalaFormatter.format(code))))
      .foreach(csv.add)
    val c = counter.incrementAndGet()
    if (c % 1000 == 0) {
      println(c)
    }
  }

  val csvText = {
    val sb = new StringBuilder
    sb.append(s"Formatter, LOC, ns")
    csv.foreach(x => sb.append(x.toCsv))
    sb.toString()
  }

  Files.write(Paths.get("target", "macro.csv"), csvText.getBytes)
}

object FormatExperimentApp extends FormatExperiment with App {
  def valid(result: ExperimentResult): Boolean = result match {
    case _: Success | _: Timeout | _: Skipped |
        _: ParseErr | _: SearchStateExploded =>
      true
    case failure => false
  }

  // Java 7 times out on Travis.
  if (!sys.env.contains("TRAVIS") ||
      sys.props("java.specification.version") == "1.8") {
    runExperiment(scalaFiles)
    printResults()
    val nonValidResults = results.filterNot(valid)
    nonValidResults.foreach(println)
    if (nonValidResults.nonEmpty) {
      throw new IllegalStateException("Failed test.")
    }
  } else {
    println("Skipping test")
  }
}
