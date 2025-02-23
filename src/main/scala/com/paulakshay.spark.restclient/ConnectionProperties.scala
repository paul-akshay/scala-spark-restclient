package com.paulakshay.spark.restclient
case class ConnectionProperties(url: String,
                                connTimeoutInMillis: Int = 10000,
                                readTimeoutInMillis: Int = 10000,
                                tps: Option[Int] = Option.empty)
