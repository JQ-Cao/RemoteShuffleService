package com.aliyun.emr.rss.service.deploy.worker

import com.aliyun.emr.rss.common.RssConf
import com.aliyun.emr.rss.common.meta.{PartitionLocationInfo, WorkerInfo}
import com.aliyun.emr.rss.common.metrics.MetricsSystem
import com.aliyun.emr.rss.common.metrics.source.{JVMCPUSource, JVMSource, NetWorkSource}
import com.aliyun.emr.rss.common.network.TransportContext
import com.aliyun.emr.rss.common.network.buffer.NettyManagedBuffer
import com.aliyun.emr.rss.common.network.client.{RpcResponseCallback, TransportClient, TransportClientFactory}
import com.aliyun.emr.rss.common.network.protocol.{PushData, PushMergedData}
import com.aliyun.emr.rss.common.network.server.{BaseMessageHandler, MemoryTracker}
import com.aliyun.emr.rss.common.network.util.{JavaUtils, TransportConf}
import com.aliyun.emr.rss.common.protocol.message.StatusCode
import com.aliyun.emr.rss.common.protocol.{PartitionLocation, PartitionSplitMode}
import com.aliyun.emr.rss.common.unsafe.Platform
import com.aliyun.emr.rss.common.util.{ThreadUtils, Utils}
import io.netty.buffer.Unpooled
import org.mockito.MockitoSugar._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import java.io.{File, IOException}
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, ThreadPoolExecutor}
import java.util.concurrent.atomic.AtomicBoolean

class PushDataHandlerSuite extends AnyFunSuite with BeforeAndAfterAll {
  private val LOG = LoggerFactory.getLogger(classOf[PushDataHandlerSuite])

  val rssConf = new RssConf()
  val metricsSystem = MetricsSystem.createMetricsSystem("worker", rssConf, WorkerSource.ServletPath)
  val workerSource = {
    val source = new WorkerSource(rssConf)
    metricsSystem.registerSource(source)
    metricsSystem.registerSource(new NetWorkSource(rssConf, MetricsSystem.ROLE_WOKRER))
    metricsSystem.registerSource(new JVMSource(rssConf, MetricsSystem.ROLE_WOKRER))
    metricsSystem.registerSource(new JVMCPUSource(rssConf, MetricsSystem.ROLE_WOKRER))
    source
  }

  val partitionLocationInfo = new PartitionLocationInfo
  val shuffleMapperAttempts = new ConcurrentHashMap[String, Array[Int]]()
  val replicateThreadPool: ThreadPoolExecutor = ThreadUtils.newDaemonCachedThreadPool(
    "worker-replicate-data", RssConf.workerReplicateNumThreads(rssConf))
  val unavailablePeers = new ConcurrentHashMap[WorkerInfo, Long]()

  val handler = new BaseMessageHandler
  val conf: TransportConf = Utils.fromRssConf(rssConf, "push")
  val context = new TransportContext(conf, handler)
  val pushClientFactory: TransportClientFactory = context.createClientFactory
  val server = context.createServer(10086)
  val registered = new AtomicBoolean(true)

  val worker: Worker = mock[Worker]
  when(worker.workerSource).thenReturn(workerSource)
  when(worker.partitionLocationInfo).thenReturn(partitionLocationInfo)
  when(worker.shuffleMapperAttempts).thenReturn(shuffleMapperAttempts)
  when(worker.replicateThreadPool).thenReturn(replicateThreadPool)
  when(worker.unavailablePeers).thenReturn(unavailablePeers)
  when(worker.pushClientFactory).thenReturn(pushClientFactory)
  when(worker.registered).thenReturn(registered)

  val pushDataHandler = new PushDataHandler
  pushDataHandler.init(worker)
  val client: TransportClient = pushClientFactory.createClient("127.0.0.1", 10086)

  private var tempDir: File = null
  private var flusher: DiskFlusher = null
  private val CHUNK_SIZE = 1024
  val FLUSH_BUFFER_SIZE_LIMIT: Int = 1
  val splitMode = PartitionSplitMode.soft

  val shuffleKey = "local-1"
  var masterLocation: PartitionLocation = null
  var writer: FileWriter = null
  var writer2: FileWriter = null

