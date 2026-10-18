# WhiteDNS transport changes — 2026-10-18

Original: Lantern Soar v0.2.2, commit f0d55048c7035786265e2b671a45c4faf5b97a92.
Source: https://github.com/getlantern/soar/tree/v0.2.2
License: Apache-2.0 (the complete upstream LICENSE is retained).

WhiteDNS modifies client.go, session.go and stream.go and adds query_size.go and
reliability_test.go. These are WhiteDNS modifications, not an upstream release.
The DNS, packet, crypto and frame encodings are unchanged; the original wire
vectors must continue to pass without regeneration.

- Bound concurrent initial stream flights, so small DNS query budgets complete
  connection prefaces rather than exposing every origin to an unfinished TLS
  ClientHello. Established small writes have priority; established bulk uploads
  retain at least every fourth scheduling turn. Initial priority is bounded by
  8 KiB or five seconds and released when a response arrives or the stream ends.
- Learn query-name capacity by comparing long probes with safe-name canaries.
  Probe packets carry padding rather than application data and request 512-byte
  answers so answer-size policy does not masquerade as query-size filtering.
  Shared congestion does not permanently reduce a proven large query budget.
  Actual size filtering uses intermediate sizes and backed-off retries.
- Keep all upstream protocol, crypto, flow-control, isolation and loss-recovery
  tests, plus regressions for these policies. Resolver datasets are unchanged.

These changes require a newly built WhiteDNS native Soar core. Updating a server
or reimporting a link cannot replace the engine already installed on a phone.
