# Scala Spark REST Client

This is a utility to provide convenient interaction with REST APIs in Spark using asynchronous HTTP requests. It allows you to make `GET` and `POST` requests in parallel across DataFrame partitions, while limiting the number of requests sent per second. The utility dynamically builds query parameters and headers based on DataFrame columns. Additionally, the responses (including JSON body and status codes) are appended to the original DataFrame.

## Features

- **Rate Limiting**: Ensures a maximum no of requests send to the downstream API. You can configure tps in ConnectionProperties for the same
- **Asynchronous Execution**: Uses `AsyncHttpClient` for non-blocking requests.
- **Convenient**: Provide your input as dataframe where each row represents the varying set of values for headers, query parameters, or request body fields. The output json reponse will be appended against each row which you can transform as per your need

## Example Usage

### ConnectionProperties Definition

```scala
val spark = SparkSession.builder
  .appName("Scala Spark REST Client")
  .master("local[*]")
  .getOrCreate()

val connectionProperties = ConnectionProperties(
  url = "http://api.example.com/endpoint",
  connTimeoutInMillis = 5000,
  readTimeoutInMillis = 10000,
  tps = Some(10) // Optional transactions per second
)
```
###  Example for Making GET Requests

```scala
import org.apache.spark.sql.Row

val inputDf = spark.createDataFrame(Seq(
  Row("value1", "value2"),
  Row("value3", "value4")
))

val paramColumns = Seq("param1", "param2")
val headerColumns = Seq("Authorization")

val restClient = new RestClient(connectionProperties)

val resultDf = restClient.doGet(inputDf, paramColumns, headerColumns)

```
###  Example for Making POST Requests

```scala
val inputSchema = StructType(Array(
  StructField("param1", StringType, nullable = true), 
  StructField("param2", StringType, nullable = true),
  StructField("header1", StringType, nullable = true),
  StructField("header2", StringType, nullable = true),
  StructField("queryparam", StringType, nullable = true)  // Renamed param3 and param4 to queryparam
))

// Create sample data for the DataFrame
val inputRows = Seq(
  Row("value1", "value2", "headerValue1", "application/json", "queryParamvalue1"),
  Row("value3", "value4", "headerValue2", "application/json", "queryParamvalue2"),
  Row("value5", "value6", "headerValue3", "application/json", "queryParamvalue3")
)

val inputDf = spark.createDataFrame(spark.sparkContext.parallelize(data), schema)

//param1 and param2 will be used to construct the request body
val requestBodyBuilderFn: Row => String = row => {
  val param1 = row.getAs[String]("param1")
  val param2 = row.getAs[String]("param2")
  s"""{"param1": "$param1", "param2": "$param2"}"""
}

val paramColumns = Seq("param1", "param2", "queryparam")  // Using queryparam instead of param3, param4
val headerColumns = Seq("header1", "header2")

val resultDfPost = restClient.doPost(inputDf, paramColumns, headerColumns, requestBodyBuilderFn)

```

- 
## Future Enhancements
- Ability to control no of requests sent from each executor. Currently it is limited to 1
- Support for Datasets
