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

package org.apache.spark.examples.mllib

import scopt.OptionParser

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.{RandomForest, DecisionTree, impurity}
import org.apache.spark.mllib.tree.configuration.{Algo, Strategy}
import org.apache.spark.mllib.tree.configuration.Algo._
import org.apache.spark.mllib.tree.model.{WeightedEnsembleModel, DecisionTreeModel}
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.util.Utils

/**
 * An example runner for decision trees and random forests. Run with
 * {{{
 * ./bin/run-example org.apache.spark.examples.mllib.DecisionTreeRunner [options]
 * }}}
 * If you use it as a template to create your own app, please use `spark-submit` to submit your app.
 *
 * Note: This script treats all features as real-valued (not categorical).
 *       To include categorical features, modify categoricalFeaturesInfo.
 */
object DecisionTreeRunner {

  object ImpurityType extends Enumeration {
    type ImpurityType = Value
    val Gini, Entropy, Variance = Value
  }

  import ImpurityType._

  case class Params(
      input: String = null,
      testInput: String = "",
      dataFormat: String = "libsvm",
      algo: Algo = Classification,
      maxDepth: Int = 5,
      impurity: ImpurityType = Gini,
      maxBins: Int = 32,
      minInstancesPerNode: Int = 1,
      minInfoGain: Double = 0.0,
      numTrees: Int = 1,
      featureSubsetStrategy: String = "auto",
      fracTest: Double = 0.2) extends AbstractParams[Params]

  def main(args: Array[String]) {
    val defaultParams = Params()

    val parser = new OptionParser[Params]("DecisionTreeRunner") {
      head("DecisionTreeRunner: an example decision tree app.")
      opt[String]("algo")
        .text(s"algorithm (${Algo.values.mkString(",")}), default: ${defaultParams.algo}")
        .action((x, c) => c.copy(algo = Algo.withName(x)))
      opt[String]("impurity")
        .text(s"impurity type (${ImpurityType.values.mkString(",")}), " +
          s"default: ${defaultParams.impurity}")
        .action((x, c) => c.copy(impurity = ImpurityType.withName(x)))
      opt[Int]("maxDepth")
        .text(s"max depth of the tree, default: ${defaultParams.maxDepth}")
        .action((x, c) => c.copy(maxDepth = x))
      opt[Int]("maxBins")
        .text(s"max number of bins, default: ${defaultParams.maxBins}")
        .action((x, c) => c.copy(maxBins = x))
      opt[Int]("minInstancesPerNode")
        .text(s"min number of instances required at child nodes to create the parent split," +
          s" default: ${defaultParams.minInstancesPerNode}")
        .action((x, c) => c.copy(minInstancesPerNode = x))
      opt[Double]("minInfoGain")
        .text(s"min info gain required to create a split, default: ${defaultParams.minInfoGain}")
        .action((x, c) => c.copy(minInfoGain = x))
      opt[Int]("numTrees")
        .text(s"number of trees (1 = decision tree, 2+ = random forest)," +
          s" default: ${defaultParams.numTrees}")
        .action((x, c) => c.copy(numTrees = x))
      opt[String]("featureSubsetStrategy")
        .text(s"feature subset sampling strategy" +
          s" (${RandomForest.supportedFeatureSubsetStrategies.mkString(", ")}}), " +
          s"default: ${defaultParams.featureSubsetStrategy}")
        .action((x, c) => c.copy(featureSubsetStrategy = x))
      opt[Double]("fracTest")
        .text(s"fraction of data to hold out for testing.  If given option testInput, " +
          s"this option is ignored. default: ${defaultParams.fracTest}")
        .action((x, c) => c.copy(fracTest = x))
      opt[String]("testInput")
        .text(s"input path to test dataset.  If given, option fracTest is ignored." +
          s" default: ${defaultParams.testInput}")
        .action((x, c) => c.copy(testInput = x))
      opt[String]("<dataFormat>")
        .text("data format: libsvm (default), dense (deprecated in Spark v1.1)")
        .action((x, c) => c.copy(dataFormat = x))
      arg[String]("<input>")
        .text("input path to labeled examples")
        .required()
        .action((x, c) => c.copy(input = x))
      checkConfig { params =>
        if (params.fracTest < 0 || params.fracTest > 1) {
          failure(s"fracTest ${params.fracTest} value incorrect; should be in [0,1].")
        } else {
          if (params.algo == Classification &&
            (params.impurity == Gini || params.impurity == Entropy)) {
            success
          } else if (params.algo == Regression && params.impurity == Variance) {
            success
          } else {
            failure(s"Algo ${params.algo} is not compatible with impurity ${params.impurity}.")
          }
        }
      }
    }

    parser.parse(args, defaultParams).map { params =>
      run(params)
    }.getOrElse {
      sys.exit(1)
    }
  }

