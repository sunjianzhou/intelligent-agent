import httpx, json, sys

r = httpx.post('http://localhost:8080/api/auth/login', json={'username':'admin','password':'admin123'})
t = r.json()['token']
h = {'Authorization': f'Bearer {t}'}

checks = [
    ('GET', '/api/projects', None),
    ('POST', '/api/projects', {'name': 'probe-test'}),
    ('GET', '/api/conversations', None),
    ('GET', '/api/memory', None),
    ('POST', '/api/analytics/feedback', {'session_id':'x','message_id':'y','rating':'up'}),
    ('POST', '/api/roles/activate', {'role_id': 'nonexistent-xyz'}),
]

for method, url, body in checks:
    try:
        if method == 'GET':
            resp = httpx.get(f'http://localhost:8080{url}', headers=h, timeout=10)
        else:
            resp = httpx.post(f'http://localhost:8080{url}', json=body, headers=h, timeout=10)
        try:
            j = json.dumps(resp.json(), ensure_ascii=False)[:300]
        except:
            j = resp.text[:300]
        print(f"{method} {url} → {resp.status_code}: {j}")
    except Exception as e:
        print(f"{method} {url} → ERROR: {e}")
