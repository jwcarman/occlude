# Configuration

Every property the Spring Boot starter reads, under `occlude.*`. Keys and roots belong in the
environment or a secret store, never in a committed file.

| Property | Default | What it does |
|---|---|---|
| `occlude.keys.current` | — | The id of the key-encryption key new values are wrapped under. Setting it builds a `JceDataKeyProvider` from `occlude.keys.keks`; a `DataKeyProvider` bean of your own replaces both. |
| `occlude.keys.keks.<id>` | — | Every key-encryption key by id, base64-encoded AES-256. Keep the old ones while anything is still wrapped under them. |
| `occlude.roots.current` | — | The id of the root new values and lines are signed under. Required: the JDBC store is not built without one. |
| `occlude.roots.secrets.<id>` | — | Every root secret by id, base64-encoded, at least 32 bytes each. Supply every root anything was signed under. |
| `occlude.roots.mac` | `HMAC_SHA256` | What new values and lines are signed with: `HMAC_SHA256`, `HMAC_SHA384` or `HMAC_SHA512`. |
| `occlude.migrate` | `true` | Create the tables at startup if they are not there. `false` where you manage the schema. |
| `occlude.log-manifest` | `true` | Write the manifest to the log as the charter is bound. |
| `occlude.integrity.interval` | unset | How often to check the store. Unset, nothing is scheduled. A check reads and decrypts every row, so size this to the store and set it on one instance — see [Operating a Store](guides/operating.md#on-a-schedule). |

Startup fails, saying which, when keys or a root are missing, the current root names no secret, a
root is shorter than 32 bytes, a key or secret is not valid base64, or the MAC is not one of the
three.

What properties do not cover — keys per tenant, most often — is reached with a
`JdbcStorageConfigCustomizer` bean; see [Spring Boot](guides/spring-boot.md#storage).
