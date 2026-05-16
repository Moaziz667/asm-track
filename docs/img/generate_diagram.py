import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.patches import FancyBboxPatch, Rectangle

fig, ax = plt.subplots(figsize=(16, 9))
ax.set_xlim(0, 16)
ax.set_ylim(0, 9)
ax.axis('off')
fig.patch.set_facecolor('white')

# ── Helpers ──────────────────────────────────────────────────────────────────
def rbox(x, y, w, h, fc='#DBEAFE', ec='#3B82F6', lw=1.5, ls='-', radius=0.15, hatch=None, alpha=1.0):
    p = FancyBboxPatch((x, y), w, h,
        boxstyle=f"round,pad={radius}",
        facecolor=fc, edgecolor=ec, linewidth=lw, linestyle=ls,
        hatch=hatch, alpha=alpha, zorder=2)
    ax.add_patch(p)

def label(x, y, txt, fs=9, fw='normal', color='#1E293B', ha='center', va='center'):
    ax.text(x, y, txt, fontsize=fs, fontweight=fw, color=color,
            ha=ha, va=va, zorder=3, linespacing=1.4)

def arrow(x1, y1, x2, y2, color='#475569', lw=1.3):
    ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
        arrowprops=dict(arrowstyle='->', color=color, lw=lw),
        zorder=4)

def db_cylinder(cx, cy, rx=0.28, ry=0.12, h=0.55, fc='#FCA5A5', ec='#DC2626'):
    # body
    body = Rectangle((cx-rx, cy), rx*2, h, facecolor=fc, edgecolor=ec, linewidth=1.2, zorder=2)
    ax.add_patch(body)
    # bottom ellipse
    bot = mpatches.Ellipse((cx, cy), rx*2, ry*2, facecolor=fc, edgecolor=ec, linewidth=1.2, zorder=3)
    ax.add_patch(bot)
    # top ellipse
    top = mpatches.Ellipse((cx, cy+h), rx*2, ry*2, facecolor='#FECACA', edgecolor=ec, linewidth=1.2, zorder=3)
    ax.add_patch(top)
    ax.text(cx, cy+h/2, 'DB', ha='center', va='center', fontsize=7, fontweight='bold', color='#7F1D1D', zorder=4)

def tall_cylinder(cx, cy, rx=0.45, ry=0.18, h=5.8, fc='#FEF9C3', ec='#EAB308', lw=2):
    body = Rectangle((cx-rx, cy), rx*2, h, facecolor=fc, edgecolor=ec, linewidth=lw, zorder=2)
    ax.add_patch(body)
    bot = mpatches.Ellipse((cx, cy), rx*2, ry*2, facecolor=fc, edgecolor=ec, linewidth=lw, zorder=3)
    ax.add_patch(bot)
    top = mpatches.Ellipse((cx, cy+h), rx*2, ry*2, facecolor='#FEF08A', edgecolor=ec, linewidth=lw, zorder=3)
    ax.add_patch(top)

def person_icon(cx, cy, color='#475569', size=0.35):
    # head
    head = mpatches.Circle((cx, cy+size*1.55), size*0.45, facecolor=color, edgecolor='none', zorder=3)
    ax.add_patch(head)
    # body (triangle/trapezoid)
    body = plt.Polygon(
        [[cx-size*0.55, cy], [cx+size*0.55, cy], [cx+size*0.35, cy+size*1.1], [cx-size*0.35, cy+size*1.1]],
        facecolor=color, edgecolor='none', zorder=3)
    ax.add_patch(body)

# ── Section backgrounds ──────────────────────────────────────────────────────
# Outer frame
rbox(0.15, 0.3, 15.7, 8.4, fc='#FFFFFF', ec='#334155', lw=2, radius=0.2)

# Client Apps section
rbox(0.25, 0.4, 2.8, 8.2, fc='#F8FAFC', ec='#94A3B8', lw=1.5, ls='--', radius=0.15)
label(1.65, 8.35, 'Client Apps', fs=11, fw='bold', color='#334155')

# Microservices section
rbox(5.1, 0.4, 8.0, 8.2, fc='#F0F9FF', ec='#7DD3FC', lw=1.5, ls='--', radius=0.15)
label(9.1, 8.35, 'Microservices', fs=11, fw='bold', color='#0369A1')

