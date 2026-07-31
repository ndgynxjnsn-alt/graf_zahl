# Graf Zahl — 3-node HA cluster (Vagrant + Ansible)

The single-node stack (`../docker-compose.yml`) turned into a **highly-available,
on-premise 3-server cluster**: real gossip rings across the nodes, clustered S3
object storage, a floating virtual IP, a reverse proxy, and multi-tenancy —
developed on scaled-down Vagrant VMs and configured entirely by replayable
Ansible playbooks.

```
                          ┌─────────── VIP 192.168.56.10 (keepalived) ───────────┐
        clients ────────► │                    Traefik (TLS)                     │
                          └───┬───────────────────┬───────────────────┬──────────┘
                     ┌────────┴───────┐   ┌────────┴───────┐   ┌───────┴────────┐
                     │ node1 .11      │   │ node2 .12      │   │ node3 .13      │
                     │ MASTER         │   │ BACKUP         │   │ BACKUP         │
                     │ app=production │   │ app=test       │   │ (backends only)│
                     ├────────────────┤   ├────────────────┤   ├────────────────┤
                     │ Garage  Loki   │   │ Garage  Loki   │   │ Garage  Loki   │
                     │ Mimir   Tempo  │◄─►│ Mimir   Tempo  │◄─►│ Mimir   Tempo  │  gossip rings
                     │ Pyroscope      │   │ Pyroscope      │   │ Pyroscope      │
                     │ Grafana Traefik│   │ Grafana Traefik│   │ Grafana Traefik│
                     └────────────────┘   └────────────────┘   └────────────────┘
                        1×SSD + 4×HDD        1×SSD + 4×HDD        1×SSD + 4×HDD
```

## Run it

```bash
cd cluster
ansible-galaxy collection install -r requirements.yml   # once
vagrant up                                              # 3 scaled-down VMs
ansible-playbook -i inventory.ini site.yml              # configure everything
```

Then (add the sslip.io hostnames — they resolve to the VIP, no DNS needed):

- Grafana → **https://grafana.192-168-56-10.sslip.io** (anonymous admin)
- Garage S3 → **https://s3.192-168-56-10.sslip.io**
- Traefik dashboard → http://192.168.56.10:8080

Re-run `ansible-playbook site.yml` any time — it's idempotent (that's the point).

### Real hardware

The playbook is identical; only the Vagrant dev sizing is scaled down. On the real
servers: point `inventory.ini` at the machines with your SSH creds, set the real
sizes (`garage_hdd_capacity`, `garage_node_capacity` in `group_vars/all.yml`),
confirm the disk device order in `roles/common` matches (SSD + 4 HDDs), and run
`ansible-playbook site.yml`. No Vagrant involved.

## What changed vs. the single node

| Concern | Single node | Cluster |
|---|---|---|
| **Ring** | `instance_addr: 127.0.0.1` (fake) | real memberlist gossip; each backend advertises its **LAN IP** and joins the 3 nodes |
| **Storage** | local filesystem | **Garage** clustered S3, `replication_factor=3` — metadata on SSD, data across the 4 HDDs |
| **Tenancy** | single tenant | **multi-tenant**: `production` & `test` are two tenants (`X-Scope-OrgID`), enforced end-to-end |
| **HA** | one box | **keepalived** VIP + **Traefik** LB across 3 nodes; survives one node down (ring quorum 2) |
| **Config delivery** | bind mounts | rendered by **Ansible** onto each node |

### The gossip-port map (why ports are spread out)

Loki, Mimir, Tempo and Pyroscope all default memberlist to `7946` and gRPC to
`9095`. With host networking they'd collide, so each gets its own ports:

| | HTTP | gRPC | memberlist |
|---|---|---|---|
| Loki | 3100 | 9096 | 7946 |
| Mimir | 9009 | 9095 | 7947 |
| Tempo | 3200 | 9195 | 7948 |
| Pyroscope | 4040 | 9295 | 7949 |

Garage: S3 3900 / RPC 3901 / web 3902 / admin 3903. (Full map: `group_vars/all.yml`.)

## Layout

```
Vagrantfile          # 3 VMs, fixed IPs, 1 SSD + 4 HDD virtual disks (scaled down)
inventory.ini        # node1..3
group_vars/all.yml   # single source of truth: IPs, VIP, ports, versions, tenants, disks, secrets
site.yml             # runs every role, in order
roles/
  common       garage         keepalived   grafana
  docker       grafana_stack  traefik      app
```

## Gossip ring isolation & healing (learned the hard way)

Running four dskit apps (Loki/Mimir/Tempo/Pyroscope) on the same hosts needs two
things, both handled in the rendered configs:

- **Distinct `cluster_label` per app** — without it the four memberlist clusters
  cross-contaminate (Mimir's ring ends up with Pyroscope/Loki addresses) and rings
  go unhealthy. Each app gets its own label.
- **`rejoin_interval`** so a ring self-heals after **staggered** restarts. Ansible
  restarts services node-by-node; without periodic rejoin, dskit rings can be left
  with `UNHEALTHY` instances. If a ring ever looks stuck, restarting one backend on
  all nodes at once fixes it instantly:
  `ansible -i inventory.ini all -b -m shell -a "docker restart mimir"`.

## Notes & decisions

- **MinIO → Garage.** MinIO's community edition was archived (April 2026); Garage is
  actively maintained and fits the 1×SSD + 4×HDD layout exactly (metadata on SSD,
  `data_dir` list across the HDDs, replication instead of erasure coding).
- **Tempo 2.10 in the cluster.** Its classic distributor→ingester memberlist ring
  clusters cleanly with shared S3. Tempo 3.0's multi-node path wants Kafka; the
  single-node dev stack still runs 3.0 monolithic.
- **Tenancy is per-node here**: `production` runs on node1, `test` on node2, so each
  node's Alloy is single-tenant and just stamps a static `X-Scope-OrgID`. Grafana
  carries **per-tenant datasources**; the dashboard's **Tenant** selector switches
  between them.
