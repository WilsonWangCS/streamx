/*
 * Copyright 2019 The StreamX Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streamxhub.streamx.flink.connector.clickhouse.internal

import com.streamxhub.streamx.common.util.{Logger, ThreadUtils}
import com.streamxhub.streamx.flink.connector.clickhouse.conf.ClickHouseHttpConfig
import com.streamxhub.streamx.flink.connector.clickhouse.internal
import com.streamxhub.streamx.flink.connector.failover.{SinkRequest, SinkWriter}
import org.asynchttpclient.{AsyncHttpClient, DefaultAsyncHttpClientConfig, Dsl}

import java.util.concurrent._
import scala.collection.mutable.ListBuffer
import scala.collection.JavaConversions._

case class ClickHouseSinkWriter(clickHouseConfig: ClickHouseHttpConfig) extends SinkWriter with Logger {
  private val callbackServiceFactory = ThreadUtils.threadFactory("ClickHouse-writer-callback-executor")
  private val threadFactory: ThreadFactory = ThreadUtils.threadFactory("ClickHouse-writer")

  var callbackService: ExecutorService = new ThreadPoolExecutor(
    math.max(Runtime.getRuntime.availableProcessors / 4, 2),
    Integer.MAX_VALUE,
    60L,
    TimeUnit.SECONDS,
    new LinkedBlockingQueue[Runnable],
    callbackServiceFactory
  )

  var tasks: ListBuffer[ClickHouseWriterTask] = ListBuffer[ClickHouseWriterTask]()
  var recordQueue: BlockingQueue[SinkRequest] = new LinkedBlockingQueue[SinkRequest](clickHouseConfig.queueCapacity)
  var asyncHttpClient: AsyncHttpClient = Dsl.asyncHttpClient(
    new DefaultAsyncHttpClientConfig.Builder()
      .setRequestTimeout(clickHouseConfig.sinkOption.requestTimeout.get())
      .setConnectTimeout(clickHouseConfig.sinkOption.connectTimeout.get())
      .setMaxRequestRetry(clickHouseConfig.sinkOption.maxRequestRetry.get())
      .setMaxConnections(clickHouseConfig.sinkOption.maxConnections.get())
      .build()
  )
  var service: ExecutorService = Executors.newFixedThreadPool(clickHouseConfig.numWriters, threadFactory)

  for (i <- 0 until clickHouseConfig.numWriters) {
    val task = internal.ClickHouseWriterTask(i, clickHouseConfig, asyncHttpClient, recordQueue, callbackService)
    tasks.add(task)
    service.submit(task)
  }

  def write(request: SinkRequest): Unit = {
    try {
      recordQueue.put(request)
    } catch {
      case e: InterruptedException =>
        logError(s"Interrupted error while putting data to queue,error:$e")
        Thread.currentThread.interrupt()
        throw new RuntimeException(e)
    }
  }

  override def close(): Unit = {
    logInfo("Closing ClickHouse-writer...")
    tasks.foreach(_.close())
    ThreadUtils.shutdownExecutorService(service)
    ThreadUtils.shutdownExecutorService(callbackService)
    asyncHttpClient.close()
    logInfo(s"${classOf[ClickHouseSinkWriter].getSimpleName} is closed")
  }

}
