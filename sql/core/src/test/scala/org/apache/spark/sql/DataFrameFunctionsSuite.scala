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

package org.apache.spark.sql

import java.nio.charset.StandardCharsets

import scala.util.Random

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.functions._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types._

/**
 * Test suite for functions in [[org.apache.spark.sql.functions]].
 */
class DataFrameFunctionsSuite extends QueryTest with SharedSQLContext {
  import testImplicits._

  test("array with column name") {
    val df = Seq((0, 1)).toDF("a", "b")
    val row = df.select(array("a", "b")).first()

    val expectedType = ArrayType(IntegerType, containsNull = false)
    assert(row.schema(0).dataType === expectedType)
    assert(row.getAs[Seq[Int]](0) === Seq(0, 1))
  }

  test("array with column expression") {
    val df = Seq((0, 1)).toDF("a", "b")
    val row = df.select(array(col("a"), col("b") + col("b"))).first()

    val expectedType = ArrayType(IntegerType, containsNull = false)
    assert(row.schema(0).dataType === expectedType)
    assert(row.getSeq[Int](0) === Seq(0, 2))
  }

  test("map with column expressions") {
    val df = Seq(1 -> "a").toDF("a", "b")
    val row = df.select(map($"a" + 1, $"b")).first()

    val expectedType = MapType(IntegerType, StringType, valueContainsNull = true)
    assert(row.schema(0).dataType === expectedType)
    assert(row.getMap[Int, String](0) === Map(2 -> "a"))
  }

  test("map with arrays") {
    val df1 = Seq((Seq(1, 2), Seq("a", "b"))).toDF("k", "v")
    val expectedType = MapType(IntegerType, StringType, valueContainsNull = true)
    val row = df1.select(map_from_arrays($"k", $"v")).first()
    assert(row.schema(0).dataType === expectedType)
    assert(row.getMap[Int, String](0) === Map(1 -> "a", 2 -> "b"))
    checkAnswer(df1.select(map_from_arrays($"k", $"v")), Seq(Row(Map(1 -> "a", 2 -> "b"))))

    val df2 = Seq((Seq(1, 2), Seq(null, "b"))).toDF("k", "v")
    checkAnswer(df2.select(map_from_arrays($"k", $"v")), Seq(Row(Map(1 -> null, 2 -> "b"))))

    val df3 = Seq((null, null)).toDF("k", "v")
    checkAnswer(df3.select(map_from_arrays($"k", $"v")), Seq(Row(null)))

    val df4 = Seq((1, "a")).toDF("k", "v")
    intercept[AnalysisException] {
      df4.select(map_from_arrays($"k", $"v"))
    }

    val df5 = Seq((Seq("a", null), Seq(1, 2))).toDF("k", "v")
    intercept[RuntimeException] {
      df5.select(map_from_arrays($"k", $"v")).collect
    }

    val df6 = Seq((Seq(1, 2), Seq("a"))).toDF("k", "v")
    intercept[RuntimeException] {
      df6.select(map_from_arrays($"k", $"v")).collect
    }
  }

  test("struct with column name") {
    val df = Seq((1, "str")).toDF("a", "b")
    val row = df.select(struct("a", "b")).first()

    val expectedType = StructType(Seq(
      StructField("a", IntegerType, nullable = false),
      StructField("b", StringType)
    ))
    assert(row.schema(0).dataType === expectedType)
    assert(row.getAs[Row](0) === Row(1, "str"))
  }

  test("struct with column expression") {
    val df = Seq((1, "str")).toDF("a", "b")
    val row = df.select(struct((col("a") * 2).as("c"), col("b"))).first()

    val expectedType = StructType(Seq(
      StructField("c", IntegerType, nullable = false),
      StructField("b", StringType)
    ))
    assert(row.schema(0).dataType === expectedType)
    assert(row.getAs[Row](0) === Row(2, "str"))
  }

  test("struct with column expression to be automatically named") {
    val df = Seq((1, "str")).toDF("a", "b")
    val result = df.select(struct((col("a") * 2), col("b")))

    val expectedType = StructType(Seq(
      StructField("col1", IntegerType, nullable = false),
      StructField("b", StringType)
    ))
    assert(result.first.schema(0).dataType === expectedType)
    checkAnswer(result, Row(Row(2, "str")))
  }

  test("struct with literal columns") {
    val df = Seq((1, "str1"), (2, "str2")).toDF("a", "b")
    val result = df.select(struct((col("a") * 2), lit(5.0)))

    val expectedType = StructType(Seq(
      StructField("col1", IntegerType, nullable = false),
      StructField("col2", DoubleType, nullable = false)
    ))

    assert(result.first.schema(0).dataType === expectedType)
    checkAnswer(result, Seq(Row(Row(2, 5.0)), Row(Row(4, 5.0))))
  }

  test("struct with all literal columns") {
    val df = Seq((1, "str1"), (2, "str2")).toDF("a", "b")
    val result = df.select(struct(lit("v"), lit(5.0)))

    val expectedType = StructType(Seq(
      StructField("col1", StringType, nullable = false),
      StructField("col2", DoubleType, nullable = false)
    ))

    assert(result.first.schema(0).dataType === expectedType)
    checkAnswer(result, Seq(Row(Row("v", 5.0)), Row(Row("v", 5.0))))
  }

  test("constant functions") {
    checkAnswer(
      sql("SELECT E()"),
      Row(scala.math.E)
    )
    checkAnswer(
      sql("SELECT PI()"),
      Row(scala.math.Pi)
    )
  }

  test("bitwiseNOT") {
    checkAnswer(
      testData2.select(bitwiseNOT($"a")),
      testData2.collect().toSeq.map(r => Row(~r.getInt(0))))
  }

  test("bin") {
    val df = Seq[(Integer, Integer)]((12, null)).toDF("a", "b")
    checkAnswer(
      df.select(bin("a"), bin("b")),
      Row("1100", null))
    checkAnswer(
      df.selectExpr("bin(a)", "bin(b)"),
      Row("1100", null))
  }

  test("if function") {
    val df = Seq((1, 2)).toDF("a", "b")
    checkAnswer(
      df.selectExpr("if(a = 1, 'one', 'not_one')", "if(b = 1, 'one', 'not_one')"),
      Row("one", "not_one"))
  }

  test("misc md5 function") {
    val df = Seq(("ABC", Array[Byte](1, 2, 3, 4, 5, 6))).toDF("a", "b")
    checkAnswer(
      df.select(md5($"a"), md5($"b")),
      Row("902fbdd2b1df0c4f70b4a5d23525e932", "6ac1e56bc78f031059be7be854522c4c"))

    checkAnswer(
      df.selectExpr("md5(a)", "md5(b)"),
      Row("902fbdd2b1df0c4f70b4a5d23525e932", "6ac1e56bc78f031059be7be854522c4c"))
  }

  test("misc sha1 function") {
    val df = Seq(("ABC", "ABC".getBytes(StandardCharsets.UTF_8))).toDF("a", "b")
    checkAnswer(
      df.select(sha1($"a"), sha1($"b")),
      Row("3c01bdbb26f358bab27f267924aa2c9a03fcfdb8", "3c01bdbb26f358bab27f267924aa2c9a03fcfdb8"))

    val dfEmpty = Seq(("", "".getBytes(StandardCharsets.UTF_8))).toDF("a", "b")
    checkAnswer(
      dfEmpty.selectExpr("sha1(a)", "sha1(b)"),
      Row("da39a3ee5e6b4b0d3255bfef95601890afd80709", "da39a3ee5e6b4b0d3255bfef95601890afd80709"))
  }

