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
the SDK's [`flix-bridge/docs/compatibility/`](https://github.com/wstein/flixw-plugin-sdk/blob/515b8080336bf9f2ab6bc8ec2e87b29858c69eb7/flix-bridge/docs/compatibility/README.md).

This project does not currently operate a discussion forum or provide guaranteed response times.
Security vulnerabilities must be reported privately as described in [SECURITY.md](SECURITY.md).