  override def beforeAll(): Unit = {
    tempDir = Utils.createTempDir(System.getProperty("java.io.tmpdir"))
    flusher = new DiskFlusher(tempDir, 100, workerSource, DeviceMonitor.EmptyMonitor(), 2)
    MemoryTracker.initialize(0.8, 0.9, 0.5, 0.6, 10, 10, 10)
    val file = getTemporaryFile
    writer = new FileWriter(file, flusher, file.getParentFile, CHUNK_SIZE, FLUSH_BUFFER_SIZE_LIMIT, workerSource, rssConf, DeviceMonitor.EmptyMonitor(), 10, splitMode)
    masterLocation = new PartitionLocation(
      1,
      1,
      "127.0.0.1",
      1234,
      1235,
      1235,
      1237,
      PartitionLocation.Mode.Master)
    val wp = new WorkingPartition(masterLocation, writer)
    partitionLocationInfo.addMasterPartition(shuffleKey, wp)

    val file2 = getTemporaryFile
    writer2 = new FileWriter(file2, flusher, file2.getParentFile, CHUNK_SIZE, FLUSH_BUFFER_SIZE_LIMIT, workerSource, rssConf, DeviceMonitor.EmptyMonitor(), 10, splitMode)
    val masterLocation2 = new PartitionLocation(
      3,
      1,
      "127.0.0.1",
      1234,
      1235,
      1235,
      1237,
      PartitionLocation.Mode.Master)
    partitionLocationInfo.addMasterPartition(shuffleKey, new WorkingPartition(masterLocation2, writer2))

    super.beforeAll()
  }

  private def makePushData(end: Boolean = false): (PushData, Long) = {
    val BATCH_HEADER_SIZE = 4 * 4
    val pushString = "hello world"
    val body = new Array[Byte](BATCH_HEADER_SIZE + pushString.getBytes.length)
    Platform.putInt(body, Platform.BYTE_ARRAY_OFFSET, 1)
    Platform.putInt(body, Platform.BYTE_ARRAY_OFFSET + 4, 1)
    Platform.putInt(body, Platform.BYTE_ARRAY_OFFSET + 8, 1)
    Platform.putInt(body, Platform.BYTE_ARRAY_OFFSET + 12, pushString.getBytes.length)
    System.arraycopy(pushString.getBytes, 0, body, BATCH_HEADER_SIZE, pushString.getBytes.length)
    val buffer: NettyManagedBuffer = new NettyManagedBuffer(Unpooled.wrappedBuffer(body))
    val pushData: PushData = if (end) {
      new PushData(PartitionLocation.Mode.Master.mode, shuffleKey, "2-1", buffer)
    } else {
      new PushData(PartitionLocation.Mode.Master.mode, shuffleKey, masterLocation.getUniqueId, buffer)
    }
    pushData.requestId = 123
    (pushData, buffer.size())
  }

  private def makeMergeData: (PushMergedData, Long) = {
    val BATCH_HEADER_SIZE = 4 * 4
    val pushString = "hello world"
    val body1 = new Array[Byte](BATCH_HEADER_SIZE + pushString.getBytes.length)
    Platform.putInt(body1, Platform.BYTE_ARRAY_OFFSET, 1)
    Platform.putInt(body1, Platform.BYTE_ARRAY_OFFSET + 4, 1)
    Platform.putInt(body1, Platform.BYTE_ARRAY_OFFSET + 8, 1)
    Platform.putInt(body1, Platform.BYTE_ARRAY_OFFSET + 12, pushString.getBytes.length)
    System.arraycopy(pushString.getBytes, 0, body1, BATCH_HEADER_SIZE, pushString.getBytes.length)
    val body2 = new Array[Byte](BATCH_HEADER_SIZE + pushString.getBytes.length)
    Platform.putInt(body2, Platform.BYTE_ARRAY_OFFSET, 1)
    Platform.putInt(body2, Platform.BYTE_ARRAY_OFFSET + 4, 1)
    Platform.putInt(body2, Platform.BYTE_ARRAY_OFFSET + 8, 1)
    Platform.putInt(body2, Platform.BYTE_ARRAY_OFFSET + 12, pushString.getBytes.length)
    System.arraycopy(pushString.getBytes, 0, body2, BATCH_HEADER_SIZE, pushString.getBytes.length)
    val byteBuf = Unpooled.compositeBuffer
    byteBuf.addComponent(true, Unpooled.wrappedBuffer(body1))
    byteBuf.addComponent(true, Unpooled.wrappedBuffer(body2))
    val buffer: NettyManagedBuffer = new NettyManagedBuffer(Unpooled.wrappedBuffer(byteBuf))
    val partitionUniqueIds = Array("1-1", "3-1")
    val offsets = Array(0, 27)
    val pushMergeData = new PushMergedData(PartitionLocation.Mode.Master.mode, shuffleKey, partitionUniqueIds, offsets, buffer)
    pushMergeData.requestId = 123
    (pushMergeData, buffer.size())
  }

