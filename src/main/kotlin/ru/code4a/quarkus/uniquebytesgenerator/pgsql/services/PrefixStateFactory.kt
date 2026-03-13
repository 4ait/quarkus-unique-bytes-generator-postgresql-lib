package ru.code4a.quarkus.uniquebytesgenerator.pgsql.services

import jakarta.enterprise.context.ApplicationScoped
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds fully initialized [PrefixStateUniqueBytesGenerator] instances for
 * [UniqueBytesGenerator64BitPrefixBased].
 *
 * A prefix state combines:
 * - a globally unique 64-bit prefix reserved from PostgreSQL,
 * - a local counter starting point,
 * - limits that define how many values may be produced before the prefix must be rotated.
 *
 * The factory intentionally randomizes the local counter window for every new state.
 * This reduces predictability and spreads generated values across the available 32-bit suffix space.
 *
 * Example:
 * ```kotlin
 * val state = prefixStateFactory.createPrefixState()
 * println(state.prefix)
 * println(state.currentValue.get())
 * ```
 */
@ApplicationScoped
class PrefixStateFactory(
  private val unique64bitPrefixGenerator: Unique64bitPrefixGenerator,
  private val startValueGenerator: StartValueGenerator,
  private val maxGenerationsGenerator: MaxGenerationsGenerator
) {
  /**
   * Strategy used to choose the initial value of the local counter.
   *
   * The generated value becomes the first suffix position inside a fresh prefix state.
   * Different implementations may use cryptographically strong randomness, tests may use deterministic stubs.
   */
  interface StartValueGenerator {
    /**
     * Returns a value in the half-open range `[min, max)`.
     *
     * Implementations are expected to honor the bounds exactly.
     */
    fun generate(min: Int, max: Int): Int
  }

  /**
   * Strategy used to decide how long a prefix state may remain active.
   *
   * The returned number limits how many values may be generated before the prefix is considered
   * exhausted and replaced with a newly reserved one.
   */
  interface MaxGenerationsGenerator {
    /**
     * Returns a value in the half-open range `[min, max)`.
     *
     * Larger values reduce database calls, smaller values rotate prefixes more aggressively.
     */
    fun generate(min: Long, max: Long): Long
  }

  /**
   * Default production implementation of [StartValueGenerator].
   *
   * It relies on [SecureRandom.getInstanceStrong] so the starting point is not easily predictable.
   */
  @ApplicationScoped
  class SecureStartValueGenerator : StartValueGenerator {
    /**
     * Returns a cryptographically strong random starting point for the counter.
     */
    override fun generate(min: Int, max: Int): Int {
      return SecureRandom.getInstanceStrong().nextInt(min, max)
    }
  }

  /**
   * Default production implementation of [MaxGenerationsGenerator].
   *
   * It uses strong randomness to avoid fixed rotation windows across application runs.
   */
  @ApplicationScoped
  class SecureMaxGenerationsGenerator : MaxGenerationsGenerator {
    /**
     * Returns a cryptographically strong random upper bound for the number of generated values.
     */
    override fun generate(min: Long, max: Long): Long {
      return SecureRandom.getInstanceStrong().nextLong(min, max)
    }
  }

  /**
   * Creates a new active prefix state.
   *
   * The method performs the following steps:
   * 1. Reserves a unique 64-bit prefix from [unique64bitPrefixGenerator].
   * 2. Chooses a randomized starting point for the local 32-bit suffix space.
   * 3. Chooses how many values may be generated before this prefix must be replaced.
   * 4. Calculates a safe increment range so the suffix never exceeds `Int.MAX_VALUE`.
   *
   * The resulting state is ready for concurrent use by the generator.
   *
   * @throws IllegalArgumentException when the randomly chosen bounds would produce an invalid counter range
   */
  fun createPrefixState(): PrefixStateUniqueBytesGenerator {
    val startValue =
      startValueGenerator.generate(
        min = Int.MIN_VALUE,
        max = Int.MAX_VALUE - 500_000_000
      )

    val maxAllowedValueGenerations =
      maxGenerationsGenerator.generate(
        min = 100_000,
        max = 2_000_000
      )

    val allowedRange = Int.MAX_VALUE.toLong() - startValue.toLong()
    require(allowedRange > 0) { "Invalid startValue: $startValue" }
    require(allowedRange > maxAllowedValueGenerations) { "Invalid allowedRange: $allowedRange" }

    val maxValueLong = allowedRange / maxAllowedValueGenerations - 1
    require(maxValueLong >= 2) { "Invalid maxValue: $maxValueLong" }
    require(maxValueLong <= Int.MAX_VALUE) { "Invalid maxValue: $maxValueLong" }

    return PrefixStateUniqueBytesGenerator(
      prefix = unique64bitPrefixGenerator.get(),
      currentValue = AtomicLong(startValue.toLong()),
      generationsNum = AtomicLong(0L),
      maxAllowedValueGenerations = maxAllowedValueGenerations,
      minValue = 1,
      maxValue = maxValueLong
    )
  }
}
