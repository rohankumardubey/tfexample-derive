/*
 * Copyright 2018 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package tf.magnolia

import com.google.protobuf.ByteString
import org.scalatest.{FlatSpec, Matchers}
import org.tensorflow.example._
import TensorflowMapping._
import java.lang.{Float => JFloat, Iterable => JIterable, Long => JLong}
import java.util

import scala.collection.JavaConverters._

class ExampleConverterTest extends FlatSpec with Matchers {

  "ExampleConversion" should "support basic types" in {
    case class BasicRecord(int: Int, long: Long, float: Float, bytes: ByteString, string: String)
    val converter = ExampleConverter[BasicRecord]
    val record = BasicRecord(1, 2, 3.0f, ByteString.copyFromUtf8("hello"), "world")
    val actual = converter.toExample(record)
    val expected = Example.newBuilder()
        .setFeatures(Features.newBuilder()
          .putFeature("int", longFeat(1L))
          .putFeature("long", longFeat(2L))
          .putFeature("float", floatFeat(3.0f))
          .putFeature("bytes", stringFeat("hello"))
          .putFeature("string", stringFeat("world"))
        .build)
    featuresOf(actual) shouldEqual featuresOf(expected)
    converter.fromExample(actual) shouldEqual record
  }

  it should "support nested case class" in {
    case class Record(f1: Int, f2: Long, inner: Inner)
    case class Inner(f3: Long)
    val record = Record(1, 2l, Inner(3l))
    val converter = ExampleConverter[Record]
    val example = converter.toExample(record)
    example.getFeatures.getFeatureCount shouldEqual 3
    val features = example.getFeatures.getFeatureMap
    features.get("f1").getInt64List shouldEqual Int64List.newBuilder().addValue(1).build
    features.get("f2").getInt64List shouldEqual Int64List.newBuilder().addValue(2).build
    features.get("inner.f3").getInt64List shouldEqual Int64List.newBuilder().addValue(3).build
    converter.fromExample(example) shouldEqual record
  }

  it should "handle duplicate feature names" in {
    case class Outer(f: Int, middle: Middle)
    case class Middle(f: Int, inner: Inner)
    case class Inner(f: Int)
    val record = Outer(1, Middle(2, Inner(3)))
    val converter = ExampleConverter[Outer]
    val example = converter.toExample(record)
    example.getFeatures.getFeatureCount shouldEqual 3
    val expectedFeatures = Map[String, Feature](
      "f" -> longFeat(1L),
      "middle.f" -> longFeat(2L),
      "middle.inner.f" -> longFeat(3L)
    ).asJava
    featuresOf(example) shouldEqual expectedFeatures
    converter.fromExample(example) shouldEqual record
  }

  it should "support collection types" in {
    case class Record(int: Int, ints: List[Int], inner: Inner)
    case class Inner(floats: Seq[Float], bools: Array[Boolean])
    val converter = ExampleConverter[Record]
    val record = Record(1, List(1, 2, 3), Inner(Seq(1.0f, 2.0f), Array(true, false)))
    val example = converter.toExample(record)
    val expected = Example.newBuilder()
      .setFeatures(Features.newBuilder()
        .putFeature("int", longFeat(1))
        .putFeature("ints", longFeat(1L, 2L, 3L))
        .putFeature("inner.floats", floatFeat(1.0f, 2.0f))
        .putFeature("inner.bools", longFeat(1L, 0L))
        .build)
    featuresOf(example) shouldEqual featuresOf(expected)
    // Test round trip
    val newRecord = converter.fromExample(example)
    newRecord.int shouldEqual 1
    newRecord.ints shouldEqual List(1, 2, 3)
    newRecord.inner.bools.toList shouldEqual List(true, false)
    newRecord.inner.floats.toList shouldEqual List(1.0f, 2.0f)
  }

  it should "support custom types" in {
    class MyInt(val int: Int)

    implicit val myIntType: TensorflowMapping[MyInt] =
      TensorflowMapping[MyInt](
        f => toInts(f).map(n => new MyInt(n)),
        myInts => fromInts(myInts.map(_.int)))

    case class Record(id: String, myInt: MyInt, myInts: List[MyInt])
    val converter = ExampleConverter[Record]
    val record = Record("1", new MyInt(1), List(new MyInt(2), new MyInt(3)))
    val example = converter.toExample(record)
    val expected = Example.newBuilder()
      .setFeatures(Features.newBuilder()
        .putFeature("id", stringFeat("1"))
        .putFeature("myInt", longFeat(1L))
        .putFeature("myInts", longFeat(2L, 3L))
      )
      .build()
    featuresOf(example) shouldEqual featuresOf(expected)
    val newRecord = converter.fromExample(example)
    newRecord.id shouldEqual "1"
    newRecord.myInt.int shouldEqual 1
    newRecord.myInts.map(_.int) shouldEqual List(2, 3)
  }

  it should "support option types" in {
    case class OptionRecord(int: Option[Int], floats: Option[List[Float]], inner: Option[Inner])
    case class Inner(bool: Boolean)
    val converter = ExampleConverter[OptionRecord]

    // All Some()
    val record1 = OptionRecord(Some(2), Some(List(1.0f, 2.0f)), Some(Inner(true)))
    val expected1 = Example.newBuilder()
      .setFeatures(Features.newBuilder()
        .putFeature("Some#int.value", longFeat(2L))
        .putFeature("Some#floats.value", floatFeat(1.0f, 2.0f))
        .putFeature("Some#inner.value.bool", longFeat(1L))
        .build)
      .build()
    val example1 = converter.toExample(record1)
    // We can't assert directly due to Some.x vs Some.value difference between scala 2.11 / 2.12
    val fMap = expected1.getFeatures.getFeatureMap.asScala.toMap
    fMap.size shouldEqual 3
    featureOfKeyPrefix(fMap, "Some#int").get shouldEqual longFeat(2L)
    featureOfKeyPrefix(fMap, "Some#float").get shouldEqual floatFeat(1.0f, 2.0f)
    featureOfKeyPrefix(fMap, "Some#inner").get shouldEqual longFeat(1L)
    converter.fromExample(example1) shouldEqual record1

    // All None
    val record2 = OptionRecord(None, None, None)
    val expected2 = Example.newBuilder()
      .build()
    val example2 = converter.toExample(record2)
    featuresOf(example2) shouldEqual featuresOf(expected2)
    converter.fromExample(example2) shouldEqual record2
  }

  private def featureOfKeyPrefix(fMap: Map[String, Feature], prefix: String): Option[Feature] =
    fMap.keys.find(_.startsWith(prefix)).map(key => fMap(key))

  private def longFeat(longs: Long*): Feature = {
    val jLongs = longs.asJava.asInstanceOf[JIterable[JLong]]
    Feature.newBuilder().setInt64List(Int64List.newBuilder().addAllValue(jLongs)).build()
  }

  private def floatFeat(floats: Float*): Feature = {
    val jFloats = floats.asJava.asInstanceOf[JIterable[JFloat]]
    Feature.newBuilder().setFloatList(FloatList.newBuilder().addAllValue(jFloats)).build()
  }

  private def stringFeat(str: String*): Feature = {
    val strings = str.map(ByteString.copyFromUtf8).asJava
    Feature.newBuilder().setBytesList(BytesList.newBuilder().addAllValue(strings))
      .build()
  }

  private def featuresOf(e: Example): util.Map[String, Feature] = e.getFeatures.getFeatureMap
  private def featuresOf(e: Example.Builder): util.Map[String, Feature] = featuresOf(e.build)
}
