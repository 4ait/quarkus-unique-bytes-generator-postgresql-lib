package ru.code4a.quarkus.uniquebytesgenerator.pgsql.services

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.inject.ConfigProperty
import ru.code4a.quarkus.transactional.rw.annotations.TransactionalWrite
import ru.code4a.quarkus.uniquebytesgenerator.pgsql.utils.castNullable

/**
 * Database-backed implementation of [Unique64bitPrefixGenerator].
 *
 * The class executes a native SQL query that must return exactly one numeric value compatible with [Long].
 * By default it uses:
 * `SELECT nextval('unique_bytes_generator')`
 *
 * The query is configurable through
 * `quarkus.unique-bytes-generator.pgsql.nextval-query`, which makes the component usable with
 * custom sequence names or wrapper SQL functions.
 *
 * Example configuration:
 * ```properties
 * quarkus.unique-bytes-generator.pgsql.nextval-query=SELECT nextval('custom_unique_bytes_sequence')
 * ```
 */
@ApplicationScoped
class Unique64bitPrefixGeneratorFromDatabase(
  private val entityManager: EntityManager,
  @param:ConfigProperty(
    name = "quarkus.unique-bytes-generator.pgsql.nextval-query",
    defaultValue = "SELECT nextval('unique_bytes_generator')"
  )
  private val nextValueQuery: String
) : Unique64bitPrefixGenerator {
  /**
   * Loads the next unique prefix from the database.
   *
   * This is the public API used by higher-level generator components.
   */
  override fun get(): Long {
    return getFromDb()
  }

  /**
   * Executes the configured SQL query in a dedicated write transaction.
   *
   * A separate transaction is used intentionally so allocating the next prefix is isolated from
   * the caller's current unit of work. Once a sequence value is consumed, it must stay consumed,
   * even if the outer business transaction fails later.
   */
  @TransactionalWrite(TransactionalWrite.Type.REQUIRES_NEW)
  protected fun getFromDb(): Long {
    return entityManager
      .createNativeQuery(nextValueQuery, Long::class.java)
      .singleResult
      .castNullable<Long>()
  }
}
