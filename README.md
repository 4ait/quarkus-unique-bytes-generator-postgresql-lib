# Quarkus Unique Bytes Generator PostgreSQL Library

`quarkus-unique-bytes-generator-postgresql-lib` provides a CDI-managed implementation of `UniqueBytesGenerator` for
Quarkus applications. It combines PostgreSQL-backed 64-bit prefixes with locally generated counters and
format-preserving obfuscation to produce unique byte arrays with a configurable length from 12 to 255 bytes.

## What the library does

- Reserves unique 64-bit prefixes from PostgreSQL by calling `nextval(...)`
- Generates multiple values locally for each reserved prefix to reduce database round-trips
- Obfuscates the resulting bytes through `FormatPreservingEncrypterContextual`
- Exposes a ready-to-inject `UniqueBytesGenerator` bean for Quarkus applications

## Installation

Add the library to your application:

```kotlin
dependencies {
  implementation("ru.code4a:quarkus-unique-bytes-generator-postgresql:<version>")
}
```

## Requirements

- A Quarkus application
- A PostgreSQL sequence that returns unique `BIGINT` values
- Configuration for `ru.code4a:quarkus-crypto`, because this library uses `FormatPreservingEncrypterContextual`
  internally

Create the default PostgreSQL sequence:

```sql
CREATE SEQUENCE unique_bytes_generator AS bigint;
```

## Configuration

Required property from `quarkus-crypto`:

```properties
erpii.security.base-master-key-32-bytes-base64=<base64-encoded-32-byte-key>
```

Optional property for the SQL query used to fetch the next prefix:

```properties
quarkus.unique-bytes-generator.pgsql.nextval-query=SELECT nextval('unique_bytes_generator')
```

The query must return a single numeric value that fits into `Long`.

Example for a different sequence:

```properties
quarkus.unique-bytes-generator.pgsql.nextval-query=SELECT nextval('custom_unique_bytes_sequence')
```

Generate a 32-byte Base64 master key:

```bash
openssl rand -base64 32
```

## Usage

Inject `UniqueBytesGenerator` and request the required number of bytes:

```kotlin
import jakarta.inject.Inject
import ru.code4a.quarkus.crypto.generator.UniqueBytesGenerator

class TokenService {
  @Inject
  lateinit var uniqueBytesGenerator: UniqueBytesGenerator

  fun nextTokenId(): ByteArray {
    return uniqueBytesGenerator.getNext(16)
  }
}
```

## Generation model

Each generated value is built in three stages:

1. A unique 64-bit prefix is loaded from PostgreSQL.
2. A local counter contributes the remaining 32 bits for the base 96-bit value.
3. The result is padded when necessary and obfuscated with format-preserving encryption.

This design keeps values unique while avoiding a database call for every generated identifier.

## Notes

- `getNext(countBytes)` accepts values from `12` to `255`
- `12` bytes is the native size of the generated value before optional padding
- The library is intended for unique identifier generation, not for sequential ordering

## License

Apache License 2.0
