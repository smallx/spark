/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.connect.client

import java.io.{ByteArrayInputStream, InputStream, PrintStream}
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.{CheckedInputStream, CRC32}

import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import Artifact._
import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver
import org.apache.commons.codec.digest.DigestUtils.sha256Hex
import org.apache.commons.lang3.StringUtils

import org.apache.spark.connect.proto
import org.apache.spark.connect.proto.AddArtifactsResponse
import org.apache.spark.connect.proto.AddArtifactsResponse.ArtifactSummary
import org.apache.spark.util.{MavenUtils, SparkFileUtils, SparkThreadUtils}

/**
 * The Artifact Manager is responsible for handling and transferring artifacts from the local
 * client to the server (local/remote).
 * @param clientConfig
 *   The configuration of the client that the artifact manager operates in.
 * @param sessionId
 *   An unique identifier of the session which the artifact manager belongs to.
 * @param bstub
 *   A blocking stub to the server.
 * @param stub
 *   An async stub to the server.
 */
class ArtifactManager(
    clientConfig: SparkConnectClient.Configuration,
    sessionId: String,
    bstub: CustomSparkConnectBlockingStub,
    stub: CustomSparkConnectStub) {
  // Using the midpoint recommendation of 32KiB for chunk size as specified in
  // https://github.com/grpc/grpc.github.io/issues/371.
  private val CHUNK_SIZE: Int = 32 * 1024

  private[this] val classFinders = new CopyOnWriteArrayList[ClassFinder]

  /**
   * Register a [[ClassFinder]] for dynamically generated classes.
   */
  def registerClassFinder(finder: ClassFinder): Unit = classFinders.add(finder)

  /**
   * Add a single artifact to the session.
   *
   * Currently only local files with extensions .jar and .class are supported.
   */
  def addArtifact(path: String): Unit = {
    addArtifact(SparkFileUtils.resolveURI(path))
  }

  private def parseArtifacts(uri: URI): Seq[Artifact] = {
    // Currently only local files with extensions .jar and .class are supported.
    uri.getScheme match {
      case "file" =>
        val path = Paths.get(uri)
        val artifact = path.getFileName.toString match {
          case jar if jar.endsWith(".jar") =>
            newJarArtifact(path.getFileName, new LocalFile(path))
          case cf if cf.endsWith(".class") =>
            newClassArtifact(path.getFileName, new LocalFile(path))
          case other =>
            throw new UnsupportedOperationException(s"Unsupported file format: $other")
        }
        Seq[Artifact](artifact)

      case "ivy" =>
        newIvyArtifacts(uri)

      case other =>
        throw new UnsupportedOperationException(s"Unsupported scheme: $other")
    }
  }

  /**
   * Add a single artifact to the session.
   *
   * Currently it supports local files with extensions .jar and .class and Apache Ivy URIs
   */
  def addArtifact(uri: URI): Unit = addArtifacts(parseArtifacts(uri))

  /**
   * Add multiple artifacts to the session.
   *
   * Currently it supports local files with extensions .jar and .class and Apache Ivy URIs
   */
  def addArtifacts(uris: Seq[URI]): Unit = addArtifacts(uris.flatMap(parseArtifacts))

  private[client] def isCachedArtifact(hash: String): Boolean = {
    val artifactName = CACHE_PREFIX + "/" + hash
    val request = proto.ArtifactStatusesRequest
      .newBuilder()
      .setUserContext(clientConfig.userContext)
      .setClientType(clientConfig.userAgent)
      .setSessionId(sessionId)
      .addAllNames(Arrays.asList(artifactName))
      .build()
    val statuses = bstub.artifactStatus(request).getStatusesMap
    if (statuses.containsKey(artifactName)) {
      statuses.get(artifactName).getExists
    } else false
  }

  /**
   * Cache the give blob at the session.
   */
  def cacheArtifact(blob: Array[Byte]): String = {
    val hash = sha256Hex(blob)
    if (!isCachedArtifact(hash)) {
      addArtifacts(newCacheArtifact(hash, new InMemory(blob)) :: Nil)
    }
    hash
  }

  /**
   * Upload all class file artifacts from the local REPL(s) to the server.
   *
   * The registered [[ClassFinder]]s are traversed to retrieve the class file artifacts.
   */
  private[client] def uploadAllClassFileArtifacts(): Unit = {
    addArtifacts(classFinders.asScala.flatMap(_.findClasses()))
  }

  private[sql] def addClassDir(base: Path): Unit = {
    if (!Files.isDirectory(base)) {
      return
    }
    val builder = Seq.newBuilder[Artifact]
    val stream = Files.walk(base)
    try {
      stream.forEach { path =>
        if (Files.isRegularFile(path) && path.toString.endsWith(".class")) {
          builder += Artifact.newClassArtifact(base.relativize(path), new LocalFile(path))
        }
      }
    } finally {
      stream.close()
    }
    addArtifacts(builder.result())
  }

  /**
   * Add a number of artifacts to the session.
   */
  private[client] def addArtifacts(artifacts: Iterable[Artifact]): Unit = {
    if (artifacts.isEmpty) {
      return
    }

    val promise = Promise[Seq[ArtifactSummary]]()
    val responseHandler = new StreamObserver[proto.AddArtifactsResponse] {
      private val summaries = mutable.Buffer.empty[ArtifactSummary]
      override def onNext(v: AddArtifactsResponse): Unit = {
        v.getArtifactsList.forEach { summary =>
          summaries += summary
        }
      }
      override def onError(throwable: Throwable): Unit = {
        promise.failure(throwable)
      }
      override def onCompleted(): Unit = {
        promise.success(summaries.toSeq)
      }
    }
    val stream = stub.addArtifacts(responseHandler)
    val currentBatch = mutable.Buffer.empty[Artifact]
    var currentBatchSize = 0L

    def addToBatch(dep: Artifact, size: Long): Unit = {
      currentBatch += dep
      currentBatchSize += size
    }

    def writeBatch(): Unit = {
      addBatchedArtifacts(currentBatch.toSeq, stream)
      currentBatch.clear()
      currentBatchSize = 0
    }

    artifacts.iterator.foreach { artifact =>
      val data = artifact.storage
      val size = data.size
      if (size > CHUNK_SIZE) {
        // Payload can either be a batch OR a single chunked artifact. Write batch if non-empty
        // before chunking current artifact.
        if (currentBatch.nonEmpty) {
          writeBatch()
        }
        addChunkedArtifact(artifact, stream)
      } else {
        if (currentBatchSize + size > CHUNK_SIZE) {
          writeBatch()
        }
        addToBatch(artifact, size)
      }
    }
    if (currentBatch.nonEmpty) {
      writeBatch()
    }
    stream.onCompleted()
    SparkThreadUtils.awaitResult(promise.future, Duration.Inf)
    // TODO(SPARK-42658): Handle responses containing CRC failures.
  }

  /**
   * Add a batch of artifacts to the stream. All the artifacts in this call are packaged into a
   * single [[proto.AddArtifactsRequest]].
   */
  private def addBatchedArtifacts(
      artifacts: Seq[Artifact],
      stream: StreamObserver[proto.AddArtifactsRequest]): Unit = {
    val builder = proto.AddArtifactsRequest
      .newBuilder()
      .setUserContext(clientConfig.userContext)
      .setClientType(clientConfig.userAgent)
      .setSessionId(sessionId)
    artifacts.foreach { artifact =>
      val in = new CheckedInputStream(artifact.storage.stream, new CRC32)
      try {
        val data = proto.AddArtifactsRequest.ArtifactChunk
          .newBuilder()
          .setData(ByteString.readFrom(in))
          .setCrc(in.getChecksum.getValue)

        builder.getBatchBuilder
          .addArtifactsBuilder()
          .setName(artifact.path.toString)
          .setData(data)
          .build()
      } catch {
        case NonFatal(e) =>
          stream.onError(e)
          throw e
      } finally {
        in.close()
      }
    }
    stream.onNext(builder.build())
  }

  /**
   * Read data from an [[InputStream]] in pieces of `chunkSize` bytes and convert to
   * protobuf-compatible [[ByteString]].
   * @param in
   * @return
   */
  private def readNextChunk(in: InputStream): ByteString = {
    val buf = new Array[Byte](CHUNK_SIZE)
    var bytesRead = 0
    var count = 0
    while (count != -1 && bytesRead < CHUNK_SIZE) {
      count = in.read(buf, bytesRead, CHUNK_SIZE - bytesRead)
      if (count != -1) {
        bytesRead += count
      }
    }
    if (bytesRead == 0) ByteString.empty()
    else ByteString.copyFrom(buf, 0, bytesRead)
  }

  /**
   * Add a artifact in chunks to the stream. The artifact's data is spread out over multiple
   * [[proto.AddArtifactsRequest requests]].
   */
  private def addChunkedArtifact(
      artifact: Artifact,
      stream: StreamObserver[proto.AddArtifactsRequest]): Unit = {
    val builder = proto.AddArtifactsRequest
      .newBuilder()
      .setUserContext(clientConfig.userContext)
      .setClientType(clientConfig.userAgent)
      .setSessionId(sessionId)

    val in = new CheckedInputStream(artifact.storage.stream, new CRC32)
    try {
      // First RPC contains the `BeginChunkedArtifact` payload (`begin_chunk`).
      // Subsequent RPCs contains the `ArtifactChunk` payload (`chunk`).
      val artifactChunkBuilder = proto.AddArtifactsRequest.ArtifactChunk.newBuilder()
      var dataChunk = readNextChunk(in)
      // Integer division that rounds up to the nearest whole number.
      def getNumChunks(size: Long): Long = (size + (CHUNK_SIZE - 1)) / CHUNK_SIZE

      builder.getBeginChunkBuilder
        .setName(artifact.path.toString)
        .setTotalBytes(artifact.size)
        .setNumChunks(getNumChunks(artifact.size))
        .setInitialChunk(
          artifactChunkBuilder
            .setData(dataChunk)
            .setCrc(in.getChecksum.getValue))
      stream.onNext(builder.build())
      in.getChecksum.reset()
      builder.clearBeginChunk()

      dataChunk = readNextChunk(in)
      // Consume stream in chunks until there is no data left to read.
      while (!dataChunk.isEmpty) {
        artifactChunkBuilder.setData(dataChunk).setCrc(in.getChecksum.getValue)
        builder.setChunk(artifactChunkBuilder.build())
        stream.onNext(builder.build())
        in.getChecksum.reset()
        builder.clearChunk()
        dataChunk = readNextChunk(in)
      }
    } catch {
      case NonFatal(e) =>
        stream.onError(e)
        throw e
    } finally {
      in.close()
    }
  }
}

