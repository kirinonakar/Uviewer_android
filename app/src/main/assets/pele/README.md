# Pele 1.12.0

Vendored, unmodified `dist/pele.global.js` and `LICENSE` from the npm `pele@1.12.0` package.
All diagram types are included; rendering works offline without a CDN or npm at build time.

- Upstream: https://github.com/obsidianmd/pele
- API: https://pele.run/api
- Package: https://registry.npmjs.org/pele/-/pele-1.12.0.tgz
- npm integrity: `sha512-3OdOK6rvkaTB569R4/I8U0H82MqPRxj7vfkbowa2rRhgSBPFhvWlfFy5eae0jP+2A6hE8abE17nsdyxJz2VBiQ==`
- `pele.global.js` SHA-256: `787ed66bb4bd56c15e224109ff5b89ed395ebfacf71fae2c0ccad1f86d6e24ef`

To update, pack an exact version with `npm pack pele@<version>`, extract these two files,
update this provenance, and run `node --test app/src/test/js/diagrams.test.cjs` plus the Android tests.