  override def afterAll(): Unit = {
    super.afterAll()
    if (client!= null) {
      client.close()
    }
    if (server != null) {
      server.close()
    }
    if (tempDir != null) try {
      JavaUtils.deleteRecursively(tempDir)
      tempDir = null
    } catch {
      case e: IOException =>
        LOG.error("Failed to delete temp dir.", e)
    }
  }

  test("push data success") {
    var res = false
    server.getChannelHandler.getResponseHandler.addRpcRequest(123, new RpcResponseCallback {
      override def onSuccess(response: ByteBuffer): Unit = {
        res = true
        assert(response.remaining() == 0)
      }

      override def onFailure(e: Throwable): Unit = {
      }
    })
    val (pushData, bufferSize) = makePushData()
    pushDataHandler.receive(client, pushData)
    Thread.sleep(300)
    assert(res)
  }

  test("merge data success") {
    var res = false
    server.getChannelHandler.getResponseHandler.addRpcRequest(123, new RpcResponseCallback {
      override def onSuccess(response: ByteBuffer): Unit = {
        res = true
        assert(response.remaining() == 0)
      }

      override def onFailure(e: Throwable): Unit = {
      }
    })
    val (mergePushData, bufferSize) = makeMergeData
    pushDataHandler.receive(client, mergePushData)
    Thread.sleep(300)
    assert(res)
  }

  test("push soft split") {
    val (pushData1, bufferSize1) = makePushData()
    pushDataHandler.receive(client, pushData1)
    Thread.sleep(100)
    partitionLocationInfo.getMasterLocation(shuffleKey, pushData1.partitionUniqueId) match {
      case Some(workingPartition: WorkingPartition) => workingPartition.getFileWriter.flushOnMemoryPressure()
    }
    var res = false
    server.getChannelHandler.getResponseHandler.addRpcRequest(1234, new RpcResponseCallback {
      override def onSuccess(response: ByteBuffer): Unit = {
        res = true
        assert(response.get() == StatusCode.SoftSplit.getValue)
      }

      override def onFailure(e: Throwable): Unit = {
      }
    })
    val (pushData2, bufferSize2) = makePushData()
    pushData2.requestId = 1234
    pushDataHandler.receive(client, pushData2)
    Thread.sleep(300)
    assert(res)
  }

  test("partition not found") {
    val (pushData, _) = makePushData(true)
    var res = false
    server.getChannelHandler.getResponseHandler.addRpcRequest(123, new RpcResponseCallback {
      override def onSuccess(response: ByteBuffer): Unit = {
        res = true
      }

      override def onFailure(e: Throwable): Unit = {
        assert("PushDataFailPartitionNotFound".equals(e.getMessage))
      }
    })
    pushDataHandler.receive(client, pushData)
    Thread.sleep(300)
    assert(!res)
  }

  test("master push fail") {
    partitionLocationInfo.getMasterLocation(shuffleKey, masterLocation.getUniqueId) match {
      case Some(workingPartition: WorkingPartition) => workingPartition.getFileWriter.destroy()
      case _ =>
    }
    val (pushData, _) = makePushData()
    var res = false
    server.getChannelHandler.getResponseHandler.addRpcRequest(123, new RpcResponseCallback {
      override def onSuccess(response: ByteBuffer): Unit = {
        res = true
      }

      override def onFailure(e: Throwable): Unit = {
        assert("PushDataFailMain".equals(e.getMessage))
      }
    })
    pushDataHandler.receive(client, pushData)
    Thread.sleep(300)
    assert(!res)
  }


  test("stage end") {
    val (pushData, _) = makePushData(true)
    shuffleMapperAttempts.put(shuffleKey, Array(1,1))
    var res = false
    server.getChannelHandler.getResponseHandler.addRpcRequest(123, new RpcResponseCallback {
      override def onSuccess(response: ByteBuffer): Unit = {
        res = true
        assert(response.get() == StatusCode.StageEnded.getValue)
      }

      override def onFailure(e: Throwable): Unit = {
      }
    })
    pushDataHandler.receive(client, pushData)
    Thread.sleep(300)
    assert(res)
  }

  @throws[IOException]
  private def getTemporaryFile = {
    val filename = UUID.randomUUID.toString
    val temporaryFile = new File(tempDir, filename)
    temporaryFile.createNewFile
    temporaryFile
  }
}
