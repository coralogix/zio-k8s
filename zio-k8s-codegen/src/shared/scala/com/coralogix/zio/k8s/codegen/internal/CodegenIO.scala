package com.coralogix.zio.k8s.codegen.internal

import zio.nio.file.{ Files, Path }
import zio.stream.{ ZPipeline, ZStream }
import zio.{ Chunk, ZIO }

import java.io.IOException
import java.nio.charset.StandardCharsets

object CodegenIO {

  def readTextFile(path: Path): ZIO[Any, Throwable, String] =
    ZStream
      .fromFile(path.toFile)
      .via(ZPipeline.utf8Decode)
      .runFold("")(_ ++ _)

  def writeTextFile(path: Path, contents: String): ZIO[Any, IOException, Unit] =
    Files.writeBytes(
      path,
      Chunk.fromArray(contents.getBytes(StandardCharsets.UTF_8))
    )
}
