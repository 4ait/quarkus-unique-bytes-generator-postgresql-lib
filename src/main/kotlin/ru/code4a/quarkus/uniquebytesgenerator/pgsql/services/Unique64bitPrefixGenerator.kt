package ru.code4a.quarkus.uniquebytesgenerator.pgsql.services

/**
 * Contract for components that reserve globally unique 64-bit prefixes.
 *
 * The prefix is the high 64-bit part of the 96-bit internal identifier produced by the library.
 * Implementations are expected to guarantee uniqueness across the whole deployment scope,
 * typically by delegating to a database sequence or another strongly consistent allocator.
 */
interface Unique64bitPrefixGenerator {
  /**
   * Reserves and returns the next unique prefix.
   *
   * Example:
   * ```kotlin
   * val prefix = unique64bitPrefixGenerator.get()
   * ```
   */
  fun get(): Long
}
