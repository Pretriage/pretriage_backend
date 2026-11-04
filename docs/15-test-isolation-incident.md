# Database incident during verification, 2026-10-13

During the chat closure verification at approximately 01:25 Argentina time,
an integration test invocation explicitly selected a disposable datasource URL
but did not disable Spring Docker Compose. Compose connection details overrode
that URL, and Hibernate `create-drop` recreated the shared `pretriage_db` schema.
The agent stopped the identified test JVM after detecting the Compose startup.
A read-only check then found zero chats, including no chat 19. The user confirmed
there was no database backup; no backup existed in the project and PostgreSQL
archive mode was disabled. Data recovery could not be completed. No synthetic
reconstruction was presented as the original data.

Subsequent verification disabled Compose and used an isolated database. The
safe standard command is now:

```powershell
python scripts/test_backend_isolated.py
```

It creates a database named `pretriage_test_<random UUID>`, explicitly disables
Compose, uses its JDBC URL with `create-drop`, writes Maven output to
`target/isolated-tests.log`, and drops only the validated database created by
that invocation. Optional `--test ClassA,ClassB` selects tests. No user-supplied
datasource/DDL arguments are accepted. `AGENTS.md` and local development docs now
require this isolation for integration/full tests. Running the normal app with
`JPA_DDL_AUTO=update` preserves future development data but is not a backup.

Final verification through this runner passed 397 tests. The temporary database
was removed. The shared database data was not recovered; the successful tests
verify the implementation, not recovery of user data.
