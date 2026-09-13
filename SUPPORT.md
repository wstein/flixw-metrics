# Support

Use [GitHub Issues](https://github.com/wstein/flixw-metrics/issues) for reproducible bugs and
feature requests. Include the plugin version, Flix compiler version, Java version, command,
sanitized output, and a minimal fixture when possible.

Before reporting an unsupported compiler, run:

```console
./flixw metrics capabilities
```

Include that JSON output after removing local paths. The verified compiler matrix and known
rejection boundaries are documented in
[`docs/compiler-compatibility/`](docs/compiler-compatibility/README.md).

This project does not currently operate a discussion forum or provide guaranteed response times.
Security vulnerabilities must be reported privately as described in [SECURITY.md](SECURITY.md).
