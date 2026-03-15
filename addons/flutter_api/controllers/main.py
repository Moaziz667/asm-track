import json

from odoo import http
from odoo.http import request

class FlutterAPI(http.Controller):
    @http.route('/api/products', type='http', auth='public', methods=['GET', 'OPTIONS'], cors='*')
    def get_products(self, **kwargs):
        # Handle preflight (CORS) requests
        if request.httprequest.method == 'OPTIONS':
            return request.make_response('', headers={
                'Access-Control-Allow-Methods': 'GET, OPTIONS',
                'Access-Control-Allow-Headers': 'Content-Type, Authorization',
            })

        # Pagination + optional search
        try:
            page = int(request.params.get('page', 0))
        except Exception:
            page = 0
        try:
            size = int(request.params.get('size', 20))
        except Exception:
            size = 20
        search = request.params.get('search', '').strip()

        domain = []
        if search:
            domain = ['|', ('name', 'ilike', search), ('default_code', 'ilike', search)]

        total = request.env['product.product'].sudo().search_count(domain)
        products = request.env['product.product'].sudo().search(domain, offset=page * size, limit=size)

        content = [
            {
                'id': p.id,
                'name': p.name,
                'default_code': p.default_code,
                'list_price': p.list_price,
                'qty_available': p.qty_available,
            }
            for p in products
        ]

        total_pages = -(-total // size) if size > 0 else 1  # ceil division
        payload = {
            'content': content,
            'totalPages': total_pages,
            'totalElements': total,
        }

        return request.make_response(json.dumps(payload), headers={
            'Content-Type': 'application/json',
        })

    @http.route('/api/cors', type='http', auth='none', methods=['OPTIONS'], cors='*')
    def handle_cors(self, **kwargs):
        headers = {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        }
        return request.make_response('', headers=headers)