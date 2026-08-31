import httpx
r=httpx.post('http://localhost:8000/clientes', json={'nombre':'Test Borrar UUID','latitud':8.61,'longitud':-71.65})
print('POST',r.status_code)
print(r.text[:500])
data=r.json()
cid=data['id']
print('ID:',cid)
r2=httpx.delete(f'http://localhost:8000/clientes/{cid}')
print('DELETE',r2.status_code, r2.text[:200])
r3=httpx.get(f'http://localhost:8000/clientes/{cid}')
print('GET after delete',r3.status_code)
