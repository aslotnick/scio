/*
 * Copyright 2021 Spotify AB.
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

package org.apache.beam.sdk.extensions.smb

import com.spotify.scio.coders.{Coder, CoderMaterializer}
import magnolify.parquet.ParquetType
import org.apache.beam.sdk.coders.{Coder => BCoder}
import org.apache.beam.sdk.io.hadoop.SerializableConfiguration
import org.apache.beam.sdk.io.{Compression, FileIO}
import org.apache.beam.sdk.transforms.display.DisplayData
import org.apache.beam.sdk.util.MimeTypes
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.filter2.compat.FilterCompat
import org.apache.parquet.filter2.predicate.FilterPredicate
import org.apache.parquet.hadoop.{ParquetReader, ParquetWriter}
import org.apache.parquet.hadoop.metadata.CompressionCodecName

import scala.jdk.CollectionConverters._

import java.nio.channels.{ReadableByteChannel, WritableByteChannel}
import java.util.Objects

object ParquetTypeFileOperations {

  val DefaultCompression = CompressionCodecName.ZSTD
  val DefaultConfiguration: Configuration = null

  // make sure parquet is part of the classpath
  try {
    Class.forName("org.apache.parquet.schema.Types");
  } catch {
    case e: ClassNotFoundException =>
      throw new MissingImplementationException("parquet", e);
  }

  def apply[T: Coder: ParquetType](): ParquetTypeFileOperations[T] = apply(DefaultCompression)

  def apply[T: Coder: ParquetType](
    compression: CompressionCodecName
  ): ParquetTypeFileOperations[T] =
    apply(compression, new Configuration())

  def apply[T: Coder: ParquetType](
    compression: CompressionCodecName,
    conf: Configuration
  ): ParquetTypeFileOperations[T] =
    ParquetTypeFileOperations(compression, new SerializableConfiguration(conf), null)

  def apply[T: Coder: ParquetType](predicate: FilterPredicate): ParquetTypeFileOperations[T] =
    apply(predicate, new Configuration())

  def apply[T: Coder: ParquetType](
    predicate: FilterPredicate,
    conf: Configuration
  ): ParquetTypeFileOperations[T] =
    ParquetTypeFileOperations(
      DefaultCompression,
      new SerializableConfiguration(conf),
      predicate
    )
}

case class ParquetTypeFileOperations[T](
  compression: CompressionCodecName,
  conf: SerializableConfiguration,
  predicate: FilterPredicate
)(implicit val pt: ParquetType[T], val coder: Coder[T])
    extends FileOperations[T](Compression.UNCOMPRESSED, MimeTypes.BINARY) {

  override def populateDisplayData(builder: DisplayData.Builder): Unit = {
    super.populateDisplayData(builder)
    builder.add(DisplayData.item("compressionCodecName", compression.name()))
    builder.add(DisplayData.item("schema", pt.schema.getName))
  }

  override protected def createReader(): FileOperations.Reader[T] =
    ParquetTypeReader[T](conf, predicate)

  override protected def createSink(): FileIO.Sink[T] = ParquetTypeSink(compression, conf)

  override def getCoder: BCoder[T] = CoderMaterializer.beamWithDefault(coder)

  override def hashCode(): Int = Objects.hash(compression.name(), conf.get(), predicate)

  override def equals(obj: Any): Boolean = obj match {
    case ParquetTypeFileOperations(compressionThat, confThat, predicateThat) =>
      this.compression.name() == compressionThat.name() && this.predicate == predicateThat &&
      conf
        .get()
        .iterator()
        .asScala
        .map(e => (e.getKey, e.getValue))
        .toMap
        .equals(
          confThat.get().iterator().asScala.map(e => (e.getKey, e.getValue)).toMap
        )
    case _ => false
  }
}

private case class ParquetTypeReader[T](
  conf: SerializableConfiguration,
  predicate: FilterPredicate
)(implicit val pt: ParquetType[T])
    extends FileOperations.Reader[T] {
  @transient private var reader: ParquetReader[T] = _
  @transient private var current: T = _

  override def prepareRead(channel: ReadableByteChannel): Unit = {
    var builder = pt.readBuilder(new ParquetInputFile(channel)).withConf(conf.get())
    if (predicate != null) {
      builder = builder.withFilter(FilterCompat.get(predicate))
    }
    reader = builder.build()
    current = reader.read()
  }

  override def readNext(): T = {
    val r = current
    current = reader.read()
    r
  }

  override def hasNextElement: Boolean = current != null
  override def finishRead(): Unit = reader.close()
}

private case class ParquetTypeSink[T](
  compression: CompressionCodecName,
  conf: SerializableConfiguration
)(implicit val pt: ParquetType[T])
    extends FileIO.Sink[T] {
  @transient private var writer: ParquetWriter[T] = _

  override def open(channel: WritableByteChannel): Unit =
    writer = ParquetUtils.buildWriter(
      pt
        .writeBuilder(new ParquetOutputFile(channel)),
      conf.get(),
      compression
    )

  override def write(element: T): Unit = writer.write(element)
  override def flush(): Unit = writer.close()
}
