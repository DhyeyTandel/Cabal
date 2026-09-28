# Cloudflare Tunnel

The server's `cloudflared` is a **dashboard-managed tunnel**: it runs as

```
cloudflared tunnel run --token-file /etc/cloudflared/token
```

with no local ingress config. Routes are not added to a file on the server at
all; they are added in the Cloudflare dashboard, and the tunnel picks them up
on its own.

## Adding the route for Cabal

1. Go to the Cloudflare dashboard, **Networks > Tunnels**, and open the
   server's tunnel.
2. Under **Published application routes**, click **Add** and fill in:
   - **Subdomain:** `cabal`
   - **Domain:** `dhyeytandel.in`
   - **Type:** `HTTP`
   - **URL:** `127.0.0.1:8080` (the port `setup-server.sh` printed, if it had
     to pick something other than 8080)

No restart or DNS command is needed on the server; the dashboard change takes
effect on its own, and the proxied DNS record is created automatically.

### Why `HTTP`, not `HTTPS`, for that last hop

`127.0.0.1:8080` is Cabal talking to `cloudflared` inside the same machine,
never anything that leaves it, so there is nothing to encrypt on that hop.
Visitors still get HTTPS: Cloudflare terminates TLS at its edge and the request
travels the encrypted tunnel to `cloudflared`, then to `127.0.0.1:8080`.
Pointing the route at `HTTPS` would just make the connection fail, because
Cabal has no TLS listener of its own.

## Always Use HTTPS and HSTS

Under **SSL/TLS > Edge Certificates** on the `dhyeytandel.in` zone, turn on
**Always Use HTTPS** and **HTTP Strict Transport Security (HSTS)**. Both are
zone-wide settings, done once, not per route.

## Swapping the tunnel token

If the tunnel token is ever rotated, or the tunnel is recreated in another
account:

```bash
sudo tee /etc/cloudflared/token > /dev/null   # paste the new token, then Ctrl-D
sudo systemctl restart cloudflared
```

## Other services on the machine

Only Cabal is published through the tunnel. Never add a published application
route for any other service on the machine; keep private services reachable
only over a private network.
