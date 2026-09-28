-- Extra objects for verifying the Explorer tree (views, routines, aggregates, sequences,
-- object types, a second schema). Run after sample-data.sql:
--   psql -d intelladb -U intella -f tools/explorer-objects.sql

CREATE TYPE order_status AS ENUM ('new', 'paid', 'shipped', 'cancelled');
CREATE TYPE money_range AS (low NUMERIC(10,2), high NUMERIC(10,2));
CREATE DOMAIN email_address AS TEXT CHECK (VALUE LIKE '%@%');

CREATE VIEW customer_orders AS
SELECT c.id AS customer_id, c.name, count(o.id) AS order_count
FROM customers c LEFT JOIN orders o ON o.customer_id = c.id
GROUP BY c.id, c.name;

CREATE VIEW products_in_stock AS
SELECT id, name, category, price FROM products WHERE in_stock;

CREATE MATERIALIZED VIEW sales_by_category AS
SELECT p.category, sum(oi.quantity * oi.unit_price) AS revenue
FROM order_items oi JOIN products p ON p.id = oi.product_id
GROUP BY p.category;

CREATE SEQUENCE invoice_number_seq START 1000;

CREATE FUNCTION order_total(p_order_id INT) RETURNS NUMERIC
LANGUAGE sql STABLE AS $$
    SELECT coalesce(sum(quantity * unit_price), 0) FROM order_items WHERE order_id = p_order_id
$$;

CREATE FUNCTION customer_spend(p_customer_id INT, p_since DATE DEFAULT '1970-01-01') RETURNS NUMERIC
LANGUAGE sql STABLE AS $$
    SELECT coalesce(sum(order_total(o.id)), 0) FROM orders o
    WHERE o.customer_id = p_customer_id AND o.order_date >= p_since
$$;

CREATE PROCEDURE restock(p_product_id INT)
LANGUAGE sql AS $$
    UPDATE products SET in_stock = TRUE WHERE id = p_product_id
$$;

CREATE FUNCTION product_of_state(acc NUMERIC, val NUMERIC) RETURNS NUMERIC
LANGUAGE sql IMMUTABLE AS $$ SELECT acc * val $$;

CREATE AGGREGATE product_of(NUMERIC) (SFUNC = product_of_state, STYPE = NUMERIC, INITCOND = '1');

CREATE SCHEMA inventory;

CREATE TABLE inventory.warehouses (
    id   SERIAL PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    city TEXT
);

CREATE TABLE inventory.stock (
    warehouse_id INT NOT NULL REFERENCES inventory.warehouses(id),
    product_id   INT NOT NULL REFERENCES public.products(id),
    quantity     INT NOT NULL DEFAULT 0,
    PRIMARY KEY (warehouse_id, product_id)
);
