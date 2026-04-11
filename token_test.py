import urllib.request
headers = {'Authorization': 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE3NzU4ODEyOTQsImlhdCI6MTc3NTg3NzY5NCwicm9sZSI6IkFETUlOIiwic3ViIjoiMDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAwIn0.Us0qIqHOa69pzghGglWVIi1rA9U26i4F0adYD0znTXg'}
req = urllib.request.Request('http://localhost:8082/api/admin/routes', headers=headers)
print(urllib.request.urlopen(req).read().decode())
