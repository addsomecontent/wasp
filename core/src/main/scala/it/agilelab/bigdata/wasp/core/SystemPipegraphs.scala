package it.agilelab.bigdata.wasp.core

import it.agilelab.bigdata.wasp.core.datastores.DatastoreProduct.{ElasticProduct, GenericIndexProduct, SolrProduct}
import it.agilelab.bigdata.wasp.core.eventengine.eventconsumers.MailingPipegraphModel
import it.agilelab.bigdata.wasp.core.eventengine.eventproducers.EventPipegraphModel
import it.agilelab.bigdata.wasp.core.models._
import it.agilelab.bigdata.wasp.core.utils.{ConfigManager, JsonConverter}

/**
	* Default system pipegraphs.
	*/
object SystemPipegraphs {
	/** Logger  */
	lazy val loggerTopic: TopicModel = LoggerTopicModel()
	lazy val solrLoggerIndex: IndexModel = SolrLoggerIndex()
  lazy val elasticLoggerIndex: IndexModel = ElasticLoggerIndexModel()
	lazy val loggerProducer: ProducerModel = LoggerProducer()
	lazy val loggerPipegraph: PipegraphModel = LoggerPipegraph()

	
	/** Telemetry  */
	lazy val telemetryTopic: TopicModel = TelemetryTopicModel()
	lazy val solrTelemetryIndex: IndexModel = SolrTelemetryIndexModel()
  lazy val elasticTelemetryIndex: IndexModel = ElasticLatencyIndexModel()
  lazy val telemetryPipegraph: PipegraphModel = TelemetryPipegraph()



	/** Event */
	lazy val eventPipraph: PipegraphModel = EventPipegraphModel.eventPipegraph
	lazy val mailerPipegraph: PipegraphModel = MailingPipegraphModel.mailingPipegraph
	lazy val eventTopicModels: Seq[TopicModel] = EventPipegraphModel.outputTopicModels
  //lazy val mailerInputTopics = null ==> nothing to do because it's a subset of eventOutputTopics

}

private[wasp] object LoggerTopicModel {

	private val topic_name = "logger"

	def apply() = TopicModel(name = TopicModel.name(topic_name),
	                         creationTime = System.currentTimeMillis,
	                         partitions = 3,
	                         replicas = 1,
	                         topicDataType = "avro",
	                         keyFieldName = None,
	                         headersFieldName = None,
	                         valueFieldsNames = None,
		                       useAvroSchemaManager=false,
	                         schema = JsonConverter
		                         .fromString(topicSchema)
		                         .getOrElse(org.mongodb.scala.bson.BsonDocument()))

	private val topicSchema =
		TopicModel.generateField("logging", "logging", Some(
			"""
				|				 {
				|            "name": "log_source",
				|            "type": "string",
				|            "doc": "Class that logged this message"
				|        },
				|        {
				|            "name": "log_level",
				|            "type": "string",
				|            "doc": "Logged message level"
				|        },
				|        {
				|            "name": "message",
				|            "type": "string",
				|            "doc": "Logged message"
				|        },
				|        {
				|            "name": "timestamp",
				|            "type": "string",
				|            "doc": "Logged message timestamp in  ISO-8601 format"
				|        },
				|        {
				|            "name": "thread",
				|            "type": "string",
				|            "doc": "Thread that logged this message"
				|        },
				|        {
				|            "name": "cause",
				|            "type": "string",
				|            "doc": "Message of the logged exception attached to this logged message",
				|        },
				|        {
				|            "name": "stack_trace",
				|            "type": "string",
				|            "doc": "Stacktrace of the logged exception attached to this logged message",
				|        }
			""".stripMargin))
}

