package com.coralogix.zio.k8s.client

import zio.clock.Clock
import zio.{ Chunk, IO, Ref, Schedule, ZIO, ZManaged }
import zio.stream.ZStream

package object internal {

  implicit class ZStreamOps[R, E, O](self: ZStream[R, E, O]) {

    // TODO: this will be part of next ZIO release
    /**
      * When the stream fails, retry it according to the given schedule
      *
      * This retries the entire stream, so will re-execute all of the stream's acquire operations.
      *
      * The schedule is reset as soon as the first element passes through the stream again.
      *
      * @param schedule Schedule receiving as input the errors of the stream
      * @return Stream outputting elements of all attempts of the stream
      */
    def retry[R1 <: R](schedule: Schedule[R1, E, _]): ZStream[R1 with Clock, E, O] =
      ZStream {
        for {
          driver       <- schedule.driver.toManaged_
          currStream   <- Ref.make[ZIO[R, Option[E], Chunk[O]]](ZIO.fail(None)).toManaged_
          switchStream <- ZManaged.switchable[R, Nothing, ZIO[R, Option[E], Chunk[O]]]
          _            <- switchStream(self.process).flatMap(currStream.set).toManaged_
          pull = {
            def loop: ZIO[R1 with Clock, Option[E], Chunk[O]] =
              currStream.get.flatten.catchSome {
                case Some(e) =>
                  driver
                    .next(e)
                    .foldM(
                      // Failure of the schedule indicates it doesn't accept the input
                      _ => ZIO.fail(Some(e)),
                      _ =>
                        switchStream(self.process).flatMap(currStream.set) *>
                          // Reset the schedule to its initial state when a chunk is successfully pulled
                          loop.tap(_ => driver.reset)
                    )
              }

            loop
          }
        } yield pull
      }
  }

}
