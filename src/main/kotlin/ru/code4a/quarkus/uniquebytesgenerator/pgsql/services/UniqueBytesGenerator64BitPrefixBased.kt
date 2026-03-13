package ru.code4a.quarkus.uniquebytesgenerator.pgsql.services

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import ru.code4a.quarkus.crypto.generator.UniqueBytesGenerator
import ru.code4a.quarkus.crypto.services.FormatPreservingEncrypterContextual
import ru.code4a.quarkus.crypto.utils.PKCS7Padding
import ru.code4a.quarkus.uniquebytesgenerator.pgsql.utils.schedule.RandomRepeatDelayScheduler
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.hours

/**
 * Production implementation of [UniqueBytesGenerator] based on a hybrid allocation model.
 *
 * The generator combines two sources of uniqueness:
 * - a globally unique 64-bit prefix reserved from PostgreSQL,
 * - a locally generated 32-bit suffix advanced in memory.
 *
 * The raw 96-bit value is then optionally padded to the requested output length and obfuscated
 * via [FormatPreservingEncrypterContextual]. This gives callers a convenient API that returns
 * opaque unique bytes without exposing the internal prefix/counter structure.
 *
 * This design reduces database load significantly because one reserved prefix can be reused for
 * many generated values before the next database round-trip is required.
 *
 * Example:
 * ```kotlin
 * val id12 = uniqueBytesGenerator.getNext(12)
 * val id16 = uniqueBytesGenerator.getNext(16)
 * ```
 */
