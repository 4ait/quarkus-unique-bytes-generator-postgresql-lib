package ru.code4a.quarkus.uniquebytesgenerator.pgsql.utils

/**
 * Returns the receiver when it is not `null`, otherwise computes and returns a default value.
 */
internal inline fun <T> T?.unwrapElseDefault(defaultGetter: () -> T): T {
  if (this == null) {
    return defaultGetter()
  }

  return this
}

/**
 * Returns the receiver when it is not `null`, otherwise throws the exception produced by [exceptionGetter].
 */
internal inline fun <T> T?.unwrapElseThrow(exceptionGetter: () -> Throwable): T {
  if (this == null) {
    throw exceptionGetter()
  }

  return this
}

/**
 * Returns the receiver when it is not `null`, otherwise fails with [error].
 */
internal inline fun <T> T?.unwrapElseError(messageGetter: () -> String): T {
  if (this == null) {
    error(messageGetter())
  }

  return this
}

/**
 * Returns the receiver when it is not `null`, otherwise throws a plain [RuntimeException].
 *
 * This helper exists for legacy call sites that do not need a custom exception type.
 */
internal fun <T> T?.getElseThrowRuntimeException(exceptionMessage: String): T {
  if (this == null) {
    throw RuntimeException(exceptionMessage)
  }

  return this
}
