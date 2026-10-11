# spotifly-extractor

The YouTube extractor the Spotifly Android app uses: NewPipe Extractor and
the few methods the app calls (`Extractor.java`). The app carries a copy of
it and downloads newer builds from this repository's `extractor` release,
using one only when its ECDSA signature verifies against the key built into
the app.

The `Extractor module` workflow builds, tests and signs a module whenever
NewPipe Extractor releases. Nothing else of the app lives here.
