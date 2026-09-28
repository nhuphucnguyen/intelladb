#!/usr/bin/env python3
"""
Mock OpenAI-compatible AI provider for verifying the Intella DB plugin end-to-end
without real credentials. Logs every request (headers + body) to mock-ai.log so the
plugin's wire format can be inspected. Serves the OpenAI chat-completions contract:

  POST {base}/chat/completions  ->  {"choices":[{"message":{"content": "..."}}]}

Rules for canned answers (deterministic):
  - question mentions "how many"/"most"/"count" + order  -> orders-per-customer SQL
  - question mentions revenue/total/spent                -> revenue-per-customer SQL
  - question mentions top product / popular product      -> best-selling product SQL
  - anything else                                        -> preview customers table
The answer text also reports whether schema context (CREATE TABLE statements) was
found in the system prompt, which proves the plugin passed the live schema.
"""
import json
import os
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("MOCK_AI_PORT", "8931"))
LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mock-ai.log")

ORDERS_SQL = """```sql
SELECT c.name, COUNT(o.id) AS order_count
FROM public.customers c
LEFT JOIN public.orders o ON o.customer_id = c.id
GROUP BY c.name
ORDER BY order_count DESC;
```"""

REVENUE_SQL = """```sql
SELECT c.name, SUM(o.total_amount) AS total_spent
FROM public.customers c
JOIN public.orders o ON o.customer_id = c.id
WHERE o.status <> 'cancelled'
GROUP BY c.name
ORDER BY total_spent DESC
LIMIT 5;
```"""

PRODUCT_SQL = """```sql
SELECT p.name, SUM(oi.quantity) AS units_sold
FROM public.order_items oi
JOIN public.products p ON p.id = oi.product_id
GROUP BY p.name
ORDER BY units_sold DESC
LIMIT 3;
```"""

DEFAULT_SQL = """```sql
SELECT * FROM public.customers ORDER BY id LIMIT 5;
```"""


def pick_answer(question: str) -> str:
    q = question.lower()
    if "revenue" in q or "spent" in q or "total" in q:
        sql, words = REVENUE_SQL, "total spending per customer"
    elif "most" in q or "how many" in q or "count" in q or "orders" in q and "per" in q:
        sql, words = ORDERS_SQL, "the number of orders per customer"
    elif "product" in q:
        sql, words = PRODUCT_SQL, "the best-selling products"
    else:
        sql, words = DEFAULT_SQL, "a preview of the customers table"
    return f"Here is a query for {words}.\n\n{sql}"


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        try:
            payload = json.loads(body)
        except Exception:
            payload = {"raw": body.decode("utf-8", "replace")}
        with open(LOG, "a") as log:
            log.write(f"=== {time.strftime('%Y-%m-%d %H:%M:%S')} POST {self.path}\n")
            log.write(f"Authorization: {self.headers.get('Authorization')}\n")
            log.write(json.dumps(payload, indent=2)[:20000] + "\n")
        content = pick_answer(" ".join(
            str(m.get("content", "")) for m in payload.get("messages", [])
            if m.get("role") == "user"))
        system = " ".join(str(m.get("content", "")) for m in payload.get("messages", [])
                          if m.get("role") == "system")
        if "CREATE TABLE" in system:
            content = "(schema context: received — DDL present in system prompt)\n\n" + content
        else:
            content = "(schema context: NOT received)\n\n" + content
        response = {
            "id": "chatcmpl-mock", "object": "chat.completion", "created": int(time.time()),
            "model": payload.get("model", "mock"),
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": content}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }
        data = json.dumps(response).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        data = b'{"status":"mock ai provider running"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    if os.path.exists(LOG):
        os.remove(LOG)
    server = HTTPServer(("127.0.0.1", PORT), Handler)
    print(f"Mock AI provider listening on http://127.0.0.1:{PORT}/v1 (log: {LOG})", flush=True)
    server.serve_forever()
