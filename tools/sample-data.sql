-- Sample data for Intella DB plugin verification: a small e-commerce store.
CREATE USER intella WITH PASSWORD 'intella123';
CREATE DATABASE intelladb OWNER intella;
\c intelladb intella

CREATE TABLE customers (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    email       TEXT NOT NULL UNIQUE,
    city        TEXT,
    signup_date DATE NOT NULL DEFAULT CURRENT_DATE
);

CREATE TABLE products (
    id        SERIAL PRIMARY KEY,
    name      TEXT NOT NULL,
    category  TEXT NOT NULL,
    price     NUMERIC(10,2) NOT NULL CHECK (price >= 0),
    in_stock  BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE orders (
    id           SERIAL PRIMARY KEY,
    customer_id  INTEGER NOT NULL REFERENCES customers(id),
    order_date   TIMESTAMP NOT NULL DEFAULT NOW(),
    status       TEXT NOT NULL DEFAULT 'new',
    total_amount NUMERIC(10,2)
);

CREATE TABLE order_items (
    id         SERIAL PRIMARY KEY,
    order_id   INTEGER NOT NULL REFERENCES orders(id),
    product_id INTEGER NOT NULL REFERENCES products(id),
    quantity   INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(10,2) NOT NULL
);

CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_products_category ON products(category);

INSERT INTO customers (name, email, city, signup_date) VALUES
 ('Alice Nguyen',   'alice@example.com',   'San Jose',     '2024-01-15'),
 ('Bob Tran',       'bob@example.com',     'San Francisco','2024-02-20'),
 ('Carla Gomez',    'carla@example.com',   'Oakland',      '2024-03-05'),
 ('David Pham',     'david@example.com',   'San Jose',     '2024-05-11'),
 ('Emma Le',        'emma@example.com',    'Berkeley',     '2024-06-30'),
 ('Frank Vu',       'frank@example.com',   'Fremont',      '2024-08-14');

INSERT INTO products (name, category, price, in_stock) VALUES
 ('Mechanical Keyboard', 'electronics', 129.99, TRUE),
 ('USB-C Hub',           'electronics',  39.99, TRUE),
 ('Standing Desk',       'furniture',   349.00, TRUE),
 ('Office Chair',        'furniture',   189.50, TRUE),
 ('Desk Lamp',           'furniture',    24.90, TRUE),
 ('Noise-Cancel Headphones','electronics',199.00, FALSE),
 ('Monitor 27"',         'electronics',  279.00, TRUE);

INSERT INTO orders (customer_id, order_date, status, total_amount) VALUES
 (1, '2025-01-10 10:15:00', 'shipped',  169.98),
 (1, '2025-03-22 14:02:00', 'delivered',349.00),
 (2, '2025-01-18 09:30:00', 'delivered',199.00),
 (3, '2025-02-05 16:45:00', 'shipped',  418.40),
 (4, '2025-02-19 11:20:00', 'new',       39.99),
 (5, '2025-03-01 13:10:00', 'shipped',  279.00),
 (6, '2025-03-15 08:55:00', 'cancelled',189.50),
 (2, '2025-04-02 17:05:00', 'shipped',  304.89);

INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
 (1, 1, 1, 129.99), (1, 2, 1, 39.99),
 (2, 3, 1, 349.00),
 (3, 6, 1, 199.00),
 (4, 4, 2, 189.50), (4, 5, 1, 39.40),
 (5, 2, 1, 39.99),
 (6, 7, 1, 279.00),
 (7, 4, 1, 189.50),
 (8, 1, 1, 129.99), (8, 5, 7, 24.99);
