# leaks-config: ctx.config() live edits (held)

`/lkcfg` prints what `ctx.config()` contains. Live edits of `modules/leaks-config/config.yml`:

| edit | result |
|---|---|
| `message: "Bonjour é"`, `power: 5` | v1 sees both (UTF-8 ok) |
| invalid YAML (`power: [oops`) | reload refused, `config.yml is not valid YAML`, v1 keeps running (rolledBack) |
| file saved with a UTF-8 BOM | v2 loads it fine |
| a TAB indentation | refused, v2 kept |
| file deleted | v3 with defaults (empty config) |

Only problem found around config.yml: a module that WRITES its own config.yml reloads itself forever, see
`../leaks-cfgloop`.