private[wasp] object TelemetryTopicModel {



	def apply() = TopicModel(name = TopicModel.name(ConfigManager.getTelemetryConfig.telemetryTopicConfigModel.topicName),
	                         creationTime = System.currentTimeMillis,
	                         partitions = 3,
	                         replicas = 1,
	                         topicDataType = "json",
	                         keyFieldName = None,
	                         headersFieldName = None,
	                         valueFieldsNames = None,
		                       useAvroSchemaManager=false,
	                         schema = JsonConverter
		                         .fromString(topicSchema)
		                         .getOrElse(org.mongodb.scala.bson.BsonDocument()))

	private val topicSchema =
		TopicModel.generateField("telemetry", "telemetry", Some(
			"""
				|        {
				|            "name": "messageId",
				|            "type": "string",
				|            "doc": "Unique id of message whose latency was recorded"
				|        },
				|				 {
				|            "name": "timestamp",
				|            "type": "string",
				|            "doc": "Logged message timestamp in  ISO-8601 format"
				|        },
				|        {
				|            "name": "sourceId",
				|            "type": "string",
				|            "doc": "Id of the block that generated this message"
				|        },
				|        {
				|            "name": "metric",
				|            "type": "string",
				|            "doc": "Name of the metric"
				|        },
				|        {
				|            "name": "value",
				|            "type": "double",
				|            "doc": "Value of the metric"
				|        }""".stripMargin))
}

private[wasp] object SolrTelemetryIndexModel {

	val index_name = "telemetry_solr"

	import IndexModelBuilder._

	def apply(): IndexModel = IndexModelBuilder.forSolr
		.named(index_name)
		.config(Solr.Config(shards = 1,
											  replica = 1))
		.schema(Solr.Schema(
      Solr.Field("messageId", Solr.Type.String),
      Solr.Field("timestamp", Solr.Type.TrieDate),
      Solr.Field("sourceId", Solr.Type.String),
			Solr.Field("metric", Solr.Type.String),
			Solr.Field("value", Solr.Type.TrieDouble)))
		.build

}

private[wasp] object ElasticLatencyIndexModel {
  import org.json4s._

  import org.json4s.native.JsonMethods._
  import org.json4s.JsonDSL._

  import IndexModelBuilder._

  val index_name = "telemetry_elastic"

  import IndexModelBuilder._

  def apply(): IndexModel = IndexModelBuilder.forElastic
    .named(index_name)
    .config(Elastic.Config(shards = 1,
                           replica = 1))
    .schema(Elastic.Schema(indexElasticSchema))
    .build

  //noinspection ScalaUnnecessaryParentheses
  private lazy val indexElasticSchema = parse("""
        {
          "properties": {
            "messageId": {
              "type": "keyword"
            },
            "sourceId": {
              "type": "keyword"
            },
						"metric": {
               "type": "keyword"
             },
            "value": {
              "type": "double"
            },
            "timestamp": {
              "type": "date"
            }
          }
        }""").asInstanceOf[JObject]
}

private[wasp] object LoggerProducer {

	def apply() = ProducerModel(
		name = "LoggerProducer",
		className = "it.agilelab.bigdata.wasp.producers.InternalLogProducerGuardian",
		topicName = Some(SystemPipegraphs.loggerTopic.name),
		isActive = false,
    configuration = None,
		isRemote = false,
		isSystem = true)
}

private[wasp] object SolrLoggerIndex {

	val index_name = "logger_solr"

	import IndexModelBuilder._

	def apply(): IndexModel = IndexModelBuilder.forSolr
		                             						 .named(index_name)
																						 .config(Solr.Config(shards = 1,
																							 									 replica = 1))
	  																				 .schema(Solr.Schema(
																							 				Solr.Field("log_source", Solr.Type.String),
																							 				Solr.Field("log_level", Solr.Type.String),
																							 				Solr.Field("message", Solr.Type.String),
																							 				Solr.Field("timestamp", Solr.Type.TrieDate),
																							 				Solr.Field("thread", Solr.Type.String),
																							 				Solr.Field("cause", Solr.Type.String),
																							 				Solr.Field("stack_trace", Solr.Type.String)))
																 						 .build

}

