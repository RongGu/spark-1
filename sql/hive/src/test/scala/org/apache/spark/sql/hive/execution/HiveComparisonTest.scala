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

package org.apache.spark.sql.hive.execution

import java.io._

import org.apache.spark.sql.Logging
import org.apache.spark.sql.catalyst.plans.logical.{ExplainCommand, NativeCommand}
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.execution.Sort
import org.scalatest.{BeforeAndAfterAll, FunSuite, GivenWhenThen}
import org.apache.spark.sql.hive.TestHive

/**
 * Allows the creations of tests that execute the same query against both hive
 * and catalyst, comparing the results.
 *
 * The "golden" results from Hive are cached in an retrieved both from the classpath and
 * [[answerCache]] to speed up testing.
 *
 * See the documentation of public vals in this class for information on how test execution can be
 * configured using system properties.
 */
abstract class HiveComparisonTest
  extends FunSuite with BeforeAndAfterAll with GivenWhenThen with Logging {

  /**
   * When set, any cache files that result in test failures will be deleted.  Used when the test
   * harness or hive have been updated thus requiring new golden answers to be computed for some
   * tests. Also prevents the classpath being used when looking for golden answers as these are
   * usually stale.
   */
  val recomputeCache = System.getProperty("spark.hive.recomputeCache") != null

  protected val shardRegEx = "(\\d+):(\\d+)".r
  /**
   * Allows multiple JVMs to be run in parallel, each responsible for portion of all test cases.
   * Format `shardId:numShards`. Shard ids should be zero indexed.  E.g. -Dspark.hive.testshard=0:4.
   */
  val shardInfo = Option(System.getProperty("spark.hive.shard")).map {
    case shardRegEx(id, total) => (id.toInt, total.toInt)
  }

  protected val targetDir = new File("target")

  /**
   * When set, this comma separated list is defines directories that contain the names of test cases
   * that should be skipped.
   *
   * For example when `-Dspark.hive.skiptests=passed,hiveFailed` is specified and test cases listed
   * in [[passedDirectory]] or [[hiveFailedDirectory]] will be skipped.
   */
  val skipDirectories =
    Option(System.getProperty("spark.hive.skiptests"))
      .toSeq
      .flatMap(_.split(","))
      .map(name => new File(targetDir, s"$suiteName.$name"))

  val runOnlyDirectories =
    Option(System.getProperty("spark.hive.runonlytests"))
      .toSeq
      .flatMap(_.split(","))
      .map(name => new File(targetDir, s"$suiteName.$name"))

  /** The local directory with cached golden answer will be stored. */
  protected val answerCache = new File("src/test/resources/golden")
  if (!answerCache.exists) {
    answerCache.mkdir()
  }

  /** The [[ClassLoader]] that contains test dependencies.  Used to look for golden answers. */
  protected val testClassLoader = this.getClass.getClassLoader

  /** Directory containing a file for each test case that passes. */
  val passedDirectory = new File(targetDir, s"$suiteName.passed")
  if (!passedDirectory.exists()) {
    passedDirectory.mkdir() // Not atomic!
  }

  /** Directory containing output of tests that fail to execute with Catalyst. */
  val failedDirectory = new File(targetDir, s"$suiteName.failed")
  if (!failedDirectory.exists()) {
    failedDirectory.mkdir() // Not atomic!
  }

  /** Directory containing output of tests where catalyst produces the wrong answer. */
  val wrongDirectory = new File(targetDir, s"$suiteName.wrong")
  if (!wrongDirectory.exists()) {
    wrongDirectory.mkdir() // Not atomic!
  }

  /** Directory containing output of tests where we fail to generate golden output with Hive. */
  val hiveFailedDirectory = new File(targetDir, s"$suiteName.hiveFailed")
  if (!hiveFailedDirectory.exists()) {
    hiveFailedDirectory.mkdir() // Not atomic!
  }

  /** All directories that contain per-query output files */
  val outputDirectories = Seq(
    passedDirectory,
    failedDirectory,
    wrongDirectory,
    hiveFailedDirectory)

  protected val cacheDigest = java.security.MessageDigest.getInstance("MD5")
  protected def getMd5(str: String): String = {
    val digest = java.security.MessageDigest.getInstance("MD5")
    digest.update(str.getBytes)
    new java.math.BigInteger(1, digest.digest).toString(16)
  }

  protected def prepareAnswer(
    hiveQuery: TestHive.type#SqlQueryExecution,
    answer: Seq[String]): Seq[String] = {
    val orderedAnswer = hiveQuery.logical match {
      // Clean out non-deterministic time schema info.
      case _: NativeCommand => answer.filterNot(nonDeterministicLine).filterNot(_ == "")
      case _: ExplainCommand => answer
      case _ =>
        // TODO: Really we only care about the final total ordering here...
        val isOrdered = hiveQuery.executedPlan.collect {
          case s @ Sort(_, global, _) if global => s
        }.nonEmpty
        // If the query results aren't sorted, then sort them to ensure deterministic answers.
        if (!isOrdered) answer.sorted else answer
    }
    orderedAnswer.map(cleanPaths)
  }

  // TODO: Instead of filtering we should clean to avoid accidentally ignoring actual results.
  lazy val nonDeterministicLineIndicators = Seq(
    "CreateTime",
    "transient_lastDdlTime",
    "grantTime",
    "lastUpdateTime",
    "last_modified_time",
    "Owner:",
    // The following are hive specific schema parameters which we do not need to match exactly.
    "numFiles",
    "numRows",
    "rawDataSize",
    "totalSize",
    "totalNumberFiles",
    "maxFileSize",
    "minFileSize"
  )
  protected def nonDeterministicLine(line: String) =
    nonDeterministicLineIndicators.map(line contains _).reduceLeft(_||_)

  /**
   * Removes non-deterministic paths from `str` so cached answers will compare correctly.
   */
  protected def cleanPaths(str: String): String = {
    str.replaceAll("file:\\/.*\\/", "<PATH>")
  }

  val installHooksCommand = "(?i)SET.*hooks".r
  def createQueryTest(testCaseName: String, sql: String, reset: Boolean = true) {
    // If test sharding is enable, skip tests that are not in the correct shard.
    shardInfo.foreach {
      case (shardId, numShards) if testCaseName.hashCode % numShards != shardId => return
      case (shardId, _) => logger.debug(s"Shard $shardId includes test '$testCaseName'")
    }

    // Skip tests found in directories specified by user.
    skipDirectories
      .map(new File(_, testCaseName))
      .filter(_.exists)
      .foreach(_ => return)

    // If runonlytests is set, skip this test unless we find a file in one of the specified
    // directories.
    val runIndicators =
      runOnlyDirectories
        .map(new File(_, testCaseName))
        .filter(_.exists)
    if (runOnlyDirectories.nonEmpty && runIndicators.isEmpty) {
      logger.debug(
        s"Skipping test '$testCaseName' not found in ${runOnlyDirectories.map(_.getCanonicalPath)}")
      return
    }

    test(testCaseName) {
      logger.debug(s"=== HIVE TEST: $testCaseName ===")

      // Clear old output for this testcase.
      outputDirectories.map(new File(_, testCaseName)).filter(_.exists()).foreach(_.delete())

      val allQueries = sql.split("(?<=[^\\\\]);").map(_.trim).filterNot(q => q == "").toSeq

      // TODO: DOCUMENT UNSUPPORTED
      val queryList =
        allQueries
          // In hive, setting the hive.outerjoin.supports.filters flag to "false" essentially tells
          // the system to return the wrong answer.  Since we have no intention of mirroring their
          // previously broken behavior we simply filter out changes to this setting.
          .filterNot(_ contains "hive.outerjoin.supports.filters")

      if (allQueries != queryList)
        logger.warn(s"Simplifications made on unsupported operations for test $testCaseName")

      lazy val consoleTestCase = {
        val quotes = "\"\"\""
        queryList.zipWithIndex.map {
          case (query, i) =>
            s"""
              |val q$i = $quotes$query$quotes.q
              |q$i.stringResult()
            """.stripMargin
        }.mkString("\n== Console version of this test ==\n", "\n", "\n")
      }

      try {
        // MINOR HACK: You must run a query before calling reset the first time.
        TestHive.sql("SHOW TABLES")
        if (reset) { TestHive.reset() }

        val hiveCacheFiles = queryList.zipWithIndex.map {
          case (queryString, i)  =>
            val cachedAnswerName = s"$testCaseName-$i-${getMd5(queryString)}"
            new File(answerCache, cachedAnswerName)
        }

        val hiveCachedResults = hiveCacheFiles.flatMap { cachedAnswerFile =>
          logger.debug(s"Looking for cached answer file $cachedAnswerFile.")
          if (cachedAnswerFile.exists) {
            Some(fileToString(cachedAnswerFile))
          } else {
            logger.debug(s"File $cachedAnswerFile not found")
            None
          }
        }.map {
          case "" => Nil
          case "\n" => Seq("")
          case other => other.split("\n").toSeq
        }

        val hiveResults: Seq[Seq[String]] =
          if (hiveCachedResults.size == queryList.size) {
            logger.info(s"Using answer cache for test: $testCaseName")
            hiveCachedResults
          } else {

            val hiveQueries = queryList.map(new TestHive.SqlQueryExecution(_))
            // Make sure we can at least parse everything before attempting hive execution.
            hiveQueries.foreach(_.logical)
            val computedResults = (queryList.zipWithIndex, hiveQueries, hiveCacheFiles).zipped.map {
              case ((queryString, i), hiveQuery, cachedAnswerFile)=>
                try {
                  // Hooks often break the harness and don't really affect our test anyway, don't
                  // even try running them.
                  if (installHooksCommand.findAllMatchIn(queryString).nonEmpty)
                    sys.error("hive exec hooks not supported for tests.")

                  logger.warn(s"Running query ${i+1}/${queryList.size} with hive.")
                  // Analyze the query with catalyst to ensure test tables are loaded.
                  val answer = hiveQuery.analyzed match {
                    case _: ExplainCommand => Nil // No need to execute EXPLAIN queries as we don't check the output.
                    case _ => TestHive.runSqlHive(queryString)
                  }

                  // We need to add a new line to non-empty answers so we can differentiate Seq()
                  // from Seq("").
                  stringToFile(
                    cachedAnswerFile, answer.mkString("\n") + (if (answer.nonEmpty) "\n" else ""))
                  answer
                } catch {
                  case e: Exception =>
                    val errorMessage =
                      s"""
                        |Failed to generate golden answer for query:
                        |Error: ${e.getMessage}
                        |${stackTraceToString(e)}
                        |$queryString
                        |$consoleTestCase
                      """.stripMargin
                    stringToFile(
                      new File(hiveFailedDirectory, testCaseName),
                      errorMessage + consoleTestCase)
                    fail(errorMessage)
                }
            }.toSeq
            if (reset) { TestHive.reset() }

            computedResults
          }

        // Run w/ catalyst
        val catalystResults = queryList.zip(hiveResults).map { case (queryString, hive) =>
          val query = new TestHive.SqlQueryExecution(queryString)
          try { (query, prepareAnswer(query, query.stringResult())) } catch {
            case e: Exception =>
              val errorMessage =
                s"""
                  |Failed to execute query using catalyst:
                  |Error: ${e.getMessage}
                  |${stackTraceToString(e)}
                  |$query
                  |== HIVE - ${hive.size} row(s) ==
                  |${hive.mkString("\n")}
                  |
                  |$consoleTestCase
                """.stripMargin
              stringToFile(new File(failedDirectory, testCaseName), errorMessage + consoleTestCase)
              fail(errorMessage)
          }
        }.toSeq

        (queryList, hiveResults, catalystResults).zipped.foreach {
          case (query, hive, (hiveQuery, catalyst)) =>
            // Check that the results match unless its an EXPLAIN query.
            val preparedHive = prepareAnswer(hiveQuery,hive)

            if ((!hiveQuery.logical.isInstanceOf[ExplainCommand]) && preparedHive != catalyst) {

              val hivePrintOut = s"== HIVE - ${hive.size} row(s) ==" +: preparedHive
              val catalystPrintOut = s"== CATALYST - ${catalyst.size} row(s) ==" +: catalyst

              val resultComparison = sideBySide(hivePrintOut, catalystPrintOut).mkString("\n")

              if (recomputeCache) {
                logger.warn(s"Clearing cache files for failed test $testCaseName")
                hiveCacheFiles.foreach(_.delete())
              }

              val errorMessage =
                s"""
                  |Results do not match for $testCaseName:
                  |$hiveQuery\n${hiveQuery.analyzed.output.map(_.name).mkString("\t")}
                  |$resultComparison
                """.stripMargin

              stringToFile(new File(wrongDirectory, testCaseName), errorMessage + consoleTestCase)
              fail(errorMessage)
            }
        }

        // Touch passed file.
        new FileOutputStream(new File(passedDirectory, testCaseName)).close()
      } catch {
        case tf: org.scalatest.exceptions.TestFailedException => throw tf
        case originalException: Exception =>
          if (System.getProperty("spark.hive.canarytest") != null) {
            // When we encounter an error we check to see if the environment is still okay by running a simple query.
            // If this fails then we halt testing since something must have gone seriously wrong.
            try {
              new TestHive.SqlQueryExecution("SELECT key FROM src").stringResult()
              TestHive.runSqlHive("SELECT key FROM src")
            } catch {
              case e: Exception =>
                logger.error(s"FATAL ERROR: Canary query threw $e This implies that the testing environment has likely been corrupted.")
                // The testing setup traps exits so wait here for a long time so the developer can see when things started
                // to go wrong.
                Thread.sleep(1000000)
            }
          }

          // If the canary query didn't fail then the environment is still okay, so just throw the original exception.
          throw originalException
      }
    }
  }
}