  test("misc sha2 function") {
    val df = Seq(("ABC", Array[Byte](1, 2, 3, 4, 5, 6))).toDF("a", "b")
    checkAnswer(
      df.select(sha2($"a", 256), sha2($"b", 256)),
      Row("b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78",
        "7192385c3c0605de55bb9476ce1d90748190ecb32a8eed7f5207b30cf6a1fe89"))

    checkAnswer(
      df.selectExpr("sha2(a, 256)", "sha2(b, 256)"),
      Row("b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78",
        "7192385c3c0605de55bb9476ce1d90748190ecb32a8eed7f5207b30cf6a1fe89"))

    intercept[IllegalArgumentException] {
      df.select(sha2($"a", 1024))
    }
  }

  test("misc crc32 function") {
    val df = Seq(("ABC", Array[Byte](1, 2, 3, 4, 5, 6))).toDF("a", "b")
    checkAnswer(
      df.select(crc32($"a"), crc32($"b")),
      Row(2743272264L, 2180413220L))

    checkAnswer(
      df.selectExpr("crc32(a)", "crc32(b)"),
      Row(2743272264L, 2180413220L))
  }

  test("string function find_in_set") {
    val df = Seq(("abc,b,ab,c,def", "abc,b,ab,c,def")).toDF("a", "b")

    checkAnswer(
      df.selectExpr("find_in_set('ab', a)", "find_in_set('x', b)"),
      Row(3, 0))
  }

  test("conditional function: least") {
    checkAnswer(
      testData2.select(least(lit(-1), lit(0), col("a"), col("b"))).limit(1),
      Row(-1)
    )
    checkAnswer(
      sql("SELECT least(a, 2) as l from testData2 order by l"),
      Seq(Row(1), Row(1), Row(2), Row(2), Row(2), Row(2))
    )
  }

  test("conditional function: greatest") {
    checkAnswer(
      testData2.select(greatest(lit(2), lit(3), col("a"), col("b"))).limit(1),
      Row(3)
    )
    checkAnswer(
      sql("SELECT greatest(a, 2) as g from testData2 order by g"),
      Seq(Row(2), Row(2), Row(2), Row(2), Row(3), Row(3))
    )
  }

