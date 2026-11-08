import json, sqlite3, time
from config import DB_PATH


def _conn():
    c = sqlite3.connect(DB_PATH)
    c.row_factory = sqlite3.Row
    c.execute("""CREATE TABLE IF NOT EXISTS results(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        created REAL, source_type TEXT, source TEXT,
        title TEXT, items TEXT, provider TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS downloads(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        created REAL, url TEXT, title TEXT, label TEXT, kind TEXT,
        filename TEXT, path TEXT, size INTEGER, mime TEXT)""")
    return c


def _row(r):
    d = dict(r)
    d["items"] = json.loads(d["items"] or "[]")
    return d


def add(source_type, source, title, items, provider):
    with _conn() as c:
        cur = c.execute(
            "INSERT INTO results(created,source_type,source,title,items,provider) VALUES(?,?,?,?,?,?)",
            (time.time(), source_type, source, title, json.dumps(items, ensure_ascii=False), provider))
        return cur.lastrowid


def get(rid):
    with _conn() as c:
        r = c.execute("SELECT * FROM results WHERE id=?", (rid,)).fetchone()
        return _row(r) if r else None


def search(q="", limit=100):
    with _conn() as c:
        if q:
            like = f"%{q}%"
            rows = c.execute("SELECT * FROM results WHERE title LIKE ? OR items LIKE ? OR source LIKE ? "
                             "ORDER BY id DESC LIMIT ?", (like, like, like, limit)).fetchall()
        else:
            rows = c.execute("SELECT * FROM results ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return [_row(r) for r in rows]


def delete(rid):
    with _conn() as c:
        c.execute("DELETE FROM results WHERE id=?", (rid,))


def clear():
    with _conn() as c:
        c.execute("DELETE FROM results")


# ---------------- downloads
def add_download(url, title, label, kind, filename, path, size, mime):
    with _conn() as c:
        cur = c.execute("INSERT INTO downloads(created,url,title,label,kind,filename,path,size,mime) "
                        "VALUES(?,?,?,?,?,?,?,?,?)", (time.time(), url, title, label, kind, filename, path, size, mime))
        return cur.lastrowid


def get_download(did):
    with _conn() as c:
        r = c.execute("SELECT * FROM downloads WHERE id=?", (did,)).fetchone()
        return dict(r) if r else None


def list_downloads(limit=200):
    with _conn() as c:
        return [dict(r) for r in c.execute("SELECT * FROM downloads ORDER BY id DESC LIMIT ?", (limit,)).fetchall()]


def delete_download(did):
    with _conn() as c:
        c.execute("DELETE FROM downloads WHERE id=?", (did,))
