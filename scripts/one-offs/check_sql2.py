import sqlite3
con=sqlite3.connect('backend/clientes.db')
row=con.execute('SELECT sql FROM sqlite_master WHERE type="table" AND name="clientes"').fetchone()
print(row[0] if row else 'no table')