  def run(params: Params) {

    val conf = new SparkConf().setAppName(s"DecisionTreeRunner with $params")
    val sc = new SparkContext(conf)

    println(s"DecisionTreeRunner with parameters:\n$params")

    // Load training data and cache it.
    val origExamples = params.dataFormat match {
      case "dense" => MLUtils.loadLabeledPoints(sc, params.input).cache()
      case "libsvm" => MLUtils.loadLibSVMFile(sc, params.input).cache()
    }
    // For classification, re-index classes if needed.
    val (examples, classIndexMap, numClasses) = params.algo match {
      case Classification => {
        // classCounts: class --> # examples in class
        val classCounts = origExamples.map(_.label).countByValue()
        val sortedClasses = classCounts.keys.toList.sorted
        val numClasses = classCounts.size
        // classIndexMap: class --> index in 0,...,numClasses-1
        val classIndexMap = {
          if (classCounts.keySet != Set(0.0, 1.0)) {
            sortedClasses.zipWithIndex.toMap
          } else {
            Map[Double, Int]()
          }
        }
        val examples = {
          if (classIndexMap.isEmpty) {
            origExamples
          } else {
            origExamples.map(lp => LabeledPoint(classIndexMap(lp.label), lp.features))
          }
        }
        val numExamples = examples.count()
        println(s"numClasses = $numClasses.")
        println(s"Per-class example fractions, counts:")
        println(s"Class\tFrac\tCount")
        sortedClasses.foreach { c =>
          val frac = classCounts(c) / numExamples.toDouble
          println(s"$c\t$frac\t${classCounts(c)}")
        }
        (examples, classIndexMap, numClasses)
      }
      case Regression =>
        (origExamples, null, 0)
      case _ =>
        throw new IllegalArgumentException("Algo ${params.algo} not supported.")
    }

    // Create training, test sets.
    val splits = if (params.testInput != "") {
      // Load testInput.
      val numFeatures = examples.take(1)(0).features.size
      val origTestExamples = params.dataFormat match {
        case "dense" => MLUtils.loadLabeledPoints(sc, params.testInput)
        case "libsvm" => MLUtils.loadLibSVMFile(sc, params.testInput, numFeatures)
      }
      params.algo match {
        case Classification => {
          // classCounts: class --> # examples in class
          val testExamples = {
            if (classIndexMap.isEmpty) {
              origTestExamples
            } else {
              origTestExamples.map(lp => LabeledPoint(classIndexMap(lp.label), lp.features))
            }
          }
          Array(examples, testExamples)
        }
        case Regression =>
          Array(examples, origTestExamples)
      }
    } else {
      // Split input into training, test.
      examples.randomSplit(Array(1.0 - params.fracTest, params.fracTest))
    }
    val training = splits(0).cache()
    val test = splits(1).cache()
    val numTraining = training.count()
    val numTest = test.count()

    println(s"numTraining = $numTraining, numTest = $numTest.")

    examples.unpersist(blocking = false)

    val impurityCalculator = params.impurity match {
      case Gini => impurity.Gini
      case Entropy => impurity.Entropy
      case Variance => impurity.Variance
    }

    val strategy
      = new Strategy(
          algo = params.algo,
          impurity = impurityCalculator,
          maxDepth = params.maxDepth,
          maxBins = params.maxBins,
          numClassesForClassification = numClasses,
          minInstancesPerNode = params.minInstancesPerNode,
          minInfoGain = params.minInfoGain)
    if (params.numTrees == 1) {
      val startTime = System.nanoTime()
      val model = DecisionTree.train(training, strategy)
      val elapsedTime = (System.nanoTime() - startTime) / 1e9
      println(s"Training time: $elapsedTime seconds")
      if (model.numNodes < 20) {
        println(model.toDebugString) // Print full model.
      } else {
        println(model) // Print model summary.
      }
      if (params.algo == Classification) {
        val trainAccuracy =
          new MulticlassMetrics(training.map(lp => (model.predict(lp.features), lp.label)))
            .precision
        println(s"Train accuracy = $trainAccuracy")
        val testAccuracy =
          new MulticlassMetrics(test.map(lp => (model.predict(lp.features), lp.label))).precision
        println(s"Test accuracy = $testAccuracy")
      }
      if (params.algo == Regression) {
        val trainMSE = meanSquaredError(model, training)
        println(s"Train mean squared error = $trainMSE")
        val testMSE = meanSquaredError(model, test)
        println(s"Test mean squared error = $testMSE")
      }
    } else {
      val randomSeed = Utils.random.nextInt()
      if (params.algo == Classification) {
        val startTime = System.nanoTime()
        val model = RandomForest.trainClassifier(training, strategy, params.numTrees,
          params.featureSubsetStrategy, randomSeed)
        val elapsedTime = (System.nanoTime() - startTime) / 1e9
        println(s"Training time: $elapsedTime seconds")
        if (model.totalNumNodes < 30) {
          println(model.toDebugString) // Print full model.
        } else {
          println(model) // Print model summary.
        }
        val trainAccuracy =
          new MulticlassMetrics(training.map(lp => (model.predict(lp.features), lp.label)))
            .precision
        println(s"Train accuracy = $trainAccuracy")
        val testAccuracy =
          new MulticlassMetrics(test.map(lp => (model.predict(lp.features), lp.label))).precision
        println(s"Test accuracy = $testAccuracy")
      }
      if (params.algo == Regression) {
        val startTime = System.nanoTime()
        val model = RandomForest.trainRegressor(training, strategy, params.numTrees,
          params.featureSubsetStrategy, randomSeed)
        val elapsedTime = (System.nanoTime() - startTime) / 1e9
        println(s"Training time: $elapsedTime seconds")
        if (model.totalNumNodes < 30) {
          println(model.toDebugString) // Print full model.
        } else {
          println(model) // Print model summary.
        }
        val trainMSE = meanSquaredError(model, training)
        println(s"Train mean squared error = $trainMSE")
        val testMSE = meanSquaredError(model, test)
        println(s"Test mean squared error = $testMSE")
      }
    }

    sc.stop()
  }

  /**
   * Calculates the mean squared error for regression.
   */
  private def meanSquaredError(tree: DecisionTreeModel, data: RDD[LabeledPoint]): Double = {
    data.map { y =>
      val err = tree.predict(y.features) - y.label
      err * err
    }.mean()
  }

  /**
   * Calculates the mean squared error for regression.
   */
  private def meanSquaredError(tree: WeightedEnsembleModel, data: RDD[LabeledPoint]): Double = {
    data.map { y =>
      val err = tree.predict(y.features) - y.label
      err * err
    }.mean()
  }
}
