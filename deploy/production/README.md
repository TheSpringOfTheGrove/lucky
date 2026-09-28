# Production release

Run the existing `/opt/lucky5` Compose stack; do not replace `.env`, credentials, Caddy, or data volumes.

1. Test/build locally, commit and push the source.
2. Tag the verified images `lucky5-server:release-<commit>` and `lucky5-ui:release-<commit>`.
3. Send an archive containing `images.tar`, the two deployment scripts, the four `20260928-*.sql` patches and `SHA256SUMS` to `/tmp/lucky5-release-<commit>`; verify the archive checksum before extracting it.
4. Run `sh deploy-images.sh <commit> /tmp/lucky5-release-<commit>`.

The script saves the images actually running, backs up the database privately under `/opt/lucky5/backups`, applies only additive/idempotent patches, checks actual running image IDs and service readiness, and rolls back the applications on failure. It never restores a database automatically or removes Docker volumes.

## Optional, destructive runtime-history maintenance

Only append `--clear-runtime-history` after explicit approval of all tenants/all owners and the exact preserved/deleted data. The server is stopped and a second, quiesced backup is verified before cleanup. Real unsettled or uncertain market orders and running auto-proxy tasks block cleanup.

- Cleared: orders, bet items, market routing details, follow-order history, draw history, room messages, issue-transition logs and business operation logs.
- Preserved and checksum-verified: members (including balances and statistics), immutable balance ledger, all configurations and credentials, backend accounts/roles/permissions/packages, amount/rebate/chima records, owner initialization markers, issue state and auto-proxy execution/idempotency markers.
- Do not reset current issue or auto-proxy markers; doing so can repeat same-period betting. System security/login/access logs and server log files are outside this cleanup scope.
- Fresh messages, draws and future orders may appear normally after restart. The cleanup cannot be rerun for the same extracted payload.

Do not run this maintenance as part of an ordinary deployment, automatically refund orders, or test financial writes against production.