@ApplicationScoped
class UniqueBytesGenerator64BitPrefixBased(
  private val prefixStateFactory: PrefixStateFactory,
  private val prefixStateValueIncrementor: PrefixStateValueIncrementor,
  private val repeatDelayScheduler: RandomRepeatDelayScheduler,
  private val formatPreservingEncrypterContextual: FormatPreservingEncrypterContextual
) : UniqueBytesGenerator {

  companion object {
    private const val CONTEXT_SOURCE =
      "ru.code4a.quarkus.uniquebytesgenerator.pgsql.services.UniqueBytesGenerator64BitPrefixBased:1:MDliMDM2OWE3ZTM0NGMzZGY5NTY2MTM1YmYyM2FkM2QwZTc2ODFiZDY5YTgwODA3MDJkNjExMmY2ZjA0MTZhNAo="
  }

  /**
   * Strategy for producing the next 32-bit suffix value within one prefix state.
   *
   * Different implementations may trade off predictability, monotonicity and testability.
   */
  interface PrefixStateValueIncrementor {
    /**
     * Returns the next suffix value for the supplied [prefixState].
     *
     * Implementations must respect the state's configured increment bounds and are expected to be
     * safe for concurrent use.
     */
    fun nextValue(prefixState: PrefixStateUniqueBytesGenerator): Long
  }

  /**
   * Default [PrefixStateValueIncrementor] that advances the suffix by a random positive step.
   *
   * Using a random step instead of a fixed increment makes the internal sequence harder to infer
   * from observed output values.
   */
  @ApplicationScoped
  class PrefixStateValueIncrementorByRandom : PrefixStateValueIncrementor {
    private lateinit var random: SecureRandom

    /**
     * Initializes the strong random generator once the CDI bean is constructed.
     */
    @PostConstruct
    protected fun init() {
      random = SecureRandom.getInstanceStrong()
    }

    /**
     * Returns the current suffix cursor and atomically advances it by a random step.
     */
    override fun nextValue(prefixState: PrefixStateUniqueBytesGenerator): Long {
      return prefixState.currentValue.getAndAdd(
        random.nextLong(prefixState.minValue, prefixState.maxValue)
      )
    }
  }

  private lateinit var currentPrefixState: AtomicReference<PrefixStateUniqueBytesGenerator>

  private lateinit var updatePrefixTask: RandomRepeatDelayScheduler.RepeatScheduledTask

  /**
   * Initializes the generator state and schedules periodic prefix rotation.
   *
   * The scheduled refresh is a proactive measure: it prevents a prefix from staying active for too
   * long even under low traffic, while the generation path itself still performs reactive rotation
   * as soon as the allowed generation count is exhausted.
   */
  @PostConstruct
  protected fun init() {
    currentPrefixState =
      AtomicReference(
        prefixStateFactory.createPrefixState()
      )

    updatePrefixTask =
      repeatDelayScheduler.schedule(
        command = ::updateCurrentPrefixState,
        maxNextExecuteDelay = 2.hours
      )
  }

  /**
   * Stops the background refresh task during bean destruction.
   */
  @PreDestroy
  protected fun destroy() {
    updatePrefixTask.stop()
  }

  /**
   * Builds the raw 96-bit identifier before optional padding and encryption.
   *
   * Layout:
   * - first 64 bits: database-backed prefix
   * - last 32 bits: locally generated suffix
   *
   * Example byte layout:
   * ```text
   * [8 bytes prefix][4 bytes suffix]
   * ```
   *
   * @throws IllegalArgumentException when the produced suffix can no longer fit into 32 bits
   */
  private fun get96Bit(): ByteArray {
    val prefixState = getCurrentValidPrefixState()

    val prefix = prefixState.prefix
    val value = prefixStateValueIncrementor.nextValue(prefixState)

    require(value <= Int.MAX_VALUE) { "Invalid value after produce: $value" }

    val buffer = ByteArray(96 / 8)

    ByteBuffer
      .wrap(buffer)
      .putLong(prefix)
      .putInt(value.toInt())

    return buffer
  }

  /**
   * Generates the next unique byte array.
   *
   * The method first obtains the raw 96-bit value, then:
   * - returns it directly when `countBytes == 12`,
   * - or pads it to the requested length and obfuscates the result otherwise.
   *
   * The final output is suitable for opaque identifiers, binary tokens and similar use cases where
   * uniqueness matters but exposing the internal sequence structure is undesirable.
   *
   * Example:
   * ```kotlin
   * val value = uniqueBytesGenerator.getNext(24)
   * ```
   *
   * @param countBytes requested output length in bytes, from `12` to `255`
   * @return a unique byte array of exactly [countBytes] bytes
   * @throws IllegalArgumentException when [countBytes] is outside the supported range
   */
  @OptIn(ExperimentalSerializationApi::class)
  override fun getNext(countBytes: Int): ByteArray {
    require(countBytes >= 12) { "Minimum count must be at least 12 bytes" }
    require(countBytes <= 255) { "Maximum count must be at most 255 bytes" }

    val unique12Bytes = get96Bit()

    val uniqueBytes =
      if (countBytes == 12) {
        unique12Bytes
      } else {
        PKCS7Padding.addPadding(unique12Bytes, countBytes)
      }

    return formatPreservingEncrypterContextual.encrypt(
      uniqueBytes,
      CONTEXT_SOURCE,
      ProtoBuf.encodeToByteArray(
        ContextContainer(
          countBytes = countBytes,
        )
      ),
      256
    )
  }

  /**
   * Serialization payload used as encryption context for output-size-specific obfuscation.
   *
   * Two generated values of different requested lengths must not share the same encryption context,
   * because the padded payload shape is different.
   */
  @OptIn(ExperimentalSerializationApi::class)
  @Serializable
  private class ContextContainer(
    /** Target byte length requested by the caller. */
    @ProtoNumber(1)
    val countBytes: Int
  )

  /**
   * Returns the currently active prefix state that is still allowed to issue new identifiers.
   *
   * If the current state is already exhausted, the method triggers a prefix rotation and retries
   * until a valid state becomes available.
   */
  private fun getCurrentValidPrefixState(): PrefixStateUniqueBytesGenerator {
    while (true) {
      val prefixState = currentPrefixState.get()

      val currentGenerationNum = prefixState.generationsNum.getAndIncrement()

      if (currentGenerationNum >= prefixState.maxAllowedValueGenerations) {
        updatePrefixStateForPrefix(prefixState.prefix)
        continue
      }

      return prefixState
    }
  }

  private val updatePrefixStateSyncLock = ReentrantLock()

  /**
   * Rotates the prefix only if the supplied [prefix] still matches the currently active state.
   *
   * This guards against redundant database calls when multiple threads notice the same exhausted
   * prefix at approximately the same time.
   */
  private fun updatePrefixStateForPrefix(prefix: Long) {
    // Avoid requesting a new database prefix twice for the same exhausted state.
    updatePrefixStateSyncLock.withLock {
      if (currentPrefixState.get().prefix == prefix) {
        updateCurrentPrefixState()
      }
    }
  }

  /**
   * Replaces the active prefix state with a freshly allocated one.
   *
   * This method is used both by the background scheduler and by the reactive exhaustion path.
   */
  fun updateCurrentPrefixState() {
    currentPrefixState.set(
      prefixStateFactory.createPrefixState()
    )
  }
}
