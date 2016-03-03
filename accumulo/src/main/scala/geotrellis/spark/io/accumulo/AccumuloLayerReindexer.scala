package geotrellis.spark.io.accumulo

import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.avro._
import geotrellis.spark.io.index._
import geotrellis.spark.io.json._

import org.apache.avro.Schema
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.joda.time.DateTime
import spray.json.JsonFormat

import scala.reflect.ClassTag

object AccumuloLayerReindexer {
  def apply(
    instance: AccumuloInstance,
    attributeStore: AttributeStore[JsonFormat],
    options: AccumuloLayerWriter.Options
  )(implicit sc: SparkContext): AccumuloLayerReindexer =
    new AccumuloLayerReindexer(instance, AccumuloAttributeStore(instance), options)

  def apply(
    instance: AccumuloInstance,
    options: AccumuloLayerWriter.Options
  )(implicit sc: SparkContext): AccumuloLayerReindexer =
    apply(instance, AccumuloAttributeStore(instance), options)

  def apply(
    instance: AccumuloInstance
  )(implicit sc: SparkContext): AccumuloLayerReindexer =
    apply(instance, AccumuloLayerWriter.Options.DEFAULT)
}

class AccumuloLayerReindexer(
  instance: AccumuloInstance,
  attributeStore: AttributeStore[JsonFormat],
  options: AccumuloLayerWriter.Options
)(implicit sc: SparkContext) extends LayerReindexer[LayerId] {

  def getTmpId(id: LayerId): LayerId =
    id.copy(name = s"${id.name}-${DateTime.now.getMillis}")

  def reindex[
    K: AvroRecordCodec: Boundable: JsonFormat: ClassTag,
    V: AvroRecordCodec: ClassTag,
    M: JsonFormat
  ](id: LayerId, keyIndex: KeyIndex[K]): Unit = {
    if (!attributeStore.layerExists(id)) throw new LayerNotFoundError(id)
    val tmpId = getTmpId(id)

    val (existingLayerHeader, existingMetaData, existingKeyBounds, existingKeyIndex, existingSchema) =
      attributeStore.readLayerAttributes[AccumuloLayerHeader, M, KeyBounds[K], KeyIndex[K], Schema](id)
    val table = existingLayerHeader.tileTable

    val layerReader = AccumuloLayerReader(instance)
    val layerWriter = AccumuloLayerWriter(instance, table, options)
    val layerDeleter = AccumuloLayerDeleter(instance)
    val layerCopier = AccumuloLayerCopier(attributeStore, layerReader, layerWriter)

    layerWriter.write(tmpId, layerReader.read[K, V, M](id), keyIndex, existingKeyBounds)
    layerDeleter.delete(id)
    layerCopier.copy[K, V, M](tmpId, id)
    layerDeleter.delete(tmpId)
  }

  def reindex[
    K: AvroRecordCodec: Boundable: JsonFormat: ClassTag,
    V: AvroRecordCodec: ClassTag,
    M: JsonFormat
  ](id: LayerId, keyIndexMethod: KeyIndexMethod[K]): Unit = {
    if (!attributeStore.layerExists(id)) throw new LayerNotFoundError(id)
    val tmpId = getTmpId(id)

    val (existingLayerHeader, existingMetaData, existingKeyBounds, existingKeyIndex, existingSchema) =
      attributeStore.readLayerAttributes[AccumuloLayerHeader, M, KeyBounds[K], KeyIndex[K], Schema](id)
    val table = existingLayerHeader.tileTable

    val layerReader = AccumuloLayerReader(instance)
    val layerWriter = AccumuloLayerWriter(instance, table, options)
    val layerDeleter = AccumuloLayerDeleter(instance)
    val layerCopier = AccumuloLayerCopier(attributeStore, layerReader, layerWriter)

    layerWriter.write(tmpId, layerReader.read[K, V, M](id), keyIndexMethod.createIndex(existingKeyBounds), existingKeyBounds)
    layerDeleter.delete(id)
    layerCopier.copy[K, V, M](tmpId, id)
    layerDeleter.delete(tmpId)
  }
}
