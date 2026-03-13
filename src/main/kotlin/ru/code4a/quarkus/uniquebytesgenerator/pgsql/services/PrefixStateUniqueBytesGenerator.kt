package ru.code4a.quarkus.uniquebytesgenerator.pgsql.services

import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable holder for the mutable runtime state used to generate identifiers under one prefix.
 *
 * A single instance describes one generation window:
 * - [prefix] is globally unique and comes from PostgreSQL,
 * - [currentValue] is the current 32-bit suffix cursor,
 * - [generationsNum] counts how many values were already issued for this prefix,
 * - [maxAllowedValueGenerations] defines when the prefix must be rotated,
 * - [minValue] and [maxValue] constrain the random increment applied to [currentValue].
 *
 * The `AtomicLong` fields allow the state to be shared safely between concurrent callers while the
 * container object itself remains stable and can be swapped atomically as a whole.
 */
class PrefixStateUniqueBytesGenerator(
  /** Globally unique 64-bit prefix shared by all values generated from this state. */
  val prefix: Long,
  /** Current suffix cursor used as the source for the next generated value. */
  val currentValue: AtomicLong,
  /** Number of identifiers already issued from this prefix state. */
  val generationsNum: AtomicLong,
  /** Maximum number of identifiers that may be issued before the prefix is rotated. */
  val maxAllowedValueGenerations: Long,
  /** Minimum positive increment applied to [currentValue]. */
  val minValue: Long,
  /** Exclusive upper bound for the random increment applied to [currentValue]. */
  val maxValue: Long
)