# ── Client Apps ──────────────────────────────────────────────────────────────
person_icon(1.65, 5.9, color='#64748B', size=0.38)
rbox(0.85, 4.8, 1.6, 0.75, fc='#E2E8F0', ec='#94A3B8', lw=1.2)
label(1.65, 5.18, 'Admin App', fs=9, fw='bold')

person_icon(1.65, 2.5, color='#64748B', size=0.38)
rbox(0.85, 1.45, 1.6, 0.75, fc='#E2E8F0', ec='#94A3B8', lw=1.2)
label(1.65, 1.83, 'Driver App', fs=9, fw='bold')

# ── API Gateway (tall hatched box) ────────────────────────────────────────────
rbox(3.3, 1.3, 1.5, 6.2, fc='#DCFCE7', ec='#16A34A', lw=2, hatch='///', radius=0.1)
label(4.05, 4.4, 'API\nGateway', fs=10, fw='bold', color='#14532D')

# ── Microservices rows ────────────────────────────────────────────────────────
services = [
    ('AppBackend',            ':8080', 6.5),
    ('DeliveryMicroservice',  ':8082', 4.9),
    ('DriverService',         ':8086', 3.3),
    ('ErpAdapterService',     ':8088', 1.7),
]

for name, port, y in services:
    rbox(5.3, y, 5.0, 1.1, fc='#DBEAFE', ec='#3B82F6', lw=1.5)
    label(7.8, y+0.55, f'{name}\n{port}', fs=9, fw='bold', color='#1E3A5F')
    db_cylinder(cx=10.55, cy=y+0.18)

# ── auth-server (tall cylinder, right side) ───────────────────────────────────
tall_cylinder(cx=13.55, cy=1.3, rx=0.6, ry=0.22, h=5.8)
# rotated label
ax.text(13.55, 4.2, 'auth-server\n:8089\n\nOAuth2\nRSA JWTs',
        ha='center', va='center', fontsize=8.5, fontweight='bold',
        color='#713F12', zorder=4, linespacing=1.5)

# ── External (far right) ──────────────────────────────────────────────────────
ext_items = [('Odoo ERP', 6.5), ('Firebase FCM', 4.9), ('OSRM', 3.3)]
for txt, y in ext_items:
    rbox(14.45, y, 1.3, 0.9, fc='#F3E8FF', ec='#9333EA', lw=1.2)
    label(15.1, y+0.45, txt, fs=8, fw='bold', color='#581C87')

# ── Arrows ────────────────────────────────────────────────────────────────────
# Clients → Gateway
arrow(2.45, 5.18, 3.28, 5.5, color='#475569')
arrow(2.45, 1.83, 3.28, 3.0, color='#475569')

# Gateway → each service (center y of each row)
for _, _, y in services:
    arrow(4.82, y+0.55, 5.28, y+0.55, color='#1D4ED8')

# Services → auth-server (dashed orange)
for _, _, y in services:
    ax.annotate('', xy=(12.95, y+0.55), xytext=(10.32, y+0.55),
        arrowprops=dict(arrowstyle='->', color='#F97316', lw=1.0, linestyle='dashed'),
        zorder=4)

# auth-server → Gateway (JWKS, dashed)
ax.annotate('', xy=(4.82, 7.4), xytext=(12.95, 7.0),
    arrowprops=dict(arrowstyle='->', color='#F97316', lw=1.2, linestyle='dashed'),
    zorder=4)
label(9.0, 7.35, 'JWKS', fs=7.5, color='#C2410C')

# DeliveryService → External
arrow(10.32, 4.9+0.55, 14.43, 4.9+0.45, color='#7C3AED')
arrow(10.32, 6.5+0.55, 14.43, 6.5+0.45, color='#7C3AED')
arrow(10.32, 3.3+0.55, 14.43, 3.3+0.45, color='#7C3AED')

# ── Section labels (bottom) ───────────────────────────────────────────────────
label(4.05, 0.55, 'API Gateway', fs=8, fw='bold', color='#14532D')
label(13.55, 0.95, 'Identity Provider', fs=7.5, fw='bold', color='#713F12')
label(15.1, 0.95, 'External', fs=8, fw='bold', color='#581C87')

plt.tight_layout(pad=0)
plt.savefig('c:/Users/M S I/OneDrive/Documents/PFE/docs/img/container-diagram.png',
            dpi=180, bbox_inches='tight', facecolor='white')
print("Done.")
