package com.paulakshay.spark.restclient

import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types._
import org.asynchttpclient.{DefaultAsyncHttpClient, DefaultAsyncHttpClientConfig, ListenableFuture, RequestBuilder, Response}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Success

class RestClientSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  implicit val spark: SparkSession = SparkSession.builder()
    .appName("RestClientTest")
    .master("local[*]")
    .getOrCreate()

  import spark.implicits._

  val logger = LoggerFactory.getLogger(this.getClass)

  "RestClient" should "perform a GET request and append response to DataFrame" in {
    val connectionProperties = ConnectionProperties("http://example.com", 1000, 1000, Some(1))
    val restClient = new RestClient(connectionProperties)

    val inputDf = Seq(
      ("1", "value1", "header1Value"),
      ("2", "value2", "header2Value")
    ).toDF("id", "param1", "header1")

    val mockHttpClient = mock[DefaultAsyncHttpClient]
    val mockFuture = mock[ListenableFuture[Response]]
    val mockResponse = mock[Response]

    when(mockResponse.getResponseBody).thenReturn("{\"result\": \"success\"}")
    when(mockResponse.getStatusCode).thenReturn(200)
    when(mockFuture.get()).thenReturn(mockResponse)
    when(mockHttpClient.executeRequest(any())).thenReturn(mockFuture)

    val resultDf = restClient.doGet(inputDf, Seq("param1"), Seq("header1"))

    val result = resultDf.collect()
    result.length shouldBe 2
    result(0).getString(3) shouldBe "{\"result\": \"success\"}"
    result(0).getString(4) shouldBe "200"
  }

  it should "perform a POST request and append response to DataFrame" in {
    val connectionProperties = ConnectionProperties("http://example.com", 1000, 1000, Some(1))
    val restClient = new RestClient(connectionProperties)

    val inputDf = Seq(
      ("1", "value1", "header1Value"),
      ("2", "value2", "header2Value")
    ).toDF("id", "param1", "header1")

    val mockHttpClient = mock[DefaultAsyncHttpClient]
    val mockFuture = mock[ListenableFuture[Response]]
    val mockResponse = mock[Response]

    when(mockResponse.getResponseBody).thenReturn("{\"result\": \"post success\"}")
    when(mockResponse.getStatusCode).thenReturn(201)
    when(mockFuture.get()).thenReturn(mockResponse)
    when(mockHttpClient.executeRequest(any())).thenReturn(mockFuture)

    val requestBodyFn: Row => String = row => s"""{"id": "${row.getString(0)}", "param": "${row.getString(1)}"}"""
    val resultDf = restClient.doPost(inputDf, Seq("param1"), Seq("header1"), requestBodyFn)

    val result = resultDf.collect()
    result.length shouldBe 2
    result(0).getString(3) shouldBe "{\"result\": \"post success\"}"
    result(0).getString(4) shouldBe "201"
  }

  it should "handle request failures and append empty response and status code" in {
    val connectionProperties = ConnectionProperties("http://example.com", 1000, 1000, Some(1))
    val restClient = new RestClient(connectionProperties)

    val inputDf = Seq(
      ("1", "value1", "header1Value")
    ).toDF("id", "param1", "header1")

    val mockHttpClient = mock[DefaultAsyncHttpClient]
    val mockFuture = mock[ListenableFuture[Response]]

    when(mockFuture.get()).thenThrow(new RuntimeException("Request failed"))
    when(mockHttpClient.executeRequest(any())).thenReturn(mockFuture)

    val resultDf = restClient.doGet(inputDf, Seq("param1"), Seq("header1"))

    val result = resultDf.collect()
    result(0).getString(3) shouldBe ""
    result(0).getString(4) shouldBe ""
  }

  it should "handle multiple partitions and rate limiting" in {
    val connectionProperties = ConnectionProperties("http://example.com", 1000, 1000, Some(2))
    val restClient = new RestClient(connectionProperties)

    val inputDf = (1 to 4).map(i => (i.toString, s"value$i", s"header$i")).toDF("id", "param1", "header1")

    val mockHttpClient = mock[DefaultAsyncHttpClient]
    val mockFuture = mock[ListenableFuture[Response]]
    val mockResponse = mock[Response]

    when(mockResponse.getResponseBody).thenReturn("{\"result\": \"success\"}")
    when(mockResponse.getStatusCode).thenReturn(200)
    when(mockFuture.get()).thenReturn(mockResponse)
    when(mockHttpClient.executeRequest(any())).thenReturn(mockFuture)

    val resultDf = restClient.doGet(inputDf, Seq("param1"), Seq("header1"))

    val result = resultDf.collect()
    result.length shouldBe 4
    result.foreach(row => {
      row.getString(3) shouldBe "{\"result\": \"success\"}"
      row.getString(4) shouldBe "200"
    })
  }
}