class Artifact private (val path: Path, val storage: LocalData) {
  require(!path.isAbsolute, s"Bad path: $path")

  lazy val size: Long = storage match {
    case localData: LocalData => localData.size
  }
}

object Artifact {
  val CLASS_PREFIX: Path = Paths.get("classes")
  val JAR_PREFIX: Path = Paths.get("jars")
  val CACHE_PREFIX: Path = Paths.get("cache")

  def newJarArtifact(fileName: Path, storage: LocalData): Artifact = {
    newArtifact(JAR_PREFIX, ".jar", fileName, storage)
  }

  def newClassArtifact(fileName: Path, storage: LocalData): Artifact = {
    newArtifact(CLASS_PREFIX, ".class", fileName, storage)
  }

  def newCacheArtifact(id: String, storage: LocalData): Artifact = {
    newArtifact(CACHE_PREFIX, "", Paths.get(id), storage)
  }

  def newIvyArtifacts(uri: URI): Seq[Artifact] = {
    implicit val printStream: PrintStream = System.err

    val authority = uri.getAuthority
    if (authority == null) {
      throw new IllegalArgumentException(
        s"Invalid Ivy URI authority in uri ${uri.toString}:" +
          " Expected 'org:module:version', found null.")
    }
    if (authority.split(":").length != 3) {
      throw new IllegalArgumentException(
        s"Invalid Ivy URI authority in uri ${uri.toString}:" +
          s" Expected 'org:module:version', found $authority.")
    }

    val (transitive, exclusions, repos) = MavenUtils.parseQueryParams(uri)

    val exclusionsList: Seq[String] =
      if (!StringUtils.isBlank(exclusions)) {
        exclusions.split(",")
      } else {
        Nil
      }

    val ivySettings = MavenUtils.buildIvySettings(Some(repos), None)

    val jars = MavenUtils.resolveMavenCoordinates(
      authority,
      ivySettings,
      transitive = transitive,
      exclusions = exclusionsList)
    jars.map(p => Paths.get(p)).map(path => newJarArtifact(path.getFileName, new LocalFile(path)))
  }

  private def newArtifact(
      prefix: Path,
      requiredSuffix: String,
      fileName: Path,
      storage: LocalData): Artifact = {
    require(!fileName.isAbsolute)
    require(fileName.toString.endsWith(requiredSuffix))
    new Artifact(prefix.resolve(fileName), storage)
  }

  /**
   * Payload stored on this machine.
   */
  sealed trait LocalData {
    def stream: InputStream
    def size: Long
  }

  /**
   * Payload stored in a local file.
   */
  class LocalFile(val path: Path) extends LocalData {
    override def size: Long = Files.size(path)
    override def stream: InputStream = Files.newInputStream(path)
  }

  /**
   * Payload stored in memory.
   */
  class InMemory(bytes: Array[Byte]) extends LocalData {
    override def size: Long = bytes.length
    override def stream: InputStream = new ByteArrayInputStream(bytes)
  }

}
