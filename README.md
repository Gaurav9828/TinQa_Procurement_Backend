# TinQa Procurement Backend

Rules for every change: [docs/engineering-rules.md](docs/engineering-rules.md).

## Token signing keys (UAT and production)

Procurement signs every sign-in token (admin, supplier, inspector) with an Ed25519 private key (EdDSA). Other
services verify admin tokens with the public key only, which Procurement publishes at `GET /api/auth/jwks`. So the
Ecommerce backend can check admin tokens but cannot create them.

| Variable | Value |
|---|---|
| `TINQA_SECURITY_JWT_PRIVATE_KEY` | base64 PKCS#8 DER Ed25519 private key. Required; never commit it. |
| `TINQA_SECURITY_JWT_PUBLIC_KEY` | base64 X.509 DER public key of the same pair. Required. |
| `TINQA_SECURITY_JWT_EXPIRATION` | token lifetime in ms (default 86400000) |

Generate a pair:

```bash
openssl genpkey -algorithm ed25519 -outform DER -out jwt-private.der
openssl pkey -inform DER -in jwt-private.der -pubout -outform DER -out jwt-public.der
base64 < jwt-private.der | tr -d '\n'   # TINQA_SECURITY_JWT_PRIVATE_KEY
base64 < jwt-public.der | tr -d '\n'    # TINQA_SECURITY_JWT_PUBLIC_KEY (also PROCUREMENT_JWT_PUBLIC_KEY in Ecommerce)
```

`TINQA_SECURITY_JWT_SECRET` (the old shared HMAC secret) is no longer read: remove it from `procurement.env`. After
the first deploy with keys, every staff member and supplier signs in once again (tokens signed the old way are
refused).

In dev no key is needed: a pair is generated once and kept in `~/.tinqa/dev-jwt-ed25519`. Tests generate one in
memory.
