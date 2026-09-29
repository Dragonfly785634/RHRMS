# Libraries and runtime dependencies

The project intentionally uses a small dependency set. Versions are declared in `app/pom.xml`.

| Library | Purpose |
| --- | --- |
| PostgreSQL JDBC driver | Connects the Java server to PostgreSQL using JDBC. |
| jBCrypt | Hashes and verifies application passwords. |
| HikariCP | Provides a small connection pool for each PostgreSQL role. |
| SLF4J API | Common logging API used by the server and connection pool. |
| Logback Classic/Core | Console and file logging implementation for SLF4J. |
| Maven Compiler Plugin | Compiles the Java 21 source. |
| Maven Dependency Plugin | Copies runtime dependency JARs beside the application JAR. |
| Maven JAR Plugin | Creates the executable JAR manifest. |

The terminal uses Java's built-in HTTP client and a small project-local JSON reader/writer in
`app/src/main/java/org/rubyhill/rhrms/json/Json.java`; it does not add an HTTP or JSON dependency.

The spreadsheet importer uses Python 3 and the Python standard library. Python is needed for the
import and API test tooling, not for the Java server or terminal client.

When adding a dependency, update `app/pom.xml`, this document, and the project decision log with
the reason it is needed.

