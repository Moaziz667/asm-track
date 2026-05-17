import base64, os
from PIL import Image

ICON_SRC = r"C:\Users\M S I\AppData\Local\Programs\Python\Python312\Lib\site-packages\resources"
ICON_DIR = r"C:\temp\asm_icons"
OUTPUT   = r"C:\Users\M S I\OneDrive\Documents\PFE\docs\img\container-diagram.svg"

os.makedirs(ICON_DIR, exist_ok=True)

ICONS = {
    'nextjs':   ('programming/framework/nextjs.png',  68),
    'flutter':  ('programming/framework/flutter.png', 68),
    'nginx':    ('onprem/network/nginx.png',           64),
    'spring':   ('programming/framework/spring.png',  68),
    'pg':       ('onprem/database/postgresql.png',    52),
    'minio':    ('generic/storage/storage.png',       52),
    'jwt':      ('generic/network/firewall.png',      58),
    'rack':     ('generic/compute/rack.png',          58),
    'firebase': ('firebase/base/firebase.png',        58),
}
for name, (rel, sz) in ICONS.items():
    img = Image.open(os.path.join(ICON_SRC, rel.replace('/', os.sep))).convert("RGBA").resize((sz, sz), Image.LANCZOS)
    img.save(os.path.join(ICON_DIR, f"{name}.png"))

def b64(name):
    with open(f"{ICON_DIR}/{name}.png", 'rb') as f:
        return base64.b64encode(f.read()).decode()

I = {k: b64(k) for k in ICONS}

# ── SVG primitives ────────────────────────────────────────────────────────────
def grp(content, gid='', opacity=1):
    attrs = f' id="{gid}"' if gid else ''
    return f'<g{attrs} opacity="{opacity}">{content}</g>'

def rct(x, y, w, h, fill, stroke, sw=1.8, rx=12, dash='', opacity=1):
    d = f' stroke-dasharray="{dash}"' if dash else ''
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" '
            f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d} opacity="{opacity}"/>')

def shadow(x, y, w, h, rx=12):
    return rct(x+4, y+4, w, h, '#00000018', 'none', rx=rx)

def ico(name, x, y):
    sz, _ = ICONS[name][1], None
    return f'<image x="{x}" y="{y}" width="{sz}" height="{sz}" href="data:image/png;base64,{I[name]}"/>'

def txt(x, y, content, size=11, weight='normal', fill='#111827', anchor='middle', dy=0):
    return (f'<text x="{x}" y="{y+dy}" text-anchor="{anchor}" dominant-baseline="auto" '
            f'font-family="Inter,Helvetica,Arial,sans-serif" font-size="{size}" '
            f'font-weight="{weight}" fill="{fill}">{content}</text>')

def arrowhead(mid, color):
    return (f'<marker id="{mid}" markerWidth="9" markerHeight="7" refX="8" refY="3.5" orient="auto">'
            f'<polygon points="0 0, 9 3.5, 0 7" fill="{color}"/></marker>')

mkr = arrowhead

def ortho(pts, color, sw=1.8, dash='', marker_id=''):
    coords = ' '.join(f'{"M" if i==0 else "L"}{p[0]},{p[1]}' for i,p in enumerate(pts))
    d = f' stroke-dasharray="{dash}"' if dash else ''
    m = f' marker-end="url(#{marker_id})"' if marker_id else ''
    return f'<path d="{coords}" stroke="{color}" stroke-width="{sw}" fill="none" stroke-linecap="round"{d}{m}/>'

# ── Layout constants ───────────────────────────────────────────────────────────
W, H = 1800, 1020


# Section x ranges
X_CLI_L, X_CLI_R   = 20,   230
X_GW_L,  X_GW_R   = 258,  375
X_SVC_L, X_SVC_R  = 398, 1490
X_EXT_L, X_EXT_R  = 1508, 1780

# X centers
CX_CLI  = (X_CLI_L + X_CLI_R) // 2        # 125
CX_GW   = (X_GW_L  + X_GW_R)  // 2        # 316
CX_SVC  = 555                              # service column
CX_DB1  = 780                              # first db column
CX_DB2  = 980                              # minio column
CX_EXT  = (X_EXT_L + X_EXT_R) // 2        # 1644

