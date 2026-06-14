import httpx, json

r = httpx.post('http://localhost:8080/api/auth/login', json={'username':'admin','password':'admin123'})
t = r.json()['token']
h = {'Authorization': f'Bearer {t}'}

# Create a project first
r1 = httpx.post('http://localhost:8080/api/projects', json={'title': 'probe3'}, headers=h)
proj_id = r1.json().get('project', {}).get('id', 'proj_test')

# Test Python project tasks endpoint directly
py_r = httpx.get(f'http://localhost:8000/api/project/tasks?project_id={proj_id}',
                  headers={'Authorization': f'Bearer {t}'}, timeout=10)
print('Python GET /api/project/tasks:', py_r.status_code, py_r.text[:300])

# Test Python project spec endpoint
py_r2 = httpx.get(f'http://localhost:8000/api/project/spec?project_id={proj_id}',
                    headers={'Authorization': f'Bearer {t}'}, timeout=10)
print('Python GET /api/project/spec:', py_r2.status_code, py_r2.text[:300])

# Test analytics feedback correct fields
py_r3 = httpx.post('http://localhost:8000/api/analytics/feedback',
    json={'session_id':'x','message_id':'y','rating':'up','message':'test msg','response':'test resp'},
    headers={'Authorization': f'Bearer {t}'}, timeout=10)
print('Python POST /api/analytics/feedback (correct fields):', py_r3.status_code, py_r3.text[:300])

# Test task create with action_params (old field name)
r2 = httpx.post('http://localhost:8080/api/tasks/create', json={
    'name': 'probe-task-2',
    'description': 'test',
    'action': 'log_action',
    'action_params': {'message': 'test via action_params'},
    'schedule_type': 'delay',
    'delay_seconds': 3600,
}, headers=h, timeout=10)
print('POST /api/tasks/create (action_params):', r2.status_code, r2.text[:300])

# Cleanup
httpx.delete(f'http://localhost:8080/api/projects/{proj_id}', headers=h)
