import httpx, json

r = httpx.post('http://localhost:8080/api/auth/login', json={'username':'admin','password':'admin123'})
t = r.json()['token']
h = {'Authorization': f'Bearer {t}'}

# Test skill create with correct schema
r1 = httpx.post('http://localhost:8080/api/skills', json={
    'name': 'probe-skill',
    'description': 'test',
    'trigger_keywords': [],
    'enabled': True,
}, headers=h, timeout=10)
print('POST /api/skills:', r1.status_code, r1.text[:300])

# Test task create - check what args field to use
r2 = httpx.post('http://localhost:8080/api/tasks/create', json={
    'name': 'probe-task',
    'description': 'test',
    'action': 'log_action',
    'args': {'message': 'test'},
    'schedule_type': 'delay',
    'delay_seconds': 3600,
}, headers=h, timeout=10)
print('POST /api/tasks/create (args):', r2.status_code, r2.text[:300])

# Test project create with 'title'
r3 = httpx.post('http://localhost:8080/api/projects', json={'title': 'E2E probe project'}, headers=h, timeout=10)
print('POST /api/projects (title):', r3.status_code, r3.text[:400])

# Test analytics feedback
r4 = httpx.post('http://localhost:8000/api/analytics/feedback',
    json={'session_id':'x','message_id':'y','rating':'up','username':'admin'},
    headers={'Authorization': f'Bearer {t}'}, timeout=10)
print('POST Python /api/analytics/feedback:', r4.status_code, r4.text[:300])

# Test auth: what does Java /api/memory return without token
r5 = httpx.get('http://localhost:8080/api/memory', timeout=10)
print('GET /api/memory (no auth):', r5.status_code)

# Test GET /spec for project spec
r3j = r3.json() if r3.status_code == 200 else {}
proj_id = r3j.get('project', {}).get('id', '')
if proj_id:
    r6 = httpx.get(f'http://localhost:8080/spec?project_id={proj_id}', headers=h, timeout=10)
    print(f'GET /spec?project_id={proj_id}:', r6.status_code, r6.text[:200])
    r7 = httpx.get(f'http://localhost:8080/tasks?project_id={proj_id}', headers=h, timeout=10)
    print(f'GET /tasks?project_id={proj_id}:', r7.status_code, r7.text[:200])
    httpx.delete(f'http://localhost:8080/api/projects/{proj_id}', headers=h, timeout=10)