# Y row centers
Y = [195, 360, 515, 670, 825]
Y_AUTH, Y_AB, Y_DL, Y_DS, Y_EA = Y

FONT = "Inter, Helvetica, Arial, sans-serif"

# ── Build SVG ─────────────────────────────────────────────────────────────────
defs = f"""<defs>
  <filter id="shadow" x="-5%" y="-5%" width="115%" height="115%">
    <feDropShadow dx="2" dy="3" stdDeviation="4" flood-color="#00000020"/>
  </filter>
  {mkr('mb','#2563EB')}{mkr('mg','#16A34A')}{mkr('mo','#EA580C')}{mkr('mc','#0891B2')}{mkr('mp','#7C3AED')}
</defs>"""

# ── Section backgrounds ───────────────────────────────────────────────────────
sections = (
    rct(X_CLI_L, 55, X_CLI_R-X_CLI_L, 950, '#F8FAFC', '#9CA3AF', dash='10,5', rx=14) +
    txt(CX_CLI, 45, 'Client Apps', 14, 'bold', '#374151') +

    rct(X_SVC_L, 55, X_SVC_R-X_SVC_L, 950, '#EFF6FF', '#3B82F6', sw=2.2, rx=14) +
    txt((X_SVC_L+X_SVC_R)//2, 45, 'Core Microservices', 14, 'bold', '#1E40AF') +

    rct(X_EXT_L, 55, X_EXT_R-X_EXT_L, 950, '#FAF5FF', '#7C3AED', dash='10,5', sw=1.8, rx=14) +
    txt(CX_EXT, 45, 'External / Infrastructure', 14, 'bold', '#6D28D9')
)

# ── API Gateway — built after svc_card is defined below ──────────────────────

# ── Service card helper ───────────────────────────────────────────────────────
def svc_card(icon, cx, cy, name, port, fill, stroke, cw=152, ch=108):
    x, y = cx-cw//2, cy-ch//2
    sz = ICONS[icon][1]
    ix, iy = cx-sz//2, y+10
    return (
        shadow(x, y, cw, ch) +
        rct(x, y, cw, ch, fill, stroke, sw=2.0) +
        ico(icon, ix, iy) +
        txt(cx, iy+sz+14, name, 10, 'bold', '#111827') +
        txt(cx, iy+sz+28, port, 9, 'normal', '#6B7280')
    )

def db_card(icon, cx, cy, name, fill, stroke, cw=118, ch=90):
    x, y = cx-cw//2, cy-ch//2
    sz = ICONS[icon][1]
    ix, iy = cx-sz//2, y+8
    return (
        shadow(x, y, cw, ch) +
        rct(x, y, cw, ch, fill, stroke, sw=1.6) +
        ico(icon, ix, iy) +
        txt(cx, iy+sz+13, name, 8.5, 'normal', '#374151')
    )

def auth_card(cx, cy, cw=348, ch=108):
    x, y = cx-cw//2, cy-ch//2
    sz = ICONS['jwt'][1]
    ix, iy = x+20, cy-sz//2
    return (
        shadow(x, y, cw, ch) +
        rct(x, y, cw, ch, '#FFF7ED', '#F97316', sw=2.8) +
        ico('jwt', ix, iy) +
        txt(ix+sz+20, cy-16, 'auth-server', 15, 'bold', '#1C1917', 'start') +
        txt(ix+sz+20, cy+4,  ':8089',       10, 'normal', '#78716C', 'start') +
        txt(ix+sz+20, cy+22, 'OAuth2 · RSA JWT Issuer', 9, 'normal', '#EA580C', 'start')
    )

# ── API Gateway (square card) ─────────────────────────────────────────────────
gateway = svc_card('nginx', CX_GW, (Y_AB + Y_DS) // 2, 'API Gateway', ':80', '#DCFCE7', '#16A34A')

# ── All nodes ─────────────────────────────────────────────────────────────────
nodes = (
    svc_card('nextjs',  CX_CLI, Y_AB, 'Admin App',  'Next.js', '#F9FAFB', '#CBD5E1') +
    svc_card('flutter', CX_CLI, Y_DS, 'Driver App', 'Flutter', '#EFF9FF', '#BAE6FD') +

    auth_card(CX_SVC, Y_AUTH) +
    svc_card('spring', CX_SVC, Y_AB, 'AppBackend',           ':8080', '#F0FFF4', '#86EFAC') +
    svc_card('spring', CX_SVC, Y_DL, 'DeliveryMicroservice', ':8082', '#F0FFF4', '#86EFAC') +
    svc_card('spring', CX_SVC, Y_DS, 'DriverService',        ':8086', '#F0FFF4', '#86EFAC') +
    svc_card('spring', CX_SVC, Y_EA, 'ErpAdapterService',    ':8088', '#F0FFF4', '#86EFAC') +

    db_card('pg',    CX_DB1, Y_AB, 'postgres-app',      '#F0FDF4', '#16A34A') +
    db_card('pg',    CX_DB1, Y_DL, 'postgres-delivery', '#F0FDF4', '#16A34A') +
    db_card('pg',    CX_DB1, Y_DS, 'postgres-driver',   '#F0FDF4', '#16A34A') +
    db_card('minio', CX_DB2, Y_DL, 'MinIO',             '#FFF5F5', '#FCA5A5') +

    svc_card('rack',     CX_EXT, Y_AB, 'Odoo ERP',     'JSON-RPC 2.0',       '#FAF5FF', '#C4B5FD') +
    svc_card('firebase', CX_EXT, Y_DL, 'Firebase FCM', 'Push Notifications', '#FFFBEB', '#FDE68A') +
    svc_card('rack',     CX_EXT, Y_DS, 'OSRM',         ':5000 · Routing',    '#F0FDF4', '#BBF7D0')
)

# ── Connection helpers ────────────────────────────────────────────────────────
def ln(d, color, sw, dash=''):
    da = f' stroke-dasharray="{dash}"' if dash else ''
    return f'<path d="{d}" stroke="{color}" stroke-width="{sw}" fill="none" stroke-linecap="round" stroke-linejoin="round"{da}/>'

def ar(d, color, sw, dash='', mid=''):
    da = f' stroke-dasharray="{dash}"' if dash else ''
    ma = f' marker-end="url(#{mid})"' if mid else ''
    return f'<path d="{d}" stroke="{color}" stroke-width="{sw}" fill="none" stroke-linecap="round" stroke-linejoin="round"{da}{ma}/>'

def mkr(mid, color):
    return (f'<marker id="{mid}" markerWidth="9" markerHeight="7" refX="8" refY="3.5" orient="auto">'
            f'<polygon points="0 0,9 3.5,0 7" fill="{color}"/></marker>')

DEFS = (mkr('mb','#2563EB') + mkr('mg','#16A34A') +
        mkr('mo','#EA580C') + mkr('mc','#0891B2') + mkr('mp','#7C3AED'))

BL, GR, OR, CY, PU = '#2563EB','#16A34A','#EA580C','#0891B2','#7C3AED'

# ── Key coordinates (from layout constants) ───────────────────────────────────
# GW card center: (316, 515), cw=152 → left=240, right=392
# SVC cards: cx=555, left=479, right=631
# auth card: cx=555, cw=348 → left=381, right=729, cy=195, bottom=249
# DB1: cx=780, left=721, right=839
# DB2(minio): cx=980, left=921
# EXT: cx=1644, left=1568
# Y rows: auth=195, AB=360, DL=515, DS=670, EA=825
# Card half-heights: svc ch=108→54, db ch=90→45

arrows = ''

# ── Blue (HTTP/REST) — bus topology ──────────────────────────────────────────
# Client left bus
arrows += ln('M201,360 H222', BL, 1.8)           # Admin → bus
arrows += ln('M201,670 H222', BL, 1.8)           # Driver → bus
arrows += ln('M222,360 V670', BL, 1.2)           # bus vertical
arrows += ar('M222,515 H240', BL, 2.0, mid='mb') # bus → GW left

# GW right bus
arrows += ln('M392,515 H440',  BL, 2.0)           # GW → bus
arrows += ln('M440,195 V825',  BL, 1.2)           # bus vertical
arrows += ar('M440,195 H381',  BL, 2.0, mid='mb') # → auth
arrows += ar('M440,360 H479',  BL, 2.0, mid='mb') # → AB
arrows += ar('M440,515 H479',  BL, 2.0, mid='mb') # → DL
arrows += ar('M440,670 H479',  BL, 2.0, mid='mb') # → DS
arrows += ar('M440,825 H479',  BL, 2.0, mid='mb') # → EA

# ── Green dashed (Databases) ──────────────────────────────────────────────────
# auth shares DBs — routes exit auth bottom, down then right
arrows += ar('M555,249 V290 H775 V315', GR, 1.4, '8,4', 'mg') # auth→pga top
arrows += ar('M555,249 V300 H795 V625 H780', GR, 1.4, '8,4', 'mg') # auth→pgr top

# Service → DB horizontal
arrows += ar('M631,360 H721', GR, 1.4, '8,4', 'mg')  # AB → pga left
arrows += ar('M631,515 H721', GR, 1.4, '8,4', 'mg')  # DL → pgd left
arrows += ar('M839,515 H921', GR, 1.4, '8,4', 'mg')  # pgd → minio (chain)
arrows += ar('M631,670 H721', GR, 1.4, '8,4', 'mg')  # DS → pgr left

# ── Orange dotted (OAuth2) — left spine ───────────────────────────────────────
arrows += ln('M453,195 V825',  OR, 1.2, '4,4')         # full spine
arrows += ln('M479,360 H453',  OR, 1.2, '4,4')         # AB branch
arrows += ln('M479,515 H453',  OR, 1.2, '4,4')         # DL branch
arrows += ln('M479,670 H453',  OR, 1.2, '4,4')         # DS branch
arrows += ln('M479,825 H453',  OR, 1.2, '4,4')         # EA branch
arrows += ar('M453,195 H381',  OR, 1.4, '4,4', 'mo')   # spine → auth

# ── Cyan dashed (Inter-service) — right routing ───────────────────────────────
arrows += ar('M631,515 H665 V670 H479', CY, 1.4, '8,4', 'mc')  # DL → DS
arrows += ar('M631,515 H672 V825 H479', CY, 1.4, '8,4', 'mc')  # DL → EA

# ── Purple solid (External) ───────────────────────────────────────────────────
# EA → Odoo: exit EA right, clear space right of all DBs, up to Odoo level, right to Odoo
arrows += ar('M631,825 H1200 V360 H1568', PU, 1.8, mid='mp')
# DL → Firebase: exit DL right, go into gap between svc col and DB col, below DBs, across, up
arrows += ar('M631,510 H688 V580 H1568 V569', PU, 1.8, mid='mp')
# DL → OSRM: same gap, slightly lower bypass, to OSRM
arrows += ar('M631,520 H695 V595 H1568 V724', PU, 1.8, mid='mp')

# ── Legend ────────────────────────────────────────────────────────────────────
LY = 972
legend  = rct(20, 952, W-40, 52, '#F9FAFB', '#E5E7EB', sw=1, rx=8)
LITEMS = [(90,BL,'','HTTP / REST'),(360,GR,'8,4','Database'),(590,OR,'4,4','OAuth2 / Security'),
          (790,CY,'8,4','Inter-Service'),(1010,PU,'','External Integration')]
for lx,col,dash,lbl in LITEMS:
    legend += ar(f'M{lx},{LY} H{lx+72}', col, 2.2, dash,
                 {'':'mb','8,4':'mg','4,4':'mo'}.get(dash,'mp') if col!=CY else 'mc')
    legend += txt(lx+84, LY+4, lbl, 10.5, 'normal', '#374151', 'start')

# ── Assemble ──────────────────────────────────────────────────────────────────
svg = f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg"
     xmlns:xlink="http://www.w3.org/1999/xlink"
     width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  <rect width="{W}" height="{H}" fill="white"/>
  {defs}
  {sections}
  {gateway}
  {arrows}
  {nodes}
  {legend}
</svg>"""

with open(OUTPUT, 'w', encoding='utf-8') as f:
    f.write(svg)
print(f"Saved: {OUTPUT}")
