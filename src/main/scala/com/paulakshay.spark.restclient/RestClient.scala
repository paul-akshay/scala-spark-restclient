import com.paulakshay.spark.restclient.ConnectionProperties
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.asynchttpclient.{DefaultAsyncHttpClient, DefaultAsyncHttpClientConfig, ListenableFuture, RequestBuilder,
  Response}
import org.spark_project.guava.util.concurrent.RateLimiter
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * A REST client utility for interacting with REST APIs in Spark using asynchronous HTTP requests.
 * Each row in the input DataFrame is used to construct a request, and the response is appended to the row
 *
 * This class provides methods to make `GET` and `POST` requests in parallel across DataFrame partitions,
 * rate-limiting the number of requests to one per second. It supports dynamic construction of query
 * parameters and headers from DataFrame columns. Responses, including JSON body and status codes,
 * are appended to the original DataFrame.
 *
 * Key features:
 * - **Rate Limiting**: Ensures a maximum of 1 request per second using `RateLimiter` from Guava.
 * - **Asynchronous Execution**: Uses `AsyncHttpClient` for non-blocking requests.
 * - **Error Handling**: Logs errors in case of failed requests and appends empty response and status code.
 * - **Scalable**: Utilizes Spark partitioning to handle large datasets efficiently.
 *
 * @param connectionProperties Connection properties (URL, timeout settings, and TPS limit) used for making the requests.
 * @param spark The implicit `SparkSession` instance for DataFrame operations.
 */
class RestClient(connectionProperties: ConnectionProperties)(implicit spark: SparkSession) {

  import spark.implicits._ //scalastyle:ignore

  private val logger = LoggerFactory.getLogger(this.getClass)

  // Method for handling GET requests
  def doGet(inputDf: DataFrame, paramColumns: Seq[String], headerColumns: Seq[String]): DataFrame = {
    processRequest(inputDf, paramColumns, headerColumns, None)
  }

  // Method for handling POST requests
  def doPost(inputDf: DataFrame, paramColumns: Seq[String], headerColumns: Seq[String],
             requestBodyBuilderFn: Row => String): DataFrame = {
    processRequest(inputDf, paramColumns, headerColumns, Some(requestBodyBuilderFn))
  }

  private def processRequest(
                              inputDf: DataFrame,
                              paramColumns: Seq[String],
                              headerColumns: Seq[String],
                              requestBodyBuilderFn: Option[Row => String]
                            ): DataFrame = {

    // Set the TPS (transactions per second) value based on connection properties or partition size
    val tpsValue = connectionProperties.tps.getOrElse(inputDf.rdd.getNumPartitions)
    val partitionedDf = inputDf.repartition(tpsValue)

    val responseSchema = StructType(inputDf.schema.fields ++ Array(
      StructField("responseJson", StringType, nullable = true),
      StructField("responseStatusCode", StringType, nullable = true)
    ))

    val processedRdd = partitionedDf.rdd.mapPartitions { partitionIterator =>
      val rateLimiter = RateLimiter.create(1.0)  // Limit requests to 1 per second from executor. This can be adjusted
      val httpClientConfig = new DefaultAsyncHttpClientConfig.Builder()
        .setConnectTimeout(connectionProperties.connTimeoutInMillis)
        .setReadTimeout(connectionProperties.readTimeoutInMillis)
        .build()

      val httpClient = new DefaultAsyncHttpClient(httpClientConfig)

      val callables = partitionIterator.map { row =>
        rateLimiter.acquire()
        val futureResponse = executeRequest(row, paramColumns, headerColumns, requestBodyBuilderFn, httpClient)
        (row, futureResponse)
      }.toSeq

      callables.map(handleResponse).iterator
    }

    spark.createDataFrame(processedRdd, responseSchema)
  }

  private def executeRequest(
                              row: Row,
                              paramColumns: Seq[String],
                              headerColumns: Seq[String],
                              requestBodyBuilderFn: Option[Row => String],
                              client: DefaultAsyncHttpClient
                            ): ListenableFuture[Response] = {
    val params = paramColumns.flatMap(col => Option(row.getAs[String](col)).map(value => s"$col=$value")).mkString("&")
    val headers = headerColumns.flatMap(col => Option(row.getAs[String](col)).map(value => col -> value)).toMap

    val requestBuilder = requestBodyBuilderFn match {
      case Some(bodyFn) =>
        new RequestBuilder("POST").setUrl(connectionProperties.url).setBody(bodyFn(row))
      case None =>
        new RequestBuilder("GET").setUrl(s"${connectionProperties.url}?$params")
    }

    headers.foreach { case (key, value) => requestBuilder.addHeader(key, value) }
    client.executeRequest(requestBuilder.build())
  }

  private def handleResponse(callable: (Row, ListenableFuture[Response])): Row = {
    val (row, futureResponse) = callable
    val (jsonResponse, statusCode) = Try {
      val response = futureResponse.get()
      (response.getResponseBody, response.getStatusCode.toString)
    }.recover {
      case ex: Exception =>
        logger.error(s"Request failed for row: ${row.mkString(", ")}", ex)
        ("", "")
    }.get

    Row.fromSeq(row.toSeq :+ jsonResponse :+ statusCode)
  }
}
