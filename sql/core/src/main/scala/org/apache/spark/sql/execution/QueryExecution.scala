/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import java.io.{BufferedWriter, OutputStreamWriter, Writer}

import org.apache.commons.io.output.StringBuilderWriter
import org.apache.hadoop.fs.Path

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.catalyst.{InternalRow, QueryPlanningTracker}
import org.apache.spark.sql.catalyst.analysis.UnsupportedOperationChecker
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, ReturnAnswer}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.util.truncatedString
import org.apache.spark.sql.execution.exchange.{EnsureRequirements, ReuseExchange}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.Utils

/**
 * The primary workflow for executing relational queries using Spark.  Designed to allow easy
 * access to the intermediate phases of query execution for developers.
 *
 * While this is not a public class, we should avoid changing the function names for the sake of
 * changing them, because a lot of developers use the feature for debugging.
 */
class QueryExecution(
    val sparkSession: SparkSession,
    val logical: LogicalPlan,
    val tracker: QueryPlanningTracker = new QueryPlanningTracker) {

  // TODO: Move the planner an optimizer into here from SessionState.
  protected def planner = sparkSession.sessionState.planner

  def assertAnalyzed(): Unit = analyzed

  def assertSupported(): Unit = {
    if (sparkSession.sessionState.conf.isUnsupportedOperationCheckEnabled) {
      UnsupportedOperationChecker.checkForBatch(analyzed)
    }
  }

  lazy val analyzed: LogicalPlan = tracker.measureTime(QueryPlanningTracker.ANALYSIS) {
    SparkSession.setActiveSession(sparkSession)
    sparkSession.sessionState.analyzer.executeAndCheck(logical, tracker)
  }

  lazy val withCachedData: LogicalPlan = {
    assertAnalyzed()
    assertSupported()
    sparkSession.sharedState.cacheManager.useCachedData(analyzed)
  }

  lazy val optimizedPlan: LogicalPlan = tracker.measureTime(QueryPlanningTracker.OPTIMIZATION) {
    sparkSession.sessionState.optimizer.executeAndTrack(withCachedData, tracker)
  }

  lazy val sparkPlan: SparkPlan = tracker.measureTime(QueryPlanningTracker.PLANNING) {
    SparkSession.setActiveSession(sparkSession)
    // TODO: We use next(), i.e. take the first plan returned by the planner, here for now,
    //       but we will implement to choose the best plan.
    planner.plan(ReturnAnswer(optimizedPlan)).next()
  }

  // executedPlan should not be used to initialize any SparkPlan. It should be
  // only used for execution.
  lazy val executedPlan: SparkPlan = tracker.measureTime(QueryPlanningTracker.PLANNING) {
    prepareForExecution(sparkPlan)
  }

  /** Internal version of the RDD. Avoids copies and has no schema */
  lazy val toRdd: RDD[InternalRow] = executedPlan.execute()

  /**
   * Prepares a planned [[SparkPlan]] for execution by inserting shuffle operations and internal
   * row format conversions as needed.
   */
  protected def prepareForExecution(plan: SparkPlan): SparkPlan = {
    preparations.foldLeft(plan) { case (sp, rule) => rule.apply(sp) }
  }

  /** A sequence of rules that will be applied in order to the physical plan before execution. */
  protected def preparations: Seq[Rule[SparkPlan]] = Seq(
    PlanSubqueries(sparkSession),
    EnsureRequirements(sparkSession.sessionState.conf),
    CollapseCodegenStages(sparkSession.sessionState.conf),
    ReuseExchange(sparkSession.sessionState.conf),
    ReuseSubquery(sparkSession.sessionState.conf))

  protected def stringOrError[A](f: => A): String =
    try f.toString catch { case e: AnalysisException => e.toString }


  def simpleString: String = withRedaction {
    s"""== Physical Plan ==
       |${stringOrError(executedPlan.treeString(verbose = false))}
      """.stripMargin.trim
  }

  private def writeOrError(writer: Writer)(f: Writer => Unit): Unit = {
    try f(writer)
    catch {
      case e: AnalysisException => writer.write(e.toString)
    }
  }

  private def writePlans(writer: Writer, maxFields: Int): Unit = {
    val (verbose, addSuffix) = (true, false)

    writer.write("== Parsed Logical Plan ==\n")
    writeOrError(writer)(logical.treeString(_, verbose, addSuffix, maxFields))
    writer.write("\n== Analyzed Logical Plan ==\n")
    val analyzedOutput = stringOrError(truncatedString(
      analyzed.output.map(o => s"${o.name}: ${o.dataType.simpleString}"), ", ", maxFields))
    writer.write(analyzedOutput)
    writer.write("\n")
    writeOrError(writer)(analyzed.treeString(_, verbose, addSuffix, maxFields))
    writer.write("\n== Optimized Logical Plan ==\n")
    writeOrError(writer)(optimizedPlan.treeString(_, verbose, addSuffix, maxFields))
    writer.write("\n== Physical Plan ==\n")
    writeOrError(writer)(executedPlan.treeString(_, verbose, addSuffix, maxFields))
  }

  override def toString: String = withRedaction {
    val writer = new StringBuilderWriter()
    try {
      writePlans(writer, SQLConf.get.maxToStringFields)
      writer.toString
    } finally {
      writer.close()
    }
  }

  def stringWithStats: String = withRedaction {
    // trigger to compute stats for logical plans
    optimizedPlan.stats

    // only show optimized logical plan and physical plan
    s"""== Optimized Logical Plan ==
        |${stringOrError(optimizedPlan.treeString(verbose = true, addSuffix = true))}
        |== Physical Plan ==
        |${stringOrError(executedPlan.treeString(verbose = true))}
    """.stripMargin.trim
  }

  /**
   * Redact the sensitive information in the given string.
   */
  private def withRedaction(message: String): String = {
    Utils.redact(sparkSession.sessionState.conf.stringRedactionPattern, message)
  }

  /** A special namespace for commands that can be used to debug query execution. */
  // scalastyle:off
  object debug {
  // scalastyle:on

    /**
     * Prints to stdout all the generated code found in this plan (i.e. the output of each
     * WholeStageCodegen subtree).
     */
    def codegen(): Unit = {
      // scalastyle:off println
      println(org.apache.spark.sql.execution.debug.codegenString(executedPlan))
      // scalastyle:on println
    }

    /**
     * Get WholeStageCodegenExec subtrees and the codegen in a query plan
     *
     * @return Sequence of WholeStageCodegen subtrees and corresponding codegen
     */
    def codegenToSeq(): Seq[(String, String)] = {
      org.apache.spark.sql.execution.debug.codegenStringSeq(executedPlan)
    }

    /**
     * Dumps debug information about query execution into the specified file.
     *
     * @param maxFields maximim number of fields converted to string representation.
     */
    def toFile(path: String, maxFields: Int = Int.MaxValue): Unit = {
      val filePath = new Path(path)
      val fs = filePath.getFileSystem(sparkSession.sessionState.newHadoopConf())
      val writer = new BufferedWriter(new OutputStreamWriter(fs.create(filePath)))

      try {
        writePlans(writer, maxFields)
        writer.write("\n== Whole Stage Codegen ==\n")
        org.apache.spark.sql.execution.debug.writeCodegen(writer, executedPlan)
      } finally {
        writer.close()
      }
    }
  }
}
