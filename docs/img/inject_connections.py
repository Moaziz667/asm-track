"""
Injects connection arrows (smooth Bézier curves) into the downloaded Figma SVG.
Strategy: cubic Bézier C-commands only — no orthogonal H/V segments.
Card boundaries:

  Admin App     : x=153-285  y=356-457   ctr=(219,407)
  Driver App    : x=155-283  y=529-636   ctr=(219,582)
  API Gateway   : x=404-532  y=401-497   ctr=(468,449)
  auth-server   : x=689-1013 y=147-243   ctr=(851,195)
  AppBackend    : x=755-883  y=285-381   ctr=(819,333)
  DeliveryMS    : x=755-883  y=414-510   ctr=(819,462)
  DriverService : x=755-883  y=543-639   ctr=(819,591)
  ErpAdapter    : x=755-883  y=678-774   ctr=(819,726)
  postgres-app  : x=926-1020 y=334-412   ctr=(973,373)
  postgres-del  : x=926-1020 y=452-530   ctr=(973,491)
  postgres-drv  : x=930-1024 y=576-654   ctr=(977,615)
  MinIO         : x=1165-1259 y=455-533  ctr=(1212,494)
  H2 (injected) : x=926-1020 y=686-762  ctr=(973,724)
  Odoo ERP      : x=1491-1619 y=298-394  ctr=(1555,346)
  Firebase FCM  : x=1491-1619 y=432-528  ctr=(1555,480)
  OSRM          : x=1493-1621 y=566-662  ctr=(1557,614)
"""

SRC = r"C:\Users\M S I\Downloads\container-diagram 1.svg"
DST = r"C:\Users\M S I\OneDrive\Documents\PFE\docs\img\container-diagram.svg"

with open(SRC, encoding='utf-8') as f:
    svg = f.read()

# ── Markers ────────────────────────────────────────────────────────────────────
def mkr(mid, color):
    return (f'<marker id="{mid}" markerWidth="9" markerHeight="7" '
            f'refX="8" refY="3.5" orient="auto">'
            f'<polygon points="0 0,9 3.5,0 7" fill="{color}"/></marker>')

markers = (mkr('mb', '#2563EB') + mkr('mg', '#16A34A') +
           mkr('mc', '#0891B2') + mkr('mp', '#7C3AED'))

BL, GR, CY, PU = '#2563EB', '#16A34A', '#0891B2', '#7C3AED'

# ── Path builder ───────────────────────────────────────────────────────────────
def ar(d, col, sw, dash='', mid=''):
    da = f' stroke-dasharray="{dash}"' if dash else ''
    ma = f' marker-end="url(#{mid})"' if mid else ''
    return (f'<path d="{d}" stroke="{col}" stroke-width="{sw}" fill="none" '
            f'stroke-linecap="round" stroke-linejoin="round"{da}{ma}/>\n')

def hc(x1, y1, x2, y2, col, sw=1.6, dash='', mid=''):
    """Cubic Bézier, horizontal tangents at both ends (left→right arc)."""
    t = abs(x2 - x1) * 0.45
    return ar(f"M{x1},{y1} C{x1+t},{y1} {x2-t},{y2} {x2},{y2}",
              col, sw, dash, mid)

def vc(x1, y1, x2, y2, col, sw=1.6, dash='', mid=''):
    """Cubic Bézier, vertical tangents at both ends (top→bottom arc)."""
    t = abs(y2 - y1) * 0.45
    return ar(f"M{x1},{y1} C{x1},{y1+t} {x2},{y2-t} {x2},{y2}",
              col, sw, dash, mid)

def cb(x1, y1, cx1, cy1, cx2, cy2, x2, y2, col, sw=1.6, dash='', mid=''):
    """Cubic Bézier with explicit control points."""
    return ar(f"M{x1},{y1} C{cx1},{cy1} {cx2},{cy2} {x2},{y2}",
              col, sw, dash, mid)

paths = '<g id="connections" opacity="1">\n'

# ─── Blue — HTTP / REST ────────────────────────────────────────────────────────
# Admin App → API Gateway
paths += hc(285, 407,  404, 449, BL, 2.0, mid='mb')
# Driver App → API Gateway
paths += hc(283, 582,  404, 449, BL, 2.0, mid='mb')
# API Gateway → auth-server  (fan out — spread exit y slightly)
paths += hc(532, 432,  689, 195, BL, 1.8, mid='mb')
# API Gateway → AppBackend
paths += hc(532, 441,  755, 333, BL, 1.8, mid='mb')
# API Gateway → DeliveryMS
paths += hc(532, 449,  755, 462, BL, 1.8, mid='mb')
# API Gateway → DriverService
paths += hc(532, 457,  755, 591, BL, 1.8, mid='mb')
# API Gateway → ErpAdapter
paths += hc(532, 465,  755, 726, BL, 1.8, mid='mb')