private[wasp] object ElasticLoggerIndexModel {
  import org.json4s._

  import org.json4s.native.JsonMethods._
  import org.json4s.JsonDSL._

  import IndexModelBuilder._

  val index_name = "logger_elastic"

  import IndexModelBuilder._

  def apply(): IndexModel = IndexModelBuilder.forElastic
    .named(index_name)
    .config(Elastic.Config(shards = 1,
                           replica = 1))
    .schema(Elastic.Schema(indexElasticSchema))
    .build

  //noinspection ScalaUnnecessaryParentheses
  private lazy val indexElasticSchema = parse(
    """
        {
          "properties": {
            "log_source": {
              "type": "keyword"
            },
            "log_level": {
              "type": "keyword"
            },
            "message": {
               "type": "text"
             },
            "timestamp": {
              "type": "date"
            },
            "thread": {
              "type": "keyword"
            },
            "cause": {
              "type": "text"
            },
            "stack_trace": {
              "type": "text"
            }
          }
        }""").asInstanceOf[JObject]
}

private[wasp] object LoggerPipegraph {
	import SystemPipegraphs._

	val loggerPipegraphName = "LoggerPipegraph"

  private def writer: WriterModel = ConfigManager.getWaspConfig.defaultIndexedDatastore match {
    case "elastic" => WriterModel.elasticWriter(
	    "Write logging data to Elastic",
	    elasticLoggerIndex
    )
    case "solr" => WriterModel.solrWriter(
	    "Write logging data to Solr",
	    solrLoggerIndex
    )
  }

	def apply() = PipegraphModel(
		name = loggerPipegraphName,
		description = "System Logger Pipegraph",
		owner = "system",
		isSystem = true,
		creationTime = System.currentTimeMillis,

		legacyStreamingComponents = List(),
		structuredStreamingComponents = List(
      StructuredStreamingETLModel(
	      name = "write on index",
	      streamingInput = StreamingReaderModel.kafkaReader("Read logging data form Kafka", loggerTopic, Some(100)),
	      staticInputs = List.empty,
	      streamingOutput = writer,
	      mlModels = List(),
	      strategy = Some(StrategyModel(className = "it.agilelab.bigdata.wasp.consumers.spark.strategies.DropKafkaMetadata")),
	      triggerIntervalMs = None,
	      options = Map())
    ),

		rtComponents = List(),
		dashboard = None)
}



private[wasp] object TelemetryPipegraph {
	import SystemPipegraphs._

	val telemetryPipegraphName = "TelemetryPipegraph"

	private def writer: WriterModel = {
		val datastoreProductName = ConfigManager.getTelemetryConfig.writer
		val indexedDatastoreProduct =
			if (datastoreProductName == "default" | datastoreProductName == "")    GenericIndexProduct.getDefaultProductForThisCategory
			else if (datastoreProductName == ElasticProduct.getActualProductName)  ElasticProduct
			else if (datastoreProductName == SolrProduct.getActualProductName)     SolrProduct
			else throw new IllegalArgumentException(s"""Invalid datastore name in telemetry configuration: "$datastoreProductName"""")
		
		indexedDatastoreProduct match {
			case ElasticProduct =>
				WriterModel.elasticWriter(
					"Write telemetry data to Elastic",
					elasticTelemetryIndex
				)
			case SolrProduct =>
				WriterModel.solrWriter(
					"Write telemetry data to Solr",
					solrTelemetryIndex
				)
		}
	}

	def apply() = PipegraphModel(
		name = telemetryPipegraphName,
		description = "System Telemetry Pipegraph",
		owner = "system",
		isSystem = true,
		creationTime = System.currentTimeMillis,

		legacyStreamingComponents = List(),
		structuredStreamingComponents = List(
			StructuredStreamingETLModel(
				name = "write on index",
				streamingInput = StreamingReaderModel.kafkaReader("Read telemetry data from Kafka", telemetryTopic, Some(100)),
				staticInputs = List.empty,
				streamingOutput = writer,
				mlModels = List(),
				strategy = Some(StrategyModel(className = "it.agilelab.bigdata.wasp.consumers.spark.strategies.DropKafkaMetadata")),
				triggerIntervalMs = None,
				options = Map())
		),

		rtComponents = List(),
		dashboard = None)
}
