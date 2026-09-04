# Map Providers: Google Maps and Self-Hosted MapLibre Tile Servers

## Background: Why MapLibre?

Historically, all map-based views in Sailing Analytics — the **RaceBoard**, the
**Simulator**, the **EmbeddedMapAndWindChart**, and the **RibDashboards** — rendered
their basemaps through the [Google Maps JavaScript API](https://developers.google.com/maps).
Google Maps is a mature, high-quality product, but it is a poor fit for an
Open Source project like Sailing Analytics for several reasons:

* **Cost.** Google bills map loads per-view. For large, popular events this can
  become expensive very quickly, and the cost is essentially unbounded and driven
  by public interest rather than by us.
* **Credit card / API key requirement.** Using Google Maps requires a Google
  Cloud project with a valid API key that has a **credit card attached** for
  billing. That is awkward to hand to anyone who self-hosts Sailing Analytics and
  is at odds with the "clone and run" expectation of an Open Source project.
* **Branding.** The Google logo and attribution are mandatory and non-removable.
  In some contexts (neutral/white-label deployments, sponsor-sensitive events,
  print/broadcast overlays) third-party branding is discouraged or outright
  disallowed.

For these reasons we provide a **[MapLibre](https://maplibre.org/)-based
alternative**. It renders vector tiles from an
[OpenFreeMap](https://openfreemap.org)-style tile server. OpenFreeMap is a free,
open, self-hostable vector tile project — full credit and thanks go to the
[OpenFreeMap project](https://openfreemap.org) and its author for the tiles, the
"liberty" style, and the deployment tooling we build upon. There is no API key,
no billing, and no mandatory third-party logo, and — most importantly — you can
run the whole thing yourself.

## Selecting the Map Provider

Which provider a Sailing Analytics server uses is chosen **once, per server, at start-up** via the
`map.provider.type` system property, most conveniently set through the
`MAP_PROVIDER_TYPE` environment variable. The server communicates this choice to
the browser, so **all client views loaded from that server** (RaceBoard,
Simulator, EmbeddedMapAndWindChart, RibDashboards) use the selected provider — there
is no per-view or per-client switch.

Valid values (see the `MapProviderTypes` enum and the `Activator` in the
`com.sap.sailing.gwt.ui` bundle):

| `MAP_PROVIDER_TYPE` | Effect |
|---|---|
| `GOOGLE` | Google Maps |
| `MAPLIBRE` | MapLibre + OpenFreeMap vector tiles (the **default** when the property/variable is unset) |

```bash
# Serve MapLibre-based maps from this server
export MAP_PROVIDER_TYPE=MAPLIBRE
```

In an EC2 context, you may put this variable into your EC2 user data as

```bash
MAP_PROVIDER_TYPE=GOOGLE
```

### Google Maps: providing the API key

When `MAP_PROVIDER_TYPE=GOOGLE`, the Google Maps JavaScript API needs
authentication parameters. Provide them through the
`google.maps.authenticationparams` system property, or the
`GOOGLE_MAPS_AUTHENTICATION_PARAMS` environment variable (the system property
takes precedence if both are set).

The value is the query-string fragment that the Google Maps loader appends, e.g.:

```bash
export GOOGLE_MAPS_AUTHENTICATION_PARAMS='key=AIza...'
# or, for a client-ID/channel setup:
export GOOGLE_MAPS_AUTHENTICATION_PARAMS='client=gme-xxxx&channel=sapsailing'
```

You obtain a key (and, if applicable, a client ID/channel) from the
[Google Cloud Console](https://console.cloud.google.com) by creating a project,
enabling the *Maps JavaScript API*, attaching a billing account (credit card),
and creating an API key. Restrict the key to your domains.

### MapLibre: choosing the tile server

When `MAP_PROVIDER_TYPE=MAPLIBRE` (or unset, as this is the default), the browser fetches a MapLibre **vector style
document**, which in turn references the tile endpoints. The style URL defaults to
the public OpenFreeMap "liberty" style:

```
https://tiles.openfreemap.org/styles/liberty
```

The public OpenFreeMap endpoint is free and requires no key, but it is offered
**with no SLA / no uptime guarantee** and you may consider this not appropriate to depend on for a
production event. You can therefore point at a different tile server (your own,
or ours) with the `map.provider.tileserver` system property /
`MAP_PROVIDER_TILESERVER` environment variable. The value must be a full MapLibre
**style** URL:

```bash
export MAP_PROVIDER_TYPE=MAPLIBRE
export MAP_PROVIDER_TILESERVER='https://maptiles.sapsailing.com/styles/liberty'
```

## Using the secured `maptiles.sapsailing.com` tile server

`https://maptiles.sapsailing.com/styles/liberty` is our **own** OpenFreeMap
deployment, and unlike the public endpoint it is **access-controlled**. Tile
requests must carry a short-lived, signed access token (implemented as an NGINX
`secure_link`), so simply pointing `MAP_PROVIDER_TILESERVER` at it is not enough —
the sailing server has to be able to *mint* valid tokens. That requires at least two of the following
additional settings, which must be kept consistent with the tile server's own
configuration:

| System property | Meaning |
|---|---|
| `map.provider.tileserver.auth.secrets` | Comma-separated `kid:secret` list, e.g. `k1:AbC-123_xyz,k2:Def-456_uvw`. Must match the tile server's `TILE_AUTH_SECRETS`. Secrets and key IDs ("kids") use the `[A-Za-z0-9_-]` alphabet. |
| `map.provider.tileserver.auth.kid` | The **id of the secret currently used to sign** newly minted tokens. Must be one of the key IDs (`kid`s) present in `…auth.secrets` *and* accepted by the tile server. |
| `map.provider.tileserver.auth.ttl` | Token lifetime (seconds); the client refreshes at half of it. Optional; falls back to the minter's default. |

If `…auth.secrets` or `…auth.kid` is unset/blank, token minting stays disabled
and the client sends no auth headers — fine for the public/unsecured OpenFreeMap
endpoint, but such a server cannot talk to `maptiles.sapsailing.com`.

> **The `kid` and `TTL` on the sailing server side must match the tile server's
> configuration.** The tile server rejects tokens signed with a `kid` it does not
> know, and the TTL / bucketing has to agree so that tokens validate for their
> whole lifetime. This coupling is exactly what makes secret rotation a
> multi-step, ordered process (see below).

## Setting up your own tile server

You can stand up your own OpenFreeMap tile server (this is how
`maptiles.sapsailing.com` itself is built). The authoritative, step-by-step
instructions live in the **`AL2023` branch** of
[`github.com/axeluhl/openfreemap`](https://github.com/axeluhl/openfreemap/tree/AL2023),
in [`docs/self_hosting.md`](https://github.com/axeluhl/openfreemap/blob/AL2023/docs/self_hosting.md).
Follow in particular
[**Baking a golden AMI for an Auto Scaling Group behind an ALB**](https://github.com/axeluhl/openfreemap/blob/AL2023/docs/self_hosting.md#baking-a-golden-ami-for-an-auto-scaling-group-behind-an-alb).

In short, the workflow is: bake an Amazon Linux 2023 (arm64) instance with the
planet/tiles baked onto its root volume, snapshot it into a **golden AMI**, and
run that AMI as an Auto Scaling Group of small instances behind an Application
Load Balancer (ALB), with the ALB health check pointed at `/healthz/planet`.

### Our current `eu-west-1` configuration

* **Region:** `eu-west-1`
* **Target group:** `maptiles`
* **Fleet:** **two `t4g.small` instances** spread across **two Availability
  Zones**, managed by the **`OpenFreeMap Tile Server` Auto Scaling Group**.
* The ASG's **launch template** carries the user-data that configures the
  `secure_link` **secret(s)** (the `kid:secret` list, i.e. `TILE_AUTH_SECRETS`)
  that the whole server fleet will accept. Changing which secrets the fleet
  honours therefore means publishing a **new launch template version** and
  cycling instances onto it (see rotation, below).

## Rotating tile-server secrets

Because the sailing servers sign tokens with a `kid` and the tile fleet only
accepts tokens signed with `kid`s it knows, rotation has to add the new secret
to **both sides while both old and new remain valid**, and only then retire the
old one. Detailed guidance is again in the `openfreemap` `AL2023` branch's
[`docs/self_hosting.md`, section *Restricting access with short-lived tokens*](https://github.com/axeluhl/openfreemap/blob/AL2023/docs/self_hosting.md#restricting-access-with-short-lived-tokens-optional).
The authoritative explanation of the **in-place live rotation** on running
servers used by Option B below is a separate step — the item titled
*"(Optional) Rotate the secret live, without replacing instances"* near the end
of the [*Baking a golden AMI for an Auto Scaling Group behind an ALB*](https://github.com/axeluhl/openfreemap/blob/AL2023/docs/self_hosting.md#baking-a-golden-ami-for-an-auto-scaling-group-behind-an-alb)
step list (it is referenced here by title rather than by number, as the ordered
list's item numbers shift over time).
The end-to-end procedure is a **two-phase** roll:

### Phase 1 — roll the new secret onto the tile fleet

1. Create a **new launch template version** for the `OpenFreeMap Tile Server`
   ASG that **adds** a new `kid:secret` (keep the existing ones — do not remove
   anything yet).
2. Get the new secret onto the **running** fleet. Either of these works; the new
   launch template version from step 1 ensures the change survives future
   scale-outs and instance replacements regardless of which you pick:
   * **Option A — replace instances (ASG scale-out).** Increase the ASG's
     **desired capacity from 2 to 4**. Two fresh instances boot from the new
     launch template version and therefore accept both the old and the new
     secrets. Once the two new instances are healthy in the `maptiles` target
     group, **dismiss the two old instances** (scale desired back down to 2, or
     let the ASG replace them).
   * **Option B — live in-place update (no replacement).** Run OpenFreeMap's
     [`rotate-tile-auth-secrets.sh`](https://github.com/axeluhl/openfreemap/blob/AL2023/docs/self_hosting.md#baking-a-golden-ami-for-an-auto-scaling-group-behind-an-alb)
     helper, pointing it at our target group (with an **authenticated `aws` CLI**
     and SSH access to the fleet), e.g.
     `TILE_AUTH_SECRETS='k1:old-secret,k2:new-secret' ./rotate-tile-auth-secrets.sh --target-group maptiles --region eu-west-1`.
     It discovers the instances registered in the target group, SSHes into each,
     rewrites their accepted secrets and gracefully reloads NGINX — no AMI bake
     and no instance cycling.
   Either way, the fleet now uniformly accepts old **and** new `kid`s.

### Phase 2 — roll the new secret onto the sailing servers, then retire the old

4. Distribute the same new `kid:secret` to **every running sailing server** with
   the [`configuration/addSecret`](../../../configuration/addSecret) script, which
   appends it to each server's `configuration/secrets` (and to `/root/secrets`).
   Also set/update it manually in the master secrets file at
   `root@sapsailing.com:secret`, so future deployments inherit it.
5. **Restart / rotate all running sailing servers** so they pick up the new
   secret and begin signing with the new `kid`. Include **ARCHIVE**, and finally
   the **ARCHIVE failover** instance.
6. **Only after every sailing server has been rotated onto the new `kid`** may
   the **old `kid`s be removed from the tile fleet** — repeat Phase 1 with a
   launch template version that drops the retired secret. Removing an old secret
   before all signers have moved off it would break tiles for any server still
   signing with it.

### Optional: live-updating secrets without replacing instances

The live in-place update via `rotate-tile-auth-secrets.sh` is described as
**Option B** of Phase 1 above: it updates the running fleet's accepted secrets
without an AMI bake or instance cycling. Prefer it for a fast rotation or a quick
fix — but always pair it with a new **launch template version** (Phase 1, step 1)
so the change survives the next scale-out or instance replacement.