  test("pmod") {
    val intData = Seq((7, 3), (-7, 3)).toDF("a", "b")
    checkAnswer(
      intData.select(pmod('a, 'b)),
      Seq(Row(1), Row(2))
    )
    checkAnswer(
      intData.select(pmod('a, lit(3))),
      Seq(Row(1), Row(2))
    )
    checkAnswer(
      intData.select(pmod(lit(-7), 'b)),
      Seq(Row(2), Row(2))
    )
    checkAnswer(
      intData.selectExpr("pmod(a, b)"),
      Seq(Row(1), Row(2))
    )
    checkAnswer(
      intData.selectExpr("pmod(a, 3)"),
      Seq(Row(1), Row(2))
    )
    checkAnswer(
      intData.selectExpr("pmod(-7, b)"),
      Seq(Row(2), Row(2))
    )
    val doubleData = Seq((7.2, 4.1)).toDF("a", "b")
    checkAnswer(
      doubleData.select(pmod('a, 'b)),
      Seq(Row(3.1000000000000005)) // same as hive
    )
    checkAnswer(
      doubleData.select(pmod(lit(2), lit(Int.MaxValue))),
      Seq(Row(2))
    )
  }

  test("mask functions") {
    val df = Seq("TestString-123", "", null).toDF("a")
    checkAnswer(df.select(mask($"a")), Seq(Row("XxxxXxxxxx-nnn"), Row(""), Row(null)))
    checkAnswer(df.select(mask_first_n($"a", 4)), Seq(Row("XxxxString-123"), Row(""), Row(null)))
    checkAnswer(df.select(mask_last_n($"a", 4)), Seq(Row("TestString-nnn"), Row(""), Row(null)))
    checkAnswer(df.select(mask_show_first_n($"a", 4)),
      Seq(Row("TestXxxxxx-nnn"), Row(""), Row(null)))
    checkAnswer(df.select(mask_show_last_n($"a", 4)),
      Seq(Row("XxxxXxxxxx-123"), Row(""), Row(null)))
    checkAnswer(df.select(mask_hash($"a")),
      Seq(Row("dd78d68ad1b23bde126812482dd70ac6"),
        Row("d41d8cd98f00b204e9800998ecf8427e"),
        Row(null)))

    checkAnswer(df.select(mask($"a", "U", "l", "#")),
      Seq(Row("UlllUlllll-###"), Row(""), Row(null)))
    checkAnswer(df.select(mask_first_n($"a", 4, "U", "l", "#")),
      Seq(Row("UlllString-123"), Row(""), Row(null)))
    checkAnswer(df.select(mask_last_n($"a", 4, "U", "l", "#")),
      Seq(Row("TestString-###"), Row(""), Row(null)))
    checkAnswer(df.select(mask_show_first_n($"a", 4, "U", "l", "#")),
      Seq(Row("TestUlllll-###"), Row(""), Row(null)))
    checkAnswer(df.select(mask_show_last_n($"a", 4, "U", "l", "#")),
      Seq(Row("UlllUlllll-123"), Row(""), Row(null)))

    checkAnswer(
      df.selectExpr("mask(a)", "mask(a, 'U')", "mask(a, 'U', 'l')", "mask(a, 'U', 'l', '#')"),
      Seq(Row("XxxxXxxxxx-nnn", "UxxxUxxxxx-nnn", "UlllUlllll-nnn", "UlllUlllll-###"),
        Row("", "", "", ""),
        Row(null, null, null, null)))
    checkAnswer(sql("select mask(null)"), Row(null))
    checkAnswer(sql("select mask('AAaa11', null, null, null)"), Row("XXxxnn"))
    intercept[AnalysisException] {
      checkAnswer(df.selectExpr("mask(a, a)"), Seq(Row("XxxxXxxxxx-nnn"), Row(""), Row(null)))
    }

    checkAnswer(
      df.selectExpr(
        "mask_first_n(a)",
        "mask_first_n(a, 6)",
        "mask_first_n(a, 6, 'U')",
        "mask_first_n(a, 6, 'U', 'l')",
        "mask_first_n(a, 6, 'U', 'l', '#')"),
      Seq(Row("XxxxString-123", "XxxxXxring-123", "UxxxUxring-123", "UlllUlring-123",
        "UlllUlring-123"),
        Row("", "", "", "", ""),
        Row(null, null, null, null, null)))
    checkAnswer(sql("select mask_first_n(null)"), Row(null))
    checkAnswer(sql("select mask_first_n('A1aA1a', null, null, null, null)"), Row("XnxX1a"))
    intercept[AnalysisException] {
      checkAnswer(spark.range(1).selectExpr("mask_first_n('A1aA1a', id)"), Row("XnxX1a"))
    }

    checkAnswer(
      df.selectExpr(
        "mask_last_n(a)",
        "mask_last_n(a, 6)",
        "mask_last_n(a, 6, 'U')",
        "mask_last_n(a, 6, 'U', 'l')",
        "mask_last_n(a, 6, 'U', 'l', '#')"),
      Seq(Row("TestString-nnn", "TestStrixx-nnn", "TestStrixx-nnn", "TestStrill-nnn",
        "TestStrill-###"),
        Row("", "", "", "", ""),
        Row(null, null, null, null, null)))
    checkAnswer(sql("select mask_last_n(null)"), Row(null))
    checkAnswer(sql("select mask_last_n('A1aA1a', null, null, null, null)"), Row("A1xXnx"))
    intercept[AnalysisException] {
      checkAnswer(spark.range(1).selectExpr("mask_last_n('A1aA1a', id)"), Row("A1xXnx"))
    }

    checkAnswer(
      df.selectExpr(
        "mask_show_first_n(a)",
        "mask_show_first_n(a, 6)",
        "mask_show_first_n(a, 6, 'U')",
        "mask_show_first_n(a, 6, 'U', 'l')",
        "mask_show_first_n(a, 6, 'U', 'l', '#')"),
      Seq(Row("TestXxxxxx-nnn", "TestStxxxx-nnn", "TestStxxxx-nnn", "TestStllll-nnn",
        "TestStllll-###"),
        Row("", "", "", "", ""),
        Row(null, null, null, null, null)))
    checkAnswer(sql("select mask_show_first_n(null)"), Row(null))
    checkAnswer(sql("select mask_show_first_n('A1aA1a', null, null, null, null)"), Row("A1aAnx"))
    intercept[AnalysisException] {
      checkAnswer(spark.range(1).selectExpr("mask_show_first_n('A1aA1a', id)"), Row("A1aAnx"))
    }

    checkAnswer(
      df.selectExpr(
        "mask_show_last_n(a)",
        "mask_show_last_n(a, 6)",
        "mask_show_last_n(a, 6, 'U')",
        "mask_show_last_n(a, 6, 'U', 'l')",
        "mask_show_last_n(a, 6, 'U', 'l', '#')"),
      Seq(Row("XxxxXxxxxx-123", "XxxxXxxxng-123", "UxxxUxxxng-123", "UlllUlllng-123",
        "UlllUlllng-123"),
        Row("", "", "", "", ""),
        Row(null, null, null, null, null)))
    checkAnswer(sql("select mask_show_last_n(null)"), Row(null))
    checkAnswer(sql("select mask_show_last_n('A1aA1a', null, null, null, null)"), Row("XnaA1a"))
    intercept[AnalysisException] {
      checkAnswer(spark.range(1).selectExpr("mask_show_last_n('A1aA1a', id)"), Row("XnaA1a"))
    }

    checkAnswer(sql("select mask_hash(null)"), Row(null))
  }

  test("sort_array/array_sort functions") {
    val df = Seq(
      (Array[Int](2, 1, 3), Array("b", "c", "a")),
      (Array.empty[Int], Array.empty[String]),
      (null, null)
    ).toDF("a", "b")
    checkAnswer(
      df.select(sort_array($"a"), sort_array($"b")),
      Seq(
        Row(Seq(1, 2, 3), Seq("a", "b", "c")),
        Row(Seq.empty[Int], Seq.empty[String]),
        Row(null, null))
    )
    checkAnswer(
      df.select(sort_array($"a", false), sort_array($"b", false)),
      Seq(
        Row(Seq(3, 2, 1), Seq("c", "b", "a")),
        Row(Seq.empty[Int], Seq.empty[String]),
        Row(null, null))
    )
    checkAnswer(
      df.selectExpr("sort_array(a)", "sort_array(b)"),
      Seq(
        Row(Seq(1, 2, 3), Seq("a", "b", "c")),
        Row(Seq.empty[Int], Seq.empty[String]),
        Row(null, null))
    )
    checkAnswer(
      df.selectExpr("sort_array(a, true)", "sort_array(b, false)"),
      Seq(
        Row(Seq(1, 2, 3), Seq("c", "b", "a")),
        Row(Seq.empty[Int], Seq.empty[String]),
        Row(null, null))
    )

    val df2 = Seq((Array[Array[Int]](Array(2), Array(1), Array(2, 4), null), "x")).toDF("a", "b")
    checkAnswer(
      df2.selectExpr("sort_array(a, true)", "sort_array(a, false)"),
      Seq(
        Row(
          Seq[Seq[Int]](null, Seq(1), Seq(2), Seq(2, 4)),
          Seq[Seq[Int]](Seq(2, 4), Seq(2), Seq(1), null)))
    )

    val df3 = Seq(("xxx", "x")).toDF("a", "b")
    assert(intercept[AnalysisException] {
      df3.selectExpr("sort_array(a)").collect()
    }.getMessage().contains("only supports array input"))

    checkAnswer(
      df.select(array_sort($"a"), array_sort($"b")),
      Seq(
        Row(Seq(1, 2, 3), Seq("a", "b", "c")),
        Row(Seq.empty[Int], Seq.empty[String]),
        Row(null, null))
    )
    checkAnswer(
      df.selectExpr("array_sort(a)", "array_sort(b)"),
      Seq(
        Row(Seq(1, 2, 3), Seq("a", "b", "c")),
        Row(Seq.empty[Int], Seq.empty[String]),
        Row(null, null))
    )

    checkAnswer(
      df2.selectExpr("array_sort(a)"),
      Seq(Row(Seq[Seq[Int]](Seq(1), Seq(2), Seq(2, 4), null)))
    )

    assert(intercept[AnalysisException] {
      df3.selectExpr("array_sort(a)").collect()
    }.getMessage().contains("only supports array input"))
  }

  test("array size function") {
    val df = Seq(
      (Seq[Int](1, 2), "x"),
      (Seq[Int](), "y"),
      (Seq[Int](1, 2, 3), "z"),
      (null, "empty")
    ).toDF("a", "b")
    checkAnswer(
      df.select(size($"a")),
      Seq(Row(2), Row(0), Row(3), Row(-1))
    )
    checkAnswer(
      df.selectExpr("size(a)"),
      Seq(Row(2), Row(0), Row(3), Row(-1))
    )

    checkAnswer(
      df.selectExpr("cardinality(a)"),
      Seq(Row(2L), Row(0L), Row(3L), Row(-1L))
    )
  }

  test("dataframe arrays_zip function") {
    val df1 = Seq((Seq(9001, 9002, 9003), Seq(4, 5, 6))).toDF("val1", "val2")
    val df2 = Seq((Seq("a", "b"), Seq(true, false), Seq(10, 11))).toDF("val1", "val2", "val3")
    val df3 = Seq((Seq("a", "b"), Seq(4, 5, 6))).toDF("val1", "val2")
    val df4 = Seq((Seq("a", "b", null), Seq(4L))).toDF("val1", "val2")
    val df5 = Seq((Seq(-1), Seq(null), Seq(), Seq(null, null))).toDF("val1", "val2", "val3", "val4")
    val df6 = Seq((Seq(192.toByte, 256.toByte), Seq(1.1), Seq(), Seq(null, null)))
      .toDF("v1", "v2", "v3", "v4")
    val df7 = Seq((Seq(Seq(1, 2, 3), Seq(4, 5)), Seq(1.1, 2.2))).toDF("v1", "v2")
    val df8 = Seq((Seq(Array[Byte](1.toByte, 5.toByte)), Seq(null))).toDF("v1", "v2")

    val expectedValue1 = Row(Seq(Row(9001, 4), Row(9002, 5), Row(9003, 6)))
    checkAnswer(df1.select(arrays_zip($"val1", $"val2")), expectedValue1)
    checkAnswer(df1.selectExpr("arrays_zip(val1, val2)"), expectedValue1)

    val expectedValue2 = Row(Seq(Row("a", true, 10), Row("b", false, 11)))
    checkAnswer(df2.select(arrays_zip($"val1", $"val2", $"val3")), expectedValue2)
    checkAnswer(df2.selectExpr("arrays_zip(val1, val2, val3)"), expectedValue2)

    val expectedValue3 = Row(Seq(Row("a", 4), Row("b", 5), Row(null, 6)))
    checkAnswer(df3.select(arrays_zip($"val1", $"val2")), expectedValue3)
    checkAnswer(df3.selectExpr("arrays_zip(val1, val2)"), expectedValue3)

    val expectedValue4 = Row(Seq(Row("a", 4L), Row("b", null), Row(null, null)))
    checkAnswer(df4.select(arrays_zip($"val1", $"val2")), expectedValue4)
    checkAnswer(df4.selectExpr("arrays_zip(val1, val2)"), expectedValue4)

    val expectedValue5 = Row(Seq(Row(-1, null, null, null), Row(null, null, null, null)))
    checkAnswer(df5.select(arrays_zip($"val1", $"val2", $"val3", $"val4")), expectedValue5)
    checkAnswer(df5.selectExpr("arrays_zip(val1, val2, val3, val4)"), expectedValue5)

    val expectedValue6 = Row(Seq(
      Row(192.toByte, 1.1, null, null), Row(256.toByte, null, null, null)))
    checkAnswer(df6.select(arrays_zip($"v1", $"v2", $"v3", $"v4")), expectedValue6)
    checkAnswer(df6.selectExpr("arrays_zip(v1, v2, v3, v4)"), expectedValue6)

    val expectedValue7 = Row(Seq(
      Row(Seq(1, 2, 3), 1.1), Row(Seq(4, 5), 2.2)))
    checkAnswer(df7.select(arrays_zip($"v1", $"v2")), expectedValue7)
    checkAnswer(df7.selectExpr("arrays_zip(v1, v2)"), expectedValue7)

    val expectedValue8 = Row(Seq(
      Row(Array[Byte](1.toByte, 5.toByte), null)))
    checkAnswer(df8.select(arrays_zip($"v1", $"v2")), expectedValue8)
    checkAnswer(df8.selectExpr("arrays_zip(v1, v2)"), expectedValue8)
  }

  test("SPARK-24633: arrays_zip splits input processing correctly") {
    Seq("true", "false").foreach { wholestageCodegenEnabled =>
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> wholestageCodegenEnabled) {
        val df = spark.range(1)
        val exprs = (0 to 5).map(x => array($"id" + lit(x)))
        checkAnswer(df.select(arrays_zip(exprs: _*)),
          Row(Seq(Row(0, 1, 2, 3, 4, 5))))
      }
    }
  }

  test("map size function") {
    val df = Seq(
      (Map[Int, Int](1 -> 1, 2 -> 2), "x"),
      (Map[Int, Int](), "y"),
      (Map[Int, Int](1 -> 1, 2 -> 2, 3 -> 3), "z"),
      (null, "empty")
    ).toDF("a", "b")
    checkAnswer(
      df.select(size($"a")),
      Seq(Row(2), Row(0), Row(3), Row(-1))
    )
    checkAnswer(
      df.selectExpr("size(a)"),
      Seq(Row(2), Row(0), Row(3), Row(-1))
    )
  }

  test("map_keys/map_values function") {
    val df = Seq(
      (Map[Int, Int](1 -> 100, 2 -> 200), "x"),
      (Map[Int, Int](), "y"),
      (Map[Int, Int](1 -> 100, 2 -> 200, 3 -> 300), "z")
    ).toDF("a", "b")
    checkAnswer(
      df.selectExpr("map_keys(a)"),
      Seq(Row(Seq(1, 2)), Row(Seq.empty), Row(Seq(1, 2, 3)))
    )
    checkAnswer(
      df.selectExpr("map_values(a)"),
      Seq(Row(Seq(100, 200)), Row(Seq.empty), Row(Seq(100, 200, 300)))
    )
  }

  test("map_entries") {
    val dummyFilter = (c: Column) => c.isNotNull || c.isNull

    // Primitive-type elements
    val idf = Seq(
      Map[Int, Int](1 -> 100, 2 -> 200, 3 -> 300),
      Map[Int, Int](),
      null
    ).toDF("m")
    val iExpected = Seq(
      Row(Seq(Row(1, 100), Row(2, 200), Row(3, 300))),
      Row(Seq.empty),
      Row(null)
    )

    checkAnswer(idf.select(map_entries('m)), iExpected)
    checkAnswer(idf.selectExpr("map_entries(m)"), iExpected)
    checkAnswer(idf.filter(dummyFilter('m)).select(map_entries('m)), iExpected)
    checkAnswer(
      spark.range(1).selectExpr("map_entries(map(1, null, 2, null))"),
      Seq(Row(Seq(Row(1, null), Row(2, null)))))
    checkAnswer(
      spark.range(1).filter(dummyFilter('id)).selectExpr("map_entries(map(1, null, 2, null))"),
      Seq(Row(Seq(Row(1, null), Row(2, null)))))

    // Non-primitive-type elements
    val sdf = Seq(
      Map[String, String]("a" -> "f", "b" -> "o", "c" -> "o"),
      Map[String, String]("a" -> null, "b" -> null),
      Map[String, String](),
      null
    ).toDF("m")
    val sExpected = Seq(
      Row(Seq(Row("a", "f"), Row("b", "o"), Row("c", "o"))),
      Row(Seq(Row("a", null), Row("b", null))),
      Row(Seq.empty),
      Row(null)
    )

    checkAnswer(sdf.select(map_entries('m)), sExpected)
    checkAnswer(sdf.selectExpr("map_entries(m)"), sExpected)
    checkAnswer(sdf.filter(dummyFilter('m)).select(map_entries('m)), sExpected)
  }

  test("map_from_entries function") {
    def dummyFilter(c: Column): Column = c.isNull || c.isNotNull
    val oneRowDF = Seq(3215).toDF("i")

    // Test cases with primitive-type keys and values
    val idf = Seq(
      Seq((1, 10), (2, 20), (3, 10)),
      Seq((1, 10), null, (2, 20)),
      Seq.empty,
      null
    ).toDF("a")
    val iExpected = Seq(
      Row(Map(1 -> 10, 2 -> 20, 3 -> 10)),
      Row(null),
      Row(Map.empty),
      Row(null))

    checkAnswer(idf.select(map_from_entries('a)), iExpected)
    checkAnswer(idf.selectExpr("map_from_entries(a)"), iExpected)
    checkAnswer(idf.filter(dummyFilter('a)).select(map_from_entries('a)), iExpected)
    checkAnswer(
      oneRowDF.selectExpr("map_from_entries(array(struct(1, null), struct(2, null)))"),
      Seq(Row(Map(1 -> null, 2 -> null)))
    )
    checkAnswer(
      oneRowDF.filter(dummyFilter('i))
        .selectExpr("map_from_entries(array(struct(1, null), struct(2, null)))"),
      Seq(Row(Map(1 -> null, 2 -> null)))
    )

    // Test cases with non-primitive-type keys and values
    val sdf = Seq(
      Seq(("a", "aa"), ("b", "bb"), ("c", "aa")),
      Seq(("a", "aa"), null, ("b", "bb")),
      Seq(("a", null), ("b", null)),
      Seq.empty,
      null
    ).toDF("a")
    val sExpected = Seq(
      Row(Map("a" -> "aa", "b" -> "bb", "c" -> "aa")),
      Row(null),
      Row(Map("a" -> null, "b" -> null)),
      Row(Map.empty),
      Row(null))

    checkAnswer(sdf.select(map_from_entries('a)), sExpected)
    checkAnswer(sdf.selectExpr("map_from_entries(a)"), sExpected)
    checkAnswer(sdf.filter(dummyFilter('a)).select(map_from_entries('a)), sExpected)
  }

  test("array contains function") {
    val df = Seq(
      (Seq[Int](1, 2), "x", 1),
      (Seq[Int](), "x", 1)
    ).toDF("a", "b", "c")

    // Simple test cases
    checkAnswer(
      df.select(array_contains(df("a"), 1)),
      Seq(Row(true), Row(false))
    )
    checkAnswer(
      df.selectExpr("array_contains(a, 1)"),
      Seq(Row(true), Row(false))
    )
    checkAnswer(
      df.select(array_contains(df("a"), df("c"))),
      Seq(Row(true), Row(false))
    )
    checkAnswer(
      df.selectExpr("array_contains(a, c)"),
      Seq(Row(true), Row(false))
    )

    // In hive, this errors because null has no type information
    intercept[AnalysisException] {
      df.select(array_contains(df("a"), null))
    }
    intercept[AnalysisException] {
      df.selectExpr("array_contains(a, null)")
    }
    intercept[AnalysisException] {
      df.selectExpr("array_contains(null, 1)")
    }

    checkAnswer(
      df.selectExpr("array_contains(array(array(1), null)[0], 1)"),
      Seq(Row(true), Row(true))
    )
    checkAnswer(
      df.selectExpr("array_contains(array(1, null), array(1, null)[0])"),
      Seq(Row(true), Row(true))
    )
  }

  test("arrays_overlap function") {
    val df = Seq(
      (Seq[Option[Int]](Some(1), Some(2)), Seq[Option[Int]](Some(-1), Some(10))),
      (Seq[Option[Int]](Some(1), Some(2)), Seq[Option[Int]](Some(-1), None)),
      (Seq[Option[Int]](Some(3), Some(2)), Seq[Option[Int]](Some(1), Some(2)))
    ).toDF("a", "b")

    val answer = Seq(Row(false), Row(null), Row(true))

    checkAnswer(df.select(arrays_overlap(df("a"), df("b"))), answer)
    checkAnswer(df.selectExpr("arrays_overlap(a, b)"), answer)

    checkAnswer(
      Seq((Seq(1, 2, 3), Seq(2.0, 2.5))).toDF("a", "b").selectExpr("arrays_overlap(a, b)"),
      Row(true))

    intercept[AnalysisException] {
      sql("select arrays_overlap(array(1, 2, 3), array('a', 'b', 'c'))")
    }

    intercept[AnalysisException] {
      sql("select arrays_overlap(null, null)")
    }

    intercept[AnalysisException] {
      sql("select arrays_overlap(map(1, 2), map(3, 4))")
    }
  }

  test("slice function") {
    val df = Seq(
      Seq(1, 2, 3),
      Seq(4, 5)
    ).toDF("x")

    val answer = Seq(Row(Seq(2, 3)), Row(Seq(5)))

    checkAnswer(df.select(slice(df("x"), 2, 2)), answer)
    checkAnswer(df.selectExpr("slice(x, 2, 2)"), answer)

    val answerNegative = Seq(Row(Seq(3)), Row(Seq(5)))
    checkAnswer(df.select(slice(df("x"), -1, 1)), answerNegative)
    checkAnswer(df.selectExpr("slice(x, -1, 1)"), answerNegative)
  }

  test("array_join function") {
    val df = Seq(
      (Seq[String]("a", "b"), ","),
      (Seq[String]("a", null, "b"), ","),
      (Seq.empty[String], ",")
    ).toDF("x", "delimiter")

    checkAnswer(
      df.select(array_join(df("x"), ";")),
      Seq(Row("a;b"), Row("a;b"), Row(""))
    )
    checkAnswer(
      df.select(array_join(df("x"), ";", "NULL")),
      Seq(Row("a;b"), Row("a;NULL;b"), Row(""))
    )
    checkAnswer(
      df.selectExpr("array_join(x, delimiter)"),
      Seq(Row("a,b"), Row("a,b"), Row("")))
    checkAnswer(
      df.selectExpr("array_join(x, delimiter, 'NULL')"),
      Seq(Row("a,b"), Row("a,NULL,b"), Row("")))
  }

  test("array_min function") {
    val df = Seq(
      Seq[Option[Int]](Some(1), Some(3), Some(2)),
      Seq.empty[Option[Int]],
      Seq[Option[Int]](None),
      Seq[Option[Int]](None, Some(1), Some(-100))
    ).toDF("a")

    val answer = Seq(Row(1), Row(null), Row(null), Row(-100))

    checkAnswer(df.select(array_min(df("a"))), answer)
    checkAnswer(df.selectExpr("array_min(a)"), answer)
  }

  test("array_max function") {
    val df = Seq(
      Seq[Option[Int]](Some(1), Some(3), Some(2)),
      Seq.empty[Option[Int]],
      Seq[Option[Int]](None),
      Seq[Option[Int]](None, Some(1), Some(-100))
    ).toDF("a")

    val answer = Seq(Row(3), Row(null), Row(null), Row(1))

    checkAnswer(df.select(array_max(df("a"))), answer)
    checkAnswer(df.selectExpr("array_max(a)"), answer)
  }

  test("reverse function") {
    val dummyFilter = (c: Column) => c.isNull || c.isNotNull // switch codegen on

    // String test cases
    val oneRowDF = Seq(("Spark", 3215)).toDF("s", "i")

    checkAnswer(
      oneRowDF.select(reverse('s)),
      Seq(Row("krapS"))
    )
    checkAnswer(
      oneRowDF.selectExpr("reverse(s)"),
      Seq(Row("krapS"))
    )
    checkAnswer(
      oneRowDF.select(reverse('i)),
      Seq(Row("5123"))
    )
    checkAnswer(
      oneRowDF.selectExpr("reverse(i)"),
      Seq(Row("5123"))
    )
    checkAnswer(
      oneRowDF.selectExpr("reverse(null)"),
      Seq(Row(null))
    )

    // Array test cases (primitive-type elements)
    val idf = Seq(
      Seq(1, 9, 8, 7),
      Seq(5, 8, 9, 7, 2),
      Seq.empty,
      null
    ).toDF("i")

    checkAnswer(
      idf.select(reverse('i)),
      Seq(Row(Seq(7, 8, 9, 1)), Row(Seq(2, 7, 9, 8, 5)), Row(Seq.empty), Row(null))
    )
    checkAnswer(
      idf.filter(dummyFilter('i)).select(reverse('i)),
      Seq(Row(Seq(7, 8, 9, 1)), Row(Seq(2, 7, 9, 8, 5)), Row(Seq.empty), Row(null))
    )
    checkAnswer(
      idf.selectExpr("reverse(i)"),
      Seq(Row(Seq(7, 8, 9, 1)), Row(Seq(2, 7, 9, 8, 5)), Row(Seq.empty), Row(null))
    )
    checkAnswer(
      oneRowDF.selectExpr("reverse(array(1, null, 2, null))"),
      Seq(Row(Seq(null, 2, null, 1)))
    )
    checkAnswer(
      oneRowDF.filter(dummyFilter('i)).selectExpr("reverse(array(1, null, 2, null))"),
      Seq(Row(Seq(null, 2, null, 1)))
    )

    // Array test cases (non-primitive-type elements)
    val sdf = Seq(
      Seq("c", "a", "b"),
      Seq("b", null, "c", null),
      Seq.empty,
      null
    ).toDF("s")

    checkAnswer(
      sdf.select(reverse('s)),
      Seq(Row(Seq("b", "a", "c")), Row(Seq(null, "c", null, "b")), Row(Seq.empty), Row(null))
    )
    checkAnswer(
      sdf.filter(dummyFilter('s)).select(reverse('s)),
      Seq(Row(Seq("b", "a", "c")), Row(Seq(null, "c", null, "b")), Row(Seq.empty), Row(null))
    )
    checkAnswer(
      sdf.selectExpr("reverse(s)"),
      Seq(Row(Seq("b", "a", "c")), Row(Seq(null, "c", null, "b")), Row(Seq.empty), Row(null))
    )
    checkAnswer(
      oneRowDF.selectExpr("reverse(array(array(1, 2), array(3, 4)))"),
      Seq(Row(Seq(Seq(3, 4), Seq(1, 2))))
    )
    checkAnswer(
      oneRowDF.filter(dummyFilter('s)).selectExpr("reverse(array(array(1, 2), array(3, 4)))"),
      Seq(Row(Seq(Seq(3, 4), Seq(1, 2))))
    )

    // Error test cases
    intercept[AnalysisException] {
      oneRowDF.selectExpr("reverse(struct(1, 'a'))")
    }
    intercept[AnalysisException] {
      oneRowDF.selectExpr("reverse(map(1, 'a'))")
    }
  }

  test("array position function") {
    val df = Seq(
      (Seq[Int](1, 2), "x", 1),
      (Seq[Int](), "x", 1)
    ).toDF("a", "b", "c")

    checkAnswer(
      df.select(array_position(df("a"), 1)),
      Seq(Row(1L), Row(0L))
    )
    checkAnswer(
      df.selectExpr("array_position(a, 1)"),
      Seq(Row(1L), Row(0L))
    )
    checkAnswer(
      df.selectExpr("array_position(a, c)"),
      Seq(Row(1L), Row(0L))
    )
    checkAnswer(
      df.select(array_position(df("a"), df("c"))),
      Seq(Row(1L), Row(0L))
    )
    checkAnswer(
      df.select(array_position(df("a"), null)),
      Seq(Row(null), Row(null))
    )
    checkAnswer(
      df.selectExpr("array_position(a, null)"),
      Seq(Row(null), Row(null))
    )

    checkAnswer(
      df.selectExpr("array_position(array(array(1), null)[0], 1)"),
      Seq(Row(1L), Row(1L))
    )
    checkAnswer(
      df.selectExpr("array_position(array(1, null), array(1, null)[0])"),
      Seq(Row(1L), Row(1L))
    )

    val e = intercept[AnalysisException] {
      Seq(("a string element", "a")).toDF().selectExpr("array_position(_1, _2)")
    }
    assert(e.message.contains("argument 1 requires array type, however, '`_1`' is of string type"))
  }

  test("element_at function") {
    val df = Seq(
      (Seq[String]("1", "2", "3"), 1),
      (Seq[String](null, ""), -1),
      (Seq[String](), 2)
    ).toDF("a", "b")

    intercept[Exception] {
      checkAnswer(
        df.select(element_at(df("a"), 0)),
        Seq(Row(null), Row(null), Row(null))
      )
    }.getMessage.contains("SQL array indices start at 1")
    intercept[Exception] {
      checkAnswer(
        df.select(element_at(df("a"), 1.1)),
        Seq(Row(null), Row(null), Row(null))
      )
    }
    checkAnswer(
      df.select(element_at(df("a"), 4)),
      Seq(Row(null), Row(null), Row(null))
    )
    checkAnswer(
      df.select(element_at(df("a"), df("b"))),
      Seq(Row("1"), Row(""), Row(null))
    )
    checkAnswer(
      df.selectExpr("element_at(a, b)"),
      Seq(Row("1"), Row(""), Row(null))
    )

    checkAnswer(
      df.select(element_at(df("a"), 1)),
      Seq(Row("1"), Row(null), Row(null))
    )
    checkAnswer(
      df.select(element_at(df("a"), -1)),
      Seq(Row("3"), Row(""), Row(null))
    )

    checkAnswer(
      df.selectExpr("element_at(a, 4)"),
      Seq(Row(null), Row(null), Row(null))
    )

    checkAnswer(
      df.selectExpr("element_at(a, 1)"),
      Seq(Row("1"), Row(null), Row(null))
    )
    checkAnswer(
      df.selectExpr("element_at(a, -1)"),
      Seq(Row("3"), Row(""), Row(null))
    )

    val e = intercept[AnalysisException] {
      Seq(("a string element", 1)).toDF().selectExpr("element_at(_1, _2)")
    }
    assert(e.message.contains(
      "argument 1 requires (array or map) type, however, '`_1`' is of string type"))
  }

  test("concat function - arrays") {
    val nseqi : Seq[Int] = null
    val nseqs : Seq[String] = null
    val df = Seq(

      (Seq(1), Seq(2, 3), Seq(5L, 6L), nseqi, Seq("a", "b", "c"), Seq("d", "e"), Seq("f"), nseqs),
      (Seq(1, 0), Seq.empty[Int], Seq(2L), nseqi, Seq("a"), Seq.empty[String], Seq(null), nseqs)
    ).toDF("i1", "i2", "i3", "in", "s1", "s2", "s3", "sn")

    val dummyFilter = (c: Column) => c.isNull || c.isNotNull // switch codeGen on

    // Simple test cases
    checkAnswer(
      df.selectExpr("array(1, 2, 3L)"),
      Seq(Row(Seq(1L, 2L, 3L)), Row(Seq(1L, 2L, 3L)))
    )

    checkAnswer (
      df.select(concat($"i1", $"s1")),
      Seq(Row(Seq("1", "a", "b", "c")), Row(Seq("1", "0", "a")))
    )
    checkAnswer(
      df.select(concat($"i1", $"i2", $"i3")),
      Seq(Row(Seq(1, 2, 3, 5, 6)), Row(Seq(1, 0, 2)))
    )
    checkAnswer(
      df.filter(dummyFilter($"i1")).select(concat($"i1", $"i2", $"i3")),
      Seq(Row(Seq(1, 2, 3, 5, 6)), Row(Seq(1, 0, 2)))
    )
    checkAnswer(
      df.selectExpr("concat(array(1, null), i2, i3)"),
      Seq(Row(Seq(1, null, 2, 3, 5, 6)), Row(Seq(1, null, 2)))
    )
    checkAnswer(
      df.select(concat($"s1", $"s2", $"s3")),
      Seq(Row(Seq("a", "b", "c", "d", "e", "f")), Row(Seq("a", null)))
    )
    checkAnswer(
      df.selectExpr("concat(s1, s2, s3)"),
      Seq(Row(Seq("a", "b", "c", "d", "e", "f")), Row(Seq("a", null)))
    )
    checkAnswer(
      df.filter(dummyFilter($"s1"))select(concat($"s1", $"s2", $"s3")),
      Seq(Row(Seq("a", "b", "c", "d", "e", "f")), Row(Seq("a", null)))
    )

    // Null test cases
    checkAnswer(
      df.select(concat($"i1", $"in")),
      Seq(Row(null), Row(null))
    )
    checkAnswer(
      df.select(concat($"in", $"i1")),
      Seq(Row(null), Row(null))
    )
    checkAnswer(
      df.select(concat($"s1", $"sn")),
      Seq(Row(null), Row(null))
    )
    checkAnswer(
      df.select(concat($"sn", $"s1")),
      Seq(Row(null), Row(null))
    )

    // Type error test cases
    intercept[AnalysisException] {
      df.selectExpr("concat(i1, i2, null)")
    }

    intercept[AnalysisException] {
      df.selectExpr("concat(i1, array(i1, i2))")
    }

    val e = intercept[AnalysisException] {
      df.selectExpr("concat(map(1, 2), map(3, 4))")
    }
    assert(e.getMessage.contains("string, binary or array"))
  }

  test("flatten function") {
    val dummyFilter = (c: Column) => c.isNull || c.isNotNull // to switch codeGen on
    val oneRowDF = Seq((1, "a", Seq(1, 2, 3))).toDF("i", "s", "arr")

    // Test cases with a primitive type
    val intDF = Seq(
      (Seq(Seq(1, 2, 3), Seq(4, 5), Seq(6))),
      (Seq(Seq(1, 2))),
      (Seq(Seq(1), Seq.empty)),
      (Seq(Seq.empty, Seq(1))),
      (Seq(Seq.empty, Seq.empty)),
      (Seq(Seq(1), null)),
      (Seq(null, Seq(1))),
      (Seq(null, null))
    ).toDF("i")

    val intDFResult = Seq(
      Row(Seq(1, 2, 3, 4, 5, 6)),
      Row(Seq(1, 2)),
      Row(Seq(1)),
      Row(Seq(1)),
      Row(Seq.empty),
      Row(null),
      Row(null),
      Row(null))

    checkAnswer(intDF.select(flatten($"i")), intDFResult)
    checkAnswer(intDF.filter(dummyFilter($"i"))select(flatten($"i")), intDFResult)
    checkAnswer(intDF.selectExpr("flatten(i)"), intDFResult)
    checkAnswer(
      oneRowDF.selectExpr("flatten(array(arr, array(null, 5), array(6, null)))"),
      Seq(Row(Seq(1, 2, 3, null, 5, 6, null))))

    // Test cases with non-primitive types
    val strDF = Seq(
      (Seq(Seq("a", "b"), Seq("c"), Seq("d", "e", "f"))),
      (Seq(Seq("a", "b"))),
      (Seq(Seq("a", null), Seq(null, "b"), Seq(null, null))),
      (Seq(Seq("a"), Seq.empty)),
      (Seq(Seq.empty, Seq("a"))),
      (Seq(Seq.empty, Seq.empty)),
      (Seq(Seq("a"), null)),
      (Seq(null, Seq("a"))),
      (Seq(null, null))
    ).toDF("s")

    val strDFResult = Seq(
      Row(Seq("a", "b", "c", "d", "e", "f")),
      Row(Seq("a", "b")),
      Row(Seq("a", null, null, "b", null, null)),
      Row(Seq("a")),
      Row(Seq("a")),
      Row(Seq.empty),
      Row(null),
      Row(null),
      Row(null))

    checkAnswer(strDF.select(flatten($"s")), strDFResult)
    checkAnswer(strDF.filter(dummyFilter($"s")).select(flatten($"s")), strDFResult)
    checkAnswer(strDF.selectExpr("flatten(s)"), strDFResult)
    checkAnswer(
      oneRowDF.selectExpr("flatten(array(array(arr, arr), array(arr)))"),
      Seq(Row(Seq(Seq(1, 2, 3), Seq(1, 2, 3), Seq(1, 2, 3)))))

    // Error test cases
    intercept[AnalysisException] {
      oneRowDF.select(flatten($"arr"))
    }
    intercept[AnalysisException] {
      oneRowDF.select(flatten($"i"))
    }
    intercept[AnalysisException] {
      oneRowDF.select(flatten($"s"))
    }
    intercept[AnalysisException] {
      oneRowDF.selectExpr("flatten(null)")
    }
  }

  test("array_repeat function") {
    val dummyFilter = (c: Column) => c.isNull || c.isNotNull // to switch codeGen on
    val strDF = Seq(
      ("hi", 2),
      (null, 2)
    ).toDF("a", "b")

    val strDFTwiceResult = Seq(
      Row(Seq("hi", "hi")),
      Row(Seq(null, null))
    )

    checkAnswer(strDF.select(array_repeat($"a", 2)), strDFTwiceResult)
    checkAnswer(strDF.filter(dummyFilter($"a")).select(array_repeat($"a", 2)), strDFTwiceResult)
    checkAnswer(strDF.select(array_repeat($"a", $"b")), strDFTwiceResult)
    checkAnswer(strDF.filter(dummyFilter($"a")).select(array_repeat($"a", $"b")), strDFTwiceResult)
    checkAnswer(strDF.selectExpr("array_repeat(a, 2)"), strDFTwiceResult)
    checkAnswer(strDF.selectExpr("array_repeat(a, b)"), strDFTwiceResult)

    val intDF = {
      val schema = StructType(Seq(
        StructField("a", IntegerType),
        StructField("b", IntegerType)))
      val data = Seq(
        Row(3, 2),
        Row(null, 2)
      )
      spark.createDataFrame(spark.sparkContext.parallelize(data), schema)
    }

    val intDFTwiceResult = Seq(
      Row(Seq(3, 3)),
      Row(Seq(null, null))
    )

    checkAnswer(intDF.select(array_repeat($"a", 2)), intDFTwiceResult)
    checkAnswer(intDF.filter(dummyFilter($"a")).select(array_repeat($"a", 2)), intDFTwiceResult)
    checkAnswer(intDF.select(array_repeat($"a", $"b")), intDFTwiceResult)
    checkAnswer(intDF.filter(dummyFilter($"a")).select(array_repeat($"a", $"b")), intDFTwiceResult)
    checkAnswer(intDF.selectExpr("array_repeat(a, 2)"), intDFTwiceResult)
    checkAnswer(intDF.selectExpr("array_repeat(a, b)"), intDFTwiceResult)

    val nullCountDF = {
      val schema = StructType(Seq(
        StructField("a", StringType),
        StructField("b", IntegerType)))
      val data = Seq(
        Row("hi", null),
        Row(null, null)
      )
      spark.createDataFrame(spark.sparkContext.parallelize(data), schema)
    }

    checkAnswer(
      nullCountDF.select(array_repeat($"a", $"b")),
      Seq(
        Row(null),
        Row(null)
      )
    )

    // Error test cases
    val invalidTypeDF = Seq(("hi", "1")).toDF("a", "b")

    intercept[AnalysisException] {
      invalidTypeDF.select(array_repeat($"a", $"b"))
    }
    intercept[AnalysisException] {
      invalidTypeDF.select(array_repeat($"a", lit("1")))
    }
    intercept[AnalysisException] {
      invalidTypeDF.selectExpr("array_repeat(a, 1.0)")
    }

  }

  test("array remove") {
    val df = Seq(
      (Array[Int](2, 1, 2, 3), Array("a", "b", "c", "a"), Array("", ""), 2),
      (Array.empty[Int], Array.empty[String], Array.empty[String], 2),
      (null, null, null, 2)
    ).toDF("a", "b", "c", "d")
    checkAnswer(
      df.select(array_remove($"a", 2), array_remove($"b", "a"), array_remove($"c", "")),
      Seq(
        Row(Seq(1, 3), Seq("b", "c"), Seq.empty[String]),
        Row(Seq.empty[Int], Seq.empty[String], Seq.empty[String]),
        Row(null, null, null))
    )

    checkAnswer(
      df.select(array_remove($"a", $"d")),
      Seq(
        Row(Seq(1, 3)),
        Row(Seq.empty[Int]),
        Row(null))
    )

    checkAnswer(
      df.selectExpr("array_remove(a, d)"),
      Seq(
        Row(Seq(1, 3)),
        Row(Seq.empty[Int]),
        Row(null))
    )

    checkAnswer(
      df.selectExpr("array_remove(a, 2)", "array_remove(b, \"a\")",
        "array_remove(c, \"\")"),
      Seq(
        Row(Seq(1, 3), Seq("b", "c"), Seq.empty[String]),
        Row(Seq.empty[Int], Seq.empty[String], Seq.empty[String]),
        Row(null, null, null))
    )

    val e = intercept[AnalysisException] {
      Seq(("a string element", "a")).toDF().selectExpr("array_remove(_1, _2)")
    }
    assert(e.message.contains("argument 1 requires array type, however, '`_1`' is of string type"))
  }

  test("array_distinct functions") {
    val df = Seq(
      (Array[Int](2, 1, 3, 4, 3, 5), Array("b", "c", "a", "c", "b", "", "")),
      (Array.empty[Int], Array.empty[String]),
      (null, null)
    ).toDF("a", "b")
    checkAnswer(
      df.select(array_distinct($"a"), array_distinct($"b")),
      Seq(
        Row(Seq(2, 1, 3, 4, 5), Seq("b", "c", "a", "")),
        Row(Seq.empty[Int], Seq.empty[String]),
        Row(null, null))
    )
    checkAnswer(
      df.selectExpr("array_distinct(a)", "array_distinct(b)"),
      Seq(
        Row(Seq(2, 1, 3, 4, 5), Seq("b", "c", "a", "")),
        Row(Seq.empty[Int], Seq.empty[String]),
        Row(null, null))
    )
  }

  private def assertValuesDoNotChangeAfterCoalesceOrUnion(v: Column): Unit = {
    import DataFrameFunctionsSuite.CodegenFallbackExpr
    for ((codegenFallback, wholeStage) <- Seq((true, false), (false, false), (false, true))) {
      val c = if (codegenFallback) {
        Column(CodegenFallbackExpr(v.expr))
      } else {
        v
      }
      withSQLConf(
        (SQLConf.CODEGEN_FALLBACK.key, codegenFallback.toString),
        (SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key, wholeStage.toString)) {
        val df = spark.range(0, 4, 1, 4).withColumn("c", c)
        val rows = df.collect()
        val rowsAfterCoalesce = df.coalesce(2).collect()
        assert(rows === rowsAfterCoalesce, "Values changed after coalesce when " +
          s"codegenFallback=$codegenFallback and wholeStage=$wholeStage.")

        val df1 = spark.range(0, 2, 1, 2).withColumn("c", c)
        val rows1 = df1.collect()
        val df2 = spark.range(2, 4, 1, 2).withColumn("c", c)
        val rows2 = df2.collect()
        val rowsAfterUnion = df1.union(df2).collect()
        assert(rowsAfterUnion === rows1 ++ rows2, "Values changed after union when " +
          s"codegenFallback=$codegenFallback and wholeStage=$wholeStage.")
      }
    }
  }

  test("SPARK-14393: values generated by non-deterministic functions shouldn't change after " +
    "coalesce or union") {
    Seq(
      monotonically_increasing_id(), spark_partition_id(),
      rand(Random.nextLong()), randn(Random.nextLong())
    ).foreach(assertValuesDoNotChangeAfterCoalesceOrUnion(_))
  }

  test("SPARK-21281 use string types by default if array and map have no argument") {
    val ds = spark.range(1)
    var expectedSchema = new StructType()
      .add("x", ArrayType(StringType, containsNull = false), nullable = false)
    assert(ds.select(array().as("x")).schema == expectedSchema)
    expectedSchema = new StructType()
      .add("x", MapType(StringType, StringType, valueContainsNull = false), nullable = false)
    assert(ds.select(map().as("x")).schema == expectedSchema)
  }

  test("SPARK-21281 fails if functions have no argument") {
    val df = Seq(1).toDF("a")

    val funcsMustHaveAtLeastOneArg =
      ("coalesce", (df: DataFrame) => df.select(coalesce())) ::
      ("coalesce", (df: DataFrame) => df.selectExpr("coalesce()")) ::
      ("named_struct", (df: DataFrame) => df.select(struct())) ::
      ("named_struct", (df: DataFrame) => df.selectExpr("named_struct()")) ::
      ("hash", (df: DataFrame) => df.select(hash())) ::
      ("hash", (df: DataFrame) => df.selectExpr("hash()")) :: Nil
    funcsMustHaveAtLeastOneArg.foreach { case (name, func) =>
      val errMsg = intercept[AnalysisException] { func(df) }.getMessage
      assert(errMsg.contains(s"input to function $name requires at least one argument"))
    }

    val funcsMustHaveAtLeastTwoArgs =
      ("greatest", (df: DataFrame) => df.select(greatest())) ::
      ("greatest", (df: DataFrame) => df.selectExpr("greatest()")) ::
      ("least", (df: DataFrame) => df.select(least())) ::
      ("least", (df: DataFrame) => df.selectExpr("least()")) :: Nil
    funcsMustHaveAtLeastTwoArgs.foreach { case (name, func) =>
      val errMsg = intercept[AnalysisException] { func(df) }.getMessage
      assert(errMsg.contains(s"input to function $name requires at least two arguments"))
    }
  }
}

object DataFrameFunctionsSuite {
  case class CodegenFallbackExpr(child: Expression) extends Expression with CodegenFallback {
    override def children: Seq[Expression] = Seq(child)
    override def nullable: Boolean = child.nullable
    override def dataType: DataType = child.dataType
    override lazy val resolved = true
    override def eval(input: InternalRow): Any = child.eval(input)
  }
}
