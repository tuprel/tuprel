CREATE TABLE "Product" (
    "id" uuid PRIMARY KEY,
    "name" varchar(120) NOT NULL CHECK (length(btrim("name")) > 0),
    "price" numeric(12, 2) NOT NULL CHECK ("price" >= 0),
    "stock" integer NOT NULL CHECK ("stock" >= 0)
);
