# Server & Channels

TAK-XVoice uses **Mumble (Murmur)** for server-mediated voice alongside your
existing **TAK Server** deployment. This page documents the current server
layout requirements, TLS authentication model, and recommended channel
management practices.

## Mumble Host & Port Requirements

!!! important "Same Hostname & Standard Port Required"
    For the current release, the Mumble server **must be hosted on the same
    host — or be accessible via the same hostname** — as your TAK Server, and
    must listen on the **standard Mumble port `64738` (TCP and UDP)**.

### Why this is required right now

TAK-XVoice automatically discovers your TAK Server(s) from ATAK's own
connection list (`TAKServerListener`) and derives the Mumble endpoint directly
from the TAK Server's hostname:

| Parameter | Value | Notes |
| --- | --- | --- |
| **Hostname** | Same hostname as your TAK Server | Derived automatically from ATAK's configured TAK Server connection string (e.g., `tak.example.com`). |
| **Port** | **`64738`** (TCP + UDP) | Standard Mumble port. TCP carries the TLS control channel (and fallback voice); UDP carries low-latency voice packets. |
| **Transport security** | TLS (mutual / client cert) | Uses your device's ATAK enrollment certificate. |

If your Mumble server runs on a separate backend machine or container, route
traffic for `<your-tak-hostname>:64738` (via reverse proxy, port forwarding, or
shared ingress) so clients reach Mumble on the same hostname as the TAK Server.

### Multiple TAK Servers

If ATAK is configured with more than one TAK Server, XV picks a server in this
order:

1. **Preferred host** — if you explicitly selected a TAK Server in **XV
   Settings**, XV honors that exact hostname.
2. **First connected TAK Server** — otherwise, XV picks the first currently
   connected server.
3. **First configured TAK Server** — if ATAK is not actively connected to CoT,
   XV falls back to the first configured server so voice can still connect
   using the stored enrollment certificate.

## TLS Authentication & Hostname Verification

- **Client certificate:** XV authenticates to Mumble using the device's ATAK
  enrollment certificate (checking the server's specific certificate entry
  first, then falling back to ATAK's default client/trust certificates when a
  data package only populates the global entries).
- **Strict hostname verification:** XV refuses to connect to a Mumble server
  whose TLS certificate does not match the hostname it is connecting to. Ensure
  the Mumble server presents a certificate whose SAN/CN covers the TAK Server
  hostname and chains to a CA trusted by ATAK.

## Channel Management & TAK Group Synchronization

Voice **channels are configured and managed by Mumble**, not created or stored
inside TAK-XVoice itself. Operators see and join the channel tree exposed by
the Mumble server.

### Recommended: Synchronize Mumble Channels with TAK Server Groups

While you can manage Mumble channels manually, **we recommend running an
integration or synchronization interface** that maps your **TAK Server groups**
(or an existing organizational / identity group structure managed outside
Mumble) into Mumble channels and ACLs.

Benefits of automated group-to-channel synchronization:

- **Single source of truth:** Admins add or move operators in TAK Server (or
  their upstream directory/MDM) without manually mirroring changes in Murmur.
- **Consistent access control:** Mumble channel ACLs stay aligned with TAK
  group membership so operators automatically see the voice channels for their
  assigned teams.
- **Faster event spin-up:** Standing up a new event or mission group
  provisions the corresponding voice channel structure automatically.

*(For example, OpenTAKServer and similar TAK stacks can host Murmur alongside
the TAK Server on the same hostname and automate channel/group management.)*

### Mission Auto-Channels (Opt-In)

TAK-XVoice also includes an opt-in **Mission Auto-Channels** toggle (off by
default):

- When enabled, the operator's active ATAK mission drives the primary voice
  channel — XV derives a deterministic channel name from the mission name,
  creates it on the Mumble server if it does not already exist (and the server
  permits channel creation), and joins it.
- Manually switching to another channel hands control back to the operator
  until the active mission changes.
- If the Mumble server denies channel creation, XV notifies the operator rather
  than failing silently.
