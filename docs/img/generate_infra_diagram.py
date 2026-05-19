"""
Generates a clean infrastructure topology diagram for docs/08-infrastructure-deployment.md
Saves PNG to docs/img/infra-topology.png
"""
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch
from matplotlib.lines import Line2D

# ── Canvas ────────────────────────────────────────────────────────────────────
fig, ax = plt.subplots(figsize=(16, 9), dpi=140)
ax.set_xlim(0, 100)
ax.set_ylim(0, 56)
ax.axis('off')
ax.set_facecolor('white')

# ── Style ─────────────────────────────────────────────────────────────────────
COL = {
    'client':   '#E0F2FE',   'client_b':   '#0284C7',
    'gateway':  '#FEF3C7',   'gateway_b':  '#D97706',
    'auth':     '#FFE4E6',   'auth_b':     '#E11D48',
    'core':     '#DBEAFE',   'core_b':     '#2563EB',
    'broker':   '#FAE8FF',   'broker_b':   '#A21CAF',
    'storage':  '#DCFCE7',   'storage_b':  '#16A34A',
    'routing':  '#FFEDD5',   'routing_b':  '#EA580C',
    'external': '#F3E8FF',   'external_b': '#7C3AED',
}

def box(x, y, w, h, label, sub, fill, edge, weight='bold', fs=10, sub_fs=8):
    p = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.4,rounding_size=0.6",
                       linewidth=1.8, edgecolor=edge, facecolor=fill)
    ax.add_patch(p)
    ax.text(x + w/2, y + h/2 + 0.6, label, ha='center', va='center',
            fontsize=fs, fontweight=weight, color='#0F172A')
    if sub:
        ax.text(x + w/2, y + h/2 - 1.2, sub, ha='center', va='center',
                fontsize=sub_fs, color='#475569')

def group_label(x, y, text, color):
    ax.text(x, y, text, fontsize=9, fontweight='bold',
            color=color, alpha=0.85)

def arrow(x1, y1, x2, y2, color='#64748B', style='-', lw=1.3, alpha=0.85):
    a = FancyArrowPatch((x1, y1), (x2, y2),
                        arrowstyle='-|>', mutation_scale=14,
                        linewidth=lw, color=color, linestyle=style, alpha=alpha,
                        connectionstyle="arc3,rad=0")
    ax.add_patch(a)

# ── Layout ────────────────────────────────────────────────────────────────────
# Y-bands
# 48-54  Clients
# 36-42  Gateway + Auth
# 22-32  Core services
# 12-20  Messaging + Storage + Routing
# 1-9    External

# Clients (top)
group_label(4, 53.5, "CLIENT APPS", COL['client_b'])
box( 4, 47, 14, 5, 'Admin App',  'Next.js · :3000', COL['client'], COL['client_b'])
box(20, 47, 14, 5, 'Driver App', 'Flutter',          COL['client'], COL['client_b'])

# Gateway
group_label(42, 42.5, "ENTRY POINT", COL['gateway_b'])
box(42, 36, 16, 5, 'API Gateway', ':80 · Spring Cloud', COL['gateway'], COL['gateway_b'])

# Auth
group_label(66, 42.5, "IDENTITY", COL['auth_b'])
box(66, 36, 16, 5, 'auth-server', ':8089 · OAuth2 + JWKS', COL['auth'], COL['auth_b'])

# Core microservices
group_label(4, 32.5, "CORE MICROSERVICES", COL['core_b'])
box( 4, 25, 13, 5, 'AppBackend',     ':8080', COL['core'], COL['core_b'])
box(20, 25, 13, 5, 'DeliveryMS',     ':8082', COL['core'], COL['core_b'])
box(36, 25, 13, 5, 'DriverService',  ':8086', COL['core'], COL['core_b'])
box(52, 25, 13, 5, 'ErpAdapter',     ':8088', COL['core'], COL['core_b'])

# Messaging
group_label(68, 32.5, "MESSAGING", COL['broker_b'])
box(68, 25, 16, 5, 'RabbitMQ', ':5672 AMQP · :61613 STOMP', COL['broker'], COL['broker_b'])