# ─── Green dashed — each service → its own DB ──────────────────────────────────
paths += hc(883, 333,  926, 373,  GR, 1.4, '8,4', 'mg')  # AppBackend → pga
paths += hc(883, 462,  926, 491,  GR, 1.4, '8,4', 'mg')  # DeliveryMS → pgd
paths += hc(883, 591,  930, 615,  GR, 1.4, '8,4', 'mg')  # DriverSvc  → pgr
paths += hc(883, 462, 1165, 494,  GR, 1.4, '8,4', 'mg')  # DeliveryMS → MinIO
paths += hc(883, 726,  926, 724,  GR, 1.4, '8,4', 'mg')  # ErpAdapter → H2

# ─── Green dashed — auth-server shared DB reads ────────────────────────────────
# auth bottom-centre → postgres-app top  (gentle down curve)
paths += vc(851, 243,  973, 334, GR, 1.3, '6,4', 'mg')
# auth bottom-right corner → postgres-drv top  (long arc, stays right of cards)
paths += cb(1013, 243,  1013, 450,  977, 450,  977, 576, GR, 1.3, '6,4', 'mg')

# ─── Cyan dashed — inter-service ───────────────────────────────────────────────
# DeliveryMS → DriverService  (adjacent — short vertical)
paths += ar('M819,510 V543', CY, 1.5, '8,4', 'mc')
# DeliveryMS → ErpAdapter  (async outbox — arc right to bypass DriverService)
paths += cb(883, 462,  1065, 462,  1065, 726,  755, 726, CY, 1.5, '6,3', 'mc')
paths += ('<text x="1073" y="596" font-family="Inter,Helvetica,sans-serif" '
          'font-size="8" fill="#0891B2" transform="rotate(-90,1073,596)">'
          'async · outbox</text>\n')

# ─── Purple — external integrations ────────────────────────────────────────────
# ErpAdapter  → Odoo ERP   (up-right S-curve, mid-x pivot)
mid_x = int((883 + 1491) / 2)
paths += cb(883, 726,  mid_x, 726,  mid_x, 346,  1491, 346, PU, 1.8, mid='mp')
# DeliveryMS  → Firebase FCM   (fan — spread exit y)
paths += hc(883, 453,  1491, 480, PU, 1.8, mid='mp')
# DeliveryMS  → OSRM
paths += hc(883, 470,  1493, 614, PU, 1.8, mid='mp')

# ─── H2 embedded card (ErpAdapter's DB) ────────────────────────────────────────
paths += ('<rect x="926" y="686" width="94" height="76" rx="8" '
          'fill="#EFF6FF" stroke="#3B82F6" stroke-width="1.5"/>\n')
paths += ('<text x="973" y="722" text-anchor="middle" '
          'font-family="Inter,Helvetica,sans-serif" '
          'font-size="12" font-weight="bold" fill="#1E40AF">H2</text>\n')
paths += ('<text x="973" y="738" text-anchor="middle" '
          'font-family="Inter,Helvetica,sans-serif" '
          'font-size="9" fill="#64748B">embedded</text>\n')

paths += '</g>\n'

# ── Legend ─────────────────────────────────────────────────────────────────────
legend = '''<g id="legend">
  <rect x="20" y="930" width="1760" height="52" rx="8"
        fill="#F9FAFB" stroke="#E5E7EB" stroke-width="1"/>
  <path d="M60,956 H130"  stroke="#2563EB" stroke-width="2.5" marker-end="url(#mb)"/>
  <text x="142" y="961" font-family="Inter,Helvetica,sans-serif" font-size="11" fill="#374151">HTTP / REST</text>
  <path d="M370,956 H440" stroke="#16A34A" stroke-width="2" stroke-dasharray="8,4" marker-end="url(#mg)"/>
  <text x="452" y="961" font-family="Inter,Helvetica,sans-serif" font-size="11" fill="#374151">Database</text>
  <path d="M680,956 H750" stroke="#0891B2" stroke-width="2" stroke-dasharray="8,4" marker-end="url(#mc)"/>
  <text x="762" y="961" font-family="Inter,Helvetica,sans-serif" font-size="11" fill="#374151">Inter-Service / Async</text>
  <path d="M1060,956 H1130" stroke="#7C3AED" stroke-width="2.5" marker-end="url(#mp)"/>
  <text x="1142" y="961" font-family="Inter,Helvetica,sans-serif" font-size="11" fill="#374151">External Integration</text>
</g>'''

# ── Inject ─────────────────────────────────────────────────────────────────────
if '<defs>' in svg:
    svg = svg.replace('<defs>', f'<defs>{markers}', 1)
else:
    svg = svg.replace('<svg ', '<svg ').replace('>', f'><defs>{markers}</defs>', 1)

svg = svg.replace('</svg>', f'{paths}\n{legend}\n</svg>')
svg = svg.replace('height="1020"', 'height="1000"', 1)
svg = svg.replace('viewBox="0 0 1800 1020"', 'viewBox="0 0 1800 1000"', 1)

with open(DST, 'w', encoding='utf-8') as f:
    f.write(svg)
print(f"Saved: {DST}")
