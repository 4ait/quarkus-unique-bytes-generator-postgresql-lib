package ru.code4a.quarkus.uniquebytesgenerator.pgsql.utils

/**
 * Casts a nullable value to [T] or throws the exception produced by [exceptionGetter].
 *
 * This helper is useful when the caller wants both a null-check and a type-check in a single step.
 */
internal inline fun <reified T> Any?.castNullableElseThrow(exceptionGetter: () -> Throwable): T {
  val notNullValue = unwrapElseThrow(exceptionGetter)

  if (notNullValue is T) {
    return notNullValue as T
  }

  throw exceptionGetter()
}

/**
 * Casts a non-null value to [T] or throws the exception produced by [exceptionGetter].
 */
internal inline fun <reified T> Any.castElseThrow(exceptionGetter: () -> Throwable): T {
  if (this is T) {
    return this as T
  }

  throw exceptionGetter()
}

/**
 * Casts a non-null value to [T] or fails with [error].
 */
internal inline fun <reified T> Any.castElseError(messageGetter: () -> String): T {
  if (this is T) {
    return this as T
  }

  error(messageGetter())
}

/**
 * Casts a nullable value to [T] and throws a descriptive [RuntimeException] on failure.
 *
 * Example:
 * ```kotlin
 * val value: Long = nativeQuery.singleResult.castNullable()
 * ```
 */
internal inline fun <reified T> Any?.castNullable(): T {
  return castNullableElseThrow {
    val thisClassName =
      if (this == null) {
        "null"
      } else {
        this::class.toString()
      }

    RuntimeException("Cannot cast from $thisClassName to ${T::class}")
  }
}

/**
 * Casts a nullable value to [T] or fails with [error] using a lazily built message.
 */
internal inline fun <reified T> Any?.castNullableElseError(errorMessageGetter: () -> String): T {
  return castNullableElseThrow {
    error(errorMessageGetter())
  }
}
