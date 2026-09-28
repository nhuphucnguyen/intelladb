-- Long INSERT to try the column <-> value highlighting.
-- Put the caret on a column name or on a value and watch its counterpart light up.
INSERT INTO public.order_items
    (order_id, product_id, quantity, unit_price, gift_note, status)
VALUES
    (1, 7, 2, 279.00, 'wrap in silver paper', 'packed'),
    (2, 1, 1, 129.99, 'gift receipt, no prices shown', 'shipped'),
    (3, 2, 5, 39.99, 'check cable kit, HDMI + USBC', 'packed'),
    (2, 4, 1, 189.50, 'ground floor delivery', 'shipped');
