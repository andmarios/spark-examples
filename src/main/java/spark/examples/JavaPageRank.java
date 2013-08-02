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

package spark.examples;

import scala.Tuple2;
import spark.api.java.JavaPairRDD;
import spark.api.java.JavaRDD;
import spark.api.java.JavaSparkContext;
import spark.api.java.function.FlatMapFunction;
import spark.api.java.function.Function;
import spark.api.java.function.PairFunction;

import java.util.List;
import java.util.ArrayList;

/**
 * Computes the PageRank of URLs from an input file. Input file should
 * be in format of:
 * URL         neighbor URL
 * URL         neighbor URL
 * URL         neighbor URL
 * ...
 * where URL and their neighbors are separated by space(s).
 */
public class JavaPageRank {
  private static double sum(List<Double> numbers) {
    double out = 0.0;
    for (double number : numbers) {
      out += number;
    }
    return out;
  }

  public static double round(double value, int places) {
    if (places < 0) throw new IllegalArgumentException();

    long factor = (long) Math.pow(10, places);
    value = value * factor;
    long tmp = Math.round(value);
    return (double) tmp / factor;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println("Usage: JavaPageRank <master> <file> <number_of_iterations>");
      System.exit(1);
    }

  JavaSparkContext ctx = new JavaSparkContext(args[0], "JavaPageRank",
    System.getenv("SPARK_HOME"), System.getenv("SPARK_EXAMPLES_JAR"));

  // Loads in input file. It should be in format of:
  //     URL         neighbor URL
  //     URL         neighbor URL
  //     URL         neighbor URL
  //     ...
  JavaRDD<String> lines = ctx.textFile(args[1], 1).cache();

  // Loads all URLs from input file and initialize their neighbors.
  JavaPairRDD<String, List<String>> links = lines.map(new PairFunction<String, String, String>() {
    @Override
    public Tuple2<String, String> call(String s) {
      return new Tuple2<String, String>(s.split("\\s+")[0], s.split("\\s+")[1]);
    }
  }).distinct().groupByKey();

  // Loads all URLs with other URL(s) link to from input file and initialize ranks of them to one.
  JavaPairRDD<String, Double> ranks = lines.map(new PairFunction<String, String, Double>() {
    @Override
    public Tuple2<String, Double> call(String s) {
      return new Tuple2<String, Double>(s.split("\\s+")[1], 1.0);
    }
  }).distinct();


  // Calculates and updates URL ranks continuously using PageRank algorithm.
  for (int current = 0; current < Integer.parseInt(args[2]); current++) {
    // Calculates URL contributions to the rank of other URLs.
    JavaPairRDD<String, Double> contribs = links.join(ranks).values()
      .flatMap(new FlatMapFunction<Tuple2<List<String>, Double>, Tuple2<String, Double>>() {
        @Override
        public Iterable<Tuple2<String, Double>> call(Tuple2<List<String>, Double> s) {
          List<Tuple2<String, Double>> results = new ArrayList<Tuple2<String, Double>>();
          for (String n : s._1) {
            results.add(new Tuple2<String, Double>(n, s._2 / s._1.size()));
          }

          return results;
        }
      }).map(new PairFunction<Tuple2<String, Double>, String, Double>() {
        @Override
        public Tuple2<String, Double> call(Tuple2<String, Double> s) {
          return s;
        }
      });

      // Re-calculates URL ranks based on neighbor contributions.
      ranks = contribs.groupByKey().mapValues(new Function<List<Double>, Double>() {
        @Override
        public Double call(List<Double> cs) throws Exception {
          return 0.15 + sum(cs) * 0.85;
        }
        });
      }

      // Collects all URL ranks and dump them to console.
      List<Tuple2<String, Double>> output = ranks.collect();
      for (Tuple2 tuple : output) {
          System.out.println(tuple._1 + " has rank: " + round((Double)(tuple._2), 2) + ".");
      }

      System.exit(0);
  }
}
