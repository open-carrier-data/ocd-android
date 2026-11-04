# The writer-to-provider protocol

OCD Android (`../app`, the writer) hands datasets, records and test steps to OcdCarrierSettingsDataset (`../carriersettings`, the provider) through `content://com.android.carriersettings.ocddata`, with method calls only. OCD rule round 37, item 6 gives this protocol one versioned spec, which both sides' tests read:

- [`ocd-provider-protocol.json`](ocd-provider-protocol.json): the protocol version, the authority, the writer permission and packages, each method with the protocol it came in, who may call it (the writer app, adb) and its extras, the extras, the error codes, the outcomes and the install job states.
- [`vectors/`](vectors): status answers as the app reads them. Every member the provider's `status` writes is in each vector. The protocol 3 vectors stay while the app reads protocol 3.

| Side | Test | What it holds to the spec |
|---|---|---|
| Provider | `carriersettings/ocd/test/src/ProtocolContractTest.kt` (`run.sh`, CI `provider.yml`) | `Protocol`'s version, names and method sets; every method and extra handled by `OcdDataProvider`; the errors, states and outcomes present in the engine; `status()` writes exactly the vectors' members |
| App | `app/src/testOwner/.../ProviderProtocolContractTest.kt` (`testOwnerUnitTest`, CI `test.yml`) | `OcdDataProtocol`'s version, names and the methods it calls; `InstallState`; every error code the app's code branches on; the vectors read into the app's status with every member it uses |

A pull request that changes one side's names, method sets or status members fails its side's test until this spec says the same, and then fails the other side's test until that side matches. Both run on every pull request.

**Skew.** A method the app calls and the provider lacks must carry a `pending` reason in the spec; the provider then answers `unknown_method`, and the app's code treats that as "not run". None is pending today: round 32's `apn_candidate_set` and `apn_candidate_restore` are in both sides since the cut (vendor/extra `cfa8ee1`). When a provider gets a pending method, set `owner` to `true` and drop `pending`; the provider's test fails until you do.

**Versions.** `protocol` is the provider's `Protocol.VERSION` and the app's `OcdDataProtocol.PROTOCOL`; `app_reads_protocols` is what the app accepts. Raise `protocol` when a change is not backward compatible for the other side, and keep the old one in `app_reads_protocols` while ROMs with it are still in use.

| Protocol | Since | What the app can rely on |
|---|---|---|
| 2 | round 30 | |
| 3 | round 31 | the voice-loss hold, the default emergency APN test, the tester declaration; `set_safe_mode` is gone |
| 4 | round 47 | no new method or status member. An activation that leaves a SIM's served values and APN rows unchanged records the commit and neither notifies nor proves that SIM (`DatasetStatus.skipsUnchanged`), so the app may pull as often as main moves. On protocol 3 every activation reloads every SIM, and the app keeps the daily pull |

An app that does not list a protocol hands nothing to that provider, so a ROM with protocol 4 needs an OCD Android built from this spec or later.
