# EBMS Facturation

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF)

A native Android point-of-sale and invoicing app for Burundian businesses. It issues tax-compliant
invoices and sends them in real time to **EBMS** (Electronic Billing Management System), the
e-invoicing platform of the **Office Burundais des Recettes (OBR)**.

The app keeps selling when the network is down. Every invoice is saved on the device first, then sent
to the OBR automatically until the OBR confirms receipt with a signed acknowledgement.

> **Status:** in development.

---

## Features

- **Invoicing:** standard invoices (FN), credit notes (FA), deposit refunds (RC) and off-invoice
  discounts (RHF), each with its own gap-free numbering.
- **Real-time OBR transmission:** offline-first, with automatic retries and no duplicates.
- **Signed receipts:** every OBR acknowledgement is verified and archived as proof of receipt.
- **Cancellations and corrections:** cancellation, cancel-and-replace and credit notes, with manager approval.
- **Stock:** all OBR stock movement types, plus imported goods entered through their customs
  declaration (DMC).
- **Catalogue and customers:** barcode scanning, CSV import, and customer tax ID (NIF) checks with the OBR.
- **Printing and sharing:** Bluetooth thermal receipts (58/80 mm), A4 PDF invoices, and sharing
  through WhatsApp, email or SMS.
- **Cash desk and reports:** cash sessions, Z report, dashboard, VAT report and exports.
- **Users and audit:** roles, PIN or biometric login, a tamper-evident audit log and encrypted backups.
- **Languages:** French and Kirundi, with layouts for phones, tablets and Android POS terminals.

## Getting started

### Requirements

- Android Studio (latest stable)
- An Android device or emulator running Android 8.0 (API 26) or later

### Build and run

```bash
git clone https://github.com/<owner>/<repo>.git
cd <repo>
./gradlew :app:installDebug
```

On Windows without Git Bash, use `gradlew.bat` instead of `./gradlew`.

### OBR credentials and company profile

EBMS credentials (username, password and system ID) are issued by the OBR tax office that manages the
business (DGC, DMC or DPMC). They are **entered in the app** during setup, together with the company's
tax profile, and stored encrypted on the device. They never appear in the source code, the build
configuration or the Git history.

To try the app against the OBR test server, select the **Test** environment in the app settings and
enter your test credentials. Test and production credentials are different.

## Testing

Every development step ships with its tests, and a step is only complete when all tests pass.

- Business rules are covered by unit tests.
- Storage and the OBR API are covered by integration tests against a simulated OBR server.
- Screens are covered by UI tests and screenshot tests.

```bash
./gradlew check
```

Automated tests never use real OBR credentials.

## Language

Code, code comments, commit messages and this README are in English. The user interface is in
French and Kirundi.

## OBR EBMS integration

The integration follows the OBR document *Spécifications techniques de l'interfaçage avec EBMS*,
version **V0.7** (4 December 2025).

| Service | Purpose |
| --- | --- |
| `login/` | Get an access token (valid for 60 seconds) |
| `addInvoice_confirm/` | Register an invoice and receive a signed acknowledgement |
| `getInvoice/` | Read a registered invoice |
| `checkTIN/` | Check a tax identification number (NIF) |
| `cancelInvoice/` | Cancel an invoice |
| `AddStockMovement/` | Declare a stock movement |
| `getDmcItems` | Read the items of a customs declaration (DMC) |
| `AddStockMovementImporters/` | Enter imported goods into stock |

## Development workflow

The app is built step by step with [Claude Code](https://code.claude.com/docs/en/overview) in Android
Studio. Each architecture decision and each step is reviewed and approved by a developer before it is coded.
The working files used by Claude Code (`CLAUDE.md`, `.claude/`) and the internal project documents
(`docs/`) are kept out of the repository. Pushing and releasing are always done by a human.

## Branching model

The project follows **GitFlow**:

| Branch | Purpose | Created from | Merged into |
| --- | --- | --- | --- |
| `main` | Released versions only, each tagged `vX.Y.Z` | — | — |
| `develop` | Integration branch for the next release | `main` | — |
| `feature/<step>-<name>` | One roadmap step or feature, e.g. `feature/03-invoice-numbering` | `develop` | `develop` |
| `bugfix/<name>` | Fix for a bug found on `develop` | `develop` | `develop` |
| `release/X.Y.Z` | Release stabilisation (version bump, final fixes) | `develop` | `main` and `develop` |
| `hotfix/X.Y.Z` | Urgent fix for a released version | `main` | `main` and `develop` |

Merges use `--no-ff` so each feature stays visible in the history. Nobody commits directly to
`main` or `develop`.

## Contributing

1. Create a `feature/` or `bugfix/` branch from `develop`.
2. Write the tests first, then the code.
3. Make sure `./gradlew check` passes.
4. Document public classes, interfaces and functions with **KDoc** comments (Javadoc syntax:
   `/** ... */`, `@param`, `@return`, `@throws`), in English.
5. Write commit messages in English using [Conventional Commits](https://www.conventionalcommits.org/),
   for example `feat(invoicing): add credit note limit rule`.
6. Open a pull request into `develop` that describes the use cases it covers, for example `UC-E03`.

## Security

- Never commit credentials, keystores or `local.properties`.
- Never commit `CLAUDE.md`, `.claude/` or `docs/`: they are listed in `.gitignore`.
- Passwords, tokens and full signatures are never logged.
- To report a vulnerability, contact the maintainers privately instead of opening a public issue.

## License

To be defined. Until a license is added, all rights are reserved.

## Disclaimer

This project is not affiliated with, endorsed by or certified by the Office Burundais des Recettes (OBR).
Businesses remain responsible for their own tax compliance and for any approval the OBR requires before
production use.
