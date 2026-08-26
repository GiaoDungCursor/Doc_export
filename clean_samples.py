import sqlite3
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')

db_path = 'app-data/app.db'
if not os.path.exists(db_path):
    db_path = '../app-data/app.db'

conn = sqlite3.connect(db_path)
cursor = conn.cursor()

cursor.execute("SELECT id, filename, status FROM documents")
rows = cursor.fetchall()
print("CURRENT DOCUMENTS IN DB:")
for r in rows:
    print(f" - {r[0]}: {r[1]} [{r[2]}]")

# Delete sample records
cursor.execute("DELETE FROM documents WHERE filename LIKE '%sample%' OR id LIKE '%sample%'")
cursor.execute("DELETE FROM jobs WHERE document_id NOT IN (SELECT id FROM documents)")
cursor.execute("DELETE FROM document_fields WHERE document_id NOT IN (SELECT id FROM documents)")
conn.commit()
conn.close()
print("Cleaned sample records from SQLite DB successfully.")

docs_dir = 'app-data/documents' if os.path.exists('app-data/documents') else '../app-data/documents'
for f in os.listdir(docs_dir):
    if 'sample' in f.lower():
        try:
            os.remove(os.path.join(docs_dir, f))
            print(f"Deleted sample file: {f}")
        except Exception as e:
            print(f"Error removing {f}: {e}")
