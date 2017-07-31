package it.agilelab.bigdata.wasp.consumers.spark.plugins.kafka

import it.agilelab.bigdata.wasp.consumers.spark.writers.{SparkLegacyStreamingWriter, SparkStructuredStreamingWriter}
import it.agilelab.bigdata.wasp.core.WaspSystem
import it.agilelab.bigdata.wasp.core.WaspSystem._
import it.agilelab.bigdata.wasp.core.bl.TopicBL
import it.agilelab.bigdata.wasp.core.kafka.{CheckOrCreateTopic, WaspKafkaWriter}
import it.agilelab.bigdata.wasp.core.logging.Logging
import it.agilelab.bigdata.wasp.core.models.TopicModel
import it.agilelab.bigdata.wasp.core.models.configuration.{KafkaEntryConfig, TinyKafkaConfig}
import it.agilelab.bigdata.wasp.core.utils.{AvroToJsonUtil, ConfigManager, JsonToByteArrayUtil, RowToAvro}
import org.apache.spark.sql.streaming.{DataStreamWriter, StreamingQuery}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.DStream

class KafkaSparkLegacyStreamingWriter(topicBL: TopicBL,
                                      ssc: StreamingContext,
                                      name: String)
  extends SparkLegacyStreamingWriter {

  override def write(stream: DStream[String]): Unit = {

    val kafkaConfig = ConfigManager.getKafkaConfig
    val tinyKafkaConfig = kafkaConfig.toTinyConfig()

    val topicOpt: Option[TopicModel] = topicBL.getByName(name)
    topicOpt.foreach(topic => {

      if (??[Boolean](WaspSystem.kafkaAdminActor, CheckOrCreateTopic(topic.name, topic.partitions, topic.replicas))) {

        val schemaB = ssc.sparkContext.broadcast(topic.getJsonSchema)
        val configB = ssc.sparkContext.broadcast(tinyKafkaConfig)
        val topicNameB = ssc.sparkContext.broadcast(topic.name)
	      val topicDataTypeB = ssc.sparkContext.broadcast(topic.topicDataType)

        stream.foreachRDD(rdd => {
          rdd.foreachPartition(partitionOfRecords => {

            // TODO remove ???
            // val writer = WorkerKafkaWriter.writer(configB.value)

            val writer = new WaspKafkaWriter[String, Array[Byte]](configB.value)

            partitionOfRecords.foreach(record => {
              val bytes = topicDataTypeB.value match {
                case "json" => JsonToByteArrayUtil.jsonToByteArray(record)
                case "avro" => AvroToJsonUtil.jsonToAvro(record, schemaB.value)
                case _ => AvroToJsonUtil.jsonToAvro(record, schemaB.value)
              }
              writer.send(topicNameB.value, null, bytes)

            })

            writer.close()
          })
        })

      } else {
        val msg = s"Error creating topic ${topic.name}"
        throw new Exception(msg)
      }
    })
  }
}

class KafkaSparkStructuredStreamingWriter(topicBL: TopicBL,
                                          name: String,
                                          ss: SparkSession)
  extends SparkStructuredStreamingWriter
    with Logging {

  override def write(stream: DataFrame,
                     queryName: String,
                     checkpointDir: String): StreamingQuery = {

    import ss.implicits._

    val kafkaConfig = ConfigManager.getKafkaConfig
    val tinyKafkaConfig = kafkaConfig.toTinyConfig()

    val topicOpt: Option[TopicModel] = topicBL.getByName(name)


    if (topicOpt.isDefined) {

      val topic = topicOpt.get
      val topicDataTypeB = ss.sparkContext.broadcast(topic.topicDataType)

      if (??[Boolean](WaspSystem.kafkaAdminActor, CheckOrCreateTopic(topic.name, topic.partitions, topic.replicas))) {

        val pkf = topic.partitionKeyField
        val pkfIndex: Option[Int] = pkf.map(k => stream.schema.fieldIndex(k))

        val dswParsed = topicDataTypeB.value match {
          case "avro" => {
            val converter: RowToAvro = RowToAvro(stream.schema, topic.name, "wasp", None, Some(topic.getJsonSchema))
            logger.debug(s"Schema DF spark, topic name ${topic.name}: " + stream.schema.treeString)
            logger.debug(s"Schema Avro, topic name ${topic.name}: " + converter.getSchema().toString(true))

            stream.map(r => {
              val key: String = pkfIndex.map(r.getString).orNull
              (key, converter.write(r))
            }).toDF("key", "value")
          }
          case _ => {
            // json conversion
            if (pkf.isDefined)  stream.selectExpr(pkf.get, "to_json(struct(*)) AS value")
            else stream.selectExpr("to_json(struct(*)) AS value")
          }
        }

        val dswParsedReady = dswParsed
          .writeStream
          .format("kafka")
          .option("topic", topic.name)
          .option("checkpointLocation", checkpointDir)
          .queryName(queryName)

        val dswWithWritingConf = addKafkaConf(dswParsedReady, tinyKafkaConfig)

        dswWithWritingConf.start()
      } else {
        val msg = s"Error creating topic ${topic.name}"
        throw new Exception(msg)
      }
    } else {
      val msg = s"No Topic specified in writer model"
      throw new Exception(msg)
    }

  }

  private def addKafkaConf(dsw: DataStreamWriter[Row], tkc: TinyKafkaConfig): DataStreamWriter[Row] = {

    val connectionString = tkc.connections.map{
      conn => s"${conn.host}:${conn.port}"
    }.mkString(",")
    // Added for backwards compatibility
    val kafkaConfigMap: Seq[KafkaEntryConfig] =
      if (tkc.others.map(_.key).contains("acks")) {
        tkc.others
      } else {
        tkc.others :+ KafkaEntryConfig("acks", "1")
      }

    dsw
      .option("kafka.bootstrap.servers", connectionString)
      .option("value.serializer", tkc.default_encoder)
      .option("key.serializer", tkc.encoder_fqcn)
      .option("kafka.partitioner.class", tkc.partitioner_fqcn)
      .option("kafka.batch.size", tkc.batch_send_size.toString)
      .options(kafkaConfigMap.map(v => v.copy(key = "kafka." + v.key)).map(_.toTupla).toMap)
  }
}

object WorkerKafkaWriter {
	//lazy producer creation allows to create a kafka conection per worker instead of per partition
	def writer(config: TinyKafkaConfig): WaspKafkaWriter[String, Array[Byte]] = {
		ProducerObject.config = config
		//thread safe
		ProducerObject.writer
	}
	
	object ProducerObject {
		var config: TinyKafkaConfig = _
    // TODO unused!
		lazy val writer = new WaspKafkaWriter[String, Array[Byte]](config)
	}
	
}