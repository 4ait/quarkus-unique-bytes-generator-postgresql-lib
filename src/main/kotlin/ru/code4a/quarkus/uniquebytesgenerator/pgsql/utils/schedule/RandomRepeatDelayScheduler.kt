package ru.code4a.quarkus.uniquebytesgenerator.pgsql.utils.schedule

import io.quarkus.virtual.threads.VirtualThreads
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Interprets the byte array as a signed 64-bit integer using big-endian byte order.
 *
 * The helper is used only for deriving randomized delays from cryptographically strong bytes.
 */
internal fun ByteArray.asLong(): Long {
  return ByteBuffer.wrap(this).getLong()
}

/**
 * Schedules commands to run repeatedly with a random delay before each execution.
 *
 * The scheduling model deliberately separates waiting from work execution:
 * - a single scheduler thread is responsible only for timing,
 * - the actual command is dispatched to a Quarkus-managed virtual-thread executor.
 *
 * This keeps the scheduling overhead low while allowing blocking or longer-running commands
 * to execute without tying up the single timing thread.
 *
 * Example:
 * ```kotlin
 * val task = randomRepeatDelayScheduler.schedule(
 *   command = Runnable { println("rotate prefix") },
 *   maxNextExecuteDelay = 2.hours
 * )
 * ```
 */
@ApplicationScoped
class RandomRepeatDelayScheduler(
  @param:VirtualThreads
  private val virtualThreadsExecutor: ExecutorService,
) {
  private val delayScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

  /**
   * Stops the internal timing scheduler during application shutdown.
   */
  @PreDestroy
  protected fun destroy() {
    delayScheduler.shutdownNow()
  }

  /**
   * Centralized access to strong random bytes used for delay computation.
   */
  object SecureBytesGeneratorStrong {
    /**
     * Returns [lengthBytes] cryptographically strong random bytes.
     */
    fun generate(lengthBytes: Int): ByteArray {
      val secureRandom = SecureRandom.getInstanceStrong()

      val byteArray = ByteArray(lengthBytes)
      secureRandom.nextBytes(byteArray)

      return byteArray
    }
  }

  /**
   * Handle returned to callers for controlling a scheduled repeating task.
   *
   * The task reschedules itself after every execution until [stop] is called.
   */
  class RepeatScheduledTask private constructor(
    private val delayScheduler: ScheduledExecutorService,
    private val virtualThreadsExecutor: ExecutorService,
    private val command: Runnable,
    private val maxNextExecuteDelay: Duration
  ) {

    private val isStopped = AtomicBoolean(false)

    companion object {
      /**
       * Creates a task and starts the first scheduling cycle immediately.
       */
      fun createAndStart(
        delayScheduler: ScheduledExecutorService,
        virtualThreadsExecutor: ExecutorService,
        command: Runnable,
        maxNextExecuteDelay: Duration
      ): RepeatScheduledTask {
        val task =
          RepeatScheduledTask(
            delayScheduler = delayScheduler,
            virtualThreadsExecutor = virtualThreadsExecutor,
            command = command,
            maxNextExecuteDelay = maxNextExecuteDelay
          )

        task.start()

        return task
      }
    }

    /**
     * Requests graceful termination of future executions.
     *
     * Already running work is not interrupted. The flag only prevents subsequent rescheduling
     * and dispatch.
     */
    fun stop() {
      isStopped.set(true)
    }

    /**
     * Starts the scheduling loop.
     */
    private fun start() {
      scheduleNext()
    }

    /**
     * Schedules the next execution after a random delay in the range `1..maxNextExecuteDelay`.
     */
    private fun scheduleNext() {
      val delay =
        (1 + SecureBytesGeneratorStrong.generate(8).asLong().absoluteValue % maxNextExecuteDelay.toLong(
          DurationUnit.MILLISECONDS
        ))

      delayScheduler.schedule(
        ::dispatchToExecutor,
        delay,
        TimeUnit.MILLISECONDS
      )
    }

    /**
     * Hands the actual work off to the virtual-thread executor.
     *
     * If the executor is already shutting down, the task simply stops rescheduling silently.
     */
    private fun dispatchToExecutor() {
      if (isStopped.get()) {
        return
      }

      try {
        virtualThreadsExecutor.execute(::executeAndScheduleNext)
      } catch (_: RejectedExecutionException) {
        // The application is shutting down and the executor is no longer accepting work.
      }
    }

    /**
     * Executes the command and schedules the following iteration.
     *
     * Rescheduling happens in `finally` so a failing command does not permanently stop the loop.
     */
    private fun executeAndScheduleNext() {
      if (isStopped.get()) {
        return
      }

      try {
        command.run()
      } finally {
        scheduleNext()
      }
    }
  }

  /**
   * Starts a new repeating task with randomized delays between executions.
   *
   * @param command command to run on each iteration
   * @param maxNextExecuteDelay upper bound for the next delay
   * @return a handle that can be used to stop the repeating task
   */
  fun schedule(
    command: Runnable,
    maxNextExecuteDelay: Duration
  ): RepeatScheduledTask {
    return RepeatScheduledTask.createAndStart(
      delayScheduler = delayScheduler,
      virtualThreadsExecutor = virtualThreadsExecutor,
      command = command,
      maxNextExecuteDelay = maxNextExecuteDelay
    )
  }
}
