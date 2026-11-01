# Lucky_chenguanxi IP HTTPS

This directory applies only to the `chen-guan-xi` customer at `43.135.38.59`.
Do not deploy it to Lucky_qinqi.

The existing frontend Nginx continues to serve port 80. `nginx-tls.conf` adds
port 443 with the Let's Encrypt IP certificate and forwards requests to the
same frontend Nginx, preserving the existing app and API routes.

On the server, `deploy-customer/compose.customer.yaml` must publish `443:443`
and mount `/etc/letsencrypt` read-only at the same path plus
`/opt/lucky5/deploy/chenguanxi/nginx-tls.conf` at
`/etc/nginx/conf.d/tls.conf`. Keep the existing port 80 for HTTP-01 renewal
and old links.

Certbot 5.4+ issues the IP certificate with the `shortlived` profile. The
renewal configuration uses `acme-auth.sh` and `acme-cleanup.sh` to serve the
HTTP-01 challenge from the running frontend container. The snap renewal timer
and a deploy hook running `reload-nginx.sh` keep Nginx on the renewed cert.
Check renewal with `sudo /snap/bin/certbot renew --dry-run --cert-name
43.135.38.59` after any deployment changes.
