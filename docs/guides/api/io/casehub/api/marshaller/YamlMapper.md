# io.casehub.api.marshaller.YamlMapper

**Package:** `io.casehub.api.marshaller`

**Kind:** `annotation`

CDI qualifier for YAML ObjectMapper with config/secret placeholder resolution.

<p>Use this qualifier to inject the ObjectMapper that resolves ${$secret.*} and ${$config.*}
placeholders during YAML deserialization:

<pre>
`@Inject @YamlMapper ObjectMapper yamlMapper;`
</pre>
