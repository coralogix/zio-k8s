package com.coralogix.zio.k8s.codegen.internal

import org.scalafmt.interfaces.Scalafmt
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.stream.{ Transducer, ZStream }
import zio.{ Chunk, ZIO }

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{ Paths => JPaths, Path => JPath }

object CodegenIO {

  def readTextFile(path: Path): ZIO[Blocking, Throwable, String] =
    ZStream
      .fromFile(path.toFile.toPath)
      .transduce(Transducer.utf8Decode)
      .fold("")(_ ++ _)

  def writeTextFile(path: Path, contents: String): ZIO[Blocking, IOException, Unit] =
    Files.writeBytes(
      path,
      Chunk.fromArray(contents.getBytes(StandardCharsets.UTF_8))
    )

  def format(scalafmt: Scalafmt, path: Path) =
    for {
      code <- readTextFile(path)
      formatted = scalafmt.format(JPaths.get(".scalafmt.conf"), path.toFile.toPath, code)
      _ <- writeTextFile(path, formatted)
    } yield path

}