# Storage row
group_label(4, 20.5, "DATA STORES", COL['storage_b'])
box( 4, 13, 13, 5.5, 'postgres-app',      ':5435', COL['storage'], COL['storage_b'])
box(20, 13, 13, 5.5, 'postgres-delivery', ':5434', COL['storage'], COL['storage_b'])
box(36, 13, 13, 5.5, 'postgres-driver',   ':5437', COL['storage'], COL['storage_b'])
box(52, 13, 13, 5.5, 'MinIO',             ':9000 · S3 API', COL['storage'], COL['storage_b'])

# Routing
group_label(68, 20.5, "ROUTING", COL['routing_b'])
box(68, 13, 16, 5.5, 'OSRM', ':5000 · Tunisia map', COL['routing'], COL['routing_b'])

# External / Odoo
group_label(20, 9.5, "EXTERNAL · MULTI-TENANT ERP", COL['external_b'])
box(20, 2, 13, 5, 'Odoo 1', ':8069 · JSON-RPC', COL['external'], COL['external_b'])
box(36, 2, 13, 5, 'Odoo 2', ':8070 · JSON-RPC', COL['external'], COL['external_b'])

# ── Connections ───────────────────────────────────────────────────────────────
# Clients → Gateway
arrow(11, 47, 47, 41,  COL['client_b'])
arrow(27, 47, 50, 41,  COL['client_b'])

# Gateway → Core
arrow(50, 36, 10.5, 30, COL['core_b'])
arrow(50, 36, 26.5, 30, COL['core_b'])
arrow(50, 36, 42.5, 30, COL['core_b'])

# Gateway ↔ Auth
arrow(58, 38.5, 66, 38.5, COL['auth_b'])

# Core → Auth (client_credentials for inter-service tokens)
arrow(26.5, 30, 74, 36, COL['auth_b'], style=(0,(4,3)), alpha=0.5)
arrow(58.5, 30, 76, 36, COL['auth_b'], style=(0,(4,3)), alpha=0.5)

# DeliveryMS → DriverService, ErpAdapter
arrow(33, 27, 36, 27, COL['core_b'])
arrow(49, 27, 52, 27, COL['core_b'])

# DeliveryMS → RabbitMQ (STOMP relay)
arrow(33, 28, 68, 28, COL['broker_b'], lw=1.8)

# RabbitMQ → Clients (WS push)
arrow(76, 30, 11, 47, COL['broker_b'], style=(0,(5,3)), alpha=0.6)
arrow(76, 30, 27, 47, COL['broker_b'], style=(0,(5,3)), alpha=0.6)

# Core → Postgres (each its own)
arrow(10.5, 25, 10.5, 18.5, COL['storage_b'])
arrow(26.5, 25, 26.5, 18.5, COL['storage_b'])
arrow(42.5, 25, 42.5, 18.5, COL['storage_b'])

# DeliveryMS → MinIO
arrow(28, 25, 56, 18.5, COL['storage_b'])

# DeliveryMS → OSRM
arrow(33, 26.5, 68, 16, COL['routing_b'])

# ErpAdapter → Odoo
arrow(58.5, 25, 32.5, 7, COL['external_b'])
arrow(58.5, 25, 42, 7, COL['external_b'])

# ── Legend ────────────────────────────────────────────────────────────────────
legend_items = [
    Line2D([0],[0], color=COL['core_b'],    lw=2, label='HTTP / REST'),
    Line2D([0],[0], color=COL['broker_b'],  lw=2, label='STOMP / WebSocket'),
    Line2D([0],[0], color=COL['storage_b'], lw=2, label='Database / Storage'),
    Line2D([0],[0], color=COL['external_b'],lw=2, label='External JSON-RPC'),
    Line2D([0],[0], color=COL['auth_b'],    lw=2, linestyle='--', label='OAuth2 token'),
]
ax.legend(handles=legend_items, loc='lower right', frameon=True,
          fontsize=9, edgecolor='#CBD5E1', bbox_to_anchor=(0.98, 0.01))

# ── Title ─────────────────────────────────────────────────────────────────────
ax.text(50, 55.2, 'ASM Track — Infrastructure Topology',
        ha='center', va='center', fontsize=15, fontweight='bold', color='#0F172A')

plt.tight_layout()
out = r"C:\Users\M S I\OneDrive\Documents\PFE\docs\img\infra-topology.png"
plt.savefig(out, dpi=150, bbox_inches='tight', facecolor='white')
print(f"Saved: {out}")
